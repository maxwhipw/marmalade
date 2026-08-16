/**
 * Data Flow: Settings Hub
 *
 * SettingsRepository (EncryptedSharedPreferences) ──► immediate writes on toggle
 *                                                        │
 * MarmaladeRuntime (SecurePrefs/StateFlow)            ──► collectAsState() in screens
 *                                                        │
 * NavHost (state-based routing via NavController)        │
 *   "hub"            → SettingsHubScreen          ─────────► 5 category tiles
 *   "assistant_voice"→ AssistantVoiceScreen        ─────────► wake word, TTS, conversation
 *   "permissions"    → PermissionsScreen           ─────────► camera, location, SMS status
 *   "appearance"     → AppearanceScreen            ─────────► language
 *   "about"          → AboutScreen                 ─────────► version, device info
 *
 * User actions flow: UI toggle → SettingsRepository.property = value (immediate apply())
 *
 * Endpoint configuration lives in the marmalade ConnectScreen; broader
 * gateway-management parity (skills / MCP / model / env / logs) is tracked
 * separately.
 */
package app.marmalade.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.ui.theme.*
import app.marmalade.android.service.HotwordService
import app.marmalade.android.utils.SystemInfoProvider

/**
 * Public project repository, used by the Settings "Links" section.
 *
 * TODO: confirm the final public org/repo URL before release — this is a
 * placeholder for the repo the app will actually be published from.
 */
private const val PROJECT_REPO_URL = "https://github.com/marmalade-assistant/marmalade"

// ─────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────

class SettingsActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    override fun attachBaseContext(newBase: Context) {
        val repo = SettingsRepository.getInstance(newBase)
        val lang = repo.appLanguage
        if (lang.isNotBlank()) {
            val locale = java.util.Locale.forLanguageTag(lang)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)

