package app.marmalade.android.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.marmalade.android.SessionUiModel
import app.marmalade.android.ui.components.MarmaladeMenu
import app.marmalade.android.ui.components.MarmaladeMenuDivider
import app.marmalade.android.ui.components.MarmaladeMenuItem

/**
 * Session-row affordances shared by every surface that lists sessions.
 *
 * These lived inside `SessionListScreen.kt` until ADR 0013 deleted that screen
 * (the drawer is the only navigator now). They were always shared — the
 * workspace detail screen used them too — so they moved here rather than dying
 * with their old host.
 */

/**
 * Collapsible "Archived" section header — a full-width tappable row showing the
 * count and a chevron. Shared by the archived list and the workspace detail
 * screen so both archived surfaces read identically.
 */
@Composable
// Public, not internal: reached from `:app`'s ArchivedSessionsScreen / WorkspaceDetailScreen.
fun ArchivedSectionHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Archive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Archived ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Session row with an attached long-press context menu. */
@Composable
// Public, not internal: reached from `:app`'s ArchivedSessionsScreen / WorkspaceDetailScreen.
fun SessionRowWithContextMenu(
    session: SessionUiModel,
    contextMenuSession: SessionUiModel?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onToggleMute: () -> Unit = {},
    onArchive: () -> Unit = {},
) {
    Column {
        SessionRow(
            session = session,
            onClick = onClick,
            onLongClick = onLongClick,
        )

        MarmaladeMenu(
            expanded = contextMenuSession?.id == session.id,
            onDismissRequest = onDismissMenu,
            offset = DpOffset(48.dp, 0.dp),
        ) {
            MarmaladeMenuItem(
                label = "Rename",
                icon = Icons.Outlined.Edit,
                onClick = onRename,
            )

            MarmaladeMenuItem(
                label = if (session.isMuted) "Turn on notifications" else "Turn off notifications",
                icon = if (session.isMuted) Icons.Outlined.NotificationsOff
                    else Icons.Outlined.Notifications,
                onClick = onToggleMute,
            )

            // Archive / unarchive (session.archive). Never offered for the main
            // session — the daemon refuses it (it's the pinned Home surface).
            if (!session.isMain) {
                MarmaladeMenuItem(
                    label = if (session.archived) "Unarchive" else "Archive",
                    icon = if (session.archived) Icons.Outlined.Unarchive
                        else Icons.Outlined.Archive,
                    onClick = onArchive,
                )
            }

            if (session.isDeletable) {
                MarmaladeMenuDivider()
                MarmaladeMenuItem(
                    label = "Delete",
                    icon = Icons.Outlined.Delete,
                    destructive = true,
                    onClick = onDelete,
                )
            }

            // The main session can't be deleted (the daemon refuses it) — offer
            // Clear (session.clear, reset in place) instead. Shown only for the
            // main row; ordinary sessions clear via a new chat, not here.
            if (session.isMain) {
                MarmaladeMenuItem(
                    label = "Clear conversation",
                    icon = Icons.Outlined.Delete,
                    onClick = onClear,
                )
            }
        }
    }
}

/** Rename a session, with the optional emoji avatar picker. */
@Composable
// Public, not internal: reached from `:app`'s ArchivedSessionsScreen / WorkspaceDetailScreen.
fun RenameSessionDialog(
    currentName: String,
    currentEmoji: String?,
    onConfirm: (name: String, emoji: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    var emoji by remember { mutableStateOf(currentEmoji) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiAvatarButton(
                    emoji = emoji,
                    fallbackLetter = name.firstOrNull()?.uppercaseChar() ?: 'S',
                    onClick = { showEmojiPicker = true },
                )
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), emoji) },
                enabled = name.trim().isNotBlank(),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (showEmojiPicker) {
        EmojiPickerSheet(
            onEmojiSelected = { emoji = it },
            onClear = { emoji = null },
            onDismiss = { showEmojiPicker = false },
        )
    }
}

/** Confirmation before deleting a session and its history. */
@Composable
// Public, not internal: reached from `:app`'s ArchivedSessionsScreen / WorkspaceDetailScreen.
fun DeleteSessionDialog(
    sessionName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Session") },
        text = {
            Text("Delete \"$sessionName\"? This will remove the session and its history.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
