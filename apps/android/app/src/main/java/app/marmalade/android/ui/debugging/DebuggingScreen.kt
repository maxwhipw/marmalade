package app.marmalade.android.ui.debugging

/**
 * Debugging tab — debug-build-only log explorer.
 *
 *  - Subscribes to the full gateway log firehose (all frames, including
 *    tick / health / chat-state-delta noise that the Gateway tab summary
 *    filters out by default).
 *  - Per-kind toggles (assistant deltas, ticks, health, raw frames, etc.)
 *    persisted via rememberSaveable.
 *  - Consecutive-message grouping with a × N counter, Chrome-DevTools-style.
 *    Group key is event/kind shape PLUS the runId for run-scoped events
 *    so deltas from a new run break the previous group's chain.
 *  - Long messages (> 200 chars) collapse with "…" → tap to expand inline.
 */
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.node.TransportLogEntry
import app.marmalade.android.node.TransportLogLevel
import app.marmalade.android.node.MarmaladeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Maximum characters shown for an entry before truncating with `…`. */
private const val LONG_ENTRY_THRESHOLD = 200

/**
 * Stable identifier for an entry's "kind", used both to drive the filter
 * checkboxes and to bucket consecutive-message grouping.
 *
 * The order of declaration is the order shown in the filter sheet.
 */
private enum class LogKind(val label: String, val defaultVisible: Boolean) {
    Tick("Ticks (transport keepalive)", false),
    Health("Health probes", false),
    Delta("Chat state=delta (throttled text mirror)", false),
    Heartbeat("Heartbeat events", true),
    Presence("Presence updates", false),
    AssistantStream("agent stream=assistant", true),
    ThinkingStream("agent stream=thinking", true),
    ToolStream("agent stream=tool", true),
    ItemStream("agent stream=item", true),
    CommandOutput("agent stream=command_output", true),
    LifecycleStream("agent stream=lifecycle", true),
    ChatTerminal("chat state=final/aborted/error", true),
    RpcRequest("RPC requests (op ↑ / node ↑)", true),
    RpcResponse("RPC responses", true),
    Connection("Connection lifecycle", true),
    Warn("Warnings", true),
    Other("Other / unmatched", true),
    ;
}

/**
 * Classify a log entry into a [LogKind] by string-pattern-matching the
 * message. Cheaper than re-parsing the JSON; falls back to [LogKind.Other]
 * for anything we don't recognize.
 */
private fun classify(entry: TransportLogEntry): LogKind {
    val m = entry.message
    return when {
        m.contains("\"event\":\"tick\"") -> LogKind.Tick
        m.contains("\"event\":\"health\"") -> LogKind.Health
        m.contains("\"event\":\"chat\"") && m.contains("\"state\":\"delta\"") -> LogKind.Delta
        m.contains("\"event\":\"heartbeat\"") -> LogKind.Heartbeat
        m.contains("\"event\":\"presence\"") -> LogKind.Presence
        m.contains("\"stream\":\"assistant\"") -> LogKind.AssistantStream
        m.contains("\"stream\":\"thinking\"") -> LogKind.ThinkingStream
        m.contains("\"stream\":\"tool\"") -> LogKind.ToolStream
        m.contains("\"stream\":\"item\"") -> LogKind.ItemStream
        m.contains("\"stream\":\"command_output\"") -> LogKind.CommandOutput
        m.contains("\"stream\":\"lifecycle\"") -> LogKind.LifecycleStream
        m.contains("\"event\":\"chat\"") &&
            (m.contains("\"state\":\"final\"") ||
                m.contains("\"state\":\"aborted\"") ||
                m.contains("\"state\":\"error\"")) -> LogKind.ChatTerminal
        m.startsWith("op ↑") || m.startsWith("node ↑") -> LogKind.RpcRequest
        m.contains("\"type\":\"res\"") -> LogKind.RpcResponse
        m.startsWith("Connected to") || m.startsWith("Disconnected:") ||
            m.startsWith("Gateway policy") -> LogKind.Connection
        entry.level == TransportLogLevel.WARN || entry.level == TransportLogLevel.ERROR -> LogKind.Warn
        else -> LogKind.Other
    }
}

/**
 * Compute a group key for consecutive-message folding. Same kind +
 * same `runId` keeps the group; anything different breaks it.
 *
 * Reads [TransportLogEntry.runId] directly — populated by the producer
 * for run-scoped entries (raw frames whose payload carried a runId,
 * controller-side activity / abort / error logs that knew their runId
 * at the call site). For older entries that don't have the field set,
 * falls back to extracting from the message body so a build that
 * mixes pre- and post-fix entries doesn't lose grouping coherence.
 */
