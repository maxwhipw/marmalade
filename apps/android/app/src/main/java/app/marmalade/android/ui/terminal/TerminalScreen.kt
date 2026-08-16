package app.marmalade.android.ui.terminal

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.terminal.GhosttyKey
import app.marmalade.android.terminal.GhosttyKeyAction
import app.marmalade.android.terminal.GhosttyMods
import app.marmalade.android.terminal.GhosttyMouseAction
import app.marmalade.android.terminal.GhosttyMouseButton
import app.marmalade.android.terminal.GhosttyTermCore
import app.marmalade.android.terminal.GhosttyTerminalEngine
import app.marmalade.android.terminal.KeyMapper
import app.marmalade.android.terminal.TerminalController
import app.marmalade.android.terminal.TerminalErrors
import app.marmalade.android.terminal.TerminalIO
import app.marmalade.android.terminal.TerminalKey
import app.marmalade.android.terminal.TerminalSelection
import app.marmalade.android.ui.getPlainText
import app.marmalade.android.ui.setPlainText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The open-terminal screen, rendered by **libghostty-vt** (ADR 0016). It is the
 * only terminal renderer: the vendored xterm.js WebView it replaced was deleted
 * on 2026-07-28 once this was verified on device (the maintainer's explicit call), taking
 * ~528 KB of assets and the JS bridge with it. Daemon lifecycle:
 *   enter    → terminal.attach: create the emulator, feed snapshot, then stream
 *   type     → IME/keys → bytes → (sticky Ctrl/Alt) → terminal.input
 *   paste    → the emulator brackets it (DECSET 2004) → terminal.input
 *   resize   → the canvas' fitted grid → the emulator AND terminal.resize
 *   reconnect→ tear the emulator down and re-attach (shells survive a drop)
 *   leave    → terminal.detach (the shell keeps running)
 *
 * **Everything the client sends goes through a single ordered channel.** The
 * emulator answers queries on its own (DA1/DSR/DEC 2048) from the engine thread
 * while the user types on the main thread, and a `scope.launch` per send would
 * let those two streams reorder — which a terminal reads as corruption, not as
 * a race.
 *
 * The top bar (maintainer, 2026-07-27): the drawer handle owns the top-left — a
 * terminal is a peer of a session, not a sub-screen of one, so it gets the same
 * navigator rather than a back arrow. Leaving is system/predictive back.
 * Killing the shell lives on the drawer's terminal row overflow ("Close
 * terminal"), at the level of the thing it destroys, instead of a red button
 * one mis-tap from the running shell.
 *
 * **Selection data flow** (the maintainer's on-device note, 2026-07-27: selection worked
 * but did not *feel* like Android). One direction, no cycles:
 *
 *   gesture   → [GhosttyCanvas] decides cells → `selection` (this screen owns it)
 *   selection → [GhosttyCanvas] paints the wash AND reports pixel geometry back
 *   geometry  → the two [TerminalSelectionHandle]s, and the floating menu's
 *               anchor rect ([TerminalSelectionToolbar.contentRect], translated
 *               into root coordinates by the canvas' own `positionInRoot`)
 *   selection → [GhosttyTerminalEngine.selectionText] on the engine thread →
 *               `selectionText`, which is what Copy puts on the clipboard (the
 *               native handle never leaves the engine)
 *   drag      → `dragTarget`, from the canvas' long press or from a handle;
 *               it hides the menu and points the platform magnifier at the
 *               cell the moving endpoint has landed on.
 *
 * The menu is shown/hidden by one effect off (visible, rect) rather than from
 * the gesture callbacks, so it cannot be asked to move once per drag frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    controller: TerminalController,
    terminalId: String,
    connected: Boolean,
    onMenuClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val terminals by controller.terminals.collectAsStateWithLifecycle()
    val info = terminals.find { it.terminal_id == terminalId }

    var exitNotice by remember { mutableStateOf<String?>(null) }
    var ctrlSticky by remember { mutableStateOf(false) }
    var altSticky by remember { mutableStateOf(false) }
    var fontSizeSp by remember { mutableFloatStateOf(DEFAULT_TERMINAL_FONT_SIZE_SP) }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }
    // Keyboard autocorrect ("Abc"). Persisted globally — it is a keyboard
    // preference, not a property of this shell.
    val settings = remember(context) { SettingsRepository.getInstance(context) }
    var suggestionsEnabled by remember { mutableStateOf(settings.terminalSuggestionsEnabled) }
    // The canvas measures the grid; nothing may attach before it has.
    var gridReady by remember { mutableStateOf(false) }
    var cols by remember { mutableIntStateOf(80) }
    var rows by remember { mutableIntStateOf(24) }

    // Selection lives here rather than in the canvas: the drag handles are drawn
    // over the canvas, and the text has to be fetched from the engine, so the
    // canvas would be the wrong owner for either.
    var selection by remember(terminalId) { mutableStateOf<TerminalSelection?>(null) }
    var selectionGeometry by remember(terminalId) {
        mutableStateOf<TerminalSelectionGeometry?>(null)
    }
    var selectionText by remember(terminalId) { mutableStateOf<String?>(null) }
    // Which endpoint the finger owns, if any. Set by the canvas' long-press
    // drag and by the handles; drives both the magnifier and menu suppression.
    var dragTarget by remember(terminalId) { mutableStateOf(TerminalDragTarget.None) }
    // The canvas' origin in the composition root — the space the platform text
    // toolbar anchors in. Selection geometry is canvas-local, so the two are
    // added at the point of use rather than stored pre-translated.
    var canvasOriginInRoot by remember(terminalId) { mutableStateOf(Offset.Zero) }

    // Every outbound byte — typed keys, extra keys, pastes and the emulator's
    // own answers — funnels through here so it reaches the PTY in order.
    val upstream = remember(terminalId) { Channel<ByteArray>(Channel.UNLIMITED) }
    LaunchedEffect(terminalId) {
        for (bytes in upstream) {
            runCatching { controller.input(terminalId, TerminalIO.bytesToB64(bytes)) }
                .onFailure { Log.w(TAG, "input failed: ${it.message}") }
        }
    }

    val engine = remember(terminalId) {
        GhosttyTerminalEngine(
            core = GhosttyTermCore(),
            scope = scope,
            sendUpstream = { bytes -> upstream.trySend(bytes) },
            publishClipboard = { text ->
                // OSC 52: the running program asked to own the clipboard.
                scope.launch { clipboard.setPlainText(text) }
            },
        )
    }
    val render by engine.state.collectAsStateWithLifecycle()

    // The terminal is fixed dark by design (see the colour constants at the
    // bottom of this file). A null palette keeps ghostty's own 256-color table.
    LaunchedEffect(engine) {
        engine.setColorScheme(dark = true)
        engine.setDefaultColors(
            fg = TERMINAL_FG_RGB,
            bg = TERMINAL_BG_RGB,
            cursor = TERMINAL_CURSOR_RGB,
            palette = null,
        )
    }

    fun clearSticky() {
        if (ctrlSticky || altSticky) scope.launch { ctrlSticky = false; altSticky = false }
    }

    /**
     * An extra-keys press. This does NOT write a fixed byte constant: the
     * physical key goes to the emulator, which encodes it in its current modes
     * — which is what makes the arrows right inside vim (DECCKM) and under a
     * kitty-protocol app.
     *
     * The exception is a key with no physical key behind it (a literal
     * character, Ctrl+J): there is nothing for the encoder to decide, so its
     * bytes go upstream verbatim. Those carry no modifiers, so the sticky chips
     * stay armed for the next real keystroke rather than being spent on them.
     */
    fun sendSpecialKey(key: TerminalKey) {
        if (key.ghosttyKey == GhosttyKey.UNIDENTIFIED) {
            upstream.trySend(key.bytes)
            return
        }
        engine.sendKeyPressRelease(
            key.ghosttyKey,
            key.mods or GhosttyMods.sticky(ctrlSticky, altSticky),
        )
        clearSticky()
    }

    /**
     * IME-committed text. Stays on the raw-bytes path (chuchu splits the same
     * way): a committed string has already thrown away the physical keys and the
     * modifier state that [GhosttyTerminalEngine.sendKey] needs, so re-deriving
     * them would be a guess. The sticky chips are applied here by hand instead.
     */
    fun sendTyped(bytes: ByteArray) {
        val c = ctrlSticky
        val a = altSticky
        upstream.trySend(TerminalIO.applyModifiers(bytes, c, a))
        clearSticky()
    }

    /** A physical key. Both edges go through the emulator's encoder. */
    fun sendHardwareKey(keyCode: Int, unicodeChar: Int, metaState: Int, action: Int): Boolean {
        val mapped = KeyMapper.map(keyCode, unicodeChar, metaState) ?: return false
        // Sticky chips stack on top of the real modifier state, so Ctrl-chip
        // then 'c' on a Bluetooth keyboard still interrupts.
        val mods = mapped.mods or GhosttyMods.sticky(ctrlSticky, altSticky)
        val withSticky = mapped.copy(mods = mods)
        if (action != GhosttyKeyAction.RELEASE) engine.scrollToActive()
        engine.sendKey(
            key = withSticky.key,
            codepoint = KeyMapper.effectiveCodepoint(withSticky),
            mods = mods,
            action = action,
            utf8 = KeyMapper.utf8For(withSticky, action),
        )
        if (action == GhosttyKeyAction.PRESS) clearSticky()
        return true
    }

    // Resize RPCs must land in order, and only the latest geometry matters —
    // a stale mid-keyboard-animation size landing last leaves the PTY wrong.
    val resizeRequests = remember(terminalId) { Channel<Pair<Int, Int>>(Channel.CONFLATED) }
    LaunchedEffect(terminalId) {
        for ((c, r) in resizeRequests) {
            runCatching { controller.resize(terminalId, c, r) }
                .onFailure { Log.w(TAG, "resize($c,$r) failed: ${it.message}") }
        }
    }

    LaunchedEffect(terminalId) {
        controller.exits.collect { e ->
            if (e.terminal_id == terminalId) {
                exitNotice = e.exit_code?.let { "shell exited (code $it)" } ?: "terminal closed"
            }
        }
    }

    // Attach + gapless snapshot-then-stream, re-run on (re)connect. The
    // subscribe-before-attach buffering is load-bearing: terminal.data is a
    // replay-free SharedFlow, so anything arriving between the attach response
    // and the collector subscribing would simply be dropped.
    LaunchedEffect(terminalId, gridReady, connected) {
        if (!gridReady || !connected) return@LaunchedEffect
        exitNotice = null
        // A reconnect gets a fresh emulator: the daemon replays the whole
        // scrollback snapshot, so replaying it into the old one would double it.
        engine.stop()
        engine.start(cols, rows)

        val pending = ArrayDeque<ByteArray>()
        var snapshotDone = false
        val lock = Mutex()
        val subscribed = CompletableDeferred<Unit>()
        val streamJob = launch {
            controller.output
                .onSubscription { subscribed.complete(Unit) }
                .collect { p ->
                    if (p.terminal_id != terminalId) return@collect
                    val bytes = TerminalIO.b64ToBytes(p.data_b64)
                    lock.withLock {
                        if (snapshotDone) engine.onRemoteBytes(bytes) else pending.addLast(bytes)
                    }
                }
        }
        subscribed.await()
        try {
            val snap = controller.attach(terminalId)
            lock.withLock {
                if (snap.snapshot_b64.isNotEmpty()) {
                    engine.onRemoteBytes(TerminalIO.b64ToBytes(snap.snapshot_b64))
                }
                while (pending.isNotEmpty()) engine.onRemoteBytes(pending.removeFirst())
                snapshotDone = true
            }
            // Sync the PTY to OUR fitted geometry — the snapshot may predate it.
            resizeRequests.trySend(cols to rows)
            awaitCancellation()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            exitNotice = if (TerminalErrors.isGone(t.message)) {
                TerminalErrors.goneMessage()
            } else {
                "attach failed: ${t.message ?: t.javaClass.simpleName}"
            }
        } finally {
            streamJob.cancel()
        }
    }

    // Selection text is read on the engine thread (the native handle never
    // leaves it) and re-read whenever the selection or the grid beneath it
    // changes, so Copy is never a frame behind what is highlighted.
    LaunchedEffect(selection, render.snapshot) {
        val sel = selection
        selectionText = if (sel == null) null else engine.selectionText(sel.anchorIndex, sel.focusIndex)
    }

    /**
     * Put the selection on the clipboard and drop it, which is also what
     * dismisses the floating menu. Shared by the menu's Copy and the
     * extra-keys row's Copy button so the two can never disagree.
     */
    fun copySelection() {
        selectionText?.takeIf { it.isNotEmpty() }?.let { text ->
            scope.launch { clipboard.setPlainText(text) }
            selection = null
        }
    }

    // The platform floating text-action menu — the same ActionMode Android
    // shows over any other text, which is the point: the maintainer's note was that the
    // native terminal selected text but offered no way to act on it.
    val textToolbar = LocalTextToolbar.current
    val toolbarVisible = TerminalSelectionToolbar.shouldShow(
        hasSelection = selection != null,
        hasText = !selectionText.isNullOrEmpty(),
        dragging = dragTarget != TerminalDragTarget.None,
    )
    val toolbarRect = selectionGeometry?.let {
        TerminalSelectionToolbar.contentRect(it, canvasOriginInRoot)
    }
    // Keyed on the *rect*, so a drag that only moves the highlight cannot
    // reopen the menu per frame: the rect is only recomputed into a show once
    // the finger is off (dragTarget clears) or the selection genuinely moves.
    LaunchedEffect(toolbarVisible, toolbarRect) {
        if (!toolbarVisible || toolbarRect == null) {
            textToolbar.hide()
            return@LaunchedEffect
        }
        textToolbar.showMenu(
            rect = toolbarRect,
            onCopyRequested = { copySelection() },
            onPasteRequested = {
                scope.launch {
                    // Through the emulator, for bracketed paste — the same path
                    // the extra-keys Paste button takes.
                    clipboard.getPlainText()?.takeIf { it.isNotEmpty() }?.let { engine.paste(it) }
                }
                selection = null
            },
            onCutRequested = null, // a terminal's scrollback is not editable
            onSelectAllRequested = {
                // The *viewport*, not the whole scrollback: selection indices
                // are viewport-relative, so anything wider could not be
                // highlighted, and copying text the user cannot see
                // highlighted is worse than selecting what is on screen.
                render.snapshot?.takeIf { it.codepoints.isNotEmpty() }?.let { snap ->
                    selection = TerminalSelection(0, snap.codepoints.size - 1)
                }
            },
        )
    }

    // Focus reporting (DECSET 1004). A prompt that redraws on focus, or a TUI
    // that dims an unfocused pane, is otherwise stuck in whichever state it
    // started in. The emulator drops these unless the app asked for them.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(engine, windowFocused) { engine.sendFocus(windowFocused) }

    DisposableEffect(terminalId) {
        onDispose {
            // The ActionMode lives in the window, not in the composition, so
            // leaving the screen has to end it explicitly.
            textToolbar.hide()
            controller.detach(terminalId)
            engine.dispose()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = render.title?.takeIf { it.isNotBlank() }
                            ?: info?.let { "${it.shell} · ${it.cwd}" }
                            ?: "Terminal",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation drawer")
                    }
                },
                windowInsets = WindowInsets(0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            (exitNotice ?: render.error)?.let { notice ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = notice,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            // The magnifier looks at the cell the dragged endpoint has landed
            // on. Unspecified means "no drag" — that is the modifier's own way
            // of being off, so no separate visibility flag is needed.
            val magnifierCenter = TerminalSelectionMagnifier.sourceCenter(
                cellIndex = TerminalSelectionMagnifier.draggedCell(selection, dragTarget),
                cols = selectionGeometry?.cols ?: 0,
                cellWidthPx = selectionGeometry?.cellWidthPx ?: 0f,
                cellHeightPx = selectionGeometry?.cellHeightPx ?: 0f,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { canvasOriginInRoot = it.positionInRoot() },
            ) {
                GhosttyCanvas(
                    // On the canvas rather than the Box so the magnifier's
                    // coordinates are the grid's own.
                    modifier = Modifier.magnifier(sourceCenter = { magnifierCenter }),
                    snapshot = render.snapshot,
                    cursorStyle = render.cursorStyle,
                    focused = windowFocused,
                    fontSizeSp = fontSizeSp,
                    onFontSizeChange = { fontSizeSp = it },
                    defaultBg = TERMINAL_BG,
                    cursorColor = TERMINAL_CURSOR,
                    cursorTextColor = TERMINAL_BG,
                    onGridChange = { c, r, cellW, cellH ->
                        cols = c
                        rows = r
                        engine.resize(c, r, cellW, cellH)
                        resizeRequests.trySend(c to r)
                        gridReady = true
                    },
                    onTap = { inputView?.showKeyboard() },
                    onPrimaryClick = { x, y ->
                        // Press+release. Silent unless the running app turned
                        // mouse tracking on — the emulator decides, not us.
                        engine.sendMouse(
                            GhosttyMouseAction.PRESS,
                            GhosttyMouseButton.LEFT,
                            GhosttyMods.NONE,
                            x,
                            y,
                            anyButtonPressed = false,
                            trackLastCell = false,
                        )
                        engine.sendMouse(
                            GhosttyMouseAction.RELEASE,
                            GhosttyMouseButton.LEFT,
                            GhosttyMods.NONE,
                            x,
                            y,
                            anyButtonPressed = false,
                            trackLastCell = false,
                        )
                    },
                    onScroll = { delta, x, y -> engine.scroll(delta, x, y) },
                    selection = selection,
                    onSelectionChange = { selection = it },
                    onSelectionGeometry = { selectionGeometry = it },
                    selectionColor = TERMINAL_SELECTION,
                    onAppMouseDrag = { action, x, y ->
                        engine.sendMouse(
                            action = action,
                            button = GhosttyMouseButton.LEFT,
                            mods = GhosttyMods.NONE,
                            x = x,
                            y = y,
                            anyButtonPressed = action != GhosttyMouseAction.RELEASE,
                            trackLastCell = true,
                        )
                    },
                    onSelectionDragChange = { dragging ->
                        dragTarget =
                            if (dragging) TerminalDragTarget.Focus else TerminalDragTarget.None
                    },
                )
                selectionGeometry?.let { geometry ->
                    // Which endpoint each handle owns depends on the drag
                    // direction — dragging the start handle must not silently
                    // swap anchor and focus.
                    val anchorIsStart =
                        selection?.let { it.anchorIndex <= it.focusIndex } ?: true
                    val handleColor = terminalHandleColor(
                        primary = MaterialTheme.colorScheme.primary,
                        fallback = TERMINAL_CURSOR,
                    )
                    // Both handles hang BELOW their line, so the square corner
                    // sits on the bottom edge of the first / last selected cell.
                    TerminalSelectionHandle(
                        tipX = geometry.startOffset.x,
                        tipY = geometry.startOffset.y + geometry.cellHeightPx,
                        side = TerminalHandleSide.Start,
                        color = handleColor,
                        cellWidthPx = geometry.cellWidthPx,
                        cellHeightPx = geometry.cellHeightPx,
                        cols = geometry.cols,
                        startCellProvider = {
                            selection?.let { minOf(it.anchorIndex, it.focusIndex) } ?: 0
                        },
                        onDragToCell = { cell ->
                            selection = selection?.withStart(cell, updateAnchor = anchorIsStart)
                        },
                        onDragActiveChange = { dragging ->
                            dragTarget =
                                if (dragging) TerminalDragTarget.Start else TerminalDragTarget.None
                        },
                    )
                    TerminalSelectionHandle(
                        tipX = geometry.endOffset.x,
                        tipY = geometry.endOffset.y,
                        side = TerminalHandleSide.End,
                        color = handleColor,
                        cellWidthPx = geometry.cellWidthPx,
                        cellHeightPx = geometry.cellHeightPx,
                        cols = geometry.cols,
                        startCellProvider = {
                            selection?.let { maxOf(it.anchorIndex, it.focusIndex) } ?: 0
                        },
                        onDragToCell = { cell ->
                            selection = selection?.withEnd(cell, updateAnchor = !anchorIsStart)
                        },
                        onDragActiveChange = { dragging ->
                            dragTarget =
                                if (dragging) TerminalDragTarget.End else TerminalDragTarget.None
                        },
                    )
                }
                // Zero-size and invisible: it exists purely to own the IME. The
                // canvas has no editable document of its own, so
                // [TerminalInputConnection] is what turns soft-keyboard edits
                // into PTY bytes — including the fix for Gboard composing,
                // which used to send every corrected word twice.
                AndroidView(
                    modifier = Modifier.size(1.dp),
                    factory = { ctx ->
                        TerminalInputView(ctx).also { v ->
                            v.onBytes = { bytes -> sendTyped(bytes) }
                            v.onHardwareKey = ::sendHardwareKey
                            v.suggestions = suggestionsEnabled
                            inputView = v
                        }
                    },
                    update = { v -> v.updateSuggestions(suggestionsEnabled) },
                    onRelease = { inputView = null },
                )
            }
            ExtraKeysRow(
                ctrlSticky = ctrlSticky,
                altSticky = altSticky,
                copyEnabled = !selectionText.isNullOrEmpty(),
                suggestionsEnabled = suggestionsEnabled,
                onCtrlToggle = { ctrlSticky = !ctrlSticky },
                onAltToggle = { altSticky = !altSticky },
                onSuggestionsToggle = {
                    // The IME restart rides the recomposition: the new value
                    // flows into the AndroidView update block, which calls
                    // restartInput on the view that owns the connection.
                    suggestionsEnabled = !suggestionsEnabled
                    settings.terminalSuggestionsEnabled = suggestionsEnabled
                },
                onKey = { key -> sendSpecialKey(key) },
                onPaste = {
                    scope.launch {
                        val text = clipboard.getPlainText()
                        // Through the emulator: only it knows whether the running app
                        // enabled bracketed paste, which is what stops a multi-line
                        // paste from executing line by line.
                        if (!text.isNullOrEmpty()) engine.paste(text)
                    }
                },
                // The selected text comes from the emulator, so the Android
                // clipboard can reach it — the defect the WebView renderer
                // never solved (xterm.js owned its own DOM selection).
                onCopy = { copySelection() },
            )
        }
    }
}

