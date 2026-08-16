package app.marmalade.android.ui.settings

import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.CronDeleteResponse
import app.marmalade.android.rpc.types.CronJob
import app.marmalade.android.rpc.types.CronJobResponse
import app.marmalade.android.rpc.types.CronListResponse
import app.marmalade.android.rpc.types.CronRunNowResponse
import app.marmalade.android.rpc.types.CronSchedule
import app.marmalade.android.rpc.types.SessionListResponse
import app.marmalade.android.rpc.types.SessionListRow
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
 * [CronViewModel] after its move to `:shared` (desktop-client plan Phase 1).
 *
 * The daemon owns all schedule state, so this ViewModel's contract is
 * *refetch-after-mutation* rather than the optimistic patching Skills/MCP/
 * Plugins do. The `buildSchedule` half was already covered by
 * `CronCreateLogicTest`; these tests cover the parts that needed an
 * `Application` before and so had none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CronViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun job(id: String, enabled: Boolean = true) = CronJob(
        jobId = id,
        sessionId = "s1",
        prompt = "do the thing",
        schedule = CronSchedule(kind = "every", everyMs = 60_000L),
        enabled = enabled,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private class FakeCronRpc(
        var jobs: List<CronJob>,
        private val sessions: List<SessionListRow> = emptyList(),
    ) : MarmaladeRpc(client = StubJsonRpcClient) {
        var listFails = false
        var sessionListFails = false
        var opFails = false
        var deleteReturns = true
        var runNowFires = true
        /** Non-null parks every mutation so a test can tap again mid-flight. */
        var gate: CompletableDeferred<Unit>? = null
        var listCalls = 0
        val ops = mutableListOf<String>()

        override suspend fun cronList(): CronListResponse {
            listCalls++
            if (listFails) throw IllegalStateException("daemon down")
            return CronListResponse(jobs = jobs)
        }

        override suspend fun sessionList(limit: Int): SessionListResponse {
            if (sessionListFails) throw IllegalStateException("no sessions for you")
            return SessionListResponse(sessions = sessions)
        }

        private suspend fun op(tag: String) {
            ops += tag
            gate?.await()
            if (opFails) throw IllegalStateException("nope")
        }

        override suspend fun cronCreate(
            sessionId: String,
            prompt: String,
            schedule: CronSchedule,
            name: String?,
        ): CronJobResponse {
            op("create")
            return CronJobResponse(job = CronJob(
                jobId = "new", sessionId = sessionId, prompt = prompt, schedule = schedule,
                enabled = true, createdAt = 0L, updatedAt = 0L,
            ))
        }

        override suspend fun cronUpdate(
            jobId: String,
            enabled: Boolean?,
            name: String?,
            prompt: String?,
            schedule: CronSchedule?,
        ): CronJobResponse {
            op("update:$jobId:$enabled")
            return CronJobResponse(job = jobs.first { it.jobId == jobId })
        }

        override suspend fun cronDelete(jobId: String): CronDeleteResponse {
            op("delete:$jobId")
            return CronDeleteResponse(deleted = deleteReturns)
        }

        override suspend fun cronRunNow(jobId: String): CronRunNowResponse {
            op("runNow:$jobId")
            return CronRunNowResponse(fired = runNowFires)
        }
    }

    private fun vm(rpc: MarmaladeRpc) = CronViewModel(rpc, io = dispatcher)

    @Test
    fun `loads jobs and session targets on init`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(
            jobs = listOf(job("a"), job("b")),
            sessions = listOf(
                SessionListRow(session_id = "s1", title = "Inbox"),
                SessionListRow(session_id = "0123456789abcdef"),
            ),
        )

        val sut = vm(rpc)
        assertEquals(CronUiState.Loading, sut.uiState.value)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("a", "b"), (sut.uiState.value as CronUiState.Success).jobs.map { it.jobId })
        // Untitled sessions fall back to a truncated id (12 chars).
        assertEquals(listOf("Inbox", "0123456789ab"), sut.sessionOptions.value.map { it.label })
    }

    @Test
    fun `job list failure surfaces as Error`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(emptyList()).apply { listFails = true }

        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("daemon down", (sut.uiState.value as CronUiState.Error).message)
    }

    /**
     * Session targets are create-sheet furniture: their fetch failing must not
     * take the job list down with it.
     */
    @Test
    fun `session-target failure does not fail the job list`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a"))).apply { sessionListFails = true }

        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(sut.uiState.value is CronUiState.Success)
        assertTrue(sut.sessionOptions.value.isEmpty())
    }

    @Test
    fun `create reports success, refetches, and signals the sheet`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a")))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()
        val listsBefore = rpc.listCalls

        var done: Boolean? = null
        sut.create("s1", "p", CronSchedule(kind = "every", everyMs = 60_000L), null) { done = it }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, done)
        assertEquals("Scheduled.", sut.actionMessage.value)
        assertTrue(rpc.listCalls > listsBefore) // mutation refetches, never patches
    }

    @Test
    fun `create failure reports and tells the sheet to stay open`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a"))).apply { opFails = true }
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        var done: Boolean? = null
        sut.create("s1", "p", CronSchedule(kind = "every", everyMs = 60_000L), null) { done = it }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, done)
        assertEquals("Create failed: nope", sut.actionMessage.value)
    }

    @Test
    fun `setEnabled updates then refetches, with no snackbar of its own`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a")))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()
        val listsBefore = rpc.listCalls

        sut.setEnabled(job("a"), false)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("update:a:false"), rpc.ops)
        assertTrue(rpc.listCalls > listsBefore)
        assertNull(sut.actionMessage.value)
    }

    @Test
    fun `delete distinguishes a real delete from an already-gone job`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a")))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.delete(job("a"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Deleted.", sut.actionMessage.value)

        sut.clearActionMessage()
        rpc.deleteReturns = false
        sut.delete(job("a"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Job was already gone.", sut.actionMessage.value)
    }

    /** fired=false is a single-flight skip, not an error — the wording matters. */
    @Test
    fun `runNow distinguishes fired from a mid-run skip`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a")))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.runNow(job("a"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Fired — check the target session.", sut.actionMessage.value)

        rpc.runNowFires = false
        sut.runNow(job("a"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Job is mid-run — skipped.", sut.actionMessage.value)
    }

    @Test
    fun `a failed job op reports and clears`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a"))).apply { opFails = true }
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        sut.runNow(job("a"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Failed: nope", sut.actionMessage.value)

        sut.clearActionMessage()
        assertNull(sut.actionMessage.value)
    }

    @Test
    fun `a second op on the same job is dropped while one is in flight`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a")))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        rpc.gate = gate
        sut.runNow(job("a"))
        dispatcher.scheduler.runCurrent()
        sut.delete(job("a")) // same job id — dropped, not queued
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("runNow:a"), rpc.ops)

        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        sut.delete(job("a")) // guard released
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("runNow:a", "delete:a"), rpc.ops)
    }

    /** Different jobs must not block each other — the guard is per job id. */
    @Test
    fun `ops on different jobs run concurrently`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a"), job("b")))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        rpc.gate = gate
        sut.runNow(job("a"))
        sut.runNow(job("b"))
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("runNow:a", "runNow:b"), rpc.ops)
        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `silent reload failure keeps the current job list`() = runTest(dispatcher) {
        val rpc = FakeCronRpc(listOf(job("a")))
        val sut = vm(rpc)
        dispatcher.scheduler.advanceUntilIdle()

        rpc.listFails = true
        sut.load(silent = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(sut.uiState.value is CronUiState.Success)
    }
}
