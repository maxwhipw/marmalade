package app.marmalade.android.ui.voice

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.marmalade.android.service.AssistantState
import app.marmalade.android.ui.theme.isMarmaladeThemeDark
import kotlin.math.PI
import kotlin.math.sin

// =============================================================================
// Morph Pill — the voice popup's single mic/stop control
// =============================================================================
//
// Ported from the approved HTML mockup (internal design mockup
// "Morph Pill v2", maintainer 2026-07-04).
// One shape-shifting control instead of a mic↔stop FAB swap:
//
//   idle       56dp circle, mic glyph, accent ground
//   listening  224dp pill, slow traveling wave + mic square at the RIGHT
//   thinking   224dp pill, kneading dots + "Thinking" on a soft cream ground
//   speaking   224dp pill, live bars + stop square at the RIGHT, toast ground
//
// Height is 56dp — deliberately slimmer than the mockup's 64px: next to the
// 84dp mascot the full-height pill read as chunky (maintainer 2026-07-04, ~⅔ of
// the mascot height).
//
// The action square (mic while listening, stop while speaking) is always on
// the right so the touch target never moves. All grounds are mode-aware per
// the design scheme: no bright orange in dark mode.
//
// All motion hangs off ONE rememberInfiniteTransition clock with
// deterministic pseudo-noise — no per-frame state writes, which keeps the
// overlay cheap and the Robolectric test clock deterministic.

private const val TAP_DEBOUNCE_MS = 600L

/** Pill height — ~⅔ of the 84dp mascot so the control doesn't read chunky. */
private val PILL_HEIGHT = 56.dp

private enum class PillVisual { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * PROCESSING is overloaded upstream: it covers both "STT engine warming up"
 * (entered from IDLE — the popup auto-starts listening on open, and model
 * load takes seconds) and "transcript submitted" (entered from LISTENING).
 * Only the latter should read as Thinking; the warm-up showed a bogus
 * "Thinking" pill for ~5s on every popup open (maintainer, on-device 2026-07-04).
 */
private fun pillVisualFor(state: AssistantState, previous: AssistantState): PillVisual = when (state) {
    AssistantState.IDLE, AssistantState.ERROR -> PillVisual.IDLE
    AssistantState.LISTENING -> PillVisual.LISTENING
    AssistantState.PROCESSING ->
        if (previous == AssistantState.LISTENING) PillVisual.THINKING else PillVisual.IDLE
    AssistantState.THINKING -> PillVisual.THINKING
    AssistantState.PREPARING_SPEECH, AssistantState.SPEAKING -> PillVisual.SPEAKING
}

private data class PillColors(
    val accentBg: Color, val accentFg: Color,
    val softBg: Color, val softFg: Color,
    val toastBg: Color, val toastFg: Color,
)

@Composable
private fun rememberPillColors(): PillColors = if (isMarmaladeThemeDark()) {
    PillColors(
        accentBg = Color(0xFF422006), accentFg = Color(0xFFFED7AA),   // rich brown / toast
        softBg = Color(0xFFFEF3C7), softFg = Color(0xFF1C1917),       // soft pastel
        toastBg = Color(0xFFFED7AA), toastFg = Color(0xFF7C2D12),     // toast
    )
} else {
    PillColors(
        accentBg = Color(0xFFF97316), accentFg = Color.White,          // orange accent slot
        softBg = Color(0xFFFFEDD5), softFg = Color(0xFF1C1917),        // cream
        toastBg = Color(0xFFFED7AA), toastFg = Color(0xFF7C2D12),
    )
}

/**
 * The morphing mic/stop pill. [onClick] is the single action — the session
 * routes it by state exactly like the old mic/stop FAB pair did.
 *
 * Test tags: the pill root always carries [VoicePopupTags.MIC_BUTTON]; the
 * stop square carries [VoicePopupTags.STOP_BUTTON] while visible.
 */
@Composable
fun MorphPillButton(
    state: AssistantState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Previous state, one step behind: during the composition where `state`
    // just changed this still holds the old value (the effect runs after).
    var previous by remember { mutableStateOf(state) }
    LaunchedEffect(state) { previous = state }
    val visual = pillVisualFor(state, previous)
    val colors = rememberPillColors()

    // Double-tap guard: a second tap right after submit lands in THINKING
    // and aborts the fresh run (observed on-device 2026-07-04). Absorb taps
    // that arrive within the morph animation window.
    // Seeded one window in the past so the very first tap always passes —
    // uptime starts near zero under Robolectric (and right after boot).
    var lastTapUptimeMs by remember { mutableLongStateOf(-TAP_DEBOUNCE_MS) }
    val guardedClick = {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapUptimeMs >= TAP_DEBOUNCE_MS) {
            lastTapUptimeMs = now
            onClick()
        }
    }