/**
 * A focusable, zero-content [View] whose only job is to answer the IME with
 * [TerminalInputConnection] and to route hardware key presses.
 *
 * Soft-keyboard edits arrive through the input connection as committed text
 * ([onBytes]); hardware keys the IME does not consume arrive here as
 * [onKeyDown]/[onKeyUp] and go to [onHardwareKey], which encodes them through
 * the emulator. Keys a terminal has no meaning for (Back, modifiers) are not
 * mapped and fall through to `super`, so Back still navigates.
 */
private class TerminalInputView(context: Context) : View(context) {

    var onBytes: ((ByteArray) -> Unit)? = null

    /** (keyCode, unicodeChar, metaState, [GhosttyKeyAction]) → handled. */
    var onHardwareKey: ((Int, Int, Int, Int) -> Boolean)? = null

    /** The row's "Abc" mode. Read when the IME asks for an [EditorInfo]. */
    var suggestions: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        applyTerminalEditorInfo(outAttrs, suggestions)
        return TerminalInputConnection(this) { bytes -> onBytes?.invoke(bytes) }
    }

    /** Flip the mode and make the *running* keyboard re-read [EditorInfo] —
     *  without the restart the change would only apply the next time the IME
     *  connects, i.e. after leaving and re-entering the terminal. */
    fun updateSuggestions(enabled: Boolean) {
        if (suggestions == enabled) return
        suggestions = enabled
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        dispatchHardware(keyCode, event) ?: super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        dispatchHardware(keyCode, event) ?: super.onKeyUp(keyCode, event)

    /** null when the key is not ours — the caller must defer to `super`. */
    private fun dispatchHardware(keyCode: Int, event: KeyEvent): Boolean? {
        val action = GhosttyKeyAction.fromAndroid(event.action, event.repeatCount) ?: return null
        return onHardwareKey?.invoke(keyCode, event.unicodeChar, event.metaState, action)
            ?.takeIf { it }
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}

private const val TAG = "TerminalScreen"

// The terminal's palette. Fixed dark by design, and unchanged from the xterm.js
// renderer this replaced so a long-lived shell looks the same as it always did.
private val TERMINAL_BG = Color(0xFF1C1917)
private val TERMINAL_CURSOR = Color(0xFFF97316)
/** `#f9731640` — the accent at 25%, composited over the cell. */
private val TERMINAL_SELECTION = Color(0x40F97316)
private val TERMINAL_BG_RGB = intArrayOf(0x1C, 0x19, 0x17)
private val TERMINAL_FG_RGB = intArrayOf(0xE7, 0xE5, 0xE4)
private val TERMINAL_CURSOR_RGB = intArrayOf(0xF9, 0x73, 0x16)
