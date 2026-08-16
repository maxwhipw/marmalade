package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.types.PromptSubmitAck
import app.marmalade.android.rpc.types.SessionResumeResponse
import app.marmalade.android.service.awaitVoiceReply
import app.marmalade.android.service.harvestVoiceReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice reply-harvest floor = the prompt.submit ack's seq, end-to-end through
 * the real pipeline: ChatController.sendMessage → outbox → OutboxDrainer →
 * ack → [ChatController.promptAcks] → [awaitVoiceReply] polling the SAME
 * Room-derived messages flow the popup uses.
 *
 * The regression this closes (Fable review 2026-07-16, major): the old floor
 * was the pre-submit LOCAL max seq — but the daemon replays
 * `seq > MAX(local serverSeq)` on a reconnect subscribe, so with Room behind
 * the server at popup-open a replayed OLD reply landed ABOVE the local floor
 * and was spoken as this turn's answer. The ack seq is server-truth:
 * everything at or below it predates our submit, whatever the local cache had.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceAckFloorTest {

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
        drainer.start()
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

    /** Bind [key] with an old turn already in Room (user seq 1, reply seq 2)
     *  — the local cache is BEHIND a server whose history runs past seq 10. */
    private suspend fun Harness.bindStaleSession(key: String) {
        dao.insertSession(SessionEntity(key = key, thinkingLevel = "off", gatewaySessionId = key))
        rpc.sessionResumeResponse = SessionResumeResponse(session_id = key)
        dao.insertMessage(
            MessageEntity(
                id = "oldUser", sessionKey = key, role = "user",
                contentJson = """[{"type":"text","text":"an old question"}]""",
                timestampMs = 1L, serverSeq = 1L,
            ),
        )
        dao.insertMessage(
            MessageEntity(
                id = "oldReply", sessionKey = key, role = "assistant",
                contentJson = """[{"type":"text","text":"an old answer"}]""",
                timestampMs = 2L, serverSeq = 2L,
            ),
        )
        rpc.openTransport()
        controller.load(key)
    }

    @Test
    fun `a replayed old reply above the LOCAL floor is not spoken - only the post-ack reply is`() = runTest {
        val h = buildHarness()
        try {
            val key = "sess-voice"
            h.bindStaleSession(key)
            runCurrent()

            // The voice popup's floor inputs, captured just before submit.
            val localSeqFloor = h.controller.messages.value.maxOfOrNull { it.seq } ?: 0L
            assertEquals("Room is behind the server: local max is the old turn", 2L, localSeqFloor)

            // The daemon numbers our user message seq 20 — server history
            // (which local Room never saw) already ran to 19.
            h.rpc.promptSubmitAck = PromptSubmitAck(message_id = "srvUser20", seq = 20L, ts = 20_000L)
            val handle = h.controller.sendMessage(message = "what's the weather", thinkingLevel = "off")
            assertNotNull(handle)

            val wait = async {
                awaitVoiceReply(
                    acks = h.controller.promptAcks,
                    outboxId = handle!!.outboxId,
                    messages = h.controller.messages,
                    localSeqFloor = localSeqFloor,
                    isTurnAlive = { false },
                    elapsedMs = { testScheduler.currentTime },
                    pollMs = 1_000L,
                    graceMs = 30_000L,
                    hardCapMs = 300_000L,
                )
            }
            // Let the drainer submit and the ack land (50ms debounce).
            advanceTimeBy(200L); runCurrent()
            assertEquals(1, h.rpc.submittedPrompts.size)

            // Reconnect replay re-emits an OLD turn the local cache had
            // missed (the daemon replays seq > since_seq: start + deltas +
            // complete): seq 10-12 — ABOVE the local floor (2), BELOW the ack
            // (20). This is exactly what the old local-max floor spoke aloud.
            h.rpc.emit(event("message.start", seq = 10, messageId = "missedOld", sid = key))
            h.rpc.emit(
                event("message.delta", seq = 11, messageId = "missedOld", sid = key) {
                    put("text", "a stale reply from before this turn")
                },
            )
            h.rpc.emit(
                event("message.complete", seq = 12, messageId = "missedOld", sid = key) {
                    put("text", "a stale reply from before this turn")
                },
            )
            advanceTimeBy(3_000L); runCurrent()

            // Regression demonstration: the OLD floor would have harvested it…
            assertEquals(
                "a stale reply from before this turn",
                harvestVoiceReply(h.controller.messages.value, localSeqFloor),
            )
            // …but the ack-floored wait must still be holding out.
            assertTrue("the stale replayed reply must not end the wait", wait.isActive)

            // The genuine reply to OUR turn lands above the ack seq.
            h.rpc.emit(
                event("message.start", seq = 21, messageId = "freshReply", sid = key),
            )
            h.rpc.emit(
                event("message.delta", seq = 22, messageId = "freshReply", sid = key) {
                    put("text", "Sunny and 20 degrees.")
                },
            )
            h.rpc.emit(
                event("message.complete", seq = 23, messageId = "freshReply", sid = key) {
                    put("text", "Sunny and 20 degrees.")
                },
            )
            advanceTimeBy(3_000L); runCurrent()

            assertEquals("Sunny and 20 degrees.", wait.await().replyText)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `an errored turn survives the Room round-trip and is not spoken`() = runTest {
        val h = buildHarness()
        try {
            val key = "sess-voice-err"
            h.bindStaleSession(key)
            runCurrent()

            h.rpc.promptSubmitAck = PromptSubmitAck(message_id = "srvUser20", seq = 20L, ts = 20_000L)
            val handle = h.controller.sendMessage(message = "tell me a story", thinkingLevel = "off")
            assertNotNull(handle)

            val wait = async {
                awaitVoiceReply(
                    acks = h.controller.promptAcks,
                    outboxId = handle!!.outboxId,
                    messages = h.controller.messages,
                    localSeqFloor = 2L,
                    isTurnAlive = { false },
                    elapsedMs = { testScheduler.currentTime },
                    pollMs = 1_000L,
                    graceMs = 5_000L,
                    hardCapMs = 300_000L,
                )
            }
            advanceTimeBy(200L); runCurrent()

            // The turn starts, streams half a sentence, then the server
            // errors out — the bubble finalizes with the truncated partial.
            h.rpc.emit(event("message.start", seq = 21, messageId = "errReply", sid = key))
            h.rpc.emit(
                event("message.delta", seq = 22, messageId = "errReply", sid = key) {
                    put("text", "Once upon a ti")
                },
            )
            h.rpc.emit(
                event("error", seq = 23, messageId = "errReply", sid = key) {
                    put("message", "provider crashed")
                },
            )
            advanceTimeBy(2_000L); runCurrent()

            // The error must survive Room (messages.error, DB v21) — the chat
            // view derives entirely from Room rows, so a dropped error field
            // re-renders the truncated partial as a clean, speakable reply.
            val errored = h.controller.messages.value.single { it.id == "errReply" }
            assertEquals("provider crashed", errored.error)
            assertEquals("Once upon a ti", errored.text())

            // The wait must NOT speak it: grace drains with no life → no reply.
            val result = wait.await()
            assertNull("an errored turn's truncated text must not reach TTS", result.replyText)
        } finally {
            h.tearDown()
        }
    }
}
