package app.marmalade.android.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Software mic-input preprocessing between AudioRecord and the STT pipeline
 * (VAD + Whisper). Pure Kotlin/JVM — unit-testable offline with synthesized
 * PCM (the "digital twin" for this stage; hardware effects like
 * NoiseSuppressor can't run off-device).
 *
 * Current chain: a single 2nd-order Butterworth high-pass biquad (RBJ cookbook
 * coefficients, transposed direct form II). At the default 100 Hz cutoff it
 * removes DC offset, handling rumble, wind thump, and 50/60 Hz mains hum —
 * all below the speech band, all known to inflate Whisper's mel features and
 * to hold Silero VAD open on non-speech energy.
 *
 * Stateful across chunks: feed consecutive mic chunks to the same instance so
 * the filter is continuous across chunk boundaries; call [reset] between
 * recording sessions.
 *
 * Not thread-safe — call from the single audio-producer coroutine.
 */
class AudioPreprocessor(
    sampleRate: Int = 16000,
    cutoffHz: Double = 100.0,
) {
    // Normalized biquad coefficients (a0 divided out).
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double

    // Transposed direct form II state.
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        val omega = 2.0 * PI * cutoffHz / sampleRate
        val cosw = cos(omega)
        val alpha = sin(omega) / (2.0 * BUTTERWORTH_Q)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosw) / 2.0) / a0
        b1 = (-(1.0 + cosw)) / a0
        b2 = ((1.0 + cosw) / 2.0) / a0
        a1 = (-2.0 * cosw) / a0
        a2 = (1.0 - alpha) / a0
    }

    /**
     * Filter one chunk of mono float PCM **in place** (avoids per-100ms-chunk
     * allocation on the hot path). Returns the same array for call-site
     * convenience.
     */
    fun process(samples: FloatArray): FloatArray {
        for (i in samples.indices) {
            val x = samples[i].toDouble()
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            samples[i] = y.toFloat()
        }
        return samples
    }

    /** Clear filter state. Call between recording sessions. */
    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        /** Q for a maximally-flat (Butterworth) 2nd-order response. */
        private const val BUTTERWORTH_Q = 0.70710678
    }
}
