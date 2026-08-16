package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digital-twin tests for the marmaladed session.archive wiring (daemon commit
 * b2eb860 — additive to protocol v1). Archive is optimistic + reverting, the
 * setCurrentModel/clearConversation pattern (Fable review): a rejection is
 * SURFACED (revert + toast), never swallowed.
 *
 * Covers:
 *  - archiveSession flips the local row and commits {session_id, archived} on
 *    the wire; the flag sticks on success.
 *  - a rejected session.archive (mid-flight daemon restart / main-session
 *    refusal) reverts the optimistic flag.
 *  - an offline archive reverts without touching the server.
 *  - unarchive is the symmetric path (archived true → false).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionArchiveWiringTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    private fun buildHarness(): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        val rpc = FakeMarmaladeRpc()
        val stream = MessageStream(
            events = rpc.rpcClient.events,
            scope = scope,
            chatDao = dao,
            json = testJson,
        )
        val drainer = OutboxDrainer(
            chatDao = dao,
            transport = marmaladeRpcAdapter(rpc),
            scope = scope,
            persistence = stream.persistence,
        )
        val controller = ChatController(
            scope = scope,
            rpc = rpc,
            messageStream = stream,
            outboxDrainer = drainer,
            json = testJson,
            chatDao = dao,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return Harness(dao, rpc, controller, scope)
    }

    @Test
    fun `archive flips the row and commits archived=true on the wire`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "s-1", thinkingLevel = "off", archived = false))
            h.rpc.openTransport()

            h.controller.archiveSession("s-1", true)

            assertEquals(
                listOf(FakeMarmaladeRpc.SessionArchiveCall("s-1", true)),
                h.rpc.sessionArchiveCalls,
            )
            assertEquals("optimistic flip sticks on success", true, h.dao.getSessionByKey("s-1")?.archived)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `unarchive commits archived=false`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "s-2", thinkingLevel = "off", archived = true))
            h.rpc.openTransport()

            h.controller.archiveSession("s-2", false)

            assertEquals(
                listOf(FakeMarmaladeRpc.SessionArchiveCall("s-2", false)),
                h.rpc.sessionArchiveCalls,
            )
            assertEquals(false, h.dao.getSessionByKey("s-2")?.archived)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a rejected archive reverts the optimistic flag`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "s-3", thinkingLevel = "off", archived = false))
            h.rpc.openTransport()
            h.rpc.sessionArchiveError = IllegalStateException("session unavailable")

            h.controller.archiveSession("s-3", true)

            assertTrue("the call was attempted", h.rpc.sessionArchiveCalls.isNotEmpty())
            assertEquals(
                "a server rejection must revert the optimistic archive",
                false, h.dao.getSessionByKey("s-3")?.archived,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `offline archive reverts without calling the server`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "s-4", thinkingLevel = "off", archived = false))
            // Transport stays closed.
            h.controller.archiveSession("s-4", true)

            assertTrue("no remote call while offline", h.rpc.sessionArchiveCalls.isEmpty())
            assertEquals("offline flip reverts", false, h.dao.getSessionByKey("s-4")?.archived)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `archiving an already-archived session is a no-op`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "s-5", thinkingLevel = "off", archived = true))
            h.rpc.openTransport()

            h.controller.archiveSession("s-5", true)

            assertTrue("no redundant wire call", h.rpc.sessionArchiveCalls.isEmpty())
        } finally {
            h.tearDown()
        }
    }
}
