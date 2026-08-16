package app.marmalade.android.speech

import android.app.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Digital-twin tests for the mic expect/actual seam (KMP increment 3f):
 * the pure PCM conversion the desktop actual uses, and [ServerRecognizer]
 * driven end to end through a fake [MicCapture] — capture → preprocessor →
 * RMS endpointing → mic release → transcription round trip, no AudioRecord.
 *
 * Robolectric is only here for android.util.Base64 (the WAV upload encoding).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class) // repo Robolectric convention: SDK cap 34, plain Application (no EncryptedSharedPreferences boot)
class MicSeamTest {

    // ── pcm16LeToFloats (desktop actual's byte → float path) ────────────────

    @Test
    fun `pcm16LeToFloats decodes little-endian and scales by 32768`() {
        // 0, +32767 (max), -32768 (min), -1 — as LE byte pairs.
        val bytes = byteArrayOf(
            0x00, 0x00,
            0xFF.toByte(), 0x7F,
            0x00, 0x80.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
        )
        val floats = pcm16LeToFloats(bytes, bytes.size)
        assertEquals(4, floats.size)
        assertEquals(0.0f, floats[0], 0.0f)
        assertEquals(32767 / 32768.0f, floats[1], 0.0f)
        assertEquals(-1.0f, floats[2], 0.0f)
        assertEquals(-1 / 32768.0f, floats[3], 0.0f)
    }

    @Test
    fun `pcm16LeToFloats drops a trailing odd byte`() {
        val bytes = byteArrayOf(0x00, 0x08, 0x42)
        val floats = pcm16LeToFloats(bytes, bytes.size)
        assertEquals(1, floats.size)
        assertEquals(0x0800 / 32768.0f, floats[0], 0.0f)
    }

    // ── ServerRecognizer through a fake mic ──────────────────────────────────

    /** 100ms of loud 8kHz square wave — survives the 100Hz high-pass with RMS ≈ 0.5. */
    private val speechHop = FloatArray(1600) { if (it % 2 == 0) 0.5f else -0.5f }
    private val silenceHop = FloatArray(1600)

    private class FakeMicCapture(script: List<FloatArray>) : MicCapture {
        var closed = false
            private set

        override val hops: Flow<FloatArray> = flow {
            for (hop in script) emit(hop)
            // Endless silence after the script: the endpointer, not flow
            // exhaustion, must be what ends capture (mirrors a real mic).
            while (true) emit(FloatArray(1600))
        }

        override fun close() { closed = true }
    }

    @Test
    fun `speech then silence endpoints, releases mic before upload, emits FinalText`() = runTest {
        val fake = FakeMicCapture(List(3) { speechHop })
        var micClosedBeforeTranscribe = false
        var flowClosedCalls = 0
        val recognizer = ServerRecognizer(
            silenceMs = 300L,
            transcribe = { wavBase64 ->
                micClosedBeforeTranscribe = fake.closed
                assertTrue("expected non-empty WAV payload", wavBase64.isNotEmpty())
                "hello world"
            },
            openMic = { fake },
        )

        val results = recognizer.startStreaming(
            enableEndpoint = true,
            onFlowClosed = { flowClosedCalls++ },
        ).toList()

        assertEquals(StreamingResult.Ready, results.first())
        assertEquals(StreamingResult.FinalText("hello world"), results.last())
        assertTrue("mic must be released before the server round trip", micClosedBeforeTranscribe)
        assertTrue("onFlowClosed must fire", flowClosedCalls >= 1)
    }

    @Test
    fun `pure silence idle-times-out with no server round trip`() = runTest {
        val fake = FakeMicCapture(emptyList())
        var transcribeCalled = false
        val recognizer = ServerRecognizer(
            silenceMs = 300L,
            transcribe = { transcribeCalled = true; "" },
            openMic = { fake },
        )

        val results = recognizer.startStreaming(enableEndpoint = true, onFlowClosed = null).toList()

        assertEquals(StreamingResult.Ready, results.first())
        assertEquals(StreamingResult.Error("No speech detected"), results.last())
        assertTrue(fake.closed)
        assertTrue("idle timeout must not upload", !transcribeCalled)
    }

    @Test
    fun `mic open failure surfaces as Error and still signals onFlowClosed`() = runTest {
        var flowClosedCalls = 0
        val recognizer = ServerRecognizer(
            silenceMs = 300L,
            transcribe = { "unused" },
            openMic = { throw MicCaptureException("no mic") },
        )

        val results = recognizer.startStreaming(
            enableEndpoint = true,
            onFlowClosed = { flowClosedCalls++ },
        ).toList()

        assertEquals(listOf<StreamingResult>(StreamingResult.Error("Failed to initialize AudioRecord")), results)
        assertEquals(1, flowClosedCalls)
    }

    @Test
    fun `stopStreaming in patient mode transcribes what was captured`() = runTest {
        lateinit var recognizer: ServerRecognizer
        var closed = false
        val stoppingMic = object : MicCapture {
            // Two speech hops, then request stop mid-stream — patient mode
            // (enableEndpoint = false) must ignore silence ENDED states and
            // finish only on the stop flag, then still upload.
            override val hops: Flow<FloatArray> = flow {
                emit(speechHop)
                emit(speechHop)
                recognizer.stopStreaming()
                while (true) emit(silenceHop)
            }
            override fun close() { closed = true }
        }
        recognizer = ServerRecognizer(
            silenceMs = 300L,
            transcribe = { "patient result" },
            openMic = { stoppingMic },
        )

        val results = recognizer.startStreaming(enableEndpoint = false, onFlowClosed = null).toList()

        assertEquals(StreamingResult.Ready, results.first())
        assertEquals(StreamingResult.FinalText("patient result"), results.last())
        assertTrue(closed)
    }
}
