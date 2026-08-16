package app.marmalade.android.speech

import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Desktop [MicCapture]: `javax.sound.sampled.TargetDataLine` on the default
 * mixer, 16kHz mono signed 16-bit little-endian. Not yet wired to an app —
 * like `PropertiesFileStore`, it exists so desktopMain compiles the seam and
 * gives the future desktop client a working default. If TargetDataLine proves
 * unreliable on PipeWire boxes, the desktop-client plan's Phase-0 fallback is
 * a `parec`/`pw-record` subprocess behind this same interface.
 *
 * [hardwareEffects] is an Android audiofx concept with no desktop equivalent;
 * it is accepted and ignored.
 */
actual fun openMicCapture(hopSamples: Int, hardwareEffects: Boolean): MicCapture {
    val format = AudioFormat(MIC_SAMPLE_RATE.toFloat(), 16, 1, true, false)
    val line: TargetDataLine = try {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        (AudioSystem.getLine(info) as TargetDataLine).also {
            it.open(format, maxOf(it.bufferSize, hopSamples * 2 * 4))
            it.start()
        }
    } catch (e: Exception) {
        throw MicCaptureException("Failed to open desktop mic line", e)
    }
    return DesktopMicCapture(line, hopSamples)
}

private class DesktopMicCapture(
    private val line: TargetDataLine,
    private val hopSamples: Int,
) : MicCapture {

    private val closed = AtomicBoolean(false)

    override val hops: Flow<FloatArray> = flow {
        val bytes = ByteArray(hopSamples * 2)
        while (currentCoroutineContext().isActive && !closed.get()) {
            // Blocks until the requested bytes are available or the line is
            // closed/stopped (then returns what it has, possibly 0).
            val read = line.read(bytes, 0, bytes.size)
            if (read <= 0) {
                if (closed.get()) break
                continue
            }
            emit(pcm16LeToFloats(bytes, read))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { line.stop() } catch (_: Exception) {}
        try { line.close() } catch (_: Exception) {}
    }
}
