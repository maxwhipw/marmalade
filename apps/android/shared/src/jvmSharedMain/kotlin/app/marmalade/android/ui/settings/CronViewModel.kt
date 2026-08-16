package app.marmalade.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.CronJob
import app.marmalade.android.rpc.types.CronSchedule
import app.marmalade.android.utils.CronFormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class CronUiState {
    data object Loading : CronUiState()
    data class Success(val jobs: List<CronJob>) : CronUiState()
    data class Error(val message: String) : CronUiState()
}

/** Session choices for the create sheet's target picker. */
data class CronSessionOption(val sessionId: String, val label: String)

/**
 * ViewModel for the scheduled-prompts settings screen (daemon cron.*).
 *
 * Plain multiplatform [ViewModel] in `:shared` — no Hilt, and no
 * `AndroidViewModel`/`Application` reach-in for the runtime singleton (see
 * [UsageViewModel] for the shape and why).
 * The daemon owns all schedule state and semantics (one-shots self-disable,
 * run_now doesn't move next_run_at, disabled jobs stay listed with their
 * reason) — every mutation refetches the list rather than patching locally.
 */
class CronViewModel(
    private val rpc: MarmaladeRpc,
    /** Injectable so tests can drive the fetches on the test scheduler — a
     *  hardcoded [Dispatchers.IO] escapes it and races the assertions. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CronUiState>(CronUiState.Loading)
    val uiState: StateFlow<CronUiState> = _uiState.asStateFlow()

    /** One-shot action feedback (snackbar). Cleared by the screen. */
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    /** Targets for the create sheet, most recently active first. */
    private val _sessionOptions = MutableStateFlow<List<CronSessionOption>>(emptyList())
    val sessionOptions: StateFlow<List<CronSessionOption>> = _sessionOptions.asStateFlow()

    /** Job ids with a mutation in flight — dedupe guard for rapid taps. */
    private val pendingOps = mutableSetOf<String>()

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _uiState.value = CronUiState.Loading
            try {
                val jobs = withContext(io) { rpc.cronList().jobs }
                _uiState.value = CronUiState.Success(jobs)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (!silent || _uiState.value !is CronUiState.Success) {
                    _uiState.value = CronUiState.Error(e.message ?: "connection failed")
                }
            }
            // Session targets are create-sheet furniture; a failure here must
            // not fail the list (options just stay stale/empty).
            try {
                val sessions = withContext(io) { rpc.sessionList(limit = 40).sessions }
                _sessionOptions.value = sessions.map {
                    CronSessionOption(it.session_id, it.title ?: it.session_id.take(12))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
            }
        }
    }

    fun create(sessionId: String, prompt: String, schedule: CronSchedule, name: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(io) { rpc.cronCreate(sessionId, prompt, schedule, name) }
                _actionMessage.value = "Scheduled."
                load(silent = true)
                onDone(true)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _actionMessage.value = "Create failed: ${e.message ?: "network error"}"
                onDone(false)
            }
        }
    }

    fun setEnabled(job: CronJob, enabled: Boolean) = jobOp(job.jobId) {
        rpc.cronUpdate(job.jobId, enabled = enabled)
        null
    }

    fun delete(job: CronJob) = jobOp(job.jobId) {
        if (rpc.cronDelete(job.jobId).deleted) "Deleted." else "Job was already gone."
    }

    fun runNow(job: CronJob) = jobOp(job.jobId) {
        if (rpc.cronRunNow(job.jobId).fired) "Fired — check the target session."
        else "Job is mid-run — skipped."
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    private fun jobOp(jobId: String, op: suspend () -> String?) {
        if (!pendingOps.add(jobId)) return
        viewModelScope.launch {
            try {
                val msg = withContext(io) { op() }
                if (msg != null) _actionMessage.value = msg
                load(silent = true)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _actionMessage.value = "Failed: ${e.message ?: "network error"}"
            } finally {
                pendingOps.remove(jobId)
            }
        }
    }

    companion object {
        /** Factory for `viewModel(factory = CronViewModel.factory(rpc))`. */
        fun factory(rpc: MarmaladeRpc) = viewModelFactory {
            initializer { CronViewModel(rpc) }
        }

        /**
         * Build the wire schedule from the create sheet's fields, or return a
         * human error. Pure — unit-tested without Android. Mirrors the webui's
         * buildSchedule: "every" anchors at now (same as the CLI).
         */
        fun buildSchedule(
            kind: String,
            expr: String,
            tz: String,
            every: String,
            atMs: Long?,
            nowMs: Long,
        ): Result<CronSchedule> = when (kind) {
            "cron" -> {
                if (expr.isBlank()) Result.failure(IllegalArgumentException("a cron expression is required"))
                else Result.success(CronSchedule(kind = "cron", expr = expr.trim(), tz = tz.trim().ifEmpty { null }))
            }
            "every" -> {
                val ms = CronFormatUtils.parseDuration(every)
                if (ms == null) Result.failure(IllegalArgumentException("invalid interval — use e.g. 30s, 15m, 2h, 1d"))
                else Result.success(CronSchedule(kind = "every", everyMs = ms, anchorMs = nowMs))
            }
            "at" -> when {
                atMs == null -> Result.failure(IllegalArgumentException("pick a date and time"))
                atMs <= nowMs -> Result.failure(IllegalArgumentException("the one-shot time is in the past"))
                else -> Result.success(CronSchedule(kind = "at", atMs = atMs))
            }
            else -> Result.failure(IllegalArgumentException("unknown schedule kind $kind"))
        }
    }
}
