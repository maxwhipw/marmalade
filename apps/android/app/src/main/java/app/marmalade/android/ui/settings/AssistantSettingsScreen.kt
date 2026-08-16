package app.marmalade.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.service.HotwordService

/**
 * Assistant settings: wake word, TTS, conversation mode, patient listening,
 * and voice session routing.
 *
 * Split from the former VoiceSettingsScreen. All toggle/slider writes go
 * through SettingsRepository (EncryptedSharedPreferences). Sliders write
 * only on drag-end (debounce pattern).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.getInstance(context) }

    // Local state mirrors repository for instant UI feedback
    var hotwordEnabled by remember { mutableStateOf(settings.hotwordEnabled) }
    var wakeWordPreset by remember { mutableStateOf(settings.wakeWordPreset) }
    var customWakeWord by remember { mutableStateOf(settings.customWakeWord) }
    var sensitivityValue by remember {
        mutableFloatStateOf(settings.getWakeWordThreshold())
    }
    var ttsSpeed by remember { mutableFloatStateOf(settings.ttsSpeed) }
    var patientListeningEnabled by remember { mutableStateOf(settings.patientListeningEnabled) }
    // Termination words are stored as a comma-separated string in SettingsRepository.
    // UI-side we keep a parsed list for chip rendering; writes go back through the CSV field.
    var terminationWordsList by remember {
        mutableStateOf(settings.getTerminationWordsList())
    }
    var silenceTimeout by remember { mutableLongStateOf(settings.speechSilenceTimeout) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
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
            // -- Wake Word section --
            SettingsSectionHeader("Wake Word")

            SettingsToggleRow(
                title = "Enabled",
                checked = hotwordEnabled,
                onCheckedChange = { enabled ->
                    hotwordEnabled = enabled
                    settings.hotwordEnabled = enabled
                    // Start or stop HotwordService immediately
                    if (enabled) {
                        HotwordService.start(context)
                    } else {
                        HotwordService.stop(context)
                    }
                },
            )

            val wakeWordOptions = remember {
                SettingsRepository.BUILTIN_WAKE_WORD_PRESETS.map { it.displayName to it.key } +
                    ("Custom (dev only)" to SettingsRepository.WAKE_WORD_CUSTOM)
            }

            SettingsDropdownRow(
                title = "Wake word",
                subtitle = null,
                currentValue = wakeWordPreset,
                options = wakeWordOptions,
                onSelect = { value ->
                    wakeWordPreset = value
                    settings.wakeWordPreset = value
                    // Reload the engine with the new model. Mirrors the
                    // sensitivity slider below.
                    if (hotwordEnabled) {
                        HotwordService.stop(context)
                        HotwordService.start(context)
                    }
                },
            )

            if (wakeWordPreset == SettingsRepository.WAKE_WORD_CUSTOM) {
                Text(
                    text = "Custom requires a matching <name>.onnx in app/src/main/assets/ at build time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                OutlinedTextField(
                    value = customWakeWord,
                    onValueChange = { text ->
                        customWakeWord = text
                        settings.customWakeWord = text
                    },
                    label = { Text("Custom wake word") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Value IS the openWakeWord detection threshold: left = 0.3 (more
            // sensitive), right = 0.9 (stricter). Subtitle boundaries are the
            // midpoints between the high/medium/low presets (0.3/0.5/0.7), so
            // each label is active when the value sits closest to its preset.
            val wakeSensitivityLabel = when {
                sensitivityValue < 0.4f -> "Sensitive"
                sensitivityValue > 0.6f -> "Strict"
                else -> "Balanced"
            }

            SettingsSliderRow(
                title = "Sensitivity",
                subtitle = wakeSensitivityLabel,
                value = sensitivityValue,
                valueRange = SettingsRepository.MIN_WAKE_WORD_THRESHOLD..SettingsRepository.MAX_WAKE_WORD_THRESHOLD,
                steps = 0,
                valueLabel = "%.2f".format(sensitivityValue),
                onValueChange = { sensitivityValue = it },
                onValueChangeFinished = {
                    settings.setWakeWordThreshold(sensitivityValue)
                    // Restart HotwordService to pick up new threshold
                    if (hotwordEnabled) {
                        HotwordService.stop(context)
                        HotwordService.start(context)
                    }
                },
            )

            // -- Text-to-Speech section --
            SettingsSectionHeader("Text-to-Speech")

            // The assistant always speaks its replies (no on/off toggle);
            // chat-tab auto-speak is controlled by the chat top bar.
            SettingsSliderRow(
                title = "TTS Speed",
                subtitle = null,
                value = ttsSpeed,
                valueRange = 0.5f..2.0f,
                steps = 5,
                valueLabel = "${"%.1f".format(ttsSpeed)}x",
                onValueChange = { ttsSpeed = it },
                onValueChangeFinished = {
                    settings.ttsSpeed = ttsSpeed
                },
            )

            // -- Conversation section --
            SettingsSectionHeader("Conversation")

            SettingsToggleRow(
                title = "Patient listening",
                checked = patientListeningEnabled,
                subtitle = "Auto-restart on silence, accumulate text",
                onCheckedChange = { enabled ->
                    patientListeningEnabled = enabled
                    settings.patientListeningEnabled = enabled
                },
            )

            TerminationWordsEditor(
                words = terminationWordsList,
                onAdd = { raw ->
                    val word = raw.trim()
                    if (word.isEmpty()) return@TerminationWordsEditor
                    // Case-insensitive de-dupe (parsing lowercases; keep display casing of first add)
                    val existsCi = terminationWordsList.any { it.equals(word, ignoreCase = true) }
                    if (existsCi) return@TerminationWordsEditor
                    val updated = terminationWordsList + word
                    terminationWordsList = updated
                    settings.terminationWords = updated.joinToString(",")
                },
                onRemove = { word ->
                    val updated = terminationWordsList.filterNot { it == word }
                    if (updated.size == terminationWordsList.size) return@TerminationWordsEditor
                    terminationWordsList = updated
                    settings.terminationWords = updated.joinToString(",")
                },
            )

            // -- Advanced section --
            SettingsSectionHeader("Advanced")

            var silenceSliderValue by remember {
                mutableFloatStateOf(silenceTimeout.toFloat())
            }

            SettingsSliderRow(
                title = "Silence timeout",
                subtitle = null,
                value = silenceSliderValue,
                valueRange = 2000f..10000f,
                steps = 7,
                valueLabel = "${"%.0f".format(silenceSliderValue / 1000f)}s",
                onValueChange = { silenceSliderValue = it },
                onValueChangeFinished = {
                    silenceTimeout = silenceSliderValue.toLong()
                    settings.speechSilenceTimeout = silenceTimeout
                },
            )

            // The Home tab and voice both open THE daemon-managed main
            // session (session.main) — it is created and owned server-side,
            // always warm, and never deleted. There is no user-selectable
            // "assistant session" any more (assistant plan 2026-07-19: main is
            // daemon-owned), so this is a read-only note rather than a picker.
            Text(
                text = "Assistant session",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            Text(
                text = "Home and voice always open your main Marmalade session — a " +
                    "single always-on assistant chat managed by the daemon. Use its " +
                    "chat menu to clear it or switch its model.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Chip-based editor for termination words. Each word renders as a Material 3
 * [InputChip] with a trailing close icon that removes the word. A trailing
 * circular [FilledIconButton] opens a dialog to add a new word.
 *
 * Storage is unchanged — callers own the CSV serialization via [onAdd]/[onRemove].
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TerminationWordsEditor(
    words: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Termination words",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Words that end patient listening",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            words.forEach { word ->
                InputChip(
                    selected = false,
                    onClick = { /* whole-chip click is a no-op; removal is via trailing x */ },
                    label = { Text(word) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove $word",
                            modifier = Modifier
                                .size(InputChipDefaults.IconSize)
                                .clickable(onClick = { onRemove(word) }),
                        )
                    },
                )
            }

            FilledIconButton(
                onClick = { showAddDialog = true },
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add termination word",
                )
            }
        }
    }

    if (showAddDialog) {
        AddTerminationWordDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { input ->
                onAdd(input)
                showAddDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTerminationWordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add termination word") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Word or phrase") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (text.isNotBlank()) onConfirm(text)
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
