package app.marmalade.android.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.service.ModelDownloadService
import app.marmalade.android.speech.ModelStatus
import app.marmalade.android.speech.STTModel
import app.marmalade.android.speech.STTModelManager

/**
 * Speech Recognition settings: active model info, VAD sensitivity slider,
 * and model picker cards with download/select/delete actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechRecognitionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.getInstance(context) }
    val modelManager = remember { STTModelManager.getInstance(context) }

    var vadSensitivity by remember { mutableFloatStateOf(settings.vadSensitivity) }
    var keepLoaded by remember { mutableStateOf(settings.keepSTTLoaded) }

    // Observe download progress and errors for live progress bars
    val downloadProgress by modelManager.downloadProgressFlow.collectAsStateWithLifecycle()
    val downloadErrors by modelManager.downloadErrors.collectAsStateWithLifecycle()

    // Trigger recomposition when model status changes (activate/delete)
    var refreshTrigger by remember { mutableIntStateOf(0) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Speech Recognition") },
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
            // -- VAD Sensitivity section --
            SettingsSectionHeader("VAD Sensitivity")

            val sensitivityLabel = when {
                vadSensitivity < 0.33f -> "Quick response"
                vadSensitivity > 0.66f -> "Forgiving pauses"
                else -> "Balanced"
            }

            SettingsSliderRow(
                title = "Pause tolerance",
                subtitle = sensitivityLabel,
                value = vadSensitivity,
                valueRange = 0f..1f,
                steps = 0,
                valueLabel = "%.0f%%".format(vadSensitivity * 100),
                onValueChange = { vadSensitivity = it },
                onValueChangeFinished = {
                    settings.vadSensitivity = vadSensitivity
                },
            )

            // -- Performance section --
            SettingsSectionHeader("Performance")

            SettingsToggleRow(
                title = "Keep model loaded",
                subtitle = "Preloads STT engine on app start for faster voice response",
                checked = keepLoaded,
                onCheckedChange = {
                    keepLoaded = it
                    settings.keepSTTLoaded = it
                },
            )

            // -- Models section --
            SettingsSectionHeader("Models")

            for (model in modelManager.models) {
                val status = remember(refreshTrigger, downloadProgress, downloadErrors) {
                    modelManager.getModelStatus(model.id)
                }

                STTModelCard(
                    model = model,
                    status = status,
                    downloadProgress = downloadProgress[model.id],
                    onDownload = {
                        // Immediate UI feedback before service starts
                        modelManager.updateDownloadProgress(model.id, 0f)
                        ModelDownloadService.start(context, model.id)
                    },
                    onActivate = {
                        modelManager.activateModel(model.id)
                        refreshTrigger++
                        Toast.makeText(
                            context,
                            "Model activated. Changes take effect on next voice session.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onDelete = {
                        modelManager.deleteModel(model.id)
                        refreshTrigger++
                    },
                    onRetry = {
                        modelManager.clearDownloadError(model.id)
                        modelManager.updateDownloadProgress(model.id, 0f)
                        ModelDownloadService.start(context, model.id)
                    },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Changes take effect on next voice session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Card displaying an STT model with its status, size, and action buttons.
 */
@Composable
private fun STTModelCard(
    model: STTModel,
    status: ModelStatus,
    downloadProgress: Float?,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Title row: name + status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )

                StatusBadge(status)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Description
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Size
            Text(
                text = formatBytes(model.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Download progress bar
            if (status is ModelStatus.Downloading || downloadProgress != null) {
                Spacer(modifier = Modifier.height(8.dp))

                val progress = downloadProgress ?: (status as? ModelStatus.Downloading)?.progress ?: 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // Action buttons row
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Delete button for non-bundled downloaded models
                if (!model.isBundled && (status is ModelStatus.Downloaded || status is ModelStatus.Active)) {
                    if (status !is ModelStatus.Active) {
                        TextButton(onClick = onDelete) {
                            Text(
                                text = "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }

                when (status) {
                    is ModelStatus.Active -> {
                        // No action needed — already active
                    }

                    is ModelStatus.Downloaded -> {
                        Button(onClick = onActivate) {
                            Text("Select")
                        }
                    }

                    is ModelStatus.NotDownloaded -> {
                        Button(onClick = onDownload) {
                            Text("Download")
                        }
                    }

                    is ModelStatus.Downloading -> {
                        // No button during download — progress bar shown above
                    }

                    is ModelStatus.Error -> {
                        OutlinedButton(
                            onClick = onRetry,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Status badge showing model state with appropriate color.
 */
@Composable
private fun StatusBadge(status: ModelStatus) {
    when (status) {
        is ModelStatus.Active -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.height(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Active",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        is ModelStatus.Downloaded -> {
            // No badge — the "Select" button makes the state clear
        }

        is ModelStatus.Error -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.height(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Error",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        else -> {
            // NotDownloaded, Downloading — no badge (progress bar handles Downloading)
        }
    }
}

/**
 * Format byte size to human-readable string (e.g., "117 MB", "374 MB").
 */
internal fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes.toFloat() / (1024 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%d MB".format(bytes / (1024 * 1024))
        bytes >= 1024 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }
}
