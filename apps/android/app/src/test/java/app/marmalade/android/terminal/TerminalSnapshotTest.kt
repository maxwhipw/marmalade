package app.marmalade.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Digital twin for the libghostty snapshot wire format.
 *
 * The producer is `chuchu_build_text_snapshot` in
 * `native/src/bridge/chuchu_snapshot.zig`; these buffers are hand-built to
 * that layout so the decode can be tested without an emulator, an .so, or a
 * single android.* class. If this file ever needs Robolectric, the split in
 * TerminalSnapshot.kt has regressed.
 *
 * Layout (all little-endian):
 *   header: 14 x i32 = 56 bytes
 *     cols, rows, cursorX, cursorY, cursorVisible,
 *     bgR, bgG, bgB, fgR, fgG, fgB,
 *     extrasOffset, viewportScrollY, appHandlesSelectionDrag
 *   grid: cols*rows cells of 11 bytes — i32 codepoint, u8 fgR/fgG/fgB,
 *         u8 bgR/bgG/bgB, u8 flags
 *   extras (at extrasOffset, omitted when 0): u32 recordCount, then per
 *         record u32 cellIndex, u32 count, u32[count] codepoints
 */
class TerminalSnapshotTest {

    private class Cell(
        val cp: Int,
        val flags: Int = 0,
        val fg: Triple<Int, Int, Int> = Triple(200, 200, 200),
        val bg: Triple<Int, Int, Int> = Triple(0, 0, 0),
    )

    /** Builds a snapshot buffer exactly as the Zig writer would. */
    private fun buildSnapshot(
        cols: Int,
        rows: Int,
        cells: List<Cell>,
        cursorX: Int = 0,
        cursorY: Int = 0,
        cursorVisible: Boolean = true,
        viewportScrollY: Int = 0,
        appHandlesSelectionDrag: Boolean = false,
        extras: List<Pair<Int, IntArray>> = emptyList(),
        /** Emit only this many bytes of the whole buffer (truncation tests). */
        truncateTo: Int? = null,
        /** Override the extrasOffset header field (out-of-bounds tests). */
        extrasOffsetOverride: Int? = null,
    ): ByteBuffer {
        require(cells.size == cols * rows)
        val headerBytes = TerminalSnapshot.HEADER_I32_COUNT * 4
        val gridBytes = cols * rows * TerminalSnapshot.CELL_SIZE_BYTES
        val extrasWords = if (extras.isEmpty()) 0 else 1 + extras.sumOf { 2 + it.second.size }
        val total = headerBytes + gridBytes + extrasWords * 4

        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cols)
        buf.putInt(rows)
        buf.putInt(cursorX)
        buf.putInt(cursorY)
        buf.putInt(if (cursorVisible) 1 else 0)
        buf.putInt(10); buf.putInt(11); buf.putInt(12)   // default bg
        buf.putInt(240); buf.putInt(241); buf.putInt(242) // default fg
        buf.putInt(extrasOffsetOverride ?: if (extras.isEmpty()) 0 else headerBytes + gridBytes)
        buf.putInt(viewportScrollY)
        buf.putInt(if (appHandlesSelectionDrag) 1 else 0)

        for (c in cells) {
            buf.putInt(c.cp)
            buf.put(c.fg.first.toByte()); buf.put(c.fg.second.toByte()); buf.put(c.fg.third.toByte())
            buf.put(c.bg.first.toByte()); buf.put(c.bg.second.toByte()); buf.put(c.bg.third.toByte())
            buf.put(c.flags.toByte())
        }

        if (extras.isNotEmpty()) {
            buf.putInt(extras.size)
            for ((cellIndex, cps) in extras) {
                buf.putInt(cellIndex)
                buf.putInt(cps.size)
                for (cp in cps) buf.putInt(cp)
            }
        }

