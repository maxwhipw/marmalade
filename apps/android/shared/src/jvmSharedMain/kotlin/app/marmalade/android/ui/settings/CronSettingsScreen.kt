package app.marmalade.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.android.rpc.types.CronJob
import app.marmalade.android.ui.rememberMarmaladeRpc
import app.marmalade.android.utils.CronFormatUtils
import java.util.Calendar

/**
 * Scheduled prompts (daemon cron.*) management screen.
 *
 * List shows EVERY job, disabled included — a scheduler-disabled job carries
 * its reason (daemon records it in last_error) and hiding it is how a dead
 * job goes unnoticed for days. Enable/disable = Switch, run-now fires
 * out-of-band (schedule unmoved), delete confirms first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronSettingsScreen(
    onBack: () -> Unit,
    viewModel: CronViewModel = viewModel(factory = CronViewModel.factory(rememberMarmaladeRpc())),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val sessionOptions by viewModel.sessionOptions.collectAsStateWithLifecycle()

    // Pick up jobs created elsewhere (CLI, webui) on return to the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load(silent = true) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionMessage()
        }
    }

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CronJob?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Scheduled prompts") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New") },
            )
        },
    ) { padding ->
        when (val state = uiState) {
            is CronUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is CronUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SettingsErrorState(
                    headline = "Can't load scheduled prompts",
                    rawError = state.message,
                    onRetry = { viewModel.load() },
                )
            }
            is CronUiState.Success -> if (state.jobs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        "No scheduled prompts yet.\nTap New to schedule one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.jobs, key = { it.jobId }) { job ->
                        CronJobCard(
                            job = job,
                            onToggle = { viewModel.setEnabled(job, it) },
                            onRunNow = { viewModel.runNow(job) },
                            onDelete = { deleteTarget = job },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CronCreateDialog(
            sessionOptions = sessionOptions,
            onDismiss = { showCreate = false },
            onCreate = { sessionId, prompt, schedule, name ->
                viewModel.create(sessionId, prompt, schedule, name) { ok ->
                    if (ok) showCreate = false
                }
            },
        )
    }

    deleteTarget?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete scheduled prompt?") },
            text = { Text("“${job.name ?: job.jobId}” will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(job)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CronJobCard(
    job: CronJob,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    val now = System.currentTimeMillis()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        job.name ?: "(unnamed)",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        CronFormatUtils.describeSchedule(job.schedule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = job.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                CronFormatUtils.stateLabel(job, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                CronFormatUtils.lastRunLabel(job),
                style = MaterialTheme.typography.bodySmall,
                color = if (job.lastStatus == "error") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                job.prompt,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onRunNow) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run now")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

// DatePicker / TimePicker (and rememberDatePickerState / rememberTimePickerState)
// are still ExperimentalMaterial3Api. In :app this was covered by a module-wide
// -opt-in compiler flag; :shared deliberately has no such flag, so the opt-in is
// stated where it's actually used.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CronCreateDialog(
    sessionOptions: List<CronSessionOption>,
    onDismiss: () -> Unit,
    onCreate: (sessionId: String, prompt: String, schedule: app.marmalade.android.rpc.types.CronSchedule, name: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("every") }
    var every by rememberSaveable { mutableStateOf("1h") }
    var expr by rememberSaveable { mutableStateOf("") }
    var tz by rememberSaveable { mutableStateOf("") }
    // One-shot fire time (UTC ms), picked via date → time dialogs.
    var atMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerStep by rememberSaveable { mutableStateOf<String?>(null) } // "date" | "time"
    // UTC-midnight of the date picked in step one, consumed by step two.
    var pickedDateMs by rememberSaveable { mutableStateOf<Long?>(null) }
    // session.list arrives most-recently-active first; default = same as the
    // CLI's --session last.
    var sessionId by rememberSaveable(sessionOptions.firstOrNull()?.sessionId) {
        mutableStateOf(sessionOptions.firstOrNull()?.sessionId ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New scheduled prompt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "every", onClick = { kind = "every" }, label = { Text("Every") })
                    FilterChip(selected = kind == "cron", onClick = { kind = "cron" }, label = { Text("Cron") })
                    FilterChip(selected = kind == "at", onClick = { kind = "at" }, label = { Text("Once") })
                }
                when (kind) {
                    "every" -> OutlinedTextField(
                        value = every,
                        onValueChange = { every = it },
                        label = { Text("Interval (30s, 15m, 2h, 1d)") },
                        singleLine = true,
                    )
                    "cron" -> {
                        OutlinedTextField(
                            value = expr,
                            onValueChange = { expr = it },
                            label = { Text("Cron expression (e.g. 0 9 * * 1-5)") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = tz,
                            onValueChange = { tz = it },
                            label = { Text("Timezone (IANA, blank = daemon host)") },
                            singleLine = true,
                        )
                    }
                    "at" -> androidx.compose.material3.OutlinedButton(onClick = { pickerStep = "date" }) {
                        Text(
                            atMs?.let {
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                                ).format(java.util.Date(it))
                            } ?: "Pick date & time…",
                        )
                    }
                }
                if (sessionOptions.isNotEmpty()) {
                    Text("Target session", style = MaterialTheme.typography.labelMedium)
                    // A compact chip row beats a dropdown for the common case
                    // (recent sessions first); long lists scroll horizontally.
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessionOptions, key = { it.sessionId }) { opt ->
                            FilterChip(
                                selected = sessionId == opt.sessionId,
                                onClick = { sessionId = opt.sessionId },
                                label = { Text(opt.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val now = System.currentTimeMillis()
                if (prompt.isBlank()) { error = "a prompt is required"; return@TextButton }
                if (sessionId.isEmpty()) { error = "no sessions exist — open a chat first"; return@TextButton }
                CronViewModel.buildSchedule(kind, expr, tz, every, atMs, now).fold(
                    onSuccess = { onCreate(sessionId, prompt.trim(), it, name.trim().ifEmpty { null }) },
                    onFailure = { error = it.message },
                )
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    // Two-step one-shot picker: date first, then time; combined into local
    // wall-clock → UTC ms. DatePicker's selection is UTC-midnight of the
    // chosen date, so the date fields are read back in UTC.
    if (pickerStep == "date") {
        val dateState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = atMs ?: System.currentTimeMillis(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickerStep = null },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        pickedDateMs = it
                        pickerStep = "time"
                    }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { pickerStep = null }) { Text("Cancel") } },
        ) {
            androidx.compose.material3.DatePicker(state = dateState)
        }
    }
    if (pickerStep == "time") {
        val cal = Calendar.getInstance()
        val timeState = androidx.compose.material3.rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
        )
        AlertDialog(
            onDismissRequest = { pickerStep = null },
            title = { Text("Fire at") },
            text = { androidx.compose.material3.TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    atMs = combineDateAndTime(pickedDateMs ?: System.currentTimeMillis(), timeState.hour, timeState.minute)
                    pickerStep = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickerStep = null }) { Text("Cancel") } },
        )
    }
}

/**
 * DatePicker gives UTC-midnight of the chosen calendar date; combine with a
 * local wall-clock hour/minute → UTC ms.
 *
 * Public, not `internal`: its test lives in `:app` (CronCreateLogicTest), and
 * `:app`'s test source set is a friend of `:app`'s main compilation only —
 * never of `:shared`'s. An `internal` here compiles until something forces a
 * full recompile of the `:app` test sources, then fails; that is the "internal
 * trap" this module has hit before (see the 2026-07-25 KMP handoff).
 */
fun combineDateAndTime(dateUtcMidnightMs: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = dateUtcMidnightMs
    val local = Calendar.getInstance()
    local.clear()
    local.set(
        utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH),
        hour, minute, 0,
    )
    return local.timeInMillis
}
