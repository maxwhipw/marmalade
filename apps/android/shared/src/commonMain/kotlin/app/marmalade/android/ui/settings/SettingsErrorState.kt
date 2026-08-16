package app.marmalade.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The shared "couldn't load it" surface for settings sub-screens, and the
 * copy translation behind it.
 *
 * Split out of `SettingsComponents.kt` (which stays in `:app` — it holds the
 * row/card widgets and a clipboard helper that needs an Android `Context`)
 * so the settings screens in `:shared` can use it. Package is preserved, so
 * the `:app` screens that still call it are unchanged.
 */

/**
 * Translate a transport/RPC failure message into user-facing copy.
 * Raw strings like "gateway not connected (state=Error)" or
 * "session.list failed (rpc -32001): authentication required…" are
 * developer diagnostics — keep them for logcat, not for the screen.
 */
fun friendlyErrorText(raw: String?): String {
    val msg = raw.orEmpty()
    return when {
        msg.contains("authentication", ignoreCase = true) ||
            msg.contains("pairing.claim") ->
            "This phone isn't paired with your Marmalade daemon yet. " +
                "Pair it under Settings → Devices."
        msg.contains("gateway not connected", ignoreCase = true) ||
            msg.contains("Failed to connect", ignoreCase = true) ||
            msg.contains("Unable to resolve host", ignoreCase = true) ->
            "Marmalade can't reach your daemon right now. " +
                "Check that it's running and that this phone is on the same network or tailnet."
        msg.contains("timeout", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ->
            "The daemon didn't respond in time. It may be busy — try again."
        msg.isBlank() -> "Something went wrong. Try again."
        else -> msg
    }
}

/**
 * Full-screen friendly error state: cloud-off icon, a short headline,
 * plain-language body derived from the raw error, and a Retry button.
 * Shared by every settings sub-screen that loads from the daemon.
 */
@Composable
fun SettingsErrorState(
    headline: String,
    rawError: String?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = friendlyErrorText(rawError),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
