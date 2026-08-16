package app.marmalade.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.flow.collect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.chat.messages.text as messageText
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.speech.TTSManager
import app.marmalade.android.speech.TTSSpeaker
import app.marmalade.android.ui.setPlainText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How long the anchored bubble keeps its focus ring when the user doesn't
 *  scroll. Long enough to find the eye, short enough not to become chrome. */
private const val ANCHOR_HIGHLIGHT_HOLD_MS = 2_500L

/** How long the "jumped N messages back" pill stays up. */
private const val ANCHOR_PILL_HOLD_MS = 3_000L

/**
 * Top-level chat screen. Owns:
 *
 *  - Top bar (existing [ChatTopBar]) with the session title.
 *  - Message list ([ChatMessageList]) — flattened parts → rows.
 *  - Pending-prompt stack ([PromptCards]) above the composer.
 *  - Composer ([Composer]) with mic + send/abort.
 *  - Snackbar host for transient failures.
 *  - Image viewer ([ChatImageViewer]) on tap.
 *
 * Everything else is state-hoisted to [ChatController] — this composable
 * is just routing flows in and lifting callbacks out.
 */
@Composable
fun ChatScreen(
    chat: ChatController,
    sessionName: String = "Chat",
    showBackArrow: Boolean = false,
    onBackPressed: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    isInlineSTTActive: Boolean = false,
    inlineSTTPartialText: String = "",
    onMicTap: (() -> Unit)? = null,
    onMicLongPress: (() -> Unit)? = null,
    isConnected: Boolean = true,
    onStatusClick: (() -> Unit)? = null,
    /** Opens the session switcher from the top-bar title (ADR 0013). Null
     *  leaves the old behaviour: the title routes to [onStatusClick]. */
    onTitleClick: (() -> Unit)? = null,
    /** Opens the navigation drawer from the top-left handle (ADR 0013). */
    onMenuClick: (() -> Unit)? = null,
    /** Opens the session tool panel from the right edge (ADR 0013). */
    onPanelClick: (() -> Unit)? = null,
    ttsManager: TTSSpeaker? = null,
    /** Invoked when the user submits /sessions, /switch, or /resume. Host
     *  should route to the Sessions tab / sidebar. If null, the dispatcher
     *  shows a "not available here" snackbar. */
    onOpenSessionPicker: (() -> Unit)? = null,
    /** Invoked after the user deletes the current session from the settings
     *  sheet. Host should leave the chat (e.g. pop the back stack). */
    onSessionDeleted: (() -> Unit)? = null,
    /** True when the connected daemon advertises the "attachments" server
     *  feature. Gates the composer's paperclip/attach-chips UI — the daemon
     *  has no attachment method yet, so this is false today by design. */
    attachmentsSupported: Boolean = false,
    /** True when the daemon advertises the "undo" server feature (session.undo,
     *  T2 #6). Gates the "Undo last turn" action in the settings sheet. */
    undoSupported: Boolean = false,
    /** True when the daemon advertises the "search" server feature. Upgrades
     *  "Search in chat" from a local substring filter over the messages Room
     *  happens to hold to a real `search.messages` query at scope-of-one — which
     *  is the point, since the sessions worth searching are the ones too long to
     *  have fully hydrated. False falls back to the local filter. */
    searchSupported: Boolean = false,
    /** RPC handle for find-in-conversation. Null (or [searchSupported] false)
     *  keeps the local filter. */
    searchRpc: app.marmalade.android.rpc.MarmaladeRpc? = null,
) {
    val messages by chat.messages.collectAsStateWithLifecycle()
    // Scoped to the bound session — a background session's clarify must NOT
    // pop its card into this chat (it gets an OS notification instead).
    val pendingPrompts by chat.boundPendingPrompts.collectAsStateWithLifecycle()
    val pendingRunCount by chat.pendingRunCount.collectAsStateWithLifecycle()
    val isStreaming by chat.isStreaming.collectAsStateWithLifecycle()
    val isCompacting by chat.isCompacting.collectAsStateWithLifecycle()
    val errorText by chat.errorText.collectAsStateWithLifecycle()
    val sessionKey by chat.sessionKey.collectAsStateWithLifecycle()
    // True when this chat IS the daemon-managed main session (Home): Clear runs
    // session.clear in place (not a new session) and delete is hidden.
    val isBoundMain by chat.isBoundMain.collectAsStateWithLifecycle()
    val thinkingLevel by chat.thinkingLevel.collectAsStateWithLifecycle()
    val currentCwd by chat.currentCwd.collectAsStateWithLifecycle()
    // No model / effort / catalog state here any more: the composer owns both
    // pickers and reads them from the same ChatController directly.
    val sessionUsage by chat.sessionUsage.collectAsStateWithLifecycle()

    // The session sheet behind the top bar's ⋮ (ADR 0013).
    var showSettingsSheet by remember { mutableStateOf(false) }

    val visibleMessages = remember(messages) { messages.filterNot { it.hidden } }

    // In-chat search — opened from the settings sheet's "Search in chat" row.
    // Two implementations, one affordance:
    //  - daemon-backed (lab 3 frame 2): `search.messages` with
    //    scope.session_ids = [this session]. Searches the WHOLE conversation,
    //    including turns Room never hydrated, and presents a jump list whose
    //    rows open the transcript AT the match (frame 1) and enter the
    //    navigator.
    //  - local fallback: a case-insensitive substring filter over the messages
    //    already on screen. Honest but partial; only used when the daemon has
    //    no search index. It FILTERS the transcript in place rather than
    //    listing jumps, so there is nothing to anchor — you are already looking
    //    at the matches — and no navigator, because a match list built from
    //    whatever Room happens to hold would be a count the user can't trust.
    // Query is reset on session change so it doesn't leak across chats.
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember(sessionKey) { mutableStateOf("") }
    val searchTerm = searchQuery.trim()
    val daemonFindSession = sessionKey?.takeIf { searchSupported && searchRpc != null }
    // Built only while search is open, so visiting a session doesn't leave a
    // ViewModel behind in the store for a search the user never ran.
    val findViewModel = if (searchActive && daemonFindSession != null) {
        androidx.lifecycle.viewmodel.compose.viewModel<app.marmalade.android.ui.search.SearchViewModel>(
            key = "find-in-chat:$daemonFindSession",
            factory = app.marmalade.android.ui.search.SearchViewModel.factory(
                rpc = searchRpc!!,
                fixedSessionId = daemonFindSession,
            ),
        )
    } else {
        null
    }
    val findState = findViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val searchResults = remember(visibleMessages, searchActive, searchTerm, findViewModel) {
        if (searchActive && findViewModel == null && searchTerm.isNotEmpty()) {
            visibleMessages.filter { it.messageText().contains(searchTerm, ignoreCase = true) }
        } else {
            visibleMessages
        }
    }
    val exitSearch = { searchActive = false; searchQuery = "" }
    // Hardware/gesture back closes search before leaving the screen.
    androidx.activity.compose.BackHandler(enabled = searchActive) { exitSearch() }

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val settings = remember { SettingsRepository.getInstance(context) }

    // Transcript display toggles (global prefs). Changing one persists it
    // immediately and re-filters the visible parts below. Per-session mute
    // (Notifications) is DB-backed and observed from the controller.
    var showThinking by remember { mutableStateOf(settings.showThinkingBlocks) }
    var showToolBlocks by remember { mutableStateOf(settings.showToolUse) }
    val muted by chat.muted.collectAsStateWithLifecycle()
    // Apply the display toggles by stripping the corresponding parts before
    // render — Reasoning for "Show thinking blocks", ToolCall for "Show tool
    // use". A message left with no parts simply produces no rows.
    val displayedMessages = remember(searchResults, showThinking, showToolBlocks) {
        if (showThinking && showToolBlocks) {
            searchResults
        } else {
            searchResults.map { msg ->
                val kept = msg.parts.filterNot { part ->
                    (!showThinking && part is app.marmalade.android.chat.messages.ChatMessagePart.Reasoning) ||
                        // A question rides the AskUserQuestion tool pair, but it
                        // is NOT tool noise — hiding tool use must not erase the
                        // record of a decision the maintainer made (and, while one is
                        // parked, the card he has to answer).
                        (
                            !showToolBlocks &&
                                part is app.marmalade.android.chat.messages.ChatMessagePart.ToolCall &&
                                !part.isAgentQuestion
                            )
                }
                if (kept.size == msg.parts.size) msg else msg.copy(parts = kept)
            }
        }
    }

    // Hoisted so the docked card can scroll the transcript back to the inline
    // record of the question. The list owns nothing about prompts; it just
    // gets its state passed in.
    val chatListState = rememberLazyListState()
    // Is there an inline record row for the question the agent is parked on?
    // Derived from the MESSAGES only — deliberately not from the list's
    // layoutInfo. A bottom bar whose height depends on what the list can see
    // is a feedback loop: taller bar → smaller viewport → the row leaves
    // visibleItemsInfo → shorter bar → the row comes back, forever (the maintainer saw it
    // as the question card "freaking out and resizing"; `imePadding()` drove it
    // every frame while the keyboard moved). See ChatMessageListScrollTest.
    val parkedQuestionKey = remember(displayedMessages) { parkedQuestionRowKey(displayedMessages) }
    // ── open-at-seq: apply a pending transcript anchor ──────────────────────
    // A jump (search result, notification) arrives as a RETAINED intent on the
    // controller, not a scroll call, because the target row usually isn't in
    // Room yet: hydration replays it over the socket a moment later. So this
    // effect re-runs as displayedMessages grows and jumps the first time the
    // row resolves — pending, not lost, in between.
    val anchor by chat.anchor.collectAsStateWithLifecycle()
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var jumpedBackCount by remember { mutableStateOf<Int?>(null) }
    // Same gate ChatMessageList uses; it owns one extra list item, which the
    // index math has to account for.
    val activityIndicatorShown =
        showChatActivityIndicator(displayedMessages, isConnected, isStreaming)
    LaunchedEffect(anchor, displayedMessages, activityIndicatorShown, sessionKey, searchActive) {
        val pending = anchor ?: return@LaunchedEffect
        // An anchor minted for another session must never yank this one.
        if (pending.sessionKey != sessionKey) return@LaunchedEffect
        // While search is open the transcript isn't even composed (the find
        // results replace it), so scrolling it would be a silent no-op AND
        // burn the one-shot. Stay pending; closing search re-runs this.
        if (searchActive) return@LaunchedEffect
        val target = resolveAnchorTarget(displayedMessages, pending) ?: return@LaunchedEffect
        val index = anchorListIndex(displayedMessages, activityIndicatorShown, target.id)
            ?: return@LaunchedEffect
        // Consume BEFORE scrolling: the scroll changes state this effect keys
        // on, and a still-pending anchor would re-fire and fight the user.
        chat.consumeAnchor(pending)
        // Instant, not animated: animating a 74-message jump is a long smear
        // through content nobody asked to see. Landing there IS the gesture.
        chatListState.scrollToItem(index)
        highlightedMessageId = target.id
        jumpedBackCount = messagesBackFrom(displayedMessages, target.id).takeIf { it > 0 }
    }
    // Hold the focus ring briefly, then drop it — or drop it the moment the
    // user scrolls, because by then they've found their place themselves.
    // (The jump scroll above finished before this effect starts, so any
    // in-progress scroll observed here is the user's.)
    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId == null) return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(ANCHOR_HIGHLIGHT_HOLD_MS) {
            androidx.compose.runtime.snapshotFlow { chatListState.isScrollInProgress }
                .first { it }
        }
        highlightedMessageId = null
    }
    LaunchedEffect(jumpedBackCount) {
        if (jumpedBackCount == null) return@LaunchedEffect
        delay(ANCHOR_PILL_HOLD_MS)
        jumpedBackCount = null
    }

    // ── match navigator (lab 3 frame 1) ─────────────────────────────────────
    // An anchor carrying a QUERY means the jump came from a search, so the
    // query stays alive as a walkable match list. All the rules about when the
    // mode starts and stops live in MatchNavigatorHost, not here.
    val navigatorHost = remember(chat, scope, searchRpc, searchSupported) {
        MatchNavigatorHost { entering ->
            // No daemon index, no match list. The plain anchored open still
            // works — it just arrives without a navigator rather than with a
            // fake one built from whatever Room happens to hold.
            searchRpc?.takeIf { searchSupported }?.let { rpc ->
                MatchNavigator(
                    rpc = rpc,
                    sessionKey = entering.sessionKey,
                    anchor = entering,
                    onJump = chat::requestAnchor,
                    scope = scope,
                )
            }
        }
    }
    val navigator by navigatorHost.navigator.collectAsStateWithLifecycle()
    val navigatorState = navigator?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(navigatorHost, sessionKey) { navigatorHost.bind(sessionKey) }
    LaunchedEffect(navigatorHost, chat) {
        chat.anchor.collect { pending -> pending?.let(navigatorHost::onAnchor) }
    }

    // Top-bar voice toggles. ttsEnabled mirrors the chat-only auto-speak
    // setting — the voice popup always speaks and is deliberately NOT
    // affected by this; conversationMode is transient per session mount.
    var ttsEnabled by remember { mutableStateOf(settings.chatTtsEnabled) }
    var conversationMode by remember(sessionKey) { mutableStateOf(false) }
    // ChatScreen owns a TTSManager when the host didn't inject one — both
    // current hosts don't, which left ReadAloud and the TTS toggle inert.
    val ownedTts = remember { if (ttsManager == null) TTSManager(context) else null }
    val tts: TTSSpeaker? = ttsManager ?: ownedTts
    // shutdown() (not stop()): TTSManager owns a bound TextToSpeech system
    // service that must be unbound or it leaks per chat-screen exit.
    DisposableEffect(Unit) { onDispose { ownedTts?.shutdown() } }

    var composerText by rememberSaveable(sessionKey) { mutableStateOf("") }
    // Staged composer attachments. Plain remember (not saveable): the staged
    // FILES survive process death under filesDir/attachments and get pruned
    // as orphans; re-picking after a kill is acceptable for v1.
    var composerAttachments by remember(sessionKey) {
        mutableStateOf(listOf<app.marmalade.android.chat.OutgoingAttachment>())
    }
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }
    var imageViewerAlt by remember { mutableStateOf<String?>(null) }
    // Id of the message currently being read aloud. Null when nothing is playing.
    var speakingMessageId by remember { mutableStateOf<String?>(null) }

    // Restore draft on session-key change.
    LaunchedEffect(sessionKey) {
        val draft = chat.getDraft()
        if (!draft.isNullOrBlank()) composerText = draft
    }

    // Inline dictation → composer. The InlineSTT holder captures a partial
    // transcript but doesn't own the composer text, so without this wiring
    // tapping the mic listened into the void — the transcript went nowhere
    // and the user saw nothing happen. We capture whatever was already typed
    // when dictation starts, then live-append the growing transcript so the
    // user watches words appear and can edit before sending.
    var sttBaseText by remember { mutableStateOf("") }
    LaunchedEffect(isInlineSTTActive) {
        if (isInlineSTTActive) sttBaseText = composerText.trimEnd()
    }
    LaunchedEffect(inlineSTTPartialText) {
        if (isInlineSTTActive && inlineSTTPartialText.isNotBlank()) {
            // Conversation mode: a spoken termination word ("over", …) sends
            // the accumulated transcript hands-free instead of waiting for a
            // manual send tap. Same matcher the voice popup uses.
            if (conversationMode) {
                val match = settings.extractTerminationWord(inlineSTTPartialText)
                if (match != null) {
                    val (remaining, _) = match
                    val finalText = listOf(sttBaseText, remaining)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    onMicTap?.invoke() // toggle() → stop listening
                    composerText = ""
                    if (finalText.isNotBlank()) {
                        chat.sendMessage(
                            message = finalText,
                            thinkingLevel = chat.thinkingLevel.value,
                            voiceOrigin = true,
                        )
                    }
                    return@LaunchedEffect
                }
            }
            composerText = if (sttBaseText.isEmpty()) {
                inlineSTTPartialText
            } else {
                "$sttBaseText $inlineSTTPartialText"
            }
        }
    }

    // Auto-speak new assistant replies while the top-bar TTS toggle is on.
    // Replay guard: pendingRunCount increments synchronously inside
    // ChatController.sendMessage, so a positive value in this mount only
    // happens after a user-initiated send — history replayed on entry
    // never speaks.
    val userInteractedInSession = remember(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(pendingRunCount, sessionKey) {
        if (pendingRunCount > 0) userInteractedInSession.value = true
    }
    val lastSpokenMessageId = remember(sessionKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(messages, ttsEnabled, sessionKey) {
        if (!ttsEnabled || tts == null) return@LaunchedEffect
        if (!userInteractedInSession.value) return@LaunchedEffect
        val lastMsg = visibleMessages.lastOrNull() ?: return@LaunchedEffect
        if (lastMsg.role != ChatRole.Assistant || lastMsg.pending) return@LaunchedEffect
        if (lastMsg.id == lastSpokenMessageId.value) return@LaunchedEffect
        val text = lastMsg.messageText()
        if (text.isBlank()) return@LaunchedEffect
        lastSpokenMessageId.value = lastMsg.id
        speakingMessageId = lastMsg.id
        try {
            tts.speakWithProgress(text).collect { _ -> }
        } finally {
            speakingMessageId = null
        }
        // Conversation mode: hand the mic back once the reply has been
        // spoken, closing the hands-free loop (listen → send → speak → listen).
        if (conversationMode && !isInlineSTTActive) {
            delay(300)
            onMicTap?.invoke()
        }
    }

    // Surface controller error as a transient snackbar.
    LaunchedEffect(errorText) {
        val err = errorText
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
        }
    }

    // Slash-command toast messages (e.g. /save → "Saved to <path>"). The
    // SharedFlow has replay=0; this LaunchedEffect subscribes once for the
    // lifetime of the composition.
    //
    // Structural assumption: `chat` is a singleton owned by MarmaladeRuntime
    // — it does NOT change across recompositions. If that ever stops being
    // true (multi-account, controller re-init, etc.), the key change here
    // would re-subscribe the new collector AFTER the old controller may
    // already have emitted into the void. Document the singleton invariant
    // at the runtime layer if you ever consider relaxing it.
    LaunchedEffect(chat) {
        chat.toastMessage.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    // Rename dialog state — opened by /title (no args) via the dispatcher.
    var showRenameDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        // Hosted inside MarmaladeNavHost's Scaffold, which already consumed the
        // system bars (.claude/rules/window-insets.md). Harmless today because
        // this Scaffold always has both bars — but a screen that grows a state
        // without one would silently inherit a second status-bar inset.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (searchActive) {
                ChatSearchBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        findViewModel?.setQuery(it)
                    },
                    resultCount = when {
                        // The daemon's total counts the whole conversation, not
                        // just the page — that's the number worth showing.
                        findState != null -> findState.total.takeIf { findState.searched }
                        searchTerm.isEmpty() -> null
                        else -> searchResults.size
                    },
                    onClose = exitSearch,
                )
            } else {
                ChatTopBar(
                    sessionName = sessionName,
                    showBackArrow = showBackArrow,
                    workspacePath = currentCwd,
                    onBackPressed = onBackPressed,
                    onStatusClick = onStatusClick,
                    onTitleClick = onTitleClick,
                    onMenuClick = onMenuClick,
                    onPanelClick = onPanelClick,
                    onSessionMenuClick = {
                        // If the host passed an onSettingsClick, prefer that
                        // (callers that want their own settings affordance can
                        // still take over); otherwise open the in-chat sheet.
                        if (onSettingsClick != null) onSettingsClick()
                        else showSettingsSheet = true
                    },
                )
            }
        },
        bottomBar = {
            Column {
                // The docked card is the INPUT surface for a parked question,
                // so it always renders in full while one is pending — the
                // pointer-vs-card swap it replaced keyed off list visibility
                // and oscillated (see parkedQuestionKey above). The jump back
                // to where the question was asked lives inside the card
                // header, where its presence never changes with scroll.
                PromptCards(
                    prompts = pendingPrompts,
                    // The parked question IS the newest row — the agent is
                    // blocked on it, so nothing can follow it — and this is a
                    // reverse-layout list where item 0 is the visual bottom.
                    // So "take me to it" is just "go to the bottom".
                    onJumpToContext = parkedQuestionKey?.let {
                        { scope.launch { chatListState.animateScrollToItem(0) } }
                    },
                    onClarify = chat::respondClarify,
                    onApproval = { id, decision -> chat.respondApproval(id, decision) },
                    onSecret = chat::respondSecret,
                    // Secret cards deny on the wire instead of closing
                    // locally — the agent is parked on the tool call.
                    onSecretDeny = chat::denySecret,
                    onSudo = chat::respondSudo,
                    // Local-only dismissal: unblocks the UI without answering
                    // (the agent side can re-emit or time out). Pre-fix this
                    // was a no-op and the card couldn't be closed at all.
                    onDismiss = chat::dismissPrompt,
                )
                Composer(
                    chat = chat,
                    text = composerText,
                    onTextChange = { composerText = it },
                    pendingRunCount = pendingRunCount,
                    snackbarHostState = snackbarHostState,
                    onSend = { outgoing ->
                        chat.sendMessage(
                            message = outgoing,
                            thinkingLevel = chat.thinkingLevel.value,
                            attachments = composerAttachments,
                        )
                        composerAttachments = emptyList()
                        navigatorHost.onSend()
                    },
                    onQueue = { outgoing ->
                        chat.enqueuePrompt(
                            message = outgoing,
                            thinkingLevel = chat.thinkingLevel.value,
                            attachments = composerAttachments,
                        )
                        composerAttachments = emptyList()
                        navigatorHost.onSend()
                    },
                    // Clear the composer only once the steer is accepted — a
                    // reject (turn already finished) keeps the draft.
                    onSteer = { outgoing -> chat.steer(outgoing) { composerText = "" } },
                    attachments = composerAttachments,
                    onAddAttachment = { composerAttachments = composerAttachments + it },
                    onRemoveAttachment = { att ->
                        composerAttachments = composerAttachments.filterNot { it.path == att.path }
                    },
                    onMicTap = onMicTap,
                    onMicLongPress = onMicLongPress,
                    isMicActive = isInlineSTTActive,
                    onOpenRenameDialog = { showRenameDialog = true },
                    onOpenSessionPicker = onOpenSessionPicker,
                    attachmentsSupported = attachmentsSupported,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        // Between the top bar and the transcript, exactly where the lab puts
        // it. Hidden while the find UI is open — that IS the query surface,
        // and two of them would be one too many.
        if (navigatorState != null && !searchActive) {
            MatchNavigatorBar(
                state = navigatorState,
                onStepBack = { navigator?.stepBack() },
                onStepForward = { navigator?.stepForward() },
                onDismiss = navigatorHost::dismiss,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (searchActive && findViewModel != null && findState != null) {
                // Daemon-backed find-in-conversation replaces the transcript
                // while search is open (lab 3 frame 2 is a jump list, not an
                // inline highlight pass).
                app.marmalade.android.ui.search.ConversationFindResults(
                    state = findState,
                    onTogglePeek = findViewModel::togglePeek,
                    onOpenHit = { hit ->
                        // Anchor FIRST, then close search: the anchor is
                        // retained, so either order works, but requesting it
                        // while the transcript is still hidden is what makes
                        // the jump feel instantaneous when it reappears.
                        chat.requestAnchor(
                            sessionKey = daemonFindSession!!,
                            seq = hit.seq,
                            messageId = hit.message_id,
                            // The term these results answer, not whatever the
                            // field holds mid-debounce.
                            query = findState.committedQuery,
                        )
                        exitSearch()
                    },
                    onDismiss = exitSearch,
                    onRetry = findViewModel::retry,
                    onLoadMore = findViewModel::loadMore,
                )
            } else if (searchActive && searchTerm.isNotEmpty() && searchResults.isEmpty()) {
                Text(
                    text = "No messages match \"$searchTerm\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else if (visibleMessages.isEmpty() && pendingRunCount == 0) {
                ChatEmptyState(
                    onSuggestionTap = { suggestion ->
                        chat.sendMessage(
                            message = suggestion,
                            thinkingLevel = chat.thinkingLevel.value,
                        )
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ChatMessageList(
                    messages = displayedMessages,
                    listState = chatListState,
                    isChatConnected = isConnected,
                    isStreaming = isStreaming,
                    isCompacting = isCompacting,
                    speakingMessageId = speakingMessageId,
                    highlightedMessageId = highlightedMessageId,
                    onBlockResponse = { responseText ->
                        chat.sendMessage(
                            message = responseText,
                            thinkingLevel = chat.thinkingLevel.value,
                        )
                    },
                    onImageTap = { url, alt ->
                        imageViewerUrl = url
                        imageViewerAlt = alt
                    },
                    onBubbleAction = { message, action ->
                        if (action == BubbleAction.Branch) {
                            // Primary path: harness-native session.fork cut at
                            // this assistant reply (full context). Falls back
                            // to seed-create on a no-fork harness (T2 #3).
                            chat.branchSession(message.id)
                        } else if (action == BubbleAction.ReadAloud && tts != null) {
                            val text = message.messageText()
                            if (text.isNotBlank()) {
                                // Interrupt then speak (or stop if re-tapping the active bubble).
                                tts.stop()
                                if (speakingMessageId == message.id) {
                                    // Re-tap on the already-playing bubble → stop only.
                                    speakingMessageId = null
                                } else {
                                    speakingMessageId = message.id
                                    scope.launch {
                                        tts.speakWithProgress(text).collect { _ -> }
                                        speakingMessageId = null
                                    }
                                }
                            }
                        } else {
                            scope.launch {
                                handleBubbleAction(
                                    context = context,
                                    clipboard = clipboard,
                                    message = message,
                                    action = action,
                                    onEditResend = { editText ->
                                        composerText = editText
                                        // Branch-resend: count user messages up to
                                        // and including this one (1-based) for
                                        // `truncate_before_user_ordinal`.
                                        val ordinal = visibleMessages
                                            .asSequence()
                                            .filter { it.role == ChatRole.User }
                                            .indexOfFirst { it.id == message.id }
                                            .let { if (it < 0) null else it }
                                        ordinal?.let { idx ->
                                            // Interrupt any in-flight turn before
                                            // submitting the rewind — desktop does
                                            // the same (use-prompt-actions.ts:1563-
                                            // 1581) so the still-running turn
                                            // doesn't keep emitting deltas against a
                                            // now-truncated history.
                                            if (isStreaming) chat.abort()
                                            chat.sendMessage(
                                                message = editText,
                                                thinkingLevel = chat.thinkingLevel.value,
                                                truncateBeforeUserOrdinal = idx,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
            // Sits above the transcript, near its top edge — the same place
            // the lab puts it. Purely transient; never intercepts touches.
            AnchorJumpPill(
                count = jumpedBackCount,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }
      }
    }

    if (showSettingsSheet) {
        ChatSettingsSheet(
            showThinking = showThinking,
            onShowThinkingChange = {
                showThinking = it
                settings.showThinkingBlocks = it
            },
            showToolBlocks = showToolBlocks,
            onShowToolBlocksChange = {
                showToolBlocks = it
                settings.showToolUse = it
            },
            isMuted = muted,
            onMuteChange = { chat.setMuted(it) },
            // The two voice toggles that used to be top-bar icons. The
            // implication rules are unchanged, just relocated: conversation
            // mode turns TTS on, and turning TTS off exits conversation mode
            // (a hands-free loop with silent replies is a dead loop).
            ttsEnabled = ttsEnabled,
            onTtsChange = {
                ttsEnabled = it
                settings.chatTtsEnabled = it
                if (!it) conversationMode = false
            },
            conversationMode = conversationMode,
            onConversationModeChange = {
                conversationMode = it
                if (it && !ttsEnabled) {
                    ttsEnabled = true
                    settings.chatTtsEnabled = true
                }
            },
            onRenameSession = sessionKey?.let { { showRenameDialog = true } },
            sessionKey = sessionKey,
            onSearchClick = { searchActive = true },
            // Branch the whole chat from its end (session.fork, no cut) —
            // full-context branch; falls back to seed-create on a no-fork
            // harness (T2 #3). Shown only for a real open session.
            onBranchChat = sessionKey?.let { { chat.branchSession(null) } },
            onCompactClick = { chat.compact() },
            // Undo the last completed turn (session.undo) — gated on the daemon
            // "undo" feature; drops the popped bubbles live off session.undone.
            undoSupported = undoSupported,
            onUndo = sessionKey?.let { { chat.undo() } },
            // Clear: the main session resets IN PLACE via session.clear (it
            // can't be deleted and can't spin up a replacement — it's the
            // singleton). Every other session "clears" by starting a fresh
            // chat and leaving the old one intact server-side (desktop /new).
            // The sheet's confirm dialog gates both.
            onClearHistory = {
                if (isBoundMain) chat.clearConversation() else chat.startFreshSession()
            },
            // The main session is daemon-owned and non-deletable (the daemon
            // refuses session.delete for it) — hide the row entirely.
            onDeleteSession = sessionKey
                ?.takeIf { it != "main" && it != "global" && !isBoundMain }
                ?.let { key ->
                    {
                        chat.deleteSession(key)
                        onSessionDeleted?.invoke()
                    }
                },
            usage = sessionUsage,
            // Clipboard writes stay in :app — the sheet itself is shared code
            // and the Compose Multiplatform it compiles against has no
            // clipboard API of its own.
            onCopySessionKey = { key -> scope.launch { clipboard.setPlainText(key) } },
            onDismiss = { showSettingsSheet = false },
        )
    }

    imageViewerUrl?.let { url ->
        ChatImageViewer(
            imageData = ImageData.UrlImage(url),
            onDismiss = {
                imageViewerUrl = null
                imageViewerAlt = null
            },
        )
    }

    if (showRenameDialog) {
        app.marmalade.android.ui.sessions.RenameSessionDialog(
            currentTitle = sessionName,
            onConfirm = { newTitle ->
                chat.renameCurrentSession(newTitle)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
}

private suspend fun handleBubbleAction(
    context: Context,
    clipboard: Clipboard,
    message: ChatMessage,
    action: BubbleAction,
    onEditResend: (String) -> Unit,
) {
    val text = message.messageText()
    when (action) {
        BubbleAction.Copy -> clipboard.setPlainText(text)
        BubbleAction.Share -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, "Share message"))
        }
        BubbleAction.EditResend -> {
            if (message.role == ChatRole.User && text.isNotBlank()) {
                onEditResend(text)
            }
        }
        // ReadAloud + Branch are handled inline in ChatScreen's
        // onBubbleAction lambda before this function is called; they never
        // reach here.
        BubbleAction.ReadAloud -> Unit
        BubbleAction.Branch -> Unit
    }
}
