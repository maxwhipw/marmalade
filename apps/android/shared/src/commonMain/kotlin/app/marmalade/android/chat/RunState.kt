package app.marmalade.android.chat

import kotlinx.serialization.json.JsonObject

/** Alias for readability — every run is keyed by a string run-id (UUID). */
typealias RunId = String

/**
 * The lifecycle state of a single agent run, keyed by [RunId].
 *
 * `Pending -> Accepted -> Streaming -> Completed` is the happy path. The
 * terminal states `Completed`, `Errored`, and `Aborted` are sticky: once
 * a run reaches one of them, no further events move it anywhere else.
 * Events that arrive after a terminal transition log at DEBUG (expected
 * gateway duplicates) or WARN (out-of-order) and do not mutate state.
 *
 * Every input for a known runId produces exactly one of:
 *   (a) a state mutation via [RunStateMachine.apply],
 *   (b) a [RunEffect.LogDebug] (intentional no-op such as a duplicate event),
 *   (c) a [RunEffect.LogWarn] (unexpected but non-fatal, e.g. late event).
 * No code path exits without one of these three — that's the
 * "no silent drops" contract.
 */
sealed interface RunState {
    val runId: RunId
    val startedAtMs: Long
    val lastActivityAtMs: Long

    /** True when this run is in a terminal state and eligible for GC. */
    val isTerminal: Boolean
        get() = when (this) {
            is Completed, is Errored, is Aborted -> true
            is Pending, is Accepted, is Streaming, is Aborting -> false
        }

    /**
     * `chat.send` has been issued but the RPC has not yet returned. No
     * stream/lifecycle events are accepted yet because the gateway may not
     * have created the run.
     */
    data class Pending(
        override val runId: RunId,
        override val startedAtMs: Long,
        override val lastActivityAtMs: Long,
    ) : RunState

    /**
     * `chat.send` has been ack'd by the gateway; the run is alive server-side
     * but no `agent` events have arrived yet. If the server assigned a
     * different run-id than the client-generated UUID, the value is captured
     * in [serverRunId] for diagnostics.
     */
    data class Accepted(
        override val runId: RunId,
        override val startedAtMs: Long,
        override val lastActivityAtMs: Long,
        val serverRunId: RunId? = null,
    ) : RunState

    /**
     * At least one `agent` stream event has been observed. Pure lifecycle
     * sentinel — rendering data (text, blocks, tools, metadata) lives in
     * `ChatController.sessionMessages` keyed by runId (Step C/D pivot).
     * Marmalade-action dedup lives in `ChatController.dispatchedRunIds`.
     */
    data class Streaming(
        override val runId: RunId,
        override val startedAtMs: Long,
        override val lastActivityAtMs: Long,
    ) : RunState

    /**
     * Transitional: user pressed Stop, we fired the `chat.abort` RPC, and
     * we're waiting for the gateway to confirm via `chat state=aborted`.
     * Distinct from terminal [Aborted] so the UI can render a spinner
     * (instead of the stop button) while the abort is in flight, rather
     * than flipping the bubble immediately and pretending the gateway
     * has already honoured the request.
     *
     * Late stream events are debug-dropped via [requireStreamingOrPromote]
     * (the bubble keeps the content the user already saw). Lifecycle and
     * chat-state events transition to the appropriate terminal:
     *  - `chat state=aborted` → [Aborted] (happy path, gateway honoured)
     *  - `chat state=final` / `lifecycle=end` → [Completed] (race: run
     *    finished before the abort registered server-side)
     *  - errors / disconnect / seqGap → corresponding terminal
     *
     * A defensive ~5s client-side timer in the controller's executor
     * forces a synthetic [RunEvent.ChatAborted] if the gateway never
     * acks, so the spinner can't lock the UI forever.
     */
    data class Aborting(
        override val runId: RunId,
        override val startedAtMs: Long,
        override val lastActivityAtMs: Long,
        val abortRequestedAtMs: Long,
    ) : RunState

    /**
     * Terminal: `lifecycle=end` / `chat state=final` arrived. Step E
     * removed the prior `CompletedPendingHistory` limbo state — the
     * assistant message is flipped to `sendStatus="sent"` directly
     * via the [RunEffect.MarkAssistantSent] effect, no chat.history
     * round-trip needed (rendering data lives in `_messages`).
     */
    data class Completed(
        override val runId: RunId,
        override val startedAtMs: Long,
        override val lastActivityAtMs: Long,
        val endedAtMs: Long,
    ) : RunState

    /**
     * Terminal: some form of error (agent stream, lifecycle phase=error,
     * chat state=error, history refetch failure, send RPC failure, or
     * `seqGap`). The exact cause is carried in [cause] so the UI can render
     * it appropriately and logs can distinguish paths.
     */
    data class Errored(
        override val runId: RunId,
        override val startedAtMs: Long,
        override val lastActivityAtMs: Long,
        val endedAtMs: Long,
        val cause: ErrorCause,
    ) : RunState

    /**
     * Terminal: user-confirmed abort. [reason] is either [AbortReason.UserRequested]
     * (the user pressed stop and the gateway acked via `chat state=aborted`)
     * or [AbortReason.GatewayAborted] (the gateway aborted the run on its
     * own initiative — typically a server-side timeout).
     */
    data class Aborted(
        override val runId: RunId,
        override val startedAtMs: Long,
        override val lastActivityAtMs: Long,
        val endedAtMs: Long,
        val reason: AbortReason,
    ) : RunState
}

/**
 * Why a run terminated in the [RunState.Errored] state. These shapes
 * correspond 1:1 to the event types on the wire so UI/telemetry can make
 * path-specific decisions without re-parsing.
 */
sealed interface ErrorCause {
    val message: String

    /** `agent stream=error` body. Transient banner territory. */
    data class AgentError(override val message: String) : ErrorCause

    /** `agent stream=lifecycle phase=error` body. Authoritative per-run. */
    data class LifecycleError(override val message: String) : ErrorCause

    /** `chat state=error` body (includes formatted HTTP errors from gateway). */
    data class ChatStateError(override val message: String) : ErrorCause

    /** The `chat.send` RPC itself threw (network, timeout, gateway down). */
    data class SendRpcFailed(override val message: String) : ErrorCause

    /** `seqGap` event received — gateway detected it dropped an outbound frame. */
    data object SeqGap : ErrorCause {
        override val message: String = "Event stream gap"
    }

    /** Fallback — shouldn't be reached in practice but keeps the type total. */
    data class Unknown(override val message: String) : ErrorCause
}

/** Why a run transitioned into [RunState.Aborted]. */
sealed interface AbortReason {
    data object UserRequested : AbortReason
    /**
     * Gateway emitted `chat state=aborted` (typically with
     * `stopReason: "timeout"` when the server-side request window
     * elapsed before the model replied, but the field is carried
     * through so future reasons surface here too).
     */
    data class GatewayAborted(val stopReason: String?) : AbortReason
}
