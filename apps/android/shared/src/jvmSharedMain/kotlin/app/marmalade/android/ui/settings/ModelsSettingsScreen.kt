package app.marmalade.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.android.rpc.types.ModelEffortBounds
import app.marmalade.android.rpc.types.ModelListEntry
import app.marmalade.android.ui.allowedEfforts
import app.marmalade.android.ui.effortLabel
import app.marmalade.android.ui.rememberMarmaladeRpc
import kotlin.math.roundToInt

/**
 * Models settings — the model and thinking level every NEW session starts on.
 *
 * These are DAEMON settings, not phone settings: the daemon stores them in its
 * config.json, so this phone, the webui, and the CLI agree, and the choice
 * survives a marmaladed restart. An open session keeps whatever it was created
 * with (its model is chosen once and re-applied on every resume) — change a
 * live session from the chat composer's model chip instead.
 *
 * Both lists come from the daemon: the models from `model.list`, the thinking
 * levels from its published `efforts` vocabulary. Nothing here is hardcoded,
 * so a daemon that adds a model or a level lights up with no app release.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsSettingsScreen(
    onBack: () -> Unit,
    /** Whether the daemon advertises the "settings" feature. False = the
     *  screen shows the current defaults but can't change them. */
    settingsSupported: Boolean = true,
    viewModel: ModelsViewModel = viewModel(
        factory = ModelsViewModel.factory(rememberMarmaladeRpc(), settingsSupported),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()

    // Another client (webui, CLI) may have changed the defaults meanwhile —
    // silent so returning to the screen doesn't flash a spinner.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.load(silent = true)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(saveError) {
        val err = saveError
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearSaveError()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
    ) { paddingValues ->
        when (val state = uiState) {
            is ModelsUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is ModelsUiState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SettingsErrorState(
                    headline = "Can't load models",
                    rawError = state.message,
                    onRetry = { viewModel.load() },
                )
            }

            is ModelsUiState.Success -> ModelsContent(
                state = state,
                paddingValues = paddingValues,
                busy = saving,
                onPickModel = viewModel::setDefaultModel,
                onPickEffort = viewModel::setDefaultEffort,
                onClearEffort = viewModel::clearDefaultEffort,
                onSetBounds = viewModel::setModelBounds,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsContent(
    state: ModelsUiState.Success,
    paddingValues: PaddingValues,
    busy: Boolean,
    onPickModel: (String) -> Unit,
    onPickEffort: (String) -> Unit,
    onClearEffort: () -> Unit,
    onSetBounds: (modelId: String, min: String?, max: String?) -> Unit,
) {
    val modelLocked = state.settings.locked.contains(ModelsViewModel.KEY_MODEL)
    val effortLocked = state.settings.locked.contains(ModelsViewModel.KEY_EFFORT)
    // One bounds panel open at a time — this is a settings list, not a
    // dashboard; several sliders on screen invites mis-drags.
    var boundsOpenFor by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(paddingValues),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SectionHeader(
                title = "Default model",
                subtitle = "What every new session starts on. Stored on the daemon, " +
                    "so all your devices agree.",
            )
            if (!state.editable) {
                Notice("This daemon can't be edited from a client yet — update marmaladed.")
            } else if (modelLocked) {
                Notice("Pinned by MARMALADE_DEFAULT_MODEL on the daemon host.")
            }
        }

        if (state.models.isEmpty()) {
            item {
                Notice("This harness offers no model choice.")
            }
        } else {
            items(state.models, key = { it.id }) { model ->
                val bounds = state.boundsFor(model.id)
                ModelRow(
                    model = model,
                    selected = model.id == state.settings.default_model,
                    enabled = state.editable && !modelLocked && !busy,
                    onClick = { onPickModel(model.id) },
                    // Null hides the affordance entirely, so a daemon without
                    // bounds renders the row this screen has always rendered.
                    boundsSummary = if (!state.boundsEditable) null else {
                        boundsSummary(state.efforts, bounds)
                    },
                    boundsExpanded = boundsOpenFor == model.id,
                    onToggleBounds = {
                        boundsOpenFor = if (boundsOpenFor == model.id) null else model.id
                    },
                )
                if (state.boundsEditable && boundsOpenFor == model.id) {
                    ThinkingBoundsPanel(
                        levels = state.efforts,
                        bounds = bounds,
                        enabled = !busy,
                        busy = busy,
                        onChange = { min, max -> onSetBounds(model.id, min, max) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        // A default the catalog doesn't carry is still the live answer — say so
        // rather than showing a list with nothing selected.
        val orphan = state.settings.default_model
            ?.takeIf { id -> state.models.none { it.id == id } }
        if (orphan != null) {
            item { Notice("Current default \"$orphan\" isn't in this harness's catalog.") }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            SectionHeader(
                title = "Default thinking",
                subtitle = "How hard a new session reasons before answering. " +
                    "Higher costs more and takes longer.",
            )
            if (effortLocked) {
                Notice("Pinned by MARMALADE_DEFAULT_EFFORT on the daemon host.")
            }
            EffortChips(
                levels = state.efforts,
                selected = state.settings.default_effort,
                enabled = state.editable && !effortLocked && !busy,
                onPick = onPickEffort,
                onClear = onClearEffort,
            )
        }
    }
}

/** Effort chips + the explicit "Harness default" choice. Leaving the effort
 *  unset is a real decision (the harness picks per turn), so it gets its own
 *  chip instead of being represented by nothing being selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffortChips(
    levels: List<String>,
    selected: String?,
    enabled: Boolean,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        levels.forEach { level ->
            FilterChip(
                selected = selected == level,
                enabled = enabled,
                onClick = { onPick(level) },
                label = { Text(effortLabel(level)) },
            )
        }
        FilterChip(
            selected = selected == null,
            enabled = enabled,
            onClick = onClear,
            label = { Text("Harness default") },
        )
    }
}

@Composable
private fun ModelRow(
    model: ModelListEntry,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Non-null only when the daemon supports per-model effort bounds — see
     *  [ModelsUiState.Success.boundsEditable]. A blank string means "this model
     *  is unbounded": the affordance shows, the summary line doesn't, so an
     *  unbounded row keeps the two-line shape it has always had. */
    boundsSummary: String? = null,
    boundsExpanded: Boolean = false,
    onToggleBounds: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // The daemon's blurb when it has one; the raw id otherwise —
                // the id is what actually rides session.create, so it's never
                // noise.
                text = model.description ?: model.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!boundsSummary.isNullOrEmpty()) {
                Text(
                    text = boundsSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Default",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (boundsSummary != null) {
            IconButton(onClick = onToggleBounds) {
                Icon(
                    imageVector = if (boundsExpanded) Icons.Filled.ExpandLess else Icons.Filled.Tune,
                    contentDescription = "Thinking bounds for ${model.label}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The collapsed row's one-line bounds caption. Empty for an unbounded model —
 *  the row then reads exactly as it did before bounds existed. */
private fun boundsSummary(levels: List<String>, bounds: ModelEffortBounds?): String {
    if (bounds == null) return ""
    val allowed = allowedEfforts(levels, bounds.min, bounds.max)
    if (allowed.isEmpty() || allowed.size == levels.size) return ""
    return if (allowed.size == 1) {
        "Thinking: ${effortLabel(allowed.first())} only"
    } else {
        "Thinking: ${effortLabel(allowed.first())} – ${effortLabel(allowed.last())}"
    }
}

/**
 * The per-model "Thinking bounds" editor: a dual-notch [RangeSlider] whose
 * notches ARE the daemon's published effort vocabulary, so the control can only
 * ever express a bound the daemon accepts. min > max is undrawable by
 * construction (a RangeSlider's thumbs can't cross), which is why there is no
 * validation here — the one invalid state the daemon rejects can't be reached.
 *
 * Spanning the full range means "unbounded", and committing that sends a JSON
 * null that DELETES the model's entry rather than persisting a no-op bound
 * (`{min: low, max: max}`) that would clutter the daemon's config forever. For
 * the same reason a partial range omits an edge that sits at a vocabulary
 * extreme: a config only ever records the bounds that actually bite.
 *
 * Writes are not optimistic (the ModelsViewModel contract): the commit fires on
 * drag END, and the thumbs re-seat from the daemon's returned snapshot when the
 * write settles — so a refusal snaps them back to the truth instead of leaving
 * the slider claiming a bound the daemon never took.
 */
@Composable
private fun ThinkingBoundsPanel(
    levels: List<String>,
    bounds: ModelEffortBounds?,
    enabled: Boolean,
    busy: Boolean,
    onChange: (min: String?, max: String?) -> Unit,
) {
    // A one-level vocabulary has nothing to bound; two thumbs on a single
    // notch would be a control that can't do anything.
    if (levels.size < 2) return
    val lastIndex = levels.lastIndex
    val serverLo = levels.indexOf(bounds?.min).takeIf { it >= 0 } ?: 0
    val serverHi = levels.indexOf(bounds?.max).takeIf { it >= 0 } ?: lastIndex

    var range by remember(levels) {
        mutableStateOf(serverLo.toFloat()..serverHi.toFloat())
    }
    // Re-seat on every settled write: on success the daemon's snapshot IS the
    // new position (a no-op assignment), on refusal it's the OLD one and this
    // is what undoes the drag. Keyed on `busy` so it runs at the write's
    // trailing edge and never fights an in-progress drag.
    LaunchedEffect(serverLo, serverHi, busy) {
        if (!busy) range = serverLo.toFloat()..serverHi.toFloat()
    }

    val lo = range.start.roundToInt().coerceIn(0, lastIndex)
    val hi = range.endInclusive.roundToInt().coerceIn(0, lastIndex)

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Text(
            text = "Thinking bounds",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Levels this model is allowed to run at. A session asking for " +
                "anything outside is moved to the nearest one. Span everything " +
                "to remove the limit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            valueRange = 0f..lastIndex.toFloat(),
            // One stop per level: steps counts the notches BETWEEN the ends.
            steps = (lastIndex - 1).coerceAtLeast(0),
            enabled = enabled,
            onValueChangeFinished = {
                onChange(
                    levels.getOrNull(lo).takeIf { lo > 0 },
                    levels.getOrNull(hi).takeIf { hi < lastIndex },
                )
            },
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = if (lo == 0 && hi == lastIndex) {
                "No limit — any thinking level"
            } else if (lo == hi) {
                "${effortLabel(levels[lo])} only"
            } else {
                "${effortLabel(levels[lo])} – ${effortLabel(levels[hi])}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
