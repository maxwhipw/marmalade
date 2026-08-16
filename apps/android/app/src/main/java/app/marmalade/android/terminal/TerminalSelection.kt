// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/ui/terminal/TerminalSelection.kt
// (the model, `wordAt` and `extractSelectionText`) plus `cellAt` and
// `remapSelectionForViewportScroll` from .../TerminalCanvas.kt.
//
// Changed: chuchu's `buildSelectionState` is NOT here. It reaches into the
// native terminal from the UI thread through a raw handle — a real data race
// against the engine thread — so text extraction moved behind
// [GhosttyTerminalEngine.selectionText] and only the pixel geometry stayed on
// the UI side (`ui/terminal/TerminalSelectionHandle.kt`). The remap returns a
// new selection instead of firing callbacks, which is what makes it testable.
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.terminal

import kotlin.math.floor
import kotlin.math.max

/**
 * A touch selection over the *visible* grid, as two cell indices into the
 * current snapshot.
 *
 * Anchor and focus rather than start and end: the anchor is where the gesture
 * began and the focus is where the finger is, so dragging backwards past the
 * start is just `focus < anchor` rather than a special case. Order is imposed
 * only at the point of use, by [normalized].
 */
data class TerminalSelection(
    val anchorIndex: Int,
    val focusIndex: Int,
) {
    /** The ordered, in-bounds cell range, or null when the grid is empty. */
    fun normalized(cellCount: Int): IntRange? {
        if (cellCount <= 0) return null
        val start = minOf(anchorIndex, focusIndex).coerceIn(0, cellCount - 1)
        val end = maxOf(anchorIndex, focusIndex).coerceIn(0, cellCount - 1)
        return start..end
    }

    /**
     * Move whichever endpoint currently *starts* the selection.
     *
     * @param updateAnchor true when the anchor is the earlier of the two —
     *   dragging the start handle must not silently swap the roles.
     */
    fun withStart(newStartCell: Int, updateAnchor: Boolean): TerminalSelection =
        if (updateAnchor) copy(anchorIndex = newStartCell) else copy(focusIndex = newStartCell)

    /** Move whichever endpoint currently ends the selection. */
    fun withEnd(newEndCell: Int, updateAnchor: Boolean): TerminalSelection =
        if (updateAnchor) copy(anchorIndex = newEndCell) else copy(focusIndex = newEndCell)
}

/** Pure selection maths over a [TerminalSnapshot]. No Android, no native calls. */
object TerminalSelections {

    /** The cell under a canvas-local touch, clamped to the grid. */
    fun cellAt(
        snapshot: TerminalSnapshot,
        x: Float,
        y: Float,
        cellWidth: Float,
        cellHeight: Float,
    ): Int? {
        if (snapshot.cols <= 0 || snapshot.rows <= 0) return null
        if (cellWidth <= 0f || cellHeight <= 0f) return null
        val col = floor(x / cellWidth).toInt().coerceIn(0, snapshot.cols - 1)
        val row = floor(y / cellHeight).toInt().coerceIn(0, snapshot.rows - 1)
        return row * snapshot.cols + col
    }

    /**
     * The run of non-blank cells around [cellIndex], for double-tap select.
     * Null on a blank cell — there is no word to take. Bounded to one row: a
     * word does not continue across a wrap, as far as the client can tell.
     */
    fun wordAt(snapshot: TerminalSnapshot, cellIndex: Int): IntRange? {
        if (snapshot.cols <= 0 || cellIndex !in snapshot.codepoints.indices) return null
        val rowStart = (cellIndex / snapshot.cols) * snapshot.cols
        val rowEnd = rowStart + snapshot.cols - 1
        if (isBlank(snapshot, cellIndex)) return null

        var start = cellIndex
        while (start > rowStart && !isBlank(snapshot, start - 1)) start--
        var end = cellIndex
        while (end < rowEnd && !isBlank(snapshot, end + 1)) end++
        return start..end
    }

