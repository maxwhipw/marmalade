package app.marmalade.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.McpServerInfo
import app.marmalade.android.rpc.types.toMcpServerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Possible states for the MCP servers settings screen. */
sealed class McpUiState {
    data object Loading : McpUiState()
    data class Success(val servers: List<McpServerInfo>) : McpUiState()
    data class Error(val message: String) : McpUiState()
}

/**
 * ViewModel for the MCP servers settings screen.
 *
 * Plain multiplatform [ViewModel] in `:shared` — no Hilt, and no
 * `AndroidViewModel`/`Application` reach-in for the runtime singleton (see
 * [UsageViewModel] for the shape and why).
 *
 * **Optimistic toggle:** on a user flip, the server row updates immediately
 * in [uiState]. If the network call fails, the list is reverted and
 * [toggleError] carries the human-readable message. The [pendingToggles]
 * deduplication guard drops a second tap on the same server while one
 * request is already in flight.
 */
class McpViewModel(
    private val rpc: MarmaladeRpc,
    /** Injectable so tests can drive the fetch on the test scheduler — a
     *  hardcoded [Dispatchers.IO] escapes it and races the assertions. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<McpUiState>(McpUiState.Loading)
    val uiState: StateFlow<McpUiState> = _uiState.asStateFlow()

    /** Non-null when an optimistic toggle fails. Cleared on next load or explicit dismiss. */
    private val _toggleError = MutableStateFlow<String?>(null)
    val toggleError: StateFlow<String?> = _toggleError.asStateFlow()

    /** Names of servers currently awaiting a server response. Dedupe guard. */
    private val pendingToggles = mutableSetOf<String>()

    init {
        loadServers()
    }

    /**
     * (Re)load the MCP server list. [silent] = true is used by the screen's
     * ON_RESUME auto-refresh: it skips the Loading spinner and preserves the
     * current list (and any toggle error) if the refetch fails, so returning
     * to the screen quietly picks up server-side changes without flicker.
     */
    fun loadServers(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.value = McpUiState.Loading
                _toggleError.value = null
            }
            try {
                // marmaladed mcp.list (JSON-RPC) — the fork REST endpoint is
                // dead against the WS-only daemon (fork-rest-triage E).
                val servers = withContext(io) {
                    rpc.mcpList().servers.map { it.toMcpServerInfo() }
                }
                _uiState.value = McpUiState.Success(servers)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (!silent || _uiState.value !is McpUiState.Success) {
                    _uiState.value = McpUiState.Error(e.message ?: "connection failed")
                }
            }
        }
    }

    /**
     * Toggle [serverName] to [newEnabled] with an optimistic UI update.
     *
     * 1. Immediately flip the in-memory list.
     * 2. Fire the network request.
     * 3. On success: no-op (state already correct).
     * 4. On failure: revert the in-memory flip, surface error via [toggleError].
     *
     * A second call for the same [serverName] while step 2 is in flight is
     * dropped silently.
     */
    fun toggleServer(serverName: String, newEnabled: Boolean) {
        if (!pendingToggles.add(serverName)) return

        val previous = applyToggleToState(serverName, newEnabled) ?: run {
            pendingToggles.remove(serverName)
            return
        }

        viewModelScope.launch {
            try {
                withContext(io) { rpc.mcpToggle(serverName, newEnabled) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                applyToggleToState(serverName, previous.enabled)
                _toggleError.value = "Toggle failed: ${e.message ?: "network error"}"
            } finally {
                pendingToggles.remove(serverName)
            }
        }
    }

    fun clearToggleError() {
        _toggleError.value = null
    }

    /**
     * Mutate the current [McpUiState.Success] list in-place, returning the
     * OLD [McpServerInfo] entry so callers can revert. Returns null if state is
     * not [McpUiState.Success] or the server is not in the list.
     */
    private fun applyToggleToState(name: String, enabled: Boolean): McpServerInfo? {
        val current = _uiState.value as? McpUiState.Success ?: return null
        val index = current.servers.indexOfFirst { it.name == name }
        if (index < 0) return null
        val old = current.servers[index]
        val updated = current.servers.toMutableList()
        updated[index] = old.copy(enabled = enabled)
        _uiState.value = McpUiState.Success(updated)
        return old
    }

    companion object {
        /** Factory for `viewModel(factory = McpViewModel.factory(rpc))`. */
        fun factory(rpc: MarmaladeRpc) = viewModelFactory {
            initializer { McpViewModel(rpc) }
        }
    }
}
