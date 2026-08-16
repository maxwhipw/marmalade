package app.marmalade.android.mic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Who currently owns the microphone.
 *
 * KWS is always preemptable — VOICE_SESSION and INLINE_STT take priority.
 * Only one non-KWS consumer can hold the mic at a time.
 */
enum class MicOwner {
    /** Nobody holds the mic — HotwordService should be listening. */
    NONE,
    /** HotwordService's AudioRecord is feeding openWakeWord KWS. */
    KWS,
    /** MarmaladeVoiceSession voice popup is using the active STT recognizer. */
    VOICE_SESSION,
    /** Inline STT (chat input bar mic button) is using the active STT recognizer. */
    INLINE_STT,
}

/**
 * Centralized microphone ownership tracker.
 *
 * Replaces the broadcast-based ACTION_PAUSE_HOTWORD / ACTION_RESUME_HOTWORD
 * mechanism with direct method calls and observable state. All mic consumers
 * live in the same process, so no IPC is needed.
 *
 * Three safety layers guarantee HotwordService always gets the mic back:
 * 1. Primary — the STT recognizer's onFlowClosed callback invokes releaseMic()
 * 2. Guard  — callers release on lifecycle teardown (cleanupSession/release)
 * 3. Safety net — 90s timeout forcibly releases the mic
 *
 * Thread-safe via @Synchronized on state mutations.
 */
class MicOwnershipManager internal constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    /**
     * Scope used for the safety-net timer and the deferred KWS-restart
     * notification. Defaults to Main.immediate in production; tests inject a
     * virtual-time scope so the timeouts are deterministic.
     */
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Safety-net timeout. Overridable in tests; production keeps 90s. */
    private val safetyNetTimeoutMs: Long = SAFETY_NET_TIMEOUT_MS,
) {

    companion object {
        private const val TAG = "MicOwnership"

        /** Force-reclaim window — a stuck STT session loses the mic after this. */
        const val SAFETY_NET_TIMEOUT_MS = 90_000L

        /** Settle delay before the KWS-restart callback fires, letting the
         *  releasing consumer's AudioRecord teardown propagate. */
        const val KWS_RESTART_SETTLE_MS = 50L

        @Volatile
        private var instance: MicOwnershipManager? = null

        fun getInstance(context: Context): MicOwnershipManager {
            return instance ?: synchronized(this) {
                instance ?: MicOwnershipManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _currentOwner = MutableStateFlow(MicOwner.NONE)
    val currentOwner: StateFlow<MicOwner> = _currentOwner.asStateFlow()

    /** Callback invoked (on the manager scope) when mic is released back to KWS. */
    private var onMicReleasedToKws: (() -> Unit)? = null

    private var safetyNetJob: Job? = null

    @Volatile
    private var acquisitionTimestamp: Long = 0L

    /**
     * Request mic ownership. Returns true if granted.
     *
     * KWS and NONE are always preemptable. If another STT consumer already
     * holds the mic, returns false — the caller should handle gracefully
     * (e.g., show a message, skip, or retry).
     *
     * Idempotent: if the requester already owns the mic, refreshes the
     * safety net timer and returns true.
     */
    @Synchronized
    fun requestMic(requester: MicOwner): Boolean {
        val current = _currentOwner.value

        if (current == requester) {
            Log.d(TAG, "requestMic($requester): already owned, refreshing timer")
            restartSafetyNet(requester)
            return true
        }

        if (current == MicOwner.NONE || current == MicOwner.KWS) {
            Log.i(TAG, "requestMic($requester): granted (was $current)")
            _currentOwner.value = requester
            acquisitionTimestamp = System.currentTimeMillis()
            restartSafetyNet(requester)
            return true
        }

        // Another non-KWS consumer holds the mic
        Log.w(TAG, "requestMic($requester): denied — held by $current")
        return false
    }

    /**
     * Release mic ownership. Only the current owner can release.
     * After release, notifies HotwordService to restart KWS.
     */
    @Synchronized
    fun releaseMic(requester: MicOwner) {
        val current = _currentOwner.value
        if (current != requester) {
            // Already released or someone else holds it — no-op
            if (current != MicOwner.NONE && current != MicOwner.KWS) {
                Log.d(TAG, "releaseMic($requester): no-op, current owner is $current")
            }
            return
        }

        val held = System.currentTimeMillis() - acquisitionTimestamp
        Log.i(TAG, "releaseMic($requester): released after ${held}ms")
        _currentOwner.value = MicOwner.NONE
        safetyNetJob?.cancel()
        safetyNetJob = null

        notifyKwsRestart()
    }

    /**
     * Forcibly release the mic regardless of who holds it.
     * Used by the safety net timeout.
     */
    @Synchronized
    fun forceRelease(reason: String) {
        val current = _currentOwner.value
        if (current == MicOwner.NONE || current == MicOwner.KWS) return

        val held = System.currentTimeMillis() - acquisitionTimestamp
        Log.w(TAG, "forceRelease: $reason (was $current, held ${held}ms)")
        _currentOwner.value = MicOwner.NONE
        safetyNetJob?.cancel()
        safetyNetJob = null

        notifyKwsRestart()
    }

    /**
     * Register callback for HotwordService to restart its AudioRecord + KWS.
     * Invoked when the mic transitions from a non-KWS owner to NONE, after a
     * short settle delay. Pass null to unregister (e.g., HotwordService destroyed).
     */
    fun setOnMicReleasedToKws(callback: (() -> Unit)?) {
        onMicReleasedToKws = callback
    }

    private fun notifyKwsRestart() {
        // Small delay to let the releasing consumer's AudioRecord teardown propagate
        scope.launch {
            delay(KWS_RESTART_SETTLE_MS)
            onMicReleasedToKws?.invoke()
        }
    }

    private fun restartSafetyNet(owner: MicOwner) {
        safetyNetJob?.cancel()
        if (owner == MicOwner.NONE || owner == MicOwner.KWS) return

        safetyNetJob = scope.launch {
            delay(safetyNetTimeoutMs)
            forceRelease("safety net timeout after ${safetyNetTimeoutMs}ms for $owner")
        }
    }
}
