package app.marmalade.android.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

private const val TAG = "MicCapture"

/**
 * Android [MicCapture]: `AudioRecord(source = VOICE_RECOGNITION)` — the
 * CDD-mandated flat capture path for ASR (no telephony-tuned DSP; do not
 * switch back to `MIC`). With [hardwareEffects], the hardware
 * `NoiseSuppressor`/`AutomaticGainControl` attach to the record session where
 * the device supports them — on the VOICE_RECOGNITION path these effects are
 * the only NS/AGC in the chain.
 *
 * Buffer sizing and the short→float conversion (`sample / 32768f`) are
 * verbatim from the three pre-seam capture loops (WakeWordPipeline,
 * WhisperRecognizer, ServerRecognizer).
 */
actual fun openMicCapture(hopSamples: Int, hardwareEffects: Boolean): MicCapture {
    val minBuf = AudioRecord.getMinBufferSize(
        MIC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    )
    val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MIC_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuf * 2, hopSamples * 2 * 4),
    )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw MicCaptureException("Failed to initialize AudioRecord")
    }

    var noiseSuppressor: NoiseSuppressor? = null
    var agc: AutomaticGainControl? = null
    if (hardwareEffects) {
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true }
        } else null
        agc = if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(record.audioSessionId)?.also { it.enabled = true }
        } else null
        Log.i(
            TAG,
            "Mic effects: noiseSuppressor=${noiseSuppressor?.enabled ?: "unavailable"} " +
                "agc=${agc?.enabled ?: "unavailable"}",
        )
    }

    try {
        record.startRecording()
    } catch (e: Exception) {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        record.release()
        throw MicCaptureException("Failed to start recording", e)
    }
    return AndroidMicCapture(record, noiseSuppressor, agc, hopSamples)
}

private class AndroidMicCapture(
    private val record: AudioRecord,
    private val noiseSuppressor: NoiseSuppressor?,
    private val agc: AutomaticGainControl?,
    private val hopSamples: Int,
) : MicCapture {

    private val closed = AtomicBoolean(false)

    override val hops: Flow<FloatArray> = flow {
        val buffer = ShortArray(hopSamples)
        while (currentCoroutineContext().isActive && !closed.get()) {
            // Blocks in JNI with no suspension point; close() releasing the
            // AudioRecord is what unblocks it (read returns an error code).
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) {
                if (closed.get()) break
                continue
            }
            emit(FloatArray(read) { buffer[it] / 32768.0f })
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        try { record.stop() } catch (_: Exception) {}
        try { record.release() } catch (_: Exception) {}
    }
}
