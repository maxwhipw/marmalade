package app.marmalade.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.UsageSummaryResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UsageUiState {
    data object Loading : UsageUiState()
    data class Success(val summary: UsageSummaryResponse) : UsageUiState()
    data class Error(val message: String) : UsageUiState()
}

/**
 * ViewModel for the usage screen (daemon usage.summary, T2 #8).
 * Read-only surface: the daemon owns the meter; changing the window refetches.
 *
 * First ViewModel on the multiplatform `androidx.lifecycle` artifacts
 * (`org.jetbrains.androidx.lifecycle`, ADR 0011 / desktop-client plan Phase 1),
 * hence the two shape changes from its `:app` `AndroidViewModel` ancestor:
 * plain [ViewModel] + constructor-injected [MarmaladeRpc] instead of reaching
 * through `Application` for the runtime singleton (no `android.app` in shared
 * code), and a [factory] so callers can supply it. Behaviorally identical —
 * `MarmaladeRuntime.marmaladeRpc` is a `val`, so capturing it in the
 * constructor is the same object the old `rpc get()` resolved every call.
 *
 * Lives in jvmSharedMain (not commonMain) only because [MarmaladeRpc] does —
 * it is OkHttp-coupled. Nothing here is JVM-specific beyond that.
 */
class UsageViewModel(
    private val rpc: MarmaladeRpc,
    /** Injectable so tests can drive the fetch on the test scheduler — a
     *  hardcoded [Dispatchers.IO] escapes it and races the assertions. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UsageUiState>(UsageUiState.Loading)
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

    private val _days = MutableStateFlow(DEFAULT_DAYS)
    val days: StateFlow<Int> = _days.asStateFlow()

    init {
        load()
    }

    fun setDays(days: Int) {
        if (_days.value == days) return
        _days.value = days
        load()
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _uiState.value = UsageUiState.Loading
            try {
                val summary = withContext(io) { rpc.usageSummary(_days.value) }
                _uiState.value = UsageUiState.Success(summary)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (!silent || _uiState.value !is UsageUiState.Success) {
                    _uiState.value = UsageUiState.Error(e.message ?: "connection failed")
                }
            }
        }
    }

    companion object {
        const val DEFAULT_DAYS = 7

        /** Window choices — mirrors the webui's picker. */
        val WINDOWS = listOf(7, 14, 30, 90)

        /** Factory for `viewModel(factory = UsageViewModel.factory(rpc))`. */
        fun factory(rpc: MarmaladeRpc) = viewModelFactory {
            initializer { UsageViewModel(rpc) }
        }
    }
}
