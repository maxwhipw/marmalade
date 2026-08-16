package app.marmalade.android.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marmalade.android.rpc.types.SearchRoles
import app.marmalade.android.search.SearchScopeSelection

/** Layout constants for the scope sheet (no magic numbers in production files). */
private object SearchScopeSheetDefaults {
    /** Caps the sheet so a host with many workspaces scrolls instead of
     *  swallowing the whole screen. */
    val MAX_HEIGHT = 520.dp
}

/**
 * Search scope + filters (lab 1, frame 2).
 *
 * Workspaces and Quick chats are **checkboxes, not a radio group** — the ask
 * was literally "workspaces and/or quick chats". Archived is opt-in: archived
 * is what you deliberately pushed out of view, and silently ranking it back in
 * would undo that.
 *
 * The deepest-wins sentence is not decoration. The maintainer's umbrella workspace nests
 * repo workspaces, so scoping to the umbrella genuinely excludes the nested
 * ones — consistent with the session list's grouping, surprising the first
 * time, and therefore stated out loud here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScopeSheet(
    state: SearchUiState,
    onToggleWorkspace: (String) -> Unit,
    onToggleQuickChats: () -> Unit,
    onToggleArchive: () -> Unit,
    onSetRole: (String?) -> Unit,
    onSetIncludeArchived: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SearchScopeSheetDefaults.MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Search scope",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.size(4.dp))

            // WHICH CORPUS comes first and reads as a switch, not a checkbox:
            // it is one corpus per query, not another thing to OR in. The
            // workspace narrowing below applies inside either one — the daemon
            // runs archive cwds through the same matcher.
            if (state.archiveSupported) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Search archived history",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Your pre-Marmalade Claude Code conversations. " +
                                "Read-only — results open as a transcript, not a session.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.isArchive, onCheckedChange = { onToggleArchive() })
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            Text(
                text = SearchScopeSelection.DEEPEST_WINS_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.size(12.dp))

            state.workspaces.forEach { workspace ->
                CheckRow(
                    lead = workspace.emoji,
                    title = workspace.name,
                    subtitle = workspace.path,
                    checked = workspace.workspace_id in state.scope.workspaceIds,
                    onToggle = { onToggleWorkspace(workspace.workspace_id) },
                )
            }

            CheckRow(
                lead = "💬",
                title = "Quick chats",
                subtitle = "sessions in no workspace",
                checked = state.scope.quickChats,
                onToggle = onToggleQuickChats,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Hidden in the archive: `include_archived` is a live-corpus flag
            // the daemon ignores there, and the whole archive is historical
            // anyway. Showing a dead switch would imply the archive has a
            // hidden half this could reveal. It is HIDDEN, not disabled — the
            // user's live-corpus preference survives the trip untouched.
            if (!state.isArchive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Include archived sessions",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Off by default — archived is what you pushed out of view.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.includeArchived, onCheckedChange = onSetIncludeArchived)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            Text(
                text = "Who said it",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.size(6.dp))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.role == null,
                    onClick = { onSetRole(null) },
                    label = { Text("Anyone") },
                )
                FilterChip(
                    selected = state.role == SearchRoles.USER,
                    onClick = { onSetRole(SearchRoles.USER) },
                    label = { Text("Mine") },
                )
                FilterChip(
                    selected = state.role == SearchRoles.ASSISTANT,
                    onClick = { onSetRole(SearchRoles.ASSISTANT) },
                    label = { Text("Agent") },
                )
            }
        }
    }
}

@Composable
private fun CheckRow(
    lead: String?,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead != null) {
            Text(text = lead, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
