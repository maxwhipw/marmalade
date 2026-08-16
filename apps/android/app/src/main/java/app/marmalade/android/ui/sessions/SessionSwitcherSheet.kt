package app.marmalade.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.SessionListViewModel
import app.marmalade.android.ui.components.MarmaladeMenu
import app.marmalade.android.ui.components.MarmaladeMenuDivider
import app.marmalade.android.ui.components.MarmaladeMenuItem
import app.marmalade.android.utils.SessionSwitcherUtils

/**
 * The title-bar session switcher (ADR 0013 step 1).
 *
 * Tapping the chat title opens this: every session and terminal in the current
 * workspace, one tap away. That is the two-tap switch the whole navigation
 * redesign is built around — before this, changing session meant back → tab →
 * card → row.
 *
 * Sessions and terminals sit in the same list because they are peers of the
 * workspace, not of each other (ADR 0013 decision 4). A terminal you started
 * while working in a workspace is findable from any session in that workspace,
 * which is the direct fix for "I forgot which session my shell was in".
 *
 * [SessionSwitcher] is the wired host (owns the query, the rename dialog and
 * the ViewModel calls); [SessionSwitcherSheet] below is presentational.
 */
@Composable
fun SessionSwitcher(
    viewModel: SessionListViewModel,
    currentSessionKey: String?,
    onSelectSession: (String) -> Unit,
    onSelectTerminal: (String) -> Unit,
    onDismiss: () -> Unit,
    currentTerminalId: String? = null,
) {
    val layout by viewModel.workspaceLayout.collectAsStateWithLifecycle()
    val terminals by viewModel.terminals.collectAsStateWithLifecycle()
    val terminalSupported by viewModel.terminalSupported.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Both rosters are cheap and go stale while the user sits in a chat.
    LaunchedEffect(Unit) {
        viewModel.refreshSessions()
        viewModel.refreshWorkspaces()
        viewModel.refreshTerminals()
    }

    val content = remember(layout, terminals, currentSessionKey, currentTerminalId, query) {
        SessionSwitcherUtils.build(
            layout = layout,
            terminals = terminals,
            currentSessionKey = currentSessionKey,
            currentTerminalId = currentTerminalId,
            query = query,
        )
    }

    SessionSwitcherSheet(
        content = content,
        query = query,
        onQueryChange = { query = it },
        terminalSupported = terminalSupported,
        onSelectSession = onSelectSession,
        onSelectTerminal = onSelectTerminal,
        onRenameSession = { key, title -> renameTarget = key to title },
        onArchiveSession = { key -> viewModel.archiveSession(key, true) },
        onDeleteSession = { key -> viewModel.deleteSession(key, isGateway = true) },
        onCloseTerminal = { id -> viewModel.closeTerminal(id) },
        onNewSession = {
            // A workspace-scoped create rides the workspace path; in quick
            // scope we pass null and let the daemon apply its own default cwd.
            val path = content.workspacePath
            if (path != null) {
                viewModel.createSessionInWorkspace(path) { onSelectSession(it) }
            } else {
                viewModel.createSession(name = "New Chat", isGateway = true) { key, _ ->
                    onSelectSession(key)
                }
            }
        },
        onNewTerminal = {
            viewModel.createTerminal(content.workspacePath) { onSelectTerminal(it) }
        },
        onDismiss = onDismiss,
    )

    renameTarget?.let { (key, title) ->
        RenameSessionDialog(
            currentTitle = title,
            onConfirm = { newName ->
                viewModel.renameSession(key, newName, isGateway = true)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSwitcherSheet(
    content: SessionSwitcherUtils.SwitcherContent,
    query: String,
    onQueryChange: (String) -> Unit,
    terminalSupported: Boolean,
    onSelectSession: (String) -> Unit,
    onSelectTerminal: (String) -> Unit,
    onRenameSession: (key: String, currentTitle: String) -> Unit,
    onArchiveSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCloseTerminal: (String) -> Unit,
    onNewSession: () -> Unit,
    onNewTerminal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            // Scope header — which workspace this list belongs to. Without it
            // a filtered list is indistinguishable from an empty workspace.
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                Text(
                    text = content.workspaceName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                content.workspacePath?.let { path ->
                    Text(
                        text = path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (content.showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text("Filter sessions") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(content.sessions, key = { it.session.id }) { row ->
                    SwitcherSessionRow(
                        row = row,
                        onClick = { onSelectSession(row.session.id) },
                        onRename = { onRenameSession(row.session.id, row.session.title) },
                        onArchive = { onArchiveSession(row.session.id) },
                        onDelete = { onDeleteSession(row.session.id) },
                    )
                }
                items(content.terminals, key = { it.terminal.terminal_id }) { row ->
                    SwitcherTerminalRow(
                        row = row,
                        onClick = { onSelectTerminal(row.terminal.terminal_id) },
                        onClose = { onCloseTerminal(row.terminal.terminal_id) },
                    )
                }
                if (content.isEmpty) {
                    item {
                        Text(
                            text = if (content.isFiltered) "No matches"
                            else "Nothing here yet — start a session below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            SwitcherActionRow(
                icon = Icons.Default.Add,
                label = "New session",
                supporting = content.workspaceName,
                onClick = { onNewSession() },
            )
            if (terminalSupported) {
                SwitcherActionRow(
                    icon = Icons.Outlined.Terminal,
                    label = "New terminal",
                    supporting = content.workspaceName,
                    onClick = { onNewTerminal() },
                )
            }
            Spacer(modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

/** Highlight for the row you're already on — same tint as an emphasized
 *  [MarmaladeMenuItem], so "current" reads identically everywhere. */
@Composable
private fun currentTint(isCurrent: Boolean): Color =
    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent

@Composable
private fun SwitcherSessionRow(
    row: SessionSwitcherUtils.SessionRow,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(currentTint(row.isCurrent))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = row.session.emoji
                    ?: row.session.title.trim().take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.session.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val status = when {
                row.session.running -> "Running"
                row.session.awaitingInput -> "Waiting on you"
                row.session.serverUnread -> "New"
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Session actions",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MarmaladeMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MarmaladeMenuItem(
                    label = "Rename",
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    onClick = { menuOpen = false; onRename() },
                )
                MarmaladeMenuItem(
                    label = "Archive",
                    icon = Icons.Outlined.Archive,
                    onClick = { menuOpen = false; onArchive() },
                )
                if (row.session.isDeletable) {
                    MarmaladeMenuDivider()
                    MarmaladeMenuItem(
                        label = "Delete",
                        icon = Icons.Outlined.Delete,
                        destructive = true,
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitcherTerminalRow(
    row: SessionSwitcherUtils.TerminalRow,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(currentTint(row.isCurrent))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.inverseSurface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ">_",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.terminal.shell.ifBlank { "shell" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = WorkspacePaths.pathName(row.terminal.cwd),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Terminal actions",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MarmaladeMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MarmaladeMenuItem(
                    label = "Close terminal",
                    icon = Icons.Outlined.Delete,
                    supporting = "Ends the shell process",
                    destructive = true,
                    onClick = { menuOpen = false; onClose() },
                )
            }
        }
    }
}

@Composable
private fun SwitcherActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    supporting: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "in $supporting",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
