package app.marmalade.android.ui.voice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marmalade.android.service.AssistantState
import app.marmalade.android.service.VoiceMessage
import app.marmalade.android.ui.chat.DotPulse
import app.marmalade.android.ui.theme.marmaladeColors

// =============================================================================
// Marmalade Voice Popup
// =============================================================================
//
// Extracted from MarmaladeVoiceSession.kt during V1 redesign.
//
// Visual contract — see `marmalade-design` skill for token sources:
//   • Surface: scheme.surface (stone-deep `#1C1917` dark; warm-stone `#FFF7ED`
//     light) — neutral-warm, never amber-brown for dark.
//   • Mic accent: orange `#F97316` in light, rich brown `#422006` + toast
//     `#FED7AA` foreground in dark. Orange is precious in light mode and
//     gets nowhere in dark mode (anti-pattern: Halloween).
//   • Voice bubbles: marmaladeColors.userBubble (Peach) for the user side,
//     marmaladeColors.assistantBubble (Cream → Soft pastel in dark) for
//     the assistant — same tokens the main ChatScreen uses.
//
// Logic is intentionally untouched: AssistantState transitions, mic clicks,
// auto-listen toggle, and the dismiss path (scrim + close + back) all
// forward to the same callbacks MarmaladeVoiceSession already wires.

// ── Test tags ───────────────────────────────────────────────────────────────

object VoicePopupTags {
    const val ROOT = "voice_popup"
    const val MIC_BUTTON = "voice_mic_button"
    const val STOP_BUTTON = "voice_stop_button"
    const val CLOSE_BUTTON = "voice_close_button"
    const val SCRIM = "voice_scrim"
    const val AUTO_LISTEN = "voice_auto_listen"
    const val EMPTY_STATE = "voice_empty_state"
    const val LISTENING_HINT = "voice_listening_hint"
    const val MASCOT = "voice_mascot"
}

// ── Public API ──────────────────────────────────────────────────────────────

/**
 * The bottom-sheet voice popup. Hosts the mascot, an empty prompt or
 * scrollable mini chat, and the mic + auto-listen controls.
 *
 * @param state Drives mascot expression and mic visual state. (No textual
 *   state chip — the mascot + mic glow + thinking mini-bubble carry the
 *   state; a bottom "Thinking…" chip was redundant with the mini-bubble,
 *   maintainer 2026-07-04.)
 * @param messages User/assistant voice exchange — empty list shows the
 *   "tap the mic" empty state instead.
 * @param autoListenEnabled Current toggle value; popup is purely view-state,
 *   the actual recognizer restart loop lives in MarmaladeVoiceSession.
 * @param isVisible Whether the sheet is shown — drives the slide animation.
 * @param errorMessage If non-null, surfaces a banner-row above the chat.
 * @param listeningHint If non-null, a muted caption shown ONLY while
 *   listening — used for patient mode's termination word ("Say “over” to
 *   send"), which was otherwise invisible: the user had no way to know what
 *   word ends the utterance. NOT a state chip (those stay removed, maintainer
 *   2026-07-04) — it names an action, not the state.
 * @param onMicClick Tap on the mic FAB OR the stop affordance (the session
 *   already routes both through `onMicButtonClicked`).
 * @param onAutoListenToggle Switch flip — session persists & re-arms STT.
 * @param onDismiss Scrim tap, X button, or system back — all dismiss.
 */
@Composable
fun VoicePopupUI(
    state: AssistantState,
    messages: List<VoiceMessage>,
    autoListenEnabled: Boolean,
    isVisible: Boolean,
    errorMessage: String?,
    onMicClick: () -> Unit,
    onAutoListenToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    listeningHint: String? = null,
) {
    // System back dismisses just like scrim/close — the OS popup overlay
    // previously had no back handler, so back was a no-op. Wired here so
    // the contract the maintainer described ("back press dismisses") holds.
    if (isVisible) {
        BackHandler(onBack = onDismiss)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(VoicePopupTags.ROOT),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (isVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .testTag(VoicePopupTags.SCRIM)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onDismiss() },
            )
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(200)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)),
        ) {
            VoiceSheet(
                state = state,
                messages = messages,
                autoListenEnabled = autoListenEnabled,
                errorMessage = errorMessage,
                listeningHint = listeningHint,
                onMicClick = onMicClick,
                onAutoListenToggle = onAutoListenToggle,
                onDismiss = onDismiss,
            )
        }
    }
}

