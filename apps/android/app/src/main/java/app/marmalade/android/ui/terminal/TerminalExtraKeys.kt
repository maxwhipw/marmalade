package app.marmalade.android.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.marmalade.android.terminal.TerminalKey

/**
 * The soft extra-keys row above the keyboard. Ctrl/Alt are STICKY toggles
 * (applied to the next typed key, then cleared — see the screen's typed-input
 * path); the momentary keys ([TerminalKey]) are sent immediately. "Abc" is a
 * persistent mode, not a key: it turns keyboard autocorrect on and off (see
 * `applyTerminalEditorInfo`). Horizontally scrollable so it fits any width.
 *
 * Renderer-independent by construction — the row deals only in keys and bytes
 * and knows nothing about how the grid is drawn.
 *
 * @param suggestionsEnabled current state of the "Abc" mode; the screen owns
 *   it, persists it, and must restart the IME when it changes.
 */
@Composable
fun ExtraKeysRow(
    ctrlSticky: Boolean,
    altSticky: Boolean,
    copyEnabled: Boolean,
    suggestionsEnabled: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSuggestionsToggle: () -> Unit,
    onKey: (TerminalKey) -> Unit,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyButton("Esc") { onKey(TerminalKey.ESCAPE) }
            KeyButton("Tab") { onKey(TerminalKey.TAB) }
            KeyButton("⇧Tab") { onKey(TerminalKey.SHIFT_TAB) }
            StickyKey("Ctrl", ctrlSticky, onCtrlToggle)
            StickyKey("Alt", altSticky, onAltToggle)
            // Autocorrect. Same chip affordance as Ctrl/Alt because it reads
            // the same way — selected means "on" — but it persists.
            StickyKey("Abc", suggestionsEnabled, onSuggestionsToggle)
            KeyButton("←") { onKey(TerminalKey.ARROW_LEFT) }
            KeyButton("↑") { onKey(TerminalKey.ARROW_UP) }
            KeyButton("↓") { onKey(TerminalKey.ARROW_DOWN) }
            KeyButton("→") { onKey(TerminalKey.ARROW_RIGHT) }
            KeyButton("Home") { onKey(TerminalKey.HOME) }
            KeyButton("End") { onKey(TerminalKey.END) }
            // The other half of the Enter split: a soft Enter now submits
            // (LF→CR in TerminalImeCodec), so this is how a newline is typed
            // *into* a prompt.
            KeyButton("⏎") { onKey(TerminalKey.NEWLINE) }
            KeyButton("PgUp") { onKey(TerminalKey.PAGE_UP) }
            KeyButton("PgDn") { onKey(TerminalKey.PAGE_DOWN) }
            // Characters Gboard buries behind its symbol page, one tap deep.
            KeyButton("/") { onKey(TerminalKey.SLASH) }
            KeyButton("~") { onKey(TerminalKey.TILDE) }
            KeyButton("-") { onKey(TerminalKey.HYPHEN) }
            KeyButton("|") { onKey(TerminalKey.PIPE) }
            KeyButton("Paste", onClick = onPaste)
            KeyButton("Copy", enabled = copyEnabled, onClick = onCopy)
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StickyKey(label: String, active: Boolean, onToggle: () -> Unit) {
    FilterChip(selected = active, onClick = onToggle, label = { Text(label) })
}