private fun groupKey(entry: TransportLogEntry, kind: LogKind): String {
    val runId = entry.runId ?: extractRunIdFromMessage(entry.message)
    return if (runId != null) "${kind.name}:$runId" else kind.name
}

private val RUN_ID_REGEX = Regex("\"runId\":\"([^\"]+)\"")
private fun extractRunIdFromMessage(message: String): String? =
    RUN_ID_REGEX.find(message)?.groupValues?.getOrNull(1)

/** A run of consecutive entries that share a [groupKey]. */
private data class LogGroup(
    val key: String,
    val kind: LogKind,
    val firstTs: Long,
    val latest: TransportLogEntry,
    val count: Int,
)

/** Walk [entries] in order; fold consecutive same-key items into one group. */
private fun foldGroups(entries: List<TransportLogEntry>): List<LogGroup> {
    val out = mutableListOf<LogGroup>()
    var current: LogGroup? = null
    for (entry in entries) {
        val kind = classify(entry)
        val key = groupKey(entry, kind)
        val existing = current
        if (existing != null && existing.key == key) {
            current = existing.copy(latest = entry, count = existing.count + 1)
        } else {
            existing?.let(out::add)
            current = LogGroup(
                key = key,
                kind = kind,
                firstTs = entry.timestamp,
                latest = entry,
                count = 1,
            )
        }
    }
    current?.let(out::add)
    return out
}

