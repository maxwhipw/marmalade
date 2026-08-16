package app.marmalade.android.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.navigation.SettingsRoutes

/**
 * Settings tab root: filled category cards with tinted icons.
 * Each card navigates to a settings sub-screen.
 *
 * (Terminals moved out of Settings 2026-07-24: quick terminals live on the
 * Sessions screen's Terminals tab; workspace terminals on the workspace
 * detail screen.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMainScreen(
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                windowInsets = WindowInsets(0),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Palette,
                    title = "Appearance",
                    subtitle = "Theme, accent color",
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { onNavigate(SettingsRoutes.APPEARANCE) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Mic,
                    title = "Speech Recognition",
                    subtitle = "STT model, sensitivity",
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { onNavigate(SettingsRoutes.SPEECH_RECOGNITION) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.SmartToy,
                    title = "Assistant",
                    subtitle = "Wake word, TTS, conversation",
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { onNavigate(SettingsRoutes.ASSISTANT) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Hub,
                    title = "Connection",
                    subtitle = "Dashboard + plugin endpoints",
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = { onNavigate(SettingsRoutes.CONNECTION) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Memory,
                    title = "Models",
                    subtitle = "Default model and thinking level for new sessions",
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { onNavigate(SettingsRoutes.MODELS) },
                )
            }
            // Skills / MCP / Plugins are LIVE against marmaladed
            // (fork-rest-triage Parts C + E, 2026-07-12) — un-hidden
            // individually as their daemon methods landed.
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Extension,
                    title = "Skills",
                    subtitle = "Enable or disable agent skills",
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { onNavigate(SettingsRoutes.SKILLS) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Schedule,
                    title = "Scheduled prompts",
                    subtitle = "Cron jobs that message a session on a schedule",
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { onNavigate(SettingsRoutes.SCHEDULED) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.BarChart,
                    title = "Usage",
                    subtitle = "Daily token and turn totals from the daemon",
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { onNavigate(SettingsRoutes.USAGE) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.DeveloperBoard,
                    title = "MCP Servers",
                    subtitle = "Enable or disable MCP server integrations",
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { onNavigate(SettingsRoutes.MCP) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Power,
                    title = "Plugins",
                    subtitle = "Enable or disable harness plugins",
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { onNavigate(SettingsRoutes.PLUGINS) },
                )
            }
            // Device pairing is LIVE against marmaladed (M2 pairing.claim +
            // device.list/revoke; screen rebuilt 2026-07-12).
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.PersonAdd,
                    title = "Devices",
                    subtitle = "Pair this phone and manage paired devices",
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = { onNavigate(SettingsRoutes.PAIRING) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Security,
                    title = "Permissions",
                    subtitle = "Manage app permissions",
                    iconTint = MaterialTheme.colorScheme.outline,
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Code,
                    title = "Developer",
                    subtitle = "Debug tab, diagnostics",
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { onNavigate(SettingsRoutes.DEVELOPER) },
                )
            }
            item {
                SettingsCategoryCard(
                    icon = Icons.Outlined.Info,
                    title = "App Info",
                    subtitle = "About, credits, licenses",
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { onNavigate(SettingsRoutes.APP_INFO) },
                )
            }
        }
    }
}
