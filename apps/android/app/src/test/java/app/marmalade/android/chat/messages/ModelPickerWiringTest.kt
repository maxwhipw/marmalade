package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.ModelCatalogEntry
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
 * The model picker made real (marmaladed model.list + session.create model,
 * 2026-07-11). Verifies the two wire halves the picker rests on:
 *
 *  - [ChatController.refreshModels] maps the daemon's model.list menu into
 *    picker rows (id + human label, no fork provider grouping).
 *  - The deferred first-send create (OutboxDrainer.resolveSessionId →
 *    ensureServerSessionId) carries the picked model as session.create's
 *    `model` param — the ONE place a session's model is set; the daemon
 *    stores it and re-applies it on every resume.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelPickerWiringTest {

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
            // Inject the unconfined dispatcher for Room IO too (per the
            // ChatController ioDispatcher doc): without it, load()'s
            // hydration hops to REAL Dispatchers.IO and races the test body
            // — under machine load its `_currentModel = row.model` (null)
            // re-seed can land between setCurrentModel() and the deferred
            // create, flaking the model assertions.
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return Harness(dao, rpc, drainer, controller, scope)
    }

    @Test
    fun `refreshModels maps the daemon's model_list menu into picker rows`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.modelListResponse = ModelListResponse(
                models = listOf(
                    ModelListEntry(id = "claude-opus-4-8", label = "Opus 4.8"),
                    ModelListEntry(id = "claude-haiku-4-5", label = "Haiku 4.5"),
                ),
            )
            h.rpc.openTransport()
            h.controller.refreshModels()
            assertEquals(
                listOf(
                    ModelCatalogEntry(id = "claude-opus-4-8", name = "Opus 4.8", provider = ""),
                    ModelCatalogEntry(id = "claude-haiku-4-5", name = "Haiku 4.5", provider = ""),
                ),
                h.controller.models.value,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `refreshModels stashes the daemon-advertised default_model`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.modelListResponse = ModelListResponse(
                models = listOf(ModelListEntry(id = "claude-opus-4-8", label = "Opus 4.8")),
                default_model = "claude-opus-4-8",
            )
            h.rpc.openTransport()
            h.controller.refreshModels()
            assertEquals("claude-opus-4-8", h.controller.defaultModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `refreshModels leaves defaultModel null when the daemon advertises none`() = runTest {
        val h = buildHarness()
        try {
            // Old daemon: model.list carries no default_model.
            h.rpc.modelListResponse = ModelListResponse(
                models = listOf(ModelListEntry(id = "claude-opus-4-8", label = "Opus 4.8")),
            )
            h.rpc.openTransport()
            h.controller.refreshModels()
            assertNull(
                "no advertised default must degrade to null → chip keeps rendering \"Default\"",
                h.controller.defaultModel.value,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `deferred first-send create carries the picked model`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "chat-local-1", thinkingLevel = "off"))
            h.rpc.openTransport()
            h.controller.setCurrentModel("claude-opus-4-8")

            val sid = h.drainer.resolveSessionId!!.invoke("chat-local-1")

            assertEquals("server-session-1", sid)
            assertEquals("claude-opus-4-8", h.rpc.sessionCreateCalls.last().model)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `clearCurrentModel resets the pick to null`() = runTest {
        val h = buildHarness()
        try {
            h.controller.setCurrentModel("claude-opus-4-8")
            assertEquals("claude-opus-4-8", h.controller.currentModel.value)
            h.controller.clearCurrentModel()
            assertNull("the picker's Default row must null the pick", h.controller.currentModel.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `deferred create after pick-then-clear omits the model param`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "chat-local-3", thinkingLevel = "off"))
            h.rpc.openTransport()
            h.controller.setCurrentModel("claude-opus-4-8")
            h.controller.clearCurrentModel()

            h.drainer.resolveSessionId!!.invoke("chat-local-3")

            assertNull(
                "a cleared pick must ride session.create as an OMITTED model — the daemon default wins",
                h.rpc.sessionCreateCalls.last().model,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `create with no pick sends no model — the harness default wins`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "chat-local-2", thinkingLevel = "off"))
            h.rpc.openTransport()

            h.drainer.resolveSessionId!!.invoke("chat-local-2")

            assertNull(h.rpc.sessionCreateCalls.last().model)
        } finally {
            h.tearDown()
        }
    }
}
