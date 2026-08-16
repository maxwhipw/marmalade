package app.marmalade.android.ui.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.marmalade.android.SessionListViewModel
import kotlinx.coroutines.launch

/**
 * The whole "add a workspace" flow in one composable: gateway-side folder
 * picker → name/emoji confirm → `workspace.create`.
 *
 * The two halves used to be loose state inside the Sessions screen, which is
 * why they died with it (ADR 0013). Packaged here they can be opened from the
 * drawer — or anywhere else — with a single boolean.
 */
@Composable
fun NewWorkspaceSheet(
    viewModel: SessionListViewModel,
    onDismiss: () -> Unit,
    onCreated: (workspaceId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pickedPath by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val path = pickedPath
    if (path == null) {
        WorkspacePickerSheet(
            initialPath = null,
            resolveDefaultPath = { viewModel.getDefaultWorkspace()?.cwd },
            listDir = { p, showHidden -> viewModel.browseWorkspace(p, showHidden) },
            onSelect = { selected ->
                error = null
                pickedPath = selected
            },
            onDismiss = onDismiss,
        )
    } else {
        WorkspaceCreateConfirmDialog(
            path = path,
            creating = creating,
            errorMessage = error,
            onConfirm = { name, emoji ->
                creating = true
                error = null
                scope.launch {
                    try {
                        val ws = viewModel.createWorkspace(path, name.ifBlank { null }, emoji)
                        creating = false
                        onCreated(ws.workspace_id)
                    } catch (t: Throwable) {
                        // Surfaced in the dialog rather than swallowed: the
                        // daemon rejects duplicates, non-folders and paths
                        // outside home, and the user can fix all three.
                        creating = false
                        error = t.message ?: "Couldn't create workspace"
                    }
                }
            },
            onDismiss = {
                if (!creating) onDismiss()
            },
        )
    }
}
