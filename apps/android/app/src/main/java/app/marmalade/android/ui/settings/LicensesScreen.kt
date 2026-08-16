package app.marmalade.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class LicenseEntry(
    val name: String,
    val license: String,
    val description: String,
)

private val licenses = listOf(
    LicenseEntry("Jetpack Compose", "Apache 2.0", "Modern Android UI toolkit"),
    LicenseEntry("Room", "Apache 2.0", "SQLite object-mapping persistence library"),
    LicenseEntry("OkHttp", "Apache 2.0", "HTTP & WebSocket client"),
    LicenseEntry("Coil", "Apache 2.0", "Image loading library for Compose"),
    LicenseEntry("Mikepenz Markdown", "Apache 2.0", "Markdown renderer for Compose"),
    LicenseEntry("ZXing", "Apache 2.0", "QR code scanning library"),
    LicenseEntry("Sherpa-ONNX", "Apache 2.0", "On-device speech recognition (STT)"),
    LicenseEntry("ONNX Runtime", "MIT", "On-device model inference"),
    LicenseEntry("Distil-Whisper (distil-small.en)", "MIT", "Bundled STT model — distilled Whisper (Hugging Face, from OpenAI Whisper)"),
    LicenseEntry("openWakeWord feature models", "Apache 2.0", "Wake-word mel-spectrogram + embedding models"),
    LicenseEntry("Silero VAD", "MIT", "Voice-activity detection (© Silero Team)"),
    // Bundled assets are dependencies too — these were documented in
    // CREDITS.md but missing from the screen the user can actually see.
    // libmarmalade_term.so and its upstreams. All statically linked into the
    // APK, so all four notices ship — CREDITS.md carries the per-file detail.
    LicenseEntry(
        "Ghostty (libghostty-vt)",
        "MIT",
        "Headless terminal state machine behind the native terminal " +
            "(© 2024 Mitchell Hashimoto, Ghostty contributors)",
    ),
    LicenseEntry(
        "chuchu",
        "MIT",
        "JNI bridge, snapshot format and terminal rendering the native " +
            "terminal is ported from (© 2026 jossephus)",
    ),
    LicenseEntry(
        "uucode",
        "MIT",
        "Unicode width/grapheme tables libghostty-vt links (© 2026 Jacob " +
            "Sandlund; bundles the Unicode License V3 and the MIT wcwidth " +
            "family — full attribution in CREDITS.md)",
    ),
    LicenseEntry(
        "zigimg",
        "MIT",
        "PNG decoding for terminal kitty-graphics images (© 2019-2021 zigimg " +
            "developers; bundles hsluv-c, © 2015 Alexei Boronine, © 2015 " +
            "Roger Tallada, © 2017 Martin Mitáš — full text in " +
            "assets/licenses/zigimg.txt)",
    ),
    LicenseEntry("KaTeX", "MIT", "Math rendering in chat"),
    LicenseEntry(
        "Momo Trust Display",
        "SIL OFL 1.1",
        "Wordmark typeface (© 2024 The Momo Trust Project Authors)",
    ),
    LicenseEntry(
        "Manrope",
        "SIL OFL 1.1",
        "Body + heading typeface (© 2019 The Manrope Project Authors)",
    ),
    LicenseEntry(
        "Space Mono",
        "SIL OFL 1.1",
        "Monospace typeface (© 2016 The Space Mono Project Authors)",
    ),
    LicenseEntry(
        "JetBrains Mono",
        "SIL OFL 1.1",
        "Terminal typeface (© 2020 The JetBrains Mono Project Authors)",
    ),
    LicenseEntry(
        "Noto Sans Symbols 2",
        "SIL OFL 1.1",
        "Terminal symbol fallback (© 2022 The Noto Project Authors)",
    ),
    // The Nerd Font is a merge of thirteen icon sets under MIT, Apache 2.0,
    // CC BY 4.0, SIL OFL 1.1 and the Unlicense. Naming every holder here
    // would swamp the screen, so it points at the notice that does.
    LicenseEntry(
        "Symbols Nerd Font Mono",
        "MIT, and the licenses of the icon sets it merges",
        "Terminal icon glyphs (© 2014 Ryan L McIntyre, plus Seti-UI, Devicons, " +
            "Font Awesome, Material Design Icons, Weather Icons, Octicons, " +
            "Font Logos, Powerline, IEC Power Symbols, Pomicons and Codicons — " +
            "full attribution in assets/licenses/SymbolsNerdFont.txt)",
    ),
)

/**
 * Open source licenses screen listing key dependencies and their license types.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Open Source Licenses") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            items(licenses) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.license,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}
