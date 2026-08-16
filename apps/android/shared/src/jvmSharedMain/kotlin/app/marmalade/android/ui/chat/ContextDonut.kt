package app.marmalade.android.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.android.chat.messages.MessageStream

/**
 * Context-utilization donut: a ring whose filled arc is the fraction of the
 * model's context window in use, with the percentage in the center. Replaces
 * the old "Xk out" token tally in the composer action row.
 *
 * The arc colour ramps from primary → tertiary (crowded) → error (nearly
 * full) so the user gets an at-a-glance warning before compression kicks in.
 * Data comes from `session.info` usage (`context_percent`); see
 * [MessageStream.UsageDelta]. When the provider doesn't report a window the
 * composer shows the token count instead of a donut.
 */
@Composable
fun ContextDonut(
    percent: Int,
    modifier: Modifier = Modifier,
    diameter: Dp = 34.dp,
    onClick: (() -> Unit)? = null,
) {
    val pct = percent.coerceIn(0, 100)
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val arcColor = when {
        pct >= 90 -> MaterialTheme.colorScheme.error
        pct >= 75 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter * 0.82f)) {
            val stroke = size.width * 0.11f
            val inset = stroke / 2f
            val topLeft = Offset(inset, inset)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = pct / 100f * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = pct.toString(),
            fontSize = (diameter.value * 0.27f).sp,
            color = labelColor,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Bottom sheet detailing context/token usage for the bound session. Opened by
 * tapping the composer's [ContextDonut]. Shows the context ring, used/max
 * tokens, per-session input/output/cache tallies, cost, and compression count
 * — whichever fields the gateway reported.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextDetailsSheet(
    usage: MessageStream.UsageDelta,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Context",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            ContextUsageDetails(usage = usage)
        }
    }
}

/**
 * The context/token usage breakdown — donut + used/max tokens + per-session
 * input/output/cache/cost/compression rows. Extracted from [ContextDetailsSheet]
 * so the chat-settings sheet can inline the same details (it already has its own
 * "Context" header + compact/clear actions). Caller owns the surrounding Column
 * and any section heading.
 *
 * Laid out left→right — donut + utilization, then the token breakdown — so
 * the token rows fill the horizontal space beside the donut instead of
 * stacking underneath it. Falls back to a single full-width column when the
 * provider reports no context window (no donut/utilization to anchor).
 */
@Composable
fun ContextUsageDetails(usage: MessageStream.UsageDelta) {
    val details: @Composable ColumnScope.() -> Unit = {
        DetailRow("Input", usage.inputTokens?.let(::formatTokens))
        DetailRow("Output", usage.outputTokens?.let(::formatTokens))
        DetailRow("Cache read", usage.cacheReadTokens?.let(::formatTokens))
        DetailRow("Cost", usage.costUsd?.let { "$" + "%.4f".format(it) })
        DetailRow("Compressions", usage.compressions?.takeIf { it > 0 }?.toString())
    }

    val pct = usage.contextPercent
    if (pct != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContextDonut(percent = pct, diameter = 60.dp)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "$pct% used",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    // Bound to locals: UsageDelta now lives in :shared, and a
                    // public property from another module can't be smart-cast.
                    val used = usage.contextUsed
                    val max = usage.contextMax
                    if (used != null && max != null) {
                        Text(
                            text = "${formatTokens(used)} / " +
                                "${formatTokens(max)} tokens",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), content = details)
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth(), content = details)
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** k/M-suffixed token count, e.g. 61_440 → "61.4k". Shared so the session
 *  tool panel formats counts identically to the composer's details sheet.
 *  Public, not internal: that panel lives in `:app`, a separate module. */
fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