// ── Sheet shell ─────────────────────────────────────────────────────────────

@Composable
private fun VoiceSheet(
    state: AssistantState,
    messages: List<VoiceMessage>,
    autoListenEnabled: Boolean,
    errorMessage: String?,
    listeningHint: String?,
    onMicClick: () -> Unit,
    onAutoListenToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.4f),
        // Generous radius — friendly, per the shape scale (16/20/28).
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            DragHandle()
            TopBar(errorMessage = errorMessage, onDismiss = onDismiss)

            // Messages own the top of the sheet now — the mascot moved down
            // to the bottom-left controls row (maintainer 2026-07-01) so the
            // conversation gets the full height instead of a 72dp header band.
            if (messages.isEmpty()) {
                EmptyVoicePrompt(modifier = Modifier.weight(1f))
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
                MiniChatArea(
                    messages = messages,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                )
            }

            if (listeningHint != null && state == AssistantState.LISTENING) {
                Text(
                    text = listeningHint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .testTag(VoicePopupTags.LISTENING_HINT),
                )
            }

            BottomControlsRow(
                state = state,
                autoListenEnabled = autoListenEnabled,
                onMicClick = onMicClick,
                onAutoListenToggle = onAutoListenToggle,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun TopBar(errorMessage: String?, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.marmaladeColors.bannerError,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(VoicePopupTags.CLOSE_BUTTON),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Empty state ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyVoicePrompt(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(VoicePopupTags.EMPTY_STATE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Tap the mic to start",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "I'll listen, then reply out loud.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.marmaladeColors.chatTextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Mini chat ───────────────────────────────────────────────────────────────

@Composable
private fun MiniChatArea(
    messages: List<VoiceMessage>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(messages) { message ->
            MiniChatBubble(message)
        }
    }
}

@Composable
private fun MiniChatBubble(message: VoiceMessage) {
    val isThinking = !message.isUser && message.isPartial && message.text == "..."
    val bubbleColor = if (message.isUser) {
        MaterialTheme.marmaladeColors.userBubble
    } else {
        MaterialTheme.marmaladeColors.assistantBubble
    }
    val textColor = if (message.isUser) {
        // Paired ink for the peach user bubble — matches MessageBubble.
        MaterialTheme.marmaladeColors.onUserBubble
    } else {
        // Dark ink on the light soft-pastel/cream assistant bubble.
        MaterialTheme.marmaladeColors.onAssistantBubble
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // No per-message avatar: the header mascot already identifies the
        // assistant, so a second small jar next to every bubble was
        // redundant clutter (maintainer 2026-06-30).
        Surface(
            shape = RoundedCornerShape(
                topStart = if (message.isUser) 16.dp else 4.dp,
                topEnd = if (message.isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 260.dp),
        ) {
            if (isThinking) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DotPulse()
                    Text(
                        text = "Thinking…",
                        color = textColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ── Bottom controls ─────────────────────────────────────────────────────────

@Composable
private fun BottomControlsRow(
    state: AssistantState,
    autoListenEnabled: Boolean,
    onMicClick: () -> Unit,
    onAutoListenToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mascot (bottom-left) — the live-drawn jar with the full suite
        // animation vocabulary (lid lift, waves, bubbles, blink). Bigger and
        // halo-free per the maintainer, 2026-07-04; it draws its own ground shadow.
        JarMascot(
            state = state,
            size = 84.dp,
            modifier = Modifier.testTag(VoicePopupTags.MASCOT),
        )

        // The morph pill — single center control that reshapes per state
        // (mockup: internal design notes, "Morph Pill v2").
        MorphPillButton(state = state, onClick = onMicClick)

        // Auto-listen toggle (right) — a bordered pill toggle BUTTON like the
        // mockup's .auto-pill, not a Switch (maintainer 2026-07-04). Off = outline +
        // dim label; on = Toast selected-chip (same in both modes per the
        // design scheme's chip rules).
        AutoPillToggle(
            checked = autoListenEnabled,
            onCheckedChange = onAutoListenToggle,
        )
    }
}

@Composable
private fun AutoPillToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(16.dp),
        color = if (checked) Color(0xFFFED7AA) else Color.Transparent,
        contentColor = if (checked) {
            Color(0xFF7C2D12)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (checked) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier.testTag(VoicePopupTags.AUTO_LISTEN),
    ) {
        Text(
            text = "Auto",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
