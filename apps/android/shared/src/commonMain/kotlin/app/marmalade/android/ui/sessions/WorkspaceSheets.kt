package app.marmalade.android.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.marmalade.android.rpc.types.WorkspaceInfo

/**
 * Long-press actions for a workspace card: new session, rename, change emoji,
 * and remove (un-group; sessions are kept). Rename / emoji route through
 * workspace.update; remove through workspace.delete + refresh — the caller owns
 * those RPC calls, this sheet only surfaces the choices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceActionsSheet(
    workspace: WorkspaceInfo,
    onNewSession: () -> Unit,
    onRename: () -> Unit,
    onChangeEmoji: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = workspace.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = workspace.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.size(8.dp))

            SheetRow(Icons.Default.Add, "New session here", onNewSession)
            SheetRow(Icons.Outlined.Edit, "Rename workspace", onRename)
            SheetRow(Icons.Outlined.EmojiEmotions, "Change emoji", onChangeEmoji)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            SheetRow(
                icon = Icons.Outlined.Delete,
                label = "Remove workspace",
                subLabel = "Sessions are kept — they move to Quick sessions",
                tint = MaterialTheme.colorScheme.error,
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    subLabel: String? = null,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    val contentTint = tint ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentTint)
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = contentTint)
            if (subLabel != null) {
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Simple single-field rename dialog for a workspace. */
@Composable
fun WorkspaceRenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename workspace") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirm step of the add-workspace flow: the folder is already picked; show
 * its path (mono), a client-side name field pre-filled from the prettified
 * basename, an emoji picker, then call workspace.create on confirm. Detection
 * chips aren't available until the workspace exists, so this step shows only
 * what we know pre-create (the path); the created card carries detection.
 *
 * [onCreate] runs the RPC and either succeeds (dialog closes) or surfaces the
 * daemon's [errorMessage] (duplicate / outside home).
 */
@Composable
fun WorkspaceCreateConfirmDialog(
    path: String,
    creating: Boolean,
    errorMessage: String?,
    onConfirm: (name: String, emoji: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(prettifyBasename(path)) }
    var emoji by remember { mutableStateOf<String?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Don't dismiss on outside-tap while the RPC is in flight.
        properties = DialogProperties(dismissOnClickOutside = !creating),
        title = { Text("New workspace") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiAvatarButton(
                        emoji = emoji,
                        fallbackLetter = name.firstOrNull()?.uppercaseChar() ?: 'W',
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
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), emoji) },
                enabled = !creating && name.trim().isNotBlank(),
            ) {
                if (creating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("Cancel") } },
    )

    if (showEmojiPicker) {
        EmojiPickerSheet(
            onEmojiSelected = { emoji = it },
            onClear = { emoji = null },
            onDismiss = { showEmojiPicker = false },
        )
    }
}

/** Prettify a folder basename into a default workspace name, mirroring the
 *  daemon default ("marmalade-client-android" → "Marmalade Client Android"). */
internal fun prettifyBasename(path: String): String =
    WorkspacePaths.pathName(path)
        .split('-', '_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        .ifBlank { WorkspacePaths.pathName(path) }
