package app.marmalade.android.ui.debugging

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import app.marmalade.android.ui.components.MarmaladeMenu
import app.marmalade.android.ui.components.MarmaladeMenuItem
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.getDatabase
import app.marmalade.android.data.local.entity.GatewayEventEntity
import app.marmalade.android.ui.chat.ChatCodeBlock
import app.marmalade.android.ui.chat.friendlySessionName
import app.marmalade.android.ui.setPlainText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Event Trace (Settings → Developer): browses the PERSISTENT gateway_events
 * ring buffer (Room, ~500 rows/session, survives restarts) — the read side
 * promised by GatewayEventEntity's doc comment, closing dead-code audit
 * item W2. The bottom-bar Debug tab is a different surface: in-memory
 * transport frames for this process only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventTraceScreen(
    onBack: () -> Unit,
    // The VM lives in :shared and takes its DAO by constructor, so the host
    // supplies the database handle here (the Application reach-in it used to
    // do itself). Same shape as the shared settings screens' rpc factories.
    viewModel: EventTraceViewModel = run {
        val context = LocalContext.current
        val chatDao = remember(context) { AppDatabase.getDatabase(context).chatDao() }
        viewModel(factory = EventTraceViewModel.factory(chatDao))
    },
) {
    val events by viewModel.events.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val sessionFilter by viewModel.sessionFilter.collectAsState()
    val typeFilter by viewModel.typeFilter.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Event Trace") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = "Persistent per-session gateway event log (last $EVENT_TRACE_LIMIT). " +
                    "For live transport frames use the Debug tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typeFilter,
                    onValueChange = viewModel::setTypeFilter,
                    placeholder = { Text("Filter by type", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                SessionFilterChip(
                    label = sessionFilter?.let { key ->
                        sessions.firstOrNull { it.key == key }?.displayName ?: friendlySessionName(key)
                    } ?: "All sessions",
                    options = sessions.map { s -> s.key to (s.displayName ?: friendlySessionName(s.key)) },
                    onSelect = viewModel::setSessionFilter,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (events.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No events recorded yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(events, key = { it.rowid }) { event ->
                        EventTraceRow(event = event)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionFilterChip(
    label: String,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = label != "All sessions",
            onClick = { menuOpen = true },
            label = {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            },
        )
        MarmaladeMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            MarmaladeMenuItem(
                label = "All sessions",
                icon = null,
                onClick = {
                    menuOpen = false
                    onSelect(null)
                },
            )
            options.forEach { (key, name) ->
                MarmaladeMenuItem(
                    label = name,
                    icon = null,
                    onClick = {
                        menuOpen = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

@Composable
private fun EventTraceRow(event: GatewayEventEntity) {
    var expanded by remember(event.rowid) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.type,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = timeLabel(event.receivedAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        event.sessionKey?.let { key ->
            Text(
                text = key,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(4.dp))
                ChatCodeBlock(
                    code = prettyPayload(event.payloadJson, DisplayJson),
                    language = "json",
                    onCopy = { scope.launch { clipboard.setPlainText(event.payloadJson) } },
                )
            }
        }
    }
}

private fun timeLabel(ms: Long): String =
    SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date(ms))

private val DisplayJson = Json { ignoreUnknownKeys = true }
