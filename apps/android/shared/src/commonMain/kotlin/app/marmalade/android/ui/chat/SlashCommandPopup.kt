package app.marmalade.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Autocomplete popup for slash commands, displayed above the input bar.
 *
 * Shows matching commands as rows with command name (bold monospace) and
 * description. Maximum 5 visible rows, scrollable via LazyColumn if more.
 *
 * Uses Surface with elevation and rounded top corners for a clean look.
 */
@Composable
fun SlashCommandPopup(
    commands: List<SlashCommand>,
    onCommandSelected: (SlashCommand) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (commands.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Maximum height for 5 rows (each ~48dp) = 240dp
        LazyColumn(
            modifier = Modifier.heightIn(max = 240.dp),
        ) {
            items(
                items = commands,
                key = { it.command },
            ) { command ->
                SlashCommandRow(
                    command = command,
                    onClick = { onCommandSelected(command) },
                )
            }
        }
    }
}

/**
 * Single row in the slash command popup showing command name and description.
 */
@Composable
private fun SlashCommandRow(
    command: SlashCommand,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = command.command,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (command.parameters != null) {
                    Text(
                        text = " ${command.parameters}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            Text(
                text = command.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
