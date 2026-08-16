// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/service/terminal/TerminalSessionEngine.kt
//
// Ported: the terminal-hosting seam only — applyTerminalOptions, feedRemoteChunk,
// flushPtyWrites, resize, scroll/scrollToActive, writePaste, the requestSnapshot
// throttle and emitSnapshot. NOT ported: chuchu's SSH/mosh/local-shell transports,
// reconnect policy, host-key prompts, SFTP and multiplexer machinery — roughly
// 700 of its 1287 lines. Our transport is the marmalade daemon's `terminal.*`
// RPC, which the SCREEN owns; this engine is transport-free and reaches the wire
// only through [sendUpstream].
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.terminal

import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the renderer draws. Immutable, and [TerminalSnapshot] has structural
 * equals, so an identical frame does not recompose.
 *
 * @param bellTick monotonic count of bells since [GhosttyTerminalEngine.start];
 *   the UI reacts to the value *changing*, not to its magnitude.
 * @param error set when the native library is unavailable — the screen shows a
 *   notice rather than a permanently blank grid.
 */
data class TerminalRenderState(
    val snapshot: TerminalSnapshot? = null,
    /**
     * The cursor's shape and blink mode. Carried beside [snapshot] rather
     * than inside it because the snapshot's byte layout is written by
     * vendored chuchu code (see [TerminalCursorStyle]).
     */
    val cursorStyle: TerminalCursorStyle = TerminalCursorStyle.Default,
    val title: String? = null,
    val pwd: String? = null,
    val bellTick: Int = 0,
    val error: String? = null,
)

/**
 * Hosts one libghostty-vt terminal: bytes in from the daemon, a decoded grid
 * out to Compose, and the terminal's own upstream answers (DA1/DSR/DEC 2048,
 * mouse) pushed back through [sendUpstream].
 *
 * **Thread confinement is the invariant.** Every native call runs on a single
 * dedicated thread, and the raw handle never leaves this class — libghostty's
 * terminal state is not thread-safe, and chuchu's habit of passing the handle
 * out to the UI for selection formatting is a real data race we are not
 * inheriting (selection lands next stage as suspend funs on this class).
 * Public methods are fire-and-forget: they enqueue onto that thread, so calls
 * made in order are *applied* in order.
 *
 * Resize is deliberately half a job here — [resize] only sizes the emulator.
 * The screen owns the ordered `terminal.resize` RPC that tells the daemon's PTY
 * about it, because that is a conflated channel and the engine has no business
 * knowing about the wire.
 *
 * @param sendUpstream receives drained PTY writes and encoded pastes. Called on
 *   the engine thread; must be cheap and thread-safe (the screen funnels it
 *   into an ordered channel).
 * @param publishClipboard OSC 52 clipboard writes from the running program.
 * @param dispatcher inject a test dispatcher to drive the engine deterministically;
 *   null means the engine owns a private single-thread dispatcher.
 * @param nowMs time source for the snapshot throttle — inject the test
 *   scheduler's virtual clock so the 16 ms coalescing is testable.
 */
