package app.marmalade.android.ui.settings

import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.PluginInfo
import app.marmalade.android.rpc.types.PluginsListResponse
import app.marmalade.android.rpc.types.PluginsToggleResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PluginsViewModel] after its move to `:shared` (desktop-client plan Phase 1).
 *
 * Plugins differ from Skills/MCP in two ways worth their own tests: enabled
 * state is a *status string* rather than a boolean, and a successful toggle
 * **reconciles against the authoritative row the server returns** instead of
 * trusting the optimistic flip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun plugin(name: String, status: String) = PluginInfo(name = name, status = status)

    private class FakePluginsRpc(
        private val plugins: List<PluginInfo>,
        private val userCount: Int = 0,
        private val bundledCount: Int = 0,
    ) : MarmaladeRpc(client = StubJsonRpcClient) {
        var listFails = false
        var toggleFails = false
        /** Row the server reports back post-toggle; null = server sent none. */
        var toggleResultRow: PluginInfo? = null
        /** Non-null parks the toggle so a test can tap again mid-flight. */
        var gate: CompletableDeferred<Unit>? = null
        val toggleCalls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun pluginsList(): PluginsListResponse {
            if (listFails) throw IllegalStateException("daemon down")
            return PluginsListResponse(
                plugins = plugins,
                userCount = userCount,
                bundledCount = bundledCount,
            )
        }

        override suspend fun pluginsToggle(name: String, enable: Boolean): PluginsToggleResponse {
            toggleCalls += name to enable
            gate?.await()
            if (toggleFails) throw IllegalStateException("nope")
            return PluginsToggleResponse(ok = true, name = name, plugin = toggleResultRow)
        }
    }

    private fun vm(rpc: MarmaladeRpc) = PluginsViewModel(rpc, io = dispatcher)

    private fun statusOf(sut: PluginsViewModel, name: String) =
        (sut.uiState.value as PluginsUiState.Success).plugins.single { it.name == name }.status

    @Test
    fun `loads plugins with the user and bundled counts`() = runTest(dispatcher) {
        val rpc = FakePluginsRpc(
            plugins = listOf(plugin("a", "enabled"), plugin("b", "disabled")),
            userCount = 1,
            bundledCount = 1,
        )

        val sut = vm(rpc)
        assertEquals(PluginsUiState.Loading, sut.uiState.value)
        dispatcher.scheduler.advanceUntilIdle()

        val state = sut.uiState.value as PluginsUiState.Success
        assertEquals(listOf("a", "b"), state.plugins.map { it.name })
        assertEquals(1, state.userCount)
        assertEquals(1, state.bundledCount)
    }

    @Test
    fun `load failure surfaces as Error`() = runTest(dispatcher) {
        val rpc = FakePluginsRpc(emptyList()).apply { listFails = true }

        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("daemon down", (sut.uiState.value as PluginsUiState.Error).message)
    }

    @Test
    fun `toggle flips the status string optimistically`() = runTest(dispatcher) {
        val rpc = FakePluginsRpc(listOf(plugin("a", "disabled")))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.togglePlugin("a", true)
        // Optimistic flip lands before the request is dispatched.
        assertEquals("enabled", statusOf(sut, "a"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("a" to true), rpc.toggleCalls)
        assertTrue(pluginEnabled(statusOf(sut, "a")))
        assertNull(sut.toggleError.value)
    }

    /**
     * The server row wins over the optimistic guess: the daemon's toggle takes
     * effect next session, so it can answer with a status the client didn't
     * predict, and that answer must be what the UI shows.
     */
    @Test
    fun `a successful toggle reconciles against the row the server returned`() =
        runTest(dispatcher) {
            val rpc = FakePluginsRpc(listOf(plugin("a", "disabled"))).apply {
                toggleResultRow = PluginInfo(name = "a", status = "enabled (next session)")
            }
            val sut = vm(rpc)
            dispatcher.scheduler.advanceUntilIdle()

            sut.togglePlugin("a", true)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("enabled (next session)", statusOf(sut, "a"))
            // Still reads as on — the startsWith("enabled") forward-compat rule.
            assertTrue(pluginEnabled(statusOf(sut, "a")))
        }

    @Test
    fun `failed toggle restores the previous status and reports the error`() =
        runTest(dispatcher) {
            val rpc = FakePluginsRpc(listOf(plugin("a", "not enabled"))).apply {
                toggleFails = true
            }
            val sut = vm(rpc)
            dispatcher.scheduler.advanceUntilIdle()

            sut.togglePlugin("a", true)
            dispatcher.scheduler.advanceUntilIdle()

            // The exact original string comes back, not a canonicalised "disabled".
            assertEquals("not enabled", statusOf(sut, "a"))
            assertEquals("Toggle failed: nope", sut.toggleError.value)

            sut.clearToggleError()
            assertNull(sut.toggleError.value)
        }

    @Test
    fun `a second tap on the same plugin is dropped while one is in flight`() =
        runTest(dispatcher) {
            val rpc = FakePluginsRpc(listOf(plugin("a", "disabled")))
            val sut = vm(rpc)
            dispatcher.scheduler.advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            rpc.gate = gate
            sut.togglePlugin("a", true)
            dispatcher.scheduler.runCurrent()
            sut.togglePlugin("a", false) // no-op while the first is parked
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf("a" to true), rpc.toggleCalls)
            assertEquals("enabled", statusOf(sut, "a"))

            gate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            sut.togglePlugin("a", false)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("a" to true, "a" to false), rpc.toggleCalls)
        }

    @Test
    fun `toggling an unknown plugin issues no request`() = runTest(dispatcher) {
        val rpc = FakePluginsRpc(listOf(plugin("a", "disabled")))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.togglePlugin("not-installed", true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(rpc.toggleCalls.isEmpty())
    }
}
