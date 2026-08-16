package app.marmalade.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import app.marmalade.android.SessionUiModel
import app.marmalade.android.chat.ChatSessionEntry
import app.marmalade.android.chat.messages.text
import app.marmalade.android.rpc.ConnectionState
import app.marmalade.android.ui.LocalCopyText
import app.marmalade.android.ui.LocalMarmaladeRpc
import app.marmalade.android.ui.chat.BubbleAction
import app.marmalade.android.ui.chat.ChatMessageList
import app.marmalade.android.ui.chat.friendlySessionName
import app.marmalade.android.ui.sessions.SessionRow
import app.marmalade.android.ui.theme.LocalMarmaladeColors
import app.marmalade.android.ui.theme.MarmaladeDarkColors
import app.marmalade.android.ui.theme.MarmaladeShapes
import app.marmalade.android.ui.theme.DarkMarmaladeColors
import app.marmalade.android.ui.theme.marmaladeColors
import app.marmalade.android.utils.SessionCategoryUtils
import app.marmalade.android.utils.UnreadUtils
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The Phase 2 spike's whole UI: a session list on the left, the bound session's
 * transcript + a minimal composer on the right.
 *
 * Everything visible here is a `:shared` composable — [SessionRow] from
 * jvmSharedMain, [ChatMessageList] (and the whole MessageBubble / message-part
 * renderer tree under it) from commonMain. The only desktop-original code is
 * the two-pane frame, the composer, and the [SessionUiModel] projection.
 *
 * Typography is stock Material3: the Marmalade brand fonts need CMP 1.8's
 * resource API, which the locked 1.7.3 toolchain predates. Colors are the
 * shared Marmalade dark tokens, which are pure Compose values and port as-is.
 *
 * Data flow (read down, then back up):
 * ```
 *  daemon /api/ws ─► JsonRpcClient ─► MessageStream ─► Room (shared store)
 *                          │                              │
 *                          │ connectionState              │ chatDao Flows
 *                          ▼                              ▼
 *                  DesktopRuntime ──────────────► ChatController
 *                          │                       │  sessions / sessionKey
 *                          │                       │  messages / isStreaming
 *                          ▼                       ▼
 *                        DesktopApp ── collectAsState ──┐
 *                          │                            │
 *          pinMainFirst + toUiModel                     │
 *                          ▼                            ▼
 *                    SessionPane                    ChatPane
 *                  (SessionRow list)        (header + ChatMessageList + Composer)
 *                          │                            │
 *                  onSelect │                           │ onSend
 *                          ▼                            ▼
 *              ChatController.switchSession   ChatController.sendMessage
 *                          │                            │
 *                          └──── outbox / RPC ──────────┘──► daemon
 * ```
 * Everything the UI renders is derived state pulled from Room via
 * [ChatController]; the two callbacks above are the only writes. The unread
 * "New" chip clears the same way — `session.subscribe` stamps this device's
 * `session.seen` cursor, the optimistic Room merge lands, and the sessions
 * Flow re-emits. No desktop-side refresh is needed.
 */
