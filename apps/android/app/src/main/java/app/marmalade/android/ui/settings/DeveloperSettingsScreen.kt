package app.marmalade.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Timeline
import app.marmalade.android.ui.navigation.MarmaladeDestination
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance

/**
 * Developer settings: opt-in toggles for diagnostic features that are
 * hidden from the default experience but useful when investigating
 * gateway behaviour. Both toggles are OFF by default and persist across
 * launches via [SettingsRepository].
 *
 * Unlike a build-variant gate, this screen ships in release builds — a
 * curious user can reach the frame explorer without a special APK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.getInstance(context) }

    var showUnknownFrames by remember { mutableStateOf(settings.showUnknownFramesInChat) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Developer") },
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
            SettingsSectionHeader("Diagnostics")

            // Was a "show the Debug tab" toggle until ADR 0013 deleted the tab
            // bar. A live frame explorer needs a destination, not a switch —
            // so it is simply a row now, always reachable.
            SettingsNavRow(
                icon = Icons.Outlined.BugReport,
                title = "Frame explorer",
                subtitle = "Live explorer for every gateway frame, with " +
                    "per-kind filters.",
                onClick = { onNavigate(MarmaladeDestination.Debugging.route) },
            )

            SettingsToggleRow(
                title = "Show unknown frames in chat",
                subtitle = "Render gateway frames the app doesn't recognise as " +
                    "cards in the conversation. They are always written to the " +
                    "debug log regardless of this setting.",
                checked = showUnknownFrames,
                onCheckedChange = { enabled ->
                    showUnknownFrames = enabled
                    settings.showUnknownFramesInChat = enabled
                },
            )

            SettingsNavRow(
                icon = Icons.Outlined.Timeline,
                title = "Event Trace",
                subtitle = "Persistent per-session log of gateway events " +
                    "(survives restarts — the Debug tab is live frames only).",
                onClick = { onNavigate(app.marmalade.android.ui.navigation.SettingsRoutes.EVENT_TRACE) },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
