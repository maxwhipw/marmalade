package app.marmalade.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.node.ConnectionPhase
import app.marmalade.android.rpc.ConnectionHints
import app.marmalade.android.node.MarmaladeRuntime
import app.marmalade.android.ui.onboarding.ScanPairingQrButton
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * In-app view + re-edit of the dashboard + plugin connection endpoints.
 *
 * The screen itself is read-only: it shows each connection's live status and
 * its current (token-masked) config as a summary card. Tapping "Edit
 * connection" opens a dialog with the editable fields + QR scan, so the
 * endpoints aren't sitting in loose text boxes that look half-finished and
 * invite accidental edits.
 *
 * Mirrors the onboarding GatewayStep but stays available after first run so
 * the user can rotate the dashboard token (process-lifetime, re-minted on
 * gateway restart) or re-pair the plugin without wiping app data.
 *
 * Save → reconnect: clearing or updating fields disconnects the affected
 * socket and reconnects with the new values, matching the desktop's
 * apply-config behavior in `apps/desktop/.../settings/gateway-settings.tsx`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    marmaladeRuntime: MarmaladeRuntime,
    onBack: (() -> Unit)? = null,
) {
    val storedDashboardUrl by marmaladeRuntime.dashboardUrl.collectAsStateWithLifecycle()
    val storedDashboardToken by marmaladeRuntime.dashboardToken.collectAsStateWithLifecycle()
    val storedPluginUrl by marmaladeRuntime.marmaladeUrl.collectAsStateWithLifecycle()
    val storedPluginToken by marmaladeRuntime.marmaladeToken.collectAsStateWithLifecycle()
    val dashboardPhase by marmaladeRuntime.connectionPhase.collectAsStateWithLifecycle()
    val pluginPhase by marmaladeRuntime.pluginConnectionPhase.collectAsStateWithLifecycle()

    var showEditDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Connection") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            ConnectionCard(
                title = "Dashboard",
                description = "Chat (session, prompt, message events). Same /api/ws " +
                    "endpoint the desktop + web clients use.",
                phase = dashboardPhase,
                url = storedDashboardUrl,
                token = storedDashboardToken,
                notConfiguredText = "Not configured",
            )

            ConnectionCard(
                title = "Android plugin (optional)",
                description = "Only needed if you want the agent to fire Android " +
                    "tools (alarms, intents, camera) on this device.",
                phase = pluginPhase,
                url = storedPluginUrl,
                token = storedPluginToken,
                notConfiguredText = "Off — leave blank if you don't use device tools",
            )

            OutlinedButton(
                onClick = { showEditDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Edit connection")
            }

            BatteryOptimizationCard()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showEditDialog) {
        EditConnectionDialog(
            initialDashboardUrl = storedDashboardUrl,
            initialDashboardToken = storedDashboardToken,
            initialPluginUrl = storedPluginUrl,
            initialPluginToken = storedPluginToken,
            onDismiss = { showEditDialog = false },
            onSave = { dashboardUrl, dashboardToken, pluginUrl, pluginToken ->
                // Compare TRIMMED user input against the stored value (which is
                // always trimmed by the setters). Otherwise a paste with stray
                // whitespace would trigger a needless reconnect even though the
                // effective URL didn't move.
                val dashboardChanged = dashboardUrl.trim() != storedDashboardUrl ||
                    dashboardToken.trim() != storedDashboardToken
                val pluginChanged = pluginUrl.trim() != storedPluginUrl ||
                    pluginToken.trim() != storedPluginToken
                // Persist and flip enabled flags by presence-of-creds.
                marmaladeRuntime.setDashboardUrl(dashboardUrl)
                marmaladeRuntime.setDashboardToken(dashboardToken)
                marmaladeRuntime.setDashboardEnabled(
                    dashboardUrl.isNotBlank() && dashboardToken.isNotBlank(),
                )
                marmaladeRuntime.setMarmaladeUrl(pluginUrl)
                marmaladeRuntime.setMarmaladeToken(pluginToken)
                marmaladeRuntime.setMarmaladeEnabled(
                    pluginUrl.isNotBlank() && pluginToken.isNotBlank(),
                )
                // Only cycle the socket whose creds changed — don't drop a
                // working plugin connection (e.g. an in-flight node.invoke)
                // when the user only rotated the dashboard token.
                if (dashboardChanged) marmaladeRuntime.reconnectDashboard()
                if (pluginChanged) marmaladeRuntime.reconnectPlugin()
                showEditDialog = false
            },
        )
    }
}

