package app.marmalade.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import app.marmalade.android.ui.LocalCopyText
import app.marmalade.android.ui.theme.marmaladeColors
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

/**
 * Styled code block with syntax highlighting via SnipMeDev Highlights.
 *
 * Features:
 * - Near-black background, subtle border, rounded 8dp corners
 * - Header bar with language label (uppercase monospace) and copy button
 * - Horizontally scrollable content in syntax-highlighted monospace text
 * - Graceful fallback to plain monospace for unsupported languages
 *
 * Used by:
 * - ChatMarkdownContent (compose-richtext) -- wired via the AstFencedCodeBlock
 *   component override for fenced code blocks in rendered messages.
 * - ToolCallCard -- pretty-printed args/result in the expanded tool card.
 */
@Composable
fun ChatCodeBlock(
    code: String,
    language: String?,
    textColor: Color = Color.Unspecified,
    onCopy: (() -> Unit)? = null,
) {
    val copyText = LocalCopyText.current
    val codeShape = RoundedCornerShape(8.dp)
    val trimmedCode = code.trimEnd()
    val resolvedTextColor = if (textColor == Color.Unspecified) MaterialTheme.marmaladeColors.codeText else textColor
    val codeBackground = MaterialTheme.marmaladeColors.codeBackground
    val codeBorder = MaterialTheme.marmaladeColors.codeBorder
    // Header label/copy-icon tint from the code text colour (dimmed) rather
    // than the theme's `outline`, so it reads on both the light and dark
    // code grounds regardless of app theme.
    val mutedColor = resolvedTextColor.copy(alpha = 0.55f)
    // Match the syntax theme to the ground: monokai (dark) on the near-black
    // dark-mode block, a light theme on the Stone-soft light-mode block —
    // otherwise the pale monokai palette washes out on a light ground.
    val darkGround = codeBackground.luminance() < 0.5f

    // Produce syntax-highlighted AnnotatedString (falls back to plain text for unsupported languages)
    val highlightedCode = remember(trimmedCode, language, resolvedTextColor, darkGround) {
        highlightCode(trimmedCode, language, resolvedTextColor, darkGround)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(codeBackground, codeShape)
            .border(1.dp, codeBorder, codeShape),
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(codeBorder.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (language ?: "code").uppercase(),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = mutedColor,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy code",
                tint = mutedColor,
                modifier = Modifier
                    .size(15.dp)
                    .clickable {
                        copyText(trimmedCode)
                        onCopy?.invoke()
                    },
            )
        }

        // Code content -- horizontally scrollable, syntax highlighted
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = highlightedCode,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

// ── Syntax highlighting ───────────────────────────────────────────────────

/** Map common language identifiers to SnipMeDev SyntaxLanguage. */
private fun mapLanguage(language: String?): SyntaxLanguage? {
    if (language == null) return null
    return when (language.lowercase()) {
        "kotlin", "kt", "kts" -> SyntaxLanguage.KOTLIN
        "java" -> SyntaxLanguage.JAVA
        "python", "py" -> SyntaxLanguage.PYTHON
        "javascript", "js" -> SyntaxLanguage.JAVASCRIPT
        "typescript", "ts" -> SyntaxLanguage.TYPESCRIPT
        "swift" -> SyntaxLanguage.SWIFT
        "c" -> SyntaxLanguage.C
        "cpp", "c++", "cxx" -> SyntaxLanguage.CPP
        "rust", "rs" -> SyntaxLanguage.RUST
        "go", "golang" -> SyntaxLanguage.GO
        "ruby", "rb" -> SyntaxLanguage.RUBY
        "shell", "sh", "bash", "zsh" -> SyntaxLanguage.SHELL
        "php" -> SyntaxLanguage.PHP
        "perl", "pl" -> SyntaxLanguage.PERL
        "dart" -> SyntaxLanguage.DART
        "csharp", "cs", "c#" -> SyntaxLanguage.CSHARP
        "coffeescript", "coffee" -> SyntaxLanguage.COFFEESCRIPT
        else -> null // Unsupported -- will fall back to plain text
    }
}

/**
 * Produce an AnnotatedString with syntax highlighting applied.
 * Falls back to plain monospace (textColor) for unsupported languages.
 * [darkGround] selects the syntax theme variant so colours stay legible on
 * both the dark-mode and light-mode code backgrounds.
 */
private fun highlightCode(
    code: String,
    language: String?,
    fallbackColor: Color,
    darkGround: Boolean,
): AnnotatedString {
    val syntaxLang = mapLanguage(language) ?: return AnnotatedString(
        text = code,
        spanStyle = SpanStyle(color = fallbackColor),
    )

    return try {
        val highlights = Highlights.Builder()
            .code(code)
            .language(syntaxLang)
            .theme(if (darkGround) SyntaxThemes.monokai(true) else SyntaxThemes.atom(false))
            .build()

        val codeHighlights = highlights.getHighlights()

        buildAnnotatedString {
            // Start with the base fallback color for all text
            withStyle(SpanStyle(color = fallbackColor)) {
                append(code)
            }
            // Apply colored spans on top
            for (highlight in codeHighlights) {
                when (highlight) {
                    is ColorHighlight -> {
                        val color = Color(
                            red = (highlight.rgb shr 16 and 0xFF) / 255f,
                            green = (highlight.rgb shr 8 and 0xFF) / 255f,
                            blue = (highlight.rgb and 0xFF) / 255f,
                        )
                        addStyle(
                            SpanStyle(color = color),
                            start = highlight.location.start,
                            end = highlight.location.end,
                        )
                    }
                    is BoldHighlight -> {
                        addStyle(
                            SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            start = highlight.location.start,
                            end = highlight.location.end,
                        )
                    }
                    else -> { /* Ignore other highlight types */ }
                }
            }
        }
    } catch (_: Throwable) {
        // Any highlighting failure: fall back to plain text
        AnnotatedString(text = code, spanStyle = SpanStyle(color = fallbackColor))
    }
}
