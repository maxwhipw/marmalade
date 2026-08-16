package app.marmalade.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import app.marmalade.android.SessionListViewModel
import app.marmalade.android.SessionUiModel
import app.marmalade.android.rpc.types.WorkspaceContextResponse
import app.marmalade.android.rpc.types.WorkspaceInfo
import app.marmalade.android.utils.WorkspaceContextUtils
import app.marmalade.android.utils.WorkspaceContextUtils.ContextChip
import app.marmalade.android.utils.WorkspaceContextUtils.PeekTarget
import app.marmalade.android.ui.components.MarmaladeMenu
import app.marmalade.android.ui.components.MarmaladeMenuDivider
import app.marmalade.android.ui.components.MarmaladeMenuItem
import app.marmalade.android.ui.terminal.TerminalRow

/**
 * Workspace DETAIL screen (design-labs lab3): the session list scoped to one
 * daemon workspace, plus a read-only peek at what an agent spawned here
 * inherits (git branch / CLAUDE.md / AGENTS.md / memory) and workspace
 * housekeeping in the overflow menu.
 *
 * Sessions come from the SAME [SessionListViewModel.workspaceLayout] flow the
 * main list uses, filtered by [workspaceId] — membership is NEVER re-derived.
 * workspace.context is fetched ONCE on entry (no fetch loops). When the
 * workspace disappears from the layout (deleted elsewhere), the screen pops.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WorkspaceDetailScreen(
    viewModel: SessionListViewModel,
    workspaceId: String,
    onBack: () -> Unit,
    onSessionClick: (sessionId: String) -> Unit,
    onTerminalClick: (terminalId: String) -> Unit = {},
) {
    val layout by viewModel.workspaceLayout.collectAsStateWithLifecycle()
    val card = layout.cards.firstOrNull { it.id == workspaceId }

    // The workspace vanished (removed on another client / this device) — leave.
    LaunchedEffect(card == null) {
        if (card == null) onBack()
    }
    val workspace = card?.workspace ?: return
    // Active (non-archived) sessions come from the layout, which already
    // excludes archived rows. Archived sessions for THIS workspace are surfaced
    // in a separate collapsible section so they stay reachable.
    val sessions = card.sessions
    val archivedAll by viewModel.archivedSessions.collectAsStateWithLifecycle()
    val archivedForWs = archivedAll.filter { it.workspaceId == workspaceId }
    var archivedExpanded by remember(workspaceId) { mutableStateOf(false) }

    // This workspace's terminals — the server-derived workspace_id stamp, same
    // trust rule as sessions (never re-derived from cwd client-side).
    val terminalSupported by viewModel.terminalSupported.collectAsStateWithLifecycle()
    val allTerminals by viewModel.terminals.collectAsStateWithLifecycle()
    val terminalsForWs = if (terminalSupported) {
        allTerminals.filter { it.workspace_id == workspaceId }
    } else {
        emptyList()
    }
    LaunchedEffect(workspaceId) { viewModel.refreshTerminals() }

    // workspace.context — fetched once per entry. null = not-yet-loaded or
    // errored (we omit the strip either way; a WARN is logged on error).
    var context by remember(workspaceId) { mutableStateOf<WorkspaceContextResponse?>(null) }
    var contextLoading by remember(workspaceId) { mutableStateOf(true) }
    LaunchedEffect(workspaceId) {
        contextLoading = true
        context = try {
            viewModel.workspaceContext(workspaceId)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("WorkspaceDetail", "workspace.context failed: ${t.message}")
            null
        }
        contextLoading = false
    }

    // Peek sheet: null = closed; otherwise the tab to open pre-selected.
    var peekTab by remember { mutableStateOf<PeekTarget?>(null) }

    // Overflow housekeeping dialogs (reuse the main-list workspace flows).
    var renameOpen by remember { mutableStateOf(false) }
    var emojiOpen by remember { mutableStateOf(false) }

    // Session-row context menu (rename / mute / delete) — same as the main list.
    var contextMenuSession by remember { mutableStateOf<SessionUiModel?>(null) }
    var renameTarget by remember { mutableStateOf<SessionUiModel?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionUiModel?>(null) }
    var clearTarget by remember { mutableStateOf<SessionUiModel?>(null) }

    Scaffold(
        // Hosted inside MarmaladeApp's outer Scaffold, which already applies
        // the system-bar insets — zero them here or the status bar pads twice.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            WorkspaceDetailTopBar(
                workspace = workspace,
                onBack = onBack,
                onNewSession = {
                    viewModel.createSessionInWorkspace(workspace.path) { onSessionClick(it) }
                },
                onNewTerminal = if (terminalSupported) {
                    { viewModel.createTerminal(workspace.path) { onTerminalClick(it) } }
                } else {
                    null
                },
                onRename = { renameOpen = true },
                onChangeEmoji = { emojiOpen = true },
                onRemove = {
                    viewModel.deleteWorkspace(workspace.workspace_id)
                    onBack()
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        viewModel.createSessionInWorkspace(workspace.path) { onSessionClick(it) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New session in ${workspace.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Path, dimmed monospace, single line.
            Text(
                text = workspace.path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Context strip. Placeholder while loading; omitted on error/empty.
            ContextStrip(
                context = context,
                loading = contextLoading,
                onChipTap = { target -> peekTab = target },
            )

            HorizontalDivider()

            if (sessions.isEmpty() && archivedForWs.isEmpty() && terminalsForWs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No sessions yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Flat, most-recent-first. No date buckets in the workspace
                // detail — the per-row relative timestamp ("4m"/"2h") is the
                // only time dimension we want here (maintainer, 2026-07-23). Archived
                // sessions live in a collapsible section below the active rows.
                val ordered = remember(sessions) {
                    sessions.sortedByDescending { it.lastMessageAt ?: it.createdAt }
                }
                // Shared row builder — same context menu (rename / mute /
                // archive / delete / clear) as the active and archived rows.
                val rowWithMenu: @Composable (SessionUiModel) -> Unit = { session ->
                    SessionRowWithContextMenu(
                        session = session,
                        contextMenuSession = contextMenuSession,
                        onClick = { onSessionClick(session.id) },
                        onLongClick = { contextMenuSession = session },
                        onDismissMenu = { contextMenuSession = null },
                        onRename = {
                            renameTarget = session
                            contextMenuSession = null
                        },
                        onDelete = {
                            deleteTarget = session
                            contextMenuSession = null
                        },
                        onClear = {
                            clearTarget = session
                            contextMenuSession = null
                        },
                        onToggleMute = {
                            viewModel.toggleMuteSession(session.id, !session.isMuted)
                            contextMenuSession = null
                        },
                        onArchive = {
                            viewModel.archiveSession(session.id, !session.archived)
                            contextMenuSession = null
                        },
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = ordered, key = { "wsd_session_${it.id}" }) { session ->
                        rowWithMenu(session)
                    }
                    // Terminals scoped to this workspace (server-stamped).
                    // Spawned via the overflow menu's "New terminal here".
                    if (terminalsForWs.isNotEmpty()) {
                        item(key = "wsd_terminals_header") {
                            Text(
                                text = "Terminals",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(items = terminalsForWs, key = { "wsd_terminal_${it.terminal_id}" }) { t ->
                            TerminalRow(
                                terminal = t,
                                onOpen = { onTerminalClick(t.terminal_id) },
                                onKill = { viewModel.closeTerminal(t.terminal_id) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (archivedForWs.isNotEmpty()) {
                        item(key = "wsd_archived_header") {
                            ArchivedSectionHeader(
                                count = archivedForWs.size,
                                expanded = archivedExpanded,
                                onToggle = { archivedExpanded = !archivedExpanded },
                            )
                        }
                        if (archivedExpanded) {
                            items(items = archivedForWs, key = { "wsd_archived_${it.id}" }) { session ->
                                rowWithMenu(session)
                            }
                        }
                    }
                }
            }
        }
    }

    // Context-peek sheet — driven by the already-loaded context, no second RPC.
    peekTab?.let { tab ->
        context?.let { ctx ->
            WorkspaceContextPeekSheet(
                context = ctx,
                initialTab = tab,
                onDismiss = { peekTab = null },
            )
        }
    }

    // Workspace housekeeping.
    if (renameOpen) {
        WorkspaceRenameDialog(
            currentName = workspace.name,
            onConfirm = {
                viewModel.updateWorkspace(workspace.workspace_id, name = it)
                renameOpen = false
            },
            onDismiss = { renameOpen = false },
        )
    }
    if (emojiOpen) {
        EmojiPickerSheet(
            onEmojiSelected = {
                viewModel.updateWorkspace(workspace.workspace_id, emoji = it)
                emojiOpen = false
            },
            onClear = {
                viewModel.updateWorkspace(workspace.workspace_id, clearEmoji = true)
                emojiOpen = false
            },
            onDismiss = { emojiOpen = false },
        )
    }

    // Session rename / delete (reuse the main-list dialogs + VM calls).
    renameTarget?.let { session ->
        RenameSessionDialog(
            currentName = session.title,
            currentEmoji = session.emoji,
            onConfirm = { newName, newEmoji ->
                viewModel.renameSession(session.id, newName, session.isGateway, emoji = newEmoji)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { session ->
        DeleteSessionDialog(
            sessionName = session.title,
            onConfirm = {
                viewModel.deleteSession(session.id, session.isGateway)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
    // The main session can't be deleted — its context menu offers Clear instead.
    clearTarget?.let { session ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text("Clear conversation?") },
            text = { Text("This wipes the assistant's chat history and starts it fresh. This can't be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.clearSession(session.id)
                    clearTarget = null
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { clearTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WorkspaceDetailTopBar(
    workspace: WorkspaceInfo,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    /** null = daemon doesn't support terminals; the menu item is omitted. */
    onNewTerminal: (() -> Unit)?,
    onRename: () -> Unit,
    onChangeEmoji: () -> Unit,
    onRemove: () -> Unit,
) {
    TopAppBar(
        windowInsets = WindowInsets(0),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val emoji = workspace.emoji
                if (!emoji.isNullOrBlank()) {
                    Text(text = emoji)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = workspace.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            Box {
                var showMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Workspace actions")
                }
                MarmaladeMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    MarmaladeMenuItem(
                        label = "New session here",
                        icon = Icons.Default.Add,
                        emphasized = true,
                        onClick = { showMenu = false; onNewSession() },
                    )
                    if (onNewTerminal != null) {
                        MarmaladeMenuItem(
                            label = "New terminal here",
                            icon = Icons.Outlined.Terminal,
                            onClick = { showMenu = false; onNewTerminal() },
                        )
                    }
                    MarmaladeMenuItem(
                        label = "Rename",
                        icon = Icons.Outlined.Edit,
                        onClick = { showMenu = false; onRename() },
                    )
                    MarmaladeMenuItem(
                        label = "Change emoji",
                        icon = Icons.Outlined.EmojiEmotions,
                        onClick = { showMenu = false; onChangeEmoji() },
                    )
                    MarmaladeMenuDivider()
                    MarmaladeMenuItem(
                        label = "Remove workspace",
                        supporting = "Sessions are kept",
                        icon = Icons.Outlined.Delete,
                        destructive = true,
                        onClick = { showMenu = false; onRemove() },
                    )
                }
            }
        },
    )
}

