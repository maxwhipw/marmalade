package app.marmalade.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.android.rpc.types.PluginInfo
import app.marmalade.android.ui.rememberMarmaladeRpc

/**
 * Plugins management screen.
 *
 * Backed by `plugins.manage` JSON-RPC (list + toggle). Search box +
 * per-row Switch + optimistic update with Snackbar revert on failure.
 *
 * Row metadata: version, source (bundled / user), status label. The
 * Switch state is derived from [pluginEnabled] on the status string —
 * the server emits a few variants ("enabled", "enabled (override)",
 * etc.) and we collapse them into a binary toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsSettingsScreen(
    onBack: () -> Unit,
    viewModel: PluginsViewModel = viewModel(factory = PluginsViewModel.factory(rememberMarmaladeRpc())),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val toggleError by viewModel.toggleError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(toggleError) {
        if (toggleError != null) {
            snackbarHostState.showSnackbar(toggleError!!)
            viewModel.clearToggleError()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Plugins") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is PluginsUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is PluginsUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SettingsErrorState(
                        headline = "Can't load plugins",
                        rawError = state.message,
                        onRetry = { viewModel.loadPlugins() },
                    )
                }
            }

            is PluginsUiState.Success -> {
                PluginsContent(
                    plugins = state.plugins,
                    userCount = state.userCount,
                    bundledCount = state.bundledCount,
                    paddingValues = paddingValues,
                    onToggle = { name, enabled -> viewModel.togglePlugin(name, enabled) },
                )
            }
        }
    }
}

@Composable
private fun PluginsContent(
    plugins: List<PluginInfo>,
    userCount: Int,
    bundledCount: Int,
    paddingValues: PaddingValues,
    onToggle: (name: String, enabled: Boolean) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedPlugin: PluginInfo? by remember { mutableStateOf(null) }
    // Resolve the currently-selected plugin against the live list so the
    // sheet's Switch reflects optimistic toggles + server-driven status
    // changes without the sheet snapshotting an outdated row.
    val liveSelectedPlugin: PluginInfo? = remember(selectedPlugin, plugins) {
        selectedPlugin?.let { sel ->
            // Match on name only (the toggle key): source can legitimately
            // change under an open dialog (harness fallback → real
            // marketplace after a daemon upgrade) and must not orphan it.
            plugins.firstOrNull { it.name == sel.name } ?: sel
        }
    }

    val filtered = remember(plugins, searchQuery) {
        if (searchQuery.isBlank()) plugins
        else plugins.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true) ||
                it.source.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search plugins") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Text(
                text = "$userCount user, $bundledCount bundled",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No plugins found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Compose key MUST be unique. The gateway's _discover_all_plugins
            // can surface the same (name, source) pair twice — the maintainer saw
            // "bundled/fal" hit, meaning two bundled "fal" plugins exist in
            // different on-disk directories that the server doesn't expose.
            // We have nothing on the wire to disambiguate, so fold the row
            // index into the key. Stable across recompositions for the same
            // sort order (gateway sorts alphabetically) — the worst case is
            // an animation glitch if the gateway re-orders rows, which
            // doesn't happen for this list.
            itemsIndexed(
                filtered,
                key = { idx, p -> "$idx/${p.source}/${p.name}" },
            ) { _, plugin ->
                PluginRow(
                    plugin = plugin,
                    onToggle = { enabled -> onToggle(plugin.name, enabled) },
                    onOpenDetail = { selectedPlugin = plugin },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    val dialogPlugin = liveSelectedPlugin
    if (dialogPlugin != null) {
        val enabled = pluginEnabled(dialogPlugin.status)
        val subtitleParts = buildList {
            if (dialogPlugin.version.isNotBlank()) add("v${dialogPlugin.version}")
            if (dialogPlugin.source.isNotBlank()) add(dialogPlugin.source)
            if (dialogPlugin.status.isNotBlank()) add(dialogPlugin.status)
        }
        SettingDetailDialog(
            title = dialogPlugin.name.substringBeforeLast("@"),
            subtitle = subtitleParts.joinToString(" · "),
            description = dialogPlugin.description.takeIf { it.isNotBlank() },
            toggleEnabled = enabled,
            toggleTitle = if (enabled) "Enabled" else "Disabled",
            toggleSubtext = if (enabled) "Loaded on the next session"
                            else "Discovered but not loaded",
            onToggle = { toEnabled -> onToggle(dialogPlugin.name, toEnabled) },
            onDismiss = { selectedPlugin = null },
        )
    }
}

@Composable
private fun PluginRow(
    plugin: PluginInfo,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
) {
    val enabled = pluginEnabled(plugin.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Row click opens the detail sheet (mirrors the standard
            // settings-list pattern of "tap row = inspect, tap control =
            // act"). The Switch is the dedicated toggle.
            .clickable { onOpenDetail() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Strip the "@marketplace" suffix — the marketplace shows
                    // as the source subtitle. Full name stays the toggle key.
                    text = plugin.name.substringBeforeLast("@"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (plugin.version.isNotBlank()) {
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text(
                        text = "v${plugin.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plugin.description.isNotBlank()) {
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val subtitleParts = buildList {
                if (plugin.source.isNotBlank()) add(plugin.source)
                if (plugin.status.isNotBlank() && !plugin.status.equals("enabled", ignoreCase = true)) {
                    add(plugin.status)
                }
            }
            if (subtitleParts.isNotEmpty()) {
                Text(
                    text = subtitleParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
}
