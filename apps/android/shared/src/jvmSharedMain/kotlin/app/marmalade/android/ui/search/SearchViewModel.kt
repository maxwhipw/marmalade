package app.marmalade.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.SearchCorpus
import app.marmalade.android.rpc.types.SearchHit
import app.marmalade.android.rpc.types.SearchSessionInfo
import app.marmalade.android.rpc.types.WorkspaceInfo
import app.marmalade.android.search.SearchDefaults
import app.marmalade.android.search.SearchResultGrouping
import app.marmalade.android.search.SearchScopeSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the search screen renders. One immutable snapshot so the UI never has to
 * reconcile several flows mid-typing.
 *
 * [hits] accumulates across pages; [total] is the daemon's count for the whole
 * scope, so `hits.size < total` is exactly the load-more condition.
 */
data class SearchUiState(
    /** Raw text in the field — echoed straight back so the field is not
     *  debounce-lagged. */
    val query: String = "",
    /** The query [hits] actually answer. Lags [query] by the debounce, which is
     *  exactly why it exists: a deep link carries the term the user SAW matched,
     *  not whatever half-typed word the field holds at the moment of the tap. */
    val committedQuery: String = "",
    val scope: SearchScopeSelection = SearchScopeSelection.EVERYWHERE,
    /** null = anyone. See [app.marmalade.android.rpc.types.SearchRoles]. */
    val role: String? = null,
    val sort: String = SearchDefaults.SORT,
    val includeArchived: Boolean = SearchDefaults.INCLUDE_ARCHIVED,
    /** First page in flight (the list is empty / about to be replaced). */
    val loading: Boolean = false,
    /** A later page in flight — the list stays put and the footer spins. */
    val loadingMore: Boolean = false,
    /** Transport / daemon error for the last attempt. Honest, not swallowed. */
    val error: String? = null,
    val total: Int = 0,
    val hits: List<SearchHit> = emptyList(),
    /** Session context for [hits], keyed by session_id. Membership comes from
     *  here and NOWHERE else — never re-derive a workspace from a path. */
    val sessions: Map<String, SearchSessionInfo> = emptyMap(),
    /** The daemon's workspaces, for chip labels and the scope sheet. Labels
     *  only: which sessions belong to which workspace is the daemon's answer. */
    val workspaces: List<WorkspaceInfo> = emptyList(),
    /** True once a query has actually been answered — separates "no matches"
     *  from "you haven't typed anything yet". */
    val searched: Boolean = false,
    /** message_id of the hit expanded in place (peek). Null = none. */
    val peekedMessageId: String? = null,
    /** False when the daemon does not advertise the "search" feature. */
    val supported: Boolean = true,
    /** True when the daemon advertises "search_archive". Gates the Archive
     *  toggle; false means the archive simply isn't offered. */
    val archiveSupported: Boolean = false,
) {
    /** Searching the pre-daemon corpus rather than the daemon's own sessions. */
    val isArchive: Boolean get() = scope.isArchive

    /** True when this hit's session is read-only history. The corpus marker
     *  travels on the SESSION, not the hit, so this is the only honest answer —
     *  and it is what decides open-vs-view. */
    fun isArchiveSession(sessionId: String): Boolean =
        sessions[sessionId]?.isArchive == true

    /** The query the daemon will accept — under [SearchDefaults.MIN_QUERY_LENGTH]
     *  characters it refuses, so the client never sends it. */
    val queryIsSearchable: Boolean
        get() = query.trim().length >= SearchDefaults.MIN_QUERY_LENGTH

    val canLoadMore: Boolean
        get() = !loading && !loadingMore && hits.size < total

    /** Flat ranked rows with consecutive same-session runs folded under one
     *  header (the maintainer's locked decision — visual grouping, flat query). */
    val rows: List<SearchResultGrouping.Row>
        get() = SearchResultGrouping.group(hits)
}

/**
 * Where "open this hit" goes.
 *
 * A live hit opens its session normally. An archive hit CANNOT — its session_id
 * is a Claude Code UUID the daemon's session table has never heard of, so
 * navigating to session detail would resume-fail on a dead id. It goes to the
 * read-only transcript viewer instead. This is a type, not a boolean, so the
 * fork is impossible to forget at the call site.
 */
sealed interface SearchOpenTarget {
    /**
     * A daemon session — open it, resume it, chat in it.
     *
     * Carries the hit's POSITION, not just its session: opening a 400-message
     * conversation at its live end when the user tapped a message from three
     * weeks ago is the bug this feature exists to fix. [seq] is the ordering
     * key (.claude/rules/session-ids.md rule 2) and [messageId] the exact row
     * when the transcript already holds it.
     */
    data class LiveSession(
        val sessionId: String,
        val seq: Long,
        val messageId: String,
    ) : SearchOpenTarget

    /** Pre-daemon history — read it, nothing else. */
    data class ArchiveTranscript(val archiveSessionId: String) : SearchOpenTarget
}

