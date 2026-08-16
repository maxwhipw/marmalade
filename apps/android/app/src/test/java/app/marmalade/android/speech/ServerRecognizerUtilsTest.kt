package app.marmalade.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digital-twin tests for the server-STT fallback's pure pieces: the RMS
 * endpointer (the fallback deliberately uses NO native VAD — sherpa-onnx is
 * the thing that failed) and the WAV container the daemon's `audio.transcribe`
 * receives.
 */
class ServerRecognizerUtilsTest {

    private fun endpointer(silenceMs: Long = 900L) = RmsEndpointer(silenceMs = silenceMs)

    // ── RmsEndpointer ────────────────────────────────────────────────────────

    @Test
    fun `quiet before any speech stays WAITING`() {
        val e = endpointer()
        repeat(20) { assertEquals(RmsEndpointer.State.WAITING, e.feed(0.01f, 100)) }
    }

    @Test
    fun `speech then sustained silence ends the utterance`() {
        val e = endpointer(silenceMs = 900L)
        repeat(5) { assertEquals(RmsEndpointer.State.SPEAKING, e.feed(0.2f, 100)) }
        // 800ms of silence — not yet past the window.
        repeat(8) { assertEquals(RmsEndpointer.State.SPEAKING, e.feed(0.01f, 100)) }
        assertEquals(RmsEndpointer.State.ENDED, e.feed(0.01f, 100))
    }

    @Test
    fun `a pause shorter than the window does not end the utterance`() {
        val e = endpointer(silenceMs = 900L)
        e.feed(0.2f, 100)
        repeat(8) { e.feed(0.01f, 100) } // 800ms pause
        // Speech resumes — the silence run resets.
        assertEquals(RmsEndpointer.State.SPEAKING, e.feed(0.2f, 100))
        repeat(8) { assertEquals(RmsEndpointer.State.SPEAKING, e.feed(0.01f, 100)) }
        assertEquals(RmsEndpointer.State.ENDED, e.feed(0.01f, 100))
    }

    @Test
    fun `no speech at all times out idle`() {
        val e = RmsEndpointer(silenceMs = 900L, idleTimeoutMs = 2_000L)
        repeat(19) { assertEquals(RmsEndpointer.State.WAITING, e.feed(0.01f, 100)) }
        assertEquals(RmsEndpointer.State.IDLE_TIMEOUT, e.feed(0.01f, 100))
    }

    // ── pcmRms ───────────────────────────────────────────────────────────────

    @Test
    fun `rms of silence is zero and of a full-scale square wave is one`() {
        assertEquals(0f, pcmRms(FloatArray(160)), 1e-6f)
        assertEquals(1f, pcmRms(FloatArray(160) { if (it % 2 == 0) 1f else -1f }), 1e-4f)
        assertEquals(0f, pcmRms(FloatArray(0)), 0f)
    }

    // ── pcmToWav ─────────────────────────────────────────────────────────────

    @Test
    fun `wav container has a valid RIFF header and carries the samples little-endian`() {
        val samples = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        val wav = pcmToWav(samples, 16_000)
        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        fun le32(off: Int) = (wav[off].toInt() and 0xff) or
            ((wav[off + 1].toInt() and 0xff) shl 8) or
            ((wav[off + 2].toInt() and 0xff) shl 16) or
            ((wav[off + 3].toInt() and 0xff) shl 24)
        fun le16(off: Int) = ((wav[off].toInt() and 0xff) or ((wav[off + 1].toInt() and 0xff) shl 8)).toShort()

        assertEquals(36 + samples.size * 2, le32(4))   // RIFF chunk size
        assertEquals(1, le16(20).toInt())              // PCM format
        assertEquals(1, le16(22).toInt())              // mono
        assertEquals(16_000, le32(24))                 // sample rate
        assertEquals(32_000, le32(28))                 // byte rate
        assertEquals(16, le16(34).toInt())             // bits per sample
        assertEquals(samples.size * 2, le32(40))       // data length
        samples.forEachIndexed { i, s -> assertEquals(s, le16(44 + i * 2)) }
    }

    @Test
    fun `endpointer default threshold sits between silence and speech rms`() {
        // A sanity pin on the constant: typical quiet-room noise (~0.005) must
        // stay below, deliberate speech (~0.1+) above.
        assertTrue(RmsEndpointer.SPEECH_RMS_THRESHOLD > 0.01f)
        assertTrue(RmsEndpointer.SPEECH_RMS_THRESHOLD < 0.1f)
    }
}
