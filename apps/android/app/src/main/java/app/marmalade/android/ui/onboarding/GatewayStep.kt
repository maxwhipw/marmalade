package app.marmalade.android.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.node.MarmaladeRuntime
import app.marmalade.android.rpc.ConnectionHints

/**
 * Onboarding pairing for marmalade-agent. Two connections:
 *
 *  - **Dashboard** (required): `/api/ws` on the gateway's web dashboard
 *    (port 9119 by default). Same surface the desktop + web clients use.
 *    Token is `window.__MARMALADE_SESSION_TOKEN__` from the dashboard's
 *    index.html.
 *
 *  - **Plugin** (optional): the marmalade-android Python plugin's WS.
 *    Only needed if the phone should expose `node.invoke.*` device tools
 *    (alarms, intents, camera) to the agent. Token is the opaque pairing
 *    secret from `~/.marmalade/android-devices.yaml`.
 *
 *  The previous single-URL flow proxied chat through the plugin, which
 *  pulled the gateway's `_notify_long_running` heartbeat into the message
 *  stream as bubble noise. Connecting to the dashboard directly matches
 *  what desktop + web do and avoids that path entirely.
 */
@Composable
fun GatewayStep(
    marmaladeRuntime: MarmaladeRuntime,
    onConnectInitiated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val storedDashboardUrl by marmaladeRuntime.dashboardUrl.collectAsStateWithLifecycle()
    val storedDashboardToken by marmaladeRuntime.dashboardToken.collectAsStateWithLifecycle()
    val storedPluginUrl by marmaladeRuntime.marmaladeUrl.collectAsStateWithLifecycle()
    val storedPluginToken by marmaladeRuntime.marmaladeToken.collectAsStateWithLifecycle()

    // rememberSaveable so typed-but-not-yet-saved values survive Back/
    // Forward navigation through the onboarding stack. Plain `remember`
    // drops state on composition destruction, which is exactly what a
    // back-press causes.
    var dashboardUrl by rememberSaveable(storedDashboardUrl) { mutableStateOf(storedDashboardUrl) }
    var dashboardToken by rememberSaveable(storedDashboardToken) { mutableStateOf(storedDashboardToken) }
    var pluginUrl by rememberSaveable(storedPluginUrl) { mutableStateOf(storedPluginUrl) }
    var pluginToken by rememberSaveable(storedPluginToken) { mutableStateOf(storedPluginToken) }

    // Reveal the plugin section if the user already has plugin creds (re-pair)
    // or explicitly opens it; keep it folded for the common first-run case.
    var showPlugin by rememberSaveable(storedPluginUrl) {
        mutableStateOf(storedPluginUrl.isNotBlank() || storedPluginToken.isNotBlank())
    }
    var scanError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Connect to marmalade-agent",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Paste your dashboard URL and session token. The token is " +
                "the value of window.__MARMALADE_SESSION_TOKEN__ in the " +
                "dashboard's index.html.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Camera shortcut for the paste flow: the QR carries the same
        // url/token JSON (see PairingQrPayload). Fills the fields; the user
        // still taps Connect.
        ScanPairingQrButton(
            onScanned = { payload ->
                scanError = null
                dashboardUrl = payload.url
                dashboardToken = payload.token
                payload.pluginUrl?.let { pluginUrl = it }
                payload.pluginToken?.let { pluginToken = it }
                if (!payload.pluginUrl.isNullOrBlank() || !payload.pluginToken.isNullOrBlank()) {
                    showPlugin = true
                }
            },
            onError = { scanError = it },
        )
        scanError?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = dashboardUrl,
            onValueChange = { dashboardUrl = it },
            label = { Text("Dashboard URL") },
            placeholder = { Text("http://host:9119") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ConnectionHints.localhostGuidance(dashboardUrl)?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = dashboardToken,
            onValueChange = { dashboardToken = it },
            label = { Text("Dashboard session token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (showPlugin) {
            Text(
                text = "Optional: Android plugin",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Only needed if you want the agent to fire Android " +
                    "tools (alarms, intents, camera) on this device.",
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = pluginUrl,
                onValueChange = { pluginUrl = it },
                label = { Text("Plugin URL") },
                placeholder = { Text("http://host:9211") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = pluginToken,
                onValueChange = { pluginToken = it },
                label = { Text("Plugin pairing token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            TextButton(
                onClick = { showPlugin = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add Android plugin (optional)")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                marmaladeRuntime.setDashboardUrl(dashboardUrl)
                marmaladeRuntime.setDashboardToken(dashboardToken)
                marmaladeRuntime.setDashboardEnabled(true)
                // Always write plugin fields + flip the flag based on
                // presence-of-creds so a cleared plugin section actually
                // disables the socket, instead of letting a stale enabled
                // flag survive a re-onboard.
                marmaladeRuntime.setMarmaladeUrl(pluginUrl)
                marmaladeRuntime.setMarmaladeToken(pluginToken)
                marmaladeRuntime.setMarmaladeEnabled(
                    pluginUrl.isNotBlank() && pluginToken.isNotBlank(),
                )
                marmaladeRuntime.connectMarmalade()
                onConnectInitiated()
            },
            enabled = dashboardUrl.isNotBlank() && dashboardToken.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
    }
}
