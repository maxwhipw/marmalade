package app.marmalade.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A quiet, centred meta line in the transcript — the render for a System-role
 * [app.marmalade.android.chat.messages.ChatMessage].
 *
 * Today's only producer is the daemon's durable `effort.clamped` event ("a
 * per-model bound moved your thinking level"). The treatment is design-lab
 * option E3 (maintainer, 2026-07-27): a permanent record that reads like a margin
 * note, NOT a card, NOT tinted, NOT interactive. The maintainer explicitly rejected the
 * busier banner/badge option — a clamp is information, not an incident, and a
 * card in the middle of a conversation reads as one.
 *
 * So: no Surface, no border, no long-press menu (unlike [MessageBubble], which
 * every other role goes through). Muted on-surface ink at the smallest body
 * size, centred so it reads as a divider between turns rather than as
 * somebody's message.
 */
@Composable
fun SystemNoticeLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
