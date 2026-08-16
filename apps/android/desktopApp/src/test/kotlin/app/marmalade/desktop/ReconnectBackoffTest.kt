package app.marmalade.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The reconnect schedule is a cross-client contract (Android's dashboard loop
 * uses the same numbers), and it's the one part of the socket lifecycle that
 * can be asserted without a daemon or a clock.
 */
class ReconnectBackoffTest {

    @Test
    fun delayDoublesFromOneSecondAndClampsAtFifteen() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L),
            (1..6).map(ReconnectBackoff::delayMs),
        )
    }

    @Test
    fun attemptCounterSaturatesSoRetriesPlateauAtEightSeconds() {
        // The counter is what the loop actually feeds delayMs, so the real
        // steady-state interval is delayMs(MAX_ATTEMPT) = 8s, not the 15s clamp.
        var attempt = ReconnectBackoff.resetAttempt()
        val schedule = List(6) {
            attempt = ReconnectBackoff.nextAttempt(attempt)
            ReconnectBackoff.delayMs(attempt)
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), schedule)
        assertEquals(ReconnectBackoff.MAX_ATTEMPT, attempt)
    }

    @Test
    fun successResetsTheBackoffToTheFirstStep() {
        var attempt = ReconnectBackoff.resetAttempt()
        repeat(5) { attempt = ReconnectBackoff.nextAttempt(attempt) }
        attempt = ReconnectBackoff.resetAttempt()
        attempt = ReconnectBackoff.nextAttempt(attempt)
        assertEquals(1_000L, ReconnectBackoff.delayMs(attempt))
    }

    @Test
    fun hugeAttemptCountsStayClampedInsteadOfWrappingTheShift() {
        // 1_000L shl 63 is negative and shl 64 is 1_000 — either would make a
        // long outage retry FASTER than a short one.
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayMs(64))
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayMs(Int.MAX_VALUE))
    }

    @Test
    fun attemptIsOneBased() {
        assertFailsWith<IllegalArgumentException> { ReconnectBackoff.delayMs(0) }
    }
}
