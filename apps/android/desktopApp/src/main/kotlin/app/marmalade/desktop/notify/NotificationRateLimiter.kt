package app.marmalade.desktop.notify

/**
 * Per-session throttle for [Notifier] traffic.
 *
 * A single agent turn can produce several notify-worthy events in a row (a
 * clarify prompt, then the assistant bubble that answers it, then the turn
 * completing), and a busy background session can produce them repeatedly.
 * The user only needs to know *that* the session moved, so one notification
 * per session per [minIntervalMs] is the whole policy.
 *
 * Deliberately keyed on the session alone, not on the kind of event: two
 * notifications a second apart about the same session are noise regardless of
 * which half of the turn produced them.
 *
 * Thread-safe — events arrive on chat coroutines and on the AWT thread.
 */
class NotificationRateLimiter(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastFiredMs = HashMap<String, Long>()

    /**
     * True if a notification for [sessionKey] may fire now, recording the
     * decision. Calling this is the only way to consume the budget — callers
     * must not check and fire separately.
     */
    @Synchronized
    fun allow(sessionKey: String): Boolean {
        val nowMs = now()
        val last = lastFiredMs[sessionKey]
        // A clock that jumped backwards (suspend/resume, NTP step) must not
        // mute a session until it catches up.
        if (last != null && nowMs >= last && nowMs - last < minIntervalMs) return false
        lastFiredMs[sessionKey] = nowMs
        return true
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 5_000L
    }
}
