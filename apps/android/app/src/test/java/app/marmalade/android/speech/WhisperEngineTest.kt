package app.marmalade.android.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digital-twin tests for [WhisperStreamingEngine] — the VAD-segmented
 * simulated-streaming loop moved to :shared, driven end to end through fake
 * [MicCapture]/[SpeechVad]/[OfflineTranscriber] doubles: no AudioRecord, no
 * sherpa natives. Determinism comes from the fakes being pure functions of
 * the samples the consumer feeds them (never test-thread-mutated state) and
 * from the injected clock.
 *
 * Plain JUnit on purpose (no Robolectric): the engine is pure JVM.
 */
class WhisperEngineTest {

    private companion object {
        const val HOP = 1600 // engine's 100ms mic chunk
    }

    /** Mic that plays a script, then optionally stops the engine, then goes silent forever. */
    private class FakeMicCapture(
        private val script: List<FloatArray>,
        private val afterScript: (() -> Unit)? = null,
    ) : MicCapture {
        var closed = false
            private set

        override val hops: Flow<FloatArray> = flow {
            for (hop in script) emit(hop)
            afterScript?.invoke()
            // Endless silence after the script: the stop flag / VAD, not flow
            // exhaustion, must be what ends capture (mirrors a real mic).
            while (true) emit(FloatArray(HOP))
        }

        override fun close() { closed = true }
    }

    /**
     * Deterministic VAD, a pure function of the accepted-sample count (never
     * test-thread-mutated): speech is "detected" while the count is in
     * [speechFromSample, speechUntilSample); one segment (echoing everything
     * accepted so far) completes once [segmentAtSample] samples are in.
     */
    private class FakeVad(
        private val speechFromSample: Int,
        private val speechUntilSample: Int = Int.MAX_VALUE,
        private val segmentAtSample: Int = Int.MAX_VALUE,
    ) : SpeechVad {
        private var accepted = 0
        private val collected = ArrayList<Float>()
        private val segments = ArrayDeque<FloatArray>()
        private var segmentProduced = false
        var resetCalls = 0
            private set

        override fun acceptWaveform(samples: FloatArray) {
            accepted += samples.size
            for (s in samples) collected.add(s)
            if (!segmentProduced && accepted >= segmentAtSample) {
                segments.addLast(collected.toFloatArray())
                collected.clear()
                segmentProduced = true
            }
        }

        override fun isSpeechDetected(): Boolean =
            accepted >= speechFromSample && accepted < speechUntilSample

        override fun empty(): Boolean = segments.isEmpty()
        override fun frontSamples(): FloatArray = segments.first()
        override fun pop() { segments.removeFirst() }
        override fun reset() { resetCalls++ }
    }

    /** Echo transcriber: returns scripted texts in call order (then the last one forever). */
    private class FakeTranscriber(private val texts: List<String>) : OfflineTranscriber {
        val calls = ArrayList<FloatArray>()
        override fun transcribe(samples: FloatArray, sampleRate: Int): String {
            assertEquals(MIC_SAMPLE_RATE, sampleRate)
            calls.add(samples)
            return texts.getOrElse(calls.size - 1) { texts.last() }
        }
    }

    /** Clock that never crosses the 200ms partial gate (advances 1ms per call). */
    private fun slowClock(): () -> Long {
        var t = 0L
        return { ++t }
    }

    /** Clock that crosses the 200ms partial gate on every check (advances 300ms per call). */
    private fun fastClock(): () -> Long {
        var t = 0L
        return { t += 300; t }
    }

    private fun engine(
        transcriber: OfflineTranscriber,
        vad: SpeechVad,
        mic: MicCapture,
        clock: () -> Long = slowClock(),
    ) = WhisperStreamingEngine(
        transcriberProvider = { transcriber },
        vadProvider = { vad },
        openMic = { hop ->
            assertEquals(HOP, hop)
            mic
        },
        nowMs = clock,
    )

    private val speechHop = FloatArray(HOP) { if (it % 2 == 0) 0.5f else -0.5f }

    // ── endpoint mode ────────────────────────────────────────────────────────

    @Test
    fun `endpoint mode - VAD segment completion emits FinalText from segment audio`() = runTest {
        lateinit var eng: WhisperStreamingEngine
        // Speech ends exactly when the segment completes — no spurious restart.
        val vad = FakeVad(speechFromSample = 1, speechUntilSample = 3 * HOP, segmentAtSample = 3 * HOP)
        val transcriber = FakeTranscriber(listOf("hello world"))
        val mic = FakeMicCapture(List(4) { speechHop }, afterScript = { eng.stopStreaming() })
        eng = engine(transcriber, vad, mic)

        val results = eng.startStreaming(enableEndpoint = true, onFlowClosed = null).toList()

        assertEquals(StreamingResult.Ready, results.first())
        assertEquals(StreamingResult.FinalText("hello world"), results.last())
        assertEquals(1, vad.resetCalls)
        assertTrue("mic must be closed when the flow ends", mic.closed)
        // The final transcription came from the VAD's clean segment audio,
        // not the raw rolling buffer: the fake echoes exactly what it accepted.
        assertEquals(1, transcriber.calls.size)
        assertTrue(transcriber.calls.single().size >= 3 * HOP - WhisperStreamingEngine.VAD_WINDOW_SIZE)
    }

