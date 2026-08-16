package app.marmalade.android.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import app.marmalade.android.ui.LocalCopyText
import app.marmalade.android.ui.theme.marmaladeColors
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Compose renderer for the Marmalade UI v1 node tree (spec: marmalade repo
 * docs/dynamic-ui/marmalade-ui-v1.md). Inputs hold LOCAL state only (the
 * [UiInputState] map); a callback button collects the ids in collect_from
 * and synthesizes a plain user message via [UiTreeParser.callbackMessage]
 * through [onRespond] — the same send path as any typed message.
 */
private class UiInputState {
    val values = mutableStateMapOf<String, String>()
}

private val UiCardShape = RoundedCornerShape(12.dp)

/** Top-level entry: render a parsed tree as a card in the chat stream. */
@Composable
fun UiTreeRenderer(
    root: UiNode,
    onRespond: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inputs = remember(root) { UiInputState() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(UiCardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), UiCardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RenderNode(root, inputs, onRespond)
    }
}

@Composable
private fun RenderNode(node: UiNode, inputs: UiInputState, onRespond: (String) -> Unit) {
    when (node) {
        is UiNode.ColumnNode -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { RenderNode(it, inputs, onRespond) }
        }
        is UiNode.RowNode -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { RenderNode(it, inputs, onRespond) }
        }
        is UiNode.CardNode -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(UiCardShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            node.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            node.children.forEach { RenderNode(it, inputs, onRespond) }
        }
        is UiNode.DividerNode -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        is UiNode.TextNode -> RenderText(node)
        is UiNode.ListNode -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            node.items.forEachIndexed { i, item ->
                Text(
                    text = if (node.ordered) "${i + 1}. $item" else "• $item",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        is UiNode.TableNode -> RenderTable(node)
        is UiNode.CodeNode -> Text(
            text = node.code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
        )
        is UiNode.AlertNode -> RenderAlert(node)
        is UiNode.ButtonNode -> RenderButton(node, inputs, onRespond)
        is UiNode.TextInputNode -> OutlinedTextField(
            value = inputs.values[node.id] ?: node.value.orEmpty().also {
                if (it.isNotEmpty() && node.id !in inputs.values) inputs.values[node.id] = it
            },
            onValueChange = { inputs.values[node.id] = it },
            label = node.label?.let { { Text(it) } },
            placeholder = node.placeholder?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        is UiNode.SelectNode -> RenderChips(
            id = node.id, label = node.label, options = node.options, multi = false, inputs = inputs,
        )
        is UiNode.CheckboxNode -> Row(verticalAlignment = Alignment.CenterVertically) {
            val checked = (inputs.values[node.id] ?: node.checked.toString()) == "true"
            Checkbox(checked = checked, onCheckedChange = { inputs.values[node.id] = it.toString() })
            Text(node.label, style = MaterialTheme.typography.bodyMedium)
        }
        is UiNode.ChipGroupNode -> RenderChips(
            id = node.id, label = null, options = node.options, multi = node.multi, inputs = inputs,
        )
        is UiNode.ProgressNode -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.label?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (node.value != null) {
                LinearProgressIndicator(progress = { node.value }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is UiNode.StatusNode -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (node.state) {
                "active", "pending" -> CircularProgressIndicator(
                    modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp,
                )
                else -> {}
            }
            Text(
                text = node.text,
                style = MaterialTheme.typography.bodyMedium,
                color = when (node.state) {
                    "success" -> MaterialTheme.colorScheme.primary
                    "error" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        is UiNode.CountdownNode -> RenderCountdown(node)
        is UiNode.Unknown -> node.text?.let {
            // Closed vocabulary: unknown types degrade to readable text.
            Text(it, style = MaterialTheme.typography.bodyMedium)
        } ?: Unit
    }
}

@Composable
private fun RenderText(node: UiNode.TextNode) {
    Text(
        text = node.text,
        style = when (node.style) {
            "headline" -> MaterialTheme.typography.headlineSmall
            "title" -> MaterialTheme.typography.titleMedium
            "caption" -> MaterialTheme.typography.bodySmall
            else -> MaterialTheme.typography.bodyMedium
        },
        fontWeight = if (node.bold) FontWeight.Bold else null,
        color = uiNodeSemanticColor(node.color)
            ?: if (node.style == "caption") MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun RenderTable(node: UiNode.TableNode) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            node.columns.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        node.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(cell, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun RenderAlert(node: UiNode.AlertNode) {
    // Tinted from the SAME semantic token the text uses, rather than Material's
    // *Container roles — see [uiNodeSemanticColor]. A low-alpha wash over the
    // chat surface also keeps alerts legible in both light and dark without a
    // second set of on-container roles to keep in sync.
    val content = uiNodeSemanticColor(node.level) ?: MaterialTheme.colorScheme.primary
    val container = content.copy(alpha = 0.12f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        node.title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = content, fontWeight = FontWeight.SemiBold) }
        Text(node.text, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}

@Composable
private fun RenderButton(node: UiNode.ButtonNode, inputs: UiInputState, onRespond: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val copyText = LocalCopyText.current
    val onClick: () -> Unit = {
        when (node.action) {
            "open_url" -> node.url?.let { url ->
                // The only browser escape hatch the vocabulary allows. The
                // platform handler is an ACTION_VIEW intent on Android — the
                // same thing this did before the module split; no read-grant
                // flag is wanted here (these are http(s) URLs, not attachment
                // content:// URIs, which go through LocalOpenAttachment).
                runCatching { uriHandler.openUri(url) }
            }
            "copy_to_clipboard" -> copyText(node.text ?: node.label)
            else -> onRespond(UiTreeParser.callbackMessage(node, inputs.values))
        }
    }
    when (node.variant) {
        "secondary" -> OutlinedButton(onClick = onClick) { Text(node.label) }
        "danger" -> Button(
            onClick = onClick,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text(node.label) }
        else -> Button(onClick = onClick) { Text(node.label) }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RenderChips(
    id: String,
    label: String?,
    options: List<UiNode.UiOption>,
    multi: Boolean,
    inputs: UiInputState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val selected = inputs.values[id]?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            options.forEach { opt ->
                FilterChip(
                    selected = opt.id in selected,
                    onClick = {
                        val next = if (multi) {
                            if (opt.id in selected) selected - opt.id else selected + opt.id
                        } else {
                            setOf(opt.id)
                        }
                        inputs.values[id] = next.joinToString(",")
                    },
                    label = { Text(opt.label) },
                )
            }
        }
    }
}

@Composable
private fun RenderCountdown(node: UiNode.CountdownNode) {
    val targetMs = node.untilMs ?: node.seconds?.let { System.currentTimeMillis() + it * 1000 }
    var remaining by remember(node) { mutableLongStateOf(((targetMs ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0)) }
    LaunchedEffect(node) {
        if (targetMs == null) return@LaunchedEffect
        while (true) {
            remaining = (targetMs - System.currentTimeMillis()).coerceAtLeast(0)
            if (remaining == 0L) break
            delay(1000)
        }
    }
    val totalSec = remaining / 1000
    val text = if (totalSec >= 3600) {
        "%d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    } else {
        "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        node.label?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(text, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Semantic colour for a Marmalade UI v1 `color` / `level` variant.
 *
 * Marmalade UI is the most powerful surface in the app — the agent composes
 * arbitrary node trees at will — and it was the least governed: its variants
 * resolved straight through `MaterialTheme.colorScheme` (`success` → tertiary,
 * `warning` → secondary), so a block inherited whatever Material's baseline
 * roles happened to be rather than the brand palette. That is the same class
 * of bug the tts-voice-hierarchy lab caught when five undefined
 * `surfaceContainer*` roles fell through to Material's baseline purple.
 *
 * Returns null for an unrecognised variant so callers keep their own default.
 */
@Composable
internal fun uiNodeSemanticColor(variant: String?): androidx.compose.ui.graphics.Color? =
    when (variant) {
        "primary", "info" -> MaterialTheme.colorScheme.primary
        "success" -> MaterialTheme.marmaladeColors.toolSuccess
        "warning" -> MaterialTheme.marmaladeColors.bannerWarning
        "error", "danger" -> MaterialTheme.marmaladeColors.bannerError
        else -> null
    }