    val width by animateDpAsState(
        targetValue = if (visual == PillVisual.IDLE) PILL_HEIGHT else 224.dp,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "pill_width",
    )
    val bg by animateColorAsState(
        targetValue = when (visual) {
            PillVisual.IDLE, PillVisual.LISTENING -> colors.accentBg
            PillVisual.THINKING -> colors.softBg
            PillVisual.SPEAKING -> colors.toastBg
        },
        animationSpec = tween(350),
        label = "pill_bg",
    )
    val fg = when (visual) {
        PillVisual.IDLE, PillVisual.LISTENING -> colors.accentFg
        PillVisual.THINKING -> colors.softFg
        PillVisual.SPEAKING -> colors.toastFg
    }

    // One shared clock; 60s linear sweep, everything derives from it.
    val clock = rememberInfiniteTransition(label = "pill_clock")
    val tMs by clock.animateFloat(
        initialValue = 0f,
        targetValue = 60_000f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)),
        label = "pill_t",
    )

    Surface(
        onClick = guardedClick,
        shape = RoundedCornerShape(PILL_HEIGHT / 2),
        color = bg,
        contentColor = fg,
        shadowElevation = 4.dp,
        modifier = modifier
            .width(width)
            .height(PILL_HEIGHT)
            .testTag(VoicePopupTags.MIC_BUTTON),
    ) {
        Crossfade(targetState = visual, label = "pill_content") { v ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (v) {
                    PillVisual.IDLE -> Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Start listening",
                        modifier = Modifier.size(32.dp),
                        tint = fg,
                    )
                    PillVisual.LISTENING -> ListeningContent(tMs = { tMs }, fg = fg, onClick = guardedClick)
                    PillVisual.THINKING -> ThinkingContent(tMs = { tMs }, fg = fg)
                    PillVisual.SPEAKING -> SpeakingContent(tMs = { tMs }, fg = fg, onClick = guardedClick)
                }
            }
        }
    }
}

// ── State interiors ─────────────────────────────────────────────────────────

@Composable
private fun ListeningContent(tMs: () -> Float, fg: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WaveCanvas(tMs = tMs, fg = fg, modifier = Modifier.weight(1f).height(32.dp).padding(start = 6.dp, end = 10.dp))
        ActionSquare(
            icon = Icons.Default.Mic,
            contentDescription = "Stop listening",
            fg = fg,
            onClick = onClick,
        )
    }
}

@Composable
private fun ThinkingContent(tMs: () -> Float, fg: Color) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        KneadingDots(tMs = tMs, fg = fg)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Thinking",
            style = MaterialTheme.typography.labelLarge,
            color = fg.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun SpeakingContent(tMs: () -> Float, fg: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarsCanvas(tMs = tMs, fg = fg, modifier = Modifier.weight(1f).height(32.dp).padding(start = 6.dp, end = 10.dp))
        ActionSquare(
            icon = Icons.Filled.Stop,
            contentDescription = "Stop",
            fg = fg,
            onClick = onClick,
            testTag = VoicePopupTags.STOP_BUTTON,
        )
    }
}

/** The rounded action square pinned at the pill's right end. */
@Composable
private fun ActionSquare(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    fg: Color,
    onClick: () -> Unit,
    testTag: String? = null,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fg.copy(alpha = 0.16f))
            .let { if (testTag != null) it.testTag(testTag) else it }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = fg,
        )
    }
}

/** Slow traveling wave — the listening texture. */
@Composable
private fun WaveCanvas(tMs: () -> Float, fg: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val t = tMs()
        val amp = fakeAmp(t)
        val mid = size.height / 2f
        val px = 1.dp.toPx()
        val path = Path()
        var x = 0f
        while (x <= size.width) {
            val xDp = x / px
            val env = sin(PI * x / size.width).toFloat()
            val y = mid + (
                sin(xDp * .09f - t * .0035f) * (2f + 12f * amp) +
                    sin(xDp * .026f - t * .0014f) * 2.5f
                ) * env * px
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 2f * px
        }
        drawPath(path, color = fg, style = Stroke(width = 2.5f * px, cap = StrokeCap.Round))
    }
}

/** Live level bars, newest brightest — the speaking texture. */
@Composable
private fun BarsCanvas(tMs: () -> Float, fg: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val t = tMs()
        val amp = fakeAmp(t)
        val n = 22
        val bw = size.width / n
        val px = 1.dp.toPx()
        for (i in 0 until n) {
            // Each bar replays the level a little further in the past so the
            // strip reads as a scrolling history without storing any.
            val level = (amp * (.3f + .7f * barNoise(i, t - (n - i) * 40f))).coerceIn(0f, 1f)
            val h = (3f * px + level * (size.height - 4f * px)).coerceAtMost(size.height)
            drawRoundRect(
                color = fg.copy(alpha = .45f + .55f * (i + 1) / n),
                topLeft = androidx.compose.ui.geometry.Offset(i * bw + bw * .28f, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(bw * .44f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * .22f),
            )
        }
    }
}

/** Three jam dots swelling in a traveling wave — the thinking texture. */
@Composable
private fun KneadingDots(tMs: () -> Float, fg: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .graphicsLayer {
                        val ph = sin(tMs() * .005f - i * .9f)
                        translationY = -ph * 4.dp.toPx()
                        val s = 1f + ph * .28f
                        scaleX = s
                        scaleY = s
                        alpha = .55f + .45f * ((ph + 1f) / 2f)
                    }
                    .background(fg, androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}
