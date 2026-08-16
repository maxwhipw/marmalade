package app.marmalade.android.ui.settings

import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.DaemonSkillRow
import app.marmalade.android.rpc.types.SkillsListResponse
import app.marmalade.android.rpc.types.SkillsToggleResponse
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
 * [SkillsViewModel] after its move to `:shared` on the multiplatform lifecycle
 * artifacts (desktop-client plan Phase 1). The optimistic-toggle rules —
 * flip-then-revert, the in-flight dedupe guard, and silent-reload behaviour —
 * were previously untested because the ViewModel needed an `Application`;
 * dropping `AndroidViewModel` is what makes them reachable here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun row(name: String, enabled: Boolean) =
        DaemonSkillRow(name = name, enabled = enabled)

    /**
     * Scriptable skills RPC. Set [gate] to hold the toggle call open so a test
     * can fire a second tap while the first is genuinely still in flight —
     * awaiting a never-completed Deferred parks the coroutine instead of
     * re-queueing it, which a `yield()` loop would do (that spins the test
     * scheduler forever).
     */
    private class FakeSkillsRpc(
        private val skills: List<DaemonSkillRow>,
    ) : MarmaladeRpc(client = StubJsonRpcClient) {
        var toggleFails = false
        var listFails = false
        var gate: CompletableDeferred<Unit>? = null
        val toggleCalls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun skillsList(): SkillsListResponse {
            if (listFails) throw IllegalStateException("daemon down")
            return SkillsListResponse(skills = skills)
        }

        override suspend fun skillsToggle(name: String, enabled: Boolean): SkillsToggleResponse {
            toggleCalls += name to enabled
            gate?.await()
            if (toggleFails) throw IllegalStateException("nope")
            return SkillsToggleResponse(applied = true)
        }
    }

    private fun vm(rpc: MarmaladeRpc) = SkillsViewModel(rpc, io = dispatcher)

    @Test
    fun `loads the skill list on init`() = runTest(dispatcher) {
        val rpc = FakeSkillsRpc(listOf(row("git", true), row("docs", false)))

        val sut = vm(rpc)
        assertEquals(SkillsUiState.Loading, sut.uiState.value)
        dispatcher.scheduler.advanceUntilIdle()

        val skills = (sut.uiState.value as SkillsUiState.Success).skills
        assertEquals(listOf("git", "docs"), skills.map { it.name })
        assertEquals(listOf(true, false), skills.map { it.enabled })
    }

    @Test
    fun `load failure surfaces as Error`() = runTest(dispatcher) {
        val rpc = FakeSkillsRpc(emptyList()).apply { listFails = true }

        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("daemon down", (sut.uiState.value as SkillsUiState.Error).message)
    }

    @Test
    fun `successful toggle flips the row and leaves no error`() = runTest(dispatcher) {
        val rpc = FakeSkillsRpc(listOf(row("git", false)))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.toggleSkill("git", true)
        // Optimistic: the row flips before the request is even dispatched.
        assertTrue((sut.uiState.value as SkillsUiState.Success).skills.single().enabled)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("git" to true), rpc.toggleCalls)
        assertTrue((sut.uiState.value as SkillsUiState.Success).skills.single().enabled)
        assertNull(sut.toggleError.value)
    }

    @Test
    fun `failed toggle reverts the row and reports the error`() = runTest(dispatcher) {
        val rpc = FakeSkillsRpc(listOf(row("git", false))).apply { toggleFails = true }
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.toggleSkill("git", true)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse((sut.uiState.value as SkillsUiState.Success).skills.single().enabled)
        assertEquals("Toggle failed: nope", sut.toggleError.value)

        sut.clearToggleError()
        assertNull(sut.toggleError.value)
    }

    @Test
    fun `a second tap on the same skill is dropped while one is in flight`() =
        runTest(dispatcher) {
            val rpc = FakeSkillsRpc(listOf(row("git", false)))
            val sut = vm(rpc)
            dispatcher.scheduler.advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            rpc.gate = gate
            sut.toggleSkill("git", true)
            dispatcher.scheduler.runCurrent() // request issued, now parked on the gate
            sut.toggleSkill("git", false) // must be a no-op, not a second request
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf("git" to true), rpc.toggleCalls)
            // The dropped tap must not have touched the optimistic state either.
            assertTrue((sut.uiState.value as SkillsUiState.Success).skills.single().enabled)

            // Release it so the guard clears and a later tap works again.
            gate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            sut.toggleSkill("git", false)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("git" to true, "git" to false), rpc.toggleCalls)
        }

    @Test
    fun `toggling an unknown skill issues no request`() = runTest(dispatcher) {
        val rpc = FakeSkillsRpc(listOf(row("git", false)))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.toggleSkill("nope-not-here", true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(rpc.toggleCalls.isEmpty())
    }

    /** ON_RESUME refresh: a failing silent reload keeps the list on screen. */
    @Test
    fun `silent reload failure keeps the current list and any toggle error`() =
        runTest(dispatcher) {
            val rpc = FakeSkillsRpc(listOf(row("git", false))).apply { toggleFails = true }
            val sut = vm(rpc)
            dispatcher.scheduler.advanceUntilIdle()
            sut.toggleSkill("git", true)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("Toggle failed: nope", sut.toggleError.value)

            rpc.listFails = true
            sut.loadSkills(silent = true)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(sut.uiState.value is SkillsUiState.Success)
            // Silent reload must not clear the toggle error either.
            assertEquals("Toggle failed: nope", sut.toggleError.value)
        }

    @Test
    fun `a loud reload clears a stale toggle error`() = runTest(dispatcher) {
        val rpc = FakeSkillsRpc(listOf(row("git", false))).apply { toggleFails = true }
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()
        sut.toggleSkill("git", true)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Toggle failed: nope", sut.toggleError.value)

        sut.loadSkills()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(sut.toggleError.value)
    }
}
