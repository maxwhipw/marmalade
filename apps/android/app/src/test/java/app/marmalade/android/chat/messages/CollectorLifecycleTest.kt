package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.types.SessionResumeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lifecycle tests for ChatController's process-lifetime collectors
 * (replaces StartCollectorLeakTest).
 *
 * History: the finalized-assistants and pending-run collectors used to be
 * (re)launched per session switch under an `activeCollector` job. That
 * design bred a whole bug class — an untracked second launch leaked one
 * collector per switch (fix a23a87b), and deleteSession cancelled the
 * collector WITHOUT restarting it, silently dropping every cross-session
 * notification until the next navigation. Both collectors are session-
 * independent (they resolve the event's session themselves), so they now
 * live in init for the process lifetime, like desktop's single global
 * handleGatewayEvent subscription. These tests pin the two properties
 * that made the old design fail.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CollectorLifecycleTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
        val parentJob: Job,
        val otherSessionMessages: MutableList<Pair<String, String>>,
    ) {
        fun tearDown() = scope.cancel()
    }

    private suspend fun buildHarness(): Harness {
        val parentJob = Job()
        val scope = CoroutineScope(UnconfinedTestDispatcher() + parentJob)
        val dao = FakeChatDao()
        val rpc = FakeMarmaladeRpc()
        rpc.openTransport()
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
        val otherSessionMessages = mutableListOf<Pair<String, String>>()
        val controller = ChatController(
            scope = scope,
            rpc = rpc,
            messageStream = stream,
            outboxDrainer = drainer,
            json = testJson,
            chatDao = dao,
            onOtherSessionMessage = { sessionKey, _, text ->
                otherSessionMessages.add(sessionKey to text)
            },
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return Harness(dao, rpc, controller, scope, parentJob, otherSessionMessages)
    }

    @Test
    fun `switching sessions repeatedly does not grow the live child-job count`() = runTest {
        val h = buildHarness()
        try {
            h.controller.load("session-a")
            val baselineChildren = h.parentJob.children.count { it.isActive }

            repeat(5) { i -> h.controller.load("session-b-$i") }

            assertEquals(
                "live child-job count under `scope` must not grow across repeated session " +
                    "switches — a growing count means a per-switch collector is leaking again",
                baselineChildren,
                h.parentJob.children.count { it.isActive },
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `cross-session notifications survive deleting the bound session`() = runTest {
        val h = buildHarness()
        try {
            // Bind a session, then delete it — under the old per-session
            // collector design this cancelled the finalizedAssistants
            // collector without restarting it, so a background session's
            // reply produced NO notification until the next navigation.
            h.dao.insertSession(SessionEntity(key = "sess-doomed", thinkingLevel = "off", gatewaySessionId = "sess-doomed"))
            h.dao.insertSession(SessionEntity(key = "sess-bg", thinkingLevel = "off", gatewaySessionId = "live-bg"))
            h.rpc.sessionResumeResponse = SessionResumeResponse(session_id = "sess-doomed")
            h.controller.load("sess-doomed")
            h.controller.deleteSession("sess-doomed")
            advanceUntilIdle()

            // A background session finalizes a turn (events stamped with its
            // LIVE id).
            h.rpc.emit(GatewayEvent(type = "message.start", payload = null, sessionId = "live-bg"))
            h.rpc.emit(
                GatewayEvent(
                    type = "message.delta",
                    payload = buildJsonObject { put("text", JsonPrimitive("background reply")) },
                    sessionId = "live-bg",
                ),
            )
            h.rpc.emit(
                GatewayEvent(
                    type = "message.complete",
                    payload = buildJsonObject { put("text", JsonPrimitive("background reply")) },
                    sessionId = "live-bg",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "the cross-session notification must fire even after deleteSession — the " +
                    "finalized-assistants collector is process-lifetime now",
                listOf("sess-bg" to "background reply"),
                h.otherSessionMessages,
            )
        } finally {
            h.tearDown()
        }
    }
}
