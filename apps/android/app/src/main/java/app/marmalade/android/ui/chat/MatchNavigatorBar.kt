package app.marmalade.android.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.theme.MarmaladeMono
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * The match navigator (design-lab `session-search` lab 3, frame 1).
 *
 * Sits between the top bar and the transcript and keeps the query ALIVE after
 * a jump: ↑/↓ walk every match in this session without a round-trip back to
 * the results list, and ✕ drops back to a normal session view.
 *
 * Accent-toned on purpose — it is the same peach/rich-brown pair the user
 * bubble wears (`marmaladeColors.userBubble` / `onUserBubble`), which is what
 * the lab's `--accent-bg` is. Reading as chrome (a surface tint) would let it
 * disappear into the transcript; it is a transient MODE the user is in, and
 * modes have to be visible to be exitable.
 */
@Composable
fun MatchNavigatorBar(
    state: MatchNavigatorState,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.marmaladeColors.onUserBubble
    Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.marmaladeColors.userBubble,
        contentColor = ink,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    text = state.query,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The cap admission. Subtle, but present — a bounded walk that
                // looks unbounded is the one way this bar could mislead.
                val note = when {
                    state.error != null -> state.error
                    state.capped -> "first ${state.matches.size} of ${state.total}"
                    else -> null
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MarmaladeMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = if (state.loading) "…" else state.counter,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MarmaladeMono,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            // Up = older = lower seq, matching the direction the transcript
            // scrolls to reach it.
            StepButton(
                enabled = state.canStepBack,
                onClick = onStepBack,
                icon = Icons.Default.KeyboardArrowUp,
                description = "Previous match",
                ink = ink,
            )
            StepButton(
                enabled = state.canStepForward,
                onClick = onStepForward,
                icon = Icons.Default.KeyboardArrowDown,
                description = "Next match",
                ink = ink,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss match navigator",
                    tint = ink.copy(alpha = DIMMED_ALPHA),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun StepButton(
    enabled: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    ink: androidx.compose.ui.graphics.Color,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            // Disabled at an end rather than wrapped: the arrow greys out so
            // "there is nothing further this way" is visible before the tap.
            tint = if (enabled) ink else ink.copy(alpha = DISABLED_ALPHA),
        )
    }
}

/** The ✕ is secondary to the arrows — present, not competing. */
private const val DIMMED_ALPHA = 0.65f

/** Material's disabled-content opacity, applied to brand ink rather than the
 *  M3 role (the bar is not on a Material container). */
private const val DISABLED_ALPHA = 0.38f
