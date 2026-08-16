package app.marmalade.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
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
import app.marmalade.android.rpc.types.SkillInfo
import app.marmalade.android.ui.rememberMarmaladeRpc

/**
 * Skills management screen.
 *
 * Shows all skills from the marmalade-agent REST API (`GET /api/skills`).
 * Each skill row has a Switch that calls `PUT /api/skills/toggle` with
 * optimistic local update and Snackbar revert-notification on failure.
 *
 * UI states:
 * - [SkillsUiState.Loading] → centered [CircularProgressIndicator]
 * - [SkillsUiState.Error] → error text + Retry button
 * - [SkillsUiState.Success] → search field + category chips + skill list
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SkillsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SkillsViewModel = viewModel(factory = SkillsViewModel.factory(rememberMarmaladeRpc())),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val toggleError by viewModel.toggleError.collectAsStateWithLifecycle()

    // #27: auto-refresh when the screen resumes so skills toggled elsewhere
    // (gateway, another client) show up without a manual reload. Silent —
    // no spinner flash, keeps the current list if the refetch fails.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.loadSkills(silent = true)
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // Surface toggle errors via Snackbar
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
                title = { Text("Skills") },
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
            is SkillsUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is SkillsUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SettingsErrorState(
                        headline = "Can't load skills",
                        rawError = state.message,
                        onRetry = { viewModel.loadSkills() },
                    )
                }
            }

            is SkillsUiState.Success -> {
                SkillsContent(
                    skills = state.skills,
                    paddingValues = paddingValues,
                    onToggle = { name, enabled -> viewModel.toggleSkill(name, enabled) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillsContent(
    skills: List<SkillInfo>,
    paddingValues: PaddingValues,
    onToggle: (name: String, enabled: Boolean) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedSkill: SkillInfo? by remember { mutableStateOf(null) }
    // Resolve the open skill against the live list so the detail sheet's
    // Switch reflects optimistic toggles instead of snapshotting a stale row.
    // Skills are keyed by name (unique in the list), same as the LazyColumn.
    val liveSelectedSkill: SkillInfo? = remember(selectedSkill, skills) {
        selectedSkill?.let { sel -> skills.firstOrNull { it.name == sel.name } ?: sel }
    }

    // Derive distinct categories (sorted, "All" is the null sentinel)
    val categories = remember(skills) {
        skills.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }
            .distinct()
            .sorted()
    }

    // Apply filters
    val filtered = remember(skills, searchQuery, selectedCategory) {
        skills.filter { skill ->
            val matchesSearch = searchQuery.isBlank() ||
                skill.name.contains(searchQuery, ignoreCase = true) ||
                skill.description.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategory == null ||
                skill.category == selectedCategory
            matchesSearch && matchesCategory
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // Search field
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search skills") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Category chips (only if there are categories)
        if (categories.isNotEmpty()) {
            item {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All") },
                    )
                    categories.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = {
                                selectedCategory = if (selectedCategory == category) null else category
                            },
                            label = { Text(category) },
                        )
                    }
                }
            }
        }

        // Empty state
        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No skills found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(filtered, key = { it.name }) { skill ->
                SkillRow(
                    skill = skill,
                    onToggle = { enabled -> onToggle(skill.name, enabled) },
                    onOpenDetail = { selectedSkill = skill },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    val dialogSkill = liveSelectedSkill
    if (dialogSkill != null) {
        val category = dialogSkill.category?.takeIf { it.isNotBlank() }
        SettingDetailDialog(
            title = dialogSkill.name,
            subtitle = category,
            description = dialogSkill.description.takeIf { it.isNotBlank() },
            toggleEnabled = dialogSkill.enabled,
            toggleTitle = if (dialogSkill.enabled) "Enabled" else "Disabled",
            toggleSubtext = if (dialogSkill.enabled) "Available to the agent"
                            else "Not offered to the agent",
            onToggle = { enabled -> onToggle(dialogSkill.name, enabled) },
            onDismiss = { selectedSkill = null },
        )
    }
}

@Composable
private fun SkillRow(
    skill: SkillInfo,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Row click opens the detail sheet (full description); the Switch
            // is the dedicated toggle. Mirrors PluginsSettingsScreen's
            // "tap row = inspect, tap control = act" pattern.
            .clickable { onOpenDetail() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = skill.enabled,
            onCheckedChange = onToggle,
        )
    }
}
