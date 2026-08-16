package app.marmalade.android.ui.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import app.marmalade.android.terminal.TerminalSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the platform magnifier looks. The magnifier itself is a platform view;
 * what is ours — and what puts it over the wrong character — is picking the
 * dragged endpoint and turning that cell into a canvas point.
 */
class TerminalSelectionMagnifierTest {

    @Test
    fun `the start handle drags the lower index whichever way the selection runs`() {
        // Backwards selection: the anchor is the LATER cell, so "start" is the
        // focus. Getting this wrong magnifies the stationary end of the drag.
        val backwards = TerminalSelection(anchorIndex = 90, focusIndex = 12)
        assertEquals(
            12,
            TerminalSelectionMagnifier.draggedCell(backwards, TerminalDragTarget.Start),
        )
        assertEquals(
            90,
            TerminalSelectionMagnifier.draggedCell(backwards, TerminalDragTarget.End),
        )
        val forwards = TerminalSelection(anchorIndex = 12, focusIndex = 90)
        assertEquals(
            12,
            TerminalSelectionMagnifier.draggedCell(forwards, TerminalDragTarget.Start),
        )
        assertEquals(
            90,
            TerminalSelectionMagnifier.draggedCell(forwards, TerminalDragTarget.End),
        )
    }

    @Test
    fun `a canvas long-press drag follows the focus, which is the finger`() {
        val selection = TerminalSelection(anchorIndex = 12, focusIndex = 90)
        assertEquals(
            90,
            TerminalSelectionMagnifier.draggedCell(selection, TerminalDragTarget.Focus),
        )
    }

    @Test
    fun `no drag and no selection magnify nothing`() {
        val selection = TerminalSelection(anchorIndex = 1, focusIndex = 2)
        assertNull(TerminalSelectionMagnifier.draggedCell(selection, TerminalDragTarget.None))
        assertNull(TerminalSelectionMagnifier.draggedCell(null, TerminalDragTarget.Start))
    }

    @Test
    fun `a cell maps to the centre of its cell rectangle`() {
        // Cell 83 on an 80-column grid is row 1, column 3.
        assertEquals(
            Offset(3.5f * 10f, 1.5f * 20f),
            TerminalSelectionMagnifier.sourceCenter(83, cols = 80, cellWidthPx = 10f, cellHeightPx = 20f),
        )
    }

    @Test
    fun `nothing to magnify reads as Unspecified, the modifier's own off switch`() {
        assertTrue(
            TerminalSelectionMagnifier
                .sourceCenter(null, cols = 80, cellWidthPx = 10f, cellHeightPx = 20f)
                .isUnspecified,
        )
        // Before the first frame the geometry is zero — magnifying then would
        // divide the canvas by a cell that does not exist yet.
        assertTrue(
            TerminalSelectionMagnifier
                .sourceCenter(4, cols = 0, cellWidthPx = 0f, cellHeightPx = 0f)
                .isUnspecified,
        )
    }
}
