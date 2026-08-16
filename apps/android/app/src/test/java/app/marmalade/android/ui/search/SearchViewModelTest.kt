package app.marmalade.android.ui.search

import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.SearchCorpus
import app.marmalade.android.rpc.types.SearchHit
import app.marmalade.android.rpc.types.SearchMessagesResponse
import app.marmalade.android.rpc.types.SearchRoles
import app.marmalade.android.rpc.types.SearchSessionInfo
import app.marmalade.android.rpc.types.SearchSorts
import app.marmalade.android.rpc.types.WorkspaceInfo
import app.marmalade.android.rpc.types.WorkspaceListResponse
import app.marmalade.android.search.SearchScopeSelection
import app.marmalade.android.ui.settings.StubJsonRpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * State transitions for [SearchViewModel] against a fake gateway — the
 * digital-twin bar this repo prefers over ADB.
 *
 * What is worth pinning here is not "it calls the RPC" but the things that go
 * wrong on a real device: a superseded reply overwriting current results, a
 * too-short query hitting the wire, load-more replacing instead of appending,
 * and a scope flip racing a keystroke.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** One recorded `search.messages` call, as it went on the wire. */
    private data class Call(
        val query: String,
        val scope: SearchScopeSelection,
        val role: String?,
        val includeArchived: Boolean,
        val sort: String,
        val limit: Int,
        val offset: Int,
    )

    private class FakeRpc(
        private val workspaces: List<WorkspaceInfo> = emptyList(),
        private val respond: (Call) -> SearchMessagesResponse = { page() },
    ) : MarmaladeRpc(client = StubJsonRpcClient) {
        val calls = mutableListOf<Call>()
        var workspaceCalls = 0

        override suspend fun searchMessages(
            query: String,
            scope: SearchScopeSelection,
            role: String?,
            since: Long?,
            includeArchived: Boolean,
            sort: String,
            limit: Int,
            offset: Int,
        ): SearchMessagesResponse {
            val call = Call(query, scope, role, includeArchived, sort, limit, offset)
            calls += call
            return respond(call)
        }

        override suspend fun workspaceList(): WorkspaceListResponse {
            workspaceCalls++
            return WorkspaceListResponse(workspaces)
        }
    }

    private class FailingRpc(private val error: Throwable) : MarmaladeRpc(client = StubJsonRpcClient) {
        override suspend fun searchMessages(
            query: String,
            scope: SearchScopeSelection,
            role: String?,
            since: Long?,
            includeArchived: Boolean,
            sort: String,
            limit: Int,
            offset: Int,
        ): SearchMessagesResponse = throw error

        override suspend fun workspaceList() = WorkspaceListResponse(emptyList())
    }

    private fun vm(
        rpc: MarmaladeRpc,
        fixedSessionId: String? = null,
        supported: Boolean = true,
        archiveSupported: Boolean = false,
    ) = SearchViewModel(
        rpc = rpc,
        fixedSessionId = fixedSessionId,
        supported = supported,
        archiveSupported = archiveSupported,
        io = dispatcher,
        debounceMillis = DEBOUNCE,
    )

    // ── the short-query floor ───────────────────────────────────────────────

    @Test
    fun `a one-character query never reaches the wire`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc)

        model.setQuery("s")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(rpc.calls.isEmpty())
        assertFalse(model.uiState.value.searched)
        assertFalse(model.uiState.value.queryIsSearchable)
    }

    @Test
    fun `clearing a query back under the floor drops the stale results`() = runTest(dispatcher) {
        val rpc = FakeRpc(respond = { page(hits = listOf(hit("s1", "m1"))) })
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, model.uiState.value.hits.size)

        model.setQuery("")
        dispatcher.scheduler.advanceUntilIdle()

        // Leaving hits on screen under a query that didn't produce them is a lie.
        assertTrue(model.uiState.value.hits.isEmpty())
        assertEquals(0, model.uiState.value.total)
        assertFalse(model.uiState.value.searched)
    }

    // ── debounce ────────────────────────────────────────────────────────────

    @Test
    fun `a typing burst collapses into one query for the final text`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc)

        "seen_at".forEachIndexed { index, _ ->
            model.setQuery("seen_at".take(index + 1))
            dispatcher.scheduler.advanceTimeBy(DEBOUNCE / 3)
        }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, rpc.calls.size)
        assertEquals("seen_at", rpc.calls.single().query)
    }

    @Test
    fun `the query is trimmed before it is sent`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc)

        model.setQuery("  unread badge  ")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("unread badge", rpc.calls.single().query)
        // The FIELD keeps what the user typed — trimming as you type steals the
        // space you are about to type a word after.
        assertEquals("  unread badge  ", model.uiState.value.query)
    }

    // ── results ─────────────────────────────────────────────────────────────

    @Test
    fun `a successful page populates hits, total and the sessions map`() = runTest(dispatcher) {
        val rpc = FakeRpc(
            respond = {
                page(
                    total = 23,
                    hits = listOf(hit("s1", "m1"), hit("s1", "m2")),
                    sessions = mapOf("s1" to session("Fix unread badge", "ws-client")),
                )
            },
        )
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(23, state.total)
        assertEquals(listOf("m1", "m2"), state.hits.map { it.message_id })
        assertEquals("ws-client", state.sessions["s1"]?.workspace_id)
        assertTrue(state.searched)
        assertFalse(state.loading)
        assertNull(state.error)
        // Consecutive same-session hits fold under ONE header.
        assertEquals(3, state.rows.size)
    }

    @Test
    fun `a failure surfaces the message and clears the list`() = runTest(dispatcher) {
        val model = vm(FailingRpc(IllegalStateException("search not configured")))

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()

        val state = model.uiState.value
        assertEquals("search not configured", state.error)
        assertTrue(state.hits.isEmpty())
        assertTrue(state.searched)
        assertFalse(state.loading)
    }

    @Test
    fun `retry re-runs the current criteria`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()
        model.retry()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, rpc.calls.size)
        assertEquals(listOf("seen_at", "seen_at"), rpc.calls.map { it.query })
    }

    // ── pagination ──────────────────────────────────────────────────────────

    @Test
    fun `loadMore appends the next page and advances the offset`() = runTest(dispatcher) {
        val rpc = FakeRpc(
            respond = { call ->
                if (call.offset == 0) {
                    page(total = 3, hits = listOf(hit("s1", "m1"), hit("s1", "m2")))
                } else {
                    page(total = 3, hits = listOf(hit("s2", "m3")))
                }
            },
        )
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.canLoadMore)

        model.loadMore()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(0, 2), rpc.calls.map { it.offset })
        assertEquals(listOf("m1", "m2", "m3"), model.uiState.value.hits.map { it.message_id })
        assertFalse(model.uiState.value.canLoadMore)
    }

    @Test
    fun `loadMore is a no-op once the page holds everything`() = runTest(dispatcher) {
        val rpc = FakeRpc(respond = { page(total = 1, hits = listOf(hit("s1", "m1"))) })
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()
        model.loadMore()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, rpc.calls.size)
    }

    @Test
    fun `a new query supersedes an in-flight page instead of merging with it`() =
        runTest(dispatcher) {
            val rpc = FakeRpc(
                respond = { call ->
                    if (call.query == "alpha") {
                        page(total = 9, hits = listOf(hit("s1", "old")))
                    } else {
                        page(total = 1, hits = listOf(hit("s2", "new")))
                    }
                },
            )
            val model = vm(rpc)

            model.setQuery("alpha")
            dispatcher.scheduler.advanceUntilIdle()
            model.loadMore() // page 2 of "alpha" is now in flight
            model.setQuery("beta") // …and immediately superseded
            dispatcher.scheduler.advanceUntilIdle()

            // The stale alpha page must not land on top of the beta results.
            assertEquals(listOf("new"), model.uiState.value.hits.map { it.message_id })
            assertEquals(1, model.uiState.value.total)
        }

    // ── criteria that change the result set ─────────────────────────────────

    @Test
    fun `flipping sort re-queries with the new sort`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()
        model.setSort(SearchSorts.RECENT)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(SearchSorts.RANK, SearchSorts.RECENT),
            rpc.calls.map { it.sort },
        )
    }

    @Test
    fun `opting into archived re-queries`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()
        model.setIncludeArchived(true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(false, true), rpc.calls.map { it.includeArchived })
    }

    @Test
    fun `narrowing the role re-queries`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()
        model.setRole(SearchRoles.USER)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(null, SearchRoles.USER), rpc.calls.map { it.role })
    }

    @Test
    fun `toggling scope chips re-queries and clearScope widens back to everywhere`() =
        runTest(dispatcher) {
            val rpc = FakeRpc()
            val model = vm(rpc)

            model.setQuery("seen_at")
            dispatcher.scheduler.advanceUntilIdle()
            model.toggleWorkspace("ws-client")
            dispatcher.scheduler.advanceUntilIdle()
            model.toggleQuickChats()
            dispatcher.scheduler.advanceUntilIdle()
            model.clearScope()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    SearchScopeSelection.EVERYWHERE,
                    SearchScopeSelection(workspaceIds = setOf("ws-client")),
                    SearchScopeSelection(workspaceIds = setOf("ws-client"), quickChats = true),
                    SearchScopeSelection.EVERYWHERE,
                ),
                rpc.calls.map { it.scope },
            )
            assertTrue(model.uiState.value.scope.isEverywhere)
        }

    @Test
    fun `a scope change with no query does not hit the wire`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc)

        model.toggleQuickChats()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(rpc.calls.isEmpty())
        assertTrue(model.uiState.value.scope.quickChats)
    }

    // ── peek ────────────────────────────────────────────────────────────────

    @Test
    fun `peek expands and collapses without a second round-trip`() = runTest(dispatcher) {
        val rpc = FakeRpc(respond = { page(total = 1, hits = listOf(hit("s1", "m1"))) })
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()

        model.togglePeek("m1")
        assertEquals("m1", model.uiState.value.peekedMessageId)
        model.togglePeek("m1")
        assertNull(model.uiState.value.peekedMessageId)
        // The full text rode in with the hit — peek is free.
        assertEquals(1, rpc.calls.size)
    }

    @Test
    fun `a new query collapses an open peek`() = runTest(dispatcher) {
        val rpc = FakeRpc(respond = { page(total = 1, hits = listOf(hit("s1", "m1"))) })
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()
        model.togglePeek("m1")

        model.setQuery("something else")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(model.uiState.value.peekedMessageId)
    }

    // ── find-in-conversation (scope of one) ─────────────────────────────────

    @Test
    fun `a fixed session locks the scope to session_ids`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc, fixedSessionId = "20260722_091200_abc")

        model.setQuery("boundary")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            SearchScopeSelection.ofSession("20260722_091200_abc"),
            rpc.calls.single().scope,
        )
    }

    @Test
    fun `scope controls are inert at scope-of-one`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc, fixedSessionId = "s-fixed")

        model.toggleWorkspace("ws-client")
        model.toggleQuickChats()
        model.clearScope()
        model.setQuery("boundary")
        dispatcher.scheduler.advanceUntilIdle()

        // Widening out of the conversation you asked to search inside would be
        // a different feature; the scope stays exactly where it was put.
        assertEquals(SearchScopeSelection.ofSession("s-fixed"), rpc.calls.single().scope)
        assertTrue(rpc.calls.single().scope.sessionIds.isNotEmpty())
    }

    @Test
    fun `find-in-conversation does not fetch the workspace list`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        vm(rpc, fixedSessionId = "s-fixed")
        dispatcher.scheduler.advanceUntilIdle()

        // No scope chips to label, so no reason to ask.
        assertEquals(0, rpc.workspaceCalls)
    }

    // ── feature gating ──────────────────────────────────────────────────────

    @Test
    fun `an unsupported daemon is never queried at all`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc, supported = false)

        model.setQuery("seen_at")
        model.loadMore()
        model.retry()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(rpc.calls.isEmpty())
        assertEquals(0, rpc.workspaceCalls)
        assertFalse(model.uiState.value.supported)
    }

    // ── workspace labels ────────────────────────────────────────────────────

    @Test
    fun `the workspace list loads once for the scope chips`() = runTest(dispatcher) {
        val rpc = FakeRpc(workspaces = listOf(workspace("ws-client", "Marmalade Client")))
        val model = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, rpc.workspaceCalls)
        assertEquals(listOf("Marmalade Client"), model.uiState.value.workspaces.map { it.name })
    }

    @Test
    fun `a workspace-list failure leaves search working`() = runTest(dispatcher) {
        val rpc = object : MarmaladeRpc(client = StubJsonRpcClient) {
            var searchCalls = 0
            override suspend fun workspaceList(): WorkspaceListResponse =
                throw IllegalStateException("workspaces down")

            override suspend fun searchMessages(
                query: String,
                scope: SearchScopeSelection,
                role: String?,
                since: Long?,
                includeArchived: Boolean,
                sort: String,
                limit: Int,
                offset: Int,
            ): SearchMessagesResponse {
                searchCalls++
                return page(total = 1, hits = listOf(hit("s1", "m1")))
            }
        }
        val model = vm(rpc)

        model.setQuery("seen_at")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, rpc.searchCalls)
        assertTrue(model.uiState.value.workspaces.isEmpty())
        // Chips are cosmetic; losing them must not look like a search failure.
        assertNull(model.uiState.value.error)
        assertEquals(1, model.uiState.value.hits.size)
    }

    // ── the archive corpus ──────────────────────────────────────────────────

    @Test
    fun `the archive toggle flips the corpus and re-queries`() = runTest(dispatcher) {
        val rpc = FakeRpc()
        val model = vm(rpc, archiveSupported = true)

        model.setQuery("wake word")
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(rpc.calls.last().scope.isArchive)

        model.toggleArchive()
        dispatcher.scheduler.advanceUntilIdle()

        // A corpus flip is a result-set change, so it must re-run through the
        // same debounce path a keystroke does — not sit there showing live hits
        // under an Archive chip.
        assertTrue(rpc.calls.last().scope.isArchive)
        assertTrue(model.uiState.value.isArchive)
    }

    @Test
    fun `the archive toggle is inert without the hello feature`() = runTest(dispatcher) {
        // Ungated, an older daemon ignores the unknown scope.corpus and answers
        // with LIVE hits — the exact silent-wrong-results case the gate exists
        // to prevent.
        val rpc = FakeRpc()
        val model = vm(rpc, archiveSupported = false)

        model.setQuery("wake word")
        dispatcher.scheduler.advanceUntilIdle()
        model.toggleArchive()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(model.uiState.value.isArchive)
        assertTrue(rpc.calls.none { it.scope.isArchive })
    }

    @Test
    fun `find-in-conversation never offers the archive`() = runTest(dispatcher) {
        // Scope-of-one is a LIVE session by definition; the archive has nothing
        // to say about it however capable the daemon is.
        val model = vm(FakeRpc(), fixedSessionId = "s-live", archiveSupported = true)
        assertFalse(model.uiState.value.archiveSupported)
        model.toggleArchive()
        assertFalse(model.uiState.value.isArchive)
    }

    @Test
    fun `include_archived is not sent in the archive corpus`() = runTest(dispatcher) {
        // The daemon ignores it there (nothing in the archive carries an
        // archived flag), and the user's live-corpus preference must survive.
        val rpc = FakeRpc()
        val model = vm(rpc, archiveSupported = true)

        model.setQuery("compaction")
        model.setIncludeArchived(true)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(rpc.calls.last().includeArchived)

        model.toggleArchive()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(rpc.calls.last().includeArchived)
        // Kept in state, so flipping back restores what the user asked for.
        assertTrue(model.uiState.value.includeArchived)

        model.toggleArchive()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(rpc.calls.last().includeArchived)
    }

    @Test
    fun `widening the scope stays in the archive`() = runTest(dispatcher) {
        // "Search everywhere instead" must widen, not silently switch corpus —
        // that would answer a different question than the one asked.
        val rpc = FakeRpc()
        val model = vm(rpc, archiveSupported = true)

        model.setQuery("tithe")
        model.toggleArchive()
        model.toggleWorkspace("ws-finance")
        dispatcher.scheduler.advanceUntilIdle()

        model.clearScope()
        dispatcher.scheduler.advanceUntilIdle()

        val last = rpc.calls.last().scope
        assertTrue(last.isEverywhere)
        assertTrue(last.isArchive)
    }

    @Test
    fun `an archive hit routes to the transcript viewer, never session open`() =
        runTest(dispatcher) {
            // The whole point of the corpus marker: this session_id is a Claude
            // Code UUID the daemon's session table has never heard of, so
            // session detail would resume-fail on a dead id.
            val rpc = FakeRpc(
                respond = {
                    page(
                        total = 1,
                        hits = listOf(hit("arc-uuid", "am1")),
                        sessions = mapOf("arc-uuid" to archiveSession()),
                    )
                },
            )
            val model = vm(rpc, archiveSupported = true)

            model.setQuery("wake word")
            model.toggleArchive()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                SearchOpenTarget.ArchiveTranscript("arc-uuid"),
                model.openTargetFor(hit("arc-uuid", "am1")),
            )
            assertTrue(model.uiState.value.isArchiveSession("arc-uuid"))
        }

    @Test
    fun `a live hit still opens its session`() = runTest(dispatcher) {
        val rpc = FakeRpc(
            respond = {
                page(
                    total = 1,
                    hits = listOf(hit("s1", "m1")),
                    sessions = mapOf("s1" to session("Fix unread badge", "ws-client")),
                )
            },
        )
        val model = vm(rpc, archiveSupported = true)

        model.setQuery("unread")
        dispatcher.scheduler.advanceUntilIdle()

        // The POSITION travels with the target: this is what lets the chat
        // screen open at the matched message instead of the live end.
        assertEquals(
            SearchOpenTarget.LiveSession("s1", seq = 42L, messageId = "m1"),
            model.openTargetFor(hit("s1", "m1", seq = 42L)),
        )
        assertFalse(model.uiState.value.isArchiveSession("s1"))
    }

    @Test
    fun `the committed query lags the field and names what the hits answer`() =
        runTest(dispatcher) {
            // A deep link must carry the term the user SAW matched. The raw
            // field can have moved on inside the debounce window, and a
            // navigator opened on that half-typed word would walk a different
            // query than the one that was tapped.
            val model = vm(FakeRpc(respond = { page(total = 1, hits = listOf(hit("s1", "m1"))) }))

            model.setQuery("unread")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("unread", model.uiState.value.committedQuery)

            model.setQuery("unread ba")
            assertEquals("unread ba", model.uiState.value.query)
            assertEquals("unread", model.uiState.value.committedQuery)

            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("unread ba", model.uiState.value.committedQuery)
        }

    @Test
    fun `a query too short to send clears the committed one`() = runTest(dispatcher) {
        val model = vm(FakeRpc(respond = { page(total = 1, hits = listOf(hit("s1", "m1"))) }))

        model.setQuery("unread")
        dispatcher.scheduler.advanceUntilIdle()
        model.setQuery("u")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("", model.uiState.value.committedQuery)
    }

    @Test
    fun `a session the page never described is treated as live`() = runTest(dispatcher) {
        // Absence of a corpus marker means live everywhere else on the wire, so
        // an unknown id must not become "read-only" by accident — that would
        // strand a real session behind a viewer it doesn't belong in.
        val model = vm(FakeRpc(), archiveSupported = true)
        assertEquals(
            SearchOpenTarget.LiveSession("nope", seq = 1L, messageId = "m1"),
            model.openTargetFor(hit("nope", "m1")),
        )
    }

    private companion object {
        const val DEBOUNCE = 300L

        fun hit(session: String, messageId: String, seq: Long = 1L) = SearchHit(
            session_id = session,
            message_id = messageId,
            seq = seq,
            role = SearchRoles.USER,
            ts = 1_753_000_000_000L,
            snippet = "snippet",
            text = "full text",
        )

        fun session(title: String, workspaceId: String?) = SearchSessionInfo(
            title = title,
            workspace_id = workspaceId,
            archived = false,
            last_active = 1_753_000_000_000L,
        )

        /** A session entry from the pre-daemon corpus — read-only, not openable. */
        fun archiveSession(title: String? = null) = SearchSessionInfo(
            title = title,
            workspace_id = "ws-client",
            archived = false,
            last_active = 1_699_000_000_000L,
            corpus = SearchCorpus.ARCHIVE,
        )

        fun workspace(id: String, name: String) = WorkspaceInfo(
            workspace_id = id,
            path = "/home/user/coding/$id",
            name = name,
        )

        fun page(
            total: Int = 0,
            hits: List<SearchHit> = emptyList(),
            sessions: Map<String, SearchSessionInfo> = emptyMap(),
        ) = SearchMessagesResponse(total = total, hits = hits, sessions = sessions)
    }
}
