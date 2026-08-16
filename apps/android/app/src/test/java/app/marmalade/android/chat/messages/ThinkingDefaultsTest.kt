package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.types.ModelListEntry
import app.marmalade.android.rpc.types.ModelListResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * New-session THINKING defaults (2026-07-25).
 *
 * The maintainer opened a fresh session and it read "Thinking: Off" — a level marmaladed
 * does not even accept. Two bugs met: the composer seeded every un-created
 * session at "off" instead of the daemon's advertised `default_effort`, and
 * the pick was dropped on the floor at session.create (only `model` rode the
 * wire), so the daemon quietly stamped its own effort while the UI claimed
 * otherwise. Both halves are covered here.
 *
 * The invariant: what the composer SHOWS is what the next session will
 * actually run at, and the client never sends an effort the daemon rejects.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThinkingDefaultsTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val drainer: OutboxDrainer,
        val controller: ChatController,
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
        return Harness(dao, rpc, drainer, controller, scope)
    }

    private fun catalog(defaultEffort: String? = "high") = ModelListResponse(
        models = listOf(ModelListEntry(id = "claude-opus-5", label = "Opus 5")),
        default_model = "claude-opus-5",
        default_effort = defaultEffort,
        efforts = listOf("low", "medium", "high", "xhigh", "max"),
    )

    @Test
    fun `model_list seeds the composer with the daemon's default effort and vocabulary`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.modelListResponse = catalog()
            h.rpc.openTransport()
            h.controller.refreshModels()
            assertEquals("high", h.controller.defaultEffort.value)
            assertEquals(
                listOf("low", "medium", "high", "xhigh", "max"),
                h.controller.efforts.value,
            )
            // The visible composer level, not just the advertised default:
            // pre-fix this stayed at "off" until a turn completed.
            assertEquals("high", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `an untouched session omits reasoning_effort — the daemon default stands`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "chat-local-1", thinkingLevel = "off"))
            h.rpc.modelListResponse = catalog()
            h.rpc.openTransport()
            h.controller.refreshModels()

            h.drainer.resolveSessionId!!.invoke("chat-local-1")

            assertNull(
                "matching the daemon default must ride as an OMITTED param so the " +
                    "daemon stays the single source of truth",
                h.rpc.sessionCreateCalls.last().reasoningEffort,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `an explicit pick rides session_create`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "chat-local-2", thinkingLevel = "off"))
            h.rpc.modelListResponse = catalog()
            h.rpc.openTransport()
            h.controller.refreshModels()
            h.controller.setThinkingLevel("max")

            h.drainer.resolveSessionId!!.invoke("chat-local-2")

            assertEquals("max", h.rpc.sessionCreateCalls.last().reasoningEffort)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a level the daemon does not accept is never sent`() = runTest {
        val h = buildHarness()
        try {
            // A Room row written before this fix (or a stale local pick) holds
            // "off". Sending it would fail the create with InvalidParams and
            // lose the user's message; it must be dropped instead.
            h.dao.insertSession(SessionEntity(key = "chat-local-3", thinkingLevel = "off"))
            h.rpc.modelListResponse = catalog(defaultEffort = null)
            h.rpc.openTransport()
            h.controller.refreshModels()
            h.controller.setThinkingLevel("off")

            h.drainer.resolveSessionId!!.invoke("chat-local-3")

            assertNull(h.rpc.sessionCreateCalls.last().reasoningEffort)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `opening a legacy off row shows the daemon default, not off`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "chat-legacy", thinkingLevel = "off"))
            h.rpc.modelListResponse = catalog()
            h.rpc.openTransport()
            h.controller.refreshModels()

            h.controller.switchSession("chat-legacy")

            assertEquals("high", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a daemon advertising no default leaves the stored level alone`() = runTest {
        val h = buildHarness()
        try {
            // Older daemon: nothing to seed from, so the row's own value stands
            // rather than being replaced by a guess.
            h.dao.insertSession(SessionEntity(key = "chat-old", thinkingLevel = "off"))
            h.rpc.modelListResponse = ModelListResponse(
                models = listOf(ModelListEntry(id = "claude-opus-5", label = "Opus 5")),
            )
            h.rpc.openTransport()
            h.controller.refreshModels()

            h.controller.switchSession("chat-old")

            assertEquals("off", h.controller.thinkingLevel.value)
        } finally {
            h.tearDown()
        }
    }
}
