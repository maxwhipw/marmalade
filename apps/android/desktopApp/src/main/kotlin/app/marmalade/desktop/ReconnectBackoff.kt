package app.marmalade.desktop

/**
 * Reconnect timing for [DesktopRuntime]'s socket lifecycle loop.
 *
 * Pure on purpose: the loop around it is a coroutine collecting a StateFlow and
 * is awkward to assert on, but the timing IS the contract — it has to match the
 * Android runtime's dashboard reconnect (`MarmaladeRuntime`, "Auto-reconnect on
 * Closed / Error") so both clients back off identically against one daemon.
 *
 * Shape: attempt counter increments per failed transition and saturates at
 * [MAX_ATTEMPT]; the delay doubles from [BASE_DELAY_MS] and is clamped to
 * [MAX_DELAY_MS]. With the attempt cap at 4 the steady-state retry interval is
 * 8 s — the 15 s clamp only binds if a caller feeds an uncapped attempt count.
 * Both bounds are kept because Android keeps both.
 */
internal object ReconnectBackoff {
    const val BASE_DELAY_MS: Long = 1_000L
    const val MAX_DELAY_MS: Long = 15_000L
    const val MAX_ATTEMPT: Int = 4

    /**
     * Shifting a Long by >= 64 wraps (Kotlin masks the shift distance), which
     * would hand back a *shorter* delay for a *longer* outage. Past this point
     * the clamp has bound anyway, so short-circuit.
     */
    private const val SHIFT_SAFE_LIMIT: Int = 20

    /** Attempt counter after another failed/dropped connection. */
    fun nextAttempt(previous: Int): Int = (previous + 1).coerceAtMost(MAX_ATTEMPT)

    /** Attempt counter after a successful open — backoff restarts from zero. */
    fun resetAttempt(): Int = 0

    /** Delay before retry number [attempt] (1-based). */
    fun delayMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be 1-based, was $attempt" }
        if (attempt > SHIFT_SAFE_LIMIT) return MAX_DELAY_MS
        return (BASE_DELAY_MS shl (attempt - 1)).coerceAtMost(MAX_DELAY_MS)
    }
}
