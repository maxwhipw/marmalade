package app.marmalade.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for theme presets and the fromString lookup.
 */
class AccentColorTest {

    @Test
    fun themePreset_hasFivePresets() {
        assertEquals(5, ThemePreset.entries.size)
    }

    @Test
    fun themePreset_defaultIsSystem() {
        assertEquals(ThemePreset.SYSTEM, ThemePreset.entries.first())
    }

    @Test
    fun themePreset_curatedPresetsHaveValidColors() {
        // Skip SYSTEM — its colors are Color.Unspecified (dynamic at runtime)
        ThemePreset.entries.filter { it != ThemePreset.SYSTEM }.forEach { preset ->
            assertNotEquals(
                "Preset ${preset.displayName} lightPrimary should not be Unspecified",
                Color.Unspecified,
                preset.lightPrimary,
            )
            assertNotEquals(
                "Preset ${preset.displayName} darkPrimary should not be Unspecified",
                Color.Unspecified,
                preset.darkPrimary,
            )
            assertNotEquals(
                "Preset ${preset.displayName} light and dark primary should differ",
                preset.lightPrimary,
                preset.darkPrimary,
            )
        }
    }

    @Test
    fun themePreset_eachHasNonBlankDisplayName() {
        ThemePreset.entries.forEach { preset ->
            assertTrue(
                "Preset ${preset.name} should have a non-blank display name",
                preset.displayName.isNotBlank(),
            )
        }
    }

    @Test
    fun themePreset_fromString_matchesCaseInsensitive() {
        assertEquals(ThemePreset.MARMALADE, ThemePreset.fromString("marmalade"))
        assertEquals(ThemePreset.MIDNIGHT, ThemePreset.fromString("MIDNIGHT"))
        assertEquals(ThemePreset.FOREST, ThemePreset.fromString("Forest"))
    }

    @Test
    fun themePreset_fromString_unknownDefaultsToSystem() {
        assertEquals(ThemePreset.SYSTEM, ThemePreset.fromString(""))
        assertEquals(ThemePreset.SYSTEM, ThemePreset.fromString("unknown_preset"))
    }
}
