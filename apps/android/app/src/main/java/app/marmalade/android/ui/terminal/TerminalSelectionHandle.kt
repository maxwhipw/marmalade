// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/ui/terminal/TerminalSelection.kt
// (`TerminalSelectionHandle`, and the geometry half of `TerminalSelectionState`).
//
// Changed: the state carries geometry only. Upstream it also carries the
// selected *text*, which it obtains by calling into the native terminal from the
// UI thread — that call moved to [GhosttyTerminalEngine.selectionText]. The
// handle's *drawing* is no longer chuchu's ball-on-a-stem either — the Material
// teardrop and its placement below the line are ours; what remains ported is the
// pixel geometry and the accumulate-then-convert drag maths.
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.marmalade.android.terminal.TerminalSelection
import app.marmalade.android.terminal.TerminalSnapshot
import kotlin.math.max
import kotlin.math.roundToInt

/** Where a selection sits on the canvas, in pixels. Text is not part of it. */
data class TerminalSelectionGeometry(
    /** Top-left px of the first selected cell. */
    val startOffset: Offset,
    /** Bottom-right px of the last selected cell. */
    val endOffset: Offset,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val cols: Int,
)

/** Null when the selection does not land on any cell of [snapshot]. */
fun selectionGeometry(
    snapshot: TerminalSnapshot,
    selection: TerminalSelection,
    cellWidth: Float,
    cellHeight: Float,
): TerminalSelectionGeometry? {
    val cols = max(snapshot.cols, 1)
    val range = selection.normalized(snapshot.codepoints.size) ?: return null
    val startCol = range.first % cols
    val startRow = range.first / cols
    val endCol = range.last % cols
    val endRow = range.last / cols
    return TerminalSelectionGeometry(
        startOffset = Offset(startCol * cellWidth, startRow * cellHeight),
        endOffset = Offset((endCol + 1) * cellWidth, (endRow + 1) * cellHeight),
        cellWidthPx = cellWidth,
        cellHeightPx = cellHeight,
        cols = cols,
    )
}

/** Which end of the selection a handle marks — and so which way it leans. */
enum class TerminalHandleSide { Start, End }

/**
 * The handle colour: the theme's primary, unless it is too dark to see against
 * the terminal — which is fixed dark whatever the app theme is.
 *
 * Material You hands us a *light-scheme* primary on a light device, a mid-dark
 * saturated colour that all but disappears on `#1C1917`, so the handles fall
 * back to the terminal's own accent there. Every dark scheme (dynamic
 * included) clears the threshold and keeps its own colour.
 */
fun terminalHandleColor(primary: Color, fallback: Color): Color =
    if (primary.luminance() < MIN_HANDLE_LUMINANCE) fallback else primary

/** Relative luminance a handle needs to read on the dark terminal. */
private const val MIN_HANDLE_LUMINANCE = 0.2f

/**
 * One draggable Material selection handle: a circle with a single square
 * corner, leaning towards the text it marks — the same teardrop Android's own
 * text views draw, rather than the ball-on-a-stem this file used to.
 *
 * Two things make it feel native, and both are placement rather than paint:
 *
 *  - **it hangs below the line.** The square corner is pinned to the *bottom*
 *    edge of the selection's first/last cell and the body drops away from it,
 *    so the finger rests a radius clear of the text it is aiming at instead of
 *    covering it.
 *  - **the touch target is [HANDLE_TOUCH_SIZE] (48 dp), whatever the drop
 *    measures.** The drop is centred inside that box, so the grabbable area
 *    stays a full Material minimum target while the visual stays small.
 *
 * @param tipX,tipY where the square corner goes, in canvas pixels.
 * @param startCellProvider read at drag start, not captured: the selection can
 *   move under the handle (a viewport scroll remaps it) between gestures.
 * @param onDragActiveChange true while the finger is down on this handle. The
 *   screen uses it to suppress the floating menu and to show the magnifier —
 *   both of which are per-gesture, not per-frame, decisions.
 */
@Composable
fun TerminalSelectionHandle(
    tipX: Float,
    tipY: Float,
    side: TerminalHandleSide,
    color: Color,
    cellWidthPx: Float,
    cellHeightPx: Float,
    cols: Int,
    startCellProvider: () -> Int,
    onDragToCell: (newCell: Int) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val touchSizePx = with(density) { HANDLE_TOUCH_SIZE.toPx() }
    val radiusPx = with(density) { HANDLE_RADIUS.toPx() }
    // The drop is centred in the touch box; its square corner is therefore
    // half a box minus a radius in from the top, and on whichever side leans
    // towards the selection.
    val inset = touchSizePx / 2f - radiusPx
    val cornerInBoxX = if (side == TerminalHandleSide.Start) inset + radiusPx * 2f else inset
    val originX = (tipX - cornerInBoxX).roundToInt()
    val originY = (tipY - inset).roundToInt()

    Box(
        modifier = Modifier
            .offset { IntOffset(originX, originY) }
            .size(HANDLE_TOUCH_SIZE)
            .pointerInput(cellWidthPx, cellHeightPx, cols) {
                var accumX = 0f
                var accumY = 0f
                var startCell = 0
                detectDragGestures(
                    onDragStart = {
                        accumX = 0f
                        accumY = 0f
                        startCell = startCellProvider()
                        onDragActiveChange(true)
                    },
                    onDragEnd = { onDragActiveChange(false) },
                    onDragCancel = { onDragActiveChange(false) },
                    onDrag = { _, dragAmount ->
                        if (cellWidthPx <= 0f || cellHeightPx <= 0f || cols <= 0) {
                            return@detectDragGestures
                        }
                        // Accumulate in pixels and convert once: rounding each
                        // delta would lose every sub-cell movement.
                        accumX += dragAmount.x
                        accumY += dragAmount.y
                        val dCol = (accumX / cellWidthPx).roundToInt()
                        val dRow = (accumY / cellHeightPx).roundToInt()
                        onDragToCell((startCell + dRow * cols + dCol).coerceAtLeast(0))
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = color, radius = radiusPx, center = centre)
            // The one square corner: a quadrant filled back out to the corner
            // the circle rounds off, which is what points at the selection.
            drawRect(
                color = color,
                topLeft = Offset(
                    x = if (side == TerminalHandleSide.Start) centre.x else centre.x - radiusPx,
                    y = centre.y - radiusPx,
                ),
                size = Size(radiusPx, radiusPx),
            )
        }
    }
}

/** Material's minimum touch target, and deliberately larger than the drop. */
private val HANDLE_TOUCH_SIZE = 48.dp

/** Half the drop's width — Android's handles are ~22 dp across. */
private val HANDLE_RADIUS = 11.dp