        buf.position(0)
        if (truncateTo != null) {
            val short = ByteBuffer.allocate(truncateTo).order(ByteOrder.LITTLE_ENDIAN)
            val src = buf.duplicate()
            src.limit(truncateTo)
            short.put(src)
            short.position(0)
            return short
        }
        return buf
    }

    private fun textCells(cols: Int, rows: Int, text: String): List<Cell> {
        val cells = MutableList(cols * rows) { Cell(' '.code) }
        text.forEachIndexed { i, ch -> cells[i] = Cell(ch.code) }
        return cells
    }

    @Test
    fun decodesBasicGrid() {
        val buf = buildSnapshot(
            cols = 8,
            rows = 2,
            cells = textCells(8, 2, "hello"),
            cursorX = 5,
            cursorY = 0,
            viewportScrollY = 42,
            appHandlesSelectionDrag = true,
        )

        val snap = TerminalSnapshot.fromByteBuffer(buf)

        assertEquals(8, snap.cols)
        assertEquals(2, snap.rows)
        assertEquals(5, snap.cursorX)
        assertEquals(0, snap.cursorY)
        assertTrue(snap.cursorVisible)
        assertEquals(42, snap.viewportScrollY)
        assertTrue(snap.appHandlesSelectionDrag)
        assertEquals("hello   ", snap.rowText(0))
        assertEquals("        ", snap.rowText(1))
        // Colors are packed opaque ARGB from the three byte channels.
        assertEquals(0xFF0A0B0C.toInt(), snap.defaultBgArgb)
        assertEquals(0xFFF0F1F2.toInt(), snap.defaultFgArgb)
        assertEquals(0xFFC8C8C8.toInt(), snap.fgArgb[0])
        assertEquals(0xFF000000.toInt(), snap.bgArgb[0])
        assertTrue(snap.graphemeExtras.isEmpty())
    }

    @Test
    fun cursorMinusOneMeansNoViewportCursor() {
        // The Zig writer emits -1/-1 when render_state.cursor.viewport is null
        // (the cursor has scrolled out of the viewport). It must survive the
        // decode as -1 rather than being clamped or read unsigned.
        val buf = buildSnapshot(
            cols = 4,
            rows = 1,
            cells = textCells(4, 1, "ab"),
            cursorX = -1,
            cursorY = -1,
            cursorVisible = false,
        )

        val snap = TerminalSnapshot.fromByteBuffer(buf)

        assertEquals(-1, snap.cursorX)
        assertEquals(-1, snap.cursorY)
        assertEquals(false, snap.cursorVisible)
    }

    @Test
    fun spacerFlagRidesTheCellAfterAWideGlyph() {
        // A wide glyph occupies two cells: the glyph, then a spacer serialized
        // with codepoint 32 and CELL_FLAG_SPACER set.
        val cells = MutableList(4) { Cell(' '.code) }
        cells[0] = Cell(0x4F60) // 你
        cells[1] = Cell(32, flags = TerminalSnapshot.CELL_FLAG_SPACER)

        val snap = TerminalSnapshot.fromByteBuffer(buildSnapshot(4, 1, cells))

        assertEquals(0x4F60, snap.codepoints[0])
        assertEquals(0, snap.flags[0].toInt() and TerminalSnapshot.CELL_FLAG_SPACER)
        assertEquals(32, snap.codepoints[1])
        assertEquals(
            TerminalSnapshot.CELL_FLAG_SPACER,
            snap.flags[1].toInt() and 0xFF and TerminalSnapshot.CELL_FLAG_SPACER,
        )
    }

    @Test
    fun decodesGraphemeExtras() {
        val cells = MutableList(4) { Cell(' '.code) }
        // 'e' + combining acute (U+0301) + combining diaeresis (U+0308)
        cells[1] = Cell('e'.code, flags = TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME)
        cells[2] = Cell('x'.code, flags = TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME)

        val snap = TerminalSnapshot.fromByteBuffer(
            buildSnapshot(
                cols = 4,
                rows = 1,
                cells = cells,
                extras = listOf(
                    1 to intArrayOf(0x0301, 0x0308),
                    2 to intArrayOf(0x20E3),
                ),
            ),
        )

        assertEquals(2, snap.graphemeExtras.size)
        assertTrue(intArrayOf(0x0301, 0x0308).contentEquals(snap.graphemeExtras[1]))
        assertTrue(intArrayOf(0x20E3).contentEquals(snap.graphemeExtras[2]))
        assertNull(snap.graphemeExtras[0])
    }

    @Test
    fun graphemeExtrasWithOutOfRangeCellIndexDegradeToEmpty() {
        // A record naming a cell outside the grid means the section is not
        // trustworthy: drop the whole map rather than render half of a corrupt
        // one. (Matches the Zig writer's invariant that cellIndex < cols*rows.)
        val cells = MutableList(4) { Cell(' '.code) }
        cells[0] = Cell('a'.code, flags = TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME)

        val snap = TerminalSnapshot.fromByteBuffer(
            buildSnapshot(
                cols = 4,
                rows = 1,
                cells = cells,
                extras = listOf(
                    0 to intArrayOf(0x0301),
                    99 to intArrayOf(0x0302), // out of bounds
                ),
            ),
        )

        assertTrue(snap.graphemeExtras.isEmpty())
    }

    @Test
    fun extrasOffsetPastTheBufferIsIgnored() {
        val cells = MutableList(4) { Cell(' '.code) }
        val snap = TerminalSnapshot.fromByteBuffer(
            buildSnapshot(4, 1, cells, extrasOffsetOverride = 100_000),
        )
        assertTrue(snap.graphemeExtras.isEmpty())
    }

    @Test
    fun truncatedBufferIsRejected() {
        val full = buildSnapshot(8, 2, textCells(8, 2, "hello"))
        val expected = (TerminalSnapshot.HEADER_I32_COUNT * 4) +
            (8 * 2 * TerminalSnapshot.CELL_SIZE_BYTES)
        val short = buildSnapshot(
            cols = 8,
            rows = 2,
            cells = textCells(8, 2, "hello"),
            truncateTo = expected - 1,
        )

        assertEquals(expected, full.capacity())
        val error = runCatching { TerminalSnapshot.fromByteBuffer(short) }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("too small"))
    }
}
