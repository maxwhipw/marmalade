package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.types.SessionResumeResponse
import app.marmalade.android.rpc.types.SessionSubscribeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Hardening #2 — process-death mid-turn recovery (the daily-driver reason the
 * daemon WS is pinned by a foreground service: the OS can still kill the
 * process, and when START_STICKY brings it back the client must recover the
 * turn it was streaming).
 *
 * On-disk state after a mid-turn kill (boot recovery demotes isStreaming=1→0):
 * a partial assistant row keyed by the daemon's message_id, its serverSeq
 * pinned at the turn's START seq (never bumped by deltas — see
 * ChatController identity invariant #3). That pin is load-bearing: the
 * reconnect cursor is `since_seq = MAX(serverSeq)`, so pinning at the start
 * makes the daemon replay the WHOLE delta range of the turn, not the tail —
 * the full text is reconstructed with no loss.
 *
 * Recovery runs through the SAME hydrateFromServer path as any reconnect
 * (session.resume + session.subscribe), so this is an end-to-end drive of it:
 *   1. the cursor lands on the start seq (replay the whole turn), and
 *   2. the replayed gap (deltas + complete, WITHOUT a re-sent message.start —
 *      the daemon replays seq > since_seq, and since_seq == start) rebuilds
 *      the bubble under the same message_id, so the finalized row REPLACEs the
 *      stale partial by id: one row, full text, cursor advanced past the turn.
 *
 * This is the gap the existing suite leaves: MessageStreamStableIdsTest's
 * "replayed message start rebuilds a stale partial" forces a re-sent start
 * (serverSeq below the start seq); SubscribeAndSeenTest checks the cursor but
 * streams nothing. Neither drives the real death path where the start is NOT
 * replayed and recovery rides on id-upsert alone.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessDeathRecoveryTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val stream: MessageStream,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    /** Mirrors SubscribeAndSeenTest's harness, but shares the runTest virtual
     *  clock so the delta-flush + persistence debounce actually fire under
     *  advanceUntilIdle(). */
    private fun TestScope.buildHarness(): Harness {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
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
            ioDispatcher = dispatcher,
        )
        return Harness(dao, rpc, controller, stream, scope)
    }

    private fun event(
        type: String,
        seq: Long,
        messageId: String,
        sid: String,
        extra: JsonObjectBuilder.() -> Unit = {},
    ) = GatewayEvent(
        type = type,
        payload = buildJsonObject {
            put("message_id", messageId)
            put("seq", seq)
            put("ts", 1_700_000_000_000L + seq)
            extra()
        },
        sessionId = sid,
    )

    @Test
    fun `mid-turn kill recovers the streamed turn on reconnect - one row, full text, no loss`() = runTest {
        val h = buildHarness()
        try {
            val key = "sess-death"
            val msgId = "srvAsstA"
            h.dao.insertSession(SessionEntity(key = key, thinkingLevel = "off", gatewaySessionId = key))
            h.rpc.sessionResumeResponse = SessionResumeResponse(session_id = key)

            // On-disk state after the kill: user turn finalized at seq 1, and a
            // partial assistant bubble pinned at its START seq (2). The first
            // delta ("par") had already streamed + persisted; boot recovery
            // demoted isStreaming 1→0. serverSeq stays at the start (2), NOT the
            // delta's seq — that's what makes the whole turn re-replay.
            h.dao.insertMessage(
                MessageEntity(
                    id = "srvUser1", sessionKey = key, role = "user",
                    contentJson = """[{"type":"text","text":"question"}]""",
                    timestampMs = 1L, serverSeq = 1L,
                ),
            )
            h.dao.insertMessage(
                MessageEntity(
                    id = msgId, sessionKey = key, role = "assistant",
                    contentJson = """[{"type":"text","text":"par"}]""",
                    timestampMs = 2L, serverSeq = 2L, isStreaming = false,
                ),
            )

            // The daemon kept running the turn while we were dead; it reports
            // the run finished, replay head at the complete seq.
            h.rpc.sessionSubscribeResponse = SessionSubscribeResponse(
                session_id = key, replayed = 3, last_seq = 5,
                lifecycle = "active", run_state = "idle",
            )

            // Fresh process → connect → bind the session (the restart path).
            h.rpc.openTransport()
            h.controller.load(key)
            runCurrent()

            // (1) The reconnect cursor pins to the START seq so the daemon
            //     replays the WHOLE turn — never the tail (which would lose "par").
            val sub = h.rpc.sessionSubscribeCalls.last()
            assertEquals(key, sub.sessionId)
            assertEquals(
                "since_seq must be the partial's start seq so the whole delta range re-replays",
                2L, sub.sinceSeq,
            )

            // (2) The daemon replays seq > 2: the full delta range + complete,
            //     each stamped with the SAME message_id. No message.start is
            //     re-sent (2 is not > 2) — recovery rides on id-upsert alone.
            h.rpc.emit(event("message.delta", seq = 3, messageId = msgId, sid = key) { put("text", "par") })
            h.rpc.emit(event("message.delta", seq = 4, messageId = msgId, sid = key) { put("text", "tial answer") })
            h.rpc.emit(
                event("message.complete", seq = 5, messageId = msgId, sid = key) { put("text", "partial answer") },
            )
            advanceUntilIdle()

            // Rendered bubble: the whole turn re-replayed, so the canonical
            // final text is reconstructed intact ("par" + "tial answer").
            val rendered = h.stream.sessionMessages(key).value.messages.filter { it.role == ChatRole.Assistant }
            assertEquals("one finalized assistant bubble, not two", 1, rendered.size)
            assertEquals(msgId, rendered.single().id)
            assertEquals(
                "the whole turn re-replayed, so the final text is reconstructed with no loss",
                "partial answer", rendered.single().text(),
            )

            // On disk: the stale partial is REPLACEd by id — one row, finalized,
            // carrying the COMPLETE seq so the cursor sits past the whole turn.
            val assistantRows = h.dao.getMessagesForSessionOnce(key).filter { it.role == "assistant" }
            assertEquals(
                "the stale partial must be REPLACEd by id, not duplicated",
                1, assistantRows.size,
            )
            val recovered = assistantRows.single()
            assertEquals(msgId, recovered.id)
            assertFalse("the recovered turn is finalized, not left streaming", recovered.isStreaming)
            assertEquals(
                "finalized row carries the COMPLETE seq so the cursor sits past the whole turn",
                5L, recovered.serverSeq,
            )
            assertEquals(
                "next reconnect must not re-replay a finished turn",
                5L, h.dao.getMaxServerSeq(key),
            )
        } finally {
            h.tearDown()
        }
    }
}
