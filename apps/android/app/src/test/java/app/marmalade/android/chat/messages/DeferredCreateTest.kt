package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.types.SessionCreateResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deferred session create (gap triage 2026-07-11): opening a fresh
 * local-only chat must NOT materialize a daemon session (create-on-open
 * spawned an empty daemon session + adapter for every "new chat opened,
 * nothing sent"). The session is minted at FIRST SEND, by the outbox
 * drainer's resolveSessionId hook → ChatController.ensureServerSessionId
 * (session.create + the K1 key promotion).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeferredCreateTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val drainer: OutboxDrainer,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    private fun kotlinx.coroutines.test.TestScope.buildHarness(): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
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
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        return Harness(dao, rpc, controller, drainer, scope)
    }

    @Test
    fun `opening a fresh local-only chat does NOT create a server session`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            h.controller.load("chat-20260711-120000")
            runCurrent()

            assertTrue(
                "create-on-open is dead — no session.create just for looking at a fresh chat",
                h.rpc.sessionCreateCalls.isEmpty(),
            )
            assertNull("no server binding until first send", h.controller.sessionId.value)
        } finally { h.tearDown() }
    }

    @Test
    fun `first send materializes the session, K1-promotes the key, and drains through it`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.sessionCreateResponse = SessionCreateResponse(session_id = "srv-777")
            h.rpc.openTransport()
            h.drainer.start()
            h.controller.load("chat-20260711-130000")
            runCurrent()
            assertTrue(h.rpc.sessionCreateCalls.isEmpty())

            h.controller.sendMessage(message = "first words", thinkingLevel = "off")
            runCurrent()
            advanceTimeBy(200L); runCurrent()

            assertEquals("exactly one session.create, at first send", 1, h.rpc.sessionCreateCalls.size)
            val submitted = h.rpc.submittedPrompts.single()
            assertEquals("srv-777", submitted.sessionId)
            assertEquals("first words", submitted.text)
            // K1: the client-coined key promoted to the daemon's immutable id.
            assertNotNull(h.dao.getSessionByKey("srv-777"))
            assertNull(h.dao.getSessionByKey("chat-20260711-130000"))
            assertEquals("bound key follows the promotion", "srv-777", h.controller.sessionKey.value)
            assertEquals("bound id set so streaming events route", "srv-777", h.controller.sessionId.value)
            assertTrue("outbox drained", h.dao.getOutboxForSessionOnce("srv-777").isEmpty())
        } finally { h.tearDown() }
    }

    @Test
    fun `offline first send stays queued and materializes on reconnect`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.sessionCreateResponse = SessionCreateResponse(session_id = "srv-888")
            h.drainer.start()
            // Transport DOWN: open the chat and send.
            h.controller.load("chat-20260711-140000")
            runCurrent()
            h.controller.sendMessage(message = "offline first", thinkingLevel = "off")
            runCurrent()

            assertTrue("no create while offline", h.rpc.sessionCreateCalls.isEmpty())
            val queued = h.dao.getOutboxForSessionOnce("chat-20260711-140000").single()
            assertNull("queued with no server id yet", queued.serverSessionId)

            // Reconnect: the drain resolves (creates) and submits.
            h.rpc.openTransport()
            advanceTimeBy(200L); runCurrent()
            h.drainer.poke()
            advanceTimeBy(200L); runCurrent()

            assertEquals(1, h.rpc.sessionCreateCalls.size)
            assertEquals("srv-888", h.rpc.submittedPrompts.single().sessionId)
            assertTrue(h.dao.getOutboxForSessionOnce("srv-888").isEmpty())
        } finally { h.tearDown() }
    }
}
