package app.marmalade.android.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.marmalade.android.MainActivity
import app.marmalade.android.R
import app.marmalade.android.data.local.entity.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Glance composable rendering the chat widget UI.
 *
 * Top-level layout is a 3-child Column (header, LazyColumn of messages, quick reply)
 * because Glance hard-caps Column/Row at 10 children. LazyColumn is exempt from that
 * cap and is the correct container for dynamic message lists.
 */
@Composable
internal fun ChatWidgetContent(
    messages: List<MessageEntity>,
    sessionKey: String,
    displayLabel: String,
    isCompact: Boolean,
    isConnected: Boolean,
) {
    val ctx = LocalContext.current
    val openSessionAction = remember(sessionKey) {
        actionStartActivity(
            Intent(ctx, MainActivity::class.java).apply {
                putExtra("navigate_to_session", sessionKey)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(10.dp)
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // Header (1 child)
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = GlanceModifier.size(20.dp),
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = displayLabel,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                if (!isConnected) OfflineChip()
            }

            // Message list — LazyColumn has no 10-child cap (2nd child of outer Column)
            val ordered = messages.asReversed() // oldest first within the window
            val maxLinesPerMsg = if (isCompact) 2 else 3
            LazyColumn(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .defaultWeight()
                    .clickable(openSessionAction),
            ) {
                if (ordered.isEmpty()) {
                    item {
                        Text(
                            text = "No messages yet",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                        )
                    }
                } else {
                    items(ordered, itemId = { it.id.hashCode().toLong() }) { msg ->
                        MessageBubble(message = msg, maxLines = maxLinesPerMsg)
                    }
                }
            }

            // Quick reply (3rd child)
            Row(
                modifier = GlanceModifier.fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(12.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable(openSessionAction),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Reply in Marmalade...",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun OfflineChip() {
    Box(
        modifier = GlanceModifier
            .background(GlanceTheme.colors.errorContainer)
            .cornerRadius(8.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Offline",
            style = TextStyle(
                color = GlanceTheme.colors.onErrorContainer,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, maxLines: Int) {
    val isUser = message.role == "user"
    val bubbleBackground = if (isUser) {
        GlanceTheme.colors.primaryContainer
    } else {
        GlanceTheme.colors.surfaceVariant
    }
    val bubbleTextColor = if (isUser) {
        GlanceTheme.colors.onPrimaryContainer
    } else {
        GlanceTheme.colors.onSurfaceVariant
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalAlignment = alignment,
    ) {
        Box(
            modifier = GlanceModifier
                .background(bubbleBackground)
                .cornerRadius(12.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = extractVisibleText(message.contentJson).ifBlank { "(empty)" },
                style = TextStyle(color = bubbleTextColor),
                maxLines = maxLines,
            )
        }
    }
}

/**
 * Extract the first user-visible text from a stored contentJson array,
 * skipping thinking and tool_use/tool_result blocks entirely.
 *
 * Mirrors the app's own "visible text" logic (see ChatItem.kt `isToolOnly`
 * and ChatMessageViews rendering predicates): widget bubbles only show
 * conversational turns, never agent plumbing.
 */
internal fun extractVisibleText(contentJson: String): String {
    if (contentJson.isBlank()) return ""
    return try {
        val element = Json.parseToJsonElement(contentJson)
        when (element) {
            is JsonArray -> element.firstNotNullOfOrNull { part ->
                (part as? JsonObject)?.let(::readTextOnly)?.takeIf { it.isNotBlank() }
            }.orEmpty()
            is JsonObject -> readTextOnly(element)
            else -> ""
        }
    } catch (_: Throwable) {
        ""
    }
}

/** Return text only from text-typed (or untyped plain-text) blocks. */
private fun readTextOnly(obj: JsonObject): String {
    val type = obj["type"]?.jsonPrimitive?.content
    if (type == "thinking" || type == "tool_use" || type == "tool_result") return ""
    return obj["text"]?.jsonPrimitive?.content.orEmpty()
}
