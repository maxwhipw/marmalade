package app.marmalade.android.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import app.marmalade.android.ui.chat.MathBlock
import kotlinx.coroutines.launch

/**
 * The Android host's implementations of the shared UI's platform bridges
 * ([LocalCopyText], [LocalOpenAttachment], [LocalMotionEnabled], declared in
 * `:shared`).
 *
 * Wrapped around the nav host next to `LocalMarmaladeRpc`, which is the one
 * place every screen — chat included — is composed under. A desktop host will
 * provide the same three from its own platform APIs.
 */
@Composable
fun MarmaladeHostBridges(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // The Compose 1.8 clipboard API is suspending, and :shared (CMP 1.7.3)
    // has no clipboard at all — so the write happens here, fire-and-forget on
    // the composition's scope, which is exactly what the call sites did before
    // the seam existed.
    val copyText: (String) -> Unit = remember(clipboard, scope) {
        { text -> scope.launch { clipboard.setPlainText(text) } }
    }

    // FLAG_GRANT_READ_URI_PERMISSION is the reason this is not LocalUriHandler:
    // attachment sources are content:// URIs the viewer needs a grant for.
    val openAttachment: (String) -> Unit = remember(context) {
        { source ->
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(source)).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }
        }
    }

    // Compose's LocalAccessibilityManager does NOT expose a "reduce motion"
    // flag (only timeout calculation), so we read Android's canonical signal:
    // "Remove animations" in accessibility settings writes
    // ANIMATOR_DURATION_SCALE = 0, the same source ValueAnimator reads.
    // Read once — it needs a settings round trip and it cannot change without
    // the app being restarted anyway. On read failure default to "motion
    // enabled": better to over-animate than to silently freeze an indicator.
    val motionEnabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }

    CompositionLocalProvider(
        LocalCopyText provides copyText,
        LocalOpenAttachment provides openAttachment,
        LocalMotionEnabled provides motionEnabled,
        // Display equations: the vendored-KaTeX WebView renderer, which stays
        // in :app because the KaTeX bundle is an app asset.
        LocalMathRenderer provides { tex, textColor, modifier ->
            MathBlock(tex = tex, textColor = textColor, modifier = modifier)
        },
        content = content,
    )
}
