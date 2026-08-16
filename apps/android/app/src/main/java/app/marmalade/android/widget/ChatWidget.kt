package app.marmalade.android.widget

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.getDatabase
import app.marmalade.android.data.local.entity.MessageEntity

/**
 * Home-screen chat widget (Phase 6 Plan 3).
 *
 * Shows recent messages for a configured session and a quick-reply affordance.
 * Uses SizeMode.Responsive to adapt message count between COMPACT and EXPANDED sizes.
 */
class ChatWidget : GlanceAppWidget() {

    companion object {
        val COMPACT = DpSize(250.dp, 120.dp)
        val EXPANDED = DpSize(250.dp, 280.dp)

        val SESSION_KEY = stringPreferencesKey("session_key")
        const val DEFAULT_SESSION_KEY = "main"

        const val COMPACT_MESSAGE_LIMIT = 3
        const val EXPANDED_MESSAGE_LIMIT = 8

        const val WIDGET_PREFS = "marmalade_widget"
        const val KEY_GATEWAY_CONNECTED = "gateway_connected"
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, EXPANDED))

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read configured session key via explicit read API (does NOT mutate state).
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val sessionKey = prefs[SESSION_KEY] ?: DEFAULT_SESSION_KEY

        val dao = try {
            AppDatabase.getDatabase(context).chatDao()
        } catch (t: Throwable) {
            Log.w("ChatWidget", "provideGlance: DB open failed: ${t.message}")
            null
        }

        // Overfetch so we still fill the widget after dropping tool/thinking-only rows.
        // Mirrors ChatItem.kt's `isToolOnly` rule: skip role="toolResult" and any
        // message whose only blocks are tool_use/thinking (no text).
        val rawMessages: List<MessageEntity> = try {
            dao?.getRecentMessagesOnce(sessionKey, EXPANDED_MESSAGE_LIMIT * 4).orEmpty()
        } catch (t: Throwable) {
            Log.w("ChatWidget", "provideGlance: message load failed: ${t.message}")
            emptyList()
        }
        val messages: List<MessageEntity> = rawMessages
            .asSequence()
            .filter { it.role != "toolResult" }
            .filter { extractVisibleText(it.contentJson).isNotBlank() }
            .take(EXPANDED_MESSAGE_LIMIT)
            .toList()

        val displayLabel: String = try {
            dao?.getSessionByKey(sessionKey)?.displayName?.takeIf { it.isNotBlank() }
                ?: sessionKey.friendlySessionLabel()
        } catch (_: Throwable) {
            sessionKey.friendlySessionLabel()
        }

        val isConnected = try {
            context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GATEWAY_CONNECTED, false)
        } catch (_: Throwable) {
            false
        }

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                val isCompact = size.height < EXPANDED.height
                val limit = if (isCompact) COMPACT_MESSAGE_LIMIT else EXPANDED_MESSAGE_LIMIT
                val displayMessages = messages.take(limit)
                ChatWidgetContent(
                    messages = displayMessages,
                    sessionKey = sessionKey,
                    displayLabel = displayLabel,
                    isCompact = isCompact,
                    isConnected = isConnected,
                )
            }
        }
    }
}

/** Minimal friendly label — used as fallback when no displayName is stored. */
internal fun String.friendlySessionLabel(): String {
    if (isBlank()) return "Marmalade"
    val parts = split(":")
    return parts.lastOrNull()?.ifBlank { this } ?: this
}
