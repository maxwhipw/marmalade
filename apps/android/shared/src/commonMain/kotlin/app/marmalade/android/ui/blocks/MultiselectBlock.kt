package app.marmalade.android.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
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
 * Multiselect block: displays a message with checkbox options and a Submit button.
 *
 * User can toggle multiple checkboxes before pressing Submit.
 * After submit, checkboxes become disabled and selected ones show as checked.
 */
@Composable
fun MultiselectBlock(
    data: MultiselectData,
    onInteraction: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var submitted by remember { mutableStateOf(false) }

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
            val isChecked = option.id in selectedIds

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (submitted && !isChecked) Modifier.alpha(0.5f) else Modifier)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { checked ->
                        if (!submitted) {
                            selectedIds = if (checked) {
                                selectedIds + option.id
                            } else {
                                selectedIds - option.id
                            }
                        }
                    },
                    enabled = !submitted,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                    ),
                )

                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (submitted && !isChecked) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Submit button
        Button(
            onClick = {
                if (!submitted && selectedIds.isNotEmpty()) {
                    submitted = true
                    onInteraction(selectedIds.toList())
                }
            },
            enabled = !submitted && selectedIds.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(data.submitLabel)
        }
    }
}
