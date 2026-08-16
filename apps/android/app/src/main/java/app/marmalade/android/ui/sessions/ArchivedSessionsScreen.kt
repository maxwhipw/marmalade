package app.marmalade.android.ui.sessions

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.SessionListViewModel
import app.marmalade.android.SessionUiModel

/**
 * Archived sessions (`session.archive`), across every workspace.
 *
 * Archiving is shared daemon-backed metadata, never a behaviour filter — an
 * archived session still runs, resumes and receives cron fires. This screen
 * exists so archiving can't lose one: the drawer hides archived rows, and
 * deleting the Sessions screen (ADR 0013) took the old archived section with
 * it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedSessionsScreen(
    viewModel: SessionListViewModel,
    onBack: () -> Unit,
    onSessionClick: (String) -> Unit,
) {
    val archived by viewModel.archivedSessions.collectAsStateWithLifecycle()
    var contextMenuSession by remember { mutableStateOf<SessionUiModel?>(null) }
    var renameTarget by remember { mutableStateOf<SessionUiModel?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionUiModel?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Archived") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (archived.isEmpty()) {
            Text(
                text = "Nothing archived.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(20.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(archived, key = { it.id }) { session ->
                SessionRowWithContextMenu(
                    session = session,
                    contextMenuSession = contextMenuSession,
                    onClick = { onSessionClick(session.id) },
                    onLongClick = { contextMenuSession = session },
                    onDismissMenu = { contextMenuSession = null },
                    onRename = { contextMenuSession = null; renameTarget = session },
                    onDelete = { contextMenuSession = null; deleteTarget = session },
                    onClear = { contextMenuSession = null },
                    onToggleMute = {
                        contextMenuSession = null
                        viewModel.toggleMuteSession(session.id, !session.isMuted)
                    },
                    onArchive = {
                        contextMenuSession = null
                        // Every row here is archived, so this is always the
                        // un-archive direction — the row then leaves this list.
                        viewModel.archiveSession(session.id, false)
                    },
                )
            }
        }
    }

    renameTarget?.let { session ->
        RenameSessionDialog(
            currentName = session.title,
            currentEmoji = session.emoji,
            onConfirm = { name, emoji ->
                viewModel.renameSession(session.id, name, session.isGateway, emoji)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { session ->
        DeleteSessionDialog(
            sessionName = session.title,
            onConfirm = {
                viewModel.deleteSession(session.id, session.isGateway)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}
