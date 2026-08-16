package app.marmalade.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of the cursor-style wire contract with
 * `native/src/bridge/cursor_style.zig`.
 *
 * The packed ordinals are asserted as *literals* on purpose. Deriving them
 * from the enum would make this test agree with any regression: the point is
 * to pin the numbers the Zig `switch` emits, so a change on either side fails
 * here instead of silently drawing the wrong cursor on a phone.
 */
class TerminalCursorStyleTest {

    @Test
    fun `the packed ordinals match the ones cursor_style zig emits`() {
        assertEquals(TerminalCursorShape.BAR, TerminalCursorStyle.decode(0).shape)
        assertEquals(TerminalCursorShape.BLOCK, TerminalCursorStyle.decode(1).shape)
        assertEquals(TerminalCursorShape.UNDERLINE, TerminalCursorStyle.decode(2).shape)
        assertEquals(TerminalCursorShape.BLOCK_HOLLOW, TerminalCursorStyle.decode(3).shape)
    }

    @Test
    fun `bit 3 is the blink flag and is independent of the shape`() {
        assertFalse(TerminalCursorStyle.decode(0).blinking)
        assertTrue(TerminalCursorStyle.decode(8).blinking)

        // 8 or 2 = a blinking underline, which is what `ESC [3 q` selects.
        val blinkingUnderline = TerminalCursorStyle.decode(10)
        assertEquals(TerminalCursorShape.UNDERLINE, blinkingUnderline.shape)
        assertTrue(blinkingUnderline.blinking)

        // 8 or 3 = a blinking hollow block; the flag must not bleed into the
        // shape bits even when both are set.
        val blinkingHollow = TerminalCursorStyle.decode(11)
        assertEquals(TerminalCursorShape.BLOCK_HOLLOW, blinkingHollow.shape)
        assertTrue(blinkingHollow.blinking)
    }

    @Test
    fun `every style survives an encode-decode round trip`() {
        for (shape in TerminalCursorShape.entries) {
            for (blinking in listOf(false, true)) {
                val style = TerminalCursorStyle(shape, blinking)
                assertEquals(style, TerminalCursorStyle.decode(TerminalCursorStyle.encode(style)))
            }
        }
    }

    @Test
    fun `an unknown shape ordinal degrades to a block rather than throwing`() {
        // A native/Kotlin version skew must not crash the terminal screen.
        assertEquals(TerminalCursorShape.BLOCK, TerminalCursorStyle.decode(7).shape)
        assertTrue(TerminalCursorStyle.decode(15).blinking)
        assertEquals(TerminalCursorShape.BLOCK, TerminalCursorStyle.decode(15).shape)
    }

    @Test
    fun `high bits outside the contract are ignored`() {
        // The Zig side only ever sets bits 0-3; anything above them is not
        // ours to interpret, and must not disturb the decode.
        assertEquals(TerminalCursorStyle.decode(2), TerminalCursorStyle.decode(2 or 0x70))
    }

    @Test
    fun `the default is a steady block, matching a terminal at power-on`() {
        assertEquals(TerminalCursorShape.BLOCK, TerminalCursorStyle.Default.shape)
        assertFalse(TerminalCursorStyle.Default.blinking)
        // The native side answers this for a null handle, so the two agree.
        assertEquals(TerminalCursorStyle.Default, TerminalCursorStyle.decode(1))
    }
}

/**
 * The geometry each shape occupies inside its cell. This is the part that goes
 * subtly wrong — a bar on the wrong edge, an underline outside its own row.
 */
class TerminalCursorGeometryTest {

    private fun draw(shape: TerminalCursorShape, thickness: Float = 2f) =
        TerminalCursorGeometry.forShape(
            shape = shape,
            cellLeft = 10f,
            cellTop = 20f,
            cellWidth = 8f,
            cellHeight = 16f,
            thickness = thickness,
        )

    @Test
    fun `a block fills the cell and inverts the glyph under it`() {
        val d = draw(TerminalCursorShape.BLOCK)
        assertEquals(10f, d.left, 0f)
        assertEquals(20f, d.top, 0f)
        assertEquals(18f, d.right, 0f)
        assertEquals(36f, d.bottom, 0f)
        assertFalse(d.stroked)
        assertTrue(d.invertGlyph)
    }

    @Test
    fun `a hollow block covers the same cell but outlines it and keeps the glyph`() {
        val d = draw(TerminalCursorShape.BLOCK_HOLLOW)
        val block = draw(TerminalCursorShape.BLOCK)
        assertEquals(block.left, d.left, 0f)
        assertEquals(block.right, d.right, 0f)
        assertEquals(block.top, d.top, 0f)
        assertEquals(block.bottom, d.bottom, 0f)
        assertTrue(d.stroked)
        // Nothing is hidden by an outline, so inverting would be wrong.
        assertFalse(d.invertGlyph)
    }

    @Test
    fun `a bar sits on the left edge, full height, and never inverts`() {
        val d = draw(TerminalCursorShape.BAR)
        assertEquals(10f, d.left, 0f)
        assertEquals(12f, d.right, 0f)
        assertEquals(20f, d.top, 0f)
        assertEquals(36f, d.bottom, 0f)
        assertFalse(d.stroked)
        assertFalse(d.invertGlyph)
    }

    @Test
    fun `an underline sits on the bottom edge, full width, and never inverts`() {
        val d = draw(TerminalCursorShape.UNDERLINE)
        assertEquals(10f, d.left, 0f)
        assertEquals(18f, d.right, 0f)
        assertEquals(34f, d.top, 0f)
        assertEquals(36f, d.bottom, 0f)
        assertFalse(d.stroked)
        assertFalse(d.invertGlyph)
    }

    @Test
    fun `an oversized thickness is clamped to the cell instead of bleeding out`() {
        // At a tiny font a 2dp bar can be wider than the cell itself.
        val bar = draw(TerminalCursorShape.BAR, thickness = 999f)
        assertEquals(18f, bar.right, 0f)

        val underline = draw(TerminalCursorShape.UNDERLINE, thickness = 999f)
        assertEquals(20f, underline.top, 0f)
    }

    @Test
    fun `a zero thickness still paints a visible line`() {
        val bar = draw(TerminalCursorShape.BAR, thickness = 0f)
        assertEquals(11f, bar.right, 0f)

        val underline = draw(TerminalCursorShape.UNDERLINE, thickness = 0f)
        assertEquals(35f, underline.top, 0f)
    }

    @Test
    fun `only the filled block ever inverts the glyph`() {
        val inverting = TerminalCursorShape.entries.filter { draw(it).invertGlyph }
        assertEquals(listOf(TerminalCursorShape.BLOCK), inverting)
    }
}
