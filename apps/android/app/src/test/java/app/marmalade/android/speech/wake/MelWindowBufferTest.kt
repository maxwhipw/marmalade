package app.marmalade.android.speech.wake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MelWindowBufferTest {

    /** A distinguishable mel frame: every value equals [id], so window contents are checkable by eye. */
    private fun frame(id: Float): FloatArray = FloatArray(32) { id }

    private fun frames(vararg ids: Float): List<FloatArray> = ids.map { frame(it) }

    @Test
    fun `no embedding window until 76 mel frames accumulated`() {
        val buf = MelWindowBuffer()

        // Push 8 frames at a time (one hop's worth) for 9 hops = 72 frames -- still short of 76.
        repeat(9) {
            buf.pushMelFrames(frames(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f))
            val windows = buf.pendingEmbeddingWindows(newFrameCount = 8)
            assertEquals(0, windows.size)
        }
    }

    @Test
    fun `first embedding window appears once 76 frames accumulated`() {
        val buf = MelWindowBuffer()
        val hop = frames(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) // one hop's worth: 8 frames

        // 9 hops * 8 = 72 frames (below 76); still no window.
        repeat(9) {
            buf.pushMelFrames(hop)
            assertEquals(0, buf.pendingEmbeddingWindows(newFrameCount = 8).size)
        }

        // 10th hop pushes to 80 frames, crossing the 76-frame threshold.
        buf.pushMelFrames(hop)
        val windows = buf.pendingEmbeddingWindows(newFrameCount = 8)
        assertEquals(1, windows.size)
        assertEquals(76 * 32, windows[0].size) // window is 76 * 32 floats
    }

    @Test
    fun `window contents match the exact 76-frame slice at a boundary`() {
        val buf = MelWindowBuffer()

        // Push exactly 76 uniquely-tagged frames as one giant "hop" (simulates warm-up fill).
        val tags = FloatArray(76) { it.toFloat() }
        buf.pushMelFrames(tags.map { frame(it) })

        val windows = buf.pendingEmbeddingWindows(newFrameCount = 76)
        assertEquals(1, windows.size)

        // The single window should be frames 0..75, each row's 32 values equal to its tag.
        val window = windows[0]
        for (row in 0 until 76) {
            val expectedTag = row.toFloat()
            for (col in 0 until 32) {
                assertEquals(expectedTag, window[row * 32 + col], 0.0001f)
            }
        }
    }

    @Test
    fun `subsequent hop advances the window by exactly 8 frames`() {
        val buf = MelWindowBuffer()

        // Fill to exactly 76 frames tagged 0..75.
        buf.pushMelFrames((0 until 76).map { frame(it.toFloat()) })
        buf.pendingEmbeddingWindows(newFrameCount = 76) // consume warm-up window

        // Next hop: 8 new frames tagged 76..83.
        buf.pushMelFrames((76 until 84).map { frame(it.toFloat()) })
        val windows = buf.pendingEmbeddingWindows(newFrameCount = 8)

        assertEquals(1, windows.size)
        val window = windows[0]
        // Window should now cover frames 8..83 (76 frames), so its first row is tagged 8.
        assertEquals(8f, window[0], 0.0001f)
        // And its last row (row 75) is tagged 83.
        assertEquals(83f, window[75 * 32], 0.0001f)
    }

    @Test
    fun `classifier window is null before 16 embeddings accumulated`() {
        val buf = MelWindowBuffer()
        repeat(15) { buf.pushEmbedding(FloatArray(96) { it.toFloat() }) }
        assertNull(buf.classifierWindow())
    }

    @Test
    fun `classifier window is the most recent 16 embeddings flattened`() {
        val buf = MelWindowBuffer()
        // Push 20 embeddings tagged 0..19; classifier window should be the last 16 (4..19).
        repeat(20) { i -> buf.pushEmbedding(FloatArray(96) { i.toFloat() }) }

        val window = buf.classifierWindow()
        assertEquals(16 * 96, window!!.size)
        // First row of the window should be tagged 4 (the 5th embedding, 0-indexed).
        assertEquals(4f, window[0], 0.0001f)
        // Last row should be tagged 19.
        assertEquals(19f, window[15 * 96], 0.0001f)
    }

    @Test
    fun `reset clears both buffers`() {
        val buf = MelWindowBuffer()
        buf.pushMelFrames((0 until 76).map { frame(it.toFloat()) })
        buf.pendingEmbeddingWindows(newFrameCount = 76)
        buf.pushEmbedding(FloatArray(96))

        buf.reset()

        assertNull(buf.classifierWindow())
        buf.pushMelFrames((0 until 76).map { frame(it.toFloat()) })
        assertEquals(1, buf.pendingEmbeddingWindows(newFrameCount = 76).size)
    }

    @Test
    fun `zero new frames yields no windows`() {
        val buf = MelWindowBuffer()
        buf.pushMelFrames((0 until 76).map { frame(it.toFloat()) })
        assertArrayEquals(emptyArray<FloatArray>(), buf.pendingEmbeddingWindows(newFrameCount = 0).toTypedArray())
    }

    @Test
    fun `clearEmbeddings drops embeddings but leaves mel history intact`() {
        val buf = MelWindowBuffer()
        buf.pushMelFrames((0 until 76).map { frame(it.toFloat()) })
        repeat(16) { buf.pushEmbedding(FloatArray(96) { it.toFloat() }) }
        assertEquals(16 * 96, buf.classifierWindow()!!.size)

        buf.clearEmbeddings()

        assertNull(buf.classifierWindow())
        // Mel history is untouched: a fresh pendingEmbeddingWindows call for
        // the already-buffered 76 frames still sees the full history.
        assertEquals(0, buf.pendingEmbeddingWindows(newFrameCount = 0).size) // no *new* frames pushed
        buf.pushMelFrames(frames(76f, 76f, 76f, 76f, 76f, 76f, 76f, 76f))
        assertEquals(1, buf.pendingEmbeddingWindows(newFrameCount = 8).size)
    }

    @Test
    fun `backfillEmbeddingWindows returns nothing when mel buffer has no history`() {
        val buf = MelWindowBuffer()
        assertEquals(0, buf.backfillEmbeddingWindows().size)
    }

    @Test
    fun `backfillEmbeddingWindows skips windows lacking history during cold warm-up`() {
        val buf = MelWindowBuffer()
        // Only 76 frames buffered: just enough for exactly one (the newest) window.
        buf.pushMelFrames((0 until 76).map { frame(it.toFloat()) })

        val windows = buf.backfillEmbeddingWindows()

        assertEquals(1, windows.size)
        assertEquals(0f, windows[0][0], 0.0001f) // covers frames 0..75
    }

    @Test
    fun `backfillEmbeddingWindows produces exactly 16 windows when buffer is fully warm`() {
        val buf = MelWindowBuffer()
        // 76 + 15*8 = 196 frames needed for a full 16-window backfill.
        buf.pushMelFrames((0 until 196).map { frame(it.toFloat()) })

        val windows = buf.backfillEmbeddingWindows()

        assertEquals(16, windows.size)
        // Oldest window: frames 0..75 (ends at 196 - 8*15 = 76).
        assertEquals(0f, windows[0][0], 0.0001f)
        assertEquals(75f, windows[0][75 * 32], 0.0001f)
        // Newest window: frames 120..195 (ends at buffer end, 196).
        assertEquals(120f, windows[15][0], 0.0001f)
        assertEquals(195f, windows[15][75 * 32], 0.0001f)
    }

    @Test
    fun `backfillEmbeddingWindows equals the incremental path fed the same continuous audio`() {
        // Feed 200 frames (25 hops of 8; the first whole multiple of 8 that
        // covers the 196-frame full-backfill requirement) through the
        // incremental path and through a fresh buffer via backfill; only the
        // last 16 windows (the ones backfill produces) are compared, since
        // the incremental path also emits earlier warm-up windows that
        // backfill intentionally discards.
        val incremental = MelWindowBuffer()
        val hops = (0 until 200 step 8).map { start -> (start until start + 8).map { frame(it.toFloat()) } }
        val incrementalWindows = mutableListOf<FloatArray>()
        for (hop in hops) {
            incremental.pushMelFrames(hop)
            incrementalWindows += incremental.pendingEmbeddingWindows(newFrameCount = hop.size)
        }
        val lastSixteenIncremental = incrementalWindows.takeLast(16)

        val backfilled = MelWindowBuffer()
        backfilled.pushMelFrames((0 until 200).map { frame(it.toFloat()) })
        val backfillWindows = backfilled.backfillEmbeddingWindows()

        assertEquals(16, lastSixteenIncremental.size)
        assertEquals(lastSixteenIncremental.size, backfillWindows.size)
        for (i in lastSixteenIncremental.indices) {
            assertArrayEquals(lastSixteenIncremental[i], backfillWindows[i], 0.0001f)
        }
    }
}
