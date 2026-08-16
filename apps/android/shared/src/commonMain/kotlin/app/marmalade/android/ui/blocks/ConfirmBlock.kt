package app.marmalade.android.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Confirm block: displays a message with Yes/No (or custom-labeled) buttons.
 *
 * After interaction, both buttons become disabled and the selected one shows a checkmark.
 */
@Composable
fun ConfirmBlock(
    data: ConfirmData,
    onInteraction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var responded by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            // No outer padding: AgentPromptCard supplies the card's inset.
            // Keeping a second 12dp here double-padded every block once the
            // frames were unified.
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = data.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Cancel button (outlined)
            OutlinedButton(
                onClick = {
                    if (responded == null) {
                        responded = "cancelled"
                        onInteraction("cancelled")
                    }
                },
                enabled = responded == null,
                modifier = Modifier.weight(1f),
            ) {
                if (responded == "cancelled") {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Text(data.cancelLabel)
            }

            // Confirm button (filled amber)
            Button(
                onClick = {
                    if (responded == null) {
                        responded = "confirmed"
                        onInteraction("confirmed")
                    }
                },
                enabled = responded == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f),
            ) {
                if (responded == "confirmed") {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Text(data.confirmLabel)
            }
        }
    }
}
