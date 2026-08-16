package app.marmalade.android.speech

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream

// ── Pure endpointing + WAV encode (digital-twin testable) ────────────────────

/**
 * RMS-based utterance endpointing for the server-STT fallback.
 *
 * [ServerRecognizer] exists for when the on-device sherpa-onnx stack is the
 * thing that failed, so it deliberately depends on NO native code — endpointing
 * is a plain RMS gate (the same shape as the upstream desktop's WebAudio VAD in
 * use-mic-recorder.ts: speech threshold + trailing-silence window + idle
 * timeout), not Silero.
 *
 * Feed one RMS value per audio chunk; the returned [State] drives the caller:
 * WAITING (no speech yet), SPEAKING, ENDED (speech happened, then [silenceMs]
 * of quiet — transcribe now), IDLE_TIMEOUT (no speech for [idleTimeoutMs] —
 * give up without a round trip).
 */
class RmsEndpointer(
    private val speechThreshold: Float = SPEECH_RMS_THRESHOLD,
    private val silenceMs: Long,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    enum class State { WAITING, SPEAKING, ENDED, IDLE_TIMEOUT }

    companion object {
        /** Desktop uses 0.075 on its analyser scale; raw PCM RMS with the
         *  hardware AGC boost lands lower for normal speech, so 0.05. */
        const val SPEECH_RMS_THRESHOLD = 0.05f

        /** No speech at all after this long → stop listening (desktop's
         *  idleSilenceMs is 12s; same). */
        const val IDLE_TIMEOUT_MS = 12_000L
    }

    private var speechStarted = false
    private var silenceRunMs = 0L
    private var totalMs = 0L

    /** Advance by one chunk of [chunkMs] audio whose RMS (0–1) is [rms]. */
    fun feed(rms: Float, chunkMs: Long): State {
        totalMs += chunkMs
        if (rms >= speechThreshold) {
            speechStarted = true
            silenceRunMs = 0
            return State.SPEAKING
        }
        if (!speechStarted) {
            return if (totalMs >= idleTimeoutMs) State.IDLE_TIMEOUT else State.WAITING
        }
        silenceRunMs += chunkMs
        return if (silenceRunMs >= silenceMs) State.ENDED else State.SPEAKING
    }
}

/** RMS (0–1) of a float PCM chunk. */
fun pcmRms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (s in samples) sum += s * s
    return kotlin.math.sqrt(sum / samples.size).toFloat()
}

/** Wrap mono 16-bit PCM in a WAV container (44-byte RIFF header + data). */
fun pcmToWav(samples: ShortArray, sampleRate: Int): ByteArray {
    val dataLen = samples.size * 2
    val out = ByteArrayOutputStream(44 + dataLen)
    fun le16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
    fun le32(v: Int) { le16(v and 0xffff); le16((v shr 16) and 0xffff) }
    out.write("RIFF".toByteArray(Charsets.US_ASCII)); le32(36 + dataLen)
    out.write("WAVE".toByteArray(Charsets.US_ASCII))
    out.write("fmt ".toByteArray(Charsets.US_ASCII)); le32(16)
    le16(1)                    // PCM
    le16(1)                    // mono
    le32(sampleRate)
    le32(sampleRate * 2)       // byte rate
    le16(2)                    // block align
    le16(16)                   // bits per sample
    out.write("data".toByteArray(Charsets.US_ASCII)); le32(dataLen)
    for (s in samples) le16(s.toInt() and 0xffff)
    return out.toByteArray()
}

// ── The recognizer ───────────────────────────────────────────────────────────

/**
 * Server-STT fallback recognizer: capture + RMS endpointing on-device, the
 * transcription itself on the daemon (`audio.transcribe`). Used by the voice
 * popup ONLY when [WhisperRecognizer] errored and the daemon advertises the
 * "transcription" feature — a round trip per utterance and no partial text is
 * the accepted degraded mode (so exit phrases and patient-mode termination
 * words can't match mid-utterance here; the mic button still works).
 *
 * Mirrors WhisperRecognizer's capture chain (VOICE_RECOGNITION source,
 * hardware NS/AGC when available, AudioPreprocessor high-pass) so the audio
 * the server sees is the same audio Whisper-on-device would have seen — both
 * now via the shared mic seam ([openMicCapture], KMP increment 3f). Mic
 * ownership stays the CALLER's job, exactly like WhisperRecognizer —
 * [onFlowClosed] is the "mic released" signal.
 *
 * @param openMic injectable mic factory (digital-twin tests feed synthetic
 *   hops through a fake); production default is the platform seam with
 *   hardware effects requested.
 */
