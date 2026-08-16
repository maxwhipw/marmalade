package app.marmalade.android.chat.messages

import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val testJson = Json { ignoreUnknownKeys = true }

/**
 * Digital-twin tests over marmaladed-shaped frames (identity plan P1/P4):
 * every event carries server-minted `message_id` + monotonic `seq` + `ts`,
 * exactly as the daemon's SessionIdentity.stampEvent emits them. These are
 * the frames a real `session.subscribe` replay re-sends, so the same suite
 * covers live streaming AND replay semantics: dedup by id/seq watermark,
 * ordering by seq, cursor placement at the complete event's seq.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageStreamStableIdsTest {

    private fun stamped(
        type: String,
        seq: Long,
        messageId: String? = null,
        sid: String = "sess-1",
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): GatewayEvent {
        // Build payload the way identity.ts stamps it: additive fields on
        // the event payload.
        val payload = buildJsonObject {
            messageId?.let { put("message_id", it) }
            put("seq", seq)
            put("ts", 1_700_000_000_000L + seq)
            extra()
        }
        return GatewayEvent(type = type, payload = payload, sessionId = sid)
    }

    @Test
    fun `bubble adopts the server-minted message id from message start`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("sess-1")

        events.emit(stamped("message.start", seq = 2, messageId = "srvMsgA"))
        events.emit(stamped("message.delta", seq = 3, messageId = "srvMsgA") { put("text", "hi") })
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()

        val pending = state.value.pending
        assertNotNull(pending)
        assertEquals("ids are names, minted once by the daemon — never synthesize", "srvMsgA", pending!!.id)
    }

    @Test
    fun `finalized message stores the complete event seq - the replay cursor never re-replays a finished turn`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("sess-1")

        events.emit(stamped("message.start", seq = 2, messageId = "srvMsgA"))
        events.emit(stamped("message.delta", seq = 3, messageId = "srvMsgA") { put("text", "hi") })
        events.emit(stamped("message.complete", seq = 4, messageId = "srvMsgA") { put("text", "hi") })
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()

        val finalized = state.value.messages.single()
        assertEquals("srvMsgA", finalized.id)
        assertEquals(
            "the finalized row must carry the COMPLETE event's seq so MAX(serverSeq) " +
                "places the subscribe cursor after the whole turn",
            4L, finalized.seq,
        )
        assertEquals(4L, stream.lastSeq("sess-1"))
    }

    @Test
    fun `replay overlap is dropped by the seq watermark`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("sess-1")

        events.emit(stamped("message.start", seq = 2, messageId = "srvMsgA"))
        events.emit(stamped("message.delta", seq = 3, messageId = "srvMsgA") { put("text", "once") })
        // The same delta delivered again (subscribe replay from a stale Room
        // cursor while memory already applied it).
        events.emit(stamped("message.delta", seq = 3, messageId = "srvMsgA") { put("text", "once") })
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()

        assertEquals("duplicate seq must apply exactly once", "once", state.value.pending?.text())
    }

    @Test
    fun `message user replay renders a cross-device user bubble once`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "sess-1", gatewaySessionId = "sess-1"))
        val stream = MessageStream(events, backgroundScope, dao, testJson, now = { 0L })
        runCurrent()

        events.emit(stamped("message.user", seq = 1, messageId = "usrMsg1") { put("text", "from the desktop") })
        runCurrent()

        val rows = dao.getMessagesForSessionOnce("sess-1")
        assertEquals(1, rows.size)
        assertEquals("usrMsg1", rows.single().id)
        assertEquals("user", rows.single().role)
        assertEquals(1L, rows.single().serverSeq)

        // Second replay of the same event (fresh process, memory watermark
        // reset): dedup is by the server-minted id.
        val stream2 = MessageStream(events, backgroundScope, dao, testJson, now = { 0L })
        runCurrent()
        events.emit(stamped("message.user", seq = 1, messageId = "usrMsg1") { put("text", "from the desktop") })
        runCurrent()
        assertEquals(
            "same id = same message — a replayed message.user must not duplicate",
            1, dao.getMessagesForSessionOnce("sess-1").size,
        )
        stream2.close()
    }

    @Test
    fun `own submitted message is not duplicated by its replayed message user event`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "sess-1", gatewaySessionId = "sess-1"))
        // The outbox ack already promoted this device's own bubble under the
        // server-minted id (prompt.submit result).
        dao.insertMessage(
            MessageEntity(
                id = "usrMsg1", sessionKey = "sess-1", role = "user",
                contentJson = """[{"type":"text","text":"mine"}]""",
                timestampMs = 10L, serverSeq = 1L,
            ),
        )
        val stream = MessageStream(events, backgroundScope, dao, testJson, now = { 0L })
        runCurrent()

        events.emit(stamped("message.user", seq = 1, messageId = "usrMsg1") { put("text", "mine") })
        runCurrent()

        assertEquals(1, dao.getMessagesForSessionOnce("sess-1").size)
        stream.close()
    }

    @Test
    fun `replayed message start rebuilds a stale partial row instead of duplicating it`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "sess-1", gatewaySessionId = "sess-1"))
        // Mid-stream kill: the partial bubble was flushed with the START seq
        // (2) and boot-demoted; the replay cursor (MAX serverSeq = 2) makes
        // the daemon re-send the turn from seq 3... but a FULL replay
        // (since_seq below the start) re-announces the same message id.
        dao.insertMessage(
            MessageEntity(
                id = "srvMsgA", sessionKey = "sess-1", role = "assistant",
                contentJson = """[{"type":"text","text":"par"}]""",
                timestampMs = 10L, serverSeq = 2L,
            ),
        )
        val stream = MessageStream(events, backgroundScope, dao, testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("sess-1")

        events.emit(stamped("message.start", seq = 3, messageId = "srvMsgA"))
        events.emit(stamped("message.delta", seq = 4, messageId = "srvMsgA") { put("text", "partial then full") })
        events.emit(stamped("message.complete", seq = 5, messageId = "srvMsgA") { put("text", "partial then full") })
        runCurrent()
        advanceTimeBy(300L)
        runCurrent()

        assertFalse(
            "the stale partial row must be deleted on the replayed start — same id = same message",
            dao.getMessagesForSessionOnce("sess-1").count { it.id == "srvMsgA" } > 1,
        )
        assertEquals("partial then full", state.value.messages.single().text())
        stream.close()
    }
}
