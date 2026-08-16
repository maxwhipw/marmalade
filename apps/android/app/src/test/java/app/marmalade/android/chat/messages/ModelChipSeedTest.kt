package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the composer model chip's bind-time seeding from the local
 * Room mirror (`sessions.model`, fed by session.list at refresh and by the
 * user's picker choice via setCurrentModel).
 *
 * The contract under test:
 *  - load() seeds _currentModel from the bound row's `model` — a
 *    materialized session's chip is correct before/without session.info.
 *  - session.info stays authoritative and overwrites the seed.
 *  - an unsent picker choice on a fresh (unmaterialized) session survives
 *    switching away and back — setCurrentModel persists to the row, load()
 *    re-reads it.
 *  - switching between sessions never leaks the previous chip: the seed is
 *    assigned unconditionally, null included.
 *
 * Uses [FakeChatDao] + [FakeMarmaladeRpc]; transport stays CLOSED so load()
 * takes the render-from-cache early return (seed happens before the
 * connection check). No Room/Robolectric needed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelChipSeedTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    /** Minimal ChatController harness — mirrors SessionListRefreshTest
     *  (ioDispatcher injected so load()'s withContext block runs eagerly). */
    private fun buildHarness(): Harness {
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

    // ── Test cases ──────────────────────────────────────────────────────────

    @Test
    fun `bind seeds the chip from the row's session_list-sourced model`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(
                SessionEntity(key = "sess-a", gatewaySessionId = "sess-a", thinkingLevel = "off", model = "claude-opus-4-8"),
            )
            h.controller.load("sess-a")
            assertEquals("claude-opus-4-8", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info overwrites the bind-time seed`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(
                SessionEntity(key = "sess-a", gatewaySessionId = "sess-a", thinkingLevel = "off", model = "stale-model"),
            )
            h.controller.load("sess-a")
            // Transport closed → load() bound _sessionId to the row's
            // gatewaySessionId; a stamped session.info for it must win.
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject { put("model", JsonPrimitive("claude-sonnet-4")) },
                    sessionId = "sess-a",
                ),
            )
            assertEquals("session.info is authoritative over the mirror seed", "claude-sonnet-4", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `unsent pick on a fresh session survives switch-away-and-back`() = runTest {
        val h = buildHarness()
        try {
            // Fresh, never-sent chat: no pre-seeded row; ensureSessionRow
            // creates one on bind (gatewaySessionId null — unmaterialized).
            h.controller.load("chat-20260711-000001")
            h.controller.setCurrentModel("claude-haiku-4")
            h.controller.load("chat-20260711-000002")
            assertNull("fresh second chat must not inherit the first chat's pick", h.controller.currentModel.value)
            h.controller.load("chat-20260711-000001")
            assertEquals("the unsent pick must survive the round trip", "claude-haiku-4", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `switching between materialized sessions does not leak the model`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(
                SessionEntity(key = "sess-a", gatewaySessionId = "sess-a", thinkingLevel = "off", model = "claude-opus-4-8"),
            )
            h.dao.insertSession(
                SessionEntity(key = "sess-b", gatewaySessionId = "sess-b", thinkingLevel = "off", model = null),
            )
            h.controller.load("sess-a")
            assertEquals("claude-opus-4-8", h.controller.currentModel.value)
            h.controller.load("sess-b")
            assertNull("session B (no reported model) must not show session A's chip", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }
}
