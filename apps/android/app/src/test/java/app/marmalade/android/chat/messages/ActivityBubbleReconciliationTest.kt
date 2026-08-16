package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.types.SessionResumeResponse
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
 * Regression tests for the "activity bubble lingers after reply" report
 * (audit report kept internally, not in this repo).
 *
 * Root cause: no code path reconciled client-side "still streaming" state
 * against server truth after a reconnect. `_isStreaming` (cleared only by
 * a live message.complete/error) and a Room MessageEntity row's
 * isStreaming=true (preserved by history reconciliation's
 * keep-streaming-rows rule) both survived a disconnect/reconnect cycle
 * even when the turn actually completed server-side while the client was
 * offline. The server already exposes the fix-it signal
 * (`SessionRuntimeInfo.running`, sent on both live session.info events and
 * session.resume's info block) but ChatController never read it.
 *
 * Fix: read `info.running` at both sites (the live session.info handler
 * and hydrateFromServer); when it resolves to "not running", force-clear
 * _isStreaming and demote any Room row still isStreaming=true for that
 * session BEFORE reconcileHistory runs, so the demoted row content-matches
 * the server's finalized view instead of being preserved as a live stream.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivityBubbleReconciliationTest {

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

    private fun streamingRow(sessionKey: String, id: String = "stuck-1"): MessageEntity = MessageEntity(
        id = id,
        sessionKey = sessionKey,
        role = "assistant",
        contentJson = """[{"type":"text","text":"partial..."}]""",
        timestampMs = 1_000L,
        isStreaming = true,
    )

    // ── Live session.info path ────────────────────────────────────────────

    @Test
    fun `session_info running=false clears isStreaming and demotes the stuck Room row for the bound session`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.sessionResumeResponse = h.rpc.sessionResumeResponse.copy(session_id = "sess-live")
            h.dao.insertSession(SessionEntity(key = "sess-live", thinkingLevel = "off", gatewaySessionId = "sess-live"))
            h.rpc.openTransport()
            h.controller.load("sess-live")

            // Turn starts, then the client "misses" message.complete (e.g.
            // disconnect during the turn) — simulate the stuck state directly:
            // _isStreaming=true and a Room row left isStreaming=true.
            h.rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = "sess-live"))
            h.dao.insertMessage(streamingRow("sess-live"))
            assertTrue(h.controller.isStreaming.value)
            assertTrue(h.dao.getMessagesForSessionOnce("sess-live").single().isStreaming)

            // Server reports the session is no longer running — the turn
            // finished server-side while we weren't watching.
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject { put("running", JsonPrimitive(false)) },
                    sessionId = "sess-live",
                ),
            )

            assertFalse(
                "_isStreaming must clear once the server reports running=false for the bound session",
                h.controller.isStreaming.value,
            )
            assertFalse(
                "the stuck Room row must be demoted so the bubble stops rendering as streaming",
                h.dao.getMessagesForSessionOnce("sess-live").single().isStreaming,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info running=false for a background session does not touch the bound session`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.sessionResumeResponse = h.rpc.sessionResumeResponse.copy(session_id = "sess-fg")
            h.dao.insertSession(SessionEntity(key = "sess-fg", thinkingLevel = "off", gatewaySessionId = "sess-fg"))
            h.dao.insertSession(SessionEntity(key = "sess-bg", thinkingLevel = "off", gatewaySessionId = "sess-bg"))
            h.rpc.openTransport()
            h.controller.load("sess-fg")

            h.rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = "sess-fg"))
            assertTrue(h.controller.isStreaming.value)

            // A DIFFERENT (background) session reports running=false.
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject { put("running", JsonPrimitive(false)) },
                    sessionId = "sess-bg",
                ),
            )

            assertTrue(
                "a background session's running=false must not clear the foreground session's isStreaming",
                h.controller.isStreaming.value,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info running=false is ignored while a submitted prompt awaits its response`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.sessionResumeResponse = h.rpc.sessionResumeResponse.copy(session_id = "sess-await")
            h.dao.insertSession(
                SessionEntity(key = "sess-await", thinkingLevel = "off", gatewaySessionId = "sess-await"),
            )
            h.rpc.openTransport()
            h.controller.load("sess-await")

            // A prompt is submitted but not yet acked — its outbox row is the
            // awaiting-response marker. The turn already started client-side.
            h.dao.insertOutbox(
                app.marmalade.android.data.local.entity.OutboxEntity(
                    id = "outbox-await",
                    sessionKey = "sess-await",
                    serverSessionId = "sess-await",
                    contentJson = """[{"type":"text","text":"go"}]""",
                    createdAtMs = 1_000L,
                    clientOrdinal = 1L,
                    status = "sending",
                ),
            )
            h.rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = "sess-await"))
            h.dao.insertMessage(streamingRow("sess-await", id = "live-turn"))
            assertTrue(h.controller.isStreaming.value)

            // A stale running=false echo (the server emits session.info for
            // every config.set around submit time) must not kill the turn.
            // Desktop's guard: use-message-stream.ts:801-803
            // (awaitingResponse && !sawAssistantPayload → ignore).
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject { put("running", JsonPrimitive(false)) },
                    sessionId = "sess-await",
                ),
            )

            assertTrue(
                "running=false must be ignored while an un-acked prompt awaits its response — " +
                    "honoring the stale echo is how a starting turn got demoted into nothing",
                h.controller.isStreaming.value,
            )
            assertTrue(
                "the live turn's Room row must stay streaming",
                h.dao.getMessagesForSessionOnce("sess-await").single { it.id == "live-turn" }.isStreaming,
            )
        } finally {
            h.tearDown()
        }
    }

    // ── hydrateFromServer (session.subscribe) path ────────────────────────

    @Test
    fun `subscribe reporting run_state idle on reconnect clears stale streaming state`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(
                SessionEntity(key = "sess-resume", thinkingLevel = "off", gatewaySessionId = "sess-resume"),
            )
            // Pre-seed a Room row left isStreaming=true from before the
            // disconnect (as if message.start fired, then the socket dropped
            // before message.complete arrived).
            h.dao.insertMessage(streamingRow("sess-resume"))

            // The subscribe response reports the run is over (P2 run_state).
            h.rpc.sessionResumeResponse = SessionResumeResponse(session_id = "sess-resume")
            h.rpc.sessionSubscribeResponse = app.marmalade.android.rpc.types.SessionSubscribeResponse(
                session_id = "sess-resume",
                replayed = 0,
                last_seq = 0,
                lifecycle = "active",
                run_state = "idle",
            )
            h.rpc.openTransport()

            h.controller.load("sess-resume")

            assertFalse(
                "_isStreaming must be cleared by hydrateFromServer when session.subscribe reports run_state=idle",
                h.controller.isStreaming.value,
            )
            assertFalse(
                "no row may still be isStreaming after the demote step — the run ended server-side",
                h.dao.getMessagesForSessionOnce("sess-resume").any { it.isStreaming },
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `subscribe reporting run_state running does NOT clear isStreaming`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(
                SessionEntity(key = "sess-inflight", thinkingLevel = "off", gatewaySessionId = "sess-inflight"),
            )
            h.dao.insertMessage(streamingRow("sess-inflight"))

            h.rpc.sessionResumeResponse = SessionResumeResponse(session_id = "sess-inflight")
            h.rpc.sessionSubscribeResponse = app.marmalade.android.rpc.types.SessionSubscribeResponse(
                session_id = "sess-inflight",
                replayed = 3,
                last_seq = 7,
                lifecycle = "active",
                run_state = "running",
            )
            h.rpc.openTransport()

            h.controller.load("sess-inflight")

            val stuckRow = h.dao.getMessagesForSessionOnce("sess-inflight").single { it.id == "stuck-1" }
            assertTrue(
                "a genuinely in-progress turn (run_state=running) must not be demoted",
                stuckRow.isStreaming,
            )
            assertTrue(
                "_isStreaming must reflect the server's run_state=running at attach",
                h.controller.isStreaming.value,
            )
        } finally {
            h.tearDown()
        }
    }
}
