package app.marmalade.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline digital-twin tests for the mic-input preprocessing stage: synthesized
 * PCM in, signal-property assertions out. Guards against regressions in the
 * only preprocessing component that can run off-device (hardware
 * NoiseSuppressor/AGC can't be exercised in JVM tests; real-world accuracy is
 * verified on-device by daily use).
 */
class AudioPreprocessorTest {

    private val sampleRate = 16000

    private fun sine(freqHz: Double, seconds: Double, amplitude: Float = 0.5f): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { (amplitude * sin(2.0 * PI * freqHz * it / sampleRate)).toFloat() }
    }

    private fun rms(samples: FloatArray, from: Int = 0): Double {
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (samples.size - from))
    }

    /** Gain in dB measured on the steady-state second half of the signal. */
    private fun steadyStateGainDb(freqHz: Double): Double {
        val input = sine(freqHz, seconds = 1.0)
        val inputRms = rms(input, from = input.size / 2)
        val output = AudioPreprocessor(sampleRate).process(input)
        val outputRms = rms(output, from = output.size / 2)
        return 20.0 * log10(outputRms / inputRms)
    }

    @Test
    fun `mains hum at 50Hz is strongly attenuated`() {
        // 2nd-order Butterworth HPF @100Hz: theoretical -12.3 dB at 50 Hz.
        assertTrue("50Hz gain should be < -11 dB", steadyStateGainDb(50.0) < -11.0)
    }

    @Test
    fun `60Hz hum is attenuated`() {
        assertTrue("60Hz gain should be < -8 dB", steadyStateGainDb(60.0) < -8.0)
    }

    @Test
    fun `speech band passes at unity`() {
        for (freq in listOf(300.0, 1000.0, 3000.0)) {
            val gainDb = steadyStateGainDb(freq)
            assertTrue("${freq}Hz gain $gainDb dB should be within ±0.5 dB", abs(gainDb) < 0.5)
        }
    }

    @Test
    fun `dc offset is removed`() {
        val input = FloatArray(sampleRate) { 0.1f } // 1s of pure DC
        val output = AudioPreprocessor(sampleRate).process(input)
        assertTrue("DC should decay to ~0", rms(output, from = output.size / 2) < 1e-4)
    }

    @Test
    fun `hum plus speech tone leaves the tone`() {
        val n = sampleRate // 1s
        val tone = sine(1000.0, 1.0, amplitude = 0.3f)
        val mixed = FloatArray(n) {
            tone[it] + (0.3 * sin(2.0 * PI * 50.0 * it / sampleRate)).toFloat()
        }
        val out = AudioPreprocessor(sampleRate).process(mixed)
        // Residual vs the clean tone, steady state: hum (equal RMS to the tone
        // pre-filter) should be knocked down to a small fraction.
        var errSum = 0.0
        for (i in n / 2 until n) {
            val e = (out[i] - tone[i]).toDouble()
            errSum += e * e
        }
        val errRms = sqrt(errSum / (n / 2))
        val toneRms = rms(tone, from = n / 2)
        assertTrue(
            "residual after de-humming should be well below the tone (got ${errRms / toneRms})",
            errRms < toneRms * 0.35,
        )
    }

    @Test
    fun `silence stays silent`() {
        val output = AudioPreprocessor(sampleRate).process(FloatArray(1600))
        assertEquals(0.0, rms(output), 0.0)
    }

    @Test
    fun `full-scale input stays bounded and finite`() {
        val input = sine(500.0, 1.0, amplitude = 1.0f)
        val output = AudioPreprocessor(sampleRate).process(input)
        for (s in output) {
            assertTrue("sample $s should be finite", s.isFinite())
            // A 2nd-order HPF overshoots slightly on the onset transient
            // (~8% for a full-scale sine switched on at t=0); downstream
            // consumers take unbounded floats, so bound "no blow-up", not [-1,1].
            assertTrue("sample $s should not blow up", abs(s) <= 1.2f)
        }
    }

    @Test
    fun `chunked processing equals whole-buffer processing`() {
        // The live path feeds 100ms (1600-sample) chunks; filter state must be
        // continuous across chunk boundaries.
        val whole = sine(220.0, 1.0)
        val chunked = whole.copyOf()

        val wholeOut = AudioPreprocessor(sampleRate).process(whole.copyOf())

        val chunkedProcessor = AudioPreprocessor(sampleRate)
        val chunkSize = 1600
        var offset = 0
        while (offset < chunked.size) {
            val end = minOf(offset + chunkSize, chunked.size)
            val chunk = chunked.copyOfRange(offset, end)
            chunkedProcessor.process(chunk).copyInto(chunked, offset)
            offset = end
        }

        for (i in wholeOut.indices) {
            assertEquals("sample $i", wholeOut[i], chunked[i], 1e-6f)
        }
    }

    @Test
    fun `reset makes runs reproducible`() {
        val input = sine(150.0, 0.5)
        val processor = AudioPreprocessor(sampleRate)
        val first = processor.process(input.copyOf())
        processor.reset()
        val second = processor.process(input.copyOf())
        for (i in first.indices) {
            assertEquals("sample $i", first[i], second[i], 0.0f)
        }
    }
}
