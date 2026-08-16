package app.marmalade.android.ui.settings

import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.UsageSummaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [UsageViewModel] is the first ViewModel living in `:shared` on the
 * multiplatform `androidx.lifecycle` artifacts (desktop-client plan Phase 1).
 * Dropping `AndroidViewModel` is what makes these tests possible at all — the
 * VM is now directly constructible with a fake RPC, no Robolectric and no
 * `Application`. That it runs here is also the load-bearing evidence that
 * `viewModelScope` works off-Android, which every later shared ViewModel and
 * the desktop client depend on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** Records every requested window and replays a scripted result. */
    private class RecordingRpc(
        private val result: Result<UsageSummaryResponse>,
    ) : MarmaladeRpc(client = StubJsonRpcClient) {
        val requestedDays = mutableListOf<Int>()

        override suspend fun usageSummary(days: Int): UsageSummaryResponse {
            requestedDays += days
            return result.getOrThrow()
        }
    }

    private fun summary(today: String) = UsageSummaryResponse(today = today, entries = emptyList())

    @Test
    fun `loads on init using the default window`() = runTest(dispatcher) {
        val rpc = RecordingRpc(Result.success(summary("2026-07-24")))

        val vm = UsageViewModel(rpc, io = dispatcher)
        // init{} launched into viewModelScope — nothing has run yet.
        assertEquals(UsageUiState.Loading, vm.uiState.value)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(UsageViewModel.DEFAULT_DAYS), rpc.requestedDays)
        assertEquals("2026-07-24", (vm.uiState.value as UsageUiState.Success).summary.today)
    }

    @Test
    fun `setDays refetches for the new window and ignores a repeat of the current one`() =
        runTest(dispatcher) {
            val rpc = RecordingRpc(Result.success(summary("2026-07-24")))
            val vm = UsageViewModel(rpc, io = dispatcher)
            dispatcher.scheduler.advanceUntilIdle()

            vm.setDays(30)
            dispatcher.scheduler.advanceUntilIdle()
            vm.setDays(30) // same window — must not hit the daemon again
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(30, vm.days.value)
            assertEquals(listOf(UsageViewModel.DEFAULT_DAYS, 30), rpc.requestedDays)
        }

    @Test
    fun `failure surfaces as Error`() = runTest(dispatcher) {
        val rpc = RecordingRpc(Result.failure(IllegalStateException("daemon down")))

        val vm = UsageViewModel(rpc, io = dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("daemon down", (vm.uiState.value as UsageUiState.Error).message)
    }

    /**
     * The ON_RESUME refresh: a silent reload that fails must leave the last
     * good summary on screen rather than blanking it to an error.
     */
    @Test
    fun `silent reload failure keeps the previous Success`() = runTest(dispatcher) {
        var fail = false
        val rpc = object : MarmaladeRpc(client = StubJsonRpcClient) {
            override suspend fun usageSummary(days: Int): UsageSummaryResponse =
                if (fail) throw IllegalStateException("daemon down") else summary("2026-07-24")
        }
        val vm = UsageViewModel(rpc, io = dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        fail = true
        vm.load(silent = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value is UsageUiState.Success)
    }

    /** A silent reload that fails from Loading (never had a summary) does surface. */
    @Test
    fun `silent reload failure from Loading surfaces the Error`() = runTest(dispatcher) {
        val rpc = RecordingRpc(Result.failure(IllegalStateException("daemon down")))
        val vm = UsageViewModel(rpc, io = dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        vm.load(silent = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("daemon down", (vm.uiState.value as UsageUiState.Error).message)
    }
}
