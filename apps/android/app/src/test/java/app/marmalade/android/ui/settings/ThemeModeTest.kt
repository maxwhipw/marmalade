package app.marmalade.android.ui.settings

import app.marmalade.android.ui.theme.resolveThemeIsDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [resolveThemeIsDark].
 * No Android context needed.
 */
class ThemeModeTest {

    @Test
    fun `light mode always returns false regardless of system`() {
        assertFalse(resolveThemeIsDark("light", isSystemDark = false))
        assertFalse(resolveThemeIsDark("light", isSystemDark = true))
    }

    @Test
    fun `dark mode always returns true regardless of system`() {
        assertTrue(resolveThemeIsDark("dark", isSystemDark = false))
        assertTrue(resolveThemeIsDark("dark", isSystemDark = true))
    }

    @Test
    fun `system mode delegates to isSystemDark`() {
        assertFalse(resolveThemeIsDark("system", isSystemDark = false))
        assertTrue(resolveThemeIsDark("system", isSystemDark = true))
    }

    @Test
    fun `empty string delegates to system`() {
        assertFalse(resolveThemeIsDark("", isSystemDark = false))
        assertTrue(resolveThemeIsDark("", isSystemDark = true))
    }

    @Test
    fun `unknown value delegates to system`() {
        assertFalse(resolveThemeIsDark("sepia", isSystemDark = false))
        assertTrue(resolveThemeIsDark("sepia", isSystemDark = true))
    }

    @Test
    fun `only three valid modes exist`() {
        val validModes = listOf("system", "light", "dark")
        // light -> false, dark -> true, system -> depends on system
        assertEquals(false, resolveThemeIsDark(validModes[0], isSystemDark = false))
        assertEquals(true, resolveThemeIsDark(validModes[0], isSystemDark = true))
        assertEquals(false, resolveThemeIsDark(validModes[1], isSystemDark = false))
        assertEquals(false, resolveThemeIsDark(validModes[1], isSystemDark = true))
        assertEquals(true, resolveThemeIsDark(validModes[2], isSystemDark = false))
        assertEquals(true, resolveThemeIsDark(validModes[2], isSystemDark = true))
    }
}
