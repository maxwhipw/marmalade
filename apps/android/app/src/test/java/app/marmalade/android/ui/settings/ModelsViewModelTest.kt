package app.marmalade.android.ui.settings

import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.DaemonSettings
import app.marmalade.android.rpc.types.ModelEffortBounds
import app.marmalade.android.rpc.types.ModelListEntry
import app.marmalade.android.rpc.types.ModelListResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * [ModelsViewModel] — the Models settings screen (the daemon's new-session
 * model + thinking defaults).
 *
 * The rules that matter here are all about not lying to the user about DAEMON
 * state: writes are never optimistic (the daemon validates and can refuse), a
 * refusal leaves the displayed values alone, an env-pinned key is surfaced as
 * locked, and a daemon that can't be written to still shows its defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeModelsRpc(
        private val models: List<ModelListEntry> = listOf(
            ModelListEntry("claude-opus-5", "Opus 5", "The standard"),
            ModelListEntry("claude-haiku-4-5", "Haiku 4.5"),
        ),
        private val efforts: List<String> = listOf("low", "medium", "high", "xhigh", "max"),
        var settings: DaemonSettings = DaemonSettings(
            default_model = "claude-opus-5",
            default_effort = "high",
        ),
    ) : MarmaladeRpc(client = StubJsonRpcClient) {
        var listFails = false
        var updateFails: String? = null
        var gate: CompletableDeferred<Unit>? = null
        val updates = mutableListOf<Triple<String?, String?, Boolean>>()
        /** Every `model_efforts` patch as sent — null = the key was omitted. */
        val boundsPatches = mutableListOf<Map<String, ModelEffortBounds?>?>()

        override suspend fun modelList(): ModelListResponse {
            if (listFails) throw IllegalStateException("daemon down")
            return ModelListResponse(
                models = models,
                default_model = settings.default_model,
                default_effort = settings.default_effort,
                efforts = efforts,
            )
        }

        override suspend fun settingsGet(): DaemonSettings {
            if (listFails) throw IllegalStateException("daemon down")
            return settings
        }

        override suspend fun settingsUpdate(
            defaultModel: String?,
            defaultEffort: String?,
            clearModel: Boolean,
            clearEffort: Boolean,
            modelEfforts: Map<String, ModelEffortBounds?>?,
        ): DaemonSettings {
            updates += Triple(defaultModel, defaultEffort, clearEffort)
            boundsPatches += modelEfforts
            gate?.await()
            updateFails?.let { throw IllegalStateException(it) }
            settings = settings.copy(
                default_model = defaultModel ?: settings.default_model,
                default_effort = if (clearEffort) null else defaultEffort ?: settings.default_effort,
                // Apply the PER-MODEL patch the way the daemon does: an omitted
                // id keeps its bounds, a null value deletes, an object replaces.
                model_efforts = modelEfforts?.let { patch ->
                    val merged = (settings.model_efforts ?: emptyMap()).toMutableMap()
                    patch.forEach { (id, b) -> if (b == null) merged.remove(id) else merged[id] = b }
                    merged
                } ?: settings.model_efforts,
            )
            return settings
        }
    }

    private fun vm(rpc: MarmaladeRpc, supported: Boolean = true) =
        ModelsViewModel(rpc, settingsSupported = supported, io = dispatcher)

    private fun success(state: ModelsUiState) = state as ModelsUiState.Success

    @Test
    fun `load surfaces the catalog, the daemon vocabulary, and the current defaults`() = runTest(dispatcher) {
        val model = vm(FakeModelsRpc())
        advanceUntilIdle()
        val state = success(model.uiState.value)
        assertEquals(listOf("claude-opus-5", "claude-haiku-4-5"), state.models.map { it.id })
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), state.efforts)
        assertEquals("claude-opus-5", state.settings.default_model)
        assertEquals("high", state.settings.default_effort)
        assertTrue(state.editable)
    }

    @Test
    fun `a daemon without the settings feature still shows defaults, read-only`() = runTest(dispatcher) {
        // model.list has carried default_model/default_effort since 2026-07-23,
        // so only the WRITE half is missing — the screen must not go blank.
        val rpc = FakeModelsRpc()
        val model = vm(rpc, supported = false)
        advanceUntilIdle()
        val state = success(model.uiState.value)
        assertFalse(state.editable)
        assertEquals("claude-opus-5", state.settings.default_model)
        assertEquals("high", state.settings.default_effort)
    }

    @Test
    fun `an older daemon publishing no effort vocabulary falls back to the shipped levels`() = runTest(dispatcher) {
        val model = vm(FakeModelsRpc(efforts = emptyList()))
        advanceUntilIdle()
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            success(model.uiState.value).efforts,
        )
    }

    @Test
    fun `picking a model writes it and adopts the daemon's returned state`() = runTest(dispatcher) {
        val rpc = FakeModelsRpc()
        val model = vm(rpc)
        advanceUntilIdle()
        model.setDefaultModel("claude-haiku-4-5")
        advanceUntilIdle()
        assertEquals(listOf(Triple("claude-haiku-4-5", null, false)), rpc.updates)
        assertEquals("claude-haiku-4-5", success(model.uiState.value).settings.default_model)
    }

    @Test
    fun `re-picking the current default is a no-op, not a redundant write`() = runTest(dispatcher) {
        val rpc = FakeModelsRpc()
        val model = vm(rpc)
        advanceUntilIdle()
        model.setDefaultModel("claude-opus-5")
        model.setDefaultEffort("high")
        advanceUntilIdle()
        assertTrue(rpc.updates.isEmpty())
    }

    @Test
    fun `clearing the effort sends the explicit clear and lands on null`() = runTest(dispatcher) {
        val rpc = FakeModelsRpc()
        val model = vm(rpc)
        advanceUntilIdle()
        model.clearDefaultEffort()
        advanceUntilIdle()
        assertEquals(listOf(Triple(null, null, true)), rpc.updates)
        assertNull(success(model.uiState.value).settings.default_effort)
    }

    @Test
    fun `a refused write surfaces the daemon's message and leaves the shown value alone`() = runTest(dispatcher) {
        val rpc = FakeModelsRpc()
        rpc.updateFails = "default_model is pinned by MARMALADE_DEFAULT_MODEL"
        val model = vm(rpc)
        advanceUntilIdle()
        model.setDefaultModel("claude-haiku-4-5")
        advanceUntilIdle()
        assertEquals("default_model is pinned by MARMALADE_DEFAULT_MODEL", model.saveError.value)
        // Not optimistic: the screen still shows what the daemon actually has.
        assertEquals("claude-opus-5", success(model.uiState.value).settings.default_model)
        assertFalse(model.saving.value)
    }

    @Test
    fun `a second tap while a write is in flight is dropped`() = runTest(dispatcher) {
        val rpc = FakeModelsRpc()
        val gate = CompletableDeferred<Unit>()
        rpc.gate = gate
        val model = vm(rpc)
        advanceUntilIdle()
        model.setDefaultModel("claude-haiku-4-5")
        advanceUntilIdle()
        assertTrue(model.saving.value)
        model.setDefaultEffort("low") // arrives mid-flight
        advanceUntilIdle()
        assertEquals(1, rpc.updates.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.saving.value)
    }

    @Test
    fun `locked keys are reported so the screen can disable the control`() = runTest(dispatcher) {
        val rpc = FakeModelsRpc(
            settings = DaemonSettings(
                default_model = "claude-opus-5",
                default_effort = "high",
                locked = listOf("default_model"),
            ),
        )
        val model = vm(rpc)
        advanceUntilIdle()
        assertTrue(model.isLocked(ModelsViewModel.KEY_MODEL))
        assertFalse(model.isLocked(ModelsViewModel.KEY_EFFORT))
    }

    @Test
    fun `a failed silent reload keeps the last good state instead of blanking`() = runTest(dispatcher) {
        val rpc = FakeModelsRpc()
        val model = vm(rpc)
        advanceUntilIdle()
        rpc.listFails = true
        model.load(silent = true)
        advanceUntilIdle()
        assertTrue(model.uiState.value is ModelsUiState.Success)
    }

    @Test
    fun `a failed first load is an error state with the daemon's message`() = runTest(dispatcher) {
        val rpc = FakeModelsRpc()
        rpc.listFails = true
        val model = vm(rpc)
        advanceUntilIdle()
        assertEquals("daemon down", (model.uiState.value as ModelsUiState.Error).message)
    }

    // ── Per-model effort bounds (2026-07-27) ────────────────────────────────

    /** A daemon that knows about bounds always sends `model_efforts` — `{}`
     *  when nothing is bounded. Its PRESENCE is the feature signal. */
    private fun boundsAwareRpc(entries: Map<String, ModelEffortBounds> = emptyMap()) =
        FakeModelsRpc(
            settings = DaemonSettings(
                default_model = "claude-opus-5",
                default_effort = "high",
                model_efforts = entries,
            ),
        )

    @Test
    fun `a daemon that publishes model_efforts is bounds-editable, even when nothing is bounded`() =
        runTest(dispatcher) {
            val model = vm(boundsAwareRpc())
            advanceUntilIdle()
            val state = success(model.uiState.value)
            assertTrue(state.boundsEditable)
            assertNull(state.boundsFor("claude-opus-5"))
        }

    @Test
    fun `an older daemon omitting model_efforts degrades to no bounds UI at all`() =
        runTest(dispatcher) {
            // The pre-2026-07-27 daemon: no model_efforts key, no effort_min /
            // effort_max on any catalog row. The screen must render exactly what
            // it rendered before the feature — a dead control would write a key
            // this daemon rejects.
            val model = vm(FakeModelsRpc())
            advanceUntilIdle()
            val state = success(model.uiState.value)
            assertFalse(state.boundsEditable)
            assertNull(state.settings.model_efforts)
        }

    @Test
    fun `a daemon without the settings feature is never bounds-editable`() = runTest(dispatcher) {
        // model.list may still report bounds, but settings.update would 404 —
        // read-only means read-only.
        val rpc = FakeModelsRpc(
            models = listOf(ModelListEntry("claude-opus-5", "Opus 5", null, "high", null)),
        )
        val model = vm(rpc, supported = false)
        advanceUntilIdle()
        assertFalse(success(model.uiState.value).boundsEditable)
    }

    @Test
    fun `setting bounds sends a single-entry per-model patch`() = runTest(dispatcher) {
        // Per-model patch, not a whole-map replace: only the edited id appears,
        // so a concurrent edit to another model can't be clobbered.
        val rpc = boundsAwareRpc(mapOf("claude-haiku-4-5" to ModelEffortBounds(max = "medium")))
        val model = vm(rpc)
        advanceUntilIdle()
        model.setModelBounds("claude-opus-5", min = "high", max = null)
        advanceUntilIdle()
        assertEquals(
            listOf(mapOf("claude-opus-5" to ModelEffortBounds(min = "high"))),
            rpc.boundsPatches,
        )
        val state = success(model.uiState.value)
        assertEquals(ModelEffortBounds(min = "high"), state.boundsFor("claude-opus-5"))
        assertEquals(
            "an untouched model must keep its bounds",
            ModelEffortBounds(max = "medium"),
            state.boundsFor("claude-haiku-4-5"),
        )
    }

    @Test
    fun `clearing back to unbounded sends an explicit null that DELETES the entry`() =
        runTest(dispatcher) {
            // Not an empty object: the daemon requires at least one edge, and a
            // no-op bound would sit in config.json forever.
            val rpc = boundsAwareRpc(mapOf("claude-opus-5" to ModelEffortBounds(min = "high")))
            val model = vm(rpc)
            advanceUntilIdle()
            model.setModelBounds("claude-opus-5", min = null, max = null)
            advanceUntilIdle()
            assertEquals(listOf(mapOf("claude-opus-5" to null)), rpc.boundsPatches)
            assertNull(success(model.uiState.value).boundsFor("claude-opus-5"))
        }

    @Test
    fun `re-committing the bounds already stored is a no-op, not a redundant write`() =
        runTest(dispatcher) {
            // The slider commits on every drag END, including one that lands
            // back where it started.
            val rpc = boundsAwareRpc(mapOf("claude-opus-5" to ModelEffortBounds(min = "high")))
            val model = vm(rpc)
            advanceUntilIdle()
            model.setModelBounds("claude-opus-5", min = "high", max = null)
            model.setModelBounds("claude-haiku-4-5", min = null, max = null) // already unbounded
            advanceUntilIdle()
            assertTrue(rpc.boundsPatches.isEmpty())
        }

    @Test
    fun `a refused bounds write leaves the daemon's stored bounds on screen`() = runTest(dispatcher) {
        val rpc = boundsAwareRpc(mapOf("claude-opus-5" to ModelEffortBounds(min = "high")))
        rpc.updateFails = "unknown model"
        val model = vm(rpc)
        advanceUntilIdle()
        model.setModelBounds("claude-opus-5", min = "low", max = "medium")
        advanceUntilIdle()
        assertEquals("unknown model", model.saveError.value)
        assertEquals(
            ModelEffortBounds(min = "high"),
            success(model.uiState.value).boundsFor("claude-opus-5"),
        )
    }

    @Test
    fun `catalog rows carry the daemon's bounds through to the screen`() = runTest(dispatcher) {
        val rpc = FakeModelsRpc(
            models = listOf(
                ModelListEntry("claude-opus-5", "Opus 5", "The standard", "high", null),
                ModelListEntry("claude-haiku-4-5", "Haiku 4.5"),
            ),
        )
        val model = vm(rpc)
        advanceUntilIdle()
        val state = success(model.uiState.value)
        assertEquals("high", state.models[0].effort_min)
        assertNull(state.models[0].effort_max)
        assertNull(state.models[1].effort_min)
        // model.list reporting bounds is itself enough to light the editor.
        assertTrue(state.boundsEditable)
    }
}
