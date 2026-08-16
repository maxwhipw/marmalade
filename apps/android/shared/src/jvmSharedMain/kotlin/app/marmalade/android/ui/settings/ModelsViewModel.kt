package app.marmalade.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.DaemonSettings
import app.marmalade.android.rpc.types.EFFORT_LEVELS
import app.marmalade.android.rpc.types.ModelEffortBounds
import app.marmalade.android.rpc.types.ModelListEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Possible states for the Models settings screen. */
sealed class ModelsUiState {
    data object Loading : ModelsUiState()
    data class Success(
        /** The harness catalog (`model.list`), in the daemon's own order. */
        val models: List<ModelListEntry>,
        /** The effort levels the daemon accepts, cheapest → deepest. Never
         *  empty: an older daemon that publishes none falls back to the
         *  levels this client shipped with. */
        val efforts: List<String>,
        /** The daemon's current new-session defaults. */
        val settings: DaemonSettings,
        /** False when the daemon predates settings.get/update — the screen
         *  renders read-only and says why, rather than offering a write that
         *  would 404. */
        val editable: Boolean,
        /** False when the daemon predates per-model effort bounds (2026-07-27).
         *  The screen then hides the bounds affordance entirely and every row
         *  looks exactly as it did before the feature — no dead control that
         *  writes a key the daemon would reject. */
        val boundsEditable: Boolean = false,
    ) : ModelsUiState() {
        /** The configured bounds for [modelId], or null when it's unbounded. */
        fun boundsFor(modelId: String): ModelEffortBounds? = settings.model_efforts?.get(modelId)
    }
    data class Error(val message: String) : ModelsUiState()
}

/**
 * ViewModel for the Models settings screen — the daemon's NEW-SESSION
 * defaults (model + reasoning effort).
 *
 * These are DAEMON state, not device preferences: they live in the daemon's
 * config.json, so the phone, the webui, and the CLI all agree on what a new
 * session starts with, and the choice survives a marmaladed restart. This is
 * the same server-owns-cross-client-state rule as `seen_at` and workspaces —
 * a device-local mirror would make "what model am I on?" depend on which
 * device you happened to pick up.
 *
 * Writes are NOT optimistic (unlike [SkillsViewModel]'s toggles): the daemon
 * validates and can legitimately refuse (unknown model, a key pinned by an
 * env var on the host), and it returns the post-write state. Rendering its
 * answer rather than our guess means the screen can never show a default the
 * daemon didn't accept. Concurrent taps are dropped while a write is in
 * flight ([saving]) so a slow round-trip can't land out of order.
 *
 * Plain multiplatform [ViewModel] in `:shared` — no Hilt (see [UsageViewModel]
 * for the shape and why).
 */
