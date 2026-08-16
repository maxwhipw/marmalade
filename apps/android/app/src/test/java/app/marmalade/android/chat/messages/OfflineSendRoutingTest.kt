package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.types.SessionResumeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the offline-send routing half of the lost-message bug
 * (maintainer, on-device 2026-07-03): a message composed while DISCONNECTED, in a
 * session the server already knows (cached stored id), must drain into THAT
 * session on reconnect — never into a freshly `session.create`'d one.
 *
 * The visible bug had two halves:
 *  1. Binding: an offline cold start bound Home to the "main" placeholder
 *     instead of the cached assistant session — covered by
 *     ResolveAssistantSessionKeyTest (placeholder cases) + the runtime's
 *     cache seed.
 *  2. Routing (this test): once bound to a REAL cached session, the offline
 *     send must queue under its key with its known server id, and the
 *     drainer must submit there without creating anything.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfflineSendRoutingTest {

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

    // Share the runTest scheduler so the drainer's debounce timers advance
    // with the test clock (a bare UnconfinedTestDispatcher() owns a separate
    // scheduler and its delays never fire).
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
    fun `offline send into a cached session resumes it on reconnect — no session create`() = runTest {
        val h = buildHarness()
        try {
            val key = "20260701_120000_abcdef"
            // The cached assistant session, seeded the way applyMainSessionKey
            // does: key = stored id, gatewaySessionId = stored id (server-known).
            h.dao.insertSession(
                SessionEntity(key = key, thinkingLevel = "off", gatewaySessionId = key),
            )

            // Transport is DOWN (Idle). Binding falls back to the cache and
            // must adopt the session's known server id.
            h.controller.load(key)
            runCurrent()
            assertEquals(key, h.controller.sessionKey.value)

            h.controller.sendMessage(message = "hello offline", thinkingLevel = "off")
            runCurrent()

            val queued = h.dao.getOutboxForSessionOnce(key).single()
            assertEquals(
                "the offline send must queue under the CACHED session's server id",
                key,
                queued.serverSessionId,
            )

            // Reconnect: the drainer submits the queued prompt.
            h.rpc.sessionResumeResponse = SessionResumeResponse(session_id = key)
            h.drainer.start()
            runCurrent()
            h.rpc.openTransport()
            advanceTimeBy(200L); runCurrent()
            h.drainer.poke()
            advanceTimeBy(200L); runCurrent()

            assertTrue(
                "reconnect must NOT create a new server session for a queued " +
                    "offline send — that is the 'New Session stole my message' bug",
                h.rpc.sessionCreateCalls.isEmpty(),
            )
            val submitted = h.rpc.submittedPrompts.single()
            assertEquals("prompt submitted to the cached session", key, submitted.sessionId)
            assertEquals("hello offline", submitted.text)
            assertTrue("outbox drained", h.dao.getOutboxForSessionOnce(key).isEmpty())
            val userRows = h.dao.getMessagesForSessionOnce(key).filter { it.role == "user" }
            assertEquals(
                "the sent message lives in the session the user was looking at",
                1,
                userRows.size,
            )
        } finally {
            h.tearDown()
        }
    }
}
