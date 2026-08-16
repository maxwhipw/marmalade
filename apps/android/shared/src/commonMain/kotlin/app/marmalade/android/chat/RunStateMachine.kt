package app.marmalade.android.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure transition result — a new state (or `null` meaning "remove this run
 * from the map") plus the side effects the caller must execute.
 */
data class TransitionResult(
    val newState: RunState?,
    val effects: List<RunEffect>,
)

/**
 * Typed state machine for agent runs. Replaces the load-bearing
 * `pendingRuns: MutableSet<RunId>` + `state == "final"` string checks scattered
 * through [ChatController] with a single sealed-type state graph.
 *
 * ## Design constraints
 *
 *  - **Functional core, imperative shell.** [RunStateTransitions.transition]
 *    is a pure function of (current, event) → (new, effects). The wrapper
 *    class holds mutable state (`MutableStateFlow<Map<RunId, RunState>>`)
 *    and executes no side effects beyond emitting to the flow — all RPCs,
 *    notifications, widget refreshes, and logging are in the returned
 *    [RunEffect] list.
 *
 *  - **Sealed-type exhaustiveness.** [RunEvent] is a sealed interface so
 *    the transition function's outer `when` is compile-time exhaustive.
 *    Adding a new event variant fails the build until every handler branch
 *    has been considered.
 *
 *  - **No silent drops.** Every input produces exactly one of: a state
 *    mutation, a DEBUG log (intentional no-op such as duplicate event),
 *    or a WARN log (unexpected but non-fatal). No `return` without logging.
 *
 *  - **Terminal stickiness (with one carve-out).** Once a run is terminal
 *    ([RunState.isTerminal]), events generally log and drop without
 *    changing state. The one exception is `LifecycleStart on Errored`:
 *    the gateway emits `lifecycle phase=error` for recoverable blocks
 *    (e.g. `livenessState="blocked"`) and then `lifecycle phase=start`
 *    on the same runId to resume. Treating Errored as truly sticky would
 *    drop every subsequent stream event for that run and break the
 *    streaming UI. Completed / Aborted remain fully sticky — only Errored
 *    resurrects, and only via LifecycleStart.
 */
class RunStateMachine {
    private val _states = MutableStateFlow<Map<RunId, RunState>>(emptyMap())

    /** Full map of known runs keyed by id. Read-only. */
    val states: StateFlow<Map<RunId, RunState>> = _states.asStateFlow()

    /**
     * Apply an event and return the effects the caller must execute.
     *
     * For broadcast events ([RunEvent.SeqGap]) this runs one transition per
     * non-terminal run and returns the concatenated effects list.
     */
    fun apply(event: RunEvent): List<RunEffect> {
        // Broadcasts: one per-run transition for every non-terminal run.
        if (event is RunEvent.SeqGap) return applySeqGap(event)

        // Targeted: one transition for the event's runId.
        val runId = event.runId ?: return listOf(
            RunEffect.LogWarn("RunStateMachine: event with null runId and no broadcast branch: $event"),
        )
        val current = _states.value[runId]
        val result = RunStateTransitions.transition(current, event)
        commit(runId, result.newState)
        return result.effects
    }

    /**
     * Remove a terminal run from the map. Intended for a caller-driven GC
     * pass after the UI has read the terminal state. The machine does not
     * auto-GC because a caller may still want to show "last error" / "last
     * completed" info downstream of the map.
     */
    fun dropTerminal(runId: RunId) {
        val current = _states.value[runId] ?: return
        if (!current.isTerminal) return
        _states.update { it - runId }
    }

    private fun applySeqGap(event: RunEvent.SeqGap): List<RunEffect> {
        val effects = mutableListOf<RunEffect>()
        val snapshot = _states.value
        for ((runId, state) in snapshot) {
            if (state.isTerminal) continue
            val result = RunStateTransitions.transition(
                current = state,
                event = SeqGapForRun(runId, event.nowMs),
            )
            commit(runId, result.newState)
            effects += result.effects
        }
        return effects
    }

    private fun commit(runId: RunId, newState: RunState?) {
        _states.update { prev ->
            if (newState == null) prev - runId
            else prev + (runId to newState)
        }
    }
}

// Small inline helper so MutableStateFlow.update exists in this file's scope
// without requiring an extra import in every file that consumes it.
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return
    }
}

