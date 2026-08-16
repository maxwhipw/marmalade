package app.marmalade.android.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.android.chat.messages.MessageStream

/**
 * Per-chat settings bottom sheet. Visual structure (2026-07-19 rework):
 * grouped rounded cards — quick actions (rename/search/branch/undo), voice,
 * display toggles, context usage with Compact/Clear as real
 * buttons — with the delete row and the de-emphasized session id as the
 * footer. All groups sit on `surfaceContainerLow` so they read as cards on
 * the sheet's surface in both modes.
 *
 * Model and thinking effort are deliberately NOT here — they are turn-scoped,
 * so the composer's own chips own them (ADR 0013). The Context section reuses
 * [ContextUsageDetails] (the composer's context-donut sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsSheet(
    showThinking: Boolean,
    onShowThinkingChange: (Boolean) -> Unit,
    showToolBlocks: Boolean,
    onShowToolBlocksChange: (Boolean) -> Unit,
    isMuted: Boolean = false,
    onMuteChange: (Boolean) -> Unit = {},
    /** Speak the assistant's replies in THIS chat. Session state, not an app
     *  preference (the assistant's own TTS is always on) — it lost its top-bar
     *  button in the ADR 0013 consolidation and lives here now. */
    ttsEnabled: Boolean = false,
    onTtsChange: (Boolean) -> Unit = {},
    /** Hands-free loop: speak the reply, then reopen the mic. Implies TTS, so
     *  the caller turns TTS on with it. */
    conversationMode: Boolean = false,
    onConversationModeChange: (Boolean) -> Unit = {},
    /** Rename this session. Null hides the row. */
    onRenameSession: (() -> Unit)? = null,
    sessionKey: String?,
    onSearchClick: () -> Unit,
    /** Branch the whole chat into a new session from its end (session.fork,
     *  no cut point) — T2 #3. Null hides the row (no session open). */
    onBranchChat: (() -> Unit)? = null,
    onCompactClick: () -> Unit,
    /** True when the daemon advertises the "undo" feature (session.undo, T2
     *  #6). Together with a non-null [onUndo] this shows the "Undo last turn"
     *  row. */
    undoSupported: Boolean = false,
    /** Pop the last completed turn (session.undo). Null hides the row (no
     *  session open). Gated additionally on [undoSupported]. */
    onUndo: (() -> Unit)? = null,
    onClearHistory: () -> Unit,
    /** Delete the whole session (server + local) and leave the chat. Null
     *  hides the row — e.g. for a session that isn't user-deletable. */
    onDeleteSession: (() -> Unit)? = null,
    /** Latest gateway-reported usage snapshot for the bound session. Drives
     *  the Context breakdown; null hides it (no snapshot yet). */
    usage: MessageStream.UsageDelta?,
    /** Copy the session id to the clipboard. Hoisted to the call site because
     *  the clipboard API is platform-owned (`:app`'s `ClipboardExt`) — this
     *  module compiles against Compose Multiplatform, which has no equivalent. */
    onCopySessionKey: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Content is taller than the sheet on Pixel-class screens
                // (420 dpi): without this the footer — Compact/Clear, cost,
                // session key, Delete — is clipped and unreachable.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Quick actions ────────────────────────────────────────
            SettingsCard {
                if (onRenameSession != null) {
                    ActionRow(
                        icon = Icons.Outlined.DriveFileRenameOutline,
                        label = "Rename session",
                        onClick = {
                            onDismiss()
                            onRenameSession()
                        },
                    )
                    CardDivider()
                }
                ActionRow(
                    icon = Icons.Default.Search,
                    label = "Search in chat",
                    onClick = {
                        onDismiss()
                        onSearchClick()
                    },
                )
                if (onBranchChat != null) {
                    CardDivider()
                    ActionRow(
                        icon = Icons.AutoMirrored.Outlined.CallSplit,
                        label = "Branch this chat",
                        onClick = {
                            onDismiss()
                            onBranchChat()
                        },
                    )
                }
                if (undoSupported && onUndo != null) {
                    CardDivider()
                    ActionRow(
                        icon = Icons.AutoMirrored.Outlined.Undo,
                        label = "Undo last turn",
                        subtitle = "Conversation only — file edits are not reverted",
                        onClick = {
                            onDismiss()
                            onUndo()
                        },
                    )
                }
            }

            // No Model / Thinking section: the composer's own chips are the
            // one home for both (ADR 0013 puts turn-scoped controls there),
            // and a second copy two taps away was redundant — the maintainer, 2026-07-25.
            // Both pickers still open from those chips.

            // ── Voice ────────────────────────────────────────────────
            // Both are per-session, both used to be top-bar icons. Conversation
            // mode implies TTS (a hands-free loop with silent replies is a dead
            // loop), so the caller enables TTS alongside it.
            Column {
                SectionLabel("Voice")
                SettingsCard {
                    ToggleRow(
                        label = "Speak replies",
                        checked = ttsEnabled,
                        onCheckedChange = onTtsChange,
                    )
                    CardDivider()
                    ToggleRow(
                        label = "Conversation mode",
                        subtitle = "Speak the reply, then reopen the mic",
                        checked = conversationMode,
                        onCheckedChange = onConversationModeChange,
                    )
                }
            }

            // ── Display toggles ──────────────────────────────────────
            Column {
                SectionLabel("Display")
                SettingsCard {
                    ToggleRow(
                        label = "Show thinking blocks",
                        checked = showThinking,
                        onCheckedChange = onShowThinkingChange,
                    )
                    CardDivider()
                    ToggleRow(
                        label = "Show tool use",
                        checked = showToolBlocks,
                        onCheckedChange = onShowToolBlocksChange,
                    )
                    CardDivider()
                    // Notifications toggle (positive framing: ON = not muted)
                    ToggleRow(
                        label = "Notifications",
                        checked = !isMuted,
                        onCheckedChange = { onMuteChange(!it) },
                    )
                }
            }

            // ── Context ──────────────────────────────────────────────
            Column {
                SectionLabel("Context")
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (usage != null) {
                            ContextUsageDetails(usage = usage)
                        } else {
                            Text(
                                text = "No usage reported yet — stats appear " +
                                    "after the next completed turn.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    onDismiss()
                                    onCompactClick()
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Text("Compact", fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = { showClearConfirmDialog = true },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                ),
                            ) {
                                Text(
                                    "Clear",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            // ── Footer: delete + de-emphasized session id ────────────
            Column {
                if (onDeleteSession != null) {
                    ActionRow(
                        icon = Icons.Outlined.Delete,
                        label = "Delete session",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showDeleteConfirmDialog = true },
                    )
                }
                if (sessionKey != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = sessionKey,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onCopySessionKey(sessionKey) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy session ID",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // Model picker — same sheet the composer's model chip opens. Rendered as a
    // sibling (like the dialogs below) so it stacks above the settings sheet
    // and returns to it on dismiss.

    // Clear history confirmation dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear chat history?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        onClearHistory()
                        onDismiss()
                    },
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Delete session confirmation dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete session?") },
            text = { Text("This removes the session and its history. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteSession?.invoke()
                        onDismiss()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Rounded group container — rows inside read as one card on the sheet. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** Inset divider between rows inside a [SettingsCard]. */
@Composable
private fun CardDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 52.dp),
    )
}

/** Icon + label (+ optional subtitle) tappable row for cards and the footer. */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (tint == MaterialTheme.colorScheme.onSurface) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                tint
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            ),
        )
    }
}
