package app.marmalade.android.ui.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The handle's shape and placement are drawing, which a JVM test cannot see.
 * Its one *decision* is the colour: the terminal is fixed dark, so a theme
 * primary can be legible or invisible depending on the device's theme.
 */
class TerminalSelectionHandleTest {

    private val accent = Color(0xFFF97316)

    @Test
    fun `a dark-scheme primary is kept`() {
        // Material You dark primary — plenty of luminance on the dark terminal.
        assertEquals(Color(0xFFD0BCFF), terminalHandleColor(Color(0xFFD0BCFF), accent))
    }

    @Test
    fun `a light-scheme primary falls back to the terminal accent`() {
        // Material You light primary — near-invisible on #1C1917.
        assertEquals(accent, terminalHandleColor(Color(0xFF6750A4), accent))
    }
}
