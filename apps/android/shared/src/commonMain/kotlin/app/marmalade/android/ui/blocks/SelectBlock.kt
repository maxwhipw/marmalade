package app.marmalade.android.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Select block: displays a message with single-choice radio options.
 *
 * Tapping an option immediately sends the response.
 * After selection, all options become non-interactive and the selected one shows a checkmark.
 */
@Composable
fun SelectBlock(
    data: SelectData,
    onInteraction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            // No outer padding: AgentPromptCard supplies the card's inset.
            // Keeping a second 12dp here double-padded every block once the
            // frames were unified.
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = data.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        for (option in data.options) {
            val isSelected = selectedId == option.id
            val isDisabled = selectedId != null

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!isDisabled) {
                            Modifier.clickable {
                                selectedId = option.id
                                onInteraction(option.id)
                            }
                        } else {
                            Modifier.alpha(if (isSelected) 1f else 0.5f)
                        }
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                } else {
                    RadioButton(
                        selected = false,
                        onClick = if (!isDisabled) {
                            {
                                selectedId = option.id
                                onInteraction(option.id)
                            }
                        } else null,
                        enabled = !isDisabled,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDisabled && !isSelected) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