/**
 * Internal synthetic per-run event used to distribute the [RunEvent.SeqGap]
 * broadcast through the same transition function. Not part of the public
 * [RunEvent] surface.
 */
internal data class SeqGapForRun(
    override val runId: RunId,
    val nowMs: Long,
) : RunEvent

/**
 * The pure transition function. Given a current state (or null) and an
 * event, produce the new state and the effects the caller must execute.
 */
object RunStateTransitions {

    fun transition(current: RunState?, event: RunEvent): TransitionResult =
        when (event) {
            is RunEvent.Send -> handleSend(current, event)
            is RunEvent.SendAcked -> handleSendAcked(current, event)
            is RunEvent.SendFailed -> handleSendFailed(current, event)
            is RunEvent.AssistantText -> handleAssistantText(current, event)
            is RunEvent.ThinkingText -> handleThinkingText(current, event)
            is RunEvent.ToolStart -> handleToolStart(current, event)
            is RunEvent.ToolResult -> handleToolResult(current, event)
            is RunEvent.AgentItem -> handleAgentItem(current, event)
            is RunEvent.LifecycleStart -> handleLifecycleStart(current, event)
            is RunEvent.LifecycleEnd -> handleLifecycleEnd(current, event)
            is RunEvent.LifecycleErrorEvent -> handleLifecycleError(current, event)
            is RunEvent.AgentError -> handleAgentError(current, event)
            is RunEvent.ChatFinal -> handleChatFinal(current, event)
            is RunEvent.ChatError -> handleChatError(current, event)
            is RunEvent.ChatAborted -> handleChatAborted(current, event)
            is RunEvent.Abort -> handleAbort(current, event)
            is SeqGapForRun -> handleSeqGapForRun(current, event)
            // SeqGap is a broadcast — the machine wrapper expands it into
            // SeqGapForRun before reaching transition(). This branch keeps
            // the `when` exhaustive.
            is RunEvent.SeqGap ->
                TransitionResult(
                    newState = current,
                    effects = listOf(
                        RunEffect.LogWarn("RunStateTransitions: broadcast event reached transition() directly: $event"),
                    ),
                )
        }

    // -- Send lifecycle ---------------------------------------------------

    private fun handleSend(current: RunState?, event: RunEvent.Send): TransitionResult {
        if (current != null) {
            return TransitionResult(
                newState = current,
                effects = listOf(
                    RunEffect.LogWarn(
                        "Send event for an already-existing run ${event.runId}; using fresh runId is required",
                    ),
                ),
            )
        }
        return TransitionResult(
            newState = RunState.Pending(
                runId = event.runId,
                startedAtMs = event.nowMs,
                lastActivityAtMs = event.nowMs,
            ),
            effects = emptyList(),
        )
    }

    private fun handleSendAcked(current: RunState?, event: RunEvent.SendAcked): TransitionResult {
        if (current == null) {
            return debugDrop(current, "SendAcked for unknown run ${event.runId}")
        }
        return when (current) {
            is RunState.Pending -> TransitionResult(
                newState = RunState.Accepted(
                    runId = current.runId,
                    startedAtMs = current.startedAtMs,
                    lastActivityAtMs = event.nowMs,
                    serverRunId = event.serverRunId,
                ),
                effects = emptyList(),
            )
            is RunState.Accepted -> debugDrop(current, "Duplicate SendAcked for ${current.runId}")
            is RunState.Streaming, is RunState.Aborting, is RunState.Completed,
            is RunState.Errored, is RunState.Aborted -> warnStay(
                current,
                "SendAcked arrived after ${current::class.simpleName} for ${current.runId}",
            )
        }
    }

