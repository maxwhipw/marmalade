package app.marmalade.android.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * Bubble shape with the tail on the bottom-left — the visual marker for
 * an incoming assistant message. Shared between [ActivityBubble] (which
 * renders before the first part lands so it must look like an empty
 * assistant bubble) and any other surface that needs to mirror the
 * incoming-message look.
 */
// Public, not internal: `:app`'s ActivityBubble reads it across the module
// boundary now that this file lives in :shared.
val AssistantBubbleTailShape = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 18.dp,
    bottomStart = 4.dp,
    bottomEnd = 18.dp,
)

/**
 * Three small dots that pulse in sequence — the canonical "agent is
 * working" affordance. Sized small enough to live inside the activity
 * bubble or the assistant-side mini chat header.
 */
// Public, not internal: `:app`'s VoicePopupUI calls it across the module
// boundary now that this file lives in :shared.
@Composable
fun DotPulse() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index -> PulseDot(delayMs = index * 200) }
    }
}

@Composable
private fun PulseDot(delayMs: Int) {
    val infinite = rememberInfiniteTransition(label = "dot")
    val dotAlpha by infinite.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 900
                0.2f at 0
                1f at 300
                0.2f at 600
                0.2f at 900
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(delayMs),
        ),
        label = "dotAlpha",
    )
    Surface(
        modifier = Modifier
            .size(6.dp)
            .alpha(dotAlpha),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {}
}