/**
 * ViewModel for cross-session message search (`search.messages`) and, with
 * [fixedSessionId] set, for find-in-conversation — which is the SAME method at
 * scope-of-one, not a second code path.
 *
 * Lives in jvmSharedMain (not commonMain) only because [MarmaladeRpc] does; it
 * follows the plain-[ViewModel] + injected-RPC shape UsageViewModel established
 * (ADR 0011 / desktop-client plan Phase 1), so it is directly constructible in
 * tests with no Robolectric and no Application.
 *
 * There is no client-side index and no offline mode by design: the daemon owns
 * the corpus, so a disconnected client honestly reports the failure rather than
 * answering from whatever it happens to have cached.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val rpc: MarmaladeRpc,
    /** Non-null for find-in-conversation: scope is locked to this session and
     *  the scope UI is not offered. */
    private val fixedSessionId: String? = null,
    /** False when the daemon lacks the "search" hello feature — the screen
     *  renders an unavailable state and nothing is ever sent. */
    supported: Boolean = true,
    /** False when the daemon lacks the "search_archive" hello feature — the
     *  Archive toggle is not offered and the corpus never leaves "live". */
    archiveSupported: Boolean = false,
    /** Injectable so tests drive the fetch on the test scheduler; a hardcoded
     *  Dispatchers.IO escapes it and races the assertions. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SearchUiState(
            scope = fixedSessionId?.let { SearchScopeSelection.ofSession(it) }
                ?: SearchScopeSelection.EVERYWHERE,
            supported = supported,
            // Find-in-conversation is scoped to ONE live session, so the archive
            // is meaningless there however capable the daemon is.
            archiveSupported = archiveSupported && fixedSessionId == null,
        ),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Debounce driver. Carries everything that changes the RESULT SET, so a
     *  scope/sort/role flip re-runs through the same single-flight path as a
     *  keystroke instead of racing it. */
    private data class Criteria(
        val query: String,
        val scope: SearchScopeSelection,
        val role: String?,
        val sort: String,
        val includeArchived: Boolean,
    )

    private val criteria = MutableStateFlow(currentCriteria())

    /** Monotonic request id — a late reply from a superseded query is dropped
     *  rather than overwriting the current results. */
    private var generation = 0L

    init {
        if (supported) {
            viewModelScope.launch {
                // No drop(1) on the seeded initial criteria: debounce collapses
                // a fast typing burst into ONE emission, and if that emission
                // is the user's first real query, dropping it would swallow the
                // search. The blank seed is harmless — runFirstPage short-circuits
                // anything under the daemon's 2-character floor.
                criteria
                    .debounce(debounceMillis)
                    .distinctUntilChanged()
                    .collect { runFirstPage(it) }
            }
            if (fixedSessionId == null) loadWorkspaces()
        }
    }

    // ── user intent ─────────────────────────────────────────────────────────

    fun setQuery(query: String) = update { it.copy(query = query) }

    /** Toggle a workspace chip. No-op at scope-of-one. */
    fun toggleWorkspace(workspaceId: String) {
        if (fixedSessionId != null) return
        update { it.copy(scope = it.scope.toggleWorkspace(workspaceId)) }
    }

    /** Toggle the first-class Quick chats chip. No-op at scope-of-one. */
    fun toggleQuickChats() {
        if (fixedSessionId != null) return
        update { it.copy(scope = it.scope.toggleQuickChats()) }
    }

    /** Back to everywhere — the one-tap widen the empty state offers. Stays in
     *  the current corpus: widening is not the same as changing which history
     *  you are reading. */
    fun clearScope() {
        if (fixedSessionId != null) return
        update { it.copy(scope = it.scope.clearedNarrowing()) }
    }

    /**
     * Flip between the live and archive corpora.
     *
     * One corpus per query — the wire field is a single enum — so this REPLACES
     * the corpus rather than adding to it. The workspace/quick-chat narrowing
     * survives the flip: the daemon runs archive cwds through the same workspace
     * matcher, so "this workspace, in the archive" is a real question.
     *
     * No-op without the "search_archive" feature, so a UI bug can never send a
     * corpus an older daemon would silently ignore.
     */
    fun toggleArchive() {
        val state = _uiState.value
        if (!state.archiveSupported || fixedSessionId != null) return
        val next = if (state.scope.isArchive) SearchCorpus.LIVE else SearchCorpus.ARCHIVE
        update { it.copy(scope = it.scope.withCorpus(next)) }
    }

    /**
     * Where tapping "open" on a hit should go.
     *
     * The corpus marker lives on the session entry, not the hit, so this reads
     * [SearchUiState.isArchiveSession] rather than the current toggle — a page
     * of results can outlive a corpus flip mid-flight, and the id in hand is
     * what decides.
     */
    fun openTargetFor(hit: SearchHit): SearchOpenTarget =
        if (_uiState.value.isArchiveSession(hit.session_id)) {
            // The archive viewer pages a whole transcript; it has no anchor
            // concept, so the position is deliberately dropped here.
            SearchOpenTarget.ArchiveTranscript(hit.session_id)
        } else {
            SearchOpenTarget.LiveSession(hit.session_id, hit.seq, hit.message_id)
        }

    fun setSort(sort: String) = update { it.copy(sort = sort) }

    /** null = anyone. */
    fun setRole(role: String?) = update { it.copy(role = role) }

    fun setIncludeArchived(include: Boolean) = update { it.copy(includeArchived = include) }

    /** Expand a hit in place (peek). Tapping the open hit collapses it. The
     *  full text is already on the wire, so this costs no round-trip. */
    fun togglePeek(messageId: String) {
        _uiState.value = _uiState.value.let {
            it.copy(peekedMessageId = if (it.peekedMessageId == messageId) null else messageId)
        }
    }

    /** Fetch the next page and APPEND. Ignored while a fetch is in flight or
     *  when the page already holds everything. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.supported || !state.canLoadMore || !state.queryIsSearchable) return
        val criteriaNow = currentCriteria()
        val gen = generation
        _uiState.value = state.copy(loadingMore = true, error = null)
        viewModelScope.launch {
            val result = fetch(criteriaNow, offset = state.hits.size)
            if (gen != generation) return@launch // superseded by a new query
            result
                .onSuccess { page ->
                    _uiState.value = _uiState.value.let {
                        it.copy(
                            loadingMore = false,
                            total = page.total,
                            hits = it.hits + page.hits,
                            sessions = it.sessions + page.sessions,
                        )
                    }
                }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(loadingMore = false, error = message(t))
                }
        }
    }

    /** Re-run the current criteria — the retry affordance on an error state. */
    fun retry() {
        if (!_uiState.value.supported) return
        viewModelScope.launch { runFirstPage(currentCriteria()) }
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** Applies [transform], then republishes the criteria so a change that
     *  affects the result set re-queries through the debounce. */
    private inline fun update(transform: (SearchUiState) -> SearchUiState) {
        val next = transform(_uiState.value)
        _uiState.value = next
        criteria.value = Criteria(
            query = next.query.trim(),
            scope = next.scope,
            role = next.role,
            sort = next.sort,
            includeArchived = next.includeArchived,
        )
    }

    private fun currentCriteria(): Criteria = _uiState.value.let {
        Criteria(it.query.trim(), it.scope, it.role, it.sort, it.includeArchived)
    }

    private suspend fun runFirstPage(criteriaNow: Criteria) {
        val gen = ++generation
        if (criteriaNow.query.length < SearchDefaults.MIN_QUERY_LENGTH) {
            // Too short to send. Clear rather than leave stale hits under a
            // query that no longer produced them.
            _uiState.value = _uiState.value.copy(
                loading = false,
                loadingMore = false,
                error = null,
                total = 0,
                hits = emptyList(),
                sessions = emptyMap(),
                searched = false,
                peekedMessageId = null,
                committedQuery = "",
            )
            return
        }
        _uiState.value = _uiState.value.copy(loading = true, error = null, peekedMessageId = null)
        val result = fetch(criteriaNow, offset = 0)
        if (gen != generation) return
        result
            .onSuccess { page ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = null,
                    total = page.total,
                    hits = page.hits,
                    sessions = page.sessions,
                    searched = true,
                    committedQuery = criteriaNow.query,
                )
            }
            .onFailure { t ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = message(t),
                    total = 0,
                    hits = emptyList(),
                    sessions = emptyMap(),
                    searched = true,
                    committedQuery = criteriaNow.query,
                )
            }
    }

    private suspend fun fetch(criteriaNow: Criteria, offset: Int) = runCatchingCancellable {
        withContext(io) {
            rpc.searchMessages(
                query = criteriaNow.query,
                scope = criteriaNow.scope,
                role = criteriaNow.role,
                // include_archived is meaningless in the archive corpus (the
                // whole thing is historical and nothing carries an archived
                // flag) and the daemon ignores it. Don't send it: a field the
                // server ignores is a field the next reader has to go verify.
                // The user's live-corpus preference is kept in state, untouched.
                includeArchived = !criteriaNow.scope.isArchive && criteriaNow.includeArchived,
                sort = criteriaNow.sort,
                limit = SearchDefaults.LIMIT,
                offset = offset,
            )
        }
    }

    /** Workspace names/emoji for the chips. A failure is silent — the chips
     *  simply don't render; search itself is unaffected. */
    private fun loadWorkspaces() {
        viewModelScope.launch {
            val list = try {
                withContext(io) { rpc.workspaceList().workspaces }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                return@launch
            }
            _uiState.value = _uiState.value.copy(workspaces = list)
        }
    }

    private fun message(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: "Search failed"

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }

    companion object {
        /** Long enough that a normal typing burst is one query, short enough
         *  that the list feels type-ahead. Matches the old title-search bar. */
        const val DEBOUNCE_MILLIS = 300L

        fun factory(
            rpc: MarmaladeRpc,
            fixedSessionId: String? = null,
            supported: Boolean = true,
            archiveSupported: Boolean = false,
        ) = viewModelFactory {
            initializer { SearchViewModel(rpc, fixedSessionId, supported, archiveSupported) }
        }
    }
}
