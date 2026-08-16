package app.marmalade.android.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.getDatabase
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.ui.MarmaladeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host-screen widget configuration. Displays a list of known sessions and, on tap,
 * saves the selected sessionKey into Glance preferences, triggers a widget update,
 * and returns RESULT_OK so the launcher completes widget placement.
 */
class ChatWidgetConfigActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionsFlow = MutableStateFlow<List<SessionEntity>>(emptyList())
    private val loadingFlow = MutableStateFlow(true)

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Default result: cancelled. Overridden on successful selection.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Load sessions from Room (one-shot, sorted by lastMessageAt desc).
        scope.launch {
            try {
                val sessions = withContext(Dispatchers.IO) {
                    val dao = AppDatabase.getDatabase(applicationContext).chatDao()
                    // getAllSessionKeys isn't enough — we need display names, so query each.
                    val keys = dao.getAllSessionKeys()
                    keys.mapNotNull { dao.getSessionByKey(it) }
                        .sortedByDescending { it.lastMessageAt ?: 0L }
                }
                sessionsFlow.value = sessions
            } finally {
                loadingFlow.value = false
            }
        }

        setContent {
            MarmaladeTheme {
                val sessions by sessionsFlow.asStateFlow().collectAsState()
                val loading by loadingFlow.asStateFlow().collectAsState()
                SessionPickerScreen(
                    sessions = sessions,
                    loading = loading,
                    onSessionSelected = ::onSessionSelected,
                )
            }
        }
    }

    override fun onDestroy() {
        // Cancel any in-flight session-load / selection coroutine so it can't
        // outlive a fast finish() (e.g. invalid widget id, or the user
        // dismissing the picker before Room returns).
        scope.cancel()
        super.onDestroy()
    }

    private fun onSessionSelected(session: SessionEntity) {
        scope.launch {
            var writeOk = false
            try {
                val glanceId = GlanceAppWidgetManager(applicationContext)
                    .getGlanceIdBy(appWidgetId)
                updateAppWidgetState(applicationContext, glanceId) { prefs ->
                    prefs[ChatWidget.SESSION_KEY] = session.key
                }
                writeOk = true
                // Commit the widget placement BEFORE triggering the render. If the
                // subsequent update throws (e.g. layout constraint), the widget is
                // already placed and will retry on next update — the launcher won't
                // silently remove it.
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                )
                try {
                    ChatWidget().update(applicationContext, glanceId)
                } catch (t: Throwable) {
                    android.util.Log.w("ChatWidgetConfig", "update failed (widget still placed): ${t.message}")
                }
            } catch (t: Throwable) {
                android.util.Log.w("ChatWidgetConfig", "onSessionSelected failed: ${t.message}")
                if (!writeOk) {
                    // State write itself failed — leave result as CANCELED.
                }
            } finally {
                finish()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionPickerScreen(
    sessions: List<SessionEntity>,
    loading: Boolean,
    onSessionSelected: (SessionEntity) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Pick a session") })
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                sessions.isEmpty() -> {
                    Text(
                        text = "No sessions found. Open Marmalade to start one first.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessions, key = { it.key }) { session ->
                            SessionRow(session = session, onClick = { onSessionSelected(session) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = session.displayName ?: session.key,
                style = MaterialTheme.typography.titleMedium,
            )
            val sub = buildString {
                if (!session.category.isNullOrBlank()) append(session.category)
                if (isEmpty()) append(session.key)
            }
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

