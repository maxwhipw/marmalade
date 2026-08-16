package app.marmalade.android.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Simulated streaming STT: offline Whisper transcription behind [OfflineTranscriber]
 * + Silero VAD behind [SpeechVad], fed from the platform mic seam ([openMicCapture]).
 *
 * This is `WhisperRecognizer`'s capture/segmentation loop moved to :shared
 * (KMP conversion, follows the inc-3f mic seam; unblocked by the 2026-07-24
 * sherpa desktop spike). The Android `WhisperRecognizer` is now a thin shell:
 * it owns sherpa model/VAD lifecycle and delegates streaming here.
 *
 * Architecture (unchanged from the pre-move loop): Producer coroutine collects
 * mic hops in 100ms chunks, preprocesses them ([AudioPreprocessor] high-pass;
 * hardware NS/AGC live inside the platform mic actual), and sends via Channel
 * -> Consumer coroutine feeds VAD in 512-sample windows -> speech onset with
 * 0.4s backtrack -> partial re-transcription every ~200ms -> VAD segment
 * completion triggers final transcription from clean segment audio -> buffer
 * reset for next utterance.
 *
 * @param transcriberProvider invoked at collection start; a throwing provider
 *   (model load failure) propagates as a flow error, matching the pre-move
 *   behavior of `getOrCreateRecognizer()` inside the flow body.
 * @param vadProvider same contract; the engine calls [SpeechVad.reset] itself.
 * @param openMic injectable mic factory (digital-twin tests feed synthetic
 *   hops through a fake); production default is the platform seam with
 *   hardware effects requested — the STT chain wants NS/AGC.
 * @param nowMs injectable clock for the partial-result cadence (tests only).
 * @param logError sink for the streaming-loop catch (Android shell passes Log.e).
 */
