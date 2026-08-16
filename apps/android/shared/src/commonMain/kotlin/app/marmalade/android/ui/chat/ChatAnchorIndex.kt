package app.marmalade.android.ui.chat

import app.marmalade.android.chat.ChatAnchor
import app.marmalade.android.chat.messages.ChatMessage

/**
 * One flattened transcript row: the row itself, the message it came from, and
 * the row's index WITHIN that message's own [toRows] output (the `segIdx` that
 * namespaces the LazyColumn bubble key — see .claude/rules/chat-ui.md).
 */
data class ChatRowEntry(
    val row: MessageRow,
    val message: ChatMessage,
    val segIdx: Int,
)

/**
 * The transcript's row model — the SINGLE source of truth for how messages
 * flatten into LazyColumn items. [ChatMessageList] emits exactly this list
 * (reversed), and [anchorListIndex] converts a position in it back to a list
 * index. Keeping both on one function is the point: if they ever drift, a jump
 * lands on the wrong bubble and nothing crashes to say so.
 */
fun chatRows(messages: List<ChatMessage>): List<ChatRowEntry> =
    messages.flatMap { msg ->
        msg.toRows().mapIndexed { segIdx, row -> ChatRowEntry(row, msg, segIdx) }
    }

/**
 * Whether [ChatMessageList] renders the activity indicator, which occupies one
 * extra list item between the tail spacer and the newest row. Extracted so the
 * anchor index math can account for it without re-deriving the condition.
 */
fun showChatActivityIndicator(
    messages: List<ChatMessage>,
    isChatConnected: Boolean,
    isStreaming: Boolean,
): Boolean = isChatConnected && (isStreaming || messages.any { it.pending })

/**
 * Number of leading LazyColumn items that are not transcript rows: the tail
 * spacer at index 0, plus the activity indicator when it is shown.
 */
private fun anchorBaseIndex(showActivityIndicator: Boolean): Int =
    if (showActivityIndicator) 2 else 1

/**
 * The LazyColumn index of [targetMessageId]'s row, or null when that message
 * contributes no row to [messages] (filtered out, or not hydrated yet).
 *
 * The list is REVERSE-layout and emits `chatRows(...).asReversed()` after the
 * base items, so row `i` of the forward row list sits at
 * `base + (rows.size - 1 - i)`.
 *
 * Target row = the message's first [MessageRow.Bubble] when it has one (that
 * is the prose the maintainer is looking for), else its first row — a tool-only message
 * still has somewhere to land.
 */
fun anchorListIndex(
    messages: List<ChatMessage>,
    showActivityIndicator: Boolean,
    targetMessageId: String,
): Int? {
    val rows = chatRows(messages)
    val own = rows.withIndex().filter { it.value.message.id == targetMessageId }
    if (own.isEmpty()) return null
    val target = own.firstOrNull { it.value.row is MessageRow.Bubble } ?: own.first()
    return anchorBaseIndex(showActivityIndicator) + (rows.size - 1 - target.index)
}

/**
 * Resolve [anchor] against the messages currently on screen.
 *
 * `messageId` first — it is the daemon-minted Room primary key, so it is an
 * exact hit. Falling back to "the first message at or after `seq`" keeps the
 * jump useful when the exact row is gone (compacted) or when the caller only
 * knew a position: seq is monotonic per session, so the nearest later message
 * is the right neighbourhood. Local-only rows (`seq == 0`: un-acked outbox
 * bubbles, client chrome) are excluded from the fallback — they carry no
 * server ordering and would match every anchor.
 *
 * Returns null while the target has not arrived yet; the caller keeps the
 * anchor pending and retries as hydration fills the list.
 */
fun resolveAnchorTarget(messages: List<ChatMessage>, anchor: ChatAnchor): ChatMessage? {
    anchor.messageId?.let { id ->
        messages.firstOrNull { it.id == id }?.let { return it }
    }
    return messages
        .filter { it.seq > 0L && it.seq >= anchor.seq }
        .minByOrNull { it.seq }
}

/**
 * How many messages sit between the anchored message and the end of the
 * transcript — the "jumped N messages back" honesty pill. Counts MESSAGES,
 * not rows: "74 messages back" is a distance the maintainer can reason about; "212 rows
 * back" is an implementation detail.
 */
fun messagesBackFrom(messages: List<ChatMessage>, targetMessageId: String): Int {
    val idx = messages.indexOfFirst { it.id == targetMessageId }
    return if (idx < 0) 0 else messages.size - 1 - idx
}
