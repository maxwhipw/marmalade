package app.marmalade.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.Composable
import app.marmalade.android.ui.components.MarmaladeMenu
import app.marmalade.android.ui.components.MarmaladeMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Role-aware bubble container for ONE contiguous segment of a
 * [ChatMessage]'s parts (text + reasoning interleaved between tool
 * calls). Tool calls / images / files surface as top-level row items in
 * [ChatMessageList], so this only renders streaming-class parts.
 *
 * @param message the parent [ChatMessage] — used for role styling,
 *  streaming activity hint, and long-press actions.
 * @param segmentParts the slice of `message.parts` to render here
 *  (already filtered to only `Text` / `Reasoning` by the caller).
 * @param isLastSegment true when this bubble is the final segment of
 *  the message — only the last segment shows the activity pill and the
 *  blinking caret (mid-message segments are finalized text between
 *  tool calls).
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    segmentParts: List<ChatMessagePart>,
    isLastSegment: Boolean,
    onBlockResponse: (String) -> Unit,
    onImageTap: (url: String, alt: String?) -> Unit,
    onLongPress: (BubbleAction) -> Unit,
    isSpeaking: Boolean = false,
    /** True while this bubble is the message the transcript was just anchored
     *  to. Draws a transient focus ring (design-lab `session-search` lab 3,
     *  frame 1); flipping it back to false fades the ring out. */
    isFocused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatRole.User
    val isAssistant = message.role == ChatRole.Assistant
    val bgColor = when (message.role) {
        ChatRole.User -> MaterialTheme.marmaladeColors.userBubble
        ChatRole.Assistant -> MaterialTheme.marmaladeColors.assistantBubble
        ChatRole.System -> MaterialTheme.colorScheme.surfaceContainer
        ChatRole.Tool -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val textColor = when (message.role) {
        ChatRole.User -> MaterialTheme.marmaladeColors.onUserBubble
        ChatRole.Assistant -> MaterialTheme.marmaladeColors.onAssistantBubble
        // System / Tool bubbles sit on surfaceContainer — keep on-surface ink.
        else -> MaterialTheme.colorScheme.onSurface
    }
    var menuExpanded by remember { mutableStateOf(false) }

    // Anchor focus ring. Drawn as border + shadow ON the bubble surface, not
    // as an outer padded ring: both are paint-only, so the highlight can't
    // reflow the transcript the instant after we scrolled to it. Snaps in
    // (the jump should read as arrival), fades out slowly.
    val focusRing = MaterialTheme.marmaladeColors.focusRing
    val focus by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = if (isFocused) 120 else 520),
        label = "anchorFocus",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            // Activity indication is owned by the singleton ActivityBubble
            // rendered below the message list (ChatMessageList.kt:194), NOT
            // by an inline pill on each pending bubble. The inline pill +
            // the bottom bubble both rendering for the same in-flight turn
            // produced the "Running terminal..." thin descriptor + an empty
            // surface above the proper "Operating terminal" ActivityBubble
            // (on-device 2026-06-30).

            Box {
                Surface(
                    shape = bubbleShape(isUser),
                    color = bgColor,
                    modifier = Modifier
                        .then(
                            if (focus > 0f) {
                                Modifier
                                    .shadow(
                                        elevation = 10.dp * focus,
                                        shape = bubbleShape(isUser),
                                        clip = false,
                                        ambientColor = focusRing,
                                        spotColor = focusRing,
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = focusRing.copy(alpha = 0.9f * focus),
                                        shape = bubbleShape(isUser),
                                    )
                            } else {
                                Modifier
                            }
                        )
                        .pointerInput(message.id) {
                        detectTapGestures(
                            onLongPress = { menuExpanded = true },
                        )
                    },
                ) {
                    Column(modifier = Modifier.padding(bubblePadding)) {
                        segmentParts.forEach { part ->
                            when (part) {
                                is ChatMessagePart.Text -> when (message.role) {
                                    ChatRole.User -> UserTextPart(
                                        text = part.text,
                                        voiceOrigin = message.voiceOrigin,
                                        textColor = textColor,
                                        steered = message.steered,
                                        originSource = message.originSource,
                                        originDeviceId = message.originDeviceId,
                                    )
                                    else -> AssistantTextPart(
                                        text = part.text,
                                        textColor = textColor,
                                        onBlockResponse = onBlockResponse,
                                        isStreaming = isAssistant && isLastSegment && message.pending,
                                        revealKey = message.id,
                                    )
                                }
                                is ChatMessagePart.Reasoning -> ReasoningPart(
                                    text = part.text,
                                    streaming = isLastSegment && message.pending,
                                    textColor = textColor,
                                )
                                is ChatMessagePart.Image -> ImagePart(
                                    part = part,
                                    onTap = onImageTap,
                                )
                                is ChatMessagePart.File -> FilePart(part = part)
                                // ToolCall renders as a top-level row item — not here.
                                is ChatMessagePart.ToolCall -> Unit
                            }
                        }
                        // The streaming caret is owned by AssistantTextPart now,
                        // so it rides the paced-reveal cursor at the tail of the
                        // revealed text (design proposal TOPIC 1 (c)).
                        message.error?.takeIf { isLastSegment }?.let { err ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = err,
                                color = MaterialTheme.marmaladeColors.bannerError,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                MarmaladeMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    MarmaladeMenuItem(
                        label = "Copy",
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            menuExpanded = false
                            onLongPress(BubbleAction.Copy)
                        },
                    )
                    if (isUser) {
                        MarmaladeMenuItem(
                            label = "Edit & resend",
                            icon = Icons.Outlined.Edit,
                            onClick = {
                                menuExpanded = false
                                onLongPress(BubbleAction.EditResend)
                            },
                        )
                    }
                    if (isAssistant) {
                        MarmaladeMenuItem(
                            label = if (isSpeaking) "Stop reading" else "Read aloud",
                            icon = if (isSpeaking) Icons.AutoMirrored.Outlined.VolumeOff
                                else Icons.AutoMirrored.Outlined.VolumeUp,
                            onClick = {
                                menuExpanded = false
                                onLongPress(BubbleAction.ReadAloud)
                            },
                        )
                        // Hidden when the daemon marked this bubble cut-less
                        // (has_cut_point=false: fork-copied rows, no-uuid
                        // harnesses) — session.fork would reject the cut.
                        // null (pre-flag transcript) still offers it and lets
                        // the daemon decide.
                        if (message.hasCutPoint != false) {
                            MarmaladeMenuItem(
                                label = "Branch in new chat",
                                icon = Icons.AutoMirrored.Outlined.CallSplit,
                                onClick = {
                                    menuExpanded = false
                                    onLongPress(BubbleAction.Branch)
                                },
                            )
                        }
                    }
                    MarmaladeMenuItem(
                        label = "Share",
                        icon = Icons.Outlined.Share,
                        onClick = {
                            menuExpanded = false
                            onLongPress(BubbleAction.Share)
                        },
                    )
                }
            }

            if (isUser && isLastSegment) {
                Spacer(Modifier.height(2.dp))
                SendStatusLabel(message.sendStatus)
            }
        }
    }
}

