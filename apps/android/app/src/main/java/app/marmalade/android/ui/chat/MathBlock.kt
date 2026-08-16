package app.marmalade.android.ui.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One display equation rendered by the VENDORED KaTeX bundle
 * (assets/katex/, v0.16.47, MIT — see CREDITS.md) inside a WebView.
 *
 * Design constraints (research 2026-07-03):
 * - No third-party Android KaTeX wrapper deps (unmaintained/license-
 *   unclear) and absolutely no jlatexmath (GPL). Vendoring KaTeX's own
 *   MIT dist + a thin host here is the safe path.
 * - The WebView mounts once per equation and reloads only when the
 *   content key (tex/color/size) changes — [splitMathInMarkdown] only
 *   produces Math segments for CLOSED delimiters, so nothing here runs
 *   inside the 33ms streaming flush churn.
 * - Height is reported back over a JS bridge after fonts settle (CSS px
 *   ≈ dp at default WebView zoom); render failures degrade to raw TeX
 *   in monospace.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathBlock(
    tex: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val resolvedColor = textColor.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.onSurface
    val fontSizeSp = MaterialTheme.typography.bodyMedium.fontSize.value
    // Not keyed on tex: the factory closure captures these state objects
    // once; re-keying would strand the WebView callbacks on stale instances.
    var contentHeightDp by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    if (failed) {
        Text(
            text = tex,
            color = resolvedColor.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        return
    }

    val html = remember(tex, resolvedColor, fontSizeSp) {
        buildMathHtml(tex = tex, colorCss = cssColor(resolvedColor), fontSizePx = fontSizeSp)
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // Asset/JS load failure — main-frame only matters,
                        // but degrading on any error is the safe default.
                        post { failed = true }
                    }
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onHeight(cssPx: Float) {
                            // CSS px map ~1:1 to dp at default zoom.
                            post { contentHeightDp = cssPx.toInt().coerceAtLeast(1) }
                        }

                        @JavascriptInterface
                        fun onRenderError() {
                            post { failed = true }
                        }
                    },
                    "MarmaladeMath",
                )
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                // file:///android_asset base is always permitted regardless of
                // allowFileAccess — it resolves the vendored css/js/fonts.
                webView.loadDataWithBaseURL(
                    "file:///android_asset/katex/",
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height((if (contentHeightDp > 0) contentHeightDp else 24).dp),
    )
}

private fun cssColor(color: Color): String {
    val argb = color.toArgb()
    return String.format("#%06X", argb and 0xFFFFFF)
}

private val texJson = Json

/**
 * Self-contained page for one equation. The TeX is injected as a JSON
 * string literal (TeX is backslash/quote-heavy — JSON escaping is the
 * whole ballgame, and it also neutralises `</script>` breakouts since
 * the raw TeX never appears in markup).
 */
internal fun buildMathHtml(tex: String, colorCss: String, fontSizePx: Float): String {
    val texLiteral = texJson.encodeToString(String.serializer(), tex)
        // Guard against the classic inline-script pitfall: a literal
        // "</script" inside a JS string still terminates the script tag.
        .replace("</", "<\\/")
    return """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="katex.min.css">
        <script src="katex.min.js"></script>
        <style>
          html, body { margin: 0; padding: 0; background: transparent; }
          #m { color: $colorCss; font-size: ${fontSizePx}px; overflow-x: auto; padding: 2px 0; }
        </style>
        </head><body><div id="m"></div>
        <script>
          (function() {
            var tex = $texLiteral;
            var el = document.getElementById('m');
            try {
              katex.render(tex, el, { displayMode: true, throwOnError: false, strict: 'ignore' });
            } catch (e) {
              MarmaladeMath.onRenderError();
              return;
            }
            function report() { MarmaladeMath.onHeight(el.offsetHeight || document.body.scrollHeight); }
            if (document.fonts && document.fonts.ready) {
              document.fonts.ready.then(report);
            } else {
              setTimeout(report, 60);
            }
          })();
        </script>
        </body></html>
    """.trimIndent()
}
