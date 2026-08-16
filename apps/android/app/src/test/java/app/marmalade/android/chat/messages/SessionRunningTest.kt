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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for session.info → _sessionRunning hydration (parity row M3).
 *
 * Verifies that ChatController correctly tracks the per-session running flag
 * across all sessions (foreground and background), ignores events with no
 * "running" key, and handles the two-session independence requirement.
 *
 * Covers:
 *  - session.info with running=true updates the flow for that session id.
 *  - session.info with running=false updates the flow.
 *  - session.info with no running field leaves the flow unchanged.
 *  - Two different session ids are tracked independently.
 *  - needsInput and running can coexist on the same session.
 *
 * Uses [FakeChatDao] and [FakeMarmaladeRpc] — no Room/Robolectric needed.
 * Harness mirrors [SessionInfoUsageTest] exactly.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionRunningTest {

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
        // One dispatcher for BOTH the scope and the controller's ioDispatcher:
        // PromptCenter.capture hops through withContext(ioDispatcher) before
        // writing pendingPrompts, so leaving the controller on the default
        // Dispatchers.IO makes the coexist test's immediate read a race
        // (observed flaking in full-suite runs, 2026-07-28).
        val dispatcher = UnconfinedTestDispatcher()
        val scope = CoroutineScope(dispatcher)
        val dao = FakeChatDao()
        dao.insertSession(
            SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "server-session-1"),
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
            ioDispatcher = dispatcher,
        )
        return Harness(dao, rpc, controller, scope)
    }

    // ── Test cases ──────────────────────────────────────────────────────────

    @Test
    fun `session_info with running=true sets flag for that session id`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("running", JsonPrimitive(true))
                    },
                    sessionId = null, // unstamped → resolved to _sessionId.value (null)
                ),
            )
            // When _sessionId.value is null and sessionId is null, runningSid = null,
            // so the map should NOT be updated (null sid is skipped).
            // This confirms the null-guard works.
            assertTrue(
                "sessionRunning should be empty when resolved sid is null",
                h.controller.sessionRunning.value.isEmpty(),
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info with running=true updates flow for a known session id`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("running", JsonPrimitive(true))
                    },
                    sessionId = "live-session-A",
                ),
            )
            val map = h.controller.sessionRunning.value
            assertTrue("live-session-A should be running=true", map["live-session-A"] == true)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info with running=false updates flow for that session id`() = runTest {
        val h = buildHarness()
        try {
            // First set running=true
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject { put("running", JsonPrimitive(true)) },
                    sessionId = "live-session-B",
                ),
            )
            // Then set running=false — entry must be KEPT but set to false
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject { put("running", JsonPrimitive(false)) },
                    sessionId = "live-session-B",
                ),
            )
            val map = h.controller.sessionRunning.value
            assertTrue("entry must remain in map", map.containsKey("live-session-B"))
            assertFalse("running should be false after second event", map["live-session-B"]!!)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info with no running field leaves sessionRunning unchanged`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("model", JsonPrimitive("claude-sonnet-4"))
                    },
                    sessionId = "live-session-C",
                ),
            )
            assertFalse(
                "sessionRunning should not contain an entry when running key is absent",
                h.controller.sessionRunning.value.containsKey("live-session-C"),
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `two different session ids are tracked independently`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject { put("running", JsonPrimitive(true)) },
                    sessionId = "live-session-X",
                ),
            )
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject { put("running", JsonPrimitive(false)) },
                    sessionId = "live-session-Y",
                ),
            )
            val map = h.controller.sessionRunning.value
            assertTrue("session X should be running=true", map["live-session-X"] == true)
            assertFalse("session Y should be running=false", map["live-session-Y"]!!)
            assertEquals("both sessions tracked", 2, map.size)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `needsInput and running can coexist on same session — both flags compute correctly`() = runTest {
        val h = buildHarness()
        try {
            // Emit a clarify.request so pendingPrompts gets a prompt for "live-session-Z".
            // The capturePrompt path stores by event.sessionId.
            h.rpc.emit(
                GatewayEvent(
                    type = "clarify.request",
                    payload = buildJsonObject {
                        put("request_id", JsonPrimitive("req-1"))
                        put("title", JsonPrimitive("Needs your input"))
                    },
                    sessionId = "live-session-Z",
                ),
            )
            // Emit running=true for the same session
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject { put("running", JsonPrimitive(true)) },
                    sessionId = "live-session-Z",
                ),
            )
            // Verify both flags are live simultaneously
            val runningMap = h.controller.sessionRunning.value
            val prompts = h.controller.pendingPrompts.value
            assertTrue("running should be true for live-session-Z", runningMap["live-session-Z"] == true)
            assertTrue(
                "pending prompt must still be present for live-session-Z",
                prompts.any { it.sessionId == "live-session-Z" },
            )
        } finally {
            h.tearDown()
        }
    }
}