        setContent {
            app.marmalade.android.ui.theme.MarmaladeAssistantTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "hub") {
                    composable("hub") {
                        SettingsHubScreen(
                            navController = navController,
                            settings = settings,
                            onBack = { finish() }
                        )
                    }
                    composable("assistant_voice") {
                        AssistantVoiceScreen(navController = navController, settings = settings)
                    }
                    composable("permissions") {
                        PermissionsScreen(navController = navController)
                    }
                    composable("appearance") {
                        AppearanceScreen(navController = navController, settings = settings)
                    }
                    composable("about") {
                        AboutScreen(navController = navController, settings = settings)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
// Hub Screen
// ─────────────────────────────────────────────────────────

@Composable
fun SettingsHubScreen(
    navController: NavController,
    settings: SettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val hotwordEnabled = remember { mutableStateOf(settings.hotwordEnabled) }

    // Dynamically refresh hotword state when returning from sub-screen
    LaunchedEffect(Unit) {
        hotwordEnabled.value = settings.hotwordEnabled
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    // Subtitle helpers
    val wakeWordSubtitle = if (hotwordEnabled.value) "Wake word on" else "Wake word off"

    val permissionsSubtitle = remember(context) {
        buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) add("Camera")
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) add("Location")
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) add("SMS")
        }.joinToString(", ").ifEmpty { "None granted" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        SettingsTopBar(
            title = "Settings",
            onBack = onBack
        )

        // Tile list
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SettingsCategoryTile(
                icon = "\uD83C\uDF99\uFE0F",
                title = "Assistant & Voice",
                subtitle = wakeWordSubtitle,
                onClick = { navController.navigate("assistant_voice") }
            )
            SettingsCategoryTile(
                icon = "\uD83D\uDEE1\uFE0F",
                title = "Permissions",
                subtitle = permissionsSubtitle,
                onClick = { navController.navigate("permissions") }
            )
            SettingsCategoryTile(
                icon = "\uD83C\uDFA8",
                title = "Appearance",
                subtitle = "Dark theme",
                onClick = { navController.navigate("appearance") }
            )
            SettingsCategoryTile(
                icon = "\uD83D\uDD14",
                title = "Notifications",
                subtitle = "Coming soon",
                onClick = {
                    Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                }
            )
            SettingsCategoryTile(
                icon = "\u2139\uFE0F",
                title = "About",
                subtitle = "Marmalade v$versionName",
                onClick = { navController.navigate("about") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Version footer
            HorizontalDivider(color = MaterialTheme.marmaladeColors.assistantBubble, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Marmalade v$versionName",
                color = MaterialTheme.colorScheme.outline,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────
// Sub-Screen: Assistant & Voice
// ─────────────────────────────────────────────────────────

@Composable
fun AssistantVoiceScreen(
    navController: NavController,
    settings: SettingsRepository
) {
    val context = LocalContext.current

    // State — read immediately, write immediately
    var hotwordEnabled by remember { mutableStateOf(settings.hotwordEnabled) }
    var thinkingSoundEnabled by remember { mutableStateOf(settings.thinkingSoundEnabled) }

    val ttsSpeedDisplay = remember(settings.ttsSpeed) {
        "%.1fx".format(settings.ttsSpeed)
    }
    val speechLangDisplay = remember(settings.speechLanguage) {
        settings.speechLanguage.ifBlank { "System default" }
    }
    val silenceDisplay = remember(settings.speechSilenceTimeout) {
        "%.1fs".format(settings.speechSilenceTimeout / 1000f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsTopBar(title = "Assistant & Voice", onBack = { navController.popBackStack() })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // GROUP: WAKE WORD
            SettingsGroupHeader(title = "Wake Word")

            SettingsSwitchItem(
                title = "Wake word",
                subtitle = settings.getWakeWordDisplayName(),
                checked = hotwordEnabled,
                onCheckedChange = { value ->
                    hotwordEnabled = value
                    settings.hotwordEnabled = value
                    if (value) {
                        HotwordService.start(context)
                    } else {
                        HotwordService.stop(context)
                    }
                }
            )

            SettingsNavigationItem(
                title = "Default assistant",
                subtitle = "Required for system-wide activation",
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "Voice input settings not available", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.marmaladeColors.assistantBubble,
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 28.dp)
            )

            // GROUP: SPEECH
            SettingsGroupHeader(title = "Speech")

            SettingsNavigationItem(
                title = "Speech speed",
                subtitle = ttsSpeedDisplay,
                onClick = {
                    Toast.makeText(context, "Adjust TTS speed in voice settings", Toast.LENGTH_SHORT).show()
                }
            )

            SettingsNavigationItem(
                title = "Speech language",
                subtitle = speechLangDisplay,
                onClick = {
                    Toast.makeText(context, "Speech language: configure in the system settings", Toast.LENGTH_SHORT).show()
                }
            )

            SettingsNavigationItem(
                title = "Silence timeout",
                subtitle = silenceDisplay,
                onClick = {
                    Toast.makeText(context, "Silence timeout: ${silenceDisplay}", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.marmaladeColors.assistantBubble,
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 28.dp)
            )

            // GROUP: CONVERSATION
            SettingsGroupHeader(title = "Conversation")

            SettingsSwitchItem(
                title = "Thinking sound",
                subtitle = "Play audio while processing",
                checked = thinkingSoundEnabled,
                onCheckedChange = { value ->
                    thinkingSoundEnabled = value
                    settings.thinkingSoundEnabled = value
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// Gateway management UI is not hosted here: endpoint config lives in
// ConnectScreen; broader gateway-management parity (skills / MCP config /
// model selection / env vars / logs) is tracked separately.

// ─────────────────────────────────────────────────────────
// Sub-Screen: Permissions
// ─────────────────────────────────────────────────────────

@Composable
fun PermissionsScreen(navController: NavController) {
    val context = LocalContext.current

    data class PermissionInfo(
        val label: String,
        val description: String,
        val permission: String
    )

    val permissions = listOf(
        PermissionInfo("Camera", "Required for the AI to see your camera", Manifest.permission.CAMERA),
        PermissionInfo("Location", "Required for the AI to know your location", Manifest.permission.ACCESS_COARSE_LOCATION),
        PermissionInfo("SMS", "Required for the AI to send text messages", Manifest.permission.SEND_SMS),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsTopBar(title = "Permissions", onBack = { navController.popBackStack() })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsGroupHeader(title = "App Permissions")

            permissions.forEach { perm ->
                val granted = ContextCompat.checkSelfPermission(context, perm.permission) == PackageManager.PERMISSION_GRANTED

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // Navigate to app settings so user can grant permission
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = perm.label,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp
                        )
                        Text(
                            text = perm.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (granted) MaterialTheme.marmaladeColors.toolSuccessDim else MaterialTheme.marmaladeColors.assistantBubble
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (granted) "Granted" else "Denied",
                            color = if (granted) MaterialTheme.marmaladeColors.toolChipText else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.marmaladeColors.assistantBubble,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 28.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Tap any permission to open system settings and change it.",
                color = MaterialTheme.colorScheme.outline,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────
// Sub-Screen: Appearance
// ─────────────────────────────────────────────────────────

@Composable
fun AppearanceScreen(
    navController: NavController,
    settings: SettingsRepository
) {
    val context = LocalContext.current

    var appLanguage by remember { mutableStateOf(settings.appLanguage) }
    var showLanguageMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsTopBar(title = "Appearance", onBack = { navController.popBackStack() })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsGroupHeader(title = "Theme")

            // Theme is currently always dark (Marmalade design uses dark-only)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Theme", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    Text("Dark (Marmalade)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }

            HorizontalDivider(
                color = MaterialTheme.marmaladeColors.assistantBubble,
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 28.dp)
            )

            SettingsGroupHeader(title = "Language")

            // Display language selector
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 4.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = showLanguageMenu,
                    onExpandedChange = { showLanguageMenu = it }
                ) {
                    val currentLabel = DISPLAY_LANGUAGE_OPTIONS.find { it.first == appLanguage }?.second
                        ?: if (appLanguage.isBlank()) "System Default" else appLanguage
                    OutlinedTextField(
                        value = currentLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Display Language", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLanguageMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.marmaladeColors.assistantBubble,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
                        DISPLAY_LANGUAGE_OPTIONS.forEach { (tag, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    appLanguage = tag
                                    showLanguageMenu = false
                                    settings.appLanguage = tag
                                    applyAppLanguage(context, tag)
                                },
                                leadingIcon = {
                                    if (appLanguage == tag) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────
// Sub-Screen: About
// ─────────────────────────────────────────────────────────

@Composable
fun AboutScreen(
    navController: NavController,
    settings: SettingsRepository
) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) {
        (context.applicationContext as MarmaladeApplication).marmaladeRuntime
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    val deviceModel = remember { "${Build.MANUFACTURER} ${Build.MODEL}" }
    val androidVersion = remember { "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsTopBar(title = "About", onBack = { navController.popBackStack() })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsGroupHeader(title = "App")

            SettingsNavigationItem(
                title = "Version",
                subtitle = "Marmalade v$versionName",
                onClick = { }
            )

            SettingsNavigationItem(
                title = "Device",
                subtitle = deviceModel,
                onClick = { }
            )

            SettingsNavigationItem(
                title = "Android",
                subtitle = androidVersion,
                onClick = { }
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.marmaladeColors.assistantBubble,
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 28.dp)
            )

            SettingsGroupHeader(title = "Links")

            SettingsNavigationItem(
                title = "Open-source licenses",
                subtitle = "View third-party licenses",
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_REPO_URL))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            SettingsButtonItem(
                title = "Report an issue",
                subtitle = "Submit feedback or bug report",
                buttonText = "Open GitHub",
                onClick = {
                    // Server version (formerly from `hello-ok` handshake) is no
                    // longer tracked; B/3a may reinstate via a marmalade RPC.
                    val serverVersion: String? = null
                    val systemInfo = SystemInfoProvider.getSystemInfoReport(context, settings, serverVersion)
                    val body = "\n\n$systemInfo"
                    val uri = Uri.parse("$PROJECT_REPO_URL/issues/new")
                        .buildUpon()
                        .appendQueryParameter("body", body)
                        .build()
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────
// Shared Building Blocks
// ─────────────────────────────────────────────────────────

@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
    }
}

@Composable
fun SettingsCategoryTile(
    icon: String,
    title: String,
    subtitle: String,
    showStatusDot: Boolean = false,
    statusDotGreen: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon circle
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                fontSize = 20.sp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Text block
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showStatusDot) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (statusDotGreen) MaterialTheme.marmaladeColors.toolSuccess else MaterialTheme.colorScheme.error)
                    )
                }
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Chevron
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }

    HorizontalDivider(
        color = MaterialTheme.marmaladeColors.assistantBubble,
        thickness = 1.dp,
        modifier = Modifier.padding(start = 72.dp, end = 12.dp)
    )
}

@Composable
fun SettingsGroupHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp).padding(bottom = 0.dp)
    )
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 28.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = Color(0xFF7A5500),
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.marmaladeColors.assistantBubble
            )
        )
    }
}

@Composable
fun SettingsNavigationItem(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun SettingsButtonItem(
    title: String,
    subtitle: String? = null,
    buttonText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = buttonText,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────

private fun applyAppLanguage(context: Context, languageTag: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
        val locales = if (languageTag.isBlank()) {
            android.os.LocaleList.getEmptyLocaleList()
        } else {
            android.os.LocaleList.forLanguageTags(languageTag)
        }
        try {
            localeManager.applicationLocales = locales
        } catch (_: Exception) {
            // Best effort
        }
    } else if (languageTag.isNotBlank()) {
        val locale = java.util.Locale.forLanguageTag(languageTag)
        java.util.Locale.setDefault(locale)
    }
}

private val DISPLAY_LANGUAGE_OPTIONS = listOf(
    "" to "System Default",
    "en" to "English",
    "ja-JP" to "Japan",
    "zh-CN" to "China",
    "zh-TW" to "Taiwan",
    "hi-IN" to "India",
    "de-DE" to "Germany",
    "de-AT" to "Austria",
    "ru-RU" to "Russia",
    "fr" to "Francais",
    "es" to "Espanol"
)