    private fun handleSendFailed(current: RunState?, event: RunEvent.SendFailed): TransitionResult {
        if (current == null) {
            return debugDrop(current, "SendFailed for unknown run ${event.runId}")
        }
        if (current.isTerminal) return debugDrop(current, "SendFailed on terminal ${current::class.simpleName}")
        return TransitionResult(
            newState = RunState.Errored(
                runId = current.runId,
                startedAtMs = current.startedAtMs,
                lastActivityAtMs = event.nowMs,
                endedAtMs = event.nowMs,
                cause = ErrorCause.SendRpcFailed(event.message),
            ),
            effects = listOf(
                RunEffect.ShowBanner(event.message),
            ),
        )
    }

    // -- Assistant / thinking text ----------------------------------------
    //
    // Step D: rendering data lives in ChatController.sessionMessages now;
    // these handlers just track lifecycle (last activity time + the
    // marmalade-action effect dispatch on assistant text). Marmalade-action
    // dedup moved to ChatController.dispatchedRunIds — the machine emits the
    // effect on every text event containing the key, the controller dedups.

    private fun handleAssistantText(current: RunState?, event: RunEvent.AssistantText): TransitionResult {
        val streaming = requireStreamingOrPromote(current, event.runId, event.nowMs)
            ?: return debugDrop(current, "AssistantText on terminal ${current!!::class.simpleName} for run ${event.runId}")
        val effects = mutableListOf<RunEffect>()
        if (event.text.contains("\"marmalade_action\"")) {
            effects += RunEffect.DispatchMarmaladeAction(
                runId = event.runId,
                text = event.text,
                source = "streaming",
            )
        }
        return TransitionResult(
            newState = streaming.copy(lastActivityAtMs = event.nowMs),
            effects = effects,
        )
    }

    private fun handleThinkingText(current: RunState?, event: RunEvent.ThinkingText): TransitionResult {
        val streaming = requireStreamingOrPromote(current, event.runId, event.nowMs)
            ?: return debugDrop(current, "ThinkingText on terminal ${current!!::class.simpleName} for run ${event.runId}")
        return TransitionResult(
            newState = streaming.copy(lastActivityAtMs = event.nowMs),
            effects = emptyList(),
        )
    }

    // -- Tool events ------------------------------------------------------

    private fun handleToolStart(current: RunState?, event: RunEvent.ToolStart): TransitionResult {
        val streaming = requireStreamingOrPromote(current, event.runId, event.ts)
            ?: return debugDrop(current, "ToolStart on terminal ${current!!::class.simpleName} for run ${event.runId}")
        return TransitionResult(
            newState = streaming.copy(lastActivityAtMs = event.ts),
            effects = emptyList(),
        )
    }

    private fun handleToolResult(current: RunState?, event: RunEvent.ToolResult): TransitionResult {
        if (current !is RunState.Streaming) {
            return debugDrop(current, "ToolResult on non-Streaming ${current?.let { it::class.simpleName } ?: "null"}")
        }
        return TransitionResult(
            newState = current.copy(lastActivityAtMs = event.ts),
            effects = emptyList(),
        )
    }

    /**
     * `agent stream=item` — lifecycle-only under Step D. Tool chip metadata
     * is folded into ChatController.sessionMessages by the controller's
     * handleAgentItemStream pre-dispatch hook.
     */
    private fun handleAgentItem(current: RunState?, event: RunEvent.AgentItem): TransitionResult {
        val streaming = requireStreamingOrPromote(current, event.runId, event.nowMs)
            ?: return debugDrop(
                current,
                "AgentItem on terminal ${current!!::class.simpleName} itemId=${event.itemId}",
            )
        return TransitionResult(
            newState = streaming.copy(lastActivityAtMs = event.nowMs),
            effects = emptyList(),
        )
    }

