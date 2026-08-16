package app.marmalade.android.chat

/**
 * Side-effect requests produced by [RunStateMachine.apply]. The machine
 * itself is pure (no RPCs, no coroutines, no logging); it returns a list
 * of effects that [ChatController] executes.
 *
 * This is the "functional core, imperative shell" split: state transitions
 * are data, side effects are code. Tests can assert on the list of effects
 * a given event produces without mocking any I/O.
 */
sealed interface RunEffect {

    /**
     * Issue `chat.history` for the run's session. Step E: no longer
     * emitted from terminal transitions (those use [MarkAssistantSent] /
     * [MarkAssistantFailed] / [MarkAssistantAborted] to flip the in-memory
     * streaming message). Reserved for explicit-refresh paths and future
     * bootstrap reconciliation (Step F).
     */
    data class RequestHistoryRefetch(val runId: RunId) : RunEffect

    /**
     * Step E terminal effect: flip the assistant ChatMessage keyed by
     * [runId] to `sendStatus="sent"` in its session's flow. Replaces
     * the delete-and-refetch cycle that lost tool chip metadata at
     * finalization. Controller's executor finds the message via
     * `runIdToSessionKey` and applies the flip in-place.
     */
    data class MarkAssistantSent(val runId: RunId) : RunEffect

    /**
     * Step E: flip the assistant message to `sendStatus="failed"` and
     * append a brief error block to its content (per-message record of
     * what went wrong, complementing the transient banner).
     */
    data class MarkAssistantFailed(val runId: RunId, val message: String) : RunEffect

    /**
     * Step E: flip the assistant message to `sendStatus="aborted"`. The
     * partial content the user already saw stays visible — abort means
     * "stop adding more," not "erase what we have."
     */
    data class MarkAssistantAborted(val runId: RunId, val reason: AbortReason) : RunEffect

    /**
     * Step E notification fire for a terminally-flipped assistant message.
     * Controller's executor checks foreground state and routes to the
     * existing `onOtherSessionMessage` callback if the user isn't viewing
     * the session this run belongs to.
     */
    data class NotifyMessageCompleted(val runId: RunId) : RunEffect

    /**
     * A `marmalade_action` JSON block was extracted from streaming text (or
     * from the finalized history) and should be dispatched to the Android
     * intent system. [source] is `"streaming"` or `"final"` for logging.
     */
    data class DispatchMarmaladeAction(
        val runId: RunId,
        val text: String,
        val source: String,
    ) : RunEffect

    /**
     * Fire the `chat.abort` RPC to the gateway. Fire-and-forget — the
     * machine has already transitioned the local state to Aborted.
     */
    data class FireAbortRpc(val runId: RunId) : RunEffect

    /** Set the UI's transient error banner (`_errorText` StateFlow). */
    data class ShowBanner(val text: String) : RunEffect

    /** Clear the UI's transient error banner. */
    data object ClearBanner : RunEffect

    /**
     * Schedule a same-session notification fire for the run's final message.
     * NotificationPipelineLogic decides whether to actually fire based on
     * foreground state + mute state.
     */
    data class TriggerNotification(val runId: RunId) : RunEffect

    /** Emit a WARN line into the Gateway Tab log buffer. */
    data class LogWarn(val message: String) : RunEffect

    /** Emit a DEBUG line into logcat (not the user-visible log buffer). */
    data class LogDebug(val message: String) : RunEffect
}