@Composable
fun DebuggingScreen(marmaladeRuntime: MarmaladeRuntime, onBack: () -> Unit) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val entries by marmaladeRuntime.transportLog.entries.collectAsStateWithLifecycle()
    val visibleKinds = rememberKindToggles()

    val groups by remember(entries, visibleKinds) {
        derivedStateOf {
            val filtered = entries.filter { visibleKinds[classify(it)] != false }
            foldGroups(filtered)
        }
    }

    androidx.compose.material3.Scaffold(
        // Parent NavHost Scaffold already consumes the status-bar inset;
        // zero out here so we don't double-pad the top bar (visible as a
        // ~2× tall header on existing screens that forgot this).
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Debug log") },
                // Reachable straight from the drawer's bottom row now, not just
                // from inside Settings — so it needs a way back that isn't the
                // system gesture.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // Exports the full buffer (every entry, unfiltered) so a
                    // bug report carries the complete picture regardless of
                    // which per-kind filters are active on screen.
                    IconButton(onClick = { exportDebugLog(exportScope, context, entries) }) {
                        Icon(Icons.Default.Share, contentDescription = "Export log")
                    }
                    IconButton(onClick = { marmaladeRuntime.transportLog.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear log")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterPanel(visibleKinds = visibleKinds)
            HorizontalDivider()
            Text(
                text = "${groups.size} groups · ${entries.size} entries",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            HorizontalDivider()
            LogList(groups = groups)
        }
    }
}

/**
 * Persisted (across config change + process death) per-kind visibility map.
 * Stored as an ordered string of `0`/`1` flags so the saver is trivial.
 */
@Composable
private fun rememberKindToggles(): androidx.compose.runtime.snapshots.SnapshotStateMap<LogKind, Boolean> {
    val saver = remember {
        Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<LogKind, Boolean>, String>(
            save = { state ->
                LogKind.entries.joinToString("") { kind ->
                    if (state[kind] != false) "1" else "0"
                }
            },
            restore = { encoded ->
                val map = androidx.compose.runtime.mutableStateMapOf<LogKind, Boolean>()
                LogKind.entries.forEachIndexed { idx, kind ->
                    map[kind] = encoded.getOrNull(idx) == '1'
                }
                map
            },
        )
    }
    return rememberSaveable(saver = saver) {
        mutableStateMapOf<LogKind, Boolean>().apply {
            for (kind in LogKind.entries) put(kind, kind.defaultVisible)
        }
    }
}

@Composable
private fun FilterPanel(
    visibleKinds: androidx.compose.runtime.snapshots.SnapshotStateMap<LogKind, Boolean>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val activeCount = LogKind.entries.count { visibleKinds[it] == true }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Filters · $activeCount of ${LogKind.entries.size} on",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (expanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.outline,
                fontSize = 12.sp,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                for (kind in LogKind.entries) {
                    val checked = visibleKinds[kind] == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { visibleKinds[kind] = !checked }
                            .padding(vertical = 2.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { visibleKinds[kind] = it },
                        )
                        Text(
                            text = kind.label,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogList(groups: List<LogGroup>) {
    val listState = rememberLazyListState()
    // Auto-follow when the user is parked at the bottom — same pattern as
    // the Gateway tab's log viewer. Scrolling up freezes the viewport.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) true
            else visible.last().index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(groups.size) {
        if (groups.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(groups.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        // Position is part of the LazyColumn key because (groupKey, firstTs)
        // alone can collide: foldGroups produces a fresh group every time a
        // different kind interrupts a sequence, so two same-kind+same-runId
        // events arriving in the same millisecond with another kind in
        // between would each open a new group with identical
        // {key, firstTs}. LazyColumn's duplicate-key throw kills the
        // process when the user scrolls into the second one.
        itemsIndexed(
            items = groups,
            key = { idx, it -> "${it.key}:${it.firstTs}:$idx" },
        ) { _, group ->
            LogGroupRow(group = group)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogGroupRow(group: LogGroup) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val time = remember(group.latest.timestamp) {
        timeFormat.format(Date(group.latest.timestamp))
    }
    val levelColor = when (group.latest.level) {
        TransportLogLevel.INFO -> MaterialTheme.colorScheme.primary
        TransportLogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        TransportLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        TransportLogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    var expanded by remember(group.key, group.firstTs) { mutableStateOf(false) }
    val isLong = group.latest.message.length > LONG_ENTRY_THRESHOLD
    val displayText = if (isLong && !expanded) {
        group.latest.message.take(LONG_ENTRY_THRESHOLD) + "…"
    } else {
        group.latest.message
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Tap expands a truncated entry; long-press copies the full
            // (untruncated) message to the clipboard.
            .combinedClickable(
                onClick = { if (isLong) expanded = !expanded },
                onLongClick = { copyLogEntry(context, group.latest.message) },
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Run-id chip: short prefix of the runId so the user can visually
        // see which run an entry belongs to. Same colour for the same run
        // (modular hash). Null runId entries (connection lifecycle, etc.)
        // get no chip — they intentionally have no run scope.
        val runId = group.latest.runId ?: extractRunIdFromMessage(group.latest.message)
        if (runId != null) {
            RunIdChip(runId = runId)
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (group.count > 1) {
            CountBadge(count = group.count)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "× $count",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Short run-id chip — last 6 chars of the runId. Same run renders the
 * same colour (modular hash so the eye can pair entries from the same
 * run across rows even when they're separated by other runs' entries).
 */
@Composable
private fun RunIdChip(runId: String) {
    val short = runId.takeLast(6)
    val hue = ((runId.hashCode() % 360) + 360) % 360
    val tint = androidx.compose.ui.graphics.Color.hsv(hue.toFloat(), 0.55f, 0.85f)
    Box(
        modifier = Modifier
            .background(tint.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = short,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Copy a single log entry's full message to the clipboard (long-press). */
private fun copyLogEntry(context: Context, message: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Debug log entry", message))
    Toast.makeText(context, "Entry copied", Toast.LENGTH_SHORT).show()
}

/**
 * Dump the whole gateway log buffer to a timestamped `.txt` in the cache
 * dir and hand it to the system share sheet. Sharing a file (rather than
 * an `ACTION_SEND` text extra) avoids the Binder transaction-size limit a
 * multi-thousand-entry buffer would otherwise blow.
 *
 * The string build + file write happen on IO; only the chooser launch
 * runs on Main. With a buffer at the 5_000-entry cap the build can take
 * several frames on a Pixel 8a — keeping it off Main is what makes the
 * Share button feel responsive on a populated log.
 */
private fun exportDebugLog(
    scope: CoroutineScope,
    context: Context,
    entries: List<TransportLogEntry>,
) {
    if (entries.isEmpty()) {
        Toast.makeText(context, "Log is empty — nothing to export", Toast.LENGTH_SHORT).show()
        return
    }
    scope.launch {
        try {
            val intent = withContext(Dispatchers.IO) {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                val text = buildString {
                    append("Marmalade debug log\n")
                    append("Exported ").append(stamp.format(Date())).append('\n')
                    append(entries.size).append(" entries\n\n")
                    for (e in entries) {
                        append("=== [").append(stamp.format(Date(e.timestamp))).append("] ")
                        append(e.level.name)
                        e.source?.let { append("  src=").append(it) }
                        e.runId?.let { append("  run=").append(it) }
                        append(" ===\n")
                        append(e.message).append("\n\n")
                    }
                }
                val dir = File(context.cacheDir, "debug_logs").apply { mkdirs() }
                val file = File(dir, "marmalade-debug-${System.currentTimeMillis()}.txt")
                file.writeText(text)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Marmalade debug log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(Intent.createChooser(intent, "Export debug log"))
        } catch (e: Throwable) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
