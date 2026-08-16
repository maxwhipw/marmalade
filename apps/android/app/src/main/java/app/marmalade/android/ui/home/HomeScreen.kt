package app.marmalade.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.chat.MAIN_SESSION_PLACEHOLDER
import app.marmalade.android.node.MarmaladeRuntime
import app.marmalade.android.ui.chat.ChatScreen
import app.marmalade.android.ui.chat.friendlySessionName
import app.marmalade.android.ui.chat.rememberInlineSTTState
import kotlinx.coroutines.launch

/**
 * Home tab content: opens THE daemon-managed singleton main session.
 *
 * Session resolution: Home is ALWAYS `session.main` — the daemon-managed
 * assistant session, resolved on connect (and seeded from the persisted id on
 * an offline cold start). It is never created client-side and never
 * user-selectable; there is no "pick your assistant session" (assistant plan
 * 2026-07-19: main is daemon-owned). The only null case is before the runtime
 * has resolved any main id at all (first launch, never connected), which shows
 * a brief connecting state.
 */
@Composable
fun HomeScreen(
    marmaladeRuntime: MarmaladeRuntime,
    onStatusClick: (() -> Unit)? = null,
    /** Opens the session switcher from the title bar (ADR 0013). Main belongs
     *  to no workspace, so the sheet opens in Quick-sessions scope. */
    onTitleClick: (() -> Unit)? = null,
    /** Opens the navigation drawer (ADR 0013). */
    onMenuClick: (() -> Unit)? = null,
    /** Opens the session tool panel (ADR 0013). */
    onPanelClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val sessionKey by marmaladeRuntime.chatSessionKey.collectAsStateWithLifecycle()
    val mainSessionKey by marmaladeRuntime.mainSessionKey.collectAsStateWithLifecycle()
    val chatSessions by marmaladeRuntime.chatSessions.collectAsStateWithLifecycle()
    val isConnected by marmaladeRuntime.isConnected.collectAsStateWithLifecycle()
    val attachmentsSupported by marmaladeRuntime.attachmentsSupported.collectAsStateWithLifecycle()
    val undoSupported by marmaladeRuntime.undoSupported.collectAsStateWithLifecycle()
    val searchSupported by marmaladeRuntime.searchSupported.collectAsStateWithLifecycle()

    // Home binds to the daemon's main session — resolved on connect, seeded
    // from cache offline. Recomputed when it changes.
    val assistantKey: String? = remember(mainSessionKey) {
        resolveAssistantSessionKey(mainSessionKey)
    }

    // On Home entry (and when the resolved assistant key changes), navigate
    // ChatController into the assistant session. Uses switchChatSession so the
    // old session's messages are cleared during bootstrap — matches the
    // Sessions-detail route behaviour instead of leaving stale content on
    // screen. Keyed by assistantKey so recomposition doesn't churn.
    LaunchedEffect(assistantKey) {
        val target = assistantKey
        if (target != null && target != sessionKey) {
            marmaladeRuntime.switchChatSession(target)
        }
    }

    if (assistantKey == null) {
        HomeNoAssistantSessionState()
        return
    }

    val sttState = rememberInlineSTTState(
        onMicBusy = {
            android.widget.Toast.makeText(
                context,
                context.getString(app.marmalade.android.R.string.error_mic_busy),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        },
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val displayName = chatSessions
        .find { it.key == assistantKey }?.displayName
        ?: friendlySessionName(assistantKey)

    ChatScreen(
        chat = marmaladeRuntime.chat,
        sessionName = displayName,
        showBackArrow = false,
        onBackPressed = null,
        onSettingsClick = null,
        isInlineSTTActive = sttState.isActive,
        inlineSTTPartialText = sttState.partialText,
        onMicTap = { sttState.toggle() },
        onMicLongPress = { sttState.triggerVoicePopup() },
        isConnected = isConnected,
        onStatusClick = onStatusClick,
        onTitleClick = onTitleClick,
        onMenuClick = onMenuClick,
        onPanelClick = onPanelClick,
        attachmentsSupported = attachmentsSupported,
        undoSupported = undoSupported,
        searchSupported = searchSupported,
        searchRpc = marmaladeRuntime.marmaladeRpc,
    )
}

/**
 * Resolve the session Home shows: ALWAYS the daemon-managed main session
 * (`session.main`), or null before any main id has been resolved.
 *
 * The [mainSessionKey] is the runtime's resolved main id — set from
 * `session.main` on connect and seeded from the persisted id on an offline
 * cold start. The only non-main value it can hold is [MAIN_SESSION_PLACEHOLDER]
 * ("main"), the boot placeholder that names no real conversation: binding it
 * would render an empty phantom chat, so we return null (the connecting state)
 * and let the runtime replace it with the real id. There is no user pref and
 * no most-recent fallback — Home == main, full stop.
 */
internal fun resolveAssistantSessionKey(mainSessionKey: String): String? {
    val main = mainSessionKey.trim()
    if (main.isEmpty() || main == MAIN_SESSION_PLACEHOLDER) return null
    return main
}

@Composable
private fun HomeNoAssistantSessionState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Connecting to Marmalade…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your assistant opens here as soon as the daemon is reachable.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
