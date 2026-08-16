package app.marmalade.android.ui.terminal

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things that can silently go wrong about the floating text-action
 * menu: it is anchored on the wrong rectangle, or it is on screen at the wrong
 * moment. The ActionMode itself is platform glue no JVM test can drive; these
 * are the parts that decide where and when it appears.
 */
class TerminalSelectionToolbarTest {

    private fun geometry(
        startCol: Int,
        startRow: Int,
        endCol: Int,
        endRow: Int,
        cellW: Float = 10f,
        cellH: Float = 20f,
        cols: Int = 80,
    ) = TerminalSelectionGeometry(
        startOffset = Offset(startCol * cellW, startRow * cellH),
        endOffset = Offset((endCol + 1) * cellW, (endRow + 1) * cellH),
        cellWidthPx = cellW,
        cellHeightPx = cellH,
        cols = cols,
    )

    @Test
    fun `a one-row selection anchors on its own span`() {
        val rect = TerminalSelectionToolbar.contentRect(
            geometry(startCol = 3, startRow = 2, endCol = 7, endRow = 2),
            canvasOriginInRoot = Offset.Zero,
        )
        assertEquals(30f, rect.left, 0.01f)
        assertEquals(40f, rect.top, 0.01f)
        assertEquals(80f, rect.right, 0.01f)
        assertEquals(60f, rect.bottom, 0.01f)
    }

    @Test
    fun `a multi-row selection anchors on the full grid width`() {
        // The last row ends at column 2, but the rows between the endpoints
        // cover every column — anchoring on the endpoints would float the menu
        // over the middle of the text instead of beside it.
        val rect = TerminalSelectionToolbar.contentRect(
            geometry(startCol = 40, startRow = 1, endCol = 2, endRow = 4, cols = 80),
            canvasOriginInRoot = Offset.Zero,
        )
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(800f, rect.right, 0.01f)
        assertEquals(20f, rect.top, 0.01f)
        assertEquals(100f, rect.bottom, 0.01f)
    }

    @Test
    fun `the rect is translated out of canvas space into root space`() {
        // The toolbar anchors in the composition root; the geometry is
        // canvas-local. Forgetting this puts the menu under the app bar.
        val rect = TerminalSelectionToolbar.contentRect(
            geometry(startCol = 1, startRow = 0, endCol = 4, endRow = 0),
            canvasOriginInRoot = Offset(15f, 200f),
        )
        assertEquals(25f, rect.left, 0.01f)
        assertEquals(200f, rect.top, 0.01f)
        assertEquals(65f, rect.right, 0.01f)
        assertEquals(220f, rect.bottom, 0.01f)
    }

    @Test
    fun `the menu shows only for a selection with text and no finger down`() {
        assertTrue(
            TerminalSelectionToolbar.shouldShow(
                hasSelection = true,
                hasText = true,
                dragging = false,
            ),
        )
        // Nothing selected.
        assertFalse(
            TerminalSelectionToolbar.shouldShow(
                hasSelection = false,
                hasText = true,
                dragging = false,
            ),
        )
        // Selected blank cells: a Copy that copies nothing.
        assertFalse(
            TerminalSelectionToolbar.shouldShow(
                hasSelection = true,
                hasText = false,
                dragging = false,
            ),
        )
        // Mid-drag: the menu must not chase the handle.
        assertFalse(
            TerminalSelectionToolbar.shouldShow(
                hasSelection = true,
                hasText = true,
                dragging = true,
            ),
        )
    }
}