    // -- Lifecycle events -------------------------------------------------

    private fun handleLifecycleStart(current: RunState?, event: RunEvent.LifecycleStart): TransitionResult {
        if (current == null) return debugDrop(current, "LifecycleStart for unknown run ${event.runId}")
        return when (current) {
            is RunState.Pending -> TransitionResult(
                newState = RunState.Accepted(
                    runId = current.runId,
                    startedAtMs = current.startedAtMs,
                    lastActivityAtMs = event.nowMs,
                ),
                effects = emptyList(),
            )
            is RunState.Accepted -> TransitionResult(
                newState = current.copy(lastActivityAtMs = event.nowMs),
                effects = emptyList(),
            )
            is RunState.Streaming -> TransitionResult(
                newState = current.copy(lastActivityAtMs = event.nowMs),
                effects = listOf(RunEffect.LogDebug("Idempotent LifecycleStart on Streaming ${current.runId}")),
            )
            // Carve-out from the "terminal stickiness" invariant: the gateway
            // emits `phase=error` with a recoverable `livenessState` (e.g.
            // "blocked") followed by a `phase=start` on the SAME runId to
            // resume. Without resurrecting here, every AssistantText /
            // ToolStart after the restart would be dropped because the run
            // stays in Errored, and streaming disappears from the UI.
            // Observed: livenessState=blocked + error="terminated" → start.
            // Resurrect to a fresh Streaming; next assistant/tool events
            // repopulate blocks (the gateway re-emits cumulative text after
            // restart). Clear the stale banner the prior error set.
            is RunState.Errored -> TransitionResult(
                newState = RunState.Streaming(
                    runId = current.runId,
                    startedAtMs = current.startedAtMs,
                    lastActivityAtMs = event.nowMs,
                ),
                effects = listOf(
                    RunEffect.ClearBanner,
                    RunEffect.LogDebug(
                        "LifecycleStart resurrecting Errored run ${current.runId} " +
                            "(cause was ${current.cause::class.simpleName})",
                    ),
                ),
            )
            // Completed/Aborted remain sticky: a completed run shouldn't
            // rewind, and a user-initiated or gateway-confirmed abort
            // shouldn't be overridden by a late server event.
            else -> warnStay(current, "LifecycleStart on ${current::class.simpleName} for ${current.runId}")
        }
    }

    private fun handleLifecycleEnd(current: RunState?, event: RunEvent.LifecycleEnd): TransitionResult {
        if (current == null) return debugDrop(current, "LifecycleEnd for unknown run ${event.runId}")
        return when (current) {
            // Step E happy path: flip the assistant message to "sent"
            // directly. No chat.history round-trip — the streaming bubble
            // already has the full content, terminal just changes its status.
            //
            // Aborting → Completed: race where the run finished server-side
            // before the abort RPC registered. User pressed stop, gateway
            // returned a final message anyway. Honour the final answer
            // (the partial content is already in the bubble) and let the
            // sendStatus flip from "streaming" → "sent" reflect that.
            is RunState.Pending, is RunState.Accepted, is RunState.Streaming,
            is RunState.Aborting -> TransitionResult(
                newState = RunState.Completed(
                    runId = current.runId,
                    startedAtMs = current.startedAtMs,
                    lastActivityAtMs = event.endedAtMs,
                    endedAtMs = event.endedAtMs,
                ),
                effects = listOf(
                    RunEffect.ClearBanner,
                    RunEffect.MarkAssistantSent(event.runId),
                    RunEffect.NotifyMessageCompleted(event.runId),
                ),
            )
            is RunState.Completed -> TransitionResult(
                newState = current,
                effects = listOf(RunEffect.LogDebug("Duplicate LifecycleEnd on Completed ${current.runId}")),
            )
            is RunState.Errored, is RunState.Aborted ->
                warnStay(current, "LifecycleEnd after ${current::class.simpleName} for ${current.runId}")
        }
    }

