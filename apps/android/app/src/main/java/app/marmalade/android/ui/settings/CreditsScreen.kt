package app.marmalade.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.marmalade.android.R
import app.marmalade.android.data.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.credits_title)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.credits_back_description))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Projects & Code References
            Text(
                "Projects & Code",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Marmalade is built on and incorporates code from these open source projects.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            val codeCredits = listOf(
                Triple(
                    "OpenClaw Assistant",
                    "MIT License",
                    "Fork base — connection protocol, VoiceInteractionService, and chat infrastructure.",
                ),
                Triple(
                    "Sherpa-ONNX (k2-fsa)",
                    "Apache License 2.0",
                    "On-device speech recognition engine. Whisper simulated streaming implementation ported from the SherpaOnnxSimulateStreamingAsr Android example.",
                ),
                Triple(
                    "Distil-Whisper (distil-small.en)",
                    "MIT License",
                    "Bundled on-device speech-recognition model — distilled English Whisper (Hugging Face), from OpenAI's Whisper. ONNX build by k2-fsa (csukuangfj).",
                ),
                Triple(
                    "openWakeWord",
                    "Apache License 2.0",
                    "Wake word detection model and architecture.",
                ),
            )

            codeCredits.forEach { (name, license, description) ->
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    license,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Open Source Libraries
            Text(
                stringResource(R.string.credits_oss_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                stringResource(R.string.credits_oss_description),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            val licenses = listOf(
                "Android Jetpack" to "Apache License 2.0",
                "Compose" to "Apache License 2.0",
                "Kotlin" to "Apache License 2.0",
                "OkHttp" to "Apache License 2.0",
                "Bouncy Castle" to "Bouncy Castle License",
                "Sherpa-ONNX" to "Apache License 2.0",
                "Room" to "Apache License 2.0",
            )

            licenses.forEach { (name, license) ->
                Text(
                    "\u2022 $name ($license)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
