package app.marmalade.android.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.messages.contextOccupancy
import app.marmalade.android.chat.messages.contextReading
import app.marmalade.android.node.MarmaladeRuntime
import app.marmalade.android.rpc.types.DaemonFsEntry
import app.marmalade.android.rpc.types.PlanLimitWindow
import app.marmalade.android.rpc.types.SessionListRow
import app.marmalade.android.ui.chat.ContextDonut
import app.marmalade.android.ui.chat.formatTokens
import app.marmalade.android.ui.sessions.WorkspacePaths
import app.marmalade.android.utils.PlanWindowUtils
import app.marmalade.android.utils.UsageFormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The session tool panel (ADR 0013 step 3) — everything *about* the session
 * you're in, beside the session rather than instead of it.
 *
 * It opens from the RIGHT because the left edge belongs to the drawer, and one
 * edge cannot own two surfaces. Tabs are Overview → Files → Artifacts; adding
 * a future per-session surface costs a tab, not a redesign, which is the whole
 * reason this is a panel and not more screens.
 *
 * Every field degrades on its own. A daemon that doesn't report plan limits,
 * a harness with no context window, a session with no summary yet — each drops
 * its own card and the rest still render. Nothing here is ever fabricated.
 */
enum class PanelTab(val label: String) {
    OVERVIEW("Overview"),
    FILES("Files"),
    ARTIFACTS("Artifacts"),
}

@Composable
fun SessionToolPanel(
    runtime: MarmaladeRuntime,
    chat: ChatController,
    sessionName: String,
) {
    var tab by remember { mutableStateOf(PanelTab.OVERVIEW) }
    val cwd by chat.currentCwd.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sessionName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Which folder this panel is about. Files and Artifacts are
                // both scoped to it, so naming it here saves repeating it in
                // each tab — and a session with no cwd says so rather than
                // leaving the reader to wonder.
                Text(
                    text = cwd?.takeIf { it.isNotBlank() }?.let(WorkspacePaths::pathName)
                        ?: "no working directory",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // No close button: the panel dismisses by tapping the scrim or
            // swiping it back off the right edge, which is how every modal
            // sheet in the app already behaves. A chevron here was one more
            // glyph teaching a gesture the user already has (maintainer, 2026-07-25).
        }

        PanelTabs(selected = tab, onSelect = { tab = it })

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                PanelTab.OVERVIEW -> OverviewTab(runtime, chat)
                PanelTab.FILES -> FilesTab(runtime, chat)
                PanelTab.ARTIFACTS -> ArtifactsTab()
            }
        }
    }
}

/**
 * Segmented pills, not a Material `TabRow`. A tab row's underline indicator is
 * a page-level affordance; this is a small surface inside a sheet, and the
 * house style for "one of these is selected" is the tinted pill the composer
 * chips and the context menu already use.
 */
