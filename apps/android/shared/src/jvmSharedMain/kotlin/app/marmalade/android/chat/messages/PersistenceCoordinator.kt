package app.marmalade.android.chat.messages

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session debounced write coordinator. Each [schedule] call cancels any
 * pending flush for the same session and starts a fresh debounce window.
 * [flushNow] cancels the debounce and runs the flush immediately — used on
 * terminal events and lifecycle hooks (onPause, process shutdown).
 *
 * Concurrency: each session has its own [Mutex]. The flush body runs
 * `withLock`, so a debounce job already executing the flush cannot race
 * with a [flushNow] call — the second flush waits its turn. `cancel()` on
 * the debounce Job is still attempted (best-effort cooperative cancellation
 * while the job is in `delay`), but correctness no longer depends on it:
 * serialization is enforced by the Mutex regardless. Different sessions
 * still flush in parallel — the per-key Mutex only blocks same-key
 * contention.
 *
 * Adapted from the OLD marmalade-android client
 * (ChatController.kt:224-254). The mechanism is unchanged; only the
 * callback signature and scope ownership move out of ChatController so the
 * coordinator can be reused by MessageStream, hydrateFromServer, and any
 * other writer that needs serialized session-scoped flushes.
 */
class PersistenceCoordinator(
    private val scope: CoroutineScope,
    private val flush: suspend (sessionKey: String) -> Unit,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private val pending = ConcurrentHashMap<String, Job>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Public lock accessor for callers that need to coordinate non-flush
     *  writes against the same per-session serialization (reconcileHistory,
     *  outbox ack, boot recovery). */
    fun lockFor(sessionKey: String): Mutex =
        locks.computeIfAbsent(sessionKey) { Mutex() }

    /** Schedule a debounced flush. Cancels any pending flush for the same
     *  session; the new debounce window starts now. */
    fun schedule(sessionKey: String) {
        val job = scope.launch {
            delay(debounceMs)
            lockFor(sessionKey).withLock { flush(sessionKey) }
        }
        pending.put(sessionKey, job)?.cancel()
    }

    /** Cancel any pending flush for the session and run one immediately.
     *  Use on terminal events (message.complete, error) and lifecycle hooks. */
    fun flushNow(sessionKey: String) {
        pending.remove(sessionKey)?.cancel()
        scope.launch {
            lockFor(sessionKey).withLock { flush(sessionKey) }
        }
    }

    /** Force-flush every session with a pending debounce. Use on app
     *  background / shutdown so users never lose more than ~debounceMs of
     *  in-memory streaming content. */
    fun flushAll() {
        val keys = pending.keys.toList()
        for (key in keys) flushNow(key)
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 200L
    }
}