class ServerRecognizer(
    private val silenceMs: Long,
    private val transcribe: suspend (wavBase64: String) -> String,
    private val openMic: (hopSamples: Int) -> MicCapture = { hop ->
        openMicCapture(hopSamples = hop, hardwareEffects = true)
    },
) : StreamingRecognizer {

    companion object {
        private const val TAG = "ServerRecognizer"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_MS = 100L
        private const val CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()

        /** Utterance hard cap — bounds the upload (~2.9 MB WAV at 90s). */
        private const val MAX_UTTERANCE_MS = 90_000L
    }

    /** Control-flow signal: stop collecting mic hops and move to transcription. */
    private class CaptureDone : Exception()

    @Volatile private var stopRequested = false

    override fun warmup() { /* nothing to load — the model lives on the daemon */ }

    override fun startStreaming(
        enableEndpoint: Boolean,
        onFlowClosed: (() -> Unit)?,
    ): Flow<StreamingResult> = flow {
        stopRequested = false
        val capture = try {
            openMic(CHUNK_SAMPLES)
        } catch (e: MicCaptureException) {
            Log.e(TAG, "Failed to open mic for server-STT capture", e)
            emit(StreamingResult.Error("Failed to initialize AudioRecord"))
            onFlowClosed?.invoke()
            return@flow
        }
        val preprocessor = AudioPreprocessor(sampleRate = SAMPLE_RATE)
        val endpointer = RmsEndpointer(silenceMs = silenceMs)
        val pcm = ArrayList<Short>(SAMPLE_RATE * 30)

        try {
            emit(StreamingResult.Ready)
            Log.i(TAG, "Server-STT capture started (silenceMs=$silenceMs, endpoint=$enableEndpoint)")

            var elapsedMs = 0L
            var idleTimedOut = false
            try {
                capture.hops.collect { raw ->
                    // stopStreaming() means "finish and transcribe what we
                    // have" — same exit as the pre-seam loop's while guard.
                    if (stopRequested) throw CaptureDone()
                    val floats = preprocessor.process(raw)
                    for (f in floats) pcm.add((f.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                    elapsedMs += CHUNK_MS
                    when (endpointer.feed(pcmRms(floats), CHUNK_MS)) {
                        RmsEndpointer.State.ENDED -> if (enableEndpoint) throw CaptureDone()
                        RmsEndpointer.State.IDLE_TIMEOUT -> if (enableEndpoint) {
                            idleTimedOut = true
                            throw CaptureDone()
                        }
                        else -> { /* keep capturing */ }
                    }
                    if (elapsedMs >= MAX_UTTERANCE_MS) throw CaptureDone()
                }
            } catch (_: CaptureDone) { /* fall through to transcription */ }

            if (idleTimedOut) {
                emit(StreamingResult.Error("No speech detected"))
                return@flow
            }

            // Release the mic BEFORE the (slow) server round trip — in the
            // popup flow the reply TTS follows, and holding the mic through
            // it would block the next listen's handoff. close() is
            // idempotent, so the finally below stays a safe backstop.
            capture.close()
            onFlowClosed?.invoke()

            val samples = pcm.toShortArray()
            if (samples.isEmpty()) {
                emit(StreamingResult.Error("No speech detected"))
                return@flow
            }
            val wavBase64 = Base64.encodeToString(pcmToWav(samples, SAMPLE_RATE), Base64.NO_WRAP)
            Log.i(TAG, "Uploading ${samples.size / SAMPLE_RATE}s utterance for server transcription")
            val transcript = transcribe(wavBase64).trim()
            if (transcript.isEmpty()) {
                emit(StreamingResult.Error("Server transcription returned no text"))
            } else {
                emit(StreamingResult.FinalText(transcript))
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.e(TAG, "Server-STT fallback failed", e)
                emit(StreamingResult.Error(e.message ?: "Server transcription failed"))
            }
        } finally {
            capture.close()
            onFlowClosed?.invoke()
        }
    }.flowOn(Dispatchers.IO)

    override fun stopStreaming() {
        stopRequested = true
    }

    override fun release() { /* no native resources */ }
}
