package app.marmalade.android.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.node.MarmaladeRuntime

/**
 * Waits for the [MarmaladeRuntime] WebSocket to reach [isConnected] =
 * true (driven by [GatewayStep] saving creds + calling
 * [MarmaladeRuntime.connectMarmalade]). On success calls [onConnected].
 * Surfaces transport errors via [onError] and lets the user back up
 * to retry from [GatewayStep].
 */
@Composable
fun PairingStep(
    marmaladeRuntime: MarmaladeRuntime,
    onConnected: () -> Unit,
    onBack: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected by marmaladeRuntime.isConnected.collectAsStateWithLifecycle()
    val statusText by marmaladeRuntime.connectionStatus.collectAsStateWithLifecycle()
    // Show the spinner only while we're actually trying — "Disconnected" or
    // "Connection failed" should kill the indeterminate progress so the user
    // isn't told to wait under text that says it failed.
    val isTrying = statusText.equals("Connecting…", ignoreCase = true) ||
        statusText.equals("Offline", ignoreCase = true)

    LaunchedEffect(isConnected) {
        if (isConnected) onConnected()
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = if (isTrying) "Connecting…" else statusText.ifBlank { "Not connected" },
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isTrying) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = if (isTrying) "Reaching marmalade-agent…" else
                "Tap Retry to try again, or go back to fix the connection details.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                marmaladeRuntime.connectMarmalade()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Retry")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back to connection details")
        }
    }
}
