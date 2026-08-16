package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The composer context donut's cold-open seed: `sessionUsage` picks up the
 * daemon's persisted occupancy (`sessions.contextUsed`/`contextMax`, mirrored
 * from every session.list row) for the bound session, so a session opened
 * WITHOUT running a turn shows a percentage immediately.
 *
 * The contract under test (mirrors the webui's components/context.ts):
 *  - bind seeds from the bound row; a row with no reading seeds nothing.
 *  - a session.list refresh that lands AFTER the bind still seeds — the
 *    collector is reactive, not a one-shot read.
 *  - a LIVE message.complete usage block wins and is never overwritten.
 *  - switching sessions reseeds from that session's OWN row (no leak).
 *  - session.cleared resets to unknown, mirroring the daemon nulling its
 *    columns, and the stale local row can't re-seed it.
 *
 * Uses [FakeChatDao] + [FakeMarmaladeRpc]; the transport stays CLOSED so load()
 * takes the render-from-cache path and binds _sessionId to the row's
 * gatewaySessionId. No Room/Robolectric needed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContextDonutSeedTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    /** Minimal ChatController harness — mirrors [ModelChipSeedTest]. */
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

    private suspend fun Harness.seedRow(
        key: String,
        used: Long?,
        max: Long?,
    ) = dao.insertSession(
        SessionEntity(
            key = key, gatewaySessionId = key, thinkingLevel = "off",
            contextUsed = used, contextMax = max,
        ),
    )

    /** A finalized assistant turn carrying a live usage block for [sid]. */
    private suspend fun Harness.completeTurnWithUsage(sid: String, used: Long, max: Long) {
        rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = sid))
        rpc.emit(
            GatewayEvent(
                type = "message.delta",
                payload = buildJsonObject { put("text", JsonPrimitive("done")) },
                sessionId = sid,
            ),
        )
        rpc.emit(
            GatewayEvent(
                type = "message.complete",
                payload = buildJsonObject {
                    put("text", JsonPrimitive("done"))
                    put(
                        "usage",
                        buildJsonObject {
                            put("context_used", JsonPrimitive(used))
                            put("context_max", JsonPrimitive(max))
                        },
                    )
                },
                sessionId = sid,
            ),
        )
    }

    // ── Test cases ──────────────────────────────────────────────────────────

    @Test
    fun `bind seeds the donut from the row's persisted occupancy`() = runTest {
        val h = buildHarness()
        try {
            h.seedRow("sess-cold", used = 32_900L, max = 200_000L)
            h.controller.load("sess-cold")
            assertEquals(
                "a cold session must show a percentage before any turn runs",
                16, h.controller.sessionUsage.value?.contextPercent,
            )
            assertEquals(32_900L, h.controller.sessionUsage.value?.contextUsed)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a row with no reading seeds nothing`() = runTest {
        val h = buildHarness()
        try {
            // Never ran, or a daemon predating the fields — unknown, so NO
            // donut. Never a fabricated value.
            h.seedRow("sess-blank", used = null, max = null)
            h.controller.load("sess-blank")
            assertNull(h.controller.sessionUsage.value?.contextPercent)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `used without a window seeds nothing`() = runTest {
        val h = buildHarness()
        try {
            h.seedRow("sess-nowindow", used = 9_000L, max = null)
            h.controller.load("sess-nowindow")
            assertNull(
                "both halves are required — a window-less harness stays unknown",
                h.controller.sessionUsage.value?.contextPercent,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a session_list refresh landing after the bind still seeds`() = runTest {
        val h = buildHarness()
        try {
            // Cold start: the chat binds before the first session.list round
            // trip writes the columns (first launch, or right after the
            // destructive DB upgrade).
            h.seedRow("sess-late", used = null, max = null)
            h.controller.load("sess-late")
            assertNull(h.controller.sessionUsage.value?.contextPercent)

            val row = h.dao.getSessionByKey("sess-late")!!
            h.dao.updateSessionRow(row.copy(contextUsed = 61_440L, contextMax = 128_000L))
            assertEquals(
                "the seed must not wait for the next navigation",
                48, h.controller.sessionUsage.value?.contextPercent,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a live turn supersedes the seed and the seed never wins it back`() = runTest {
        val h = buildHarness()
        try {
            h.seedRow("sess-live", used = 32_900L, max = 200_000L)
            h.controller.load("sess-live")
            assertEquals(16, h.controller.sessionUsage.value?.contextPercent)

            h.completeTurnWithUsage("sess-live", used = 100_000L, max = 200_000L)
            advanceUntilIdle()
            assertEquals(
                "the live message.complete usage block is authoritative",
                50, h.controller.sessionUsage.value?.contextPercent,
            )

            // A later session.list refresh writing a DIFFERENT (stale, or
            // simply older) reading must not drag the donut backwards.
            val row = h.dao.getSessionByKey("sess-live")!!
            h.dao.updateSessionRow(row.copy(contextUsed = 20_000L, contextMax = 200_000L))
            assertEquals(
                "live wins once seen — the seed only fills the gap before it",
                50, h.controller.sessionUsage.value?.contextPercent,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `switching sessions reseeds from that session's own row`() = runTest {
        val h = buildHarness()
        try {
            h.seedRow("sess-a", used = 100_000L, max = 200_000L)
            h.seedRow("sess-b", used = 32_900L, max = 200_000L)
            h.seedRow("sess-c", used = null, max = null)

            h.controller.load("sess-a")
            assertEquals(50, h.controller.sessionUsage.value?.contextPercent)
            h.controller.load("sess-b")
            assertEquals(16, h.controller.sessionUsage.value?.contextPercent)
            h.controller.load("sess-c")
            assertNull(
                "a session with no reading must not inherit the previous donut",
                h.controller.sessionUsage.value?.contextPercent,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_cleared resets the donut to unknown and the row cannot reseed it`() = runTest {
        val h = buildHarness()
        try {
            h.seedRow("sess-clear", used = 32_900L, max = 200_000L)
            h.controller.load("sess-clear")
            assertEquals(16, h.controller.sessionUsage.value?.contextPercent)

            h.rpc.emit(
                GatewayEvent(
                    type = "session.cleared",
                    payload = buildJsonObject { put("session_id", JsonPrimitive("sess-clear")) },
                    sessionId = "sess-clear",
                ),
            )
            assertNull(
                "an emptied window has no honest percentage until the next turn",
                h.controller.sessionUsage.value?.contextPercent,
            )
            assertNull(
                "the local mirror is nulled too, so it can't re-seed the stale reading",
                h.dao.getSessionByKey("sess-clear")?.contextUsed,
            )
        } finally {
            h.tearDown()
        }
    }
}
