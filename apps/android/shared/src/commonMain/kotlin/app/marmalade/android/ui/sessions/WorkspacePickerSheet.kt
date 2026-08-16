package app.marmalade.android.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marmalade.android.rpc.types.FsEntry
import app.marmalade.android.rpc.types.FsListResponse

/**
 * Modal bottom sheet that browses directories on the GATEWAY host to pick a
 * workspace / working directory for a new session. There is no local Android
 * filesystem involved — [listDir] fetches gateway-side entries over the
 * daemon's `fs.list`. Ported from desktop's `remote-picker.tsx`; directories only.
 *
 * The sheet starts at [initialPath] when given (an already-picked workspace
 * folder), otherwise at the daemon's home dir via [resolveDefaultPath] — never
 * at filesystem root, which the daemon rejects as "outside home". Navigation is
 * clamped to that home root (the daemon confines fs.list there). The "Show
 * hidden" toggle re-lists including dot-directories. The user drills in via
 * folder rows / breadcrumbs / a ".." row and confirms with "Select this folder".
 *
 * [listDir] / [resolveDefaultPath] must dispatch their network calls off the
 * main thread (the caller wraps them in `Dispatchers.IO`).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkspacePickerSheet(
    initialPath: String?,
    resolveDefaultPath: suspend () -> String?,
    listDir: suspend (path: String, showHidden: Boolean) -> FsListResponse,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Home root = the daemon's default cwd; the floor for ".." and breadcrumbs.
    var homeRoot by remember { mutableStateOf<String?>(null) }
    // Null until the start dir is known (explicit [initialPath] or resolved home).
    var currentPath by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Resolve where to start once: an explicit workspace folder if supplied,
    // else the daemon's home dir. Home is also fetched for the clamp floor even
    // when an explicit path is given. Offline → no start path → surfaced error.
    LaunchedEffect(Unit) {
        val explicit = initialPath?.takeIf { it.isNotBlank() && it != "/" }?.let(WorkspacePaths::clean)
        val home = try {
            resolveDefaultPath()?.let(WorkspacePaths::clean)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        homeRoot = home
        currentPath = explicit ?: home
        if (currentPath == null) {
            error = "Couldn't reach the daemon to find your home folder"
            loading = false
        }
    }

    // Reload the listing whenever the path or the show-hidden toggle changes.
    // On a change Compose cancels the previous effect's coroutine, so a
    // superseded fetch can't paint the wrong folder's contents — but ONLY if
    // cancellation is rethrown: runCatching would swallow the
    // CancellationException at the suspension point and the dying coroutine
    // would then overwrite the new load's `loading`/`error` state.
    LaunchedEffect(currentPath, showHidden) {
        val path = currentPath ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            val resp = listDir(path, showHidden)
            if (resp.error != null) {
                error = resp.error
                entries = emptyList()
            } else {
                entries = resp.entries.filter { it.isDirectory }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            entries = emptyList()
        }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "Choose workspace",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "The agent loads AGENTS.md / CLAUDE.md from this folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            // Breadcrumb trail (clamped to the home root) — each segment jumps
            // to that ancestor directory. Absent until the start dir resolves.
            currentPath?.let { path ->
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val crumbs = homeRoot?.let { WorkspacePaths.crumbsFrom(it, path) }
                        ?: WorkspacePaths.crumbs(path)
                    crumbs.forEachIndexed { index, crumb ->
                        val isLast = index == crumbs.lastIndex
                        Text(
                            text = crumb.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isLast) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = !isLast) { currentPath = crumb.path }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        if (!isLast) {
                            Text(
                                text = "/",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // "Show hidden" — re-lists the current folder including dot-directories.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Show hidden folders",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = showHidden, onCheckedChange = { showHidden = it })
            }

            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 360.dp)) {
                when {
                    loading -> Row(
                        modifier = Modifier.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    }
                    error != null -> Text(
                        text = "Can't read this folder ($error)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        // ".." row — up one level, disabled at the home root
                        // (the daemon confines fs.list there; going higher errors).
                        val floor = homeRoot ?: "/"
                        item {
                            FolderRow(
                                name = "..",
                                enabled = currentPath != null && currentPath != floor,
                                onClick = { currentPath?.let { currentPath = WorkspacePaths.parentDir(it) } },
                            )
                        }
                        if (entries.isEmpty()) {
                            item {
                                Text(
                                    text = "No sub-folders",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                )
                            }
                        } else {
                            items(entries, key = { it.path }) { entry ->
                                FolderRow(
                                    name = entry.name,
                                    enabled = true,
                                    onClick = { currentPath = WorkspacePaths.clean(entry.path) },
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    enabled = currentPath != null,
                    onClick = { currentPath?.let(onSelect) },
                ) { Text("Select this folder") }
            }
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (name == "..") Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}