    @Test
    fun `endpoint mode - blank segment text emits nothing`() = runTest {
        lateinit var eng: WhisperStreamingEngine
        val vad = FakeVad(speechFromSample = 1, speechUntilSample = 2 * HOP, segmentAtSample = 2 * HOP)
        val transcriber = FakeTranscriber(listOf("   "))
        val mic = FakeMicCapture(List(3) { speechHop }, afterScript = { eng.stopStreaming() })
        eng = engine(transcriber, vad, mic)

        val results = eng.startStreaming(enableEndpoint = true, onFlowClosed = null).toList()

        assertEquals(listOf<StreamingResult>(StreamingResult.Ready), results)
    }

    // ── patient mode ─────────────────────────────────────────────────────────

    @Test
    fun `patient mode - segment becomes a partial, stop joins segment plus flushed tail`() = runTest {
        lateinit var eng: WhisperStreamingEngine
        // One segment mid-stream; speech keeps going after it, so the stop
        // flush must transcribe the tail and join it to the finalized segment.
        val vad = FakeVad(speechFromSample = 1, segmentAtSample = 2 * HOP)
        val transcriber = FakeTranscriber(listOf("first segment", "tail text"))
        val mic = FakeMicCapture(List(4) { speechHop }, afterScript = { eng.stopStreaming() })
        eng = engine(transcriber, vad, mic)

        val results = eng.startStreaming(enableEndpoint = false, onFlowClosed = null).toList()

        assertEquals(StreamingResult.Ready, results.first())
        assertTrue(
            "segment must surface as an accumulated partial, got $results",
            results.contains(StreamingResult.PartialText("first segment")),
        )
        assertEquals(StreamingResult.FinalText("first segment tail text"), results.last())
    }

    @Test
    fun `stop mid-speech flushes in-progress audio as FinalText`() = runTest {
        lateinit var eng: WhisperStreamingEngine
        // Speech detected, but no segment ever completes — only the stop flush
        // path can produce the final text.
        val vad = FakeVad(speechFromSample = 1)
        val transcriber = FakeTranscriber(listOf("flushed tail"))
        val mic = FakeMicCapture(List(3) { speechHop }, afterScript = { eng.stopStreaming() })
        eng = engine(transcriber, vad, mic)

        val results = eng.startStreaming(enableEndpoint = false, onFlowClosed = null).toList()

        assertEquals(StreamingResult.FinalText("flushed tail"), results.last())
        assertTrue("flush must transcribe the buffered speech", transcriber.calls.isNotEmpty())
    }

    @Test
    fun `silence then stop emits only Ready`() = runTest {
        lateinit var eng: WhisperStreamingEngine
        val vad = FakeVad(speechFromSample = Int.MAX_VALUE)
        val transcriber = FakeTranscriber(listOf("never"))
        val mic = FakeMicCapture(emptyList(), afterScript = { eng.stopStreaming() })
        eng = engine(transcriber, vad, mic)

        val results = eng.startStreaming(enableEndpoint = false, onFlowClosed = null).toList()

        assertEquals(listOf<StreamingResult>(StreamingResult.Ready), results)
        assertEquals(0, transcriber.calls.size)
    }

    // ── partial cadence ──────────────────────────────────────────────────────

    @Test
    fun `partials emit while speech is in progress once the interval elapses`() = runTest {
        lateinit var eng: WhisperStreamingEngine
        val vad = FakeVad(speechFromSample = 1)
        val transcriber = FakeTranscriber(listOf("partial text"))
        val mic = FakeMicCapture(List(2) { speechHop }, afterScript = { eng.stopStreaming() })
        eng = engine(transcriber, vad, mic, clock = fastClock())

        val results = eng.startStreaming(enableEndpoint = true, onFlowClosed = null).toList()

        assertTrue(
            "expected a PartialText with the fast clock, got $results",
            results.any { it is StreamingResult.PartialText && it.text == "partial text" },
        )
    }

    // ── mic failure + close signalling ───────────────────────────────────────

    @Test
    fun `mic open failure surfaces as Error and still signals onFlowClosed`() = runTest {
        var flowClosedCalls = 0
        val eng = WhisperStreamingEngine(
            transcriberProvider = { FakeTranscriber(listOf("unused")) },
            vadProvider = { FakeVad(speechFromSample = 1) },
            openMic = { throw MicCaptureException("no mic") },
        )

        val results = eng.startStreaming(
            enableEndpoint = true,
            onFlowClosed = { flowClosedCalls++ },
        ).toList()

        assertEquals(
            listOf<StreamingResult>(StreamingResult.Error("Failed to initialize AudioRecord")),
            results,
        )
        assertEquals(1, flowClosedCalls)
    }

    @Test
    fun `onFlowClosed fires after the mic is closed on the normal path`() = runTest {
        lateinit var eng: WhisperStreamingEngine
        val vad = FakeVad(speechFromSample = Int.MAX_VALUE)
        lateinit var mic: FakeMicCapture
        mic = FakeMicCapture(emptyList(), afterScript = { eng.stopStreaming() })
        var micClosedWhenSignalled = false
        eng = engine(FakeTranscriber(listOf("unused")), vad, mic)

        eng.startStreaming(
            enableEndpoint = true,
            onFlowClosed = { micClosedWhenSignalled = mic.closed },
        ).toList()

        assertTrue("onFlowClosed must fire only after capture.close()", micClosedWhenSignalled)
    }
}