    private fun handleLifecycleError(current: RunState?, event: RunEvent.LifecycleErrorEvent): TransitionResult {
        if (current == null) return debugDrop(current, "LifecycleError for unknown run ${event.runId}")
        if (current.isTerminal) {
            return warnStay(current, "LifecycleError after ${current::class.simpleName} for ${current.runId}")
        }
        return TransitionResult(
            newState = RunState.Errored(
                runId = current.runId,
                startedAtMs = current.startedAtMs,
                lastActivityAtMs = event.endedAtMs,
                endedAtMs = event.endedAtMs,
                cause = ErrorCause.LifecycleError(event.message),
            ),
            effects = listOf(
                RunEffect.ShowBanner(event.message),
                RunEffect.MarkAssistantFailed(event.runId, event.message),
                RunEffect.NotifyMessageCompleted(event.runId),
            ),
        )
    }

    private fun handleAgentError(current: RunState?, event: RunEvent.AgentError): TransitionResult {
        if (current == null) return debugDrop(current, "AgentError for unknown run ${event.runId}")
        if (current.isTerminal) {
            return warnStay(current, "AgentError after ${current::class.simpleName} for ${current.runId}")
        }
        return TransitionResult(
            newState = RunState.Errored(
                runId = current.runId,
                startedAtMs = current.startedAtMs,
                lastActivityAtMs = event.nowMs,
                endedAtMs = event.nowMs,
                cause = ErrorCause.AgentError(event.message),
            ),
            effects = listOf(
                RunEffect.ShowBanner(event.message),
                RunEffect.MarkAssistantFailed(event.runId, event.message),
            ),
        )
    }

    // -- Chat state events ------------------------------------------------

    private fun handleChatFinal(current: RunState?, event: RunEvent.ChatFinal): TransitionResult {
        if (current == null) return debugDrop(current, "ChatFinal for unknown run ${event.runId}")
        return when (current) {
            // Step E: chat=final → Completed + flip status. If lifecycle=end
            // already arrived, current is Completed and we just notification-
            // fire (idempotent — MarkAssistantSent on an already-sent message
            // is a no-op flip). Aborting → Completed handled the same way as
            // in handleLifecycleEnd: race where run finished before abort
            // registered server-side.
            is RunState.Pending, is RunState.Accepted, is RunState.Streaming,
            is RunState.Aborting -> TransitionResult(
                newState = RunState.Completed(
                    runId = current.runId,
                    startedAtMs = current.startedAtMs,
                    lastActivityAtMs = event.endedAtMs,
                    endedAtMs = event.endedAtMs,
                ),
                effects = listOf(
                    // chat=final without prior lifecycle=end is the fallback
                    // path (older gateways, dropped lifecycle frames). Log so
                    // we can see if it fires often; still treat as terminal.
                    RunEffect.LogWarn(
                        "ChatFinal without prior LifecycleEnd for run ${event.runId} " +
                            "(state was ${current::class.simpleName})",
                    ),
                    RunEffect.ClearBanner,
                    RunEffect.MarkAssistantSent(event.runId),
                    RunEffect.NotifyMessageCompleted(event.runId),
                ),
            )
            is RunState.Completed -> TransitionResult(
                newState = current,
                effects = listOf(RunEffect.LogDebug("Duplicate ChatFinal on Completed ${current.runId}")),
            )
            is RunState.Errored, is RunState.Aborted ->
                warnStay(current, "ChatFinal after ${current::class.simpleName} for ${current.runId}")
        }
    }

