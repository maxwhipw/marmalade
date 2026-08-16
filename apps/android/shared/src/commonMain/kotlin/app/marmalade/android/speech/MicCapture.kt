package app.marmalade.android.speech

import kotlinx.coroutines.flow.Flow

/** Sample rate every [MicCapture] delivers — the voice stack is 16kHz mono throughout. */
const val MIC_SAMPLE_RATE = 16_000

/** Thrown by [openMicCapture] when the platform microphone can't be opened or started. */
class MicCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The platform mic seam (KMP increment 3f): an already-started 16kHz mono
 * capture yielding fixed-size hops of float PCM in [-1, 1].
 *
 * Contract:
 * - [openMicCapture] returns with the capture LIVE (recording started) or
 *   throws [MicCaptureException] — there is no half-open state, so callers can
 *   signal "listening" (e.g. `StreamingResult.Ready`) as soon as it returns.
 * - [hops] is a cold flow; collect it on whatever dispatcher the consumer's
 *   processing wants (the seam adds no `flowOn`, preserving each caller's
 *   existing threading). A hop is normally `hopSamples` long but may be
 *   shorter on a partial read — consumers already size their work off the
 *   actual array length.
 * - [close] is idempotent and callable from another coroutine; it unblocks a
 *   pending platform read and ends the flow.
 *
 * Hop size is a parameter, not hardcoded 80ms: the wake pipeline reads
 * 1280-sample (80ms) hops, the STT capture loops read 1600-sample (100ms)
 * chunks, and preserving each loop's exact timing is worth more than a single
 * canonical hop.
 */
interface MicCapture : AutoCloseable {
    val hops: Flow<FloatArray>
}

/**
 * Opens the platform mic and starts capture.
 *
 * @param hopSamples samples per emitted hop at [MIC_SAMPLE_RATE].
 * @param hardwareEffects request platform capture cleanup — on Android this
 *   attaches the hardware `NoiseSuppressor`/`AutomaticGainControl` to the
 *   record session when the device supports them (the STT chain wants this;
 *   the wake pipeline does not). Desktop has no equivalent and ignores it.
 * @throws MicCaptureException when the mic can't be opened or started.
 */
expect fun openMicCapture(hopSamples: Int, hardwareEffects: Boolean = false): MicCapture

/**
 * Converts little-endian 16-bit PCM bytes to floats in [-1, 1] using the same
 * `sample / 32768f` scaling as the Android short→float path. `byteCount` may
 * be odd on a truncated read; the trailing odd byte is dropped.
 */
fun pcm16LeToFloats(bytes: ByteArray, byteCount: Int): FloatArray {
    val samples = byteCount / 2
    return FloatArray(samples) { i ->
        val lo = bytes[2 * i].toInt() and 0xff
        val hi = bytes[2 * i + 1].toInt() shl 8
        (hi or lo).toShort() / 32768.0f
    }
}
