package app.marmalade.android.ui.chat

import app.marmalade.android.chat.ChatAnchor
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.SearchHit
import app.marmalade.android.rpc.types.SearchMessagesResponse
import app.marmalade.android.rpc.types.SearchRoles
import app.marmalade.android.rpc.types.SearchSorts
import app.marmalade.android.search.SearchScopeSelection
import app.marmalade.android.ui.settings.StubJsonRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The match navigator against a fake daemon — the offline digital-twin bar this
 * repo prefers over ADB.
 *
 * What's worth pinning is not "it calls search.messages" but the four things
 * that would be wrong in a way nobody notices: the walk is in TRANSCRIPT order
 * even though the daemon answers in rank order, the ends CLAMP instead of
 * wrapping, each step re-fires an anchor carrying the query (that is what makes
 * the jump repeatable), and the denominator is the daemon's honest total even
 * when the collected set is capped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchNavigatorTest {

    private data class Call(val offset: Int, val limit: Int, val scope: SearchScopeSelection, val sort: String, val includeArchived: Boolean)

    private class FakeRpc(
        private val respond: (Call) -> SearchMessagesResponse,
    ) : MarmaladeRpc(client = StubJsonRpcClient) {
        val calls = mutableListOf<Call>()

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
            val call = Call(offset, limit, scope, sort, includeArchived)
            calls += call
            return respond(call)
        }
    }

    private class FailingRpc : MarmaladeRpc(client = StubJsonRpcClient) {
        override suspend fun searchMessages(
            query: String,
            scope: SearchScopeSelection,
            role: String?,
            since: Long?,
            includeArchived: Boolean,
            sort: String,
            limit: Int,
            offset: Int,
        ): SearchMessagesResponse = throw IllegalStateException("socket closed")
    }

    // ── entry ───────────────────────────────────────────────────────────────

    @Test
    fun `the walk is transcript order even though the daemon ranks`() = runTest {
        // Deliberately rank-shuffled: the best match is in the middle of the
        // conversation. Walking THAT order would send ↑/↓ bouncing around the
        // transcript, which is not what an arrow next to a scroll position means.
        val rpc = FakeRpc { page(total = 3, hits = listOf(hit("m2", 40), hit("m0", 10), hit("m1", 25))) }
        val nav = navigator(rpc, anchor("m1", 25))
        advance()

        assertEquals(listOf("m0", "m1", "m2"), nav.state.value.matches.map { it.message_id })
        assertEquals(listOf(10L, 25L, 40L), nav.state.value.matches.map { it.seq })
    }

    @Test
    fun `the opening anchor seats n by message id`() = runTest {
        val rpc = FakeRpc { page(total = 3, hits = listOf(hit("m0", 10), hit("m1", 25), hit("m2", 40))) }
        val nav = navigator(rpc, anchor("m1", 25))
        advance()

        assertEquals(1, nav.state.value.index)
        assertEquals("2 / 3", nav.state.value.counter)
        assertFalse(nav.state.value.loading)
    }

    @Test
    fun `an anchor with no message id falls back to seq`() = runTest {
        val rpc = FakeRpc { page(total = 3, hits = listOf(hit("m0", 10), hit("m1", 25), hit("m2", 40))) }
        val nav = navigator(rpc, ChatAnchor(SESSION, seq = 40, messageId = null, query = QUERY))
        advance()

        assertEquals(2, nav.state.value.index)
        assertEquals("3 / 3", nav.state.value.counter)
    }

    @Test
    fun `an anchor the collected set never held reports an unknown position`() = runTest {
        // Honest "– / 3" rather than silently pointing at match 1: the user is
        // standing somewhere the walk doesn't contain, and both arrows are dead.
        val rpc = FakeRpc { page(total = 3, hits = listOf(hit("m0", 10), hit("m1", 25), hit("m2", 40))) }
        val nav = navigator(rpc, ChatAnchor(SESSION, seq = 999, messageId = "gone", query = QUERY))
        advance()

        assertEquals(-1, nav.state.value.index)
        assertEquals("– / 3", nav.state.value.counter)
        assertFalse(nav.state.value.canStepBack)
        assertFalse(nav.state.value.canStepForward)
    }

    // ── stepping ────────────────────────────────────────────────────────────

    @Test
    fun `a step re-fires an anchor carrying the query`() = runTest {
        // The re-fired query is what keeps the navigator alive across the jump;
        // dropping it would make the second step exit the mode it is in.
        val rpc = FakeRpc { page(total = 3, hits = listOf(hit("m0", 10), hit("m1", 25), hit("m2", 40))) }
        val jumps = mutableListOf<ChatAnchor>()
        val nav = navigator(rpc, anchor("m1", 25), onJump = { jumps += it })
        advance()

        nav.stepForward()
        nav.stepBack()

        assertEquals(
            listOf(
                ChatAnchor(SESSION, seq = 40, messageId = "m2", query = QUERY),
                ChatAnchor(SESSION, seq = 25, messageId = "m1", query = QUERY),
            ),
            jumps,
        )
    }

    @Test
    fun `the ends clamp instead of wrapping`() = runTest {
        val rpc = FakeRpc { page(total = 3, hits = listOf(hit("m0", 10), hit("m1", 25), hit("m2", 40))) }
        val jumps = mutableListOf<ChatAnchor>()
        val nav = navigator(rpc, anchor("m0", 10), onJump = { jumps += it })
        advance()

        assertFalse(nav.state.value.canStepBack)
        nav.stepBack()
        assertEquals(0, nav.state.value.index)
        assertTrue(jumps.isEmpty())

        nav.stepForward()
        nav.stepForward()
        assertEquals(2, nav.state.value.index)
        assertFalse(nav.state.value.canStepForward)
        nav.stepForward()
        assertEquals(2, nav.state.value.index)
        assertEquals(2, jumps.size)
    }

    @Test
    fun `syncTo re-seats the readout without re-querying`() = runTest {
        // Tapping a second result for the SAME term: the match list cannot have
        // changed, so a refetch would be a round-trip that answers itself.
        val rpc = FakeRpc { page(total = 3, hits = listOf(hit("m0", 10), hit("m1", 25), hit("m2", 40))) }
        val nav = navigator(rpc, anchor("m0", 10))
        advance()
        val callsAfterLoad = rpc.calls.size

        nav.syncTo(anchor("m2", 40))

        assertEquals(2, nav.state.value.index)
        assertEquals(callsAfterLoad, rpc.calls.size)
    }

    // ── paging, cap and honesty ─────────────────────────────────────────────

    @Test
    fun `paging stops as soon as the total is covered`() = runTest {
        val rpc = FakeRpc { call ->
            page(total = 60, hits = (0 until if (call.offset == 0) 50 else 10).map { hit("m${call.offset + it}", (call.offset + it).toLong() + 1) })
        }
        val nav = navigator(rpc, anchor("m0", 1))
        advance()

        assertEquals(listOf(0, 50), rpc.calls.map { it.offset })
        assertEquals(60, nav.state.value.matches.size)
        assertFalse(nav.state.value.capped)
    }

    @Test
    fun `a huge result set is capped and says so`() = runTest {
        val rpc = FakeRpc { call ->
            page(total = 900, hits = (0 until 50).map { hit("m${call.offset + it}", (call.offset + it).toLong() + 1) })
        }
        val nav = navigator(rpc, anchor("m0", 1))
        advance()

        assertEquals(MatchNavigator.MATCH_CAP, nav.state.value.matches.size)
        // N stays the daemon's honest count — the bar admits the walk is the
        // first 250 rather than pretending 250 is all there is.
        assertEquals(900, nav.state.value.total)
        assertTrue(nav.state.value.capped)
        assertEquals("1 / 900", nav.state.value.counter)
        assertEquals(5, rpc.calls.size)
    }

    @Test
    fun `an empty page stops the loop even when the total disagrees`() = runTest {
        // A daemon whose total outruns what it will return must not spin this.
        val rpc = FakeRpc { call ->
            if (call.offset == 0) page(total = 900, hits = listOf(hit("m0", 1))) else page(total = 900, hits = emptyList())
        }
        val nav = navigator(rpc, anchor("m0", 1))
        advance()

        assertEquals(2, rpc.calls.size)
        assertEquals(1, nav.state.value.matches.size)
        assertTrue(nav.state.value.capped)
    }

    @Test
    fun `the query goes out at scope-of-one over the whole corpus`() = runTest {
        val rpc = FakeRpc { page(total = 1, hits = listOf(hit("m0", 1))) }
        navigator(rpc, anchor("m0", 1))
        advance()

        val call = rpc.calls.single()
        assertEquals(SearchScopeSelection.ofSession(SESSION), call.scope)
        assertEquals(SearchSorts.RANK, call.sort)
        // A session you deep-linked into is one you are reading, archived or not
        // — excluding archived here would disagree with the list that sent you.
        assertTrue(call.includeArchived)
        assertEquals(MatchNavigator.PAGE_SIZE, call.limit)
    }

    @Test
    fun `a failed load surfaces and leaves the arrows dead`() = runTest {
        val nav = navigator(FailingRpc(), anchor("m0", 1))
        advance()

        assertEquals("socket closed", nav.state.value.error)
        assertFalse(nav.state.value.loading)
        assertFalse(nav.state.value.canStepForward)
        assertTrue(nav.state.value.matches.isEmpty())
    }

    // ── the host: when the mode starts and stops ────────────────────────────

    @Test
    fun `an anchor without a query never enters the mode`() {
        // A notification deep-link (or any non-search jump) anchors the
        // transcript but has no match list to walk.
        val host = host()
        host.bind(SESSION)
        host.onAnchor(ChatAnchor(SESSION, seq = 5, messageId = "m0", query = null))
        assertNull(host.navigator.value)
    }

    @Test
    fun `an anchor for another session never enters the mode`() {
        val host = host()
        host.bind(SESSION)
        host.onAnchor(ChatAnchor("some-other-session", seq = 5, messageId = "m0", query = QUERY))
        assertNull(host.navigator.value)
    }

    @Test
    fun `a daemon without the search index gives no navigator`() {
        val host = MatchNavigatorHost { null }
        host.bind(SESSION)
        host.onAnchor(anchor("m0", 1))
        assertNull(host.navigator.value)
    }

    @Test
    fun `sending a message drops the navigator`() = runTest {
        val host = host(this)
        host.bind(SESSION)
        host.onAnchor(anchor("m0", 1))
        assertTrue(host.navigator.value != null)

        host.onSend()

        assertNull(host.navigator.value)
    }

    @Test
    fun `switching sessions drops the navigator`() = runTest {
        val host = host(this)
        host.bind(SESSION)
        host.onAnchor(anchor("m0", 1))
        assertTrue(host.navigator.value != null)

        host.bind("another-session")

        assertNull(host.navigator.value)
        // …and the old navigator's session is gone, so its anchors are refused.
        host.onAnchor(anchor("m0", 1))
        assertNull(host.navigator.value)
    }

    @Test
    fun `a second hit for the same term re-seats rather than rebuilding`() = runTest {
        val host = host(this)
        host.bind(SESSION)
        host.onAnchor(anchor("m0", 10))
        val first = host.navigator.value
        advance()

        host.onAnchor(anchor("m2", 40))

        assertTrue(first === host.navigator.value)
        assertEquals(2, host.navigator.value!!.state.value.index)
    }

    @Test
    fun `a different term builds a new navigator`() = runTest {
        val host = host(this)
        host.bind(SESSION)
        host.onAnchor(anchor("m0", 10))
        val first = host.navigator.value

        host.onAnchor(ChatAnchor(SESSION, seq = 10, messageId = "m0", query = "another term"))

        assertFalse(first === host.navigator.value)
        assertEquals("another term", host.navigator.value!!.query)
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private fun TestScope.advance() = testScheduler.advanceUntilIdle()

    private fun TestScope.navigator(
        rpc: MarmaladeRpc,
        anchor: ChatAnchor,
        onJump: (ChatAnchor) -> Unit = {},
    ) = MatchNavigator(
        rpc = rpc,
        sessionKey = SESSION,
        anchor = anchor,
        onJump = onJump,
        scope = this,
        io = StandardTestDispatcher(testScheduler),
    )

    /** A host whose navigators run on [scope]; the default never loads, which
     *  is all the host-level tests need. */
    private fun host(scope: CoroutineScope = TestScope()) = MatchNavigatorHost { entering ->
        MatchNavigator(
            rpc = FakeRpc { page(total = 3, hits = listOf(hit("m0", 10), hit("m1", 25), hit("m2", 40))) },
            sessionKey = entering.sessionKey,
            anchor = entering,
            onJump = {},
            scope = scope,
            io = StandardTestDispatcher((scope as? TestScope)?.testScheduler ?: TestScope().testScheduler),
        )
    }

    private companion object {
        const val SESSION = "sess-abc"
        const val QUERY = "seen_at monotonic"

        fun anchor(messageId: String, seq: Long) =
            ChatAnchor(SESSION, seq = seq, messageId = messageId, query = QUERY)

        fun hit(messageId: String, seq: Long) = SearchHit(
            session_id = SESSION,
            message_id = messageId,
            seq = seq,
            role = SearchRoles.USER,
            ts = 1_753_000_000_000L,
            snippet = "snippet",
            text = "full text",
        )

        fun page(total: Int, hits: List<SearchHit>) =
            SearchMessagesResponse(total = total, hits = hits, sessions = emptyMap())
    }
}