class WhisperStreamingEngine(
    private val transcriberProvider: () -> OfflineTranscriber,
    private val vadProvider: () -> SpeechVad,
    private val openMic: (hopSamples: Int) -> MicCapture = { hop ->
        openMicCapture(hopSamples = hop, hardwareEffects = true)
    },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
) {

    companion object {
        const val VAD_WINDOW_SIZE = 512 // ~32ms at 16kHz
        private const val SAMPLE_RATE = MIC_SAMPLE_RATE
        private const val BACKTRACK_SAMPLES = 6400 // 0.4s at 16kHz
        private const val MIC_CHUNK_SAMPLES = 1600 // 100ms at 16kHz
        private const val PARTIAL_INTERVAL_MS = 200L
    }

    /** Control-flow signal: stop collecting mic hops (same idiom as ServerRecognizer). */
    private class CaptureDone : Exception()

    @Volatile
    private var stopRequested = false

    /**
     * Start streaming recognition. Returns a Flow that emits the current
     * transcription as the user speaks. Mic ownership is the CALLER's
     * responsibility; [onFlowClosed] is the precise "mic released" signal.
     *
     * @param enableEndpoint If true, VAD speech-end triggers FinalText emission.
     *                       If false (patient listening), speech accumulates until
     *                       [stopStreaming] is called.
     */
    fun startStreaming(
        enableEndpoint: Boolean,
        onFlowClosed: (() -> Unit)?,
    ): Flow<StreamingResult> = flow {
        stopRequested = false

        // Deliberately outside the try: a model-load failure propagates as a
        // flow error (pre-move parity), not a StreamingResult.Error.
        val transcriber = transcriberProvider()
        val vad = vadProvider()
        vad.reset()

        val capture = try {
            openMic(MIC_CHUNK_SAMPLES)
        } catch (e: MicCaptureException) {
            logError("Failed to open mic for Whisper streaming", e)
            emit(StreamingResult.Error("Failed to initialize AudioRecord"))
            onFlowClosed?.invoke()
            return@flow
        }

        // Software preprocessing (always on): high-pass for DC/rumble/mains hum.
        // Hardware NS/AGC ride the mic actual (hardwareEffects = true).
        val preprocessor = AudioPreprocessor(sampleRate = SAMPLE_RATE)

        // Channel decouples mic reading (IO) from VAD+recognition (Default)
        // Matches sherpa-onnx SherpaOnnxSimulateStreamingAsr example exactly
        val samplesChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)
        val micScope = CoroutineScope(Dispatchers.IO)

        try {
            emit(StreamingResult.Ready)

            // Producer: collect mic hops, preprocess, send to channel
            micScope.launch {
                try {
                    capture.hops.collect { raw ->
                        if (stopRequested) throw CaptureDone()
                        samplesChannel.send(preprocessor.process(raw))
                    }
                } catch (_: CaptureDone) { /* stopStreaming() — flush below */ }
                samplesChannel.send(FloatArray(0)) // signal stop
            }

            // Consumer: VAD segmentation + Whisper recognition
            // Core loop ported from SherpaOnnxSimulateStreamingAsr Home.kt
            var buffer = arrayListOf<Float>()
            var offset = 0
            val windowSize = VAD_WINDOW_SIZE
            var isSpeechStarted = false
            var startTime = nowMs()
            var lastText = ""
            var speechStartOffset = 0
            // Patient mode: accumulate finalized segments, only emit FinalText on stopStreaming()
            val finalizedSegments = mutableListOf<String>()

            for (s in samplesChannel) {
                if (s.isEmpty()) break

                buffer.addAll(s.toList())

                // Feed VAD in windowSize chunks
                while (offset + windowSize < buffer.size) {
                    vad.acceptWaveform(
                        buffer.subList(offset, offset + windowSize).toFloatArray()
                    )
                    offset += windowSize

                    // Detect speech start
                    if (!isSpeechStarted && vad.isSpeechDetected()) {
                        isSpeechStarted = true
                        speechStartOffset = offset - BACKTRACK_SAMPLES
                        if (speechStartOffset < 0) speechStartOffset = 0
                        startTime = nowMs()
                    }
                }

                // Partial results (every ~200ms while speech detected)
                val elapsed = nowMs() - startTime
                if (isSpeechStarted && elapsed > PARTIAL_INTERVAL_MS) {
                    lastText = transcriber.transcribe(
                        buffer.subList(speechStartOffset, offset).toFloatArray(),
                        SAMPLE_RATE,
                    )
                    if (lastText.isNotBlank()) {
                        // In patient mode, prepend already-finalized segments
                        val displayText = if (finalizedSegments.isNotEmpty()) {
                            finalizedSegments.joinToString(" ") + " " + lastText
                        } else {
                            lastText
                        }
                        emit(StreamingResult.PartialText(displayText))
                    }
                    startTime = nowMs()
                }

                // VAD completed segment
                while (!vad.empty()) {
                    val segmentText = transcriber.transcribe(vad.frontSamples(), SAMPLE_RATE)

                    isSpeechStarted = false
                    vad.pop()

                    // Reset buffer completely
                    buffer = arrayListOf()
                    offset = 0

                    if (segmentText.isNotBlank()) {
                        if (enableEndpoint) {
                            emit(StreamingResult.FinalText(segmentText))
                        } else {
                            // Patient mode: accumulate, show combined partial
                            finalizedSegments.add(segmentText)
                            emit(StreamingResult.PartialText(
                                finalizedSegments.joinToString(" ")
                            ))
                        }
                    }
                }
            }

            // stopStreaming() called — flush everything
            if (isSpeechStarted && offset > speechStartOffset) {
                // Transcribe any in-progress speech
                val text = transcriber.transcribe(
                    buffer.subList(speechStartOffset, offset).toFloatArray(),
                    SAMPLE_RATE,
                )
                if (text.isNotBlank()) {
                    finalizedSegments.add(text)
                }
            }
            // Emit all accumulated text as final
            if (finalizedSegments.isNotEmpty()) {
                emit(StreamingResult.FinalText(finalizedSegments.joinToString(" ")))
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                logError("Whisper streaming recognition error", e)
                emit(StreamingResult.Error(e.message ?: "Recognition failed"))
            }
        } finally {
            micScope.cancel()
            samplesChannel.close()
            capture.close()
            try { onFlowClosed?.invoke() } catch (e: Exception) {
                logError("onFlowClosed callback failed", e)
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Request the streaming loop to stop and emit a final transcription.
     * The flow will complete after processing the final result.
     */
    fun stopStreaming() {
        stopRequested = true
    }
}
