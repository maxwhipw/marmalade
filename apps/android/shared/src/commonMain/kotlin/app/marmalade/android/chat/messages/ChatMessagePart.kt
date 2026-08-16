package app.marmalade.android.chat.messages

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One discrete piece of a [ChatMessage]'s content. Sealed hierarchy matching
 * desktop's `ChatMessagePart` (which derives from `@assistant-ui/react`'s
 * `ThreadMessageLike.content` union — see
 * `hermes-agent upstream: apps/desktop/src/lib/chat-messages.ts:8`).
 *
 * Streaming parts ([Text], [Reasoning]) coalesce within the current
 * "segment" — see [appendStreamPart] in `StreamCoalescer.kt`. A segment is
 * bounded by any non-streaming part ([ToolCall], [Image], [File]), so a tool
 * call opens a fresh text part for the next narration burst, while a
 * reasoning fragment between two text deltas does NOT split the sentence.
 *
 * All members are immutable `data class`es — updates return new instances
 * (matches desktop's spread-based updates so the UI can diff cheaply).
 */
sealed class ChatMessagePart {
    /** A streaming assistant/user text fragment. */
    data class Text(val text: String) : ChatMessagePart()

    /** A streaming "thinking" fragment — extended reasoning visible to the user. */
    data class Reasoning(val text: String) : ChatMessagePart()

    /**
     * An agent tool invocation, in any phase of its lifecycle. While a tool is
     * still running, [result] is null and [isError] is false. On `tool.complete`
     * the same part gets `result` and `isError` filled in (via [upsertToolPart],
     * future commit).
     *
     * - [toolCallId] — server's stable id when available; a synthesized
     *   `live-tool:<name>:<n>` when the stream started without one.
     * - [toolName] — e.g. `read_file`, `terminal`, `image_generate`.
     * - [args] — merged argument object. Matches desktop's `Record<string,
     *   unknown>`; deserialized server-side params + any client-supplied
     *   context/preview enrichment.
     * - [argsText] — `JSON.stringify(args)` cached as a string for the
     *   collapsible-tool-card title. Empty when [args] is empty.
     * - [result] — server's tool-result object when the tool has completed;
     *   null while still running.
     * - [isError] — true if the completion payload signaled error.
     */
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
        val argsText: String,
        val result: JsonElement? = null,
        val isError: Boolean = false,
        /**
         * The `tool_use` id of the SUBAGENT SPAWN this call belongs to, or null
         * when the call was made by the main agent. Comes straight off the
         * daemon's `parent_tool_use_id` (marmalade `ec6ea8e`).
         *
         * Subagent tool frames arrive on the same stream as the parent's and
         * are distinguishable ONLY by this field — without it a subagent's
         * work is indistinguishable from the main agent's, which is exactly
         * how it rendered before 2026-07-26. The UI nests a call under the
         * [ChatMessagePart.ToolCall] whose `toolCallId` equals this value.
         */
        val parentToolUseId: String? = null,
        /**
         * The approval choice the maintainer gave for this call — `once` / `session` /
         * `always` / `deny` — or null when nobody was asked.
         *
         * `approval.request`/`approval.resolved` are transient on the wire: they
         * drive the docked card and then vanish, so before the daemon started
         * stamping this on `tool.complete` the fact that the maintainer personally
         * authorised a command survived nowhere. Null is the overwhelmingly
         * common case (approvals default to auto) and means exactly "no human
         * was asked" — never "denied", and never "approved silently".
         */
        val approvalChoice: String? = null,
    ) : ChatMessagePart() {
        /** True when this call IS a subagent spawn (the Agent/Task tool). Its
         *  own `toolCallId` is what children point at via [parentToolUseId]. */
        val isSubagentSpawn: Boolean get() = toolName == "Task" || toolName == "Agent"

        /**
         * True when this call IS the agent asking the maintainer a question.
         *
         * `AskUserQuestion` rides the SDK's `canUseTool` bridge, so the ask and
         * the answer are a normal tool pair on the wire — persisted in the
         * session transcript and replayed on subscribe, unlike the transient
         * `clarify.request` that drives the docked card. That makes this row the
         * durable record of a decision: [args] holds the questions, [result]
         * holds the maintainer's answers. See `ClarifyPrompt.parseClarifyAnswers`.
         */
        val isAgentQuestion: Boolean get() = toolName == "AskUserQuestion"
    }

    /** An inline image (URL or data:uri). [alt] is the assistant's caption. */
    data class Image(val image: String, val alt: String? = null) : ChatMessagePart()

    /** An inline file attachment by reference (no inline binary on the wire). */
    data class File(val name: String, val source: String, val mimeType: String? = null) : ChatMessagePart()
}
