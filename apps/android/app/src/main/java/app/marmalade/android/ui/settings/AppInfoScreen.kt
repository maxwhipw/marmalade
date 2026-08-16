package app.marmalade.android.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.theme.Wordmark
import app.marmalade.android.ui.theme.marmaladeColors
import app.marmalade.android.BuildConfig
import app.marmalade.android.ui.home.MascotImage
import app.marmalade.android.ui.navigation.SettingsRoutes

/**
 * App Info screen: permissions link, about card with copy, credits and licenses navigation.
 *
 * The about card is non-tappable per SET-05 / CONTEXT.md decision --
 * no Modifier.clickable, no chevron icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current

    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val packageName = context.packageName

    val infoBlock = buildString {
        appendLine("Marmalade v$versionName ($versionCode)")
        appendLine("Device: $deviceModel")
        appendLine("Android: $androidVersion")
        appendLine("Package: $packageName")
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("App Info") },
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
            // About card (non-tappable -- no Modifier.clickable, no chevron)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Mascot illustration (default happy expression)
                    MascotImage(
                        modifier = Modifier.size(80.dp),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "marmalade",
                        fontFamily = Wordmark,
                        style = MaterialTheme.typography.titleLarge,
                        // titleLarge carries SemiBold; the wordmark face has
                        // only 400, so override it (theme/Type.kt).
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.marmaladeColors.wordmark,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Version $versionName ($versionCode)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Device: $deviceModel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = androidVersion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Package: $packageName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { copyToClipboard(context, "App Info", infoBlock) },
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Credits row
            SettingsNavRow(
                icon = Icons.Outlined.Favorite,
                title = "Credits",
                subtitle = null,
                onClick = { onNavigate(SettingsRoutes.CREDITS) },
            )

            // Licenses row
            SettingsNavRow(
                icon = Icons.Outlined.Description,
                title = "Licenses",
                subtitle = "Open source licenses",
                onClick = { onNavigate(SettingsRoutes.LICENSES) },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
