package app.marmalade.android.ui.theme

import app.marmalade.android.data.getInstance

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * Resolve whether dark theme should be active based on user preference.
 *
 * Pure function -- testable without Android context.
 * - "light"  -> false (always light)
 * - "dark"   -> true  (always dark)
 * - anything else (including "system", empty, unknown) -> delegates to system
 */
fun resolveThemeIsDark(themeMode: String, isSystemDark: Boolean): Boolean {
    return when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemDark
    }
}

/**
 * Marmalade app theme with Material You dynamic colors and curated presets.
 *
 * @param darkTheme Whether to use the dark color scheme.
 * @param themePreset The selected theme preset. SYSTEM uses Material You
 *   dynamic colors (Android 12+). MARMALADE uses the hand-tuned orange
 *   palette. Other presets overlay their primary family onto the warm
 *   stone base scheme.
 * @param content The composable content to render within this theme.
 */
@Composable
fun MarmaladeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themePreset: ThemePreset = ThemePreset.SYSTEM,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = if (darkTheme) MarmaladeDarkColors else MarmaladeLightColors

    val colorScheme = remember(themePreset, darkTheme, context) {
        when (themePreset) {
            ThemePreset.SYSTEM -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else {
                    baseScheme // Fallback for older devices (shouldn't happen: minSdk=31)
                }
            }
            ThemePreset.MARMALADE -> baseScheme
            else -> buildPresetScheme(baseScheme, themePreset, darkTheme)
        }
    }

    val marmaladeColors = remember(themePreset, darkTheme, colorScheme) {
        when (themePreset) {
            ThemePreset.MARMALADE -> if (darkTheme) DarkMarmaladeColors else LightMarmaladeColors
            else -> deriveMarmaladeColors(colorScheme, darkTheme)
        }
    }

    CompositionLocalProvider(
        LocalMarmaladeColors provides marmaladeColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = MarmaladeShapes,
            typography = MarmaladeTypography,
            content = content,
        )
    }
}

/**
 * Theme wrapper for entry points outside [MainActivity]'s setContent —
 * [SettingsActivity] and the [MarmaladeVoiceSession] service overlay. Unlike the
 * bare [MarmaladeTheme] defaults (system dark + dynamic colors), this reads
 * the user's saved theme mode and preset from [SettingsRepository], so the
 * voice popup matches the app instead of always following the system
 * (pre-fix: app in light mode + system dark rendered the popup dark).
 */
@Composable
fun MarmaladeAssistantTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { app.marmalade.android.data.SettingsRepository.getInstance(context) }
    val darkTheme = resolveThemeIsDark(settings.themeMode, isSystemInDarkTheme())
    val themePreset = ThemePreset.fromString(settings.themePreset)
    MarmaladeTheme(darkTheme = darkTheme, themePreset = themePreset, content = content)
}

/**
 * Whether the *active* Marmalade theme is dark — derived from the resolved
 * surface luminance, not [isSystemInDarkTheme]. Use this in composables that
 * hand-pick mode-aware palette values (voice popup, pill, mascot): the app
 * theme setting can disagree with the system (e.g. app forced light while
 * the system is dark), and the system check painted those surfaces dark
 * inside an otherwise light app.
 */
@Composable
@ReadOnlyComposable
fun isMarmaladeThemeDark(): Boolean =
    MaterialTheme.colorScheme.surface.luminance() < 0.5f
