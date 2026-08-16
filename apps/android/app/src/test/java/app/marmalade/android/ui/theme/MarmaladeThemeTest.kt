package app.marmalade.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the Marmalade theme color configuration.
 *
 * Light mode uses the precious orange accent; dark mode swaps it out — per
 * the design scheme, orange is banned on dark large fills, so the dark
 * `primary`/`secondary` are Toast (legible as both a fill and a tint).
 */
class MarmaladeThemeTest {

    @Test
    fun marmaladeTheme_lightColors_usesOrangePrimary() {
        assertEquals(Color(0xFFF97316), MarmaladeLightColors.primary)
    }

    @Test
    fun marmaladeTheme_darkColors_usesToastPrimary() {
        // Orange is banned on dark; primary is Toast (design scheme swap).
        assertEquals(Color(0xFFFED7AA), MarmaladeDarkColors.primary)
    }

    @Test
    fun marmaladeTheme_lightColors_usesOrangeSecondary() {
        assertEquals(Color(0xFFEA580C), MarmaladeLightColors.secondary)
    }

    @Test
    fun marmaladeTheme_darkColors_usesToastSecondary() {
        assertEquals(Color(0xFFFED7AA), MarmaladeDarkColors.secondary)
    }

    @Test
    fun marmaladeTheme_darkSurface_isStoneDeep() {
        // Dark surface must be stone-deep #1C1917, never amber-brown.
        assertEquals(Color(0xFF1C1917), MarmaladeDarkColors.surface)
        assertEquals(Color(0xFF1C1917), MarmaladeDarkColors.background)
    }

    @Test
    fun marmaladeTheme_noDynamicColor() {
        // Verify that our color schemes are the fixed palettes,
        // not dynamically generated.
        assertEquals(
            "Light primary should be fixed Orange, not dynamic",
            Color(0xFFF97316),
            MarmaladeLightColors.primary,
        )
        assertEquals(
            "Dark primary should be fixed Toast, not dynamic",
            Color(0xFFFED7AA),
            MarmaladeDarkColors.primary,
        )
    }
}
