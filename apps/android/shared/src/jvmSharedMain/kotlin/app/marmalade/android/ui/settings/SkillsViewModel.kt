package app.marmalade.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.SkillInfo
import app.marmalade.android.rpc.types.toSkillInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Possible states for the Skills settings screen. */
sealed class SkillsUiState {
    data object Loading : SkillsUiState()
    data class Success(val skills: List<SkillInfo>) : SkillsUiState()
    data class Error(val message: String) : SkillsUiState()
}

/**
 * ViewModel for the Skills settings screen.
 *
 * Plain multiplatform [ViewModel] in `:shared` — no Hilt, and no
 * `AndroidViewModel`/`Application` reach-in for the runtime singleton (see
 * [UsageViewModel] for the shape and why).
 *
 * **Optimistic toggle:** on a user flip, the skill row updates immediately
 * in [uiState]. If the network call fails, the list is reverted and
 * [toggleError] carries the human-readable message. Because toggle calls
 * are serialised through [viewModelScope] + the [io] dispatcher, concurrent
 * toggles on different skills queue safely; two rapid
 * toggles on the SAME skill can create a state where the second request
 * wins and the revert of the first wrongly snaps the state back. The
 * deduplication guard ([pendingToggles]) prevents issuing a second request
 * for the same skill name while one is in flight — the second tap is a no-op
 * until the first request resolves.
 */
class SkillsViewModel(
    private val rpc: MarmaladeRpc,
    /** Injectable so tests can drive the fetch on the test scheduler — a
     *  hardcoded [Dispatchers.IO] escapes it and races the assertions. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SkillsUiState>(SkillsUiState.Loading)
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    /** Non-null when an optimistic toggle fails. Cleared on next load or explicit dismiss. */
    private val _toggleError = MutableStateFlow<String?>(null)
    val toggleError: StateFlow<String?> = _toggleError.asStateFlow()

    /** Names of skills currently awaiting a server response. Dedupe guard. */
    private val pendingToggles = mutableSetOf<String>()

    init {
        loadSkills()
    }

    /**
     * (Re)load the skills list. [silent] = true is used by the screen's
     * ON_RESUME auto-refresh: it skips the Loading spinner and preserves the
     * current list (and any toggle error) if the refetch fails, so returning
     * to the screen quietly picks up server-side changes without flicker.
     */
    fun loadSkills(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.value = SkillsUiState.Loading
                _toggleError.value = null
            }
            try {
                // marmaladed skills.list (JSON-RPC) — the fork REST endpoint
                // is dead against the WS-only daemon (fork-rest-triage C).
                val skills = withContext(io) {
                    rpc.skillsList().skills.map { it.toSkillInfo() }
                }
                _uiState.value = SkillsUiState.Success(skills)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (!silent || _uiState.value !is SkillsUiState.Success) {
                    _uiState.value = SkillsUiState.Error(e.message ?: "connection failed")
                }
            }
        }
    }

    /**
     * Toggle [skillName] to [newEnabled] with an optimistic UI update.
     *
     * 1. Immediately flip the in-memory list.
     * 2. Fire the network request.
     * 3. On success: no-op (state already correct).
     * 4. On failure: revert the in-memory flip, surface error via [toggleError].
     *
     * A second call for the same [skillName] while step 2 is in flight is
     * dropped silently.
     */
    fun toggleSkill(skillName: String, newEnabled: Boolean) {
        // Dedupe: skip if a request for this skill is already in flight
        if (!pendingToggles.add(skillName)) return

        // Optimistic update
        val previous = applyToggleToState(skillName, newEnabled) ?: run {
            pendingToggles.remove(skillName)
            return
        }

        viewModelScope.launch {
            try {
                withContext(io) { rpc.skillsToggle(skillName, newEnabled) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // Revert on failure
                applyToggleToState(skillName, previous.enabled)
                _toggleError.value = "Toggle failed: ${e.message ?: "network error"}"
            } finally {
                pendingToggles.remove(skillName)
            }
        }
    }

    fun clearToggleError() {
        _toggleError.value = null
    }

    /**
     * Mutate the current [SkillsUiState.Success] list in-place, returning the
     * OLD [SkillInfo] entry so callers can revert. Returns null if state is
     * not [SkillsUiState.Success] or the skill is not in the list.
     */
    private fun applyToggleToState(name: String, enabled: Boolean): SkillInfo? {
        val current = _uiState.value as? SkillsUiState.Success ?: return null
        val index = current.skills.indexOfFirst { it.name == name }
        if (index < 0) return null
        val old = current.skills[index]
        val updated = current.skills.toMutableList()
        updated[index] = old.copy(enabled = enabled)
        _uiState.value = SkillsUiState.Success(updated)
        return old
    }

    companion object {
        /** Factory for `viewModel(factory = SkillsViewModel.factory(rpc))`. */
        fun factory(rpc: MarmaladeRpc) = viewModelFactory {
            initializer { SkillsViewModel(rpc) }
        }
    }
}
