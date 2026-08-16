package app.marmalade.android.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.PortraitCaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pairing-QR payload. There is NO upstream device-pairing QR (verified
 * 2026-07-03: dashboard_auth has ws-tickets for already-authenticated
 * browsers only, and the web PairingPage is messaging-DM approval) — this
 * format is ours. It encodes exactly what the user would otherwise paste
 * into GatewayStep / ConnectionSettingsScreen:
 *
 * ```json
 * {"v":1,"url":"http://host:9119","token":"…",
 *  "pluginUrl":"http://host:9211","pluginToken":"…"}
 * ```
 *
 * pluginUrl/pluginToken are optional. Any QR generator works — the QR
 * carries a LIVE bearer token, so treat the rendered code like the token
 * itself (it also goes stale when the gateway restarts, since the token
 * rotates).
 */
@Serializable
data class PairingQrPayload(
    val v: Int = 1,
    val url: String,
    val token: String,
    val pluginUrl: String? = null,
    val pluginToken: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse scanned QR contents; failure carries a human-readable message. */
        fun parse(raw: String): Result<PairingQrPayload> = runCatching {
            val payload = json.decodeFromString(serializer(), raw)
            require(payload.v == 1) { "Unsupported pairing QR version ${payload.v}" }
            require(payload.url.isNotBlank()) { "Pairing QR is missing the dashboard URL" }
            require(payload.token.isNotBlank()) { "Pairing QR is missing the session token" }
            payload
        }.recoverCatching { t ->
            // Re-throw require() messages as-is; JSON decode errors get a
            // friendlier line than a serializer stack message.
            if (t is IllegalArgumentException && t.message?.startsWith("Pairing QR") == true) throw t
            if (t is IllegalArgumentException && t.message?.startsWith("Unsupported") == true) throw t
            throw IllegalArgumentException("Not a Marmalade pairing QR code")
        }
    }
}

/**
 * "Scan pairing QR" button: launches the ZXing capture flow (wiring
 * PortraitCaptureActivity — dead-code audit item W1) and reports a parsed
 * payload or an error message. CaptureManager owns the CAMERA runtime
 * permission prompt.
 */
@Composable
fun ScanPairingQrButton(
    onScanned: (PairingQrPayload) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult // cancelled
        PairingQrPayload.parse(contents)
            .onSuccess(onScanned)
            .onFailure { onError(it.message ?: "Unrecognized QR code") }
    }
    OutlinedButton(
        onClick = {
            launcher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setCaptureActivity(PortraitCaptureActivity::class.java)
                    .setBeepEnabled(false)
                    .setPrompt("Scan the Marmalade pairing QR"),
            )
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Default.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Scan pairing QR")
    }
}
