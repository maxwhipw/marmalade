package app.marmalade.android.ui.chat

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.chat.messages.text

/**
 * LazyColumn driver for the chat thread. Flattens each [ChatMessage]'s
 * parts into a row sequence (per design decision (c)) — text/reasoning
 * segments render as bubbles, tool calls render as standalone top-level
 * cards.
 *
 * The list is a **reverse-layout LazyColumn**: item index 0 renders at the
 * BOTTOM and the scroll anchor is measured from the viewport's bottom
 * edge. That makes bottom-pinning structural instead of corrective —
 * keyboard open/close (the viewport resizing under `imePadding`),
 * tab-switch state restoration, and the streaming bubble growing all keep
 * the list at the bottom with no scroll math. The previous forward-layout
 * implementation chased the bottom with scroll-correction effects and
 * dropped the anchor on exactly those events (maintainer, on-device 2026-07-02);
 * regression-pinned by ChatMessageListScrollTest.
 *
 * Auto-stick: when the user is at (or near) the bottom, newly INSERTED
 * items re-pin via scrollToItem(0) — insertion keeps the anchor on the
 * previously-visible item, so without this nudge a new bubble would land
 * below the fold. Scrolling up to read disengages naturally (position is
 * the state; there is no separate flag to get stale).
 */
@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    onBlockResponse: (String) -> Unit,
    onImageTap: (url: String, alt: String?) -> Unit,
    onBubbleAction: (ChatMessage, BubbleAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    /** True when chat WS is fully connected. Gates the activity indicator so
     *  it doesn't spin forever after a mid-turn drop with no message.complete
     *  to clear the pending bubble's `isStreaming` flag. */
    isChatConnected: Boolean = true,
    /** True between `message.start` and `message.complete` / `error` —
     *  ChatController.isStreaming. Distinct from a Room-derived
     *  `messages.any{pending}` because it flips before the first delta
     *  is persisted, eliminating the blank-window at the top of a turn. */
    isStreaming: Boolean = false,
    /** True when the gateway is mid `session.compress`. Swaps the activity
     *  verb from the generic "Thinking…" pool to "Compacting…" so a long
     *  compress pause reads correctly (matches desktop's behavior at
     *  thread.tsx:418 — `$compactionActive`). */
    isCompacting: Boolean = false,
    /** Id of the message currently being read aloud, or null if nothing is
     *  playing. Used to show the "Stop reading" / speaker indicator on the
     *  active bubble. */
    speakingMessageId: String? = null,
    /** Id of the message the transcript was just anchored to (a search-result
     *  jump). Its bubble wears a transient focus ring; null means nothing is
     *  highlighted, and clearing it fades the ring out. */
    highlightedMessageId: String? = null,
    modifier: Modifier = Modifier,
) {
    // Reverse layout: firstVisibleItem is the BOTTOM-most visible item.
    // "Near bottom" = the bottom item is index 0 or 1 (tail spacer /
    // newest row) and not scrolled up past the slop within it.
    val nearBottomSlopPx = with(LocalDensity.current) { NEAR_BOTTOM_SLOP.toPx() }
    val nearBottom by remember(listState, nearBottomSlopPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex <= 1 &&
                listState.firstVisibleItemScrollOffset <= nearBottomSlopPx
        }
    }

    // Rebuilds when the visible (non-hidden) message list changes.
    // toRows() is cheap; recomputing on every recomposition is fine
    // until a message has hundreds of parts.
    //
    // segIdx is the row's position WITHIN its own message's toRows() output
    // (not the global flattened position) — see the "bubble:" key below.
    // A per-message segment index keeps a bubble's key stable as OTHER
    // messages' segment counts change (e.g. an earlier message streams in a
    // mid-message tool call, 1 segment -> 2). A global index would shift
    // every later bubble's key on that edit, forcing Compose to treat them
    // as brand-new items and drop remembered state / jump scroll position.
    //
    // chatRows() is shared with anchorListIndex() (ChatAnchorIndex.kt) so the
    // list's emission and the anchor's index math can never drift apart.
    val rows = remember(messages) { chatRows(messages) }

    // Activity indicator state: render a pulsing ActivityBubble below the
    // last bubble whenever any message is mid-turn (matches upstream
    // desktop's `ActivityTimerText` gating on `busy`). The activity verb
    // tracks the latest pending message's streamingActivity hint; defaults
    // to "starting" when the bubble exists but no event has set a phase yet.
    val streamingMessage = remember(messages) { messages.lastOrNull { it.pending } }
    // Keep the gate in one place: the anchor index math has to know whether
    // this extra item is present (showChatActivityIndicator, same condition).
    // Show the indicator when EITHER:
    //  - the controller says a turn is in flight (message.start fired,
    //    message.complete/error hasn't — covers the start-to-first-delta
    //    window where no pending bubble exists in Room yet), OR
    //  - a Room row is still streaming (covers warm reads after process
    //    restart while events catch up).
    // Drop the pill on disconnect so it doesn't spin forever when the WS
    // dies mid-turn with no message.complete to clear the row.
    val showActivityIndicator = showChatActivityIndicator(messages, isChatConnected, isStreaming)
    val tailItemCount = if (showActivityIndicator) rows.size + 1 else rows.size

    // Re-pin on item insertion: a newly inserted index-0 item lands below
    // the fold (Lazy keeps the anchor on the previously-visible item), so
    // nudge back to the bottom — but only when the user is already there.
    // Streaming TEXT growth needs no handling: the growing bubble is
    // anchored at the bottom edge and grows upward.
    LaunchedEffect(tailItemCount) {
        if (tailItemCount > 0 && nearBottom) listState.scrollToItem(0)
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxSize(),
    ) {
        // Emission order is bottom-up: index 0 (the tail spacer) renders at
        // the visual bottom, rows are emitted newest-first.
        item("tail-spacer") { Spacer(Modifier.height(8.dp)) }
        if (showActivityIndicator) {
            // Key the item by the streaming message's id (when one exists)
            // so a fresh turn re-rolls the verb. With a stable
            // "activity-indicator" key, ActivityBubble's `remember(activity)`
            // survives across turns, sticking on the previous turn's verb
            // when the same activity string repeats (e.g. two consecutive
            // "starting" rolls). Fall back to "pre-bubble" while the first
            // delta hasn't landed yet.
            item(key = "activity-indicator:${streamingMessage?.id ?: "pre-bubble"}") {
                // Default to "starting" when no event has set a phase yet —
                // ActivityVocabulary.verbsFor(null) returns the DEFAULT verbs
                // ("Working", "Tinkering", …) which is wrong for a fresh turn
                // that hasn't received any deltas yet.
                // isCompacting wins over the streamingActivity verb so the
                // long compress pause reads "Compacting…" instead of the
                // default thinking pool.
                val activityVerb = when {
                    isCompacting -> "compacting"
                    else -> streamingMessage?.streamingActivity ?: "starting"
                }
                ActivityBubble(activity = activityVerb)
            }
        }
        rows.asReversed().forEach { (row, message, segIdx) ->
            when (row) {
                is MessageRow.Bubble -> {
                    item(key = "bubble:${message.id}:$segIdx") {
                        // A System-role row is transcript META (the daemon's
                        // durable effort.clamped line), not a participant's
                        // message — quiet centred line, no bubble. Same key
                        // namespace: it is still one message's one segment.
                        if (message.role == ChatRole.System) {
                            SystemNoticeLine(text = message.text())
                        } else {
                            MessageBubble(
                                message = message,
                                segmentParts = row.parts,
                                isLastSegment = row.isLastSegment,
                                onBlockResponse = onBlockResponse,
                                onImageTap = onImageTap,
                                onLongPress = { action -> onBubbleAction(message, action) },
                                isSpeaking = speakingMessageId == message.id,
                                isFocused = highlightedMessageId == message.id,
                            )
                        }
                    }
                }
                is MessageRow.Tool -> {
                    item(key = "tool:${row.part.toolCallId}") {
                        ToolCallCard(part = row.part)
                    }
                }
                // Keyed off the FIRST call in the run: stable as the run grows
                // mid-turn (a new call appends, it doesn't re-key the row), so
                // the expand state and scroll anchor survive streaming.
                is MessageRow.ToolRun -> {
                    item(key = "toolrun:${row.entries.first().call.toolCallId}") {
                        ToolRunCard(entries = row.entries)
                    }
                }
                is MessageRow.Subagent -> {
                    item(key = "subagent:${row.entry.call.toolCallId}") {
                        SubagentCard(entry = row.entry)
                    }
                }
                // The asked → answered → recorded lifecycle. Keyed off the tool
                // call id like every other tool row, so the card survives the
                // ask settling into its answer without re-keying.
                is MessageRow.Prompt -> {
                    item(key = "prompt:${row.part.toolCallId}") {
                        PromptRecordCard(part = row.part)
                    }
                }
            }
        }
    }
}

/** How far the user may be scrolled up (within the bottom-most item) and
 *  still count as "at the bottom" for auto-stick purposes. */
private val NEAR_BOTTOM_SLOP = 80.dp
