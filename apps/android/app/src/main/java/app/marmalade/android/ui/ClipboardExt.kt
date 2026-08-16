package app.marmalade.android.ui

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

/**
 * Plain-text helpers over the Compose 1.8+ [Clipboard] API, which deals in
 * [ClipEntry]/[ClipData] rather than strings. They reproduce exactly what the
 * deprecated `ClipboardManager.setText`/`getText` did: one plain-text item in,
 * that item's text back out. Both are suspending, like the API underneath.
 */
suspend fun Clipboard.setPlainText(text: String, label: String = "text") {
    setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
}

/** The clipboard's first item as plain text, or null if empty / not text. */
suspend fun Clipboard.getPlainText(): String? =
    getClipEntry()?.clipData
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.text?.toString()
