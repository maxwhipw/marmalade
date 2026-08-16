package app.marmalade.android.ui.sessions

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Dialog for renaming an existing session.
 *
 * Invoked by the `/title` slash command (no args) in the composer.
 * The dialog itself is pure UI — it does not call any RPC. The caller
 * is responsible for invoking `sessionTitle` RPC inside [onConfirm].
 *
 * @param currentTitle  Pre-fills the text field with the session's current name.
 * @param onConfirm     Called with the trimmed new title when the user taps "Rename".
 *                      Guaranteed non-blank (the button is disabled for blank input).
 * @param onDismiss     Called when the user taps "Cancel" or dismisses the dialog.
 */
@Composable
fun RenameSessionDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Trim server-side whitespace before pre-filling — server titles
    // occasionally carry trailing newline / padding that makes the field
    // render misaligned.
    var title by remember(currentTitle) { mutableStateOf(currentTitle.trim()) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Session title") },
                singleLine = true,
                modifier = androidx.compose.ui.Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.trim()) },
                enabled = title.isNotBlank(),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/**
 * Returns true if [title] is a valid (non-blank) session title after trimming.
 * Extracted as a pure function so it can be unit-tested without Compose.
 */
fun isValidSessionTitle(title: String): Boolean = title.isNotBlank()
