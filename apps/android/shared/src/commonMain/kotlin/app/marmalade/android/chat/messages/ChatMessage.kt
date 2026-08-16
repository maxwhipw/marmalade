package app.marmalade.android.chat.messages

/**
 * One rendered chat-bubble's worth of state. Matches desktop's `ChatMessage`
 * in `hermes-agent upstream: apps/desktop/src/lib/chat-messages.ts:10`.
 *
 * A `ChatMessage` is *render state*, not wire state. It can grow during a
 * single agent turn — streaming text deltas append to the most-recent
 * matching part inside its current "segment", tool-call lifecycle events
 * upsert one part by stable id, and tool-result events fill in the same part
 * later. Segment boundaries are non-streaming parts (tool-call, image, file)
 * — see [appendStreamPart] for the coalescing rules.
 *
 * - [id] — stable per turn; matches the server's message id when known, or
 *   a synthesized "local-..." id for client-side errors / queued user
 *   messages.
 * - [role] — Assistant / User / Tool / System. The chat UI renders bubbles
 *   differently per role.
 * - [parts] — append-only-from-the-end sequence of [ChatMessagePart]s. Order
 *   is load-bearing for narration sequencing. Treat as immutable; updates
 *   return a new list (matches desktop's spread-based updates).
 * - [timestamp] — server-assigned message creation time, milliseconds since
 *   epoch. Null for parts of a still-streaming turn.
 * - [pending] — true if the assistant is still producing this message (no
 *   terminal `message.complete` yet).
 * - [error] — short user-visible explanation of why this message failed
 *   (set on local errors / aborted runs / approval denied).
 * - [branchGroupId] — used by the message-branching UI (siblings sharing a
 *   parent user prompt). Null when the message isn't part of a branch.
 * - [hidden] — true for messages that exist in history but should not
 *   render (e.g. tool-only rows already represented in the previous
 *   assistant bubble).
 * - [attachmentRefs] — composer attachment ref strings (`@file:...`,
 *   `@image:...`) sent with this user message; the assistant bubble never
 *   has these. Surfaced to the UI for the attachment chips above the bubble.
 */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val parts: List<ChatMessagePart>,
    val timestamp: Long? = null,
    val pending: Boolean = false,
    val error: String? = null,
    val branchGroupId: String? = null,
    val hidden: Boolean = false,
    val attachmentRefs: List<String> = emptyList(),
    /** Lifecycle of a USER message in the local queue:
     *  `"sent"` (default), `"sending"`, `"queued"`, `"failed"`. Drives the
     *  delivery-state indicator in the composer tail. The assistant bubble
     *  uses [pending] instead — these are orthogonal concerns. */
    val sendStatus: String = "sent",
    /** Id of the message this one quote-replies to. Null when not a reply. */
    val replyToId: String? = null,
    /** True if this user message originated from voice transcription
     *  rather than typing — drives the mic-icon affordance on the bubble. */
    val voiceOrigin: Boolean = false,
    /** Latest activity hint for the streaming assistant bubble — `"thinking"`,
     *  `"writing"`, `"tool:<NAME>"`, `"starting"`, or null when idle. Drives
     *  the activity pill above the streaming bubble. Written by the event
     *  handlers as the run proceeds (see ChatController rewrite for source). */
    val streamingActivity: String? = null,
    /** Verbatim wire-protocol JSON the bubble was last derived from — kept
     *  for the long-press debug-info popup and forward-compat fallback
     *  rendering. Null on locally-composed messages. */
    val rawPayloadJson: String? = null,
    /** Server-minted per-session event seq (marmaladed identity plan P1) —
     *  THE ordering key. 0 = local-only message the server hasn't
     *  acknowledged yet (sorts by timestamp after all seq-bearing rows).
     *  seq orders; timestamps are metadata. */
    val seq: Long = 0L,
    /** Whether this assistant message can serve as a session.fork cut
     *  (daemon `has_cut_point` on message.complete). false = hide the
     *  "Branch in new chat" affordance (fork-copied bubbles and no-uuid
     *  harnesses reject the cut); null = pre-flag transcript — offer and
     *  let the daemon decide (legacy behavior). */
    val hasCutPoint: Boolean? = null,
    /** True when this user message was sent mid-turn via session.steer
     *  (T2 #6) — renders a "steered" marker on the bubble. */
    val steered: Boolean = false,
    /** The daemon-stamped origin.source of a USER message: "text" | "voice" |
     *  "cron" | "agent" | … A "cron" turn renders a "Scheduled" marker; an
     *  "agent" turn (another session sent this prompt) renders "from session X"
     *  off [originDeviceId]. Never spoofable — the daemon mints it. Null on
     *  pre-flag rows and locally-composed messages. */
    val originSource: String? = null,
    /** The daemon-stamped origin.deviceId of a USER message. For a
     *  source="agent" turn this is "session:<sender>" — the sending session's
     *  id, surfaced as "from session X". Null = this device / not reported. */
    val originDeviceId: String? = null,
) {
    companion object {
        /** Build an empty assistant-pending bubble — used when a new turn starts. */
        fun assistantPending(id: String, timestamp: Long? = null, seq: Long = 0L): ChatMessage =
            ChatMessage(id = id, role = ChatRole.Assistant, parts = emptyList(), timestamp = timestamp, pending = true, seq = seq)

        /** Build a user message with raw text + optional attachment refs. */
        fun user(
            id: String,
            text: String,
            attachmentRefs: List<String> = emptyList(),
            timestamp: Long? = null,
        ): ChatMessage = ChatMessage(
            id = id,
            role = ChatRole.User,
            parts = if (text.isEmpty()) emptyList() else listOf(ChatMessagePart.Text(text)),
            timestamp = timestamp,
            attachmentRefs = attachmentRefs,
        )
    }
}

/**
 * Chat participant roles. Maps to marmalade-agent's server `SessionMessage.role`
 * (`hermes-agent upstream: apps/desktop/src/types/hermes.ts:SessionMessage`).
 */
enum class ChatRole { User, Assistant, System, Tool }

/** Concatenate all `Text` parts in order — the plain-text view of a message. */
fun ChatMessage.text(): String = parts.asSequence()
    .filterIsInstance<ChatMessagePart.Text>()
    .joinToString(separator = "") { it.text }

/** True if any tool-call part is attached to this message. */
fun ChatMessage.hasToolPart(): Boolean = parts.any { it is ChatMessagePart.ToolCall }
