package app.marmalade.android.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Animated mascot image with expression support, idle blink, and gentle bob.
 *
 * Expressions are driven by app state (connection status, voice state, etc.).
 * The blink animation briefly swaps to the eyes-closed variant every 4-8 seconds.
 * The bob animation provides a gentle vertical float every ~7 seconds.
 *
 * Architecture note: Replacing all mascot art requires only swapping the 9
 * drawable XML files referenced by [MascotExpression] and [MascotExpression.BLINK_DRAWABLE_RES].
 *
 * @param expression The mascot expression to display. Defaults to [MascotExpression.HAPPY].
 * @param animate Whether to run idle animations (blink + bob). Set false in
 *   contexts that already have their own animations (e.g., voice popup).
 * @param modifier Modifier for sizing and layout. Default size is 180.dp.
 */
@Composable
fun MascotImage(
    expression: MascotExpression = MascotExpression.HAPPY,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Determine the effective drawable: blink swaps eyes closed briefly
    var effectiveDrawableRes by remember { mutableIntStateOf(expression.drawableRes) }

    // The blink loop below is keyed on Unit (so it isn't restarted on every
    // expression change), so its closure would otherwise capture the first
    // composition's `expression`. rememberUpdatedState lets it read the
    // current value — without it, a blink ending mid-expression-change would
    // briefly restore the stale drawable.
    val currentExpression by rememberUpdatedState(expression)

    // Blink animation: periodically swap to eyes-closed for 150ms
    if (animate) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(Random.nextLong(4000, 8000))
                effectiveDrawableRes = MascotExpression.BLINK_DRAWABLE_RES
                delay(150)
                effectiveDrawableRes = currentExpression.drawableRes
            }
        }
    }

    // Update drawable when expression changes (outside blink)
    LaunchedEffect(expression) {
        effectiveDrawableRes = expression.drawableRes
    }

    // Bob animation: gentle vertical movement every ~7 seconds
    // Always compose the transition (Compose rule: no conditional composables),
    // but only apply the offset when animate is true.
    val infiniteTransition = rememberInfiniteTransition(label = "mascot_idle")
    val bobAnimValue by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2000,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(7000),
        ),
        label = "mascot_bob",
    )
    val bobOffset = if (animate) bobAnimValue else 0f

    Crossfade(
        targetState = effectiveDrawableRes,
        label = "mascot_expression",
    ) { drawableRes ->
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = "Marmalade mascot - ${expression.name.lowercase()}",
            modifier = modifier
                .size(180.dp)
                .graphicsLayer {
                    translationY = bobOffset
                },
        )
    }
}