/** Long-press menu actions surfaced by [MessageBubble]. */
sealed class BubbleAction {
    data object Copy : BubbleAction()
    data object Share : BubbleAction()
    data object EditResend : BubbleAction()
    data object ReadAloud : BubbleAction()

    /** Branch a NEW session cut at this message via harness-native
     *  session.fork (ChatController.branchSession) — full context, tool
     *  calls + reasoning included. Assistant bubbles only (the daemon only
     *  holds fork cut-points for assistant replies). */
    data object Branch : BubbleAction()
}

@Composable
private fun ActivityPill(activity: String?) {
    val label = remember(activity) { activityPillLabel(activity) }
    if (label == null) return
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivityDots()
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun activityPillLabel(activity: String?): String? {
    if (activity.isNullOrBlank()) return null
    return when {
        activity == "starting" -> "Starting…"
        activity == "thinking" -> "Thinking…"
        activity == "writing" -> "Writing…"
        activity.startsWith("tool:") -> "Running ${activity.removePrefix("tool:")}…"
        else -> null
    }
}

@Composable
private fun ActivityDots() {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "dots-phase",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { idx ->
            val on = phase.toInt() >= idx
            val alpha = if (on) 1f else 0.3f
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                    .height(4.dp)
                    .widthIn(min = 4.dp, max = 4.dp),
            )
        }
    }
}

