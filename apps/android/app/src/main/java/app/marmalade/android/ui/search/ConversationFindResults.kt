package app.marmalade.android.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marmalade.android.rpc.types.SearchHit
import app.marmalade.android.search.SearchDefaults

/**
 * Find-in-conversation (lab 3, frame 2).
 *
 * Scope-of-one through the SAME `search.messages` method — `scope.session_ids =
 * [this session]`. No new backend, and it answers the daily "where did we decide
 * that?" question that long agent sessions make unscrollable.
 *
 * A jump LIST rather than inline highlighting is deliberate: with 100+ messages
 * you want to pick the right occurrence before you lose your scroll position.
 * Tapping a result peeks it in place; the row's open action closes search and
 * lands the transcript ON that message (frame 1's anchored open), entering the
 * match navigator so ↑/↓ walk the rest without coming back here.
 *
 * No session headers: there is exactly one session, and the top bar already
 * names it.
 */
@Composable
fun ConversationFindResults(
    state: SearchUiState,
    onTogglePeek: (String) -> Unit,
    /** Open the transcript AT this hit. The caller anchors and dismisses. */
    onOpenHit: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when {
            !state.supported -> Message(
                "Find in conversation needs the daemon's search index, which this " +
                    "daemon doesn't advertise.",
            )
            !state.queryIsSearchable -> Message(
                "Type at least ${SearchDefaults.MIN_QUERY_LENGTH} characters to search " +
                    "this conversation's messages.",
            )
            state.error != null -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            state.loading && state.hits.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.searched && state.hits.isEmpty() -> Message("No matches in this conversation.")
            else -> {
                Text(
                    text = "${state.total} ${if (state.total == 1) "match" else "matches"} " +
                        "in this conversation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.hits, key = { "find-hit:${it.message_id}" }) { hit ->
                        SearchHitRow(
                            hit = hit,
                            peeked = state.peekedMessageId == hit.message_id,
                            onTogglePeek = { onTogglePeek(hit.message_id) },
                            onOpenSession = onOpenHit,
                        )
                    }
                    if (state.hits.size < state.total) {
                        item(key = "find-load-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                } else {
                                    TextButton(onClick = onLoadMore) {
                                        Text("Load ${state.total - state.hits.size} more")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}
