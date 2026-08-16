package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composer send-queue: staged-while-running prompts drain into the outbox
 * (via sendMessage) on the idle edge, FIFO, one turn at a time. Desktop
 * analogue: store/composer-queue.ts + composer/index.tsx drain loop.
 *
 * Uses [FakeChatDao] + [FakeMarmaladeRpc]; _isStreaming is driven by
 * emitting message.start / message.complete with sessionId = null (which
 * ChatController treats as the bound session).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerQueueTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    private suspend fun buildHarness(): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        dao.insertSession(
            SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "server-session-main"),
        )
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

    private suspend fun Harness.startTurn() =
        rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = null))

    private suspend fun Harness.endTurn() =
        rpc.emit(GatewayEvent(type = "message.complete", payload = null, sessionId = null))

    @Test
    fun `enqueue while streaming stages instead of sending, drains FIFO on idle`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            h.startTurn()

            h.controller.enqueuePrompt("first", thinkingLevel = "off")
            h.controller.enqueuePrompt("second", thinkingLevel = "off")

            assertEquals("both staged", 2, h.controller.boundQueue.first().size)
            assertTrue(
                "nothing reaches the outbox while the turn runs",
                h.dao.snapshot().third.isEmpty(),
            )

            h.endTurn()

            // Idle edge: the drain worker hands the HEAD to sendMessage
            // (outbox insert) and waits for the next turn to start before
            // touching the second entry.
            val outboxAfterFirst = h.dao.snapshot().third
            assertEquals("head drained into the outbox", 1, outboxAfterFirst.size)
            assertTrue(outboxAfterFirst.single().contentJson.contains("first"))
            assertEquals(
                "second entry still queued until the drained turn starts and ends",
                listOf("second"),
                h.controller.boundQueue.first().map { it.text },
            )

            // The drained prompt's turn runs and completes -> second drains.
            h.startTurn()
            h.endTurn()
            assertEquals("queue empty", 0, h.controller.boundQueue.first().size)
            assertEquals("both prompts in the outbox", 2, h.dao.snapshot().third.size)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `queue does not drain while disconnected`() = runTest {
        val h = buildHarness()
        try {
            // Transport closed: healthOk = false. Even though no turn is
            // running, entries must stay queued (still editable) rather than
            // draining into the outbox.
            h.controller.enqueuePrompt("offline entry", thinkingLevel = "off")

            assertEquals(1, h.controller.boundQueue.first().size)
            assertTrue("no outbox row while disconnected", h.dao.snapshot().third.isEmpty())

            // Reconnect (idle, no streaming) -> edge-independent drain fires.
            h.rpc.openTransport()
            assertEquals("drained on reconnect", 0, h.controller.boundQueue.first().size)
            assertEquals(1, h.dao.snapshot().third.size)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `sendQueuedNow promotes to head and interrupts the running turn`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            // Bind the session for real so _sessionId hydrates (abort() is a
            // no-op without a live id). FakeMarmaladeRpc's sessionResume
            // returns "server-session-1".
            h.controller.load("main")
            h.startTurn()

            h.controller.enqueuePrompt("first", thinkingLevel = "off")
            h.controller.enqueuePrompt("urgent", thinkingLevel = "off")
            val urgent = h.controller.boundQueue.first().single { it.text == "urgent" }

            h.controller.sendQueuedNow(urgent.id)

            assertEquals(
                "explicit send-now interrupts the in-flight turn (user tap, desktop parity)" +
                    " — under marmaladed the session id never rotates, so the interrupt" +
                    " targets the id the row was seeded with",
                listOf("server-session-main"),
                h.rpc.interruptedSessions,
            )
            // abort() clears _isStreaming optimistically, so the promoted
            // entry drains immediately — ahead of "first". If the server is
            // still settling, the outbox's 4009 busy-retry absorbs the race.
            val outbox = h.dao.snapshot().third
            assertEquals("promoted entry drained first", 1, outbox.size)
            assertTrue(outbox.single().contentJson.contains("urgent"))
            assertEquals(
                "the non-promoted entry stays queued",
                listOf("first"),
                h.controller.boundQueue.first().map { it.text },
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `removeQueued deletes without sending`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            h.startTurn()
            h.controller.enqueuePrompt("doomed", thinkingLevel = "off")
            val entry = h.controller.boundQueue.first().single()

            h.controller.removeQueued(entry.id)
            h.endTurn()

            assertEquals(0, h.controller.boundQueue.first().size)
            assertTrue("never sent", h.dao.snapshot().third.isEmpty())
        } finally {
            h.tearDown()
        }
    }
}