@Composable
private fun PanelTabs(selected: PanelTab, onSelect: (PanelTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PanelTab.entries.forEach { entry ->
            val on = entry == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable { onSelect(entry) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Overview ────────────────────────────────────────────────────────────────

private class OverviewData(
    val row: SessionListRow?,
    val windows: List<PlanLimitWindow>,
    val branch: String?,
    val failed: Boolean,
)

@Composable
private fun OverviewTab(runtime: MarmaladeRuntime, chat: ChatController) {
    val sessionKey by chat.sessionKey.collectAsStateWithLifecycle()
    val model by chat.currentModel.collectAsStateWithLifecycle()
    val usage by chat.sessionUsage.collectAsStateWithLifecycle()
    val isCompacting by chat.isCompacting.collectAsStateWithLifecycle()

    var data by remember(sessionKey) { mutableStateOf<OverviewData?>(null) }

    // Fetched on open rather than persisted: the summary, the stamped context
    // occupancy and the plan windows all live on the daemon, and one round trip
    // when the panel opens beats a Room column that goes stale between turns.
    LaunchedEffect(sessionKey) {
        data = withContext(Dispatchers.IO) {
            var failed = false
            val row = runCatching {
                runtime.marmaladeRpc.sessionList(limit = 100).sessions
                    .firstOrNull { it.session_id == sessionKey }
            }.onFailure { failed = true }.getOrNull()
            val windows = runCatching {
                PlanWindowUtils.forOverview(runtime.marmaladeRpc.usageSummary(days = 1).planLimits)
            }.getOrDefault(emptyList())
            val branch = runCatching {
                runtime.marmaladeRpc.workspaceList().workspaces
                    .firstOrNull { it.workspace_id == row?.workspace_id }
                    ?.detection?.git_branch
            }.getOrNull()
            OverviewData(row, windows, branch, failed)
        }
    }

    val snapshot = data
    if (snapshot == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The agent's own rollup. The CARD is unconditional and only its
        // contents degrade (maintainer, 2026-07-25) — a panel whose sections appear
        // and disappear per session reads as broken, where a stated "nothing
        // yet" reads as a fact about this session.
        item(key = "summary") {
            PanelCard(title = "Summary") {
                val summary = snapshot.row?.summary?.takeIf { it.isNotBlank() }
                if (summary != null) {
                    Text(text = summary, style = MaterialTheme.typography.bodyMedium)
                } else {
                    PanelPlaceholder("The agent writes one as the session goes on.")
                }
            }
        }

        // Live usage beats the persisted stamp — the stamp is written at turn
        // end, so mid-turn it lags. Fall back to the row when there's no live
        // reading yet (a session opened but not yet run this launch).
        val live = usage.contextReading()
        // One formula, one home: reuse contextOccupancy rather than dividing
        // again here, so the panel can't drift from the composer's donut.
        val percent = live?.percent
            ?: contextOccupancy(snapshot.row?.context_used, snapshot.row?.context_max)?.percent
        // Same rule as Summary: the card always exists, only its contents
        // degrade. Token counts come from the LIVE snapshot only — the
        // persisted row carries occupancy but no input/output split, so a
        // cold-opened session shows the donut without the breakdown rather
        // than showing counts from some other turn.
        item(key = "context") {
            PanelCard(title = "Context") {
                if (percent == null) {
                    PanelPlaceholder(
                        "Measured at the end of a turn — this session hasn't run one yet."
                    )
                } else {
                    // Stats sit BESIDE the donut, not under it — the card was
                    // the tallest thing in the panel for four short numbers.
                    // The "70% of the window" line is gone with them: the
                    // donut already draws that number in its middle, and
                    // saying it twice was the redundancy the maintainer flagged.
                    // Bound to a local: `usage` is a delegated property and
                    // Kotlin will not smart-cast one.
                    val counts = usage
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContextDonut(percent = percent, diameter = 52.dp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val used = live?.used ?: snapshot.row?.context_used
                            val max = live?.max ?: snapshot.row?.context_max
                            if (used != null && max != null) {
                                StatRow("Window", "${formatTokens(used)} / ${formatTokens(max)}")
                            }
                            counts?.inputTokens?.let { StatRow("Input", formatTokens(it)) }
                            counts?.outputTokens?.let { StatRow("Output", formatTokens(it)) }
                            counts?.cacheReadTokens?.let { StatRow("Cache read", formatTokens(it)) }
                        }
                    }
                    TextButton(
                        onClick = { chat.compact() },
                        enabled = !isCompacting,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text(
                            text = if (isCompacting) "Compacting…" else "Compact now",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        if (snapshot.windows.isNotEmpty()) {
            item(key = "quota") {
                PanelCard(title = "Subscription") {
                    snapshot.windows.forEach { window ->
                        QuotaBar(window)
                    }
                }
            }
        }

        // Only the facts we actually have. A four-row table of em-dashes is
        // the same information as no table, rendered as though something
        // failed. Deliberately "last active", not "started": session.list
        // reports last_active and no creation time, and inventing a start
        // time from it would be a plausible-looking lie.
        val meta = listOfNotNull(
            (model ?: snapshot.row?.model)?.let { "Model" to it },
            snapshot.branch?.let { "Branch" to it },
            snapshot.row?.last_active?.let { "Last active" to relativeTime(it) },
            snapshot.row?.harness?.let { "Harness" to it },
        )
        if (meta.isNotEmpty()) {
            item(key = "meta") {
                PanelCard(title = "Session") {
                    meta.forEach { (label, value) -> MetaRow(label, value) }
                }
            }
        }

        if (snapshot.failed) {
            item(key = "err") {
                Text(
                    text = "Couldn't reach the daemon — showing what was cached.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** "just now" / "14m ago" / "3h ago" / "2d ago" — coarse on purpose; this is
 *  orientation, not an audit trail. */
private fun relativeTime(epochMs: Long): String {
    val mins = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

@Composable
private fun QuotaBar(window: PlanLimitWindow) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = UsageFormatUtils.planWindowLine(window),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            UsageFormatUtils.resetsInText(window.resetsAt, System.currentTimeMillis())?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { UsageFormatUtils.planWindowFraction(window) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
    }
}

/** A tight label/value line for the context card's column beside the donut.
 *  Denser than [MetaRow]: it has a fixed 52dp of donut to sit level with. */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What a card says when it has nothing to say yet. Never an empty card and
 *  never a fabricated value — the reason the field is blank IS the content. */
@Composable
private fun PanelPlaceholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PanelCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

// ── Files ───────────────────────────────────────────────────────────────────

/**
 * The session's working directory, browsable. Read-only for now: opening a file
 * needs a viewer the client doesn't have yet, and a fake preview would be worse
 * than none.
 */
@Composable
private fun FilesTab(runtime: MarmaladeRuntime, chat: ChatController) {
    val cwd by chat.currentCwd.collectAsStateWithLifecycle()
    val root = cwd?.takeIf { it.isNotBlank() }

    if (root == null) {
        PanelEmpty("This session has no working directory.")
        return
    }

    var path by remember(root) { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<DaemonFsEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(path) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching { runtime.marmaladeRpc.fsList(path) }
        }
        result
            .onSuccess { entries = it.entries.sortedWith(compareByDescending<DaemonFsEntry> { e -> e.dir }.thenBy { e -> e.name.lowercase() }) }
            // fs.list is home-confined server-side; a rejection is a real
            // answer, not a crash, so surface its message.
            .onFailure { error = it.message ?: "Couldn't read that directory" }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Never navigate above the session's own root: the panel is about
            // THIS session, and fs.list is confined anyway.
            val canGoUp = path != root
            IconButton(
                onClick = { if (canGoUp) path = WorkspacePaths.parentDir(path) },
                enabled = canGoUp,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Up one directory",
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (path == root) WorkspacePaths.pathName(path)
                else path.removePrefix(root).trimStart('/'),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()

        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> PanelEmpty(error!!)
            entries.isEmpty() -> PanelEmpty("Empty directory.")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.name }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = entry.dir) {
                                path = WorkspacePaths.clean(path) + "/" + entry.name
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (entry.dir) Icons.Default.Folder
                            else Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ── Artifacts ───────────────────────────────────────────────────────────────

/**
 * Reserved, deliberately empty. ADR 0013 put Artifacts in the panel so the
 * surface exists when the daemon grows one; shipping a fabricated list to fill
 * the tab would make the panel untrustworthy everywhere else.
 */
@Composable
private fun ArtifactsTab() {
    PanelEmpty("Artifacts will appear here once the daemon publishes them.")
}

@Composable
private fun PanelEmpty(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
