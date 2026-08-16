package app.marmalade.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.android.rpc.types.McpServerInfo
import app.marmalade.android.ui.rememberMarmaladeRpc

/**
 * MCP server list settings screen.
 *
 * Shows all MCP servers from the marmalade-agent REST API (`GET /api/mcp/servers`).
 * Each row has a Switch that calls `PUT /api/mcp/servers/<name>/enabled` with
 * optimistic local update and Snackbar revert-notification on failure.
 *
 * Mirrors [SkillsSettingsScreen] closely — search + per-row toggle, same
 * error/loading states.
 *
 * UI states:
 * - [McpUiState.Loading] → centered [CircularProgressIndicator]
 * - [McpUiState.Error] → error text + Retry button
 * - [McpUiState.Success] → search field + server list
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsScreen(
    onBack: () -> Unit,
    viewModel: McpViewModel = viewModel(factory = McpViewModel.factory(rememberMarmaladeRpc())),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val toggleError by viewModel.toggleError.collectAsStateWithLifecycle()

    // #27: auto-refresh when the screen resumes so servers changed elsewhere
    // (gateway, another client) show up without a manual reload. Silent —
    // no spinner flash, keeps the current list if the refetch fails.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.loadServers(silent = true)
    }

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
                title = { Text("MCP Servers") },
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
            is McpUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is McpUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SettingsErrorState(
                        headline = "Can't load MCP servers",
                        rawError = state.message,
                        onRetry = { viewModel.loadServers() },
                    )
                }
            }

            is McpUiState.Success -> {
                McpContent(
                    servers = state.servers,
                    paddingValues = paddingValues,
                    onToggle = { name, enabled -> viewModel.toggleServer(name, enabled) },
                )
            }
        }
    }
}

@Composable
private fun McpContent(
    servers: List<McpServerInfo>,
    paddingValues: PaddingValues,
    onToggle: (name: String, enabled: Boolean) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedServer: McpServerInfo? by remember { mutableStateOf(null) }
    // Resolve the open server against the live list so the dialog's Switch
    // reflects optimistic toggles instead of snapshotting a stale row.
    val liveSelectedServer: McpServerInfo? = remember(selectedServer, servers) {
        selectedServer?.let { sel -> servers.firstOrNull { it.name == sel.name } ?: sel }
    }

    val filtered = remember(servers, searchQuery) {
        if (searchQuery.isBlank()) servers
        else servers.filter { server ->
            server.name.contains(searchQuery, ignoreCase = true) ||
                server.transport.contains(searchQuery, ignoreCase = true) ||
                server.url?.contains(searchQuery, ignoreCase = true) == true ||
                server.command?.contains(searchQuery, ignoreCase = true) == true
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
                label = { Text("Search servers") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        text = "No MCP servers found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(filtered, key = { it.name }) { server ->
                McpServerRow(
                    server = server,
                    onToggle = { enabled -> onToggle(server.name, enabled) },
                    onOpenDetail = { selectedServer = server },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    val dialogServer = liveSelectedServer
    if (dialogServer != null) {
        // Endpoint + tool count as the subtitle; the daemon's mcp.list sends
        // only name/transport/enabled today, so url/command/tools are usually
        // absent — they render when the wire starts carrying them.
        val subtitleParts = buildList {
            add(dialogServer.transport)
            (dialogServer.url ?: dialogServer.command)?.takeIf { it.isNotBlank() }?.let { add(it) }
            dialogServer.tools?.size?.let { n ->
                add(if (n == 0) "no tools" else "$n tool${if (n == 1) "" else "s"}")
            }
        }
        SettingDetailDialog(
            title = dialogServer.name,
            subtitle = subtitleParts.joinToString(" · "),
            toggleEnabled = dialogServer.enabled,
            toggleTitle = if (dialogServer.enabled) "Enabled" else "Disabled",
            toggleSubtext = "Applies on the next session",
            onToggle = { enabled -> onToggle(dialogServer.name, enabled) },
            onDismiss = { selectedServer = null },
        )
    }
}

@Composable
private fun McpServerRow(
    server: McpServerInfo,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Row click opens the detail dialog; the Switch is the dedicated
            // toggle (same "tap row = inspect, tap control = act" pattern as
            // Skills / Plugins).
            .clickable { onOpenDetail() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Show the endpoint (URL or command) as a subtitle
            val subtitle = server.url ?: server.command
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Tool count badge (null means not yet connected)
            val toolCount = server.tools?.size
            if (toolCount != null) {
                Text(
                    text = if (toolCount == 0) "No tools" else "$toolCount tool${if (toolCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = server.enabled,
            onCheckedChange = onToggle,
        )
    }
}
