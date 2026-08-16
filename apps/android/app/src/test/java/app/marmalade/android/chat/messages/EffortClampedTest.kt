package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.types.ModelListEntry
import app.marmalade.android.rpc.types.ModelListResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-model reasoning-effort bounds, client half (daemon `baef872`, 2026-07-27).
 *
 * Two behaviours are pinned here:
 *
 *  1. **`effort.clamped` becomes a durable transcript row.** The daemon stamps
 *     and caches the event, so it replays on cold load — the client has to
 *     persist it like `message.user`, not hold it in stream state, and has to
 *     dedup it by a derived id so a replay-window overlap can't double it.
 *  2. **A model switch snaps the effort locally.** `session.model` does NOT
 *     re-clamp the session's stored effort daemon-side (router.ts sets the
 *     model and restarts the child), so without the client's snap the turn
 *     would run at a level the new model's bounds forbid while the chip
 *     claimed otherwise.
 *
 * Unknown-event handling stays intact for old daemons: a daemon that never
 * emits `effort.clamped` and reports no bounds produces exactly the pre-feature
 * behaviour, asserted at the bottom.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EffortClampedTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private fun clamped(
        requested: String = "low",
        effective: String = "high",
        model: String = "claude-opus-5",
        bound: String = "min",
        limit: String = "high",
        seq: Long = 7L,
        sid: String? = "s1",
    ): GatewayEvent = GatewayEvent(
        type = "effort.clamped",
        payload = buildJsonObject {
            put("requested", requested)
            put("effective", effective)
            put("model", model)
            put("bound", bound)
            put("limit", limit)
            put("seq", seq)
            put("ts", 1_700_000_000_000L)
        },
        sessionId = sid,
    )

    private suspend fun rowsFor(dao: FakeChatDao, sessionKey: String) =
        dao.getMessagesForSessionOnce(sessionKey)

    @Test
    fun `a clamp lands as a durable system row in the transcript`() = runBlocking {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
        val dao = FakeChatDao()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val stream = MessageStream(events, scope, dao, testJson, now = { 0L })
        stream.modelLabels = mapOf("claude-opus-5" to "Opus 5")
        delay(50)

        events.emit(clamped())
        delay(50)

        val rows = rowsFor(dao, "s1")
        assertEquals(1, rows.size)
        val row = rows.single().toChatMessage(testJson)
        assertEquals(ChatRole.System, row.role)
        assertEquals("Thinking adjusted to High — Opus 5 minimum", row.text())
        assertEquals("the row must carry the event's seq so replay lands after it", 7L, row.seq)
        scope.cancel()
    }

    @Test
    fun `a max clamp reads as a limit and uses the friendly level name`() = runBlocking {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
        val dao = FakeChatDao()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val stream = MessageStream(events, scope, dao, testJson, now = { 0L })
        stream.modelLabels = mapOf("claude-fable-5" to "Fable 5")
        delay(50)

        events.emit(
            clamped(requested = "max", effective = "xhigh", model = "claude-fable-5", bound = "max", limit = "xhigh"),
        )
        delay(50)

        assertEquals(
            "Thinking adjusted to Very high — Fable 5 limit",
            rowsFor(dao, "s1").single().toChatMessage(testJson).text(),
        )
        scope.cancel()
    }

    @Test
    fun `an unloaded catalog falls back to the raw model id rather than dropping the line`() =
        runBlocking {
            val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
            val dao = FakeChatDao()
            val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob())
            MessageStream(events, scope, dao, testJson, now = { 0L })
            delay(50)

            events.emit(clamped())
            delay(50)

            assertTrue(
                rowsFor(dao, "s1").single().toChatMessage(testJson).text()
                    .contains("claude-opus-5"),
            )
            scope.cancel()
        }

    @Test
    fun `a replayed clamp is not inserted twice`() = runBlocking {
        // The seq watermark drops in-process replay, but a cold load
        // re-subscribes from Room's max seq and the boundary event comes back.
        // The derived id is what makes that idempotent.
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
        val dao = FakeChatDao()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val stream = MessageStream(events, scope, dao, testJson, now = { 0L })
        stream.modelLabels = mapOf("claude-opus-5" to "Opus 5")
        delay(50)

        events.emit(clamped(seq = 7L))
        delay(50)
        // Fresh stream = fresh watermark, exactly like a process restart.
        val stream2 = MessageStream(events, scope, dao, testJson, now = { 0L })
        stream2.modelLabels = mapOf("claude-opus-5" to "Opus 5")
        delay(50)
        events.emit(clamped(seq = 7L))
        delay(50)

        assertEquals(1, rowsFor(dao, "s1").size)
        scope.cancel()
    }

    @Test
    fun `a malformed clamp payload is dropped, not rendered half-written`() = runBlocking {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
        val dao = FakeChatDao()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob())
        MessageStream(events, scope, dao, testJson, now = { 0L })
        delay(50)

        events.emit(
            GatewayEvent(
                type = "effort.clamped",
                payload = buildJsonObject { put("requested", "low") } as JsonObject,
                sessionId = "s1",
            ),
        )
        delay(50)

        assertTrue(rowsFor(dao, "s1").isEmpty())
        scope.cancel()
    }

    // ── the composer's snap on model switch ─────────────────────────────────

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    private fun buildHarness(): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        val rpc = FakeMarmaladeRpc()
        val stream = MessageStream(rpc.rpcClient.events, scope, dao, testJson)
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

    private val boundedCatalog = ModelListResponse(
        models = listOf(
            ModelListEntry(id = "claude-opus-5", label = "Opus 5", effort_min = "high"),
            ModelListEntry(id = "claude-fable-5", label = "Fable 5", effort_max = "medium"),
            ModelListEntry(id = "claude-haiku-4-5", label = "Haiku 4.5"),
        ),
        efforts = listOf("low", "medium", "high", "xhigh", "max"),
    )

    @Test
    fun `switching to a model whose minimum is higher snaps the thinking level up`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.modelListResponse = boundedCatalog
            h.rpc.openTransport()
            h.controller.refreshModels()
            h.controller.setThinkingLevel("low")
            assertEquals("low", h.controller.thinkingLevel.value)

            h.controller.setCurrentModel("claude-opus-5")

            assertEquals("high", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `switching to a model whose maximum is lower snaps the thinking level down`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.modelListResponse = boundedCatalog
            h.rpc.openTransport()
            h.controller.refreshModels()
            h.controller.setThinkingLevel("max")

            h.controller.setCurrentModel("claude-fable-5")

            assertEquals("medium", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `an in-range level survives a model switch untouched`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.modelListResponse = boundedCatalog
            h.rpc.openTransport()
            h.controller.refreshModels()
            h.controller.setThinkingLevel("xhigh")

            h.controller.setCurrentModel("claude-opus-5")

            assertEquals("xhigh", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a materialized session pushes the snapped effort on the wire`() = runTest {
        // The whole point: the daemon keeps the row's OLD effort after
        // session.model, so the client owes it session.effort with the snap.
        val h = buildHarness()
        try {
            h.dao.insertSession(
                SessionEntity(key = "chat-local-1", gatewaySessionId = "server-1", thinkingLevel = "low"),
            )
            h.rpc.modelListResponse = boundedCatalog
            h.rpc.openTransport()
            h.controller.refreshModels()
            h.controller.load("chat-local-1")
            h.controller.setThinkingLevel("low")
            h.rpc.sessionEffortCalls.clear()

            h.controller.setCurrentModel("claude-opus-5")

            assertEquals(listOf("claude-opus-5"), h.rpc.sessionModelCalls.map { it.model })
            assertEquals(
                "the snap must reach the daemon, not just the chip",
                listOf("high"),
                h.rpc.sessionEffortCalls.map { it.effort },
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `an old daemon reporting no bounds never snaps and never pushes an effort`() = runTest {
        // Degradation gate: pre-2026-07-27 model.list has no effort_min /
        // effort_max, so a model switch must behave exactly as it always did.
        val h = buildHarness()
        try {
            h.rpc.modelListResponse = ModelListResponse(
                models = listOf(
                    ModelListEntry(id = "claude-opus-5", label = "Opus 5"),
                    ModelListEntry(id = "claude-fable-5", label = "Fable 5"),
                ),
                efforts = listOf("low", "medium", "high", "xhigh", "max"),
            )
            h.rpc.openTransport()
            h.controller.refreshModels()
            h.controller.setThinkingLevel("low")
            h.rpc.sessionEffortCalls.clear()

            h.controller.setCurrentModel("claude-opus-5")

            assertEquals("low", h.controller.thinkingLevel.value)
            assertTrue(h.rpc.sessionEffortCalls.isEmpty())
        } finally {
            h.tearDown()
        }
    }
}
