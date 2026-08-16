package app.marmalade.android.ui.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Where the platform floating text-action menu (`LocalTextToolbar`) goes for a
 * terminal selection, and when it may be on screen at all.
 *
 * Pure on purpose: the toolbar itself is an Android `ActionMode` that no JVM
 * test can drive, but the two things that actually go wrong — anchoring the
 * menu on the wrong rectangle, and showing it at the wrong moment — are
 * arithmetic and a truth table, so they live here where they can be tested.
 */
object TerminalSelectionToolbar {

    /**
     * The selection's bounding box in **Compose-root** coordinates, which is
     * the space `TextToolbar.showMenu` anchors in (the rect is handed to the
     * floating ActionMode as a content rect on the compose host view).
     *
     * A selection spanning more than one row is anchored on the full grid
     * width rather than on the first and last cells: the text between them
     * covers every column, and the menu should not float over the middle of a
     * paragraph because the last row happens to be short. This is what
     * Android's own multi-line selection does.
     *
     * @param canvasOriginInRoot where the terminal canvas sits in the root —
     *   the geometry is canvas-local, the toolbar is not.
     */
    fun contentRect(
        geometry: TerminalSelectionGeometry,
        canvasOriginInRoot: Offset,
    ): Rect {
        val spansRows =
            geometry.endOffset.y - geometry.startOffset.y > geometry.cellHeightPx * ROW_SPAN_FACTOR
        val left: Float
        val right: Float
        if (spansRows) {
            left = 0f
            right = geometry.cols * geometry.cellWidthPx
        } else {
            left = minOf(geometry.startOffset.x, geometry.endOffset.x)
            right = maxOf(geometry.startOffset.x, geometry.endOffset.x)
        }
        return Rect(
            left = left + canvasOriginInRoot.x,
            top = geometry.startOffset.y + canvasOriginInRoot.y,
            right = right + canvasOriginInRoot.x,
            bottom = geometry.endOffset.y + canvasOriginInRoot.y,
        )
    }

    /**
     * Whether the floating menu belongs on screen right now.
     *
     * - **no selection** → nothing to act on;
     * - **no text** → the selection covers only blank cells, so a Copy that
     *   copies nothing would be a lie (the extra-keys Copy button greys out on
     *   the same condition);
     * - **mid-drag** → a menu that follows the handle around is exactly what
     *   Android's text views avoid; it reappears when the finger lifts.
     */
    fun shouldShow(hasSelection: Boolean, hasText: Boolean, dragging: Boolean): Boolean =
        hasSelection && hasText && !dragging

    /** Half a cell of slack, so a one-row selection is never read as two. */
    private const val ROW_SPAN_FACTOR = 1.5f
}
