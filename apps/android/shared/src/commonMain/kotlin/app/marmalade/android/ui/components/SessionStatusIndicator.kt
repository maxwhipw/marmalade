package app.marmalade.android.ui.components

import app.marmalade.android.ui.LocalMotionEnabled
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.theme.marmaladeColors
import app.marmalade.android.utils.SessionStatus

/**
 * The status indicator that leads every session and terminal row (design lab
 * `session-status`; the maintainer's sign-off 2026-07-26).
 *
 * The legend and the reasoning behind it live on [SessionStatus] — read that
 * first. This file is only the drawing, and it exists as its own component
 * because four surfaces share it: the drawer's session rows, the drawer's
 * terminal rows, a collapsed section header, and the "LIVE" chip.
 *
 * Three properties are load-bearing:
 *
 * - **The column is [INDICATOR_SIZE] on every row, drawn or not.** Idle draws
 *   nothing; if the space collapsed with it, a title would slide sideways the
 *   moment its session started running, and the list would twitch while you
 *   read it. Reserving the space is what makes "no dot when idle" safe.
 * - **One clock, not one per row.** The phase is derived from the frame time,
 *   which is the same value for every instance in a frame — so five running
 *   rows breathe in phase whenever they mounted. Independent clocks read as
 *   five unrelated things flashing; a shared one reads as the app being alive.
 * - **Motion is honoured as a preference.** With the system animation scale at
 *   zero the indicator draws its resting form instead of animating.
 */
val INDICATOR_SIZE = 14.dp

/** Which running animation ships. The maintainer picked BLOCKS with WAVE as the fallback
 *  (2026-07-26) — if blocks doesn't read on the phone, flip this one constant
 *  and nothing else changes. */
private enum class RunningStyle { BLOCKS, WAVE }
private val RUNNING_STYLE = RunningStyle.BLOCKS

// ── blocks-shuffle (design lab 14; after n3r4zzurr0/svg-spinners blocks-shuffle-3,
//    MIT © Utkarsh Verma — re-drawn for Compose, see CREDITS.md) ───────────────
/** Twelve moves, one block at a time, each move a quarter-turn of the square. */
private const val BLOCK_MOVES = 12
private const val BLOCKS_CYCLE_MS = 2400
private val BLOCK_SIZE = 6.dp
private val BLOCK_TRAVEL = 7.dp
private val BLOCK_CORNER = 1.6.dp

// ── wave (design lab 1 — the fallback) ───────────────────────────────────────
private const val WAVE_CYCLE_MS = 820
private const val WAVE_STAGGER = 0.134f // 110ms of the 820ms cycle
private val WAVE_DOT = 3.dp
private val WAVE_GAP = 2.4.dp

/** Filled states are all the same disc; only the colour changes. */
private val DOT_SIZE = 7.dp

@Composable
fun SessionStatusIndicator(
    status: SessionStatus,
    modifier: Modifier = Modifier,
    /** On the peach main-session card the neutral palette washes out, so the
     *  caller can supply ink that stays legible there. */
    accentTint: Color? = null,
) {
    val colors = MaterialTheme.marmaladeColors
    val running = colors.statusRunning
    val fill = when (status) {
        SessionStatus.RUNNING -> running
        SessionStatus.ERROR -> MaterialTheme.colorScheme.error
        SessionStatus.AWAITING_INPUT -> colors.statusAwaiting
        // Same green as running, deliberately: filled = something happened,
        // moving = something is happening.
        SessionStatus.UNREAD -> accentTint ?: running
        SessionStatus.IDLE -> Color.Transparent
    }

    if (status != SessionStatus.RUNNING) {
        Canvas(modifier = modifier.size(INDICATOR_SIZE)) {
            if (fill == Color.Transparent) return@Canvas
            drawCircle(color = fill, radius = DOT_SIZE.toPx() / 2f)
        }
        return
    }

    // Whether the user has motion switched on — a platform reading the host
    // publishes once (see HostBridges.kt).
    val animated = LocalMotionEnabled.current
    val cycle = if (RUNNING_STYLE == RunningStyle.BLOCKS) BLOCKS_CYCLE_MS else WAVE_CYCLE_MS
    val phase = rememberSharedPhase(cycle, enabled = animated)

    Canvas(modifier = modifier.size(INDICATOR_SIZE)) {
        when (RUNNING_STYLE) {
            RunningStyle.BLOCKS -> drawBlocks(phase.value, running)
            RunningStyle.WAVE -> drawWave(phase.value, running)
        }
    }
}

