/**
 * Data Flow: ActivityBubble
 *
 * `ChatMessage.streamingActivity` (set by ChatController) → string is
 * passed to [ActivityBubble] → resolved against [ActivityVocabulary]
 * → renders the selected verb + (optional) subtitle + pulsing dots
 * inside an assistant-shaped, partial-width chat bubble, with an
 * optional italic body text below.
 *
 * The bubble mirrors the assistant message bubble's visual contract:
 * left-aligned via an outer [Row] with `Arrangement.Start`, constrained
 * to the same 85%-of-window max width that
 * [ChatMessageBubble] uses, and clipped with [AssistantBubbleTailShape]
 * so the typing indicator reads as "an incoming message that hasn't
 * filled in yet" rather than a full-width banner. No outline border —
 * the assistant-bubble background already separates it from
 * surrounding chat.
 *
 * Consumed by [ChatTypingIndicatorBubble] — the unified activity surface.
 * Lives at the END of [flattenMessages]'s output for the entire run (which
 * in the reverse-layout LazyColumn renders just under the input bar, below
 * all message bubbles). The activity string tracks the latest
 * streaming-status message's `streamingActivity`; defaults to `"starting"`
 * when no streaming message exists yet.
 *
 * Verb selection is per-activity-transition: `remember(activity)` re-keys
 * on each new activity, picking a fresh index from the activity's verb
 * list. The default picker is `kotlin.random.Random.nextInt`; tests may
 * inject a deterministic [pickIndex] fixture.
 *
 * Cross-activity transitions animate the verb header via [AnimatedContent]
 * (250ms fade + 150ms size animation). Pulsing dots and the body Column
 * sit outside the animated content so only the verb swaps. Reduce-motion
 * (`LocalMotionEnabled` — on Android, `Settings.Global.
 * ANIMATOR_DURATION_SCALE == 0`) skips the crossfade and freezes the
 * pulsing dots at full opacity.
 *
 * The verb itself is rendered without a trailing ellipsis: the pulsing
 * dots already convey "ongoing", so a literal `…` would be redundant.
 */
package app.marmalade.android.ui.chat

import app.marmalade.android.ui.LocalMotionEnabled
import app.marmalade.android.ui.windowWidthDp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.android.ui.theme.marmaladeColors
import kotlin.random.Random

/**
 * Unified "what is the agent doing right now" bubble.
 *
 * @param activity One of `"starting"`, `"thinking"`, `"writing"`,
 *   `"tool:NAME"`, or `null` (falls through to the default verb list).
 * @param bodyText When non-blank, renders below the header in italic
 *   dimmed style to surface live thinking text. When blank (the default,
 *   and what the live [ChatTypingIndicatorBubble] passes), no body Column
 *   is rendered.
 * @param modifier Optional outer modifier (most callers pass none).
 * @param pickIndex Test seam — picks an index into the verb list for the
 *   current activity. Defaults to a random pick. Called once per
 *   activity transition (see [remember]); the verb stays stable for the
 *   duration of an activity.
 */
@Composable
fun ActivityBubble(
    activity: String?,
    bodyText: String = "",
    modifier: Modifier = Modifier,
    pickIndex: ((List<String>) -> Int)? = null,
) {
    // Reduce-motion is a platform reading the host publishes (on Android,
    // `Settings.Global.ANIMATOR_DURATION_SCALE == 0`); see HostBridges.kt.
    val reduceMotion = !LocalMotionEnabled.current

    // Pick an index ONCE per activity transition. `remember(activity)`
    // re-keys on the activity string, giving a fresh roll on each phase
    // change. Within a phase the verb is stable across recompositions.
    val index = remember(activity) {
        val verbs = ActivityVocabulary.verbsFor(activity)
        val picker = pickIndex ?: { list -> Random.nextInt(list.size) }
        picker(verbs)
    }
    val verbResult = ActivityVocabulary.pickVerb(activity, index)

    // Outer Row: left-align the bubble like an incoming assistant message,
    // leaving empty space to the right. Mirrors the user-facing layout of
    // the assistant message bubble (see MessageBubble's Row at
    // `horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start`,
    // with `padding(horizontal = 12.dp)`).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        // Inner Column: assistant-shaped bubble with the same max-width
        // constraint as ChatMessageBubble (85% of the window) so
        // the typing/activity surface visually reads as a normal incoming
        // chat bubble that hasn't received its content yet.
        val maxWidth = windowWidthDp() * 0.85f
        Column(
            modifier = Modifier
                .widthIn(min = 80.dp, max = maxWidth)
                .clip(AssistantBubbleTailShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            ActivityHeader(verbResult = verbResult, activity = activity, reduceMotion = reduceMotion)
            if (bodyText.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                ) {
                    Text(
                        text = bodyText,
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.outline,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

/**
 * Verb (animated across activities) + pulsing dots.
 *
 * The verb [Text] is wrapped in [AnimatedContent] keyed on the activity
 * so cross-activity transitions (e.g. thinking → tool:exec) crossfade
 * smoothly and the row width animates to match the new label. Within a
 * single activity recompositions are no-ops because the key doesn't
 * change.
 *
 * Under reduce-motion the [AnimatedContent] is skipped (verb swaps
 * instantly) and the pulsing dots' alpha is frozen at 1.0f.
 */
@Composable
private fun ActivityHeader(
    verbResult: VerbResult,
    activity: String?,
    reduceMotion: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "activity-pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "activity-dot-alpha",
    )

    // Header sizes to its own content (no fillMaxWidth) so the bubble
    // collapses to a chat-bubble shape rather than stretching to the
    // outer Column's maxWidth — this is what makes ActivityBubble look
    // like an incoming message instead of a banner.
    Row(
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (reduceMotion) {
            // Reduce-motion: swap verb instantly, no crossfade.
            VerbLabel(verbResult = verbResult)
        } else {
            AnimatedContent(
                targetState = activity,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(250)) togetherWith
                        fadeOut(animationSpec = tween(250))).using(
                        SizeTransform { _, _ -> tween(150) },
                    )
                },
                label = "activity-verb-swap",
            ) { _ ->
                // The captured [verbResult] is stable for the lifetime of
                // this target state because verb selection is keyed on
                // `activity`. Each transition gets the freshly picked verb.
                VerbLabel(verbResult = verbResult)
            }
        }

        // Pulsing dots — outside AnimatedContent so they don't crossfade.
        // Under reduce-motion they freeze at full opacity.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { idx ->
                val dotAlpha = if (reduceMotion) {
                    1.0f
                } else {
                    when (idx) {
                        0 -> pulseAlpha * 0.5f
                        1 -> pulseAlpha * 0.75f
                        else -> pulseAlpha
                    }
                }
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .alpha(dotAlpha)
                        .background(MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }
    }
}

/**
 * The verb text plus optional dimmed subtitle (the raw tool name for
 * unknown / non-built-in tools). The verb renders bare ("Pondering",
 * "Bashing", "Warming up") with NO trailing ellipsis: the pulsing
 * dots in [ActivityHeader] already convey "ongoing", so a literal `…`
 * after the word would be redundant clutter.
 */
@Composable
private fun VerbLabel(
    verbResult: VerbResult,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = verbResult.verb,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        val subtitle = verbResult.subtitle
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontSize = 10.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            )
        }
    }
}
