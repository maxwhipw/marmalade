package app.marmalade.android.ui.chat

import app.marmalade.android.chat.ChatAnchor
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.SearchHit
import app.marmalade.android.rpc.types.SearchSorts
import app.marmalade.android.search.SearchDefaults
import app.marmalade.android.search.SearchScopeSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the match-navigator bar renders (design-lab `session-search` lab 3,
 * frame 1): the live query, "n / N", and the two clamped step arrows.
 *
 * [total] is the DAEMON's count for the whole session, not `matches.size` — a
 * conversation with 900 matches says 900, and [capped] admits that only the
 * first [MatchNavigator.MATCH_CAP] of them can be walked. Showing
 * `matches.size` instead would be the quiet lie: the number would look complete
 * and be wrong.
 */
data class MatchNavigatorState(
    /** The term that opened this navigator. Survives the jump — that is the
     *  whole point of carrying it on [ChatAnchor]. */
    val query: String,
    /** Every match in this session, in TRANSCRIPT order (seq ASC). The daemon
     *  ranks; the navigator walks the conversation, so it re-sorts. */
    val matches: List<SearchHit> = emptyList(),
    /** 0-based position in [matches], or -1 while unknown (still loading, or an
     *  anchor whose match the collected page never contained). */
    val index: Int = -1,
    /** The daemon's total for the whole session. */
    val total: Int = 0,
    val loading: Boolean = true,
    /** Transport / daemon failure. The bar still shows the query and still
     *  dismisses; it just cannot step. */
    val error: String? = null,
) {
    /** True when the session holds more matches than the navigator collected.
     *  Drives the "first N" hint — the honesty note, not a footnote. */
    val capped: Boolean get() = total > matches.size

    /** 1-based n for the "n / N" readout; 0 while the position is unknown. */
    val position: Int get() = if (index < 0) 0 else index + 1

    /** Older / lower seq. Clamped, never wrapped — the webui made the same
     *  call, and a wrap silently teleports you to the far end of a long
     *  conversation you were reading forwards. */
    val canStepBack: Boolean get() = index > 0

    val canStepForward: Boolean get() = index >= 0 && index < matches.lastIndex

    /** "2 / 8", or "– / 8" when the current position isn't in the walked set. */
    val counter: String get() = "${if (position == 0) "–" else "$position"} / $total"
}

/**
 * Walks the matches for one query inside one session, re-firing the transcript
 * anchor on every step.
 *
 * Deliberately a plain class, not a ViewModel and not composition state: the
 * interesting parts (which match is current, where the ends clamp, what a step
 * asks the transcript to do) are pure enough to unit-test offline, and burying
 * them in a `@Composable` would put them out of reach of exactly the tests that
 * pin them.
 *
 * The match list is the SAME `search.messages` query find-in-conversation runs
 * — scope-of-one, rank-sorted — so it costs no new daemon surface. Rank order
 * decides WHICH matches survive the cap; seq order decides the walk.
 */
