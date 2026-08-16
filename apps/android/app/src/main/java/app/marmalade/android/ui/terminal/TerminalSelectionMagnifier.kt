package app.marmalade.android.ui.terminal

import androidx.compose.ui.geometry.Offset
import app.marmalade.android.terminal.TerminalSelection

/**
 * Which endpoint of the selection the finger currently owns.
 *
 * [Focus] is the long-press drag started on the canvas itself: there the moving
 * end is the selection's *focus* by construction, whichever side of the anchor
 * it has wandered to.
 */
enum class TerminalDragTarget { None, Start, End, Focus }

/**
 * Where the platform magnifier looks while a selection endpoint is dragged.
 *
 * The magnifier is centred on the **cell** the endpoint has landed on rather
 * than on the raw touch point, so it lines up with the highlight the user is
 * aiming — and it costs nothing extra, because that cell is already the state
 * the drag writes.
 */
object TerminalSelectionMagnifier {

    /** The cell under the finger, or null when no drag is in progress. */
    fun draggedCell(selection: TerminalSelection?, target: TerminalDragTarget): Int? {
        if (selection == null) return null
        return when (target) {
            TerminalDragTarget.None -> null
            TerminalDragTarget.Start -> minOf(selection.anchorIndex, selection.focusIndex)
            TerminalDragTarget.End -> maxOf(selection.anchorIndex, selection.focusIndex)
            TerminalDragTarget.Focus -> selection.focusIndex
        }
    }

    /**
     * The centre of [cellIndex] in canvas pixels, or [Offset.Unspecified] —
     * which is `Modifier.magnifier`'s own "nothing to magnify, hide" value, so
     * an absent drag needs no separate flag.
     */
    fun sourceCenter(
        cellIndex: Int?,
        cols: Int,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Offset {
        if (cellIndex == null || cellIndex < 0) return Offset.Unspecified
        if (cols <= 0 || cellWidthPx <= 0f || cellHeightPx <= 0f) return Offset.Unspecified
        val col = cellIndex % cols
        val row = cellIndex / cols
        return Offset((col + 0.5f) * cellWidthPx, (row + 0.5f) * cellHeightPx)
    }
}
