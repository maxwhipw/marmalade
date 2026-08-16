package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.PendingPrompt
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
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * marmaladed M2 approvals wiring (client half of an internal design note):
 * the daemon's EXACT approval.request payload shape (decision 5 —
 * request_id/tool_name/command/description/pattern_key/allow_permanent),
 * request_id-correlated approval.respond, and the transient approval.resolved
 * broadcast that clears cards on every subscribed device.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class M2ApprovalsTest {

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

    /** The daemon's exact approval.request payload (router.ts requestInner). */
    private fun daemonApprovalRequest(rid: String, sessionId: String) = GatewayEvent(
        type = "approval.request",
        payload = buildJsonObject {
            put("request_id", JsonPrimitive(rid))
            put("tool_name", JsonPrimitive("WebSearch"))
            put("command", JsonPrimitive("WebSearch({\"query\":\"latest news\"})"))
            put("description", JsonPrimitive("WebSearch({\"query\":\"latest news\"})"))
            put("pattern_key", JsonPrimitive("WebSearch"))
            put("allow_permanent", JsonPrimitive(false))
            put("seq", JsonPrimitive(42))
            put("ts", JsonPrimitive(1_700_000_000_000L))
        },
        sessionId = sessionId,
    )

    @Test
    fun `daemon approval_request parses - card keyed by the daemon-minted request_id`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonApprovalRequest("rid-abc123", "live-main"))
            val prompt = h.controller.pendingPrompts.first().single()
            assertEquals("daemon request_id adopted, not synthesized", "rid-abc123", prompt.requestId)
            assertEquals(PromptKind.Approval, prompt.kind)
            assertEquals("Approval required", prompt.title)
            assertEquals("card detail from description", "WebSearch({\"query\":\"latest news\"})", prompt.detail)
        } finally { h.tearDown() }
    }

    @Test
    fun `respondApproval carries the daemon request_id for exact correlation`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonApprovalRequest("rid-abc123", "live-main"))
            h.controller.respondApproval("rid-abc123", "deny")
            val call = h.rpc.approvalRespondCalls.single()
            assertEquals("deny", call.choice)
            assertEquals("live-main", call.sessionId)
            assertEquals("daemon-minted id rides approval.respond", "rid-abc123", call.requestId)
        } finally { h.tearDown() }
    }

    @Test
    fun `a synthesized local card id is NOT sent as request_id (FIFO fallback)`() = runTest {
        val h = buildHarness()
        try {
            // Fork-era approval.request without request_id → local "approval-…" id.
            h.rpc.emit(
                GatewayEvent(
                    type = "approval.request",
                    payload = buildJsonObject {
                        put("command", JsonPrimitive("rm -rf build/"))
                        put("allow_permanent", JsonPrimitive(true))
                    },
                    sessionId = "live-main",
                ),
            )
            val prompt = h.controller.pendingPrompts.first().single()
            assertTrue(prompt.requestId.startsWith("approval-"))
            h.controller.respondApproval(prompt.requestId, "once")
            assertEquals("local synth id must NOT reach the wire", null, h.rpc.approvalRespondCalls.single().requestId)
        } finally { h.tearDown() }
    }

    @Test
    fun `approval_resolved clears the matching card - answered on another device`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonApprovalRequest("rid-1", "live-main"))
            assertEquals(1, h.controller.pendingPrompts.first().size)
            // Another device answered; the daemon broadcast approval.resolved.
            h.rpc.emit(
                GatewayEvent(
                    type = "approval.resolved",
                    payload = buildJsonObject {
                        put("request_id", JsonPrimitive("rid-1"))
                        put("choice", JsonPrimitive("once"))
                    },
                    sessionId = "live-main",
                ),
            )
            assertTrue("card cleared without a local respond", h.controller.pendingPrompts.first().isEmpty())
            assertTrue("no respond sent from this device", h.rpc.approvalRespondCalls.isEmpty())
        } finally { h.tearDown() }
    }

    @Test
    fun `approval_resolved with an unknown id falls back to the session's oldest approval card`() = runTest {
        val h = buildHarness()
        try {
            // A fork-era card with a synthesized local id can't match by rid.
            h.rpc.emit(
                GatewayEvent(
                    type = "approval.request",
                    payload = buildJsonObject { put("command", JsonPrimitive("x")) },
                    sessionId = "live-main",
                ),
            )
            assertEquals(1, h.controller.pendingPrompts.first().size)
            h.rpc.emit(
                GatewayEvent(
                    type = "approval.resolved",
                    payload = buildJsonObject {
                        put("request_id", JsonPrimitive("rid-unknown"))
                        put("choice", JsonPrimitive("deny"))
                    },
                    sessionId = "live-main",
                ),
            )
            assertTrue(h.controller.pendingPrompts.first().isEmpty())
        } finally { h.tearDown() }
    }
}