    /**
     * `chat state=aborted` — gateway-initiated abort (e.g. `stopReason:
     * "timeout"` when the server-side request window elapsed). Distinct
     * from user-initiated [RunEvent.Abort] both in reason (`GatewayAborted`
     * vs `UserRequested`) and in effects (no `FireAbortRpc` — the gateway
     * already aborted, a client abort RPC would be redundant).
     */
    private fun handleChatAborted(current: RunState?, event: RunEvent.ChatAborted): TransitionResult {
        if (current == null) return debugDrop(current, "ChatAborted for unknown run ${event.runId}")
        if (current.isTerminal) {
            return debugDrop(current, "ChatAborted on terminal ${current::class.simpleName} for ${current.runId}")
        }
        // When the user pressed stop (current = Aborting), the gateway's
        // chat=aborted is the *confirmation* of their abort. Preserve the
        // user-initiated reason rather than stamping it as gateway-driven.
        // For all other current states (Streaming, Pending, Accepted),
        // chat=aborted is a server-side decision (e.g. server-timeout).
        val (reason, label) = if (current is RunState.Aborting) {
            AbortReason.UserRequested to "Run stopped"
        } else {
            AbortReason.GatewayAborted(event.stopReason) to
                (event.stopReason?.let { "Run aborted: $it" } ?: "Run aborted by gateway")
        }
        return TransitionResult(
            newState = RunState.Aborted(
                runId = current.runId,
                startedAtMs = current.startedAtMs,
                lastActivityAtMs = event.endedAtMs,
                endedAtMs = event.endedAtMs,
                reason = reason,
            ),
            effects = listOf(
                RunEffect.ShowBanner(label),
                // Step E: flip the partial bubble to "aborted" instead of
                // refetching. Whatever the user already saw streamed live
                // stays visible; status indicates it stopped early.
                RunEffect.MarkAssistantAborted(event.runId, reason),
                RunEffect.NotifyMessageCompleted(event.runId),
            ),
        )
    }

    private fun handleChatError(current: RunState?, event: RunEvent.ChatError): TransitionResult {
        if (current == null) return debugDrop(current, "ChatError for unknown run ${event.runId}")
        if (current.isTerminal) {
            return warnStay(current, "ChatError after ${current::class.simpleName} for ${current.runId}")
        }
        return TransitionResult(
            newState = RunState.Errored(
                runId = current.runId,
                startedAtMs = current.startedAtMs,
                lastActivityAtMs = event.endedAtMs,
                endedAtMs = event.endedAtMs,
                cause = ErrorCause.ChatStateError(event.message),
            ),
            effects = listOf(
                // Step E: flip in place; the red inline error surfaces from
                // the message's status + the appended error block, no
                // chat.history round-trip needed.
                RunEffect.MarkAssistantFailed(event.runId, event.message),
                RunEffect.NotifyMessageCompleted(event.runId),
            ),
        )
    }

    // -- Abort / seqGap ---------------------------------------------------

    private fun handleAbort(current: RunState?, event: RunEvent.Abort): TransitionResult {
        if (current == null) return debugDrop(current, "Abort for unknown run ${event.runId}")
        if (current.isTerminal) return debugDrop(current, "Abort on terminal ${current::class.simpleName}")
        if (current is RunState.Aborting) {
            return debugDrop(current, "Duplicate Abort while already Aborting ${current.runId}")
        }
        // Transition to Aborting (transitional), not Aborted (terminal).
        // The UI swaps the stop button for a spinner while in this state.
        // The terminal transition happens when the gateway acks via
        // `chat state=aborted` (handleChatAborted) or when the executor's
        // defensive ~5s timer synthesises a ChatAborted event.
        return TransitionResult(
            newState = RunState.Aborting(
                runId = current.runId,
                startedAtMs = current.startedAtMs,
                lastActivityAtMs = event.nowMs,
                abortRequestedAtMs = event.nowMs,
            ),
            effects = listOf(RunEffect.FireAbortRpc(current.runId)),
        )
    }

