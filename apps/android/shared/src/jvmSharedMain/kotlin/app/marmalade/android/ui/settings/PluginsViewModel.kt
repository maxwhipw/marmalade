package app.marmalade.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.PluginInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Possible states for the Plugins settings screen. */
sealed class PluginsUiState {
    data object Loading : PluginsUiState()
    data class Success(
        val plugins: List<PluginInfo>,
        val userCount: Int,
        val bundledCount: Int,
    ) : PluginsUiState()
    data class Error(val message: String) : PluginsUiState()
}

/**
 * ViewModel for the Plugins settings screen.
 *
 * JSON-RPC backed (marmaladed `plugins.list` + `plugins.toggle` since
 * fork-rest-triage Part E, 2026-07-12 — the wrappers map the daemon rows
 * onto the fork-era shapes), optimistic update on flip, revert + Snackbar
 * on failure, [pendingToggles] dedupe.
 *
 * Plain multiplatform [ViewModel] in `:shared` — no Hilt, and no
 * `AndroidViewModel`/`Application` reach-in for the runtime singleton (see
 * [UsageViewModel] for the shape and why).
 *
 * Plugin enabled state is computed from the [PluginInfo.status] string —
 * the daemon mapping emits exactly "enabled"/"disabled". A toggle takes
 * effect on the NEXT session spawn (daemon returns effective:next_session).
 */
class PluginsViewModel(
    private val rpc: MarmaladeRpc,
    /** Injectable so tests can drive the fetch on the test scheduler — a
     *  hardcoded [Dispatchers.IO] escapes it and races the assertions. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PluginsUiState>(PluginsUiState.Loading)
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    private val _toggleError = MutableStateFlow<String?>(null)
    val toggleError: StateFlow<String?> = _toggleError.asStateFlow()

    // ConcurrentHashMap-backed set: toggles resolve on IO coroutines
    // concurrently with UI reads, so a plain mutableSetOf would race.
    private val pendingToggles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        loadPlugins()
    }

    fun loadPlugins() {
        viewModelScope.launch {
            _uiState.value = PluginsUiState.Loading
            _toggleError.value = null
            try {
                val response = withContext(io) { rpc.pluginsList() }
                _uiState.value = PluginsUiState.Success(
                    plugins = response.plugins,
                    userCount = response.userCount,
                    bundledCount = response.bundledCount,
                )
            } catch (t: Throwable) {
                _uiState.value = PluginsUiState.Error(
                    t.message ?: "Failed to load plugins",
                )
            }
        }
    }

    fun togglePlugin(name: String, newEnabled: Boolean) {
        if (!pendingToggles.add(name)) return

        val previousStatus = applyToggleToState(name, newEnabled) ?: run {
            pendingToggles.remove(name)
            return
        }

        viewModelScope.launch {
            try {
                val response = withContext(io) {
                    rpc.pluginsToggle(name = name, enable = newEnabled)
                }
                // Reconcile against the authoritative row the server returned.
                // status string is the source of truth post-toggle.
                response.plugin?.let { applyServerRow(it) }
            } catch (t: Throwable) {
                applyToggleStatusToState(name, previousStatus)
                _toggleError.value = "Toggle failed: ${t.message ?: "RPC error"}"
            } finally {
                pendingToggles.remove(name)
            }
        }
    }

    fun clearToggleError() {
        _toggleError.value = null
    }

    /**
     * Apply an optimistic enable/disable flip by swapping the status string
     * to a canonical form. Returns the PREVIOUS status so callers can
     * revert on RPC failure.
     */
    private fun applyToggleToState(name: String, enabled: Boolean): String? {
        val current = _uiState.value as? PluginsUiState.Success ?: return null
        val index = current.plugins.indexOfFirst { it.name == name }
        if (index < 0) return null
        val old = current.plugins[index]
        val updated = current.plugins.toMutableList()
        updated[index] = old.copy(status = if (enabled) "enabled" else "disabled")
        _uiState.value = current.copy(plugins = updated)
        return old.status
    }

    /** Restore [name]'s status field to [status] (revert path). */
    private fun applyToggleStatusToState(name: String, status: String) {
        val current = _uiState.value as? PluginsUiState.Success ?: return
        val index = current.plugins.indexOfFirst { it.name == name }
        if (index < 0) return
        val updated = current.plugins.toMutableList()
        updated[index] = updated[index].copy(status = status)
        _uiState.value = current.copy(plugins = updated)
    }

    /** Replace the row for [row].name with the authoritative server copy. */
    private fun applyServerRow(row: PluginInfo) {
        val current = _uiState.value as? PluginsUiState.Success ?: return
        val index = current.plugins.indexOfFirst { it.name == row.name }
        if (index < 0) return
        val updated = current.plugins.toMutableList()
        updated[index] = row
        _uiState.value = current.copy(plugins = updated)
    }

    companion object {
        /** Factory for `viewModel(factory = PluginsViewModel.factory(rpc))`. */
        fun factory(rpc: MarmaladeRpc) = viewModelFactory {
            initializer { PluginsViewModel(rpc) }
        }
    }
}

/**
 * Whether a [PluginInfo.status] string represents the "on" state.
 *
 * The gateway's `_plugin_status` (marmalade_cli/plugins_cmd.py:928)
 * returns exactly `"enabled"`, `"disabled"`, or `"not enabled"` (the
 * last when the plugin is discovered on disk but absent from both
 * enabled + disabled sets — counted as off).
 *
 * `startsWith("enabled")` rather than `equals("enabled")` gives a soft
 * forward-compat: if a future server adds a qualifier like
 * `"enabled (override)"`, we don't flip the toggle to off behind the
 * user's back. `"not enabled"` is still off (doesn't start with
 * "enabled").
 *
 * Public rather than `internal` because it now lives in `:shared` — an
 * `internal` here would be invisible to `:app`'s PluginsSettingsScreen, which
 * derives every switch's state from it.
 */
fun pluginEnabled(status: String): Boolean =
    status.startsWith("enabled", ignoreCase = true)
