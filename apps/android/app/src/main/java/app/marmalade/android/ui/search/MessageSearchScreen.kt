package app.marmalade.android.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.chat.ChatAnchor
import app.marmalade.android.rpc.types.SearchHit
import app.marmalade.android.rpc.types.SearchSorts
import app.marmalade.android.search.SearchDefaults
import app.marmalade.android.search.SearchResultGrouping

/**
 * Cross-session full-text message search (labs 1–2, plus lab 3 frame 3's peek).
 *
 * Reached from the drawer's bottom app-action row — search is an APP-scoped
 * control and ADR 0013 puts app-scoped controls exactly there. The drawer is the
 * only navigator, so there is no second entry point to keep in sync.
 *
 * Shape decisions that are locked (maintainer, 2026-07-27), not preferences:
 *  - the result list is FLAT and ranked, with consecutive same-session hits
 *    folded under one header client-side. Grouping by session was rejected as a
 *    query shape because it cannot paginate.
 *  - a hit expands IN PLACE (peek) off the capped full text it already carries.
 *    Opening is the explicit second step, and since 2026-07-28 it opens the
 *    transcript AT the matched message (lab 3 frame 1): the hit's seq +
 *    message_id + the committed query ride out on a [ChatAnchor], and the chat
 *    screen enters the match navigator on arrival.
 *  - no client-local index and no offline search. When the daemon lacks the
 *    "search" feature this screen says so instead of quietly searching less.
 */
@Composable
fun MessageSearchScreen(
    viewModel: SearchViewModel,
    /** Open a LIVE session at the matched message. The anchor carries the
     *  position and the query; the host hands it to the chat controller before
     *  navigating (the route itself takes no anchor arg — the request travels
     *  on the controller, which is what lets it survive hydration). */
    onOpenSession: (ChatAnchor) -> Unit,
    /** An archive hit opens the READ-ONLY viewer, never session detail — its id
     *  is a Claude Code UUID the daemon cannot resume. [SearchViewModel.openTargetFor]
     *  picks; this screen never branches on the toggle itself. */
    onOpenArchiveTranscript: (archiveSessionId: String) -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onOpen: (SearchHit) -> Unit = { hit ->
        when (val target = viewModel.openTargetFor(hit)) {
            is SearchOpenTarget.LiveSession ->
                onOpenSession(liveHitAnchor(target, state.committedQuery))
            is SearchOpenTarget.ArchiveTranscript ->
                onOpenArchiveTranscript(target.archiveSessionId)
        }
    }
    var showScopeSheet by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    Scaffold(
        // Hosted inside MarmaladeNavHost's Scaffold, which already consumed the
        // system bars (.claude/rules/window-insets.md).
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SearchTopBar(
                state = state,
                onQueryChange = viewModel::setQuery,
                onSetSort = viewModel::setSort,
                onClose = onClose,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.supported) {
                UnavailableState()
                return@Column
            }

            ScopeStrip(
                state = state,
                onClearScope = viewModel::clearScope,
                onToggleWorkspace = viewModel::toggleWorkspace,
                onToggleQuickChats = viewModel::toggleQuickChats,
                onToggleArchive = viewModel::toggleArchive,
                onOpenSheet = { showScopeSheet = true },
            )
            MetaLine(state = state)
            HorizontalDivider()
            ResultsBody(
                state = state,
                onTogglePeek = viewModel::togglePeek,
                onOpenSession = onOpen,
                onLoadMore = viewModel::loadMore,
                onClearScope = viewModel::clearScope,
                onRetry = viewModel::retry,
            )
        }
    }

    if (showScopeSheet) {
        SearchScopeSheet(
            state = state,
            onToggleWorkspace = viewModel::toggleWorkspace,
            onToggleQuickChats = viewModel::toggleQuickChats,
            onToggleArchive = viewModel::toggleArchive,
            onSetRole = viewModel::setRole,
            onSetIncludeArchived = viewModel::setIncludeArchived,
            onDismiss = { showScopeSheet = false },
        )
    }
}

/**
 * The deep link a tapped live hit becomes.
 *
 * Pure and top-level so the threading is testable without a screen: everything
 * the transcript and the match navigator need has to survive this hop, and the
 * bug it replaced was exactly a hop that quietly dropped seq and message_id.
 *
 * [committedQuery] is the term the shown results ANSWER, not the raw field —
 * the field can have moved on mid-debounce, and the navigator would then walk a
 * different term than the one the user tapped.
 */
