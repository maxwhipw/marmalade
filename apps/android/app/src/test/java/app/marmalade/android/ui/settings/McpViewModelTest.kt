package app.marmalade.android.ui.settings

import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.DaemonMcpListResponse
import app.marmalade.android.rpc.types.DaemonMcpServerRow
import app.marmalade.android.rpc.types.DaemonToggleResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [McpViewModel] after its move to `:shared` (desktop-client plan Phase 1).
 * Same optimistic-toggle contract as [SkillsViewModelTest] — kept as its own
 * suite rather than a shared parameterised one because the two ViewModels are
 * only *similar*, and a shared harness would hide a future divergence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun row(name: String, enabled: Boolean) =
        DaemonMcpServerRow(name = name, enabled = enabled)

    private class FakeMcpRpc(
        private val servers: List<DaemonMcpServerRow>,
    ) : MarmaladeRpc(client = StubJsonRpcClient) {
        var toggleFails = false
        var listFails = false
        /** Non-null parks the toggle so a test can tap again mid-flight. */
        var gate: CompletableDeferred<Unit>? = null
        val toggleCalls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun mcpList(): DaemonMcpListResponse {
            if (listFails) throw IllegalStateException("daemon down")
            return DaemonMcpListResponse(servers = servers)
        }

        override suspend fun mcpToggle(name: String, enabled: Boolean): DaemonToggleResponse {
            toggleCalls += name to enabled
            gate?.await()
            if (toggleFails) throw IllegalStateException("nope")
            return DaemonToggleResponse(applied = true)
        }
    }

    private fun vm(rpc: MarmaladeRpc) = McpViewModel(rpc, io = dispatcher)

    @Test
    fun `loads the server list on init`() = runTest(dispatcher) {
        val rpc = FakeMcpRpc(listOf(row("fs", true), row("http", false)))

        val sut = vm(rpc)
        assertEquals(McpUiState.Loading, sut.uiState.value)
        dispatcher.scheduler.advanceUntilIdle()

        val servers = (sut.uiState.value as McpUiState.Success).servers
        assertEquals(listOf("fs", "http"), servers.map { it.name })
        assertEquals(listOf(true, false), servers.map { it.enabled })
    }

    @Test
    fun `load failure surfaces as Error`() = runTest(dispatcher) {
        val rpc = FakeMcpRpc(emptyList()).apply { listFails = true }

        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("daemon down", (sut.uiState.value as McpUiState.Error).message)
    }

    @Test
    fun `successful toggle flips the row and leaves no error`() = runTest(dispatcher) {
        val rpc = FakeMcpRpc(listOf(row("fs", false)))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.toggleServer("fs", true)
        // Optimistic: flipped before the request is dispatched.
        assertTrue((sut.uiState.value as McpUiState.Success).servers.single().enabled)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("fs" to true), rpc.toggleCalls)
        assertTrue((sut.uiState.value as McpUiState.Success).servers.single().enabled)
        assertNull(sut.toggleError.value)
    }

    @Test
    fun `failed toggle reverts the row and reports the error`() = runTest(dispatcher) {
        val rpc = FakeMcpRpc(listOf(row("fs", false))).apply { toggleFails = true }
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.toggleServer("fs", true)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse((sut.uiState.value as McpUiState.Success).servers.single().enabled)
        assertEquals("Toggle failed: nope", sut.toggleError.value)

        sut.clearToggleError()
        assertNull(sut.toggleError.value)
    }

    @Test
    fun `a second tap on the same server is dropped while one is in flight`() =
        runTest(dispatcher) {
            val rpc = FakeMcpRpc(listOf(row("fs", false)))
            val sut = vm(rpc)
            dispatcher.scheduler.advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            rpc.gate = gate
            sut.toggleServer("fs", true)
            dispatcher.scheduler.runCurrent()
            sut.toggleServer("fs", false) // no-op while the first is parked
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf("fs" to true), rpc.toggleCalls)
            assertTrue((sut.uiState.value as McpUiState.Success).servers.single().enabled)

            gate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            sut.toggleServer("fs", false)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("fs" to true, "fs" to false), rpc.toggleCalls)
        }

    @Test
    fun `toggling an unknown server issues no request`() = runTest(dispatcher) {
        val rpc = FakeMcpRpc(listOf(row("fs", false)))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.toggleServer("not-a-server", true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(rpc.toggleCalls.isEmpty())
    }

    @Test
    fun `silent reload failure keeps the current list and any toggle error`() =
        runTest(dispatcher) {
            val rpc = FakeMcpRpc(listOf(row("fs", false))).apply { toggleFails = true }
            val sut = vm(rpc)
            dispatcher.scheduler.advanceUntilIdle()
            sut.toggleServer("fs", true)
            dispatcher.scheduler.advanceUntilIdle()

            rpc.listFails = true
            sut.loadServers(silent = true)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(sut.uiState.value is McpUiState.Success)
            assertEquals("Toggle failed: nope", sut.toggleError.value)
        }

    @Test
    fun `a loud reload clears a stale toggle error`() = runTest(dispatcher) {
        val rpc = FakeMcpRpc(listOf(row("fs", false))).apply { toggleFails = true }
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()
        sut.toggleServer("fs", true)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Toggle failed: nope", sut.toggleError.value)

        sut.loadServers()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(sut.toggleError.value)
    }
}
