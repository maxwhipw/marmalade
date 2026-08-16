package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.types.SessionResumeResponse
import app.marmalade.android.rpc.types.SessionSubscribeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatController attach semantics against marmaladed (P4):
 * session.subscribe replays from the LOCAL seq cursor (MAX(serverSeq)),
 * and viewing/finishing a turn stamps this device's read cursor via
 * session.seen(seq) — unread is arithmetic, never wall-clock.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscribeAndSeenTest {

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
        return Harness(dao, rpc, controller, stream, scope)
    }

    private suspend fun Harness.seedSession(key: String) {
        dao.insertSession(SessionEntity(key = key, thinkingLevel = "off", gatewaySessionId = key))
        rpc.sessionResumeResponse = SessionResumeResponse(session_id = key)
    }

    @Test
    fun `subscribe replays from the local serverSeq cursor`() = runTest {
        val h = buildHarness()
        try {
            h.seedSession("sess-cur")
            // Locally-stored rows up to seq 7 — the replay cursor.
            h.dao.insertMessage(
                MessageEntity(
                    id = "m7", sessionKey = "sess-cur", role = "assistant",
                    contentJson = """[{"type":"text","text":"old"}]""",
                    timestampMs = 1L, serverSeq = 7L,
                ),
            )
            h.rpc.openTransport()
            h.controller.load("sess-cur")

            val call = h.rpc.sessionSubscribeCalls.last()
            assertEquals("sess-cur", call.sessionId)
            assertEquals(
                "since_seq must be the highest locally-rendered seq — replay only the gap",
                7L, call.sinceSeq,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `attach stamps the read cursor at the replay head when foreground`() = runTest {
        val h = buildHarness()
        try {
            h.seedSession("sess-seen")
            h.dao.insertMessage(
                MessageEntity(
                    id = "m9", sessionKey = "sess-seen", role = "assistant",
                    contentJson = """[{"type":"text","text":"latest"}]""",
                    timestampMs = 1L, serverSeq = 9L,
                ),
            )
            h.rpc.sessionSubscribeResponse = SessionSubscribeResponse(
                session_id = "sess-seen", replayed = 0, last_seq = 9,
                lifecycle = "active", run_state = "idle",
            )
            h.rpc.openTransport()
            h.controller.load("sess-seen")

            val seen = h.rpc.sessionSeenCalls.last()
            assertEquals("sess-seen", seen.sessionId)
            assertEquals("viewing IS seeing — stamp at the rendered head", 9L, seen.seq)
            // Optimistic local merge clears the chip without a list refresh.
            assertEquals(9L, h.dao.getSessionByKey("sess-seen")?.seenSeq)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `status update run_state drives streaming state and the Room row`() = runTest {
        val h = buildHarness()
        try {
            h.seedSession("sess-run")
            h.rpc.openTransport()
            h.controller.load("sess-run")
            assertFalse(h.controller.isStreaming.value)

            h.rpc.emit(
                GatewayEvent(
                    type = "status.update",
                    payload = buildJsonObject {
                        put("session_id", "sess-run")
                        put("lifecycle", "active")
                        put("run_state", "running")
                        put("seq", JsonPrimitive(11))
                    },
                    sessionId = "sess-run",
                ),
            )
            assertTrue("run_state=running must flip the bound streaming flag", h.controller.isStreaming.value)
            assertEquals("running", h.dao.getSessionByKey("sess-run")?.runState)

            h.rpc.emit(
                GatewayEvent(
                    type = "status.update",
                    payload = buildJsonObject {
                        put("session_id", "sess-run")
                        put("lifecycle", "active")
                        put("run_state", "idle")
                        put("seq", JsonPrimitive(12))
                    },
                    sessionId = "sess-run",
                ),
            )
            assertFalse("run_state=idle must clear it", h.controller.isStreaming.value)
            assertEquals("idle", h.dao.getSessionByKey("sess-run")?.runState)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session list cursors merge monotonically - a stale row never regresses seenSeq`() = runTest {
        val h = buildHarness()
        try {
            h.seedSession("sess-list")
            h.dao.mergeSessionSeqCursors("sess-list", lastSeq = 20L, seenSeq = 20L)
            h.rpc.sessionListResponse = app.marmalade.android.rpc.types.SessionListResponse(
                sessions = listOf(
                    app.marmalade.android.rpc.types.SessionListRow(
                        session_id = "sess-list", topic = "t",
                        last_seq = 15, seen_seq = 10, // stale snapshot
                    ),
                ),
            )
            h.rpc.openTransport()
            h.controller.refreshSessions()

            val row = h.dao.getSessionByKey("sess-list")
            assertEquals(20L, row?.lastSeq)
            assertEquals("max-merge: the stale list row must not regress the cursor", 20L, row?.seenSeq)
        } finally {
            h.tearDown()
        }
    }
}
