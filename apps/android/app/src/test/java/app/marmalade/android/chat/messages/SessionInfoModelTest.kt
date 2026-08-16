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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for session.info → _currentModel hydration (parity row M1).
 *
 * Verifies that ChatController updates _currentModel from session.info
 * payload fields (config.set is gone with the marmaladed flip), and
 * correctly ignores events scoped to a different session or events that
 * carry no model field.
 *
 * Covers:
 *  - model (provider ignored — marmaladed has no provider concept) → "<model>"
 *  - model only → "<model>"
 *  - different session_id → no change
 *  - neither model nor provider → no change (no clobber)
 *
 * Uses [FakeChatDao] (in-memory) and [FakeMarmaladeRpc] (controllable
 * event bus + call recorder). No Room/Robolectric needed — all assertions
 * are on the pure Kotlin state machine in ChatController.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionInfoModelTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    /**
     * Builds a minimal ChatController harness backed by fakes.
     *
     * Uses [UnconfinedTestDispatcher] so coroutines launched in the
     * controller's init (event collector, connectionState collector) start
     * eagerly without requiring an explicit advance before the first emit.
     * This matches how the production runtime drives the controller —
     * the socket events arrive after the component is live.
     *
     * Must be called inside a coroutine context (runTest) because
     * [FakeChatDao.insertSession] is suspend. Call [Harness.tearDown]
     * after the test to cancel the scope.
     */
    private suspend fun buildHarness(): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        // Pre-seed a session so the FK on messages is satisfied if any
        // background path (like refreshSessions) tries to write.
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
        )
        return Harness(dao, rpc, controller, scope)
    }

    // ── Test cases ──────────────────────────────────────────────────────────

    @Test
    fun `session_info with model and provider sets currentModel to the plain model id`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("model", JsonPrimitive("claude-sonnet-4"))
                        put("provider", JsonPrimitive("anthropic"))
                    },
                    sessionId = null, // unstamped → null == _sessionId.value (also null)
                ),
            )
            // marmaladed has no provider concept — the fork's
            // "<model> --provider <provider>" composite is dead; a provider
            // field on the payload is ignored.
            assertEquals("claude-sonnet-4", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info with model only sets currentModel without provider suffix`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("model", JsonPrimitive("claude-haiku-4"))
                    },
                    sessionId = null,
                ),
            )
            assertEquals("claude-haiku-4", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info stamped with different session_id leaves currentModel unchanged`() = runTest {
        val h = buildHarness()
        try {
            // _sessionId is null at this point (no load() called).
            // A non-null foreign session_id must not match (null != "other-session-999").
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("model", JsonPrimitive("gpt-4o"))
                        put("provider", JsonPrimitive("openai"))
                    },
                    sessionId = "other-session-999",
                ),
            )
            assertNull("currentModel must not change for a foreign session", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info with neither model nor provider leaves currentModel unchanged`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("title", JsonPrimitive("Renamed session"))
                    },
                    sessionId = null,
                ),
            )
            assertNull(
                "currentModel must not be clobbered when payload has no model",
                h.controller.currentModel.value,
            )
        } finally {
            h.tearDown()
        }
    }

}
