package app.marmalade.android.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.android.ui.home.MascotExpression
import app.marmalade.android.ui.home.MascotImage
import app.marmalade.android.ui.theme.Wordmark
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Empty state shown when chat has no messages and is not streaming.
 *
 * Displays the mascot icon, a hint message, and tappable suggestion chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatEmptyState(
    onSuggestionTap: (String) -> Unit,
    mascotExpression: MascotExpression = MascotExpression.HAPPY,
    modifier: Modifier = Modifier,
) {
    val suggestions = listOf(
        "What can you do?",
        "Help me with code",
        "Tell me a joke",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Mascot icon — expression driven by connection state on home tab
        MascotImage(
            expression = mascotExpression,
            modifier = Modifier.size(100.dp),
        )

        Spacer(modifier = Modifier.height(14.dp))

        // The wordmark, not a sentence. An empty chat is the one moment the
        // app has nothing to say and every reason to say who it is — and the
        // old line ("Message Marmalade...") just restated the composer's own
        // placeholder two inches below it.
        Text(
            text = "marmalade",
            fontFamily = Wordmark,
            fontWeight = FontWeight.Normal,
            fontSize = 30.sp,
            color = MaterialTheme.marmaladeColors.wordmark,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Suggestion chips
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (suggestion in suggestions) {
                OutlinedButton(
                    onClick = { onSuggestionTap(suggestion) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Push content above true center
        Spacer(modifier = Modifier.height(60.dp))
    }
}