class GhosttyTerminalEngine(
    private val core: TermCore,
    private val scope: CoroutineScope,
    private val sendUpstream: (ByteArray) -> Unit,
    private val publishClipboard: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val ownedDispatcher: ExecutorCoroutineDispatcher? =
        if (dispatcher == null) {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "terminal-engine").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        } else {
            null
        }

    /** The confinement thread. Every field below is touched only from here. */
    private val engineContext: CoroutineDispatcher = dispatcher ?: ownedDispatcher!!

    private var handle: Long = 0L
    private var cols: Int = 80
    private var rows: Int = 24
    private var cellWidth: Int = 1
    private var cellHeight: Int = 1
    private var title: String? = null
    private var pwd: String? = null
    private var bellTick: Int = 0
    private var lastSnapshotAtMs: Long = 0L
    private var snapshotScheduled: Boolean = false

    // Replayed onto every freshly created terminal (applyTerminalOptions), so a
    // reconnect's new handle comes up in the app's colors rather than ghostty's.
    private var pendingColorScheme: Int? = null
    private var pendingDefaultColors: DefaultColors? = null

    private data class DefaultColors(
        val fg: IntArray?,
        val bg: IntArray?,
        val cursor: IntArray?,
        val palette: ByteArray?,
    )

    private val _state = MutableStateFlow(TerminalRenderState())
    val state: StateFlow<TerminalRenderState> = _state.asStateFlow()

    /**
     * Create the terminal at [startCols] x [startRows] and paint an empty
     * frame. Safe to call again — an existing terminal is destroyed first, which
     * is what a reconnect does.
     */
    fun start(startCols: Int, startRows: Int) = onEngineThread {
        if (!core.isLoaded) {
            _state.value = TerminalRenderState(
                error = "Native terminal library ${core.status()}.",
            )
            return@onEngineThread
        }
        if (handle != 0L) {
            core.destroy(handle)
            handle = 0L
        }
        cols = startCols.coerceAtLeast(1)
        rows = startRows.coerceAtLeast(1)
        bellTick = 0
        title = null
        pwd = null
        handle = core.create(cols, rows, MAX_SCROLLBACK)
        applyTerminalOptions()
        requestSnapshot(force = true)
    }

    /** Destroy the terminal and clear the render state. */
    fun stop() = onEngineThread { destroyTerminal() }

    /**
     * Release the terminal and the private thread.
     *
     * Deliberately does NOT go through [scope]: a screen disposes the engine
     * from `onDispose`, by which point its composition scope is being
     * cancelled — routing the destroy through it would leak the native
     * terminal. The task goes straight to the owned executor instead, and
     * `close()` is a graceful shutdown, so it still runs.
     */
    fun dispose() {
        val owned = ownedDispatcher
        if (owned == null) {
            stop()
            return
        }
        owned.executor.execute { destroyTerminal() }
        owned.close()
    }

    private fun destroyTerminal() {
        if (handle != 0L) {
            core.destroy(handle)
            handle = 0L
        }
        snapshotScheduled = false
        lastSnapshotAtMs = 0L
        title = null
        pwd = null
        bellTick = 0
        _state.value = TerminalRenderState()
    }

    /**
     * PTY output from the daemon. Drains before AND after the write: before, so
     * an answer queued by the previous chunk leaves ahead of the new input;
     * after, so this chunk's queries are answered immediately — a startup probe
     * burst that never resolves leaves the shell waiting on DA1/DSR forever.
     */
    fun onRemoteBytes(chunk: ByteArray) = onEngineThread {
        if (handle == 0L || chunk.isEmpty()) return@onEngineThread
        val wasImageLoading = core.isImageLoading(handle)
        flushPtyWrites()
        core.writeRemote(handle, chunk)
        flushPtyWrites()
        val isImageLoading = core.isImageLoading(handle)
        when {
            // Transmission just finished — force a frame so the image appears
            // without waiting for the next byte to arrive.
            wasImageLoading && !isImageLoading -> requestSnapshot(force = true)
            // Mid-transmission frames would paint a half-decoded image.
            !isImageLoading -> requestSnapshot()
        }
    }

    /**
     * Size the emulator. The caller is also responsible for the daemon-side
     * `terminal.resize` — see the class doc.
     */
    fun resize(newCols: Int, newRows: Int, newCellWidth: Int, newCellHeight: Int) = onEngineThread {
        if (newCols <= 0 || newRows <= 0 || newCellWidth <= 0 || newCellHeight <= 0) {
            return@onEngineThread
        }
        cols = newCols
        rows = newRows
        cellWidth = newCellWidth
        cellHeight = newCellHeight
        if (handle == 0L) return@onEngineThread
        core.resize(handle, cols, rows, cellWidth, cellHeight)
        // The resize itself can queue an in-band size report (DEC 2048); flush
        // it or Textual-based TUIs never notice the new geometry.
        flushPtyWrites()
        applyMouseEncodingSize()
        requestSnapshot(force = true)
    }

    /** Scroll the viewport by [delta] rows. Negative is towards scrollback. */
    fun scroll(delta: Int, x: Float, y: Float) = onEngineThread {
        if (handle == 0L || delta == 0) return@onEngineThread
        core.scroll(handle, delta, x, y)
        // A mouse-mode app answers a wheel event with bytes, not with a redraw.
        flushPtyWrites()
        requestSnapshot(force = true)
    }

    /** Jump the viewport back to the active (cursor) screen. */
    fun scrollToActive() = onEngineThread {
        if (handle == 0L) return@onEngineThread
        core.scrollToActive(handle)
        requestSnapshot(force = true)
    }

    /**
     * Paste clipboard text. Goes through the terminal rather than straight to
     * the wire because only it knows whether the running app enabled bracketed
     * paste (DECSET 2004) — the thing that stops a multi-line paste from
     * running line by line.
     */
    fun paste(text: String) = onEngineThread {
        if (handle == 0L || text.isEmpty()) return@onEngineThread
        val encoded = core.encodePaste(handle, text) ?: return@onEngineThread
        if (encoded.isNotEmpty()) sendUpstream(encoded)
    }

    /**
     * Encode one key event in the terminal's current modes and send it.
     *
     * The encoding has to happen HERE rather than from a byte table on the UI
     * side because only the terminal knows its modes: DECCKM decides whether an
     * arrow is `ESC [ A` or `ESC O A`, and the kitty keyboard protocol changes
     * the shape of everything. Empty output is normal and means "this key sends
     * nothing" (a bare modifier, or a release with no kitty protocol active).
     *
     * Press and release are separate calls — see [sendKeyPressRelease] for the
     * soft-key case where both are synthesised.
     */
    fun sendKey(key: Int, codepoint: Int, mods: Int, action: Int, utf8: String?) = onEngineThread {
        if (handle == 0L) return@onEngineThread
        val encoded = core.encodeKey(handle, key, codepoint, mods, action, utf8)
        if (encoded == null || encoded.isEmpty()) return@onEngineThread
        sendUpstream(encoded)
    }

    /** A soft key: no real key-up exists, so synthesise the pair in order. */
    fun sendKeyPressRelease(key: Int, mods: Int) {
        sendKey(key, codepoint = 0, mods = mods, action = GhosttyKeyAction.PRESS, utf8 = null)
        sendKey(key, codepoint = 0, mods = mods, action = GhosttyKeyAction.RELEASE, utf8 = null)
    }

    /**
     * Report a focus change. Silent unless the running app asked for focus
     * events (DECSET 1004), which the encoder decides — the screen just tells
     * the truth about the window and lets the terminal ignore it.
     */
    fun sendFocus(focused: Boolean) = onEngineThread {
        if (handle == 0L) return@onEngineThread
        val encoded = core.encodeFocus(handle, focused)
        if (encoded == null || encoded.isEmpty()) return@onEngineThread
        sendUpstream(encoded)
    }

    /**
     * Forward a touch as a mouse event. Empty output is the normal case and
     * means the running app is not tracking the mouse — which is exactly the
     * gate we want, decided by the terminal rather than guessed at by the UI.
     */
    fun sendMouse(
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ) = onEngineThread {
        if (handle == 0L) return@onEngineThread
        val encoded =
            core.encodeMouse(handle, action, button, mods, x, y, anyButtonPressed, trackLastCell)
        if (encoded == null || encoded.isEmpty()) return@onEngineThread
        sendUpstream(encoded)
    }

    /**
     * The text of a selection, given its two endpoints as viewport cell
     * indices into the snapshot the UI is currently showing.
     *
     * Suspending, and confined to the engine thread, **on purpose**: chuchu
     * formats selections by passing the raw native handle out to the UI thread,
     * which races the engine's own writes into terminal state that is not
     * thread-safe. Here the handle never leaves this class and the caller
     * awaits a String.
     *
     * Three sources, best first: the screen-space native call (sees scrollback
     * and unwraps soft-wrapped lines), the viewport-space native call, and a
     * pure scrape of the snapshot, which cannot fail.
     */
    suspend fun selectionText(anchorIndex: Int, focusIndex: Int): String? =
        withContext(engineContext) {
            val snapshot = _state.value.snapshot ?: return@withContext null
            val range = TerminalSelection(anchorIndex, focusIndex)
                .normalized(snapshot.codepoints.size) ?: return@withContext null
            val h = handle
            if (h == 0L) return@withContext TerminalSelections.extractText(snapshot, range)
            val screen = TerminalSelections.screenRange(snapshot, range)
            core.formatSelectionScreenRange(h, screen.first, screen.last)
                ?: core.formatSelectionRange(h, range.first, range.last)
                ?: TerminalSelections.extractText(snapshot, range)
        }

    /** Dark/light hint for apps that query it (OSC 11 / DSR-CPR style probes). */
    fun setColorScheme(dark: Boolean) {
        val scheme = if (dark) SCHEME_DARK else SCHEME_LIGHT
        pendingColorScheme = scheme
        onEngineThread {
            if (handle == 0L) return@onEngineThread
            core.setColorScheme(handle, scheme)
        }
    }

    /** Theme colors. `palette = null` keeps ghostty's own 256-color table. */
    fun setDefaultColors(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) {
        pendingDefaultColors = DefaultColors(fg, bg, cursor, palette)
        onEngineThread {
            if (handle == 0L) return@onEngineThread
            core.setDefaultColors(handle, fg, bg, cursor, palette)
            requestSnapshot(force = true)
        }
    }

    private fun onEngineThread(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(engineContext, block = block)
    }

    private fun applyTerminalOptions() {
        val h = handle
        if (h == 0L) return
        pendingColorScheme?.let { core.setColorScheme(h, it) }
        pendingDefaultColors?.let { core.setDefaultColors(h, it.fg, it.bg, it.cursor, it.palette) }
        // A reconnect's fresh terminal starts with no mouse geometry, and the
        // grid has not changed, so no resize is coming to supply it.
        applyMouseEncodingSize()
    }

    /**
     * Teach the mouse encoder our pixel geometry. Until it knows, every mouse
     * event encodes to nothing.
     *
     * The screen size is derived as grid × cell rather than taken from the
     * canvas: the canvas is a whole number of cells plus up to one cell of
     * slack, and the encoder's cell arithmetic has to agree with the renderer's
     * or a tap lands one column off at the right edge. Padding is zero — the
     * grid starts at the canvas origin.
     */
    private fun applyMouseEncodingSize() {
        val h = handle
        if (h == 0L) return
        core.setMouseEncodingSize(
            h,
            cols * cellWidth,
            rows * cellHeight,
            cellWidth,
            cellHeight,
            0,
            0,
            0,
            0,
        )
    }

    /**
     * Push everything the terminal wants to say upstream. Bounded at
     * [MAX_DRAIN_ROUNDS] because answering a query can itself queue bytes; an
     * unbounded loop would let a pathological stream spin the engine thread.
     */
    private fun flushPtyWrites() {
        if (handle == 0L) return
        repeat(MAX_DRAIN_ROUNDS) {
            val pending = core.drainPtyWrites(handle)
            if (pending.isEmpty()) return
            sendUpstream(pending)
        }
    }

    /**
     * Coalesce frames to ~60 Hz: emit immediately when the last frame is old
     * enough (leading edge, so a single keystroke echoes with no added latency),
     * otherwise schedule ONE trailing frame so a burst of output still ends on a
     * current picture.
     */
    private fun requestSnapshot(force: Boolean = false) {
        if (handle == 0L) return
        val now = nowMs()
        val elapsed = now - lastSnapshotAtMs
        if (force || elapsed >= SNAPSHOT_INTERVAL_MS) {
            snapshotScheduled = false
            emitSnapshot()
            lastSnapshotAtMs = now
            return
        }
        if (snapshotScheduled) return
        snapshotScheduled = true
        val waitMs = (SNAPSHOT_INTERVAL_MS - elapsed).coerceAtLeast(1L)
        onEngineThread {
            delay(waitMs)
            snapshotScheduled = false
            if (handle == 0L) return@onEngineThread
            emitSnapshot()
            lastSnapshotAtMs = nowMs()
        }
    }

    /** One decode + one poll round + ONE StateFlow write, so Compose sees one frame. */
    private fun emitSnapshot() {
        val h = handle
        if (h == 0L) return
        try {
            val snap = core.snapshot(h)
            val cursor = core.cursorStyle(h)
            core.pollTitle(h)?.let { title = it }
            core.pollPwd(h)?.let { pwd = it }
            core.pollClipboard(h)?.let { publishClipboard(it.toString(Charsets.UTF_8)) }
            bellTick += core.drainBellCount(h)
            _state.value = TerminalRenderState(
                snapshot = snap,
                cursorStyle = cursor,
                title = title,
                pwd = pwd,
                bellTick = bellTick,
                error = null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "snapshot failed", e)
        }
    }

    private companion object {
        const val TAG = "GhosttyEngine"

        /** Scrollback lines kept in the emulator (chuchu ships 1000). */
        const val MAX_SCROLLBACK = 4000

        /** ~60 Hz. Anything finer is invisible and costs a full grid decode. */
        const val SNAPSHOT_INTERVAL_MS = 16L

        const val MAX_DRAIN_ROUNDS = 8
        const val SCHEME_LIGHT = 0
        const val SCHEME_DARK = 1
    }
}
