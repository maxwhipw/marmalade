package app.marmalade.android.chat

import kotlinx.serialization.json.JsonObject

/**
 * Events that drive the [RunStateMachine]. Each maps to a distinct signal
 * from either the gateway wire or the client itself.
 *
 * `runId == null` means the event is a broadcast that applies to every
 * non-terminal run in the machine's map (currently only `SeqGap`).
 * Targeted events carry the specific runId they apply to.
 *
 * Event shapes mirror the wire payloads we parse out of `agent` / `chat`
 * events, but with the parsing already done. The ChatController-side
 * adapter is responsible for extracting fields from JSON and building
 * events; the machine only sees typed values.
 */
sealed interface RunEvent {
    /** The run this event applies to, or `null` for broadcasts. */
    val runId: RunId?

    // -- Send lifecycle (client-initiated) ---------------------------------

    /** User sent a message; `chat.send` RPC has been dispatched. */
    data class Send(
        override val runId: RunId,
        val nowMs: Long,
    ) : RunEvent

    /** `chat.send` RPC returned successfully; [serverRunId] may differ from client UUID. */
    data class SendAcked(
        override val runId: RunId,
        val serverRunId: RunId?,
        val nowMs: Long,
    ) : RunEvent

    /** `chat.send` RPC threw (transport, timeout, gateway-side error). */
    data class SendFailed(
        override val runId: RunId,
        val message: String,
        val nowMs: Long,
    ) : RunEvent

    // -- Agent stream events ----------------------------------------------

    /** `agent stream=assistant`. Cumulative text for the current assistant turn. */
    data class AssistantText(
        override val runId: RunId,
        val text: String,
        val nowMs: Long,
    ) : RunEvent

    /** `agent stream=thinking`. Cumulative reasoning text. */
    data class ThinkingText(
        override val runId: RunId,
        val text: String,
        val nowMs: Long,
    ) : RunEvent

    /** `agent stream=tool phase=start`. */
    data class ToolStart(
        override val runId: RunId,
        val toolCallId: String,
        val name: String,
        val args: JsonObject?,
        val ts: Long,
    ) : RunEvent

    /** `agent stream=tool phase=result`. */
    data class ToolResult(
        override val runId: RunId,
        val toolCallId: String,
        val isError: Boolean?,
        val ts: Long,
    ) : RunEvent

    /**
     * `agent stream=item`. Unified structured execution-item event the
     * gateway emits alongside (not instead of) `stream=tool` events.
     * Carries a richer metadata view of any discrete unit of work —
     * tool calls, commands, patches, searches, analyses — keyed by a
     * prefixed `itemId` (e.g. `tool:toolu_...`, `search:...`).
     *
     * Used client-side to enrich the tool chip UI with a human-readable
     * `title`, structured `status` (including `blocked` for interactive
     * tools awaiting input), timing, and optional `summary`.
     */
    data class AgentItem(
        override val runId: RunId,
        val itemId: String,
        val phase: String,    // "start" | "update" | "end"
        val kind: String,     // "tool" | "command" | "patch" | "search" | "analysis"
        val status: String?,  // "running" | "blocked" | "completed" | "failed"
        val title: String?,
        val summary: String?,
        val startedAtMs: Long?,
        val endedAtMs: Long?,
        val nowMs: Long,
    ) : RunEvent

    /** `agent stream=lifecycle phase=start`. Idempotent ack of run liveness. */
    data class LifecycleStart(
        override val runId: RunId,
        val nowMs: Long,
    ) : RunEvent

    /** `agent stream=lifecycle phase=end`. The authoritative per-run terminal signal. */
    data class LifecycleEnd(
        override val runId: RunId,
        val endedAtMs: Long,
    ) : RunEvent

    /** `agent stream=lifecycle phase=error`. Authoritative per-run error signal. */
    data class LifecycleErrorEvent(
        override val runId: RunId,
        val message: String,
        val endedAtMs: Long,
    ) : RunEvent

    /** `agent stream=error`. Secondary error signal; banner territory. */
    data class AgentError(
        override val runId: RunId,
        val message: String,
        val nowMs: Long,
    ) : RunEvent

    // -- Chat event states ------------------------------------------------

    /**
     * `chat state=final`. In the new model this is secondary to lifecycle=end;
     * it is a fallback terminal path if lifecycle=end was missed. Step E
     * removed the chat.history refetch on terminal — both ChatFinal and
     * LifecycleEnd now flip the assistant message to `sendStatus="sent"`
     * directly via [RunEffect.MarkAssistantSent], no round-trip needed.
     */
    data class ChatFinal(
        override val runId: RunId,
        val endedAtMs: Long,
    ) : RunEvent

    /** `chat state=error`. */
    data class ChatError(
        override val runId: RunId,
        val message: String,
        val endedAtMs: Long,
    ) : RunEvent

    /**
     * `chat state=aborted`. Gateway-initiated abort — typically a
     * server-side timeout (`stopReason: "timeout"`). Distinct from
     * [Abort] which is user-initiated. Transitions the run to
     * [RunState.Aborted] with [AbortReason.GatewayAborted], so the UI
     * can tell a timeout apart from a clean completion.
     */
    data class ChatAborted(
        override val runId: RunId,
        val stopReason: String?,
        val endedAtMs: Long,
    ) : RunEvent

    // -- Client-driven events ---------------------------------------------

    /** User tapped abort; `chat.abort` RPC is about to be issued. */
    data class Abort(
        override val runId: RunId,
        val nowMs: Long,
    ) : RunEvent

    // (Step E: HistoryRefetchOk / HistoryRefetchError deleted. Terminal
    //  transitions now flip sendStatus on the in-memory message via
    //  RunEffect.MarkAssistant* — no chat.history round-trip on terminal.)

    // -- Broadcast events -------------------------------------------------

    /** `seqGap` event — gateway detected a dropped outbound frame. Broadcast. */
    data class SeqGap(
        val nowMs: Long,
    ) : RunEvent {
        override val runId: RunId? = null
    }
}
