package app.marmalade.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for the client-paced text-reveal math
 * (StreamingTextReveal.kt). The Compose ticker itself is verified on-device;
 * this pins the catch-up cadence and the surrogate-safe clamp so the pacing
 * can't silently regress into "lags the server" or "flashes a broken glyph".
 */
class StreamingTextRevealTest {

    @Test
    fun `already caught up stays put`() {
        assertEquals(80, nextRevealCount(80, 80, 8))
        assertEquals(0, nextRevealCount(0, 0, 8))
    }

    @Test
    fun `never over-reveals past the target`() {
        // backlog of 1 -> step is at least 1 but clamps to target.
        assertEquals(80, nextRevealCount(79, 80, 8))
    }

    @Test
    fun `cursor past target clamps back down to target`() {
        // Defensive: text should only grow, but a shrink must not read OOB.
        assertEquals(3, nextRevealCount(5, 3, 8))
    }

    @Test
    fun `advances by backlog over catch-up ticks`() {
        // backlog 80 / 8 ticks = 10 chars this tick.
        assertEquals(10, nextRevealCount(0, 80, 8))
        // backlog 70 / 8 = 8 (integer div) -> 10 + 8.
        assertEquals(18, nextRevealCount(10, 80, 8))
    }

    @Test
    fun `small backlog still advances at least one char`() {
        // backlog 3 / 8 = 0 -> floored to 1 so it never stalls.
        assertEquals(1, nextRevealCount(0, 3, 8))
    }

    @Test
    fun `any backlog closes within a bounded number of ticks`() {
        // A big lump (e.g. a pasted paragraph) must converge quickly, not
        // dribble out one char at a time.
        var cursor = 0
        val target = 4000
        var ticks = 0
        while (cursor < target) {
            cursor = nextRevealCount(cursor, target, CATCH_UP_TICKS)
            ticks++
            assertTrue("cursor must be monotonic and bounded", cursor in 0..target)
        }
        // Geometric close (~backlog * 7/8 per tick) plus the +1 floor tail:
        // comfortably under 120 ticks (~2.4s at 20ms) even for 4k chars.
        assertTrue("closed in $ticks ticks", ticks < 120)
    }

    @Test
    fun `safeRevealLength clamps into range`() {
        assertEquals(0, safeRevealLength("hello", -3))
        assertEquals(5, safeRevealLength("hello", 99))
        assertEquals(3, safeRevealLength("hello", 3))
    }

    @Test
    fun `safeRevealLength never splits a surrogate pair`() {
        // "a" + grinning-face emoji (a surrogate pair -> 2 UTF-16 chars).
        val text = "a😀"
        assertEquals(3, text.length)
        // Cursor landing between the high and low surrogate backs off by one.
        assertEquals(1, safeRevealLength(text, 2))
        // Landing before or after the pair is fine.
        assertEquals(1, safeRevealLength(text, 1))
        assertEquals(3, safeRevealLength(text, 3))
    }
}
