package app.marmalade.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.android.ui.LocalMathRenderer
import app.marmalade.android.ui.LocalOpenAttachment
import coil3.compose.AsyncImage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.ui.blocks.MarmaladeBlockParser
import app.marmalade.android.ui.blocks.MarmaladeBlockRenderer
import app.marmalade.android.ui.blocks.UiTreeParser
import app.marmalade.android.ui.blocks.UiTreeRenderer
import app.marmalade.android.ui.icons.MarmaladeIcons
import app.marmalade.android.ui.theme.marmaladeColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Atomic per-`ChatMessagePart` composables. Each one is independent
 * and stateless except for local UI affordances (expand/collapse).
 * Composition with role-aware framing lives in [MessageBubble] /
 * [ChatMessageList].
 */

// ── Text (assistant: markdown + ```marmalade``` block routing) ───

/**
 * Render a [ChatMessagePart.Text] for the assistant role. The text is
 * split on ` ```marmalade\n…\n``` ` fences; interleaved markdown
 * segments render via [ChatMarkdownContent] and JSON segments route to
 * [MarmaladeBlockRenderer] as interactive cards.
 *
 * Strips the `{marmalade_action: ...}` envelope (invisible to the
 * user — dispatched separately by the controller).
 */
@Composable
fun AssistantTextPart(
    text: String,
    textColor: Color,
    onBlockResponse: (String) -> Unit,
    isStreaming: Boolean = false,
    revealKey: Any = Unit,
    modifier: Modifier = Modifier,
) {
    // Display math is host-rendered (KaTeX in a WebView on Android); see
    // LocalMathRenderer in HostBridges.kt.
    val mathRenderer = LocalMathRenderer.current
    val stripped = remember(text) { stripMarmaladeActionEnvelope(text) }
    // Client-paced reveal (design proposal TOPIC 1). When [isStreaming] is
    // false (history / finalized / non-last segment) the reveal snaps to the
    // full length instantly, so this is a no-op for every non-live bubble.
    // The reveal is LOCAL Compose state — it never re-enters MessageStream's
    // StateFlow / Room (see StreamingTextReveal.kt).
    val reveal = rememberStreamingReveal(
        fullText = stripped,
        isPending = isStreaming,
        revealKey = revealKey,
    )
    val shown = remember(stripped, reveal.revealedChars) {
        stripped.take(reveal.revealedChars)
    }
    val segments = remember(shown) { splitAssistantText(shown) }
    Column(modifier = modifier) {
        segments.forEach { segment ->
            when (segment) {
                is TextSegment.Markdown -> ChatMarkdownContent(
                    text = segment.text,
                    textColor = textColor,
                    onBlockInteraction = onBlockResponse,
                )
                is TextSegment.Math -> mathRenderer(
                    segment.tex,
                    textColor,
                    Modifier.padding(vertical = 4.dp),
                )
                is TextSegment.Block -> {
                    val block = remember(segment.json) {
                        MarmaladeBlockParser.parseMarmaladeBlock(segment.json)
                    }
                    if (block != null) {
                        MarmaladeBlockRenderer(
                            block = block,
                            onInteraction = onBlockResponse,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        // Parse failure → render as plain code fallback.
                        Text(
                            text = segment.json,
                            color = textColor.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
                is TextSegment.UiTree -> {
                    // Marmalade UI v1 (dynamic-UI blocks v2): repair-parse the
                    // tree; interactions synthesize a PLAIN user message
                    // through the same send path as any typed text.
                    val root = remember(segment.json) { UiTreeParser.parse(segment.json) }
                    if (root != null) {
                        UiTreeRenderer(
                            root = root,
                            onRespond = onBlockResponse,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        Text(
                            text = segment.json,
                            color = textColor.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        // Caret rides the reveal cursor: solid while text is actively being
        // revealed, gentle blink once the reveal catches up to the buffer.
        if (isStreaming) {
            StreamingCaret(active = reveal.revealing)
        }
    }
}

/**
 * Plain user text. Voice-originated messages get a leading mic badge — a real
 * Compose element, NOT part of the text string, so selecting/copying the
 * message yields only the spoken words (the old `"🎤 $text"` concatenation
 * leaked the emoji into copied text). The badge is purely a UI marker; the
 * gateway learns the turn is voice from the `source=voice` metadata.
 */
@Composable
fun UserTextPart(
    text: String,
    voiceOrigin: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    /** True when this user message was sent mid-turn via session.steer — a
     *  subtle "Steered" marker labels it above the text (T2 #6). */
    steered: Boolean = false,
    /** Daemon-stamped origin.source ("cron" | "agent" | …) — a scheduled or
     *  cross-session turn gets a distinct marker above the text, like the
     *  desktop cron/agent turns. Null / "text" / "voice" render no marker. */
    originSource: String? = null,
    /** For a source="agent" turn, the sending session ("session:<id>") — drives
     *  the "from session X" marker. */
    originDeviceId: String? = null,
) {
    val originLabel = originMarkerLabel(originSource, originDeviceId)
    Column(modifier = modifier) {
        if (steered) {
            Text(
                text = "Steered",
                color = textColor.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (originLabel != null) {
            Text(
                text = originLabel,
                color = textColor.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (!voiceOrigin) {
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = MarmaladeIcons.Voice,
                    contentDescription = "Voice message",
                    tint = textColor.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(top = 2.dp, end = 6.dp)
                        .size(16.dp),
                )
                Text(
                    text = text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The small marker shown above a user bubble for a daemon-minted origin:
 *  - "cron"  → "Scheduled" (a scheduled prompt fired the turn)
 *  - "agent" → "From session X" (another session sent this prompt; the sender
 *    rides origin.deviceId as "session:<id>")
 * "text"/"voice"/null render nothing here (voice has its own mic affordance).
 * Pure so it can be unit-tested without Compose.
 *
 * Public, not `internal`: `OriginMarkerLabelTest` stayed in `:app` when this
 * file moved to `:shared`, and `internal` hides it across the module boundary
 * (same reason `JsonRpcClient`'s test seam is public). The break was masked by
 * the build cache — `--rerun-tasks` is what surfaces it.
 */
fun originMarkerLabel(originSource: String?, originDeviceId: String?): String? =
    when (originSource) {
        "cron" -> "Scheduled"
        "agent" -> {
            val sender = originDeviceId?.removePrefix("session:")?.trim()?.takeIf { it.isNotEmpty() }
            if (sender != null) "From session ${sender.take(8)}" else "From another session"
        }
        else -> null
    }

// ── Reasoning ─────────────────────────────────────────────────────

/**
 * Collapsed-by-default reasoning panel. Auto-expanded while [streaming]
 * with a user-toggle override that wins from that point forward.
 */
@Composable
fun ReasoningPart(
    text: String,
    streaming: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    var userToggled by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userToggled ?: streaming
    val firstLine = remember(text) { text.lineSequence().firstOrNull()?.trim().orEmpty() }
    // Reasoning renders INSIDE the agent bubble, so it must dim from the
    // bubble's ink — not colorScheme.onSurfaceVariant, which is tuned for the
    // dark app surface and washes out on the (light or dark) bubble ground.
    val headerColor = textColor.copy(alpha = 0.7f)
    val previewColor = textColor.copy(alpha = 0.55f)
    val bodyColor = textColor.copy(alpha = 0.8f)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { userToggled = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Hide reasoning" else "Show reasoning",
                modifier = Modifier.size(18.dp),
                tint = headerColor,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Thinking",
                style = MaterialTheme.typography.labelMedium,
                color = headerColor,
                fontStyle = FontStyle.Italic,
            )
            if (!expanded && firstLine.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = firstLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = previewColor,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = bodyColor,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

// ── Tool call ─────────────────────────────────────────────────────

/**
 * Top-level tool-call card (per design decision (c) — separate row, not
 * nested in a bubble): icon, name, one-line summary, state.
 *
 * Tapping opens [ToolDetailSheet] rather than expanding inline. The card used
 * to grow an args + result body in place, which meant the app had TWO
 * tool-detail surfaces once the spine landed — and the inline one showed
 * strictly less (no raw tool name, no tool id, no subagent attribution, a
 * weaker output extraction). One surface, one interaction, everywhere.
 */
@Composable
fun ToolCallCard(
    part: ChatMessagePart.ToolCall,
    modifier: Modifier = Modifier,
) {
    val errored = part.isError
    var detail by remember(part.toolCallId) { mutableStateOf<ChatMessagePart.ToolCall?>(null) }
    detail?.let { ToolDetailSheet(call = it, onDismiss = { detail = null }) }
    val state = remember(part.result, part.isError) {
        when {
            part.isError -> ToolState.Error
            part.result != null -> ToolState.Ok
            else -> ToolState.Running
        }
    }
    // Tool parts hydrated from Room carry only argsText — ChatMessageMappers
    // reconstructs `args` empty on cold-load, and persistence lands within
    // ~200ms of a tool starting, so the rendered part usually has empty
    // `args`. Resolve a display-args object (structured when present, else
    // parsed back from argsText) so the summary + pretty-print work for
    // hydrated tool calls too.
    val displayArgs = remember(part.args, part.argsText) { part.displayArgs() }
    // Smart collapsed summary (design proposal TOPIC 2 (b1)) + duration read
    // straight off the already-merged result payload (TOPIC 2 (b3)).
    val summary = remember(part.toolName, part.argsText, displayArgs) {
        toolSummary(part.toolName, displayArgs, part.argsText)
    }
    val durationLabel = remember(part.result) { toolDurationLabel(part.result) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (errored)
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.marmaladeColors.bannerError.copy(alpha = 0.5f),
            )
        else null,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { detail = part }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = iconForTool(part.toolName),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = humanizeToolName(part.toolName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                // A call the maintainer personally decided on says so on the row itself —
                // needing to open the sheet to find out that you authorised a
                // root command would defeat the point of recording it.
                part.approvalChoice?.let { choice ->
                    Text(
                        text = approvalRecordChip(choice),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (choice == "deny") {
                            MaterialTheme.marmaladeColors.bannerError
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (summary.isNotEmpty()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                ToolStatusIndicator(state = state, durationLabel = durationLabel)
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = "Show tool details",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── collapsed tool run (design-lab tool-calls-in-chat, option E) ──

/**
 * A run of consecutive tool calls as ONE line: what it did, how long, how it
 * ended. Tap to open the spine (option D) — the same dense rows, indented
 * under a hairline rail.
 *
 * This is what stops a turn's tool count from driving its vertical cost. The
 * previous rendering gave every call a full-width card of equal weight, so a
 * nine-tool turn was nine slabs and the prose between them disappeared.
 *
 * Errors and questions never reach here — [escalatesOutOfRun] pulls them out
 * to full card weight before the run is built.
 */
@Composable
fun ToolRunCard(
    entries: List<ToolRunEntry>,
    modifier: Modifier = Modifier,
) {
    val running = remember(entries) { entries.any { it.running } }
    // Open while working, collapse once the run settles: the live state is
    // worth watching, the finished state is not. A user toggle wins from the
    // moment it's touched (same override shape as ReasoningPart).
    var userToggled by remember(entries.firstOrNull()?.call?.toolCallId) { mutableStateOf<Boolean?>(null) }
    val expanded = userToggled ?: running

    val totalSeconds = remember(entries) {
        entries.sumOf { e ->
            (e.call.durationSeconds() ?: 0.0) + e.children.sumOf { it.durationSeconds() ?: 0.0 }
        }
    }
    val count = remember(entries) { entries.sumOf { 1 + it.children.size } }
    val glyphIcons = remember(entries) { entries.map { iconForTool(it.call.toolName) }.distinct().take(3) }
    // The collapsed line and the spine are deliberately terse; that trade only
    // works because the full detail stays one tap away (maintainer, 2026-07-26).
    var detail by remember { mutableStateOf<ChatMessagePart.ToolCall?>(null) }
    detail?.let { ToolDetailSheet(call = it, onDismiss = { detail = null }) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { userToggled = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                glyphIcons.forEach {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (running) "Working" else "Worked for ${formatDuration(totalSeconds)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "· $count ${if (count == 1) "tool" else "tools"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse tool run" else "Expand tool run",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                ToolSpine(
                    entries = entries,
                    onTap = { detail = it },
                    modifier = Modifier.padding(start = 14.dp, end = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * The activity spine (option D): one node per call on a hairline rail, with a
 * subagent's calls on an indented sub-rail. Dense by construction — a node is
 * a single line of verb + object + duration.
 */
@Composable
private fun ToolSpine(
    entries: List<ToolRunEntry>,
    onTap: (ChatMessagePart.ToolCall) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        entries.forEach { entry ->
            ToolSpineNode(entry.call, onTap)
            if (entry.children.isNotEmpty()) {
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    entry.children.forEach { ToolSpineNode(it, onTap) }
                }
            }
        }
    }
}

@Composable
private fun ToolSpineNode(
    call: ChatMessagePart.ToolCall,
    onTap: (ChatMessagePart.ToolCall) -> Unit,
) {
    val summary = remember(call.toolName, call.argsText, call.args) {
        toolSummary(call.toolName, call.displayArgs(), call.argsText)
    }
    val duration = remember(call.result) { toolDurationLabel(call.result) }
    val dotColor = when {
        call.isError -> MaterialTheme.marmaladeColors.bannerError
        call.result == null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.marmaladeColors.toolSuccess
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap(call) }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(dotColor),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = humanizeToolName(call.toolName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (summary.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        if (duration != null) {
            Text(
                text = duration,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── subagent card ─────────────────────────────────────────────────

/**
 * A spawned subagent, as a participant rather than a wrench. Shows WHO (the
 * subagent type), WHY (its brief), what it's doing (live tool count / its
 * spine), and — once settled — its VERDICT, read from the report the daemon
 * now forwards on `tool.complete` (marmalade `ec6ea8e`; previously discarded).
 *
 * Before this existed a subagent spawn was an ordinary tool card named "Task"
 * and its work was attributed to the parent, because nothing on the wire said
 * otherwise.
 */
@Composable
fun SubagentCard(
    entry: ToolRunEntry,
    modifier: Modifier = Modifier,
) {
    val call = entry.call
    val args = remember(call.args, call.argsText) { call.displayArgs() }
    val who = remember(args) {
        args.firstString("subagent_type", "subagentType")?.replaceFirstChar { it.uppercaseChar() }
            ?: "Subagent"
    }
    val brief = remember(args) { args.firstString("description", "prompt") }
    val verdict = remember(call.result) { subagentReport(call.result) }
    var expanded by remember(call.toolCallId) { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ChatMessagePart.ToolCall?>(null) }
    detail?.let { ToolDetailSheet(call = it, onDismiss = { detail = null }) }
    val running = entry.running

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (entry.errored) MaterialTheme.marmaladeColors.bannerError.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MarmaladeIcons.Subagent,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = who,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (entry.children.isNotEmpty()) {
                    Text(
                        text = "${entry.children.size} ${if (entry.children.size == 1) "tool" else "tools"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                ToolStatusIndicator(
                    state = when {
                        entry.errored -> ToolState.Error
                        running -> ToolState.Running
                        else -> ToolState.Ok
                    },
                    durationLabel = toolDurationLabel(call.result),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse subagent" else "Expand subagent",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The brief is the whole point of the card — it's the only thing
            // that says why this subagent exists. Always visible.
            if (brief != null) {
                Text(
                    text = "“${brief.firstLineTrimmed()}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
            // A settled subagent collapses to its VERDICT, not its transcript.
            if (!running && verdict != null) {
                Text(
                    text = verdict,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 9.dp),
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(start = 14.dp, end = 12.dp, bottom = 9.dp)) {
                    entry.children.forEach { ToolSpineNode(it) { c -> detail = c } }
                    if (entry.children.isEmpty()) {
                        Text(
                            text = "No tool calls reported for this subagent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Duration in seconds off a completed call's merged result, if reported. */
internal fun ChatMessagePart.ToolCall.durationSeconds(): Double? {
    val obj = result as? JsonObject ?: return null
    return (obj["duration_s"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0.0 }
}

/**
 * The subagent's final report out of the structured tool result the daemon
 * forwards. The SDK documents this object as the thing to render from rather
 * than parsing the model-facing `tool_result` text, so prefer its fields and
 * fall back to the plain content only when none are present.
 */
internal fun subagentReport(result: JsonElement?): String? {
    val obj = result as? JsonObject ?: return null
    obj.firstString("report", "summary", "text", "result", "content")
        ?.let { return it.trim().takeIf(String::isNotEmpty) }
    return null
}

private enum class ToolState { Running, Ok, Error }

/**
 * Right-hand tool status (design proposal TOPIC 2 (b3)). Quieter than the old
 * always-on colored pill: success reads as a dim check + optional duration (no
 * loud green fill), while running/error keep their colored pills because that
 * signal is load-bearing. Mirrors desktop's "silent on success" philosophy.
 */
@Composable
private fun ToolStatusIndicator(state: ToolState, durationLabel: String?) {
    when (state) {
        ToolState.Running -> ToolPill(
            label = "running",
            color = MaterialTheme.marmaladeColors.statusConnecting,
            icon = null,
        )
        ToolState.Error -> ToolPill(
            label = "error",
            color = MaterialTheme.marmaladeColors.bannerError,
            icon = MarmaladeIcons.Error,
        )
        ToolState.Ok -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = MarmaladeIcons.Done,
                contentDescription = "done",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.marmaladeColors.toolSuccess.copy(alpha = 0.7f),
            )
            if (durationLabel != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolPill(label: String, color: Color, icon: ImageVector?) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.18f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = color,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

// ── tool classification (shared by icon / title / summary) ─────────

/**
 * How a wire tool name is classified — one bucket per `icon.tool.*` token in
 * the signed-off icon map (design-lab `icon-map`, 2026-08-01). The same
 * classification drives the glyph, the humanized title and the collapsed
 * summary, so a name that lands in the wrong bucket is wrong three times.
 */
enum class ToolBucket {
    Read, Write, Edit, ListDir, Search, Terminal, WebFetch, WebSearch, Image,
    Skill, Subagent, Question, Todo, Mcp, Think, Doc, Other,
}

/**
 * Matching is deliberately tolerant: names are lowercased, and MCP tools match
 * on their `mcp__` prefix because there is no fixed list of them — the
 * namespace IS the classification.
 *
 * Before this handled Claude Code's real names, `Glob`, `WebSearch`, `Task`,
 * `Skill`, `AskUserQuestion`, `TodoWrite`, `NotebookEdit` and every MCP call
 * fell through to [ToolBucket.Other], so a nine-step run drew eight identical
 * wrenches — the icons carrying the most information were exactly the ones
 * that didn't exist.
 */
fun toolBucket(name: String): ToolBucket {
    val lower = name.lowercase()
    if (lower.startsWith("mcp__")) return ToolBucket.Mcp
    return when (lower) {
        "read_file", "read", "view" -> ToolBucket.Read
        "write_file", "write" -> ToolBucket.Write
        "edit_file", "edit", "multiedit", "notebookedit" -> ToolBucket.Edit
        "list_dir", "list", "ls", "glob" -> ToolBucket.ListDir
        "search", "grep", "find" -> ToolBucket.Search
        "terminal", "bash", "shell", "run" -> ToolBucket.Terminal
        "browse", "fetch", "web", "webfetch" -> ToolBucket.WebFetch
        "websearch", "web_search" -> ToolBucket.WebSearch
        "image", "image_generate", "generate_image" -> ToolBucket.Image
        "skill" -> ToolBucket.Skill
        "task", "agent" -> ToolBucket.Subagent
        "askuserquestion" -> ToolBucket.Question
        "todowrite" -> ToolBucket.Todo
        "think", "thinking", "reason" -> ToolBucket.Think
        "article", "doc", "docs" -> ToolBucket.Doc
        else -> ToolBucket.Other
    }
}

/**
 * The tool half of the icon map. Every glyph comes from [MarmaladeIcons] —
 * mixing a Material Symbol in here would draw one concept at two weights,
 * which is the thing the map exists to stop. Unknown → wrench.
 */
fun iconForTool(name: String): ImageVector = when (toolBucket(name)) {
    ToolBucket.Read -> MarmaladeIcons.Read
    ToolBucket.Write -> MarmaladeIcons.Write
    ToolBucket.Edit -> MarmaladeIcons.Edit
    ToolBucket.ListDir -> MarmaladeIcons.ListFiles
    ToolBucket.Search -> MarmaladeIcons.Search
    ToolBucket.Terminal -> MarmaladeIcons.Terminal
    ToolBucket.WebFetch -> MarmaladeIcons.WebFetch
    ToolBucket.WebSearch -> MarmaladeIcons.WebSearch
    ToolBucket.Image -> MarmaladeIcons.Image
    ToolBucket.Skill -> MarmaladeIcons.Skill
    ToolBucket.Subagent -> MarmaladeIcons.Subagent
    ToolBucket.Question -> MarmaladeIcons.Question
    ToolBucket.Todo -> MarmaladeIcons.Todo
    ToolBucket.Mcp -> MarmaladeIcons.Mcp
    ToolBucket.Think -> MarmaladeIcons.Thinking
    ToolBucket.Doc -> MarmaladeIcons.Doc
    ToolBucket.Other -> MarmaladeIcons.Unknown
}

/**
 * The placeholder [app.marmalade.android.chat.messages.ToolCallUpsert] assigns
 * when a `tool.start` arrives with no `name` — it exists to key the upsert, and
 * must never be rendered as if it were a real tool name (it used to title-case
 * straight into the header as "Tool").
 */
private const val UNNAMED_TOOL = "tool"

/**
 * MCP tools arrive namespaced as `mcp__<server>__<tool>`. Splitting on the
 * DOUBLE underscore keeps the server and tool separate; the old blanket
 * `replace('_', ' ')` turned each `__` into two spaces and rendered
 * "Mcp  marmalade  update session summary".
 */
fun parseMcpToolName(name: String): Pair<String, String>? {
    if (!name.startsWith("mcp__")) return null
    val parts = name.removePrefix("mcp__").split("__", limit = 2)
    val server = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
    val tool = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    return server to tool
}

private fun String.humanizeSnakeCase(): String =
    replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }

/** Humanized tool title for the collapsed header (design proposal TOPIC 2 (b1)). */
fun humanizeToolName(name: String): String = when (toolBucket(name)) {
    ToolBucket.Read -> "Read file"
    ToolBucket.Write -> "Write file"
    ToolBucket.Edit -> "Edit file"
    ToolBucket.ListDir -> "List files"
    ToolBucket.Search -> "Search"
    ToolBucket.Terminal -> "Terminal"
    ToolBucket.WebFetch -> "Fetch"
    ToolBucket.WebSearch -> "Web search"
    ToolBucket.Image -> "Generate image"
    ToolBucket.Skill -> "Skill"
    ToolBucket.Subagent -> "Subagent"
    ToolBucket.Question -> "Question"
    ToolBucket.Todo -> "Task list"
    // An MCP call's information is its own tool name, not the "mcp" prefix.
    ToolBucket.Mcp -> parseMcpToolName(name)?.second?.humanizeSnakeCase()
        ?: name.humanizeSnakeCase()
    ToolBucket.Think -> "Thinking"
    ToolBucket.Doc -> "Document"
    ToolBucket.Other ->
        if (name == UNNAMED_TOOL) "Tool call" else name.humanizeSnakeCase()
}

/**
 * Per-tool one-line collapsed summary (design proposal TOPIC 2 (b1)): the
 * human-meaningful field for the tool, not raw arg JSON. File tools show the
 * filename, search the query, terminal the command's first line. Falls back to
 * the raw (truncated) argsText for tools we don't special-case.
 */
fun toolSummary(toolName: String, args: JsonObject, argsText: String): String {
    val summary = when (toolBucket(toolName)) {
        ToolBucket.Read, ToolBucket.Write, ToolBucket.Edit, ToolBucket.ListDir ->
            args.firstString("path", "file_path", "file", "filename", "dir", "directory")
                ?.basename()
        ToolBucket.Search ->
            args.firstString("query", "search_term", "pattern", "q")
        ToolBucket.Terminal ->
            args.firstString("command", "cmd", "script", "code")?.firstLineTrimmed()
        ToolBucket.WebFetch ->
            args.firstString("url", "href")
        ToolBucket.WebSearch ->
            args.firstString("query", "q")
        ToolBucket.Skill ->
            args.firstString("command", "skill", "name")
        // Which subagent, then what it was asked to do — the same who/why the
        // SubagentCard leads with, for the rows that aren't a card.
        ToolBucket.Subagent ->
            args.firstString("subagent_type", "subagentType", "description")
                ?.firstLineTrimmed()
        // MCP + anything unrecognised used to fall through to the serialised
        // argument object, printing `{"query":"select:mcp__marm…` into the
        // header. Prefer the MCP server name (real information, always short),
        // then a generic first-meaningful-string probe, then NOTHING — a
        // header with no summary reads far better than one showing JSON.
        else -> parseMcpToolName(toolName)?.first
            ?: args.firstString("query", "q", "name", "title", "path", "file_path", "url", "command", "text")
                ?.firstLineTrimmed()
    }
    // `argsText` is "{}" for an argument-less tool. The expanded body already
    // guarded against it; the collapsed header did not, so tools advertised a
    // literal empty object as their summary.
    return summary?.takeIf { it.isNotBlank() }.orEmpty()
}

/**
 * Structured args for display. Tool parts hydrated from Room carry only
 * argsText ([app.marmalade.android.chat.messages.ChatMessageMappers]
 * reconstructs `args` empty on cold-load); prefer the structured object when
 * present, else parse it back from argsText so the smart summary + pretty-print
 * work for hydrated tool calls too.
 */
fun ChatMessagePart.ToolCall.displayArgs(): JsonObject =
    if (args.isNotEmpty()) args
    else runCatching { Json.parseToJsonElement(argsText) as? JsonObject }.getOrNull()
        ?: JsonObject(emptyMap())

internal fun JsonObject.firstString(vararg keys: String): String? {
    for (k in keys) {
        val v = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (!v.isNullOrEmpty()) return v
    }
    return null
}

/** Last path segment (handles both `/` and `\`); the whole string if none. */
private fun String.basename(): String =
    substringAfterLast('/').substringAfterLast('\\').ifEmpty { this }

internal fun String.firstLineTrimmed(): String =
    lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: trim()

// ── duration (design proposal TOPIC 2 (b3)) ───────────────────────
// `duration_s` is merged into the tool result payload by
// ToolCallUpsert.mergeToolResult, so read it straight off `result` — no extra
// field on ChatMessagePart.ToolCall needed.
internal fun toolDurationLabel(result: JsonElement?): String? {
    val obj = result as? JsonObject ?: return null
    val seconds = (obj["duration_s"] as? JsonPrimitive)?.doubleOrNull ?: return null
    if (seconds <= 0.0) return null
    return formatDuration(seconds)
}

private fun formatDuration(seconds: Double): String = when {
    seconds < 10 -> "%.1fs".format(seconds)
    seconds < 100 -> "%.0fs".format(seconds)
    else -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
}

// ── pretty JSON for the expanded body (design proposal TOPIC 2 (b2)) ──
private val prettyJson = Json { prettyPrint = true }

/** Pretty-print a JSON value; bare primitives unwrap to their text. */
internal fun prettyPrintJson(element: JsonElement): String = when (element) {
    is JsonPrimitive -> element.content
    else -> runCatching {
        prettyJson.encodeToString(JsonElement.serializer(), element)
    }.getOrElse { element.toString() }
}

private fun jsonLanguageFor(element: JsonElement): String? = when (element) {
    is JsonObject, is JsonArray -> "json"
    else -> null
}

// ── Image ─────────────────────────────────────────────────────────

/** Inline image part. Tap to fullscreen via existing [ChatImageViewer]. */
@Composable
fun ImagePart(
    part: ChatMessagePart.Image,
    onTap: (url: String, alt: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onTap(part.image, part.alt) },
        ) {
            AsyncImage(
                model = part.image,
                contentDescription = part.alt,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp),
            )
        }
        // Local val: part.alt is a cross-module val (ChatMessagePart moved to
        // :shared in increment 3d) and can't be smart-cast in place.
        val alt = part.alt
        if (!alt.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = alt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── File ──────────────────────────────────────────────────────────

/** File-attachment chip. Tap asks the host to open the part's source URI. */
@Composable
fun FilePart(
    part: ChatMessagePart.File,
    modifier: Modifier = Modifier,
) {
    val openAttachment = LocalOpenAttachment.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .padding(vertical = 4.dp)
            .clickable { openAttachment(part.source) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MarmaladeIcons.Attachment,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = part.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            part.mimeType?.let {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Helpers: split text on ```marmalade``` fences ─────────────────

sealed class TextSegment {
    data class Markdown(val text: String) : TextSegment()
    data class Block(val json: String) : TextSegment()

    /** A ```marmalade-ui``` node-tree fence (Marmalade UI v1, dynamic-UI
     *  blocks v2) — rendered by [app.marmalade.android.ui.blocks.UiTreeRenderer]. */
    data class UiTree(val json: String) : TextSegment()

    /** Display math (`$$…$$` or a ```math fence), rendered by [LocalMathRenderer]. */
    data class Math(val tex: String) : TextSegment()
}

private val marmaladeFenceRegex = Regex(
    pattern = "```marmalade\\s*\\n(.*?)\\n```",
    options = setOf(RegexOption.DOT_MATCHES_ALL),
)

// v2 tree fence. Disjoint from the legacy regex: `marmalade\s*\n` cannot
// match `marmalade-ui\n` (the `-` is not whitespace).
private val marmaladeUiFenceRegex = Regex(
    pattern = "```marmalade-ui\\s*\\n(.*?)\\n```",
    options = setOf(RegexOption.DOT_MATCHES_ALL),
)

/**
 * Split assistant text into alternating markdown / ```marmalade``` JSON /
 * ```marmalade-ui``` tree / display-math segments. Empty markdown segments
 * are dropped so the renderer doesn't emit a stray paragraph between
 * adjacent blocks.
 */
fun splitAssistantText(text: String): List<TextSegment> {
    if (text.isEmpty()) return emptyList()
    data class FenceMatch(val range: IntRange, val segment: TextSegment)
    val matches = buildList {
        marmaladeUiFenceRegex.findAll(text).forEach { add(FenceMatch(it.range, TextSegment.UiTree(it.groupValues[1]))) }
        marmaladeFenceRegex.findAll(text).forEach { m ->
            if (none { m.range.first in it.range }) add(FenceMatch(m.range, TextSegment.Block(m.groupValues[1])))
        }
    }.sortedBy { it.range.first }
    val marmaladeSplit: List<TextSegment> = if (matches.isEmpty()) {
        listOf(TextSegment.Markdown(text))
    } else {
        val segments = mutableListOf<TextSegment>()
        var cursor = 0
        for (m in matches) {
            if (m.range.first < cursor) continue // overlap guard
            val before = text.substring(cursor, m.range.first)
            if (before.isNotBlank()) segments.add(TextSegment.Markdown(before))
            segments.add(m.segment)
            cursor = m.range.last + 1
        }
        val tail = text.substring(cursor)
        if (tail.isNotBlank()) segments.add(TextSegment.Markdown(tail))
        segments
    }
    return marmaladeSplit.flatMap { seg ->
        if (seg is TextSegment.Markdown) splitMathInMarkdown(seg.text) else listOf(seg)
    }
}

// Any closed triple-backtick fence — used both to recognise ```math and to
// PROTECT other fences from the $$-scan (a code sample containing $$ must
// not be carved up).
private val anyFenceRegex = Regex(
    pattern = "```(\\w*)[^\\S\\n]*\\n(.*?)\\n```",
    options = setOf(RegexOption.DOT_MATCHES_ALL),
)

// Closed $$…$$ only. An unterminated $$ mid-stream deliberately doesn't
// match — it stays plain markdown (raw TeX) until the closing delimiter
// arrives, so streaming never flashes a half-rendered equation. Delimiters
// are $$…$$ and ```math ONLY — desktop's remark-math doesn't parse
// \(…\)/\[…\] either, so the model is already contracted to dollars.
private val displayMathRegex = Regex(
    pattern = "\\$\\$(.+?)\\$\\$",
    options = setOf(RegexOption.DOT_MATCHES_ALL),
)

/**
 * Split one markdown chunk into markdown / display-math segments.
 * Inline `$x$` math is NOT handled (richtext-commonmark alpha03 exposes
 * no inline AST hook; v2 candidate).
 */
internal fun splitMathInMarkdown(text: String): List<TextSegment> {
    if (text.isEmpty()) return emptyList()
    if (!text.contains("$$") && !text.contains("```math")) {
        return if (text.isNotBlank()) listOf(TextSegment.Markdown(text)) else emptyList()
    }
    val fences = anyFenceRegex.findAll(text).toList()
    data class MathMatch(val range: IntRange, val tex: String)
    val mathMatches = buildList {
        fences.forEach { f ->
            if (f.groupValues[1] == "math") add(MathMatch(f.range, f.groupValues[2]))
        }
        displayMathRegex.findAll(text).forEach { m ->
            if (fences.none { f -> m.range.first in f.range }) {
                add(MathMatch(m.range, m.groupValues[1]))
            }
        }
    }.sortedBy { it.range.first }
    if (mathMatches.isEmpty()) {
        return if (text.isNotBlank()) listOf(TextSegment.Markdown(text)) else emptyList()
    }
    val segments = mutableListOf<TextSegment>()
    var cursor = 0
    for (m in mathMatches) {
        if (m.range.first < cursor) continue // overlap guard
        val before = text.substring(cursor, m.range.first)
        if (before.isNotBlank()) segments.add(TextSegment.Markdown(before))
        val tex = m.tex.trim()
        if (tex.isNotEmpty()) {
            segments.add(TextSegment.Math(tex))
        } else {
            // Empty $$$$ — render literally rather than an empty WebView.
            segments.add(TextSegment.Markdown(text.substring(m.range)))
        }
        cursor = m.range.last + 1
    }
    val tail = text.substring(cursor)
    if (tail.isNotBlank()) segments.add(TextSegment.Markdown(tail))
    return segments
}

/**
 * Strip a `{"marmalade_action": {...}}` envelope from assistant text so
 * it doesn't visibly render — the controller dispatches it separately.
 * Best-effort: only strips when the substring is the whole text body
 * (with optional leading/trailing whitespace).
 */
private val actionEnvelopeRegex = Regex(
    pattern = """\{\s*"marmalade_action"\s*:\s*\{[^}]*\}\s*\}""",
    options = setOf(RegexOption.DOT_MATCHES_ALL),
)

internal fun stripMarmaladeActionEnvelope(text: String): String =
    actionEnvelopeRegex.replace(text, "").trim()

/**
 * Best-effort extraction of an image URL from a tool's args or result
 * blob. Mirrors desktop's `tool-fallback-model.ts:809-810` key set:
 * `image_url`, `url`, `path`, `image_path`, `image`. Recognises both
 * `data:image/...` URLs and `http(s)://...{png,jpg,jpeg,gif,webp}`
 * suffixes; bails on anything else. Returns null when the input is
 * neither a JSON snippet with one of those keys nor a bare image URL.
 */
private val imageKeyRegex = Regex(
    """"(image_url|url|path|image_path|image)"\s*:\s*"([^"]+)"""",
    RegexOption.IGNORE_CASE,
)

private fun looksLikeImageUrl(value: String): Boolean {
    if (value.startsWith("data:image/", ignoreCase = true)) return true
    val lower = value.lowercase()
    if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
    return lower.endsWith(".png") || lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") || lower.endsWith(".gif") ||
        lower.endsWith(".webp")
}

internal fun extractImageUrl(text: String): String? {
    if (text.isBlank()) return null
    if (looksLikeImageUrl(text.trim())) return text.trim()
    for (m in imageKeyRegex.findAll(text)) {
        val candidate = m.groupValues[2]
        if (looksLikeImageUrl(candidate)) return candidate
    }
    return null
}
