package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.JsonRpcError
import app.marmalade.android.rpc.JsonRpcException
import app.marmalade.android.rpc.types.SessionForkResponse
import app.marmalade.android.rpc.types.SessionLineageRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * branchSession (T2 #3) — the harness-native session.fork branch path.
 * Pins the review findings (2026-07-18):
 *  - fork success binds the new session with branchedFromId stamped locally;
 *  - a no-fork harness (error.data.reason = "fork_unsupported") gets an
 *    HONEST unavailability toast and NO seed-create fallback (the daemon
 *    ignores session.create's `messages`, so a seeded "branch" would be an
 *    empty session sold as a degraded one);
 *  - isNoForkError branches on the structured reason first, message
 *    substring second, and never on the other fork rejections.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BranchSessionTest {

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
            SessionEntity(
                key = "main",
                displayName = "morning chat",
                thinkingLevel = "off",
                gatewaySessionId = "server-session-main",
            ),
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

    /** Bind "main" so _sessionId carries the server id the fork sources. */
    private suspend fun Harness.bindMain() {
        rpc.openTransport()
        controller.load("main")
        controller.sessionId.first { it == "server-session-main" }
    }

    private fun noForkStructured(message: String = "harness \"opencode\" cannot do it") =
        JsonRpcException(
            JsonRpcError(
                code = -32602,
                message = message,
                data = JsonObject(mapOf("reason" to JsonPrimitive("fork_unsupported"))),
            ),
            method = "session.fork",
        )

    @Test
    fun `end fork binds the new session with branchedFromId stamped and one fork name`() = runTest {
        val h = buildHarness()
        try {
            h.bindMain()
            h.rpc.sessionForkResponse = SessionForkResponse(
                session_id = "server-fork-1",
                forked_from = SessionLineageRef(session_id = "server-session-main", message_id = null),
                full_context = true,
            )

            h.controller.branchSession(atMessageId = null)

            val call = h.rpc.sessionForkCalls.single()
            assertEquals("server-session-main", call.sessionId)
            assertEquals(null, call.atMessageId)
            assertEquals("morning chat (fork)", call.title)
            val row = h.dao.snapshot().first["server-fork-1"]
            assertEquals("server-session-main", row?.branchedFromId)
            // The wire title and the local displayName come from ONE value.
            assertEquals("morning chat (fork)", row?.displayName)
            assertTrue(h.toasts.any { it.startsWith("Branched into a new chat") })
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `mid-point fork passes the cut message id and surfaces the soft warning`() = runTest {
        val h = buildHarness()
        try {
            h.bindMain()
            h.rpc.sessionForkResponse = SessionForkResponse(
                session_id = "server-fork-2",
                forked_from = SessionLineageRef(session_id = "server-session-main", message_id = "m_7"),
                full_context = true,
                warning = "file-history not copied",
            )

            h.controller.branchSession(atMessageId = "m_7")

            assertEquals("m_7", h.rpc.sessionForkCalls.single().atMessageId)
            assertTrue(h.toasts.any { it.contains("file-history not copied") })
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `no-fork harness gets an honest unavailability toast and NO seed-create fallback`() = runTest {
        val h = buildHarness()
        try {
            h.bindMain()
            h.rpc.sessionForkError = noForkStructured()

            h.controller.branchSession(atMessageId = null)

            assertTrue(h.toasts.any { it.contains("unavailable", ignoreCase = true) })
            // The old seed-create fallback is GONE: no session.create fires
            // (against the current daemon it would create an empty session).
            assertTrue("no seed-create fallback", h.rpc.sessionCreateCalls.isEmpty())
            assertFalse(h.dao.snapshot().first.containsKey("server-fork-1"))
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `other fork rejections surface as plain errors, not unavailability`() = runTest {
        val h = buildHarness()
        try {
            h.bindMain()
            h.rpc.sessionForkError = JsonRpcException(
                JsonRpcError(code = -32602, message = "session has a turn in flight — fork after it completes"),
                method = "session.fork",
            )

            h.controller.branchSession(atMessageId = null)

            assertTrue(h.toasts.any { it.startsWith("Branch failed:") })
            assertFalse(h.toasts.any { it.contains("unavailable", ignoreCase = true) })
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `isNoForkError - structured reason wins, substring is fallback, others are not no-fork`() = runTest {
        val h = buildHarness()
        try {
            val c = h.controller
            // Structured reason, ANY message wording — the contract.
            assertTrue(c.isNoForkError(noForkStructured(message = "totally reworded prose")))
            // Substring fallback for a pre-reason daemon.
            assertTrue(
                c.isNoForkError(
                    JsonRpcException(JsonRpcError(-32602, "harness \"x\" cannot fork sessions — fall back …")),
                ),
            )
            // The other fork rejections must NOT classify as no-fork.
            assertFalse(c.isNoForkError(JsonRpcException(JsonRpcError(-32602, "message m_x not found in session s_y"))))
            assertFalse(c.isNoForkError(JsonRpcException(JsonRpcError(-32602, "fork cut must be an assistant reply with harness state"))))
            // A structured error with a DIFFERENT reason is not a no-fork signal.
            assertFalse(
                c.isNoForkError(
                    JsonRpcException(
                        JsonRpcError(-32602, "nope", data = JsonObject(mapOf("reason" to JsonPrimitive("something_else")))),
                    ),
                ),
            )
            // A plain non-RPC failure is not a no-fork signal either.
            assertFalse(c.isNoForkError(IllegalStateException("boom")))
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `branch is refused while a turn is streaming and without a server session`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            // A fresh LOCAL-ONLY chat (deferred create — no server id, nothing
            // sent) has no harness state to fork. Bind it explicitly: the
            // transport-open auto-hydration binds "main" (which HAS a server
            // id), so the local row must be loaded to pin the null-id path.
            h.dao.insertSession(SessionEntity(key = "chat-local-1", thinkingLevel = "off"))
            h.controller.load("chat-local-1")
            h.controller.branchSession(atMessageId = null)
            assertTrue(h.rpc.sessionForkCalls.isEmpty())
            assertTrue(h.toasts.any { it.contains("Send a message first") })

            // Now bind main and start a streaming turn — branch must refuse.
            h.bindMain()
            h.rpc.emit(
                app.marmalade.android.rpc.GatewayEvent(type = "message.start", payload = null, sessionId = null),
            )
            h.controller.branchSession(atMessageId = null)
            assertTrue(h.rpc.sessionForkCalls.isEmpty())
        } finally {
            h.tearDown()
        }
    }
}