class MatchNavigator(
    private val rpc: MarmaladeRpc,
    private val sessionKey: String,
    /** The anchor that opened the navigator. Its `query` is the term; its
     *  seq/messageId seed the initial position. */
    anchor: ChatAnchor,
    /** Re-fires the jump. Wired to `ChatController.requestAnchor` — a one-shot
     *  the transcript consumes, so stepping to the same match twice works. */
    private val onJump: (ChatAnchor) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val query: String = anchor.query.orEmpty()

    private val _state = MutableStateFlow(MatchNavigatorState(query = query))
    val state: StateFlow<MatchNavigatorState> = _state.asStateFlow()

    /** The anchor whose position the bar is reporting. Updated by [syncTo] when
     *  another entry point re-anchors under the same query. */
    private var current: ChatAnchor = anchor

    init {
        scope.launch { load() }
    }

    /**
     * Re-seat the readout on [anchor] without re-querying.
     *
     * Happens when the user taps a second result for the same term: the query
     * has not changed, so the match list has not either — only which of them we
     * are standing on.
     */
    fun syncTo(anchor: ChatAnchor) {
        current = anchor
        _state.value = _state.value.copy(index = indexOf(_state.value.matches, anchor))
    }

    /** Older match (lower seq). No-op at the top — the arrow is disabled too,
     *  but the guard belongs to the state machine, not the button. */
    fun stepBack() = step(-1)

    /** Newer match (higher seq). No-op at the bottom. */
    fun stepForward() = step(1)

    private fun step(delta: Int) {
        val state = _state.value
        val next = state.index + delta
        if (state.index < 0 || next !in state.matches.indices) return
        val hit = state.matches[next]
        _state.value = state.copy(index = next)
        val anchor = ChatAnchor(
            sessionKey = sessionKey,
            seq = hit.seq,
            messageId = hit.message_id,
            query = query,
        )
        current = anchor
        onJump(anchor)
    }

    /**
     * Collect the match list, up to [MATCH_CAP] across [PAGE_SIZE] pages.
     *
     * Paging stops early on an empty page as well as on the count, because a
     * daemon whose `total` disagrees with what it will actually return must not
     * spin this loop.
     */
    private suspend fun load() {
        val collected = mutableListOf<SearchHit>()
        var total = 0
        while (collected.size < MATCH_CAP) {
            val page = try {
                withContext(io) {
                    rpc.searchMessages(
                        query = query,
                        scope = SearchScopeSelection.ofSession(sessionKey),
                        // Archived sessions are still readable, and a session
                        // you deep-linked into is one you are reading. Excluding
                        // them here would make the navigator disagree with the
                        // result list that sent you.
                        includeArchived = true,
                        sort = SearchSorts.RANK,
                        limit = PAGE_SIZE,
                        offset = collected.size,
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message?.takeIf { it.isNotBlank() } ?: "Search failed",
                )
                return
            }
            total = page.total
            if (page.hits.isEmpty()) break
            collected += page.hits
            if (collected.size >= page.total) break
        }
        // Rank order chose the survivors; transcript order is what ↑/↓ mean.
        val walk = collected.asSequence()
            .distinctBy { it.message_id }
            .sortedBy { it.seq }
            .toList()
        _state.value = _state.value.copy(
            matches = walk,
            index = indexOf(walk, current),
            // Never claim fewer matches than we hold: a daemon total below the
            // collected size would render "3 / 2".
            total = maxOf(total, walk.size),
            loading = false,
            error = null,
        )
    }

    private fun indexOf(matches: List<SearchHit>, anchor: ChatAnchor): Int {
        // message_id first: it is the exact row. seq is the fallback for an
        // anchor minted without one (and the tie-break when a message was
        // compacted away under the same seq).
        anchor.messageId?.let { id ->
            val exact = matches.indexOfFirst { it.message_id == id }
            if (exact >= 0) return exact
        }
        return matches.indexOfFirst { it.seq == anchor.seq }
    }

    companion object {
        /** One page. The daemon's per-request maximum — fewer round-trips for
         *  the same cap. */
        @Suppress("MemberVisibilityCanBePrivate")
        const val PAGE_SIZE = SearchDefaults.MAX_LIMIT

        /** How many matches the navigator will walk. Five pages: enough that
         *  the cap is unreachable in any conversation a human reads, small
         *  enough that a pathological query ("the") cannot page forever. The
         *  webui landed on the same shape — bounded, and honest about it. */
        const val MATCH_CAP = 250
    }
}

/**
 * Owns WHEN the chat screen is in navigator mode.
 *
 * A plain class rather than a pile of `remember`s because the rules are worth
 * pinning and none of them are about drawing: an anchor for another session is
 * ignored, an anchor for the SAME term re-seats rather than re-queries, sending
 * a message drops the mode (the user stopped reading and started
 * participating — the webui made the same call), and switching sessions drops
 * it too.
 */
class MatchNavigatorHost(
    /** Builds the navigator for an entering anchor, or returns null when the
     *  daemon has no search index — a plain anchored open still works, it just
     *  arrives without a match list. */
    private val create: (ChatAnchor) -> MatchNavigator?,
) {
    private val _navigator = MutableStateFlow<MatchNavigator?>(null)
    val navigator: StateFlow<MatchNavigator?> = _navigator.asStateFlow()

    private var boundSessionKey: String? = null

    /** Follow the chat screen's bound session. A switch drops the navigator:
     *  its ↑/↓ would mint anchors this transcript refuses anyway. */
    fun bind(sessionKey: String?) {
        if (sessionKey == boundSessionKey) return
        boundSessionKey = sessionKey
        dismiss()
    }

    /** An anchor landed. Only a search-born one (it carries the query) for the
     *  bound session enters the mode. */
    fun onAnchor(anchor: ChatAnchor) {
        val term = anchor.query?.takeIf { it.isNotBlank() } ?: return
        if (anchor.sessionKey != boundSessionKey) return
        val existing = _navigator.value
        if (existing != null && existing.query == term) {
            existing.syncTo(anchor)
        } else {
            _navigator.value = create(anchor)
        }
    }

    /** The user sent (or queued) a message. */
    fun onSend() = dismiss()

    fun dismiss() {
        _navigator.value = null
    }
}
