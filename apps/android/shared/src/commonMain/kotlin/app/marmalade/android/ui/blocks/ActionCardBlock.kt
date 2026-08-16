package app.marmalade.android.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Action card block: displays a list of tappable action buttons.
 *
 * Each button shows an optional icon + label. Tapping sends the action id as response.
 * After tap, the tapped button gets a checkmark and all buttons become disabled.
 */
@Composable
fun ActionCardBlock(
    data: ActionData,
    onInteraction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tappedId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            // No outer padding: AgentPromptCard supplies the card's inset.
            // Keeping a second 12dp here double-padded every block once the
            // frames were unified.
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (action in data.actions) {
            val isTapped = tappedId == action.id
            val isDisabled = tappedId != null

            OutlinedButton(
                onClick = {
                    if (tappedId == null) {
                        tappedId = action.id
                        onInteraction(action.id)
                    }
                },
                enabled = !isDisabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isTapped) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    } else if (action.icon != null) {
                        val iconVector = mapIconName(action.icon)
                        if (iconVector != null) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = action.label,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDisabled && !isTapped) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/**
 * Map a Material icon name string to the corresponding ImageVector.
 * Supports a subset of commonly used Material icons.
 */
private fun mapIconName(name: String): ImageVector? {
    return when (name.lowercase().replace("-", "_")) {
        "play_arrow", "play" -> Icons.Default.PlayArrow
        "search" -> Icons.Default.Search
        "settings", "gear" -> Icons.Default.Settings
        "share" -> Icons.Default.Share
        "star", "favorite" -> Icons.Default.Star
        "check" -> Icons.Default.Check
        else -> null
    }
}
