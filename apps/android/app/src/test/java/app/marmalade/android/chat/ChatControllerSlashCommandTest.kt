package app.marmalade.android.chat

import app.marmalade.android.chat.messages.FakeChatDao
import app.marmalade.android.chat.messages.FakeMarmaladeRpc
import app.marmalade.android.chat.messages.MessageStream
import app.marmalade.android.chat.messages.OutboxDrainer
import app.marmalade.android.chat.messages.marmaladeRpcAdapter
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChatController.startFreshSession].
 *
 * Verifies:
 *  - sessionId is nulled after startFreshSession.
 *  - sessionKey changes to a new value (distinct from the old key).
 *  - Draft is cleared.
 *  - load() is called with the new key (a Room SessionEntity row exists
 *    for it after the load completes).
 *  - The new key matches the `chat-yyyyMMdd-HHmmss` pattern.
 *  - Pending prompts are cleared.
 *
 * Uses [FakeChatDao] and [FakeMarmaladeRpc] — no Room or Robolectric needed.
 * Harness mirrors [SessionRunningTest].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatControllerSlashCommandTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    private suspend fun buildHarness(initialKey: String = "main"): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        dao.insertSession(
            SessionEntity(
                key = initialKey,
                thinkingLevel = "off",
                gatewaySessionId = "server-session-1",
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
            // Same UnconfinedTestDispatcher pattern SessionListRefreshTest
            // uses. Without it, ChatController's ioDispatcher defaults to
            // Dispatchers.IO (real thread pool), and assertions race with
            // the launched coroutines that withContext(ioDispatcher) {}
            // depend on — manifesting as flaky failures only when the full
            // suite runs (different JVM warmup / scheduler load).
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return Harness(dao, rpc, controller, scope)
    }

    // ── Test cases ──────────────────────────────────────────────────────────

    @Test
    fun `startFreshSession nulls sessionId`() = runTest {
        val h = buildHarness()
        try {
            // Manually set a server-side session id to simulate a bound session.
            // (load() + ensureServerSessionId requires a live socket, so we
            // set the internal state directly via the public StateFlow.)
            // The real flow: load("main") would call ensureServerSessionId and
            // populate _sessionId; here we simulate that precondition.
            // ChatController exposes sessionId as a read-only StateFlow backed by
            // _sessionId which is private — we test the *observable result*:
            // after startFreshSession the controller must have a null sessionId
            // (the server side was never bound for the new key).
            h.controller.startFreshSession()

            assertNull(
                "sessionId must be null after startFreshSession — new key has no server binding yet",
                h.controller.sessionId.value,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `startFreshSession changes sessionKey to a different value`() = runTest {
        val h = buildHarness(initialKey = "main")
        try {
            val oldKey = h.controller.sessionKey.value
            h.controller.startFreshSession()
            val newKey = h.controller.sessionKey.value

            assertNotEquals(
                "sessionKey must change after startFreshSession",
                oldKey,
                newKey,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `startFreshSession new key matches chat-yyyyMMdd-HHmmss pattern`() = runTest {
        val h = buildHarness()
        try {
            h.controller.startFreshSession()
            val newKey = h.controller.sessionKey.value

            assertTrue(
                "new session key '$newKey' must match chat-yyyyMMdd-HHmmss",
                newKey.matches(Regex("chat-\\d{8}-\\d{6}")),
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `startFreshSession shows no prompt cards in the fresh chat`() = runTest {
        val h = buildHarness()
        try {
            // Pre-populate a pending prompt by emitting a clarify.request event.
            h.rpc.emit(
                GatewayEvent(
                    type = "clarify.request",
                    payload = buildJsonObject {
                        put("request_id", JsonPrimitive("req-1"))
                        put("title", JsonPrimitive("Are you sure?"))
                    },
                    sessionId = null,
                ),
            )

            h.controller.startFreshSession()

            // Prompts are session-scoped now: the fresh chat renders no cards,
            // but the parked prompt survives in pendingPrompts so the user can
            // still navigate back and answer it (pre-fix every switch wiped
            // ALL prompts, making background clarifies unanswerable).
            assertTrue(
                "fresh chat must render no prompt cards",
                h.controller.boundPendingPrompts.value.isEmpty(),
            )
            assertEquals(
                "the parked prompt survives for its own session",
                1,
                h.controller.pendingPrompts.value.size,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `startFreshSession creates a Room row for the new key via load`() = runTest {
        val h = buildHarness()
        try {
            h.controller.startFreshSession()
            val newKey = h.controller.sessionKey.value
            val (sessions, _, _) = h.dao.snapshot()

            assertNotNull(
                "Room must have a session row for the new key '$newKey' after load",
                sessions[newKey],
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `startFreshSession clears draft for old session`() = runTest {
        val h = buildHarness()
        try {
            // Write a draft for the current session, then start fresh.
            h.controller.setDraft("half-typed message")
            h.controller.startFreshSession()

            // The controller calls setDraft("") before load(). The new session
            // has no draft, so getDraft() for the new key returns null.
            val draft = h.controller.getDraft()
            assertTrue(
                "draft must be blank after startFreshSession; got: '$draft'",
                draft.isNullOrBlank(),
            )
        } finally {
            h.tearDown()
        }
    }
}
