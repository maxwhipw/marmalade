package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.types.SessionListRow
import app.marmalade.android.rpc.types.SessionListResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digital-twin tests for the marmaladed session.delete / session.title wiring
 * (daemon commit 382b214 — additive to protocol v1).
 *
 * Covers:
 *  - session.list's explicit `title` (set via session.title rename) beats the
 *    agent rollup `topic` in the displayName chain.
 *  - deleteSession routes through deleteSessionRemote (JSON-RPC
 *    session.delete under marmaladed) with NO close-before-delete ritual;
 *    local rows go optimistically and stay gone on success.
 *  - The daemon's `session.deleted` broadcast (a delete ANOTHER device
 *    initiated) drops the local mirror and rebinds off the dead session.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeleteAndTitleWiringTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
        val remoteDeletes: MutableList<String>,
    ) {
        fun tearDown() = scope.cancel()
    }

    private fun buildHarness(remoteResult: Boolean = true): Harness {
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
        val remoteDeletes = mutableListOf<String>()
        val controller = ChatController(
            scope = scope,
            rpc = rpc,
            messageStream = stream,
            outboxDrainer = drainer,
            json = testJson,
            chatDao = dao,
            ioDispatcher = UnconfinedTestDispatcher(),
            deleteSessionRemote = { storedId ->
                remoteDeletes += storedId
                remoteResult
            },
        )
        return Harness(dao, rpc, controller, scope, remoteDeletes)
    }

    // ── session.title → session.list `title` field ─────────────────────────

    @Test
    fun `explicit title beats rollup topic in displayName`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.sessionListResponse = SessionListResponse(
                sessions = listOf(
                    SessionListRow(session_id = "s-1", title = "Groceries plan", topic = "food logistics", summary = "…"),
                ),
            )
            h.rpc.openTransport()
            h.controller.refreshSessions()
            assertEquals("Groceries plan", h.dao.getSessionByKey("s-1")?.displayName)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `blank title falls back to topic`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.sessionListResponse = SessionListResponse(
                sessions = listOf(
                    SessionListRow(session_id = "s-2", title = "  ", topic = "food logistics"),
                ),
            )
            h.rpc.openTransport()
            h.controller.refreshSessions()
            assertEquals("food logistics", h.dao.getSessionByKey("s-2")?.displayName)
        } finally {
            h.tearDown()
        }
    }

    // ── deleteSession → session.delete (no close ritual) ───────────────────

    @Test
    fun `deleteSession routes to remote delete with the stored key and removes the row`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "sess-del", displayName = "Doomed", thinkingLevel = "off"))
            h.rpc.openTransport()

            h.controller.deleteSession("sess-del")

            assertEquals(listOf("sess-del"), h.remoteDeletes)
            assertNull("optimistic local removal sticks on server success", h.dao.getSessionByKey("sess-del"))
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `failed remote delete rolls the row back`() = runTest {
        val h = buildHarness(remoteResult = false)
        try {
            h.dao.insertSession(SessionEntity(key = "sess-keep", displayName = "Survivor", thinkingLevel = "off"))
            h.rpc.openTransport()

            h.controller.deleteSession("sess-keep")

            assertEquals(listOf("sess-keep"), h.remoteDeletes)
            assertNotNull("server refused → row restored", h.dao.getSessionByKey("sess-keep"))
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `offline delete rolls back without calling the server`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "sess-off", displayName = "Offline", thinkingLevel = "off"))
            // Transport stays closed.
            h.controller.deleteSession("sess-off")

            assertTrue("no remote call while offline", h.remoteDeletes.isEmpty())
            assertNotNull("row restored", h.dao.getSessionByKey("sess-off"))
        } finally {
            h.tearDown()
        }
    }

    // ── session.deleted broadcast (cross-device delete) ────────────────────

    @Test
    fun `session_deleted event from another device drops the local mirror`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "sess-x", displayName = "Elsewhere", thinkingLevel = "off"))
            h.rpc.openTransport()

            h.rpc.emit(
                GatewayEvent(
                    type = "session.deleted",
                    payload = buildJsonObject { put("session_id", JsonPrimitive("sess-x")) },
                    sessionId = "sess-x",
                ),
            )

            assertNull("local mirror dropped on the broadcast", h.dao.getSessionByKey("sess-x"))
        } finally {
            h.tearDown()
        }
    }
}
