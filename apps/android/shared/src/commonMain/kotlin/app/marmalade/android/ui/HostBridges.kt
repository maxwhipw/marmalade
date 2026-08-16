package app.marmalade.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * The handful of platform facts the shared chat UI cannot look up itself.
 *
 * Each is supplied once by the host around the nav host — the same supply
 * point as `LocalMarmaladeRpc` (see RpcProvider.kt) — so a shared composable
 * asks the composition rather than reaching for an Android API that has no
 * multiplatform equivalent.
 *
 * Unlike `LocalMarmaladeRpc`, these default to a harmless no-op rather than
 * `error(…)`: a missing RPC means the screen cannot work at all, whereas a
 * missing clipboard only means a Copy button does nothing. A composable
 * preview, a unit test, or a second Activity that never wires them should not
 * crash on a tap.
 *
 * All three are `static`: the host binds each once for the process lifetime,
 * so there is nothing to invalidate readers for.
 */

/**
 * Copy plain text to the system clipboard.
 *
 * `:shared` compiles against Compose Multiplatform 1.7.3, which predates the
 * `Clipboard`/`ClipEntry` API, so the write itself belongs to the host. The
 * Android app provides an implementation over its `Clipboard.setPlainText`
 * helper (ui/ClipboardExt.kt).
 */
val LocalCopyText: ProvidableCompositionLocal<(String) -> Unit> =
    staticCompositionLocalOf { { _: String -> } }

/**
 * Open an attachment identified by its source URI string (a message part's
 * `source`, typically a `content://` URI).
 *
 * Deliberately not `LocalUriHandler`: the Android implementation must add
 * `FLAG_GRANT_READ_URI_PERMISSION` to the view intent, and the standard URI
 * handler drops it, which breaks content-provider attachments.
 */
val LocalOpenAttachment: ProvidableCompositionLocal<(String) -> Unit> =
    staticCompositionLocalOf { { _: String -> } }

/**
 * Whether the user has animations switched on system-wide.
 *
 * The activity bubble and the session status indicator both honour
 * reduce-motion; the signal for it is platform-specific (on Android,
 * `Settings.Global.ANIMATOR_DURATION_SCALE == 0`), so the host reads it and
 * publishes the answer here.
 *
 * Defaults to `true` — over-animating beats silently freezing an indicator,
 * which is also what both Android call sites did on a failed settings read.
 */
val LocalMotionEnabled: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { true }

/**
 * Renders one display equation (TeX source, no delimiters).
 *
 * A renderer *slot* rather than a value: the Android implementation is a
 * WebView over the vendored KaTeX bundle in the app's assets, which cannot
 * leave `:app`, and threading it as a parameter would mean a new argument on
 * every composable between the message list and the text part. The host
 * provides the real renderer; the default below is the same raw-TeX-in-
 * monospace degradation the Android renderer itself falls back to when KaTeX
 * fails to lay out.
 */
val LocalMathRenderer:
    ProvidableCompositionLocal<@Composable (tex: String, textColor: Color, modifier: Modifier) -> Unit> =
    staticCompositionLocalOf {
        { tex, textColor, modifier ->
            Text(
                text = tex,
                color = textColor.takeIf { it != Color.Unspecified }
                    ?.copy(alpha = 0.8f)
                    ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = modifier,
            )
        }
    }