    private fun handleSeqGapForRun(current: RunState?, event: SeqGapForRun): TransitionResult {
        if (current == null) return debugDrop(current, "SeqGap for unknown run ${event.runId}")
        if (current.isTerminal) return debugDrop(current, "SeqGap on terminal ${current::class.simpleName}")
        // Without MarkAssistantFailed a SeqGap interruption leaves the
        // streaming bubble animating forever. The banner alone isn't
        // enough — the per-message status flip is what tells the user
        // their pending response is dead.
        return TransitionResult(
            newState = RunState.Errored(
                runId = current.runId,
                startedAtMs = current.startedAtMs,
                lastActivityAtMs = event.nowMs,
                endedAtMs = event.nowMs,
                cause = ErrorCause.SeqGap,
            ),
            effects = listOf(
                RunEffect.ShowBanner("Event stream interrupted; try refreshing."),
                RunEffect.MarkAssistantFailed(event.runId, ErrorCause.SeqGap.message),
                RunEffect.NotifyMessageCompleted(event.runId),
            ),
        )
    }

    // (Step E: handleHistoryOk / handleHistoryError deleted — terminal
    //  transitions go straight from {Pending,Accepted,Streaming} → Completed
    //  via lifecycle=end / chat=final, with MarkAssistantSent flipping the
    //  in-memory message status. No history refetch on terminal.)

    // -- Helpers ----------------------------------------------------------

    /**
     * Ensure [current] is a Streaming state.
     *
     *  - **Null (unknown runId)** → synthesize a fresh Streaming keyed by
     *    [runId]. This is critical for the gateway-race case where a
     *    server-initiated run (e.g. resumed after client reconnect, or a
     *    run under a different runId than the one the gateway ack'd)
     *    emits stream events the client never initiated. Without this
     *    synthesis every such event silently drops, leaving the user
     *    staring at a blank screen while the agent streams invisibly
     *    under the "wrong" runId.
     *  - **Pending / Accepted** → promote to Streaming on first stream
     *    event arrival.
     *  - **Streaming** → pass through.
     *  - **Terminal** (Completed/Errored/Aborted) → return null so the
     *    caller drops. We will not resurrect a terminal run from a stream
     *    event; the [handleLifecycleStart] carve-out handles the gateway's
     *    phase=error-then-start recovery case explicitly.
     */
    private fun requireStreamingOrPromote(
        current: RunState?,
        runId: RunId,
        nowMs: Long,
    ): RunState.Streaming? =
        when (current) {
            null -> RunState.Streaming(
                runId = runId,
                startedAtMs = nowMs,
                lastActivityAtMs = nowMs,
            )
            is RunState.Streaming -> current
            is RunState.Pending -> RunState.Streaming(
                runId = current.runId,
                startedAtMs = current.startedAtMs,
                lastActivityAtMs = nowMs,
            )
            is RunState.Accepted -> RunState.Streaming(
                runId = current.runId,
                startedAtMs = current.startedAtMs,
                lastActivityAtMs = nowMs,
            )
            // Aborting drops late stream events: the user's intent is to
            // stop, and whatever already streamed is in the bubble. Don't
            // promote back to Streaming — that would un-spinner the UI.
            is RunState.Aborting -> null
            is RunState.Completed, is RunState.Errored, is RunState.Aborted -> null
        }

    /**
     * Debug-log-and-do-nothing. The caller passes [current] (possibly null)
     * and the returned transition preserves it — so an event on an unknown
     * runId leaves the map untouched, and an event on a terminal runId
     * keeps the terminal state visible. Never returns `newState = null`
     * when the run already exists; that would delete a terminal state the
     * UI may still be reading.
     */
    private fun debugDrop(current: RunState?, message: String): TransitionResult =
        TransitionResult(newState = current, effects = listOf(RunEffect.LogDebug(message)))

    private fun warnStay(current: RunState, message: String): TransitionResult =
        TransitionResult(newState = current, effects = listOf(RunEffect.LogWarn(message)))
}
