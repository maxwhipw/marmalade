package app.marmalade.android.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.marmalade.android.rpc.types.SearchHit
import app.marmalade.android.rpc.types.SearchRoles
import app.marmalade.android.rpc.types.SearchSessionInfo
import app.marmalade.android.rpc.types.WorkspaceInfo
import app.marmalade.android.search.SnippetMarkers
import app.marmalade.android.ui.sessions.formatRelativeTimestamp

/**
 * Turn a daemon snippet into a styled [AnnotatedString].
 *
 * The daemon wraps matched spans in two private-use codepoints; rendering the
 * raw string paints tofu boxes around every match. [SnippetMarkers.parse] strips
 * them and this applies the highlight — bold + the toast/primary container so it
 * reads as a highlight in both light and dark (the brand's accent swaps by mode,
 * which is why this uses theme roles rather than a literal orange).
 */
@Composable
fun highlightedSnippet(snippet: String): AnnotatedString {
    val highlightBg = MaterialTheme.colorScheme.primaryContainer
    val highlightFg = MaterialTheme.colorScheme.onPrimaryContainer
    return remember(snippet, highlightBg, highlightFg) {
        val segments = SnippetMarkers.parse(snippet)
        buildAnnotatedString {
            for (segment in segments) {
                if (segment.match) {
                    withStyle(
                        SpanStyle(
                            background = highlightBg,
                            color = highlightFg,
                            fontWeight = FontWeight.Bold,
                        ),
                    ) { append(segment.text) }
                } else {
                    append(segment.text)
                }
            }
        }
    }
}

/**
 * Header opening a run of consecutive hits from the same session (the maintainer's locked
 * decision: flat ranked query, client-side visual grouping).
 *
 * The workspace chip comes from [info]`.workspace_id` — the daemon matcher's
 * verdict. Never re-derive membership from a cwd here; the client's copy of the
 * rule would eventually disagree with the session list's grouping.
 */
@Composable
fun SearchSessionHeader(
    sessionId: String,
    info: SearchSessionInfo?,
    workspaces: List<WorkspaceInfo>,
    modifier: Modifier = Modifier,
) {
    val workspace = remember(info?.workspace_id, workspaces) {
        info?.workspace_id?.let { id -> workspaces.firstOrNull { it.workspace_id == id } }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // An archive session is not openable, so it must never look like one
        // that is. The pill leads the header for the same reason the Archive
        // chip leads the scope strip.
        if (info?.isArchive == true) ArchivePill()
        WorkspaceChip(workspaceId = info?.workspace_id, workspace = workspace)
        Text(
            // A session the daemon never titled shows its id rather than a lie.
            text = info?.title?.takeIf { it.isNotBlank() } ?: sessionId,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (info != null) {
            Text(
                text = formatRelativeTimestamp(info.last_active),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "ARCHIVE" pill — this conversation is pre-daemon history and cannot be
 * opened. Tertiary, so it is unmistakably a different KIND of thing from the
 * workspace pill beside it (secondary) rather than another workspace.
 */
@Composable
internal fun ArchivePill() {
    Text(
        text = "ARCHIVE",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Workspace pill, or the Quick-chat pill when the session matched none. */
@Composable
private fun WorkspaceChip(workspaceId: String?, workspace: WorkspaceInfo?) {
    val label = when {
        workspaceId == null -> "Quick chat"
        workspace != null -> listOfNotNull(workspace.emoji, workspace.name).joinToString(" ")
        // Scoped by a workspace whose row we haven't fetched (or that was
        // removed mid-session): show the id rather than pretend it's a quick chat.
        else -> workspaceId
    }
    val container = if (workspaceId == null) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val ink = if (workspaceId == null) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * One matching message.
 *
 * Collapsed it shows the highlighted snippet plus the role/time/seq footer.
 * Expanded ([peeked]) it shows the full message text the hit already carried
 * (capped daemon-side at 4 KB) and, on a user hit, the reply it drew — so a
 * peek costs no second round-trip. "Open at this message" is the explicit
 * second step: most searches are recall, not navigation.
 *
 * The row deliberately carries no session identity — the header above it does
 * in the cross-session list, and find-in-conversation has only one session.
 */
@Composable
fun SearchHitRow(
    hit: SearchHit,
    peeked: Boolean,
    onTogglePeek: () -> Unit,
    /** Carries the HIT, not just its session: a live hit opens the transcript
     *  at this message, which needs its seq and message_id. Dropping them here
     *  is what made "Open" land at the live end of the wrong part of a
     *  conversation. */
    onOpenSession: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
    /** True when this hit belongs to the read-only archive corpus. Changes the
     *  action's WORD, because it changes what the action does: an archive hit
     *  opens a transcript you can only read, never a session. */
    isArchive: Boolean = false,
) {
    val isUser = hit.role == SearchRoles.USER
    Surface(
        onClick = onTogglePeek,
        modifier = modifier.fillMaxWidth(),
        color = if (peeked) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (peeked) {
                PeekBody(hit = hit, isUser = isUser)
            } else {
                Text(
                    text = highlightedSnippet(hit.snippet),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoleDot(isUser = isUser)
                Spacer(Modifier.width(6.dp))
                Text(
                    // seq, not the timestamp, is the identity of a position in
                    // the stream (.claude/rules/session-ids.md rule 2). The
                    // time is metadata beside it.
                    text = "${if (isUser) "You" else "Agent"} · " +
                        "${formatRelativeTimestamp(hit.ts)} · #${hit.seq}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (peeked) {
                    TextButton(onClick = { onOpenSession(hit) }) {
                        // The archive has no anchored open — its viewer pages a
                        // whole transcript — so the word stays honest about
                        // what each action actually does.
                        Text(if (isArchive) "View transcript" else "Open at this message")
                    }
                }
            }
        }
    }
}

/** The expanded hit: full message text, then the reply it drew (user hits). */
@Composable
private fun PeekBody(hit: SearchHit, isUser: Boolean) {
    Text(
        // The hit's own text carries no markers — only `snippet` does — so the
        // full body renders plain. That's correct: the whole message is on
        // screen, so there is nothing to point at.
        text = hit.text.ifBlank { SnippetMarkers.strip(hit.snippet) },
        style = MaterialTheme.typography.bodyMedium,
    )
    val reply = hit.reply_text
    if (isUser && !reply.isNullOrBlank()) {
        Spacer(Modifier.size(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = "AGENT REPLIED",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(3.dp))
            Text(text = reply, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RoleDot(isUser: Boolean) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            ),
    )
}
