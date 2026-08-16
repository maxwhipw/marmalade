package app.marmalade.android.terminal

import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.JsonRpcClient
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.WebSocketFactory
import app.marmalade.android.rpc.types.TerminalAttachResponse
import app.marmalade.android.rpc.types.TerminalCloseResponse
import app.marmalade.android.rpc.types.TerminalCreateResponse
import app.marmalade.android.rpc.types.TerminalDataPayload
import app.marmalade.android.rpc.types.TerminalExitPayload
import app.marmalade.android.rpc.types.TerminalInfo
import app.marmalade.android.rpc.types.TerminalListResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [TerminalController] through the full create → attach → input → exit
 * flow against a scripted fake RPC + a hand-fed event stream, mirroring how the
 * screens use it. Proves the transient terminal.data/terminal.exit routing
 * (around the chat path) and the roster refresh-on-exit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControllerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun dataEvent(id: String, b64: String) = GatewayEvent(
        type = "terminal.data",
        payload = json.encodeToJsonElement(TerminalDataPayload.serializer(), TerminalDataPayload(id, b64)),
    )

    private fun exitEvent(id: String, code: Int?) = GatewayEvent(
        type = "terminal.exit",
        payload = json.encodeToJsonElement(TerminalExitPayload.serializer(), TerminalExitPayload(id, code)),
    )

    // UnconfinedTestDispatcher so the SharedFlow collectors (controller's event
    // demux + the test's output/exit collectors) subscribe eagerly before we
    // emit — a StandardTestDispatcher would leave them scheduled-but-unsubscribed.
    @Test fun createAttachInputExitFlow() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val rpc = FakeTerminalRpc()
        val controller = TerminalController(rpc, backgroundScope, events.asSharedFlow(), json)

        val output = mutableListOf<TerminalDataPayload>()
        val exits = mutableListOf<TerminalExitPayload>()
        backgroundScope.launch { controller.output.collect { output += it } }
        backgroundScope.launch { controller.exits.collect { exits += it } }
        advanceUntilIdle()

        // create → terminalCreate + roster refresh (row present immediately).
        val info = controller.create(cols = 100, rows = 40)
        advanceUntilIdle()
        assertEquals("t_1", info.terminal_id)
        assertEquals(listOf(Triple(100, 40, null)), rpc.createCalls)
        assertEquals(listOf("t_1"), controller.terminals.value.map { it.terminal_id })

        // attach → snapshot carried in the result.
        val attach = controller.attach("t_1")
        assertEquals("c25hcA==", attach.snapshot_b64)
        assertEquals(listOf("t_1"), rpc.attachCalls)

        // input → base64 written verbatim on the wire.
        controller.input("t_1", "aGk=")
        assertEquals(listOf("t_1" to "aGk="), rpc.inputCalls)

        // terminal.data event surfaces on output (routed around chat/session).
        events.emit(dataEvent("t_1", "JCA="))
        advanceUntilIdle()
        assertEquals(1, output.size)
        assertEquals("JCA=", output[0].data_b64)

        // terminal.exit → surfaces on exits AND prunes the roster (refresh).
        rpc.roster = emptyList()
        events.emit(exitEvent("t_1", 0))
        advanceUntilIdle()
        assertEquals(1, exits.size)
        assertEquals(0, exits[0].exit_code)
        assertTrue(controller.terminals.value.isEmpty())
    }

    @Test fun closePrunesRoster() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
        val rpc = FakeTerminalRpc()
        val controller = TerminalController(rpc, backgroundScope, events.asSharedFlow(), json)

        controller.create()
        advanceUntilIdle()
        assertEquals(1, controller.terminals.value.size)

        rpc.roster = emptyList()
        val closed = controller.close("t_1")
        advanceUntilIdle()
        assertTrue(closed)
        assertEquals(listOf("t_1"), rpc.closeCalls)
        assertTrue(controller.terminals.value.isEmpty())
    }
}

/** Scriptable terminal RPC: records calls, serves a mutable roster. Only the
 *  seven terminal methods matter; the superclass client is never touched. */
private class FakeTerminalRpc : MarmaladeRpc(client = StubJsonRpcClient) {

    var roster: List<TerminalInfo> = emptyList()
    val createCalls = mutableListOf<Triple<Int, Int, String?>>()
    val attachCalls = mutableListOf<String>()
    val inputCalls = mutableListOf<Pair<String, String>>()
    val closeCalls = mutableListOf<String>()

    private val info = TerminalInfo(
        terminal_id = "t_1", shell = "bash", cwd = "/home/user",
        cols = 80, rows = 24, pid = 4242, created_at = 1, last_active = 1,
    )

    override suspend fun terminalCreate(cols: Int, rows: Int, cwd: String?): TerminalCreateResponse {
        createCalls += Triple(cols, rows, cwd)
        roster = listOf(info)
        return TerminalCreateResponse(info)
    }

    override suspend fun terminalList(): TerminalListResponse = TerminalListResponse(roster)

    override suspend fun terminalAttach(terminalId: String): TerminalAttachResponse {
        attachCalls += terminalId
        return TerminalAttachResponse(terminal = info, snapshot_b64 = "c25hcA==")
    }

    override suspend fun terminalInput(terminalId: String, dataB64: String) {
        inputCalls += terminalId to dataB64
    }

    override suspend fun terminalResize(terminalId: String, cols: Int, rows: Int) {}

    override suspend fun terminalDetach(terminalId: String) {}

    override suspend fun terminalClose(terminalId: String): TerminalCloseResponse {
        closeCalls += terminalId
        return TerminalCloseResponse(closed = true)
    }
}

/** Never used — the fake overrides every terminal method. Present only to
 *  satisfy MarmaladeRpc's constructor. */
private val StubJsonRpcClient: JsonRpcClient by lazy {
    JsonRpcClient(
        webSocketFactory = object : WebSocketFactory {
            override fun create(request: Request, listener: WebSocketListener): WebSocket =
                throw UnsupportedOperationException("test stub")
        },
    )
}
