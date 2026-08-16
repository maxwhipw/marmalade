package app.marmalade.android.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Permission entry definition for display in the permissions step.
 */
private data class PermissionEntry(
    val permission: String,
    val icon: ImageVector,
    val name: String,
    val description: String,
)

/**
 * Onboarding Step 2: Permissions.
 *
 * Shows permission cards with per-permission Grant buttons (ONBOARD-02).
 * Grant All button at bottom (ONBOARD-03).
 * Buttons disappear when all granted (ONBOARD-04).
 * NO SMS permission (ONBOARD-05).
 */
@Composable
fun PermissionsStep(
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Build the permission list -- NO SMS (ONBOARD-05)
    val entries = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    PermissionEntry(
                        permission = Manifest.permission.POST_NOTIFICATIONS,
                        icon = Icons.Default.Notifications,
                        name = "Notifications",
                        description = "Receive chat notifications",
                    )
                )
            }
            add(
                PermissionEntry(
                    permission = Manifest.permission.CAMERA,
                    icon = Icons.Default.Camera,
                    name = "Camera",
                    description = "Scan QR codes and take photos",
                )
            )
            add(
                PermissionEntry(
                    permission = Manifest.permission.RECORD_AUDIO,
                    icon = Icons.Default.Mic,
                    name = "Microphone",
                    description = "Voice assistant and speech recognition",
                )
            )
            add(
                PermissionEntry(
                    permission = Manifest.permission.ACTIVITY_RECOGNITION,
                    icon = Icons.AutoMirrored.Filled.DirectionsRun,
                    name = "Activity Recognition",
                    description = "Motion-based features",
                )
            )
        }
    }

    // Track permission status
    val permissionStatus = remember {
        mutableStateMapOf<String, Boolean>().apply {
            entries.forEach { entry ->
                put(
                    entry.permission,
                    ContextCompat.checkSelfPermission(context, entry.permission) ==
                        PackageManager.PERMISSION_GRANTED,
                )
            }
        }
    }

    // Re-check on resume (user may have granted in Settings)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        entries.forEach { entry ->
            permissionStatus[entry.permission] =
                ContextCompat.checkSelfPermission(context, entry.permission) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    // Per-permission launchers -- using a single-permission approach
    // Each entry gets its own launcher via a composable loop
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Permissions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Marmalade needs a few permissions to work best. You can grant them now or later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))

        val allGranted = entries.all { permissionStatus[it.permission] == true }

        entries.forEach { entry ->
            val isGranted = permissionStatus[entry.permission] == true

            PermissionCard(
                entry = entry,
                isGranted = isGranted,
                showGrantButton = !allGranted,
                onGranted = { granted ->
                    permissionStatus[entry.permission] = granted
                },
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Battery optimization section ────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(modifier = Modifier.height(12.dp))

        val powerManager = remember {
            context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        }
        var isBatteryOptimized by remember {
            mutableStateOf(!powerManager.isIgnoringBatteryOptimizations(context.packageName))
        }

        // Re-check on resume (user may have changed it in system settings)
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            isBatteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (!isBatteryOptimized) MaterialTheme.marmaladeColors.toolSuccess
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Background Activity",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Keep Marmalade running for voice & notifications",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (!isBatteryOptimized) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Unrestricted",
                        tint = MaterialTheme.marmaladeColors.toolSuccess,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    FilledTonalButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        Text(
                            text = "Allow",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Link to dontkillmyapp.com for device-specific instructions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com"))
                    context.startActivity(intent)
                }
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = "Some devices need extra steps to keep apps running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "See dontkillmyapp.com for your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (allGranted) {
            // All granted checkmark message (ONBOARD-04)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.marmaladeColors.toolSuccess,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "All permissions granted",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.marmaladeColors.toolSuccess,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            // Grant All button (ONBOARD-03)
            val grantAllLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { results ->
                results.forEach { (perm, granted) ->
                    permissionStatus[perm] = granted
                }
            }

            FilledTonalButton(
                onClick = {
                    val pending = entries
                        .filter { permissionStatus[it.permission] != true }
                        .map { it.permission }
                        .toTypedArray()
                    if (pending.isNotEmpty()) {
                        grantAllLauncher.launch(pending)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Grant All")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Continue button -- always visible
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = "Continue",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Skip text button
        TextButton(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Skip for now",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Single permission card with icon, name, description, and Grant button or checkmark.
 */
@Composable
private fun PermissionCard(
    entry: PermissionEntry,
    isGranted: Boolean,
    showGrantButton: Boolean,
    onGranted: (Boolean) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onGranted(granted)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (isGranted) MaterialTheme.marmaladeColors.toolSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Name + description
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Grant button or checkmark
            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Granted",
                    tint = MaterialTheme.marmaladeColors.toolSuccess,
                    modifier = Modifier.size(24.dp),
                )
            } else if (showGrantButton) {
                FilledTonalButton(
                    onClick = { launcher.launch(entry.permission) },
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(
                        text = "Grant",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
