package app.marmalade.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.SearchArchiveMessage
import app.marmalade.android.search.SearchArchiveDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the read-only archive transcript renders.
 *
 * [messages] accumulates across pages in ordinal order; [total] is the session's
 * whole indexed message count, so `messages.size < total` is exactly the
 * load-more condition — the same shape [SearchUiState] uses for hits.
 */
data class ArchiveTranscriptUiState(
    /** The Claude Code session UUID being read. Shown when there is no title —
     *  the id is the only other identity this history has. */
    val archiveSessionId: String = "",
    val title: String? = null,
    /** The directory the original session ran in. */
    val cwd: String = "",
    /** Epoch ms. */
    val lastActive: Long = 0L,
    /** First page in flight. */
    val loading: Boolean = false,
    /** A later page in flight — the transcript stays put and the footer spins. */
    val loadingMore: Boolean = false,
    val error: String? = null,
    val total: Int = 0,
    val messages: List<SearchArchiveMessage> = emptyList(),
    /** False when the daemon does not advertise "search_archive". */
    val supported: Boolean = true,
) {
    val canLoadMore: Boolean
        get() = !loading && !loadingMore && messages.size < total

    /** True once a page has landed — separates "empty session" from "not yet". */
    val loaded: Boolean get() = total > 0 || messages.isNotEmpty()
}

/**
 * ViewModel for the read-only viewer behind an archive search hit
 * (`search.archive`).
 *
 * This is a TRANSCRIPT READER, not a session. The corpus is the maintainer's pre-daemon
 * Claude Code history: it has no daemon session id, no event stream to
 * subscribe to, no resume, and nothing to write — which is why there is no
 * composer, no session actions, and no subscription here. A viewer that grew
 * any of those would be lying about what the daemon can do with these ids.
 *
 * Paging is offset-based off [ArchiveTranscriptUiState.total], not a cursor:
 * the archive is immutable, so an offset cannot drift under the reader the way
 * it could in a live session.
 *
 * Same plain-[ViewModel] + injected-RPC shape as [SearchViewModel] (ADR 0011),
 * so it is directly constructible in tests with no Robolectric.
 */
class ArchiveTranscriptViewModel(
    private val rpc: MarmaladeRpc,
    private val archiveSessionId: String,
    /** False when the daemon lacks the "search_archive" hello feature — the
     *  screen says so and nothing is ever sent. */
    supported: Boolean = true,
    /** Injectable so tests drive the fetch on the test scheduler. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ArchiveTranscriptUiState(archiveSessionId = archiveSessionId, supported = supported),
    )
    val uiState: StateFlow<ArchiveTranscriptUiState> = _uiState.asStateFlow()

    init {
        if (supported) loadFirstPage()
    }

    /** Fetch the next page and APPEND. Ignored while a fetch is in flight or
     *  when the transcript is already whole. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.supported || !state.canLoadMore) return
        _uiState.value = state.copy(loadingMore = true, error = null)
        viewModelScope.launch {
            fetch(offset = state.messages.size)
                .onSuccess { page ->
                    _uiState.value = _uiState.value.let {
                        it.copy(
                            loadingMore = false,
                            total = page.total,
                            messages = it.messages + page.messages,
                        )
                    }
                }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(loadingMore = false, error = message(t))
                }
        }
    }

    /** Re-run the first page — the retry affordance on an error state. */
    fun retry() {
        if (!_uiState.value.supported) return
        loadFirstPage()
    }

    private fun loadFirstPage() {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            fetch(offset = 0)
                .onSuccess { page ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = null,
                        title = page.session.title,
                        cwd = page.session.cwd,
                        lastActive = page.session.last_active,
                        total = page.total,
                        messages = page.messages,
                    )
                }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = message(t),
                        total = 0,
                        messages = emptyList(),
                    )
                }
        }
    }

    private suspend fun fetch(offset: Int) = runCatchingCancellable {
        withContext(io) {
            rpc.searchArchive(
                sessionId = archiveSessionId,
                limit = SearchArchiveDefaults.LIMIT,
                offset = offset,
            )
        }
    }

    private fun message(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: "Couldn't load this transcript"

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }

    companion object {
        fun factory(
            rpc: MarmaladeRpc,
            archiveSessionId: String,
            supported: Boolean = true,
        ) = viewModelFactory {
            initializer { ArchiveTranscriptViewModel(rpc, archiveSessionId, supported) }
        }
    }
}