/** Horizontal row of context chips. Placeholder shimmer-less box while loading;
 *  omitted entirely on error (null after load) or when nothing is present. */
@Composable
private fun ContextStrip(
    context: WorkspaceContextResponse?,
    loading: Boolean,
    onChipTap: (PeekTarget) -> Unit,
) {
    if (loading) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .width(96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        return
    }
    val ctx = context ?: return
    val chips = WorkspaceContextUtils.chips(ctx)
    if (chips.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip -> ContextChipView(chip = chip, onTap = onChipTap) }
    }
}

@Composable
private fun ContextChipView(chip: ContextChip, onTap: (PeekTarget) -> Unit) {
    val shape = RoundedCornerShape(50)
    val base = Modifier.clip(shape)
    val styled = if (chip.outlined) {
        base.border(1.dp, MaterialTheme.colorScheme.outline, shape)
    } else {
        base.background(MaterialTheme.colorScheme.surfaceVariant)
    }
    // Bound to a local val rather than smart-cast: `ContextChip` now lives in
    // :shared, and Kotlin refuses to smart-cast a public property declared in
    // another module (it can't prove the getter is stable across compilations).
    val peek = chip.peek
    val clickable = if (peek != null) {
        styled.clickable { onTap(peek) }
    } else {
        styled
    }
    Text(
        text = chip.label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = clickable.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