/**
 * Position in the cycle, 0..1, derived from the animation frame clock.
 *
 * Every caller passing the same [cycleMillis] gets the same value in the same
 * frame regardless of when it was composed, which is what keeps a drawerful of
 * running rows in phase. Held in a [mutableFloatStateOf] read inside the draw
 * lambda so a frame invalidates drawing only — never layout or composition.
 */
@Composable
private fun rememberSharedPhase(cycleMillis: Int, enabled: Boolean): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(cycleMillis, enabled) {
        if (!enabled) {
            phase.floatValue = 0f
            return@LaunchedEffect
        }
        while (true) {
            withInfiniteAnimationFrameMillis { frameMillis ->
                phase.floatValue = (frameMillis % cycleMillis) / cycleMillis.toFloat()
            }
        }
    }
    return phase
}

/**
 * Three rounded squares orbiting a 2×2 grid, one moving at a time.
 *
 * The four corners are indexed clockwise from top-left. Block `b` rests on
 * corner `(4 - b) % 4` and moves during slots `b`, `b+3`, `b+6`, `b+9` of the
 * twelve — which is what keeps exactly one in motion and one corner empty at
 * all times. At phase 0 the three sit in an L, and that is also the resting
 * form drawn when motion is off: three squares, which no other state resembles.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlocks(
    phase: Float,
    color: Color,
) {
    val block = BLOCK_SIZE.toPx()
    val travel = BLOCK_TRAVEL.toPx()
    val corner = CornerRadius(BLOCK_CORNER.toPx())
    // Centre the 13dp grid (one block + one travel) inside the 14dp column.
    val originX = (size.width - (block + travel)) / 2f
    val originY = (size.height - (block + travel)) / 2f
    val slot = phase * BLOCK_MOVES

    for (b in 0 until 3) {
        val start = (4 - b) % 4
        var from = start
        var to = start
        var fraction = 0f
        for (move in 0 until 4) {
            val begins = (b + 3 * move).toFloat()
            when {
                slot >= begins + 1f -> {
                    from = (start + move + 1) % 4
                    to = from
                    fraction = 0f
                }
                slot >= begins -> {
                    from = (start + move) % 4
                    to = (start + move + 1) % 4
                    fraction = slot - begins
                }
            }
        }
        val eased = FastOutSlowInEasing.transform(fraction)
        val x = originX + lerp(cornerX(from), cornerX(to), eased) * travel
        val y = originY + lerp(cornerY(from), cornerY(to), eased) * travel
        drawRoundRect(
            color = color,
            topLeft = Offset(x, y),
            size = Size(block, block),
            cornerRadius = corner,
        )
    }
}

/** Corners clockwise from top-left, as 0/1 multipliers of the travel distance. */
private fun cornerX(index: Int): Float = if (index == 1 || index == 2) 1f else 0f
private fun cornerY(index: Int): Float = if (index == 2 || index == 3) 1f else 0f

private fun lerp(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction

/**
 * The fallback: three dots swelling in sequence. Kept live rather than deleted
 * because the maintainer named it as the swap if blocks doesn't read on the phone.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWave(
    phase: Float,
    color: Color,
) {
    val dot = WAVE_DOT.toPx()
    val gap = WAVE_GAP.toPx()
    val span = dot * 3 + gap * 2
    val left = (size.width - span) / 2f
    for (i in 0 until 3) {
        // Each dot runs the same curve, a third of a beat apart.
        val local = ((phase - i * WAVE_STAGGER) % 1f + 1f) % 1f
        // Swell through the first quarter, then rest — the rest is what stops
        // three dots reading as a single flashing blob.
        val swell = if (local < 0.35f) {
            val t = local / 0.35f
            // up then down over the active window
            if (t < 0.5f) t * 2f else (1f - t) * 2f
        } else {
            0f
        }
        val scale = 0.72f + 0.6f * swell
        val alpha = 0.3f + 0.7f * swell
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = dot / 2f * scale,
            center = Offset(left + dot / 2f + i * (dot + gap), size.height / 2f),
        )
    }
}