    /**
     * Viewport cell indices → *screen* cell indices, which is what the native
     * formatter wants: the screen includes scrollback, so the offset is however
     * many rows the viewport is scrolled back by.
     */
    fun screenRange(snapshot: TerminalSnapshot, range: IntRange): IntRange {
        val offset = snapshot.viewportScrollY * max(snapshot.cols, 1)
        val start = (range.first + offset).coerceAtLeast(0)
        val end = (range.last + offset).coerceAtLeast(start)
        return start..end
    }

    /**
     * Keep a selection pinned to its text when the viewport scrolls under it.
     *
     * Indices are viewport-relative, so a scroll silently renames every cell.
     * Without this a selection made in scrollback slides up the screen as
     * output arrives, and the copy comes out as whatever text moved into those
     * coordinates.
     *
     * @param anchorOnly during an edge auto-scroll the focus is the finger and
     *   must stay put; only the anchor is remapped, which is what grows the
     *   selection as the viewport moves.
     */
    fun remapForViewportScroll(
        selection: TerminalSelection,
        cols: Int,
        baselineScrollY: Int,
        currentScrollY: Int,
        anchorOnly: Boolean,
    ): TerminalSelection {
        if (baselineScrollY == currentScrollY) return selection
        val deltaCells = (baselineScrollY - currentScrollY) * max(cols, 1)
        return if (anchorOnly) {
            selection.copy(anchorIndex = selection.anchorIndex + deltaCells)
        } else {
            selection.copy(
                anchorIndex = selection.anchorIndex + deltaCells,
                focusIndex = selection.focusIndex + deltaCells,
            )
        }
    }

    /**
     * A cell with nothing in it: unset (codepoint 0) or a real space. The
     * trailing half of a wide character is stored as a space too, but it is
     * *not* blank — [TerminalSnapshot.CELL_FLAG_SPACER] marks it as the
     * continuation of the glyph beside it.
     */
    private fun isBlank(snapshot: TerminalSnapshot, index: Int): Boolean {
        val cp = snapshot.codepoints[index]
        return cp == 0 || (cp == 32 && !TerminalRuns.isSpacerContinuation(snapshot, index))
    }

    /**
     * Read a selection straight off the snapshot the UI is showing.
     *
     * The last-resort path: [GhosttyTerminalEngine.selectionText] prefers the
     * native formatter, which knows about scrollback and wrapped lines. This
     * one only ever sees the visible grid, but it never fails and it needs no
     * native call, which makes it the fallback and the only part unit-testable
     * offline.
     *
     * Rules, all of which are the difference between usable and useless copy:
     *  - a spacer contributes nothing (a wide glyph is emitted once, by the
     *    cell that owns it — otherwise every CJK character doubles);
     *  - trailing blanks are trimmed per row (a terminal row is blank-padded to
     *    its full width, so untrimmed output is mostly spaces);
     *  - rows join with `\n`, including rows that trim to nothing.
     */
    fun extractText(snapshot: TerminalSnapshot, range: IntRange): String? {
        if (snapshot.cols <= 0 || snapshot.codepoints.isEmpty()) return null

        val last = snapshot.codepoints.lastIndex
        val from = range.first.coerceIn(0, last)
        val to = range.last.coerceIn(0, last)
        val startRow = from / snapshot.cols
        val endRow = to / snapshot.cols
        val builder = StringBuilder(to - from + 1 + (endRow - startRow))

        for (row in startRow..endRow) {
            val rowStart = row * snapshot.cols
            val rowFrom = maxOf(from, rowStart)
            val rowTo = minOf(to, rowStart + snapshot.cols - 1)
            var lastContent = rowTo
            while (lastContent >= rowFrom && isBlank(snapshot, lastContent)) lastContent--
            for (index in rowFrom..lastContent) {
                val cp = snapshot.codepoints[index]
                when {
                    isBlank(snapshot, index) -> builder.append(' ')
                    cp == 32 -> Unit // spacer: the wide glyph beside it owns this cell
                    else -> builder.append(TerminalRuns.glyphAt(snapshot, index))
                }
            }
            if (row != endRow) builder.append('\n')
        }
        return builder.toString()
    }
}
