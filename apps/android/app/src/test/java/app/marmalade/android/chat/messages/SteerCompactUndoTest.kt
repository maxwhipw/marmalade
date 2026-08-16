package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.types.PromptSubmitAck
import app.marmalade.android.rpc.types.SessionUndoResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digital-twin coverage for the three shipped daemon features surfaced in this
 * client (T2 #6 / #11a), driven through the real ChatController + ChatEventRouter
 * + MessageStream against [FakeChatDao] / [FakeMarmaladeRpc] — no Room/Robolectric.
 *
 *  - Steer (session.steer): controller.steer() calls the RPC and renders the
 *    user's bubble from the ack, marked steered.
 *  - Compaction (session.compaction): started → the "compacting…" chip; a
 *    terminal clears it.
 *  - Undo (session.undo / session.undone): the RPC surfaces the files-not-
 *    reverted notice; the transient event drops the popped bubbles live.
 *  - The steered flag round-trips Room (mapper + MessageStream replay path).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SteerCompactUndoTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
        val toasts: MutableList<String>,
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
        val toasts = mutableListOf<String>()
        scope.launch { controller.toastMessage.collect { toasts += it } }
        return Harness(dao, rpc, controller, scope, toasts)
    }

    /** Bind "main" so _sessionId carries the server id steer/undo target. */
    private suspend fun Harness.bindMain() {
        rpc.openTransport()
        controller.load("main")
        controller.sessionId.first { it == "server-session-main" }
    }

    // ── Steer ─────────────────────────────────────────────────────────────

    @Test
    fun `steer calls session_steer and renders a steered user bubble from the ack`() = runTest {
        val h = buildHarness()
        try {
            h.bindMain()
            h.rpc.sessionSteerAck = PromptSubmitAck(message_id = "steer-1", seq = 9, ts = 1000)

            h.controller.steer("actually, use TypeScript")

            val call = h.rpc.sessionSteerCalls.single()
            assertEquals("server-session-main", call.sessionId)
            assertEquals("actually, use TypeScript", call.prompt)

            val row = h.dao.getMessagesForSessionOnce("main").single { it.id == "steer-1" }
            assertEquals("user", row.role)
            assertTrue("the steered bubble must be marked", row.steered)
            assertEquals(9L, row.serverSeq)
            assertTrue(row.contentJson.contains("actually, use TypeScript"))
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `steer with no bound session toasts instead of calling the RPC`() = runTest {
        val h = buildHarness()
        try {
            // Not bound — _sessionId is null.
            h.controller.steer("nudge")
            assertTrue("steer must not fire without a session", h.rpc.sessionSteerCalls.isEmpty())
            assertTrue(h.toasts.any { it.contains("Send a message first") })
        } finally {
            h.tearDown()
        }
    }

    // ── Compaction ──────────────────────────────────────────────────────────

    @Test
    fun `session_compaction started shows the chip and a terminal clears it`() = runTest {
        val h = buildHarness()
        try {
            assertFalse(h.controller.isCompacting.value)
            h.rpc.emit(compaction("started"))
            assertTrue("started → compacting…", h.controller.isCompacting.value)
            h.rpc.emit(compaction("completed"))
            assertFalse("completed clears the chip", h.controller.isCompacting.value)
            // failed is also a terminal
            h.rpc.emit(compaction("started"))
            h.rpc.emit(compaction("failed"))
            assertFalse("failed clears the chip", h.controller.isCompacting.value)
        } finally {
            h.tearDown()
        }
    }

    // ── Undo ─────────────────────────────────────────────────────────────────

    @Test
    fun `session_undone drops the popped bubbles from Room`() = runTest {
        val h = buildHarness()
        try {
            listOf("m_u1", "m_a1", "m_u2", "m_a2").forEachIndexed { i, id ->
                h.dao.insertMessage(
                    MessageEntity(
                        id = id,
                        sessionKey = "main",
                        role = if (id.startsWith("m_u")) "user" else "assistant",
                        contentJson = "[]",
                        timestampMs = i.toLong(),
                    ),
                )
            }
            h.rpc.emit(
                GatewayEvent(
                    type = "session.undone",
                    payload = buildJsonObject {
                        put("session_id", "server-session-main")
                        put("last_message_id", "m_a1")
                        putJsonArray("popped_message_ids") { add("m_u2"); add("m_a2") }
                    },
                    sessionId = "server-session-main",
                ),
            )
            val remaining = h.dao.getMessagesForSessionOnce("main").map { it.id }
            assertEquals(listOf("m_u1", "m_a1"), remaining)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `undo surfaces the conversation-only notice and calls session_undo`() = runTest {
        val h = buildHarness()
        try {
            h.bindMain()
            h.rpc.sessionUndoResponse = SessionUndoResponse(
                last_message_id = "m_a1",
                popped_message_ids = listOf("m_u2", "m_a2"),
                files_rewound = false,
            )
            h.controller.undo()
            assertEquals(listOf("server-session-main"), h.rpc.sessionUndoCalls)
            assertTrue(
                "the notice must make the file caveat explicit",
                h.toasts.any { it.contains("NOT reverted") },
            )
        } finally {
            h.tearDown()
        }
    }

    // ── steered round-trips ───────────────────────────────────────────────────

    @Test
    fun `message_user with steered marks the replayed row`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "message.user",
                    payload = buildJsonObject {
                        put("message_id", "mu-steer")
                        put("seq", 7)
                        put("ts", 1000)
                        put("text", "from another device")
                        put("steered", true)
                    },
                    sessionId = "server-session-main",
                ),
            )
            val row = h.dao.getMessagesForSessionOnce("main").firstOrNull { it.id == "mu-steer" }
            assertNotNull("message.user must persist", row)
            assertTrue("steered:true must round-trip into the row", row!!.steered)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `steered survives the ChatMessage to MessageEntity round-trip`() {
        val entity = ChatMessage.user(id = "m1", text = "hi", timestamp = 1L)
            .copy(steered = true)
            .toMessageEntity(sessionKey = "main", json = testJson)
        assertTrue(entity.steered)
        val back = entity.toChatMessage(testJson)
        assertTrue("steered must survive both mapper directions", back.steered)

        val plain = ChatMessage.user(id = "m2", text = "hi", timestamp = 1L)
            .toMessageEntity(sessionKey = "main", json = testJson)
        assertFalse(plain.steered)
    }

    private fun compaction(status: String): GatewayEvent = GatewayEvent(
        type = "session.compaction",
        payload = buildJsonObject { put("status", status) },
        sessionId = null, // unstamped → bound session (boundSessionId() is null too)
    )
}