/**
 * Blinking streaming caret. When [active] (text is actively being revealed) it
 * holds solid, reading as a live "typing" cursor; when idle (reveal has caught
 * up to the buffer but the turn is still open) it falls into a gentle blink so
 * it still signals "working" during the gaps between server chunks.
 */
@Composable
internal fun StreamingCaret(active: Boolean = false) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "caret")
    val blink by infinite.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(650),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "caret-alpha",
    )
    Text(
        text = "▍",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else blink),
        fontSize = 14.sp,
    )
}

@Composable
private fun SendStatusLabel(status: String) {
    // No tap-to-retry affordance wired yet (Phase 10 will add it via the
    // long-press menu). Until then the label is descriptive only — Reviewer
    // Checkpoint 2 finding UX-#4 flagged the previous "tap to retry" copy
    // as a lie since the bubble has no single-tap handler.
    val (label, color) = when (status) {
        "sending" -> "Sending…" to MaterialTheme.colorScheme.onSurfaceVariant
        "queued" -> "Queued" to MaterialTheme.marmaladeColors.statusConnecting
        "failed" -> "Failed" to MaterialTheme.marmaladeColors.bannerError
        else -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

private val bubblePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

private fun bubbleShape(isUser: Boolean): RoundedCornerShape =
    if (isUser) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)

/**
 * Group a message's parts into render-rows for [ChatMessageList]:
 * adjacent text/reasoning/image/file parts share a bubble segment;
 * each `ToolCall` becomes its own row that flanks the surrounding
 * bubbles. Returns an in-order list of rows.
 */
fun ChatMessage.toRows(): List<MessageRow> {
    if (parts.isEmpty()) return listOf(MessageRow.Bubble(emptyList(), isLastSegment = true))
    val rows = mutableListOf<MessageRow>()
    var segment = mutableListOf<ChatMessagePart>()
    // Consecutive tool calls collapse into ONE row (design-lab
    // tool-calls-in-chat, option E). Buffered here and flushed by
    // [flushToolRun] when the run is broken by prose or the message ends.
    var toolRun = mutableListOf<ChatMessagePart.ToolCall>()

    fun flushToolRun() {
        if (toolRun.isEmpty()) return
        rows.addAll(groupToolRun(toolRun))
        toolRun = mutableListOf()
    }

    for (part in parts) {
        when (part) {
            is ChatMessagePart.ToolCall -> {
                if (segment.isNotEmpty()) {
                    rows.add(MessageRow.Bubble(segment.toList(), isLastSegment = false))
                    segment = mutableListOf()
                }
                toolRun.add(part)
            }
            else -> {
                flushToolRun()
                segment.add(part)
            }
        }
    }
    flushToolRun()
    val tail = if (segment.isNotEmpty()) segment.toList() else emptyList()
    if (tail.isNotEmpty() || rows.isEmpty()) {
        rows.add(MessageRow.Bubble(tail, isLastSegment = true))
    }
    // Removed: the trailing-empty-bubble placeholder for tool-only pending
    // messages. That placeholder existed only to host an inline ActivityPill
    // that's now gone (the singleton ActivityBubble below the list is the
    // sole activity indicator). Without it we were rendering a tiny empty
    // surface between the tool card and the ActivityBubble (on-device
    // 2026-06-30 screenshot).
    return rows
}

/** Atomic LazyColumn item type emitted by [ChatMessage.toRows]. */
sealed class MessageRow {
    data class Bubble(
        val parts: List<ChatMessagePart>,
        val isLastSegment: Boolean,
    ) : MessageRow()

    /** A single tool call that renders at full card weight — either the only
     *  call in its run, or one that [escalatesOutOfRun] forced out. */
    data class Tool(val part: ChatMessagePart.ToolCall) : MessageRow()

    /**
     * A run of consecutive tool calls collapsed behind one line (option E).
     * A turn's tool count stops driving its vertical cost: six calls and sixty
     * both render as a single row until tapped.
     */
    data class ToolRun(val entries: List<ToolRunEntry>) : MessageRow()

    /** A subagent spawn plus the calls it made, as its own card (never folded
     *  into a run — a subagent is a participant, not a footnote). */
    data class Subagent(val entry: ToolRunEntry) : MessageRow()

    /**
     * The agent asking the maintainer something, rendered where it was asked and kept
     * there afterwards — the asked → answered → **recorded** lifecycle
     * (design-lab `agent-session-ui`, recommendation 2).
     *
     * Backed by the `AskUserQuestion` tool pair rather than by a new persisted
     * message part: that pair is already in the transcript, already seq-ordered
     * and already replayed, so the record needs no synthesis and carries no
     * reconcile risk against the live stream.
     */
    data class Prompt(val part: ChatMessagePart.ToolCall) : MessageRow()
}

/** A call in a run, plus any subagent work nested beneath it. [children] is
 *  non-empty only when [call] is a spawn (`Task`/`Agent`). */
data class ToolRunEntry(
    val call: ChatMessagePart.ToolCall,
    val children: List<ChatMessagePart.ToolCall> = emptyList(),
) {
    /** Still working when the call itself, or anything it spawned, has no
     *  result yet. */
    val running: Boolean get() = call.result == null || children.any { it.result == null }
    val errored: Boolean get() = call.isError || children.any { it.isError }
}

/**
 * A call that must NEVER hide inside a collapsed run. Two escalations, both
 * from the lab's sign-off: an **error** outranks tidiness, and a **question**
 * means the agent is blocked on the maintainer — collapsing either would hide the one
 * thing he needs to act on.
 */
internal fun escalatesOutOfRun(call: ChatMessagePart.ToolCall): Boolean =
    call.isError || call.isAgentQuestion

/**
 * Turn a flat run of consecutive tool calls into rows: nest subagent children
 * under the spawn that owns them, split subagent spawns out as their own
 * cards, escalate errors/questions to full weight, and collapse whatever is
 * left into one [MessageRow.ToolRun].
 *
 * Ordering is preserved throughout — a nested child never re-orders the run,
 * it just stops appearing at top level.
 */
fun groupToolRun(run: List<ChatMessagePart.ToolCall>): List<MessageRow> {
    val spawnIds = run.filter { it.isSubagentSpawn }.map { it.toolCallId }.toSet()
    val childrenBySpawn = run
        .filter { it.parentToolUseId != null && it.parentToolUseId in spawnIds }
        .groupBy { it.parentToolUseId!! }
    // A child whose spawn isn't in THIS run (split across a prose break, or a
    // cold load that dropped the spawn) stays top-level rather than vanishing.
    val nested = childrenBySpawn.values.flatten().toSet()

    val rows = mutableListOf<MessageRow>()
    val collapsible = mutableListOf<ToolRunEntry>()

    fun flushCollapsible() {
        if (collapsible.isEmpty()) return
        // Collapsing a single call buys nothing and costs a tap.
        rows.add(
            if (collapsible.size == 1) MessageRow.Tool(collapsible.single().call)
            else MessageRow.ToolRun(collapsible.toList()),
        )
        collapsible.clear()
    }

    for (call in run) {
        if (call in nested) continue
        val entry = ToolRunEntry(call, childrenBySpawn[call.toolCallId].orEmpty())
        when {
            call.isSubagentSpawn -> { flushCollapsible(); rows.add(MessageRow.Subagent(entry)) }
            // A question is not a tool call to the maintainer — it's the transcript's
            // record of a decision, so it gets the prompt frame rather than the
            // generic tool card. Still escalated out of the run either way.
            call.isAgentQuestion -> { flushCollapsible(); rows.add(MessageRow.Prompt(call)) }
            escalatesOutOfRun(call) -> { flushCollapsible(); rows.add(MessageRow.Tool(call)) }
            else -> collapsible.add(entry)
        }
    }
    flushCollapsible()
    return rows
}
