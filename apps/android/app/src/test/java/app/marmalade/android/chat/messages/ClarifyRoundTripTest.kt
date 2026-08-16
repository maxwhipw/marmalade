package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.PromptKind
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clarify round-trip client half (daemon 2026-07-18): the daemon bridges
 * AskUserQuestion into clarify.request → the card renders → clarify.respond
 * carries {session_id, request_id, answers/response}; an empty respond is a
 * dismissal; clarify.resolved (answered anywhere) clears the card.
 * Mirrors M2ApprovalsTest.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClarifyRoundTripTest {

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
        dao.insertSession(SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "live-main"))
        val rpc = FakeMarmaladeRpc()
        val stream = MessageStream(events = rpc.rpcClient.events, scope = scope, chatDao = dao, json = testJson)
        val drainer = OutboxDrainer(chatDao = dao, transport = marmaladeRpcAdapter(rpc), scope = scope, persistence = stream.persistence)
        val controller = ChatController(
            scope = scope, rpc = rpc, messageStream = stream, outboxDrainer = drainer,
            json = testJson, chatDao = dao, ioDispatcher = UnconfinedTestDispatcher(),
        )
        return Harness(dao, rpc, controller, scope)
    }

    /** The daemon's exact clarify.request shape (router.ts makeSessionClarifies). */
    private fun daemonClarifyRequest(rid: String, sessionId: String) = GatewayEvent(
        type = "clarify.request",
        payload = buildJsonObject {
            put("request_id", JsonPrimitive(rid))
            put(
                "questions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("question", JsonPrimitive("Which library should we use?"))
                            put("header", JsonPrimitive("Library"))
                            put("multi_select", JsonPrimitive(false))
                            put(
                                "options",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("label", JsonPrimitive("Ktor"))
                                            put("description", JsonPrimitive("Kotlin-native HTTP"))
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("label", JsonPrimitive("OkHttp"))
                                            put("description", JsonPrimitive("Battle-tested"))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put("seq", JsonPrimitive(42))
            put("ts", JsonPrimitive(1_700_000_000_000L))
        },
        sessionId = sessionId,
    )

    @Test
    fun `daemon clarify_request parses - card keyed by the daemon-minted request_id`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonClarifyRequest("rid-q1", "live-main"))
            val prompt = h.controller.pendingPrompts.first().single()
            assertEquals("rid-q1", prompt.requestId)
            assertEquals(PromptKind.Clarify, prompt.kind)
            assertEquals("The agent has a question", prompt.title)
        } finally { h.tearDown() }
    }

    @Test
    fun `respondClarify carries session_id + request_id + the answers map`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonClarifyRequest("rid-q1", "live-main"))
            h.controller.respondClarify("rid-q1", mapOf("Which library should we use?" to "Ktor"), "prefer coroutines")
            val call = h.rpc.clarifyRespondCalls.single()
            assertEquals("rid-q1", call.requestId)
            assertEquals("live-main", call.sessionId)
            assertEquals(mapOf("Which library should we use?" to "Ktor"), call.answers)
            assertEquals("prefer coroutines", call.response)
            assertTrue("card cleared after answering", h.controller.pendingPrompts.first().isEmpty())
        } finally { h.tearDown() }
    }

    @Test
    fun `empty answers and response is a dismissal - still sent so the daemon settles the question`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonClarifyRequest("rid-q1", "live-main"))
            h.controller.respondClarify("rid-q1", emptyMap(), null)
            val call = h.rpc.clarifyRespondCalls.single()
            assertEquals("rid-q1", call.requestId)
            assertEquals(emptyMap<String, String>(), call.answers)
            assertEquals(null, call.response)
            assertTrue(h.controller.pendingPrompts.first().isEmpty())
        } finally { h.tearDown() }
    }

    @Test
    fun `clarify_resolved clears the matching card - answered on another device`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonClarifyRequest("rid-q1", "live-main"))
            assertEquals(1, h.controller.pendingPrompts.first().size)
            h.rpc.emit(
                GatewayEvent(
                    type = "clarify.resolved",
                    payload = buildJsonObject { put("request_id", JsonPrimitive("rid-q1")) },
                    sessionId = "live-main",
                ),
            )
            assertTrue(h.controller.pendingPrompts.first().isEmpty())
            assertTrue("no respond sent from this device", h.rpc.clarifyRespondCalls.isEmpty())
        } finally { h.tearDown() }
    }
}
