package app.marmalade.desktop.notify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The throttle that stands between a busy background session and a wall of
 * popups. Worth testing because its failure modes are opposite and both bad:
 * too loose spams, too tight silences a session for good.
 */
class NotificationRateLimiterTest {

    private var clock = 0L
    private val limiter = NotificationRateLimiter(minIntervalMs = 5_000L) { clock }

    @Test
    fun `first notification for a session always fires`() {
        assertTrue(limiter.allow("alpha"))
    }

    @Test
    fun `a second notification inside the window is dropped`() {
        assertTrue(limiter.allow("alpha"))
        clock += 4_999
        assertFalse(limiter.allow("alpha"))
    }

    @Test
    fun `the window reopens once the interval elapses`() {
        assertTrue(limiter.allow("alpha"))
        clock += 5_000
        assertTrue(limiter.allow("alpha"))
    }

    @Test
    fun `a dropped notification does not extend the window`() {
        assertTrue(limiter.allow("alpha"))
        clock += 3_000
        assertFalse(limiter.allow("alpha"))
        clock += 2_000
        // 5s after the one that FIRED, not after the one that was dropped.
        assertTrue(limiter.allow("alpha"))
    }

    @Test
    fun `sessions are throttled independently`() {
        assertTrue(limiter.allow("alpha"))
        assertTrue(limiter.allow("beta"))
        assertFalse(limiter.allow("alpha"))
    }

    @Test
    fun `a backwards clock step does not mute a session`() {
        clock = 100_000
        assertTrue(limiter.allow("alpha"))
        clock = 10_000 // NTP step / resume
        assertTrue(limiter.allow("alpha"))
    }
}
