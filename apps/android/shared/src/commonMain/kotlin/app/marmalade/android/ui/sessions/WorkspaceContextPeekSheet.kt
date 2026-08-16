package app.marmalade.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marmalade.android.rpc.types.WorkspaceContextResponse
import app.marmalade.android.utils.WorkspaceContextUtils.PeekTarget
import app.marmalade.android.utils.WorkspaceContextUtils.peekTabs

/**
 * Read-only context-peek sheet for a workspace. Tabs-as-chips at the top —
 * only the tabs that exist ("CLAUDE.md", "AGENTS.md", "Memory (n)"), opened
 * pre-selected on [initialTab]. File tabs show scrollable monospace text in a
 * surfaceVariant panel with a truncation footer; the Memory tab lists note
 * filenames (no content — by design). Fetch is the [context] already loaded by
 * the detail screen — no second RPC.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceContextPeekSheet(
    context: WorkspaceContextResponse,
    initialTab: PeekTarget,
    onDismiss: () -> Unit,
) {
    val tabs = remember(context) { peekTabs(context) }
    // Fall back to the first available tab if the requested one vanished.
    var selected by remember {
        mutableStateOf(if (initialTab in tabs) initialTab else tabs.firstOrNull() ?: PeekTarget.CLAUDE_MD)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                text = "Workspace context",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(modifier = Modifier.size(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEach { tab ->
                    PeekTabChip(
                        label = tabLabel(tab, context),
                        selected = tab == selected,
                        onClick = { selected = tab },
                    )
                }
            }

            Spacer(modifier = Modifier.size(16.dp))

            when (selected) {
                PeekTarget.CLAUDE_MD -> FilePanel(
                    content = context.claude_md?.content.orEmpty(),
                    truncated = context.claude_md?.truncated == true,
                )
                PeekTarget.AGENTS_MD -> FilePanel(
                    content = context.agents_md?.content.orEmpty(),
                    truncated = context.agents_md?.truncated == true,
                )
                PeekTarget.MEMORY -> MemoryPanel(notes = context.memory)
            }

            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = "Read-only · edit on the host",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

private fun tabLabel(tab: PeekTarget, context: WorkspaceContextResponse): String = when (tab) {
    PeekTarget.CLAUDE_MD -> "CLAUDE.md"
    PeekTarget.AGENTS_MD -> "AGENTS.md"
    PeekTarget.MEMORY -> "Memory (${context.memory.size})"
}

@Composable
private fun PeekTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Scrollable monospace file preview in an inset panel (radius 12dp). */
@Composable
private fun FilePanel(content: String, truncated: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .heightIn(max = 360.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(
                text = content.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (truncated) {
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = "Truncated — full file on the host",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Memory tab: note filenames only — reading the notes stays on the host. */
@Composable
private fun MemoryPanel(notes: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        if (notes.isEmpty()) {
            Text(
                text = "No memory notes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            notes.forEach { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}