/**
 * Read-only summary of one connection: colored status dot, the endpoint URL,
 * and a masked token. Tokens are never shown in full on the resting screen —
 * only the last 4 chars, enough to confirm which token is loaded.
 */
@Composable
private fun ConnectionCard(
    title: String,
    description: String,
    phase: ConnectionPhase,
    url: String,
    token: String,
    notConfiguredText: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(phase = phase)
            }
            Text(
                text = description,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (url.isBlank() && token.isBlank()) {
                Text(
                    text = notConfiguredText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FieldReadout(label = "URL", value = url.ifBlank { "—" })
                FieldReadout(label = "Token", value = maskToken(token))
            }
        }
    }
}

@Composable
private fun FieldReadout(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusPill(phase: ConnectionPhase) {
    val colors = MaterialTheme.marmaladeColors
    val (text, dot) = when (phase) {
        ConnectionPhase.NotConfigured -> "off" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionPhase.Connecting -> "connecting" to colors.statusConnecting
        ConnectionPhase.Connected -> "connected" to colors.statusConnected
        ConnectionPhase.Disconnected -> "disconnected" to colors.statusDisconnected
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dot, CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditConnectionDialog(
    initialDashboardUrl: String,
    initialDashboardToken: String,
    initialPluginUrl: String,
    initialPluginToken: String,
    onDismiss: () -> Unit,
    onSave: (dashboardUrl: String, dashboardToken: String, pluginUrl: String, pluginToken: String) -> Unit,
) {
    var dashboardUrl by remember { mutableStateOf(initialDashboardUrl) }
    var dashboardToken by remember { mutableStateOf(initialDashboardToken) }
    var pluginUrl by remember { mutableStateOf(initialPluginUrl) }
    var pluginToken by remember { mutableStateOf(initialPluginToken) }
    var scanError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit connection") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScanPairingQrButton(
                    onScanned = { payload ->
                        scanError = null
                        dashboardUrl = payload.url
                        dashboardToken = payload.token
                        payload.pluginUrl?.let { pluginUrl = it }
                        payload.pluginToken?.let { pluginToken = it }
                    },
                    onError = { scanError = it },
                )
                scanError?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "Dashboard",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = dashboardUrl,
                    onValueChange = { dashboardUrl = it },
                    label = { Text("Dashboard URL") },
                    placeholder = { Text("http://host:9119") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ConnectionHints.localhostGuidance(dashboardUrl)?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = dashboardToken,
                    onValueChange = { dashboardToken = it },
                    label = { Text("Dashboard session token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Android plugin (optional)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pluginUrl,
                    onValueChange = { pluginUrl = it },
                    label = { Text("Plugin URL") },
                    placeholder = { Text("http://host:9211") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pluginToken,
                    onValueChange = { pluginToken = it },
                    label = { Text("Plugin pairing token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(dashboardUrl, dashboardToken, pluginUrl, pluginToken) },
                enabled = dashboardUrl.isNotBlank() && dashboardToken.isNotBlank(),
            ) {
                Text("Save & reconnect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Doze reliability (hardening.md #2): the always-on WS lives in the sticky
 * NodeForegroundService, but on some OEMs Doze still throttles its socket.
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is declared in the manifest; this card
 * is the only place that actually requests it. Status refreshes ON_RESUME
 * (the grant happens in a system dialog outside the app).
 */
@Composable
private fun BatteryOptimizationCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Background reliability", style = MaterialTheme.typography.titleMedium)
            Text(
                if (exempt) {
                    "Battery optimization is off for Marmalade — background " +
                        "notifications should survive Doze."
                } else {
                    "Battery optimization can delay or drop background message " +
                        "notifications while the phone sleeps. Exempting Marmalade " +
                        "keeps the connection alive."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!exempt) {
                OutlinedButton(onClick = {
                    // The direct-request intent needs the (declared) permission;
                    // some OEM builds reject it — fall back to the settings list.
                    val direct = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    )
                    try {
                        context.startActivity(direct)
                    } catch (_: android.content.ActivityNotFoundException) {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    }
                }) { Text("Disable battery optimization") }
            }
        }
    }
}

/**
 * Mask a secret to its last 4 characters (e.g. "••••Pn9C"). Short or blank
 * tokens collapse to a fixed dot run so length isn't leaked.
 */
private fun maskToken(token: String): String = when {
    token.isBlank() -> "—"
    token.length <= 4 -> "••••"
    else -> "••••" + token.takeLast(4)
}
