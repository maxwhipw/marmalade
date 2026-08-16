package app.marmalade.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.theme.MarmaladeMono

/**
 * "jumped 74 messages back" — the honesty note after a search-result jump
 * (design-lab `session-search` lab 3, frame 1).
 *
 * Landing 74 messages up a transcript is disorienting without it: the view
 * looks like the live end of a conversation that is actually far below. The
 * pill says how far, then gets out of the way (the caller nulls [count] on a
 * short timer, which fades it out).
 */
@Composable
fun AnchorJumpPill(
    count: Int?,
    modifier: Modifier = Modifier,
) {
    // Held across the fade-out so the pill never flashes a stale/zero count
    // as it leaves (`count` goes null the moment the timer fires).
    var lastCount by remember { mutableStateOf(count ?: 0) }
    if (count != null) lastCount = count
    AnimatedVisibility(
        visible = count != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
        ) {
            Text(
                text = anchorJumpPillText(lastCount),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MarmaladeMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** Singular/plural, and "back to the start" reads better than "back". */
internal fun anchorJumpPillText(count: Int): String =
    if (count == 1) "jumped 1 message back" else "jumped $count messages back"
