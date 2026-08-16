package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digital-twin coverage for THE daemon-managed singleton main session surfaced
 * as the assistant Home (assistant plan 2026-07-19), driven through the real
 * ChatController + ChatEventRouter + MessageStream against the fakes:
 *
 *  - session.model: changing the model on a MATERIALIZED session hits the wire
 *    (an unbound/fresh session persists locally only, for session.create).
 *  - session.clear: the main session resets in place; session.cleared drops the
 *    local rows but keeps the session.
 *  - isBoundMain: reflects the runtime's resolved main id.
 *  - origin.source: "cron"/"agent" turns round-trip source + deviceId into Room.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainSessionWiringTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
        /** The runtime's resolved main id, set post-bind in the isBoundMain tests. */
        val mainKey: MutableStateFlow<String>,
        val toasts: MutableList<String>,
    ) {
        fun tearDown() = scope.cancel()
    }

    private suspend fun buildHarness(mainKey: String = "main"): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        dao.insertSession(
            SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "server-session-main"),
        )
        val rpc = FakeMarmaladeRpc()
        val stream = MessageStream(events = rpc.rpcClient.events, scope = scope, chatDao = dao, json = testJson)
        val drainer = OutboxDrainer(
            chatDao = dao,
            transport = marmaladeRpcAdapter(rpc),
            scope = scope,
            persistence = stream.persistence,
        )
        val mainKeyFlow = MutableStateFlow(mainKey)
        val controller = ChatController(
            scope = scope,
            rpc = rpc,
            messageStream = stream,
            outboxDrainer = drainer,
            json = testJson,
            chatDao = dao,
            ioDispatcher = UnconfinedTestDispatcher(),
            mainSessionKey = mainKeyFlow,
        )
        val toasts = mutableListOf<String>()
        scope.launch { controller.toastMessage.collect { toasts += it } }
        return Harness(dao, rpc, controller, scope, mainKeyFlow, toasts)
    }

    private suspend fun Harness.bindMain() {
        rpc.openTransport()
        controller.load("main")
        controller.sessionId.first { it == "server-session-main" }
    }

    // ── session.model ───────────────────────────────────────────────────────

    @Test
    fun `setCurrentModel on a materialized session calls session_model`() = runTest {
        val h = buildHarness()
        try {
            h.bindMain()
            h.controller.setCurrentModel("claude-opus-4-8")

            assertEquals(
                FakeMarmaladeRpc.SessionModelCall("server-session-main", "claude-opus-4-8"),
                h.rpc.sessionModelCalls.single(),
            )
            assertEquals("claude-opus-4-8", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `setCurrentModel on an unbound session does not hit the wire`() = runTest {
        val h = buildHarness()
        try {
            // Never bound → _sessionId is null → the pick only rides the next
            // session.create model param (persisted locally), not session.model.
            h.controller.setCurrentModel("claude-sonnet-5")
            assertTrue("no wire call without a server id", h.rpc.sessionModelCalls.isEmpty())
            assertEquals("claude-sonnet-5", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `setCurrentModel reverts the chip when session_model is rejected mid-turn`() = runTest {
        val h = buildHarness()
        try {
            h.bindMain() // main row has no model → currentModel starts null
            h.rpc.sessionModelError = IllegalStateException("session has a turn in flight")

            h.controller.setCurrentModel("claude-opus-4-8")

            // The RPC was attempted, then the optimistic chip + row reverted.
            assertEquals("claude-opus-4-8", h.rpc.sessionModelCalls.single().model)
            assertNull("chip must not lie about a model that never took", h.controller.currentModel.value)
            assertNull("row reverted too", h.dao.getSessionByKey("main")?.model)
            assertTrue("the rejection must surface", h.toasts.any { it.contains("Couldn't switch model") })
        } finally {
            h.tearDown()
        }
    }

    // ── session.clear ─────────────────────────────────────────────────────────

    @Test
    fun `clearConversation calls session_clear for the bound session`() = runTest {
        val h = buildHarness()
        try {
            h.bindMain()
            h.controller.clearConversation()
            assertEquals(listOf("server-session-main"), h.rpc.sessionClearCalls)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_cleared drops local messages but keeps the session`() = runTest {
        val h = buildHarness()
        try {
            listOf("m1", "m2", "m3").forEachIndexed { i, id ->
                h.dao.insertMessage(
                    MessageEntity(id = id, sessionKey = "main", role = "user", contentJson = "[]", timestampMs = i.toLong()),
                )
            }
            h.rpc.emit(
                GatewayEvent(
                    type = "session.cleared",
                    payload = buildJsonObject { put("session_id", "server-session-main") },
                    sessionId = "server-session-main",
                ),
            )
            assertTrue("all messages wiped", h.dao.getMessagesForSessionOnce("main").isEmpty())
            assertNotNull("the session row survives a clear", h.dao.getSessionByKey("main"))
        } finally {
            h.tearDown()
        }
    }

    // ── isBoundMain ─────────────────────────────────────────────────────────

    // The real main session's key is a daemon id (e.g. "20260719_...") — never
    // the literal "main" placeholder — so isBoundMain is exercised against a
    // realistic-keyed session (key == gatewaySessionId == the id, post-K1).
    private suspend fun Harness.bindRealSession(id: String) {
        dao.insertSession(SessionEntity(key = id, thinkingLevel = "off", gatewaySessionId = id))
        rpc.sessionResumeResponse =
            app.marmalade.android.rpc.types.SessionResumeResponse(session_id = id)
        rpc.openTransport()
        controller.load(id)
        controller.sessionId.first { it == id }
    }

    @Test
    fun `isBoundMain is true when the bound session is the resolved main id`() = runTest {
        val h = buildHarness()
        try {
            h.bindRealSession("20260719_090000_main01")
            h.mainKey.value = "20260719_090000_main01" // runtime's session.main id
            assertTrue(h.controller.isBoundMain.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `isBoundMain is false when the bound session is not the main id`() = runTest {
        val h = buildHarness()
        try {
            h.bindRealSession("20260719_090000_other9")
            h.mainKey.value = "20260719_090000_main01" // a DIFFERENT session is main
            assertFalse(h.controller.isBoundMain.value)
        } finally {
            h.tearDown()
        }
    }

    // ── origin.source round-trip ──────────────────────────────────────────────

    @Test
    fun `message_user cron origin round-trips into the row`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(userEvent("mu-cron", origin = buildJsonObject { put("source", "cron"); put("device_id", "cron") }))
            val row = h.dao.getMessagesForSessionOnce("main").single { it.id == "mu-cron" }
            assertEquals("cron", row.originSource)
            assertFalse("cron is not voice", row.voiceOrigin)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `message_user agent origin carries the sending session`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(userEvent("mu-agent", origin = buildJsonObject { put("source", "agent"); put("device_id", "session:abc123") }))
            val row = h.dao.getMessagesForSessionOnce("main").single { it.id == "mu-agent" }
            assertEquals("agent", row.originSource)
            assertEquals("session:abc123", row.originDeviceId)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `origin fields survive the ChatMessage to MessageEntity round-trip`() {
        val entity = ChatMessage.user(id = "m1", text = "hi", timestamp = 1L)
            .copy(originSource = "agent", originDeviceId = "session:xyz")
            .toMessageEntity(sessionKey = "main", json = testJson)
        assertEquals("agent", entity.originSource)
        assertEquals("session:xyz", entity.originDeviceId)
        val back = entity.toChatMessage(testJson)
        assertEquals("agent", back.originSource)
        assertEquals("session:xyz", back.originDeviceId)

        val plain = ChatMessage.user(id = "m2", text = "hi", timestamp = 1L)
            .toMessageEntity(sessionKey = "main", json = testJson)
        assertNull(plain.originSource)
        assertNull(plain.originDeviceId)
    }

    private fun userEvent(id: String, origin: kotlinx.serialization.json.JsonObject): GatewayEvent =
        GatewayEvent(
            type = "message.user",
            payload = buildJsonObject {
                put("message_id", id)
                put("seq", 3)
                put("ts", 1000)
                put("text", "scheduled ping")
                put("origin", origin)
            },
            sessionId = "server-session-main",
        )
}
