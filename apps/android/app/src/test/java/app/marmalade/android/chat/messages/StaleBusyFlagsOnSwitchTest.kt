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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for _isStreaming / _isCompacting surviving a session
 * switch (fix brief kept internally, not in this repo).
 *
 * Pre-fix, load() reset every other per-session StateFlow
 * (_sessionOutputTokensTotal, _sessionUsage, _lastTurnModel, _errorText,
 * _pendingPrompts) but NOT _isStreaming / _isCompacting. handleEvent() only
 * clears those two flags when the event's sessionId matches the (about to
 * be replaced) bound session, so a session left mid-stream when the user
 * switches away leaves _isStreaming/_isCompacting stuck at true for
 * whatever session gets bound next — even if that session has never had a
 * turn running. The verified user-facing impact (per the fix brief's
 * verifier corrections) is ChatMessageList's activity indicator
 * (`isStreaming || streamingMessage != null`, ChatMessageList.kt:101)
 * showing a stale pulsing "thinking"/"compacting" pill for an idle,
 * newly-bound session.
 *
 * Fix: load() explicitly resets both flags in its per-session reset block,
 * mirroring the reset startFreshSession() already performed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StaleBusyFlagsOnSwitchTest {

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
        dao.insertSession(SessionEntity(key = "session-a", thinkingLevel = "off", gatewaySessionId = "session-a"))
        dao.insertSession(SessionEntity(key = "session-b", thinkingLevel = "off", gatewaySessionId = "session-b"))

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

    /** sessionResume's response always echoes back whatever id it's asked to
     *  resume, so `_sessionId.value` after load(key) reliably equals `key`
     *  (each session in this test uses its key as its own gatewaySessionId). */
    private fun Harness.resumeAs(key: String) {
        rpc.sessionResumeResponse = rpc.sessionResumeResponse.copy(session_id = key)
    }

    @Test
    fun `switching away from a streaming session clears isStreaming for the newly-bound session`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            h.resumeAs("session-a")
            h.controller.load("session-a")

            // session-a starts streaming.
            h.rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = "session-a"))
            assertTrue("isStreaming should be true while session-a is mid-turn", h.controller.isStreaming.value)

            // User switches to session-b WITHOUT session-a ever finishing —
            // no message.complete/error fires for session-a.
            h.resumeAs("session-b")
            h.controller.load("session-b")

            assertFalse(
                "isStreaming must not leak into the newly-bound idle session-b — " +
                    "session-b never had a message.start of its own",
                h.controller.isStreaming.value,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `switching away from a compacting session clears isCompacting for the newly-bound session`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            h.resumeAs("session-a")
            h.controller.load("session-a")

            h.rpc.emit(
                GatewayEvent(
                    type = "status.update",
                    payload = buildJsonObject { put("kind", JsonPrimitive("compacting")) },
                    sessionId = "session-a",
                ),
            )
            assertTrue("isCompacting should be true while session-a is compacting", h.controller.isCompacting.value)

            h.resumeAs("session-b")
            h.controller.load("session-b")

            assertFalse(
                "isCompacting must not leak into the newly-bound idle session-b",
                h.controller.isCompacting.value,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `switching back to a still-streaming session does not incorrectly clear isStreaming`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            h.resumeAs("session-a")
            h.controller.load("session-a")
            h.rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = "session-a"))
            assertTrue(h.controller.isStreaming.value)

            // Switch away, then switch back before session-a's turn finishes.
            h.resumeAs("session-b")
            h.controller.load("session-b")
            assertFalse(h.controller.isStreaming.value)

            h.resumeAs("session-a")
            h.controller.load("session-a")
            // load() unconditionally resets to false; the flag only comes
            // back once a live event (message.start / session.info with
            // running=true) re-asserts it. This test documents that
            // contract — load() itself doesn't need to "know" the previous
            // state, since server-truth reconciliation (commit 3) is what
            // re-derives it on rebind.
            assertFalse(
                "load() resets isStreaming unconditionally on every bind; server-truth " +
                    "reconciliation on rebind is a separate concern",
                h.controller.isStreaming.value,
            )
        } finally {
            h.tearDown()
        }
    }
}
