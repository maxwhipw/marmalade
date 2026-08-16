package app.marmalade.android.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.markdown.AstBlockNodeComposer
import com.halilibo.richtext.markdown.node.AstBlockNodeType
import com.halilibo.richtext.markdown.node.AstFencedCodeBlock
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.ui.BlockQuoteGutter
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.HeadingStyle
import com.halilibo.richtext.ui.RichTextScope
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import app.marmalade.android.chat.ChatMarkdownPreprocessor
import app.marmalade.android.ui.blocks.MarmaladeBlockParser
import app.marmalade.android.ui.blocks.MarmaladeBlockRenderer
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Full-featured markdown renderer using `compose-richtext` (commonmark-java
 * backend). The single renderer for BOTH streaming and finalized text
 * (`AssistantTextPart` feeds it a growing prefix during a live turn) —
 * commonmark-java tolerates unclosed constructs mid-stream, so re-parsing the
 * growing text on each flush doesn't flicker (ADR 0006; the hand-rolled
 * `ChatMarkdown` streaming renderer it superseded was deleted 2026-07-02).
 *
 * Why commonmark-java over the prior intellij-markdown stack: this is the
 * CommonMark reference implementation. Lists nested in blockquotes,
 * tables, code fences with language tags, headers, and inline emphasis
 * all parse correctly without preprocessor hacks. The mikepenz/intellij-
 * markdown parser absorbed `> 1. item` into the preceding paragraph as
 * lazy continuation, dropping the list silently — that bug is gone now.
 *
 * The marmalade-fence interception (` ```marmalade ` blocks rendered as
 * interactive cards) lives in [marmaladeBlockComposer] which intercepts
 * any [AstFencedCodeBlock] whose `info` is exactly `"marmalade"` and
 * routes it to [MarmaladeBlockRenderer].
 */
@Composable
fun ChatMarkdownContent(
    text: String,
    textColor: Color,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onBlockInteraction: (String) -> Unit = {},
) {
    // Preprocessor still strips inbound metadata blocks and timestamp
    // prefixes the gateway injects; the blockquote-list normaliser is
    // moot under commonmark-java but the function is harmless on
    // already-correct input (the no-op test case in
    // ChatMarkdownPreprocessorTest covers this).
    val processed = remember(text) { ChatMarkdownPreprocessor.preprocess(text) }
    val composer = remember(onBlockInteraction) {
        marmaladeBlockComposer(onBlockInteraction)
    }
    val style = chatRichTextStyle(textColor = textColor, linkColor = linkColor)
    // Match the streaming bubble's body typography. ChatMarkdown (the
    // hand-rolled streaming renderer) renders text via `ClickableText` with
    // `MaterialTheme.typography.bodyMedium` — without this propagation,
    // compose-richtext falls back to its own internal default (a different
    // font / size / line-height) and the bubble visibly reflows the moment
    // a run finalises. ProvideTextStyle is the M3 seam compose-richtext
    // reads for body paragraphs and inline runs.
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor)
    ProvideTextStyle(bodyStyle) {
        RichText(modifier = modifier, style = style) {
            Markdown(content = processed, astBlockNodeComposer = composer)
        }
    }
}

/**
 * RichTextStyle wired to MaterialTheme + marmaladeColors so the markdown
 * render matches the surrounding chat bubble theming. Centralised here so
 * both the live render and any future preview/test paths get the same
 * palette without re-deriving it.
 */
@Composable
private fun chatRichTextStyle(textColor: Color, linkColor: Color): RichTextStyle {
    // Capture typography styles outside the headingStyle lambda — that
    // lambda is not @Composable so we can't read MaterialTheme there.
    val h1 = MaterialTheme.typography.headlineSmall
    val h2 = MaterialTheme.typography.titleLarge
    val h3 = MaterialTheme.typography.titleMedium
    val h4 = MaterialTheme.typography.titleSmall
    val h5_6 = MaterialTheme.typography.bodyLarge
    val codeTextColor = MaterialTheme.marmaladeColors.codeText
    return RichTextStyle(
        // paragraphSpacing is compose-richtext's only knob for inter-block
        // gap — paragraphs, headings, blockquotes, rules, and lists all
        // honour the same value. The streaming bubble (single ClickableText
        // with `\n\n`) gets paragraph-to-paragraph gap "for free" from
        // bodyMedium's line-height, so its perceived inter-block spacing
        // is much tighter than what compose-richtext produces at the same
        // paragraphSpacing value. 4.sp keeps mixed-block boundaries (text
        // → heading, paragraph → blockquote, rule → heading) tight enough
        // to match the streaming look without losing legibility.
        paragraphSpacing = 4.sp,
        headingStyle = { level, currentTextStyle ->
            // Ramp matches our Material 3 typography scale for h1-h4 and
            // falls through to body weight/size for h5/h6 (rare in chat).
            val target: TextStyle = when (level) {
                0 -> h1
                1 -> h2
                2 -> h3
                3 -> h4
                else -> h5_6
            }
            currentTextStyle.merge(target).copy(color = textColor)
        },
        codeBlockStyle = CodeBlockStyle(
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = codeTextColor,
            ),
        ),
        blockQuoteGutter = BlockQuoteGutter.BarGutter(
            startMargin = 6.sp,
            barWidth = 3.sp,
            endMargin = 8.sp,
            color = { textColor.copy(alpha = 0.35f) },
        ),
        stringStyle = com.halilibo.richtext.ui.string.RichTextStringStyle(
            linkStyle = TextLinkStyles(
                style = SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
            codeStyle = SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = textColor.copy(alpha = 0.10f),
                color = textColor,
            ),
        ),
    )
}

/**
 * Owns rendering of ALL fenced code blocks. A ` ```marmalade ` fence routes
 * to [MarmaladeBlockRenderer] (interactive card); every other fence renders
 * as a [ChatCodeBlock] — a styled block with a language header and a copy
 * button. Without this, ordinary code fences would fall through to
 * compose-richtext's default code-block component, which has neither syntax
 * highlighting nor a copy affordance.
 *
 * Exact-equality check on `"marmalade"` is deliberate (per MCP-06):
 * `startsWith` would also match `"marmalade-response"` and trigger an
 * infinite loop where the renderer's response is itself parsed as a
 * marmalade block.
 */
private fun marmaladeBlockComposer(
    onBlockInteraction: (String) -> Unit,
): AstBlockNodeComposer {
    return object : AstBlockNodeComposer {
        override fun predicate(astBlockNodeType: AstBlockNodeType): Boolean {
            return astBlockNodeType is AstFencedCodeBlock
        }

        @Composable
        override fun RichTextScope.Compose(
            astNode: AstNode,
            visitChildren: @Composable (AstNode) -> Unit,
        ) {
            val type = astNode.type as AstFencedCodeBlock
            if (type.info == "marmalade") {
                val block = MarmaladeBlockParser.parseMarmaladeBlock(type.literal)
                if (block != null) {
                    MarmaladeBlockRenderer(block = block, onInteraction = onBlockInteraction)
                    return
                }
                // Malformed JSON in a `marmalade` fence — fall through to the
                // styled code block so the content is still surfaced (per
                // ERRV13-03).
            }
            // Ordinary fenced code block (or a malformed marmalade fence):
            // render with syntax highlighting + copy button. The `info` string
            // is the language tag (empty for a bare ``` fence).
            ChatCodeBlock(code = type.literal, language = type.info.ifBlank { null })
            visitChildren(astNode)
        }
    }
}
