package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the reasoning-effort round trip: the composer's pick out to
 * `session.effort`, and the daemon's value back in via `session.info`
 * (ChatEventRouter → ChatController.applyServerEffort).
 *
 * The daemon decorates session.info with the spawn's `reasoning_effort` (daemon
 * 93152aa) and, since 2026-07-25, exposes `session.effort` to CHANGE it on an
 * existing session. Both halves are load-bearing for the maintainer's on-device bug —
 * picking medium showed medium, then flipped to high on send, because the pick
 * had never reached the daemon and the spawn's info echo was telling the truth.
 *
 * Uses [FakeChatDao] + [FakeMarmaladeRpc] — a pure Kotlin state-machine test,
 * no Room/Robolectric. Mirrors [SessionInfoModelTest].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReasoningEffortAdoptTest {

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
        dao.insertSession(SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "server-session-1"))
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
            // Without this the pick's Room write lands on the real IO
            // dispatcher, escapes the test scheduler, and the persistence
            // assertion races it (the trap documented in ModelChipSeedTest).
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        val toasts = mutableListOf<String>()
        scope.launch { controller.toastMessage.collect { toasts += it } }
        return Harness(dao, rpc, controller, scope, toasts)
    }

    /** Bind the seeded session with a live transport — the state every pick
     *  test needs, since an existing session's effort is server state. */
    private suspend fun Harness.bind() {
        rpc.openTransport()
        controller.load("main")
        controller.sessionId.first { it == "server-session-1" }
    }

    private fun sessionInfoEffort(effort: String?): GatewayEvent = GatewayEvent(
        type = "session.info",
        payload = buildJsonObject {
            if (effort != null) put("reasoning_effort", JsonPrimitive(effort))
        },
        sessionId = null, // unstamped → targets the bound (current) session
    )

    @Test
    fun `session_info reasoning_effort is adopted as the thinking level`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(sessionInfoEffort("high"))
            assertEquals("high", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a later session_info propagates a cross-client effort change`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(sessionInfoEffort("medium"))
            assertEquals("medium", h.controller.thinkingLevel.value)
            h.rpc.emit(sessionInfoEffort("xhigh"))
            assertEquals("xhigh", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `absent reasoning_effort does not clobber the current level`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(sessionInfoEffort("high"))
            assertEquals("high", h.controller.thinkingLevel.value)
            // A session.info with no reasoning_effort field must leave it be.
            h.rpc.emit(sessionInfoEffort(null))
            assertEquals("high", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    // ── The pick reaches the daemon ─────────────────────────────────────────
    // The maintainer, on-device 2026-07-25: picking medium snapped back to high on send.
    // Effort used to be create-only, so on an existing session the pick was
    // never sent AND the turn kept running at the session row's stored level.

    @Test
    fun `a pick on a materialized session changes the effort on the wire`() = runTest {
        val h = buildHarness()
        try {
            h.bind()
            h.rpc.emit(sessionInfoEffort("high"))

            h.controller.setThinkingLevel("medium")

            assertEquals(
                FakeMarmaladeRpc.SessionEffortCall("server-session-1", "medium"),
                h.rpc.sessionEffortCalls.single(),
            )
            assertEquals("medium", h.controller.thinkingLevel.value)
            assertEquals("medium", h.dao.getSessionByKey("main")?.thinkingLevel)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `sending after a pick keeps the picked level`() = runTest {
        val h = buildHarness()
        try {
            h.bind()
            h.rpc.emit(sessionInfoEffort("high"))
            h.controller.setThinkingLevel("medium")

            h.controller.sendMessage("hello", thinkingLevel = "medium")?.job?.join()
            // The daemon restarted the child for the effort change, so the
            // spawn's decorated session.info now carries the NEW level.
            h.rpc.emit(sessionInfoEffort("medium"))

            assertEquals("medium", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a stale echo of the old effort never reverts the pick`() = runTest {
        val h = buildHarness()
        try {
            h.bind()
            h.rpc.emit(sessionInfoEffort("high"))
            h.controller.setThinkingLevel("medium")

            // session.info fires for any metadata change; one that raced the
            // session.effort call still says "high". The pick must win.
            h.rpc.emit(sessionInfoEffort("high"))
            assertEquals("medium", h.controller.thinkingLevel.value)

            // Once the server echoes the pick, it owns the value again — a
            // later cross-client change applies normally.
            h.rpc.emit(sessionInfoEffort("medium"))
            h.rpc.emit(sessionInfoEffort("xhigh"))
            assertEquals("xhigh", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a rejected pick reverts the chip, the row and tells the user`() = runTest {
        val h = buildHarness()
        try {
            h.bind()
            h.rpc.emit(sessionInfoEffort("high"))
            h.rpc.sessionEffortError = IllegalStateException("session has a turn in flight")

            h.controller.setThinkingLevel("medium")

            assertEquals("high", h.controller.thinkingLevel.value)
            assertEquals("high", h.dao.getSessionByKey("main")?.thinkingLevel)
            assertTrue("user was told", h.toasts.any { it.contains("turn in flight") })
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `an offline pick on a materialized session is refused, not faked`() = runTest {
        val h = buildHarness()
        try {
            h.bind()
            h.rpc.emit(sessionInfoEffort("high"))
            h.rpc.closeTransport()

            h.controller.setThinkingLevel("medium")

            assertTrue("no wire call", h.rpc.sessionEffortCalls.isEmpty())
            assertEquals("high", h.controller.thinkingLevel.value)
            assertTrue("user was told", h.toasts.any { it.contains("offline") })
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a pick on a session that does not exist yet is a local draft`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            // No gatewaySessionId → nothing to change server-side; the pick
            // rides the next session.create reasoning_effort param.
            h.dao.insertSession(SessionEntity(key = "fresh", thinkingLevel = "off"))
            h.controller.load("fresh")

            h.controller.setThinkingLevel("medium")

            assertTrue("no wire call without a server id", h.rpc.sessionEffortCalls.isEmpty())
            assertEquals("medium", h.controller.thinkingLevel.value)
            assertEquals("medium", h.dao.getSessionByKey("fresh")?.thinkingLevel)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `binding another session drops the pick and adopts that session's effort`() = runTest {
        val h = buildHarness()
        try {
            h.bind()
            h.controller.setThinkingLevel("medium")
            assertEquals("medium", h.controller.thinkingLevel.value)

            // A pick belongs to the session it was made in. The next session's
            // own server value must apply normally.
            h.dao.insertSession(
                SessionEntity(
                    key = "other",
                    thinkingLevel = "off",
                    gatewaySessionId = "server-session-2",
                ),
            )
            h.controller.load("other")
            h.rpc.emit(sessionInfoEffort("xhigh"))
            assertEquals("xhigh", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }
}
