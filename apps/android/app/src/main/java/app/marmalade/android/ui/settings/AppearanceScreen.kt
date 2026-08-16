package app.marmalade.android.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.ui.theme.ThemePreset

/**
 * Appearance settings: theme mode (System/Light/Dark), color theme preset,
 * and language selection.
 *
 * Theme switching is instant -- the segmented button updates the hoisted
 * MutableState in MainActivity, causing live recomposition without restart.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    currentThemeMode: String,
    onThemeModeChange: (String) -> Unit,
    currentThemePreset: String = "SYSTEM",
    onThemePresetChange: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.getInstance(context) }
    val isDark = isSystemInDarkTheme() || currentThemeMode == "dark"

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        ) {
            // -- Theme section --
            SettingsSectionHeader("Theme")

            val themeModes = listOf("system", "light", "dark")
            val themeLabels = listOf("System", "Light", "Dark")
            val selectedIndex = themeModes.indexOf(currentThemeMode).coerceAtLeast(0)

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                themeModes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = themeModes.size,
                        ),
                        onClick = { onThemeModeChange(mode) },
                        selected = index == selectedIndex,
                    ) {
                        Text(themeLabels[index])
                    }
                }
            }

            // -- Color Theme section --
            SettingsSectionHeader("Color Theme")

            val selectedPreset = ThemePreset.fromString(currentThemePreset)

            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThemePreset.entries.forEach { preset ->
                    ThemePresetChip(
                        preset = preset,
                        isDark = isDark,
                        isSelected = preset == selectedPreset,
                        onClick = { onThemePresetChange(preset.name) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // -- Sessions section --
            // -- Language section --
            SettingsSectionHeader("Language")

            var currentLanguage by remember { mutableStateOf(settings.appLanguage) }

            val languageOptions = listOf(
                "System Default" to "",
                "English" to "en",
                "Japanese (nihongo)" to "ja",
            )

            SettingsDropdownRow(
                title = "Language",
                subtitle = null,
                currentValue = currentLanguage,
                options = languageOptions,
                onSelect = { value ->
                    currentLanguage = value
                    settings.appLanguage = value
                    applyLocaleChange(context, value)
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// =============================================================================
// Theme Preset Chip
// =============================================================================

/**
 * Circular color chip for theme preset selection. Shows a checkmark when
 * selected. SYSTEM shows a palette icon. Others show their primary color.
 */
@Composable
private fun ThemePresetChip(
    preset: ThemePreset,
    isDark: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val chipColor = when (preset) {
        ThemePreset.SYSTEM -> MaterialTheme.colorScheme.primary
        else -> if (isDark) preset.darkPrimary else preset.lightPrimary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(chipColor, CircleShape)
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    }
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "${preset.displayName} selected",
                    tint = if (chipColor.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            } else if (preset == ThemePreset.SYSTEM) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = "System dynamic color",
                    tint = if (chipColor.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = preset.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// =============================================================================
// Utility
// =============================================================================

/**
 * Apply locale change using the appropriate API for the Android version.
 * API 33+: LocaleManager.applicationLocales
 * Older: Locale.setDefault + Activity.recreate
 */
private fun applyLocaleChange(context: Context, appLanguage: String) {
    val tag = SettingsRepository.resolveLanguageTag(appLanguage)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager.applicationLocales = if (tag != null) {
            LocaleList.forLanguageTags(tag)
        } else {
            LocaleList.getEmptyLocaleList()
        }
    } else {
        if (tag != null) {
            val locale = java.util.Locale.forLanguageTag(tag)
            java.util.Locale.setDefault(locale)
        } else {
            java.util.Locale.setDefault(java.util.Locale.getDefault())
        }
        context.findActivity()?.recreate()
    }
}

/** Walk up the context wrapper chain to find the host Activity. */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