class ModelsViewModel(
    private val rpc: MarmaladeRpc,
    /** Whether the daemon advertised the "settings" hello feature. Supplied by
     *  the screen (the runtime owns the negotiated feature list). */
    private val settingsSupported: Boolean = true,
    /** Injectable so tests drive the fetch on the test scheduler. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ModelsUiState>(ModelsUiState.Loading)
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    /** True while a write is in flight — the screen disables its controls. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Non-null when the daemon refused a write. Cleared on the next attempt. */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    init {
        load()
    }

    /**
     * (Re)load the catalog and the daemon's defaults. [silent] = true is the
     * screen's ON_RESUME refresh: it keeps the current list (and any error)
     * on failure instead of flashing a spinner.
     */
    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _uiState.value = ModelsUiState.Loading
            try {
                val (catalog, settings) = withContext(io) {
                    val catalog = rpc.modelList()
                    // A daemon without the feature can still SHOW its defaults:
                    // model.list has carried default_model/default_effort since
                    // 2026-07-23. Only the write half is missing.
                    val settings = if (settingsSupported) {
                        rpc.settingsGet()
                    } else {
                        DaemonSettings(
                            default_model = catalog.default_model,
                            default_effort = catalog.default_effort,
                        )
                    }
                    catalog to settings
                }
                _uiState.value = ModelsUiState.Success(
                    models = catalog.models,
                    efforts = catalog.efforts.ifEmpty { EFFORT_LEVELS },
                    settings = settings,
                    editable = settingsSupported,
                    // Feature-detect rather than version-check: a daemon that
                    // knows about bounds always emits the `model_efforts` key
                    // (`{}` when nothing is bounded), so its PRESENCE — not its
                    // contents — is the signal. The model.list half is the
                    // belt-and-braces case where settings.get is unavailable
                    // but the catalog still reports bounds.
                    boundsEditable = settingsSupported && (
                        settings.model_efforts != null ||
                            catalog.models.any { it.effort_min != null || it.effort_max != null }
                        ),
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (!silent || _uiState.value !is ModelsUiState.Success) {
                    _uiState.value = ModelsUiState.Error(e.message ?: "connection failed")
                }
            }
        }
    }

    /** Set the model every NEW session starts on. No-op if it's already the
     *  default (avoids a pointless write on a re-tap of the selected row). */
    fun setDefaultModel(modelId: String) {
        val current = (_uiState.value as? ModelsUiState.Success)?.settings?.default_model
        if (modelId == current) return
        write { rpc.settingsUpdate(defaultModel = modelId) }
    }

    /** Set the reasoning effort every NEW session starts at. */
    fun setDefaultEffort(effort: String) {
        val current = (_uiState.value as? ModelsUiState.Success)?.settings?.default_effort
        if (effort == current) return
        write { rpc.settingsUpdate(defaultEffort = effort) }
    }

    /** Hand the effort choice back to the harness (a real choice — the daemon
     *  stops stamping one, and the harness picks per turn). */
    fun clearDefaultEffort() {
        if ((_uiState.value as? ModelsUiState.Success)?.settings?.default_effort == null) return
        write { rpc.settingsUpdate(clearEffort = true) }
    }

    /**
     * Set (or clear) one model's reasoning-effort bounds.
     *
     * `settings.update`'s `model_efforts` is a PER-MODEL patch, so this sends a
     * single-entry map and every other model's bounds are left alone — never a
     * read-modify-write of the whole map, which would race another client's
     * edit. Both edges null means "unbounded", expressed on the wire as an
     * explicit JSON null that DELETES the entry (an empty object is not a legal
     * bound; the daemon requires at least one edge).
     *
     * min > max is unreachable from the UI by construction (a RangeSlider
     * cannot cross its own thumbs); the daemon rejects it anyway and the
     * refusal surfaces through [saveError] like any other.
     */
    fun setModelBounds(modelId: String, min: String?, max: String?) {
        val current = (_uiState.value as? ModelsUiState.Success)?.boundsFor(modelId)
        val next = if (min == null && max == null) null else ModelEffortBounds(min = min, max = max)
        if (next == current) return
        write { rpc.settingsUpdate(modelEfforts = mapOf(modelId to next)) }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    /** True when the daemon pins [key] via an env var — the control is
     *  disabled, since a write would be refused. */
    fun isLocked(key: String): Boolean =
        (_uiState.value as? ModelsUiState.Success)?.settings?.locked?.contains(key) == true

    /** Run one settings write, adopting the daemon's returned state verbatim.
     *  Dropped when another write is already in flight. */
    private fun write(call: suspend () -> DaemonSettings) {
        if (_saving.value) return
        _saving.value = true
        _saveError.value = null
        viewModelScope.launch {
            try {
                val updated = withContext(io) { call() }
                val current = _uiState.value
                if (current is ModelsUiState.Success) {
                    _uiState.value = current.copy(settings = updated)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // The daemon refused (unknown model, env-pinned key) or the
                // socket dropped. Surface it and leave the displayed values
                // alone — they are still the daemon's last known truth.
                _saveError.value = e.message ?: "couldn't save"
            } finally {
                _saving.value = false
            }
        }
    }

    companion object {
        /** Config keys that may appear in [DaemonSettings.locked]. */
        const val KEY_MODEL = "default_model"
        const val KEY_EFFORT = "default_effort"

        fun factory(rpc: MarmaladeRpc, settingsSupported: Boolean) = viewModelFactory {
            initializer { ModelsViewModel(rpc, settingsSupported) }
        }
    }
}
