package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CompletableDeferred
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
 * Data-loss regression: the composer-queue drain worker must delete a queue
 * row only AFTER the outbox row it hands to sendMessage is durably persisted.
 *
 * sendMessage launches the outbox insert asynchronously and returns a Job; the
 * drain worker joins that Job before deleting the queue row. Without the join,
 * a process death (or a slow/failed insert) between the queue delete and the
 * outbox insert would lose the prompt from BOTH tables. These tests exercise
 * the join seam with a FakeChatDao whose insertOutbox is gated.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerQueueDurabilityTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    /** FakeChatDao whose insertOutbox blocks on a gate until released (or
     *  never, simulating an insert that hasn't become durable yet). */
    private class GatedOutboxDao(
        private val insertGate: CompletableDeferred<Unit>,
    ) : FakeChatDao() {
        override suspend fun insertOutbox(row: OutboxEntity) {
            insertGate.await()
            super.insertOutbox(row)
        }
    }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    private fun buildHarness(dao: FakeChatDao): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
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
    fun `queue row survives when the outbox insert never completes`() = runTest {
        val gate = CompletableDeferred<Unit>() // never completed → insert hangs
        val dao = GatedOutboxDao(gate)
        dao.insertSession(
            SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "server-session-main"),
        )
        val h = buildHarness(dao)
        try {
            h.rpc.openTransport()
            // Turn running so the prompt stages in the queue rather than sending.
            h.rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = null))
            h.controller.enqueuePrompt("durable", thinkingLevel = "off")
            assertEquals("staged in queue", 1, h.controller.boundQueue.first().size)

            // Idle edge: drain worker hands the head to sendMessage, whose
            // outbox insert is stuck on the gate → sendJob.join() blocks →
            // deleteQueuedPrompt is NOT reached.
            h.rpc.emit(GatewayEvent(type = "message.complete", payload = null, sessionId = null))

            assertTrue(
                "outbox insert never completed, so nothing landed in the outbox",
                dao.snapshot().third.isEmpty(),
            )
            assertEquals(
                "queue row must survive — deleting it before the outbox insert " +
                    "is durable would lose the prompt from both tables",
                listOf("durable"),
                h.controller.boundQueue.first().map { it.text },
            )
        } finally {
            gate.complete(Unit)
            h.tearDown()
        }
    }

    @Test
    fun `queue row is deleted only once the outbox row exists`() = runTest {
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) } // insert proceeds
        val dao = GatedOutboxDao(gate)
        dao.insertSession(
            SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "server-session-main"),
        )
        val h = buildHarness(dao)
        try {
            h.rpc.openTransport()
            h.rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = null))
            h.controller.enqueuePrompt("durable", thinkingLevel = "off")
            assertEquals(1, h.controller.boundQueue.first().size)

            h.rpc.emit(GatewayEvent(type = "message.complete", payload = null, sessionId = null))

            val outbox = dao.snapshot().third
            assertEquals("head drained into the outbox", 1, outbox.size)
            assertTrue("outbox carries the prompt text", outbox.single().contentJson.contains("durable"))
            assertEquals("queue row removed after the durable outbox insert", 0, h.controller.boundQueue.first().size)
        } finally {
            h.tearDown()
        }
    }
}
