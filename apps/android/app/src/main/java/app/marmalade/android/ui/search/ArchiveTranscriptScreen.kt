package app.marmalade.android.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.rpc.types.SearchArchiveMessage
import app.marmalade.android.rpc.types.SearchRoles
import app.marmalade.android.ui.sessions.formatRelativeTimestamp

/**
 * Read-only viewer for one PRE-DAEMON archive conversation (`search.archive`).
 *
 * Reached only from an archive search hit. What is deliberately ABSENT is the
 * design: no composer, no session actions, no interrupt, no resume, no live
 * subscription. The daemon has no way to do any of those with a Claude Code
 * UUID — this corpus is history, not state — so offering the affordance would
 * be a lie that fails on tap.
 *
 * The archive marker is therefore not decoration. It is the screen's one job
 * beyond rendering text: make it obvious you are reading something you cannot
 * reply to.
 *
 * Paging is load-more off `total`, matching the search results list rather than
 * chat's transcript replay — the archive is immutable, so there is no tail to
 * follow and nothing to scroll-anchor against.
 */
@Composable
fun ArchiveTranscriptScreen(
    viewModel: ArchiveTranscriptViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Scaffold(
        // Hosted inside MarmaladeNavHost's Scaffold, which already consumed the
        // system bars (.claude/rules/window-insets.md).
        contentWindowInsets = WindowInsets(0),
        topBar = { ArchiveTopBar(state = state, onBack = onBack) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HorizontalDivider()
            when {
                !state.supported -> UnavailableArchiveState()
                state.error != null -> ArchiveErrorState(
                    message = state.error!!,
                    onRetry = viewModel::retry,
                )
                state.loading && state.messages.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                state.loaded && state.messages.isEmpty() -> EmptyArchiveState()
                else -> ArchiveMessageList(
                    state = state,
                    onLoadMore = viewModel::loadMore,
                )
            }
        }
    }
}

/**
 * Title, cwd and the archive marker. The cwd is not a nicety: a pre-Marmalade
 * session often has no title, and the folder it ran in is then the only thing
 * that identifies the conversation.
 */
@Composable
private fun ArchiveTopBar(state: ArchiveTranscriptUiState, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to search",
                )
            }
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArchivePill()
                    Text(
                        // No title is normal in this corpus — show the uuid
                        // rather than inventing a name for it.
                        text = state.title?.takeIf { it.isNotBlank() } ?: state.archiveSessionId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.cwd.isNotBlank()) {
                    Text(
                        text = state.cwd,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.total > 0) {
                Text(
                    text = "${state.total} ${if (state.total == 1) "message" else "messages"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArchiveMessageList(
    state: ArchiveTranscriptUiState,
    onLoadMore: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = state.messages, key = { it.ordinal }) { message ->
            ArchiveMessageRow(message = message)
        }
        if (state.messages.size < state.total) {
            item(key = "archive-load-more") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = onLoadMore) {
                            Text("Load ${state.total - state.messages.size} more")
                        }
                    }
                }
            }
        }
    }
}

/**
 * One archived message.
 *
 * Styled after the search peek rather than the chat bubble: this is a document
 * you read, not a conversation you are in, and borrowing chat's bubbles would
 * invite a tap that has nowhere to go. Role reads through the same dot + label
 * vocabulary the hit rows use, so the two screens agree.
 */
@Composable
private fun ArchiveMessageRow(message: SearchArchiveMessage) {
    val isUser = message.role == SearchRoles.USER
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isUser) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                // ordinal, not the timestamp, is the position in the stream —
                // the same identity rule the live corpus uses for seq.
                text = "${if (isUser) "You" else "Agent"} · " +
                    "${formatRelativeTimestamp(message.ts)} · #${message.ordinal}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
}

@Composable
private fun EmptyArchiveState() {
    ArchiveCenteredState(
        headline = "Nothing indexed here",
        detail = "This archived session has no readable messages — the daemon indexes " +
            "prompts and replies only.",
    )
}

@Composable
private fun UnavailableArchiveState() {
    ArchiveCenteredState(
        headline = "Archive unavailable",
        detail = "This daemon doesn't advertise the archived-history index, so there " +
            "is nothing to read. The archive lives on the daemon by design — there " +
            "is no on-device copy.",
    )
}

@Composable
private fun ArchiveErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Couldn't load this transcript",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(10.dp))
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun ArchiveCenteredState(headline: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
