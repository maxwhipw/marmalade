package app.marmalade.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.android.rpc.DevicePairingHost
import app.marmalade.android.ui.rememberMarmaladeRpc
import app.marmalade.android.rpc.types.DeviceInfo
import app.marmalade.android.ui.PortraitCaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.DateFormat
import java.util.Date

/**
 * Devices & pairing (Settings → Devices) — REBUILT 2026-07-12 against the
 * marmaladed daemon's M2 device pairing. Two halves:
 *
 *  - **Pair this phone**: scan the terminal QR from `marmalade pair` (or
 *    paste the setup code it prints) → pairing.claim → the daemon's
 *    per-device bearer token replaces the connection config.
 *  - **Paired devices**: the device.list roster with live-connection dots
 *    and per-device revoke (device.revoke drops the device's sockets NOW).
 *
 * The previous PairingScreen was fork-gateway messaging-DM approval UI
 * (web PairingPage parity) — replaced, not revived, per the hardening plan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onBack: () -> Unit,
    // The VM lives in :shared: the roster half rides the shared RPC composition
    // local, the claim half rides the DevicePairingHost port the runtime
    // implements. The screen itself stays in :app — the QR scanner is an
    // Android activity-result launcher.
    host: DevicePairingHost,
    viewModel: PairingViewModel = viewModel(
        factory = PairingViewModel.factory(rememberMarmaladeRpc(), host),
    ),
) {
    val claimState by viewModel.claimState.collectAsState()
    val devicesState by viewModel.devicesState.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var setupCodeDraft by remember { mutableStateOf("") }
    var revokeTarget by remember { mutableStateOf<DeviceInfo?>(null) }

    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeActionMessage()
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.claim(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::loadDevices) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionHeader("Pair this phone")
            Text(
                text = "Run `marmalade pair` on the daemon host, then scan the QR " +
                    "it prints (or paste the setup code). The code is single-use " +
                    "and expires after 10 minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            val claiming = claimState is ClaimState.Claiming
            OutlinedButton(
                onClick = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setCaptureActivity(PortraitCaptureActivity::class.java)
                            .setBeepEnabled(false)
                            .setPrompt("Scan the QR from `marmalade pair`"),
                    )
                },
                enabled = !claiming,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan pairing QR")
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = setupCodeDraft,
                onValueChange = { setupCodeDraft = it },
                label = { Text("Setup code") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                enabled = !claiming,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.claim(setupCodeDraft) },
                    enabled = setupCodeDraft.isNotBlank() && !claiming,
                ) { Text("Pair") }
                if (claiming) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Claiming…", style = MaterialTheme.typography.bodySmall)
                }
            }

            when (val claim = claimState) {
                is ClaimState.Paired -> {
                    Text(
                        text = "Paired as ${claim.deviceId} ✓ — connection saved.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                is ClaimState.Error -> {
                    Text(
                        text = claim.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader("Paired devices")
            when (val devices = devicesState) {
                is DevicesState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is DevicesState.Error -> {
                    Text(
                        text = friendlyErrorText(devices.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                is DevicesState.Loaded -> {
                    if (devices.devices.isEmpty()) {
                        Text(
                            text = "No devices on the roster yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        devices.devices.forEach { device ->
                            DeviceRow(
                                device = device,
                                isThisDevice = device.device_id == viewModel.thisDeviceId,
                                onRevoke = { revokeTarget = device },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    revokeTarget?.let { device ->
        val isSelf = device.device_id == viewModel.thisDeviceId
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text(if (isSelf) "Revoke THIS phone?" else "Revoke device?") },
            text = {
                Text(
                    if (isSelf) {
                        "This revokes the phone's own token and disconnects it immediately. " +
                            "You'll need a new setup code to pair again."
                    } else {
                        "${device.device_id} loses access now — its live connections drop immediately."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.revoke(device.device_id)
                    revokeTarget = null
                }) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    isThisDevice: Boolean,
    onRevoke: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.device_id + if (isThisDevice) " (this phone)" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val details = buildList {
                device.platform?.let { add(it) }
                add(if (device.connected) "connected" else "offline")
                if (!device.paired) add("unpaired (roster only)")
                device.last_seen?.let { add("seen ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}") }
            }
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (device.paired) {
            FilledTonalButton(onClick = onRevoke) { Text("Revoke") }
        }
    }
}
