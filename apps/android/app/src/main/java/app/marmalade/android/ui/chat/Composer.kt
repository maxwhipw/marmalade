package app.marmalade.android.ui.chat

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.ModelCatalogEntry
import app.marmalade.android.chat.OutgoingAttachment
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch

/**
 * Bottom composer: multiline text input flanked by attachment / mic on
 * the left and a send-or-abort affordance on the right. The button
 * swaps to a red "Stop" square when [pendingRunCount] is positive so
 * the user can cancel an in-flight turn.
 *
 * Drafts persist via [ChatController.setDraft] on focus loss and at
 * disposal; the parent restores via [ChatController.getDraft] on
 * session change.
 *
 * Visual aesthetic: rounded-rect border around the whole composer with
 * the text field on top and a row of action buttons (paperclip on the
 * left, mic + send on the right) below. (Inherited from the deleted
 * legacy `ChatInputBar`.)
 */
@Composable
fun Composer(
    chat: ChatController,
    text: String,
    onTextChange: (String) -> Unit,
    pendingRunCount: Int,
    snackbarHostState: SnackbarHostState,
    onSend: (String) -> Unit,
    /** Stage the composer payload in the send-queue instead of sending —
     *  invoked when the user submits while a turn is running. The caller
     *  owns attachments the same way it does for [onSend]. */
    onQueue: (String) -> Unit = {},
    /** Steer the RUNNING turn (session.steer, T2 #6): inject the composer
     *  text as mid-turn guidance. Distinct from [onQueue] (which defers to a
     *  next turn) — this sends INTO the turn in flight. Surfaced as a
     *  dedicated button shown only while running. */
    onSteer: (String) -> Unit = {},
    onMicTap: (() -> Unit)?,
    onMicLongPress: (() -> Unit)?,
    /** Staged attachments shown as chips above the input. State is hoisted
     *  (ChatScreen owns it, like the composer text) so the send path can
     *  read + clear it alongside the text. Add/remove callbacks (not a
     *  whole-list setter) so concurrent staging coroutines can't clobber
     *  each other with stale captures. */
    attachments: List<OutgoingAttachment> = emptyList(),
    onAddAttachment: (OutgoingAttachment) -> Unit = {},
    onRemoveAttachment: (OutgoingAttachment) -> Unit = {},
    /** True while inline dictation is capturing — tints the mic to signal
     *  it's actively listening. */
    isMicActive: Boolean = false,
    /** Invoked when the user submits a slash command that opens the rename
     *  dialog (e.g. bare /title). Composer surfaces this to the caller
     *  (ChatScreen) so the dialog renders at the chat-screen level. */
    onOpenRenameDialog: (() -> Unit)? = null,
    /** Invoked when the user submits a slash command that opens the
     *  session picker (/sessions, /switch, /resume). Caller routes to
     *  the Sessions tab. */
    onOpenSessionPicker: (() -> Unit)? = null,
    /** True when the connected daemon advertises the "attachments" server
     *  feature. Gates the paperclip button and the staged-attachment chips
     *  row — the daemon has no attachment method yet, so this is false
     *  today by design. The transport layer (AttachmentStaging,
     *  PromptTransport, OutboxDrainer, MarmaladeRpc's attach methods) keeps
     *  working underneath; only the entry point into it is hidden. */
    attachmentsSupported: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var hasFocus by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Attachment picking. OpenMultipleDocuments covers images AND arbitrary
    // files in one system picker; each pick is staged (copied into
    // filesDir/attachments, large images recompressed) off the main thread
    // and its chip appears when staging lands. SAF grants are transient, so
    // staging happens NOW, not at send time — see AttachmentStaging.
    var stagingCount by remember { mutableStateOf(0) }
    val pickDocuments = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            stagingCount++
            scope.launch {
                try {
                    onAddAttachment(AttachmentStaging.stage(context, uri))
                } catch (t: Throwable) {
                    snackbarHostState.showSnackbar(
                        t.message ?: "Could not attach that file",
                    )
                } finally {
                    stagingCount--
                }
            }
        }
    }

    // Paseo-style chips for thinking + model at the bottom-left of the
    // composer. Each opens a small modal bottom sheet (the existing
    // ReasoningPickerSheet / ModelPickerSheet) instead of stuffing the
    // full ChatSettingsSheet under the gear icon.
    val thinkingLevel by chat.thinkingLevel.collectAsStateWithLifecycle()
    val currentModel by chat.currentModel.collectAsStateWithLifecycle()
    val availableModels by chat.models.collectAsStateWithLifecycle()
    val defaultModel by chat.defaultModel.collectAsStateWithLifecycle()
    val effortLevels by chat.efforts.collectAsStateWithLifecycle()
    // The daemon's new-session default model resolved to a human label ("Opus
    // 4.8"), falling back to the raw id. Null when the daemon reported no
    // default (older daemons) → the chip/picker keep their bare "Default" text.
    val defaultModelLabel = resolveDefaultModelLabel(defaultModel, availableModels)
    val sessionOutputTokens by chat.sessionOutputTokensTotal.collectAsStateWithLifecycle()
    val sessionUsage by chat.sessionUsage.collectAsStateWithLifecycle()
    var showThinkingSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }

    // Slash-command completion is a static prefix filter over the client's
    // own command table. The fork's server-backed live completion
    // (commands.catalog / complete.slash) was removed with the marmaladed
    // gap triage (2026-07-11) — the daemon exposes no slash surface.
    val slashCommands = remember(text) {
        if (text.startsWith("/")) filterSlashCommands(text) else emptyList()
    }

    val showSlashPopup = slashCommands.isNotEmpty()

    // Persist draft on focus loss + on dispose.
    LaunchedEffect(hasFocus, text) {
        if (!hasFocus) chat.setDraft(text)
    }
    DisposableEffect(Unit) {
        onDispose { chat.setDraft(text) }
    }

    val canSubmit = text.isNotBlank() || attachments.isNotEmpty()
    val isRunning = pendingRunCount > 0

    val submit: () -> Unit = submit@{
        // While a turn runs: an empty composer's button is Stop; with a
        // payload it QUEUES instead (desktop busyAction, composer/index.tsx:
        // 245 — queue when there's a payload, stop otherwise). Slash
        // commands still execute immediately even while busy (dispatcher
        // runs before the queue branch below).
        if (isRunning && !canSubmit) {
            chat.abort()
            return@submit
        }
        if (!canSubmit) return@submit
        val outgoing = text.trim()

        // With attachments staged, everything is a prompt — desktop skips
        // slash interception the same way (use-prompt-actions.ts:1334).
        if (attachments.isNotEmpty()) {
            if (isRunning) onQueue(outgoing) else onSend(outgoing)
            onTextChange("")
            keyboard?.hide()
            return@submit
        }

        // Intercept client-side slash commands BEFORE they reach the gateway.
        // SlashCommandDispatcher maps every known command to a handler kind:
        //   Handled     → clear composer (command ran client-side)
        //   Unavailable → show snackbar, clear composer (don't send to server)
        //   NotASlashCommand → fall through to onSend (server handles it)
        when (val slashResult = app.marmalade.android.chat.SlashCommandDispatcher.dispatch(outgoing, chat)) {
            is app.marmalade.android.chat.SlashCommandDispatcher.Result.Handled -> {
                onTextChange("")
                keyboard?.hide()
                return@submit
            }
            is app.marmalade.android.chat.SlashCommandDispatcher.Result.Unavailable -> {
                scope.launch { snackbarHostState.showSnackbar(slashResult.message) }
                onTextChange("")
                keyboard?.hide()
                return@submit
            }
            is app.marmalade.android.chat.SlashCommandDispatcher.Result.UsageError -> {
                // Preserve composer text so the user can append the missing arg
                // (distinct from Unavailable, which clears text).
                scope.launch { snackbarHostState.showSnackbar(slashResult.message) }
                return@submit
            }
            is app.marmalade.android.chat.SlashCommandDispatcher.Result.ShowRenameDialog -> {
                onOpenRenameDialog?.invoke() ?: scope.launch {
                    snackbarHostState.showSnackbar("Rename not available here")
                }
                onTextChange("")
                keyboard?.hide()
                return@submit
            }
            is app.marmalade.android.chat.SlashCommandDispatcher.Result.OpenSessionPicker -> {
                onOpenSessionPicker?.invoke() ?: scope.launch {
                    snackbarHostState.showSnackbar("Session picker not available here")
                }
                onTextChange("")
                keyboard?.hide()
                return@submit
            }
            is app.marmalade.android.chat.SlashCommandDispatcher.Result.NotASlashCommand -> {
                // Fall through to onSend/onQueue below.
            }
        }

        if (isRunning) onQueue(outgoing) else onSend(outgoing)
        onTextChange("")
        keyboard?.hide()
    }

    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    // Queued-while-running prompts render as editable chips just above the
    // composer (desktop QueuePanel parity). The panel talks to the
    // controller directly; edit loads the entry back into the composer.
    val queuedPrompts by chat.boundQueue.collectAsStateWithLifecycle()

    // Wrap in Column so the slash popup sits above the input surface.
    Column(modifier = modifier.fillMaxWidth()) {
        if (queuedPrompts.isNotEmpty()) {
            QueuePanel(
                entries = queuedPrompts,
                onSendNow = { chat.sendQueuedNow(it) },
                onDelete = { chat.removeQueued(it) },
                onEdit = { entry ->
                    if (text.isBlank()) {
                        onTextChange(entry.text)
                        entry.attachments.forEach(onAddAttachment)
                        chat.removeQueued(entry.id)
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar("Clear the composer first to edit a queued message")
                        }
                    }
                },
            )
        }
        if (showSlashPopup) {
            SlashCommandPopup(
                commands = slashCommands,
                onCommandSelected = { command ->
                    val withTrailingSpace = if (command.parameters != null) "${command.command} " else command.command
                    onTextChange(withTrailingSpace)
                },
                // The popup is purely derived from `text`; clearing the
                // composer's slash text is what hides it.
                onDismiss = { onTextChange("") },
            )
        }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, outlineColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            if (attachmentsSupported && (attachments.isNotEmpty() || stagingCount > 0)) {
                AttachmentChipsRow(
                    attachments = attachments,
                    stagingCount = stagingCount,
                    onRemove = { att ->
                        AttachmentStaging.discard(att)
                        onRemoveAttachment(att)
                    },
                )
            }
            InputTextField(
                text = text,
                onTextChange = onTextChange,
                onSend = submit,
                hasFocus = hasFocus,
                onFocusChanged = { hasFocus = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )

            // Action row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Paperclip attachment button — leftmost. Opens the system
                // document picker; picked images/files stage as chips above
                // the input and upload at drain time. Hidden until the
                // daemon advertises the "attachments" server feature (it
                // defines none yet) — attaching would otherwise degrade
                // with a toast the user has no way to anticipate.
                if (attachmentsSupported) {
                    IconButton(
                        onClick = { pickDocuments.launch(arrayOf("*/*")) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Add attachment",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Thinking chip — Paseo-style brain icon + level word. Tap
                // opens the existing ReasoningPickerSheet.
                ThinkingChip(
                    level = thinkingLevel,
                    onClick = { showThinkingSheet = true },
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Model chip — chip/memory icon + model id truncated. Tap
                // opens ModelPickerSheet. Wrapped in a weight(1f) box so the
                // chip is the ONLY element that compresses when the row is
                // crowded (it hugs content at CenterStart; the box's slack
                // pins the right-hand cluster to the edge and keeps the mic /
                // send buttons from being starved of width).
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    ModelChip(
                        // Fallback chain: adopted/picked model label → raw id →
                        // daemon default label → bare "Default". See
                        // [composerModelChipLabel].
                        label = composerModelChipLabel(
                            currentModel, availableModels, defaultModelLabel,
                        ),
                        onClick = { showModelSheet = true },
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Context-utilization donut (context_percent from session.info
                // usage). Tap opens the details sheet. Falls back to the old
                // session-level output token tally when the provider doesn't
                // report a context window (desktop's setCurrentUsage badge,
                // use-message-stream.ts:899-901).
                val contextPercent = sessionUsage?.contextPercent
                if (contextPercent != null) {
                    ContextDonut(
                        percent = contextPercent,
                        onClick = { showContextSheet = true },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                } else {
                    sessionOutputTokens?.let { total ->
                        Text(
                            text = formatTokenCount(total),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }

                if (onMicTap != null) {
                    MicButton(
                        onTap = onMicTap,
                        onLongPress = onMicLongPress ?: {},
                        isActive = isMicActive,
                        modifier = Modifier.size(40.dp),
                    )
                }

                // Steer (session.steer, T2 #6): while a turn runs and there's a
                // payload, offer a dedicated "send into the running turn"
                // action beside the queue/stop pill. Sends the composer text as
                // mid-turn guidance; the main pill keeps its queue/stop role.
                // Gated on non-blank TEXT (not canSubmit): steer never carries
                // attachments, so an attachments-only payload must not light a
                // button whose tap would no-op. The composer is cleared by the
                // caller ON SUCCESS (onSteer), so a rejected steer keeps the
                // draft.
                if (isRunning && text.isNotBlank()) {
                    IconButton(
                        onClick = {
                            onSteer(text.trim())
                            keyboard?.hide()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Steer the running reply",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Send / Stop pill. Always visible at fixed size — the
                // legacy bar slid it in/out on submit-availability, but
                // keeping it visible matches the wiring here (the button
                // is the only way to abort an in-flight run).
                Box(modifier = Modifier.size(32.dp)) {
                    SendStopButton(
                        canSubmit = canSubmit,
                        isRunning = isRunning,
                        onClick = submit,
                    )
                }
            }
        }
    }
    } // end outer Column

    if (showThinkingSheet) {
        // Bounds follow the model the NEXT turn will run on: the session's own
        // pick when it has one, else the daemon's new-session default (which is
        // exactly what the chip already renders). An unknown/unbounded model
        // leaves both null and the sheet is the one that shipped before bounds.
        val boundingModel = remember(currentModel, defaultModel, availableModels) {
            val id = currentModel ?: defaultModel
            availableModels.firstOrNull { it.id == id }
        }
        ReasoningPickerSheet(
            currentEffort = thinkingLevel,
            levels = effortLevels,
            onSelect = {
                chat.setThinkingLevel(it)
                showThinkingSheet = false
            },
            onDismiss = { showThinkingSheet = false },
            effortMin = boundingModel?.effortMin,
            effortMax = boundingModel?.effortMax,
            modelLabel = boundingModel?.name ?: "this model",
        )
    }

    if (showContextSheet) {
        sessionUsage?.let { usage ->
            ContextDetailsSheet(
                usage = usage,
                onDismiss = { showContextSheet = false },
            )
        } ?: run { showContextSheet = false }
    }

    if (showModelSheet) {
        ModelPickerSheet(
            models = availableModels,
            currentModelId = currentModel,
            defaultModelLabel = defaultModelLabel,
            onSelect = { modelId ->
                chat.setCurrentModel(modelId)
                showModelSheet = false
            },
            onSelectDefault = {
                chat.clearCurrentModel()
                showModelSheet = false
            },
            onDismiss = { showModelSheet = false },
        )
    }
}

/**
 * Horizontal strip of staged-attachment chips above the input field.
 * Image chips render a thumbnail from the staged local file; file chips show
 * an icon + name. Each chip carries an ✕ that discards the staged file. A
 * spinner chip trails while picks are still staging (copy/recompress).
 */
@Composable
private fun AttachmentChipsRow(
    attachments: List<OutgoingAttachment>,
    stagingCount: Int,
    onRemove: (OutgoingAttachment) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { att ->
            if (att.kind == OutgoingAttachment.KIND_IMAGE) {
                ImageAttachmentChip(att, onRemove)
            } else {
                FileAttachmentChip(att, onRemove)
            }
        }
        if (stagingCount > 0) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun ImageAttachmentChip(
    attachment: OutgoingAttachment,
    onRemove: (OutgoingAttachment) -> Unit,
) {
    Box {
        AsyncImage(
            model = File(attachment.path),
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        RemoveChipButton(
            onClick = { onRemove(attachment) },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun FileAttachmentChip(
    attachment: OutgoingAttachment,
    onRemove: (OutgoingAttachment) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Article,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Spacer(modifier = Modifier.width(2.dp))
            RemoveChipButton(onClick = { onRemove(attachment) })
        }
    }
}

@Composable
private fun RemoveChipButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
        modifier = modifier
            .padding(2.dp)
            .size(18.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove attachment",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

private fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${"%.1f".format(tokens / 1_000_000.0)}M"
    tokens >= 1_000 -> "${tokens / 1_000}k"
    else -> tokens.toString()
} + " out"

/**
 * Resolve the daemon's new-session default model id to a human label ("Opus
 * 4.8"), falling back to the raw id when the catalog doesn't list it. Null in →
 * null out (older daemons advertise no default → callers keep "Default").
 */
internal fun resolveDefaultModelLabel(
    defaultModel: String?,
    availableModels: List<ModelCatalogEntry>,
): String? = defaultModel?.let { d ->
    availableModels.firstOrNull { it.id == d }?.name ?: d
}

/**
 * The composer model chip's label fallback chain, for a session that may not
 * exist yet:
 *   1. [currentModel]'s catalog label — an adopted (session.info) or user-picked
 *      model. Non-null [currentModel] always wins, so a live session's real
 *      model can never be masked by the daemon default.
 *   2. the raw [currentModel] id, for a model the catalog didn't list.
 *   3. [defaultModelLabel] — the daemon's advertised new-session default, shown
 *      only in the "no pick / no adopted model" placeholder slot.
 *   4. the bare "Default" (no pick and no advertised default — old daemon).
 */
internal fun composerModelChipLabel(
    currentModel: String?,
    availableModels: List<ModelCatalogEntry>,
    defaultModelLabel: String?,
): String =
    availableModels.firstOrNull { it.id == currentModel }?.name
        ?: currentModel?.takeIf { it.isNotBlank() }
        ?: defaultModelLabel
        ?: "Default"

@Composable
private fun ThinkingChip(level: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = "Thinking level",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = level.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .widthIn(max = 140.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = "Model",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InputTextField(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    hasFocus: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }

    LaunchedEffect(text) {
        if (text != textFieldValue.text) {
            textFieldValue = TextFieldValue(text, TextRange(text.length))
        }
    }

    Box(
        modifier = modifier
            .heightIn(min = 20.dp)
            .onFocusChanged { onFocusChanged(it.isFocused) },
    ) {
        if (textFieldValue.text.isEmpty()) {
            Text(
                text = "Message...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                onTextChange(newValue.text)
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 6,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(
                onSend = { onSend() },
            ),
        )
    }
}

@Composable
private fun MicButton(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current

    // Pulse the mic between full and dim accent while listening, so it reads
    // as "live" rather than just "toggled on". A single infinite transition
    // drives the icon alpha; idle mics don't animate.
    val pulse = rememberInfiniteTransition(label = "micPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "micPulseAlpha",
    )

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongPress()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = if (isActive) {
                "Listening — tap to stop"
            } else {
                "Voice input (hold for voice popup)"
            },
            // While dictating, tint the mic to the accent and pulse its alpha
            // so it clearly reads as live. Idle: static, dim.
            tint = if (isActive) {
                MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SendStopButton(
    canSubmit: Boolean,
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    // Three modes (desktop busyAction): running + payload = QUEUE,
    // running + empty = STOP, idle = SEND.
    val queueMode = isRunning && canSubmit
    val containerColor = when {
        queueMode -> MaterialTheme.colorScheme.tertiary
        isRunning -> MaterialTheme.colorScheme.error
        canSubmit -> MaterialTheme.colorScheme.primary
        // Disabled: the M3 neutral treatment (a faint on-surface fill), NOT a
        // translucent primary — the latter blends to a muddy dark over the
        // dark composer and reads as "accent under 50% black". Keep it a
        // clean, subtle disc that's lighter than its icon.
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    Surface(
        onClick = {
            if (isRunning || canSubmit) onClick()
        },
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when {
                    queueMode -> Icons.AutoMirrored.Filled.PlaylistAdd
                    isRunning -> Icons.Default.Stop
                    else -> Icons.AutoMirrored.Filled.Send
                },
                contentDescription = when {
                    queueMode -> "Queue message"
                    isRunning -> "Stop"
                    else -> "Send"
                },
                tint = when {
                    queueMode -> MaterialTheme.colorScheme.onTertiary
                    isRunning -> MaterialTheme.colorScheme.onError
                    canSubmit -> MaterialTheme.colorScheme.onPrimary
                    // Disabled icon: brighter than the faint disc so the arrow
                    // still reads (M3 disabled-content alpha), never darker.
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