fun liveHitAnchor(target: SearchOpenTarget.LiveSession, committedQuery: String) = ChatAnchor(
    // sessionKey == the daemon session id after the K1 rename
    // (.claude/rules/session-ids.md rule 4), which is what the search result
    // carries and what the session-detail route takes.
    sessionKey = target.sessionId,
    seq = target.seq,
    messageId = target.messageId,
    query = committedQuery.takeIf { it.isNotBlank() },
)

@Composable
private fun SearchTopBar(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSetSort: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(end = 8.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (state.query.isEmpty()) {
                    Text(
                        text = if (state.isArchive) {
                            "Search your archived history"
                        } else {
                            "Search all messages"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
            // Best/Newest only means something on a flat ranked list — which is
            // the layout we shipped, so it earns its place in the bar.
            SortToggle(sort = state.sort, onSetSort = onSetSort)
        }
    }
}

@Composable
private fun SortToggle(sort: String, onSetSort: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(end = 4.dp)) {
        FilterChip(
            selected = sort == SearchSorts.RANK,
            onClick = { onSetSort(SearchSorts.RANK) },
            label = { Text("Best", style = MaterialTheme.typography.labelSmall) },
        )
        FilterChip(
            selected = sort == SearchSorts.RECENT,
            onClick = { onSetSort(SearchSorts.RECENT) },
            label = { Text("New", style = MaterialTheme.typography.labelSmall) },
        )
    }
}

/**
 * Workspaces + Quick chats as first-class chips, with Everywhere as the leading
 * chip. Everywhere is the ABSENCE of a scope on the wire, so selecting it just
 * clears the selection.
 *
 * The Archive chip leads, behind a divider, because it is a different AXIS: the
 * others narrow the corpus, it swaps which corpus you are in. Putting it inline
 * with the workspace chips would read as a fourth thing to OR together, which is
 * exactly what it isn't — the wire takes one corpus per query. It appears only
 * when the daemon advertises "search_archive".
 */
@Composable
private fun ScopeStrip(
    state: SearchUiState,
    onClearScope: () -> Unit,
    onToggleWorkspace: (String) -> Unit,
    onToggleQuickChats: () -> Unit,
    onToggleArchive: () -> Unit,
    onOpenSheet: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.archiveSupported) {
            FilterChip(
                selected = state.isArchive,
                onClick = onToggleArchive,
                label = { Text("Archive") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            VerticalDivider(modifier = Modifier.height(20.dp))
        }
        FilterChip(
            selected = state.scope.isEverywhere,
            onClick = onClearScope,
            label = { Text("Everywhere") },
        )
        state.workspaces.forEach { workspace ->
            FilterChip(
                selected = workspace.workspace_id in state.scope.workspaceIds,
                onClick = { onToggleWorkspace(workspace.workspace_id) },
                label = {
                    Text(listOfNotNull(workspace.emoji, workspace.name).joinToString(" "))
                },
            )
        }
        FilterChip(
            selected = state.scope.quickChats,
            onClick = onToggleQuickChats,
            label = { Text("Quick chats") },
        )
        IconButton(onClick = onOpenSheet) {
            Icon(Icons.Default.Tune, contentDescription = "Scope and filters")
        }
    }
}

/** Corpus/result line. Honest about the count, and about not having run yet.
 *  In the archive it also names the corpus — a count with no corpus beside it
 *  is the one way this screen could quietly mislead. */
@Composable
private fun MetaLine(state: SearchUiState) {
    val corpus = if (state.isArchive) "archived history · " else ""
    val text = when {
        !state.queryIsSearchable ->
            "Type at least ${SearchDefaults.MIN_QUERY_LENGTH} characters"
        state.loading -> if (state.isArchive) "Searching your history…" else "Searching…"
        !state.searched -> ""
        else -> {
            val sessionCount = state.hits.map { it.session_id }.distinct().size
            val shown = if (state.hits.size < state.total) " · showing ${state.hits.size}" else ""
            corpus + "${state.total} ${if (state.total == 1) "match" else "matches"} " +
                "in $sessionCount ${if (sessionCount == 1) "session" else "sessions"}$shown"
        }
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (state.isArchive) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ResultsBody(
    state: SearchUiState,
    onTogglePeek: (String) -> Unit,
    onOpenSession: (SearchHit) -> Unit,
    onLoadMore: () -> Unit,
    onClearScope: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.error != null -> ErrorState(message = state.error!!, onRetry = onRetry)
        state.loading && state.hits.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        !state.queryIsSearchable -> PromptState(isArchive = state.isArchive)
        state.searched && state.hits.isEmpty() -> EmptyState(state = state, onClearScope = onClearScope)
        else -> HitList(
            state = state,
            onTogglePeek = onTogglePeek,
            onOpenSession = onOpenSession,
            onLoadMore = onLoadMore,
        )
    }
}

@Composable
private fun HitList(
    state: SearchUiState,
    onTogglePeek: (String) -> Unit,
    onOpenSession: (SearchHit) -> Unit,
    onLoadMore: () -> Unit,
) {
    val rows = state.rows
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(
            items = rows,
            key = { index, row -> SearchResultGrouping.keyOf(row, index) },
        ) { _, row ->
            when (row) {
                is SearchResultGrouping.Row.SessionHeader -> SearchSessionHeader(
                    sessionId = row.sessionId,
                    info = state.sessions[row.sessionId],
                    workspaces = state.workspaces,
                )
                is SearchResultGrouping.Row.HitRow -> SearchHitRow(
                    hit = row.hit,
                    peeked = state.peekedMessageId == row.hit.message_id,
                    onTogglePeek = { onTogglePeek(row.hit.message_id) },
                    onOpenSession = onOpenSession,
                    isArchive = state.isArchiveSession(row.hit.session_id),
                )
            }
        }
        if (state.hits.size < state.total) {
            item(key = "search-load-more") {
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

@Composable
private fun PromptState(isArchive: Boolean) {
    if (isArchive) {
        CenteredState(
            headline = "Search your archived history",
            detail = "Your Claude Code conversations from before Marmalade, years deep " +
                "and read-only. Results open as a transcript — nothing in here can be " +
                "resumed or replied to. Conversations Marmalade already carries forward " +
                "are found in the live corpus instead, not twice.",
        )
        return
    }
    CenteredState(
        headline = "Search your messages",
        detail = "Message text only — prompts and replies. Tool calls, thinking and " +
            "system prompts are never searched. Use \"quotes\" for a phrase, or a " +
            "trailing * to match a prefix.",
    )
}

/**
 * Zero results NAMES what it did not search and offers the widen in one tap.
 * With a scope model this expressive, "you were scoped somewhere narrow" is the
 * single most likely reason for an empty list.
 */
@Composable
private fun EmptyState(state: SearchUiState, onClearScope: () -> Unit) {
    val scopeName = when {
        state.scope.isEverywhere -> null
        else -> {
            val names = state.workspaces
                .filter { it.workspace_id in state.scope.workspaceIds }
                .map { it.name }
                .toMutableList()
            if (state.scope.quickChats) names += "Quick chats"
            names.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (scopeName == null) "No matches" else "No matches in $scopeName",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(6.dp))
        val excluded = buildList {
            if (state.isArchive) add("only your pre-Marmalade history was searched")
            if (scopeName != null) add("other workspaces and your quick chats weren't searched")
            // The archived flag is live-corpus only — the whole archive is
            // historical, so saying "archived sessions are excluded" there would
            // name an exclusion that doesn't exist.
            if (!state.isArchive && !state.includeArchived) add("archived sessions are excluded")
            if (state.role != null) add("only one side of the conversation was searched")
        }
        if (excluded.isNotEmpty()) {
            Text(
                text = excluded.joinToString("; ").replaceFirstChar { it.uppercase() } + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (scopeName != null) {
            Spacer(Modifier.size(10.dp))
            TextButton(onClick = onClearScope) { Text("Search everywhere instead") }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Search failed",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
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

/** The daemon has no search index — say so rather than search less quietly. */
@Composable
private fun UnavailableState() {
    CenteredState(
        headline = "Search unavailable",
        detail = "This daemon doesn't advertise the search index. Message search " +
            "runs on the daemon by design — there is no on-device index to fall " +
            "back to, so results would depend on which device you were holding.",
    )
}

@Composable
private fun CenteredState(headline: String, detail: String) {
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
