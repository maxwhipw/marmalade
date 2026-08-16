package app.marmalade.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared detail **modal** for a settings list entry (skill / MCP server /
 * plugin). Tapping a row opens this; the Switch — both in the row and here —
 * is the only thing that toggles, so a mis-tap can't silently flip an entry.
 *
 * A centred [AlertDialog] (not a bottom sheet — the maintainer's call, 2026-07-22): a
 * title, an optional [subtitle] line, an optional untruncated [description]
 * ("About"), and an enable/disable toggle row with an honest [toggleSubtext]
 * caption (e.g. "Applies on the next session").
 *
 * The caller is expected to re-resolve the entry against its live list each
 * recomposition so [toggleEnabled] tracks the optimistic flip while the
 * dialog stays open.
 */
@Composable
fun SettingDetailDialog(
    title: String,
    toggleEnabled: Boolean,
    toggleTitle: String,
    onToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    description: String? = null,
    toggleSubtext: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                // Only the subtitle/description scrolls; the divider + toggle
                // row sit OUTSIDE the scroll region so a long description can
                // never push the toggle off-screen.
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (!description.isNullOrBlank()) {
                        if (!subtitle.isNullOrBlank()) Spacer(Modifier.height(16.dp))
                        Text(
                            text = "About",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(!toggleEnabled) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = toggleTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!toggleSubtext.isNullOrBlank()) {
                            Text(
                                text = toggleSubtext,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = toggleEnabled,
                        onCheckedChange = onToggle,
                    )
                }
            }
        },
    )
}