@Composable
fun DesktopApp(runtime: DesktopRuntime) {
    // start() owns the socket for the process lifetime (connect + reconnect
    // backoff + per-open handshake) and is idempotent, so a recomposition or a
    // re-launched effect can't stack a second loop.
    LaunchedEffect(runtime) { runtime.start() }

    val connection by runtime.connectionState.collectAsState()
    val entries by runtime.chat.sessions.collectAsState()
    val boundKey by runtime.chat.sessionKey.collectAsState()
    val messages by runtime.chat.messages.collectAsState()
    val isStreaming by runtime.chat.isStreaming.collectAsState()
    val thinkingLevel by runtime.chat.thinkingLevel.collectAsState()

    val sessions = remember(entries) { pinMainFirst(entries.map(ChatSessionEntry::toUiModel)) }
    val boundSession = remember(sessions, boundKey) { sessions.firstOrNull { it.id == boundKey } }

    MaterialTheme(colorScheme = MarmaladeDarkColors, shapes = MarmaladeShapes) {
        CompositionLocalProvider(
            LocalMarmaladeRpc provides runtime.rpc,
            LocalMarmaladeColors provides DarkMarmaladeColors,
            LocalCopyText provides ::copyToSystemClipboard,
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize()) {
                    SessionPane(
                        sessions = sessions,
                        boundKey = boundKey,
                        connection = connection,
                        onSelect = { runtime.chat.switchSession(it) },
                        modifier = Modifier.width(SESSION_PANE_WIDTH).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChatPane(
                        messages = messages,
                        boundKey = boundKey,
                        title = boundSessionTitle(boundSession, boundKey),
                        isMain = boundSession?.isMain == true,
                        connection = connection,
                        isStreaming = isStreaming,
                        onSend = { text ->
                            runtime.chat.sendMessage(text, thinkingLevel = thinkingLevel)
                        },
                        onCopy = ::copyToSystemClipboard,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * Window focus → the cross-client unread contract.
 *
 * `session.seen` is a per-device cursor the daemon shares with every other
 * client, so "read" has to mean the user actually looked. Two halves:
 *  - focus state gates [ChatController]'s turn-completion seen mark
 *    ([DesktopRuntime.windowFocused]), so a turn landing behind another window
 *    stays unread on the phone;
 *  - regaining focus re-attaches the bound session (`refresh()` → resume +
 *    subscribe), which both replays anything missed while we were away and
 *    stamps the cursor now that it is honestly read. This is the desktop
 *    analogue of Android's ON_START hook.
 *
 * The AWT listener rather than `LocalWindowInfo.isWindowFocused`: it fires on
 * the real OS transition (including window-manager focus changes that never
 * touch Compose's input state) and gives us the edge, not just the level.
 */
@Composable
fun FrameWindowScope.WindowFocusBridge(runtime: DesktopRuntime) {
    DisposableEffect(runtime, window) {
        runtime.windowFocused = window.isFocused
        val listener = object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent?) {
                runtime.windowFocused = true
                runtime.chat.refresh()
            }

            override fun windowLostFocus(e: WindowEvent?) {
                runtime.windowFocused = false
            }
        }
        window.addWindowFocusListener(listener)
        onDispose { window.removeWindowFocusListener(listener) }
    }
}

@Composable
private fun SessionPane(
    sessions: List<SessionUiModel>,
    boundKey: String,
    connection: ConnectionState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        // Connection state deliberately NOT repeated here: the chat pane header
        // owns it, so there is one place to look when the socket drops.
        Text(
            text = "Sessions",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (sessions.isEmpty()) {
            Text(
                text = if (connection == ConnectionState.Open) "No sessions." else "Connecting…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    onClick = { onSelect(session.id) },
                    onLongClick = {},
                    modifier = if (session.id == boundKey) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun ChatPane(
    messages: List<app.marmalade.android.chat.messages.ChatMessage>,
    boundKey: String,
    title: String,
    isMain: Boolean,
    connection: ConnectionState,
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected = connection == ConnectionState.Open
    Column(modifier) {
        ChatPaneHeader(title = title, isMain = isMain, connection = connection)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatMessageList(
                messages = messages,
                // Interactive blocks, image taps and bubble actions beyond Copy
                // are out of spike scope — the renderers still draw, the
                // callbacks just don't lead anywhere yet.
                onBlockResponse = {},
                onImageTap = { _, _ -> },
                onBubbleAction = { message, action ->
                    if (action is BubbleAction.Copy) onCopy(message.text())
                },
                isChatConnected = isConnected,
                isStreaming = isStreaming,
            )
        }
        Composer(enabled = isConnected, boundKey = boundKey, onSend = onSend)
    }
}

/**
 * The chat pane's slim header: which session the transcript belongs to, and
 * whether the socket is up.
 *
 * Connection state lives HERE rather than in a banner over the transcript. A
 * desktop client that loses the daemon is a persistent, low-urgency condition
 * — [DesktopRuntime.start]'s backoff loop heals it without user action — so it
 * belongs in ambient chrome, not in something that eats transcript rows and
 * has to be dismissed on every blip.
 */
@Composable
private fun ChatPaneHeader(title: String, isMain: Boolean, connection: ConnectionState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isMain) {
                // Same "this is THE daemon-managed session" marker SessionRow
                // draws, shortened for the header (the row has ★ Assistant).
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Main",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(CONNECTION_DOT_SIZE)
                .clip(CircleShape)
                .background(connection.indicatorColor()),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = connection.indicatorLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The composer, with desktop keyboard semantics: plain Enter sends, Shift+Enter
 * inserts a newline.
 *
 * [Modifier.onPreviewKeyEvent] rather than `ImeAction.Send`: an IME action is a
 * soft-keyboard concept, and a hardware Enter on CMP desktop never raises one —
 * it goes straight to the text field as a newline. Previewing the event is the
 * only place to intercept it before the field consumes it, and returning false
 * for the Shift variant is what lets multi-line drafts still work.
 *
 * Both Enter key-downs are consumed (main and numpad), and their key-ups with
 * them: leaving the up event to the field is how a stray newline shows up in
 * the next draft on some platforms.
 */
@Composable
private fun Composer(enabled: Boolean, boundKey: String, onSend: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val submit = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            onSend(text)
            draft = ""
        }
    }
    // Click a session, type immediately. Re-runs on every switch (and on the
    // connect that first enables the field) so focus follows the binding.
    LaunchedEffect(boundKey, enabled) {
        if (!enabled) return@LaunchedEffect
        // The field can be detached mid-recomposition; a failed request just
        // means the user has to click it, never a crash.
        runCatching { focusRequester.requestFocus() }
    }
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (!isEnter || event.isShiftPressed) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) submit()
                    // Swallow the matching KeyUp too — see the kdoc.
                    event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
                },
            enabled = enabled,
            placeholder = { Text("Message Marmalade…  (Enter to send, Shift+Enter for a new line)") },
            maxLines = 6,
        )
        Button(onClick = submit, enabled = enabled && draft.isNotBlank()) {
            Text("Send")
        }
    }
}

/** Human label for the header's connection indicator. */
private fun ConnectionState.indicatorLabel(): String = when (this) {
    ConnectionState.Open -> "connected"
    ConnectionState.Idle, ConnectionState.Connecting -> "connecting"
    ConnectionState.Closed, ConnectionState.Error -> "offline"
}

/** Dot color for the header's connection indicator — the shared status tokens. */
@Composable
private fun ConnectionState.indicatorColor(): Color = when (this) {
    ConnectionState.Open -> MaterialTheme.marmaladeColors.statusConnected
    ConnectionState.Idle, ConnectionState.Connecting -> MaterialTheme.marmaladeColors.statusConnecting
    ConnectionState.Closed, ConnectionState.Error -> MaterialTheme.marmaladeColors.statusDisconnected
}

/**
 * Pin the daemon-managed main session to the top, leaving everything else in
 * the order [ChatController.sessions] delivered it.
 *
 * That order is already most-recently-active-first (`ChatDao.getAllSessions`
 * sorts `lastMessageAt DESC`, nulls last, off the server's `last_active`), the
 * same key Android's `SessionListViewModel` sorts by — so this only adds the
 * pin, using a STABLE sort so recency survives inside each group. Android
 * pins main too (ADR 0013: the drawer hoists it above the workspace cards);
 * the desktop's single flat list has no Home tab to park it on, so pinning it
 * here is the analogue rather than a new ordering rule.
 */
internal fun pinMainFirst(sessions: List<SessionUiModel>): List<SessionUiModel> =
    sessions.sortedByDescending { it.isMain }

/**
 * The chat header's session label: the list row's parsed title when the bound
 * key is in the list, otherwise a friendly rendering of the key itself.
 *
 * The fallback is not dead code — the bound key leads the list by a beat on a
 * cold start (ChatController binds before the first `session.list` lands) and
 * survives a refresh that prunes the row.
 */
internal fun boundSessionTitle(bound: SessionUiModel?, boundKey: String): String =
    bound?.title?.takeIf { it.isNotBlank() } ?: friendlySessionName(boundKey)

private const val SESSION_PANE_WIDTH_DP = 320
private val SESSION_PANE_WIDTH = SESSION_PANE_WIDTH_DP.dp

/** Header connection dot — matches SessionRow's 10dp status dots. */
private val CONNECTION_DOT_SIZE = 10.dp

/** Copy-text host bridge — the desktop half of `LocalCopyText`. */
private fun copyToSystemClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard
        .setContents(StringSelection(text), null)
}

/**
 * Project a daemon session row onto the shared list model.
 *
 * A cut-down [app.marmalade.android.SessionListViewModel] mapping: it keeps the
 * fields that come straight off `session.list` (title, category, main, unread
 * seq arithmetic, run state, lineage) and skips everything the Android VM joins
 * in from local Room rows or live prompt/run pushes — mute, emoji, per-session
 * pending prompts. Those are host state the spike doesn't own yet.
 */
private fun ChatSessionEntry.toUiModel(): SessionUiModel {
    val (category, title) = SessionCategoryUtils.parseSessionCategory(displayName)
    return SessionUiModel(
        id = key,
        title = title,
        createdAt = updatedAtMs ?: 0L,
        isGateway = true,
        category = category,
        isMain = isMain,
        isDeletable = false,
        lastMessageAt = updatedAtMs,
        running = runState == "running" || runState == "starting",
        awaitingInput = runState == "awaiting_input",
        hung = runState == "hung",
        source = source,
        cwd = cwd,
        serverUnread = UnreadUtils.isUnread(lastSeq = lastSeq, seenSeq = seenSeq),
        branchedFromId = branchedFromId,
        workspaceId = workspaceId,
        archived = archived,
    )
}
