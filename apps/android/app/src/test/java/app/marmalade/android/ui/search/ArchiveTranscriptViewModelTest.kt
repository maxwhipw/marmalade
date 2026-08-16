package app.marmalade.android.ui.search

import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.SearchArchiveMessage
import app.marmalade.android.rpc.types.SearchArchiveResponse
import app.marmalade.android.rpc.types.SearchArchiveSessionInfo
import app.marmalade.android.rpc.types.SearchRoles
import app.marmalade.android.search.SearchArchiveDefaults
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
 * Paging and gating for [ArchiveTranscriptViewModel] against a fake gateway.
 *
 * What is worth pinning is the read-only-ness (nothing but `search.archive` ever
 * goes out), that load-more APPENDS rather than replaces, and that the feature
 * gate keeps a request off the wire entirely — the archive index is optional
 * daemon-side, so an ungated fetch would MethodNotFound on a real older daemon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveTranscriptViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** One recorded `search.archive` call, as it went on the wire. */
    private data class Call(val sessionId: String, val limit: Int, val offset: Int)

    private class FakeRpc(
        private val respond: (Call) -> SearchArchiveResponse,
    ) : MarmaladeRpc(client = StubJsonRpcClient) {
        val calls = mutableListOf<Call>()

        override suspend fun searchArchive(
            sessionId: String,
            limit: Int,
            offset: Int,
        ): SearchArchiveResponse {
            val call = Call(sessionId, limit, offset)
            calls += call
            return respond(call)
        }
    }

    private class FailingRpc(private val error: Throwable) :
        MarmaladeRpc(client = StubJsonRpcClient) {
        override suspend fun searchArchive(
            sessionId: String,
            limit: Int,
            offset: Int,
        ): SearchArchiveResponse = throw error
    }

    private fun vm(
        rpc: MarmaladeRpc,
        archiveSessionId: String = ARCHIVE_ID,
        supported: Boolean = true,
    ) = ArchiveTranscriptViewModel(
        rpc = rpc,
        archiveSessionId = archiveSessionId,
        supported = supported,
        io = dispatcher,
    )

    @Test
    fun `the first page loads on construction and carries the header`() =
        runTest(dispatcher) {
            val rpc = FakeRpc { pageOf(total = 250, from = 0, count = 100) }
            val model = vm(rpc)
            dispatcher.scheduler.advanceUntilIdle()

            val state = model.uiState.value
            assertEquals(1, rpc.calls.size)
            assertEquals(Call(ARCHIVE_ID, SearchArchiveDefaults.LIMIT, 0), rpc.calls.single())
            assertEquals(250, state.total)
            assertEquals(100, state.messages.size)
            assertEquals("Wake word spike", state.title)
            assertEquals("/home/user/coding/marmalade", state.cwd)
            assertFalse(state.loading)
            assertNull(state.error)
        }

    @Test
    fun `load more appends the next page in ordinal order`() = runTest(dispatcher) {
        val rpc = FakeRpc { call -> pageOf(total = 250, from = call.offset, count = 100) }
        val model = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        model.loadMore()
        dispatcher.scheduler.advanceUntilIdle()

        val state = model.uiState.value
        // Offset is the count already held — the archive is immutable, so it
        // cannot drift under the reader the way a live session's would.
        assertEquals(100, rpc.calls[1].offset)
        assertEquals(200, state.messages.size)
        assertEquals((0 until 200).toList(), state.messages.map { it.ordinal })
        assertFalse(state.loadingMore)
    }

    @Test
    fun `load more stops when the transcript is whole`() = runTest(dispatcher) {
        val rpc = FakeRpc { call -> pageOf(total = 150, from = call.offset, count = 100) }
        val model = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.canLoadMore)

        model.loadMore()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(150, model.uiState.value.messages.size)
        assertFalse(model.uiState.value.canLoadMore)

        // A tap after the end is a no-op, not a redundant round-trip.
        model.loadMore()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, rpc.calls.size)
    }

    @Test
    fun `an unsupported daemon is never asked`() = runTest(dispatcher) {
        // search.archive MethodNotFounds without the archive index, so the gate
        // has to stop the request, not just hide the result.
        val rpc = FakeRpc { pageOf(total = 1, from = 0, count = 1) }
        val model = vm(rpc, supported = false)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(rpc.calls.isEmpty())
        assertFalse(model.uiState.value.supported)

        model.loadMore()
        model.retry()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(rpc.calls.isEmpty())
    }

    @Test
    fun `a failure is reported honestly and retry re-runs the first page`() =
        runTest(dispatcher) {
            val model = vm(FailingRpc(IllegalStateException("archive session not found")))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("archive session not found", model.uiState.value.error)
            assertTrue(model.uiState.value.messages.isEmpty())
            assertFalse(model.uiState.value.loading)
        }

    @Test
    fun `an empty transcript is distinguishable from one still loading`() =
        runTest(dispatcher) {
            val rpc = FakeRpc { pageOf(total = 0, from = 0, count = 0) }
            val model = vm(rpc)
            dispatcher.scheduler.advanceUntilIdle()

            val state = model.uiState.value
            assertFalse(state.loading)
            assertTrue(state.messages.isEmpty())
            assertFalse(state.canLoadMore)
        }

    private companion object {
        const val ARCHIVE_ID = "b3f1c2de-0000-4aaa-9999-1234567890ab"

        fun pageOf(total: Int, from: Int, count: Int) = SearchArchiveResponse(
            session = SearchArchiveSessionInfo(
                title = "Wake word spike",
                cwd = "/home/user/coding/marmalade",
                last_active = 1_699_000_000_000L,
                message_count = total,
            ),
            total = total,
            messages = (from until minOf(from + count, total)).map { ordinal ->
                SearchArchiveMessage(
                    ordinal = ordinal,
                    role = if (ordinal % 2 == 0) SearchRoles.USER else SearchRoles.ASSISTANT,
                    ts = 1_699_000_000_000L + ordinal,
                    text = "message $ordinal",
                )
            },
        )
    }
}
