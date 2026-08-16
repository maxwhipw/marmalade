package app.marmalade.android.terminal

/**
 * The native-terminal operations [GhosttyTerminalEngine] needs, named as
 * operations rather than as JNI entry points.
 *
 * The engine talks to this instead of to [GhosttyBridge] directly for one
 * reason: a JVM unit test cannot load the `.so`, and half the value of the
 * engine is in its ordering (drain → write → drain) and its snapshot
 * throttling, which are exactly the parts a digital twin can prove offline
 * (CLAUDE.md, "Tests where they earn their keep"). [GhosttyTermCore] is the
 * real implementation; the tests drive a fake.
 *
 * Note [snapshot] returns a decoded [TerminalSnapshot], not the raw buffer:
 * image decoding needs `android.graphics.Bitmap`, so it lives on the Android
 * side of this seam and the engine stays framework-free.
 */
interface TermCore {
    /** False when the native library failed to load — the caller must degrade. */
    val isLoaded: Boolean

    /** Human-readable load status, for the "native terminal unavailable" notice. */
    fun status(): String

    fun create(cols: Int, rows: Int, maxScrollback: Int): Long
    fun destroy(handle: Long)
    fun writeRemote(handle: Long, data: ByteArray)
    fun resize(handle: Long, cols: Int, rows: Int, cellW: Int, cellH: Int)
    fun scroll(handle: Long, delta: Int, x: Float, y: Float)
    fun scrollToActive(handle: Long)

    /** The full grid + kitty-image placements for the current viewport. */
    fun snapshot(handle: Long): TerminalSnapshot

    /**
     * The cursor's shape and blink mode (DECSCUSR). Read alongside [snapshot]
     * rather than from it: the snapshot's wire format is written by vendored
     * chuchu code we do not extend.
     */
    fun cursorStyle(handle: Long): TerminalCursorStyle

    /** True while a kitty image transmission is still being assembled. */
    fun isImageLoading(handle: Long): Boolean

    fun pollTitle(handle: Long): String?
    fun pollPwd(handle: Long): String?

    /** OSC 52 clipboard write from the running program, or null. */
    fun pollClipboard(handle: Long): ByteArray?

    /** Bells since the last call (drains the counter). */
    fun drainBellCount(handle: Long): Int

    fun setColorScheme(handle: Long, scheme: Int)
    fun setDefaultColors(
        handle: Long,
        fg: IntArray?,
        bg: IntArray?,
        cursor: IntArray?,
        palette: ByteArray?,
    )

    /** Clipboard text → the bytes to send, bracketed when the app asked for it. */
    fun encodePaste(handle: Long, text: String): ByteArray?

    /**
     * One key press/release → the bytes it sends *in the terminal's current
     * modes*. This is why the port exists on the key path: the same arrow key
     * is `ESC [ A` or `ESC O A` depending on DECCKM, and a fixed byte constant
     * gets it wrong in vim half the time.
     *
     * @param key a [GhosttyKey] physical key, or 0 when only [codepoint] is known.
     * @param mods a [GhosttyMods] bitfield.
     * @param action a [GhosttyKeyAction].
     * @param utf8 the text this press types, or null when it types none — see
     *   [KeyMapper.utf8For].
     */
    fun encodeKey(
        handle: Long,
        key: Int,
        codepoint: Int,
        mods: Int,
        action: Int,
        utf8: String?,
    ): ByteArray?

    /**
     * Selection text for a *screen* cell range — screen space includes
     * scrollback, so this is the call that reads a selection made above the
     * viewport, and the one that unwraps a soft-wrapped line back into one.
     * Null when the range is not addressable.
     */
    fun formatSelectionScreenRange(handle: Long, startCell: Int, endCell: Int): String?

    /** The same, in *viewport* cell space. The fallback when screen space fails. */
    fun formatSelectionRange(handle: Long, startCell: Int, endCell: Int): String?

    /**
     * Focus gained/lost → the report bytes, empty unless the running app turned
     * focus reporting on (DECSET 1004). Shells that redraw a prompt on focus
     * (starship, powerlevel10k) and TUIs that dim an unfocused pane need it.
     */
    fun encodeFocus(handle: Long, focused: Boolean): ByteArray?

    /**
     * A touch, as a mouse event, encoded in whatever mouse protocol the running
     * app selected — or empty when it selected none, which is how "only forward
     * taps in mouse mode" is enforced without the client tracking modes.
     *
     * **Requires [setMouseEncodingSize] first**: with no pixel geometry the
     * encoder cannot turn [x]/[y] into a cell and answers empty.
     *
     * @param action a [GhosttyMouseAction]; [button] a [GhosttyMouseButton].
     * @param x, y canvas-local pixels.
     * @param trackLastCell suppress motion events that stay inside the same
     *   cell — a drag otherwise floods the PTY at touch-event rate.
     */
    fun encodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ): ByteArray?

    /** The pixel geometry [encodeMouse] maps touch coordinates through. */
    fun setMouseEncodingSize(
        handle: Long,
        screenWidth: Int,
        screenHeight: Int,
        cellWidth: Int,
        cellHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
        paddingLeft: Int,
        paddingRight: Int,
    )

    /**
     * Bytes the *terminal itself* wants to send upstream — DA1/DSR query
     * answers, DEC 2048 size reports, mouse events. Draining these is the
     * capability the whole port exists for (ADR 0015 revisit).
     */
    fun drainPtyWrites(handle: Long): ByteArray
}
