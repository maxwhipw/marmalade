package app.marmalade.android.terminal

/**
 * A [TermCore] that records what the engine asked it to do, in order.
 *
 * The point of the recording is that the engine's contract IS an ordering
 * contract: drain before AND after every write, drain after a resize or a
 * scroll, and never snapshot mid-image-transmission. A fake that only returned
 * values could not prove any of that.
 */
class FakeTermCore(
    override val isLoaded: Boolean = true,
) : TermCore {

    /** Every call, in order, as `"name"` or `"name:detail"`. */
    val calls = mutableListOf<String>()

    var nextHandle: Long = 42L
    var destroyed: Int = 0
    var snapshotCount: Int = 0

    /** Queued drain results; an exhausted queue drains empty. */
    val ptyWrites = ArrayDeque<ByteArray>()
    var imageLoading: Boolean = false
    var title: String? = null
    var pwd: String? = null
    var clipboard: ByteArray? = null
    var bells: Int = 0
    var pasteEncoding: (String) -> ByteArray? = { it.toByteArray(Charsets.UTF_8) }

    /** Recorded [setDefaultColors] / [setColorScheme] arguments. */
    val colorSchemes = mutableListOf<Int>()
    val defaultColors = mutableListOf<Triple<IntArray?, IntArray?, IntArray?>>()

    override fun status(): String = if (isLoaded) "loaded" else "not loaded (test)"

    override fun create(cols: Int, rows: Int, maxScrollback: Int): Long {
        calls += "create:$cols x $rows/$maxScrollback"
        return nextHandle
    }

    override fun destroy(handle: Long) {
        calls += "destroy"
        destroyed++
    }

    /** Runs inside [writeRemote] — lets a test flip [imageLoading] mid-chunk. */
    var onWrite: (() -> Unit)? = null

    override fun writeRemote(handle: Long, data: ByteArray) {
        calls += "write:${data.toString(Charsets.UTF_8)}"
        onWrite?.invoke()
    }

    override fun resize(handle: Long, cols: Int, rows: Int, cellW: Int, cellH: Int) {
        calls += "resize:$cols x $rows/$cellW x $cellH"
    }

    override fun scroll(handle: Long, delta: Int, x: Float, y: Float) {
        calls += "scroll:$delta"
    }

    override fun scrollToActive(handle: Long) {
        calls += "scrollToActive"
    }

    /** Override to give the engine a grid with real geometry. */
    var nextSnapshot: () -> TerminalSnapshot = { blankSnapshot() }

    override fun snapshot(handle: Long): TerminalSnapshot {
        calls += "snapshot"
        snapshotCount++
        return nextSnapshot()
    }

    /** What [cursorStyle] answers; a test flips it to prove the style flows. */
    var cursorStyle: TerminalCursorStyle = TerminalCursorStyle.Default

    override fun cursorStyle(handle: Long): TerminalCursorStyle {
        calls += "cursorStyle"
        return cursorStyle
    }

    override fun isImageLoading(handle: Long): Boolean = imageLoading

    override fun pollTitle(handle: Long): String? = title

    override fun pollPwd(handle: Long): String? = pwd

    override fun pollClipboard(handle: Long): ByteArray? = clipboard

    override fun drainBellCount(handle: Long): Int = bells.also { bells = 0 }

    override fun setColorScheme(handle: Long, scheme: Int) {
        calls += "setColorScheme:$scheme"
        colorSchemes += scheme
    }

    override fun setDefaultColors(
        handle: Long,
        fg: IntArray?,
        bg: IntArray?,
        cursor: IntArray?,
        palette: ByteArray?,
    ) {
        calls += "setDefaultColors"
        defaultColors += Triple(fg, bg, cursor)
    }

    override fun encodePaste(handle: Long, text: String): ByteArray? {
        calls += "encodePaste:$text"
        return pasteEncoding(text)
    }

    /** Bytes [encodeKey] answers with; null means "this key sends nothing". */
    var keyEncoding: (String) -> ByteArray? = { it.toByteArray(Charsets.UTF_8) }

    override fun encodeKey(
        handle: Long,
        key: Int,
        codepoint: Int,
        mods: Int,
        action: Int,
        utf8: String?,
    ): ByteArray? {
        calls += "encodeKey:$key/$codepoint/$mods/$action/${utf8 ?: "-"}"
        return keyEncoding("K$key.$action")
    }

    /** Selection text the native formatters answer with; null = unavailable. */
    var screenSelectionText: ((Int, Int) -> String?) = { s, e -> "screen[$s..$e]" }
    var viewportSelectionText: ((Int, Int) -> String?) = { s, e -> "viewport[$s..$e]" }

    override fun formatSelectionScreenRange(handle: Long, startCell: Int, endCell: Int): String? {
        calls += "formatScreen:$startCell..$endCell"
        return screenSelectionText(startCell, endCell)
    }

    override fun formatSelectionRange(handle: Long, startCell: Int, endCell: Int): String? {
        calls += "formatViewport:$startCell..$endCell"
        return viewportSelectionText(startCell, endCell)
    }

    /** Bytes [encodeFocus]/[encodeMouse] answer with; null means "nothing". */
    var focusEncoding: (Boolean) -> ByteArray? = { "F$it".toByteArray(Charsets.UTF_8) }
    var mouseEncoding: (Int) -> ByteArray? = { "M$it".toByteArray(Charsets.UTF_8) }

    override fun encodeFocus(handle: Long, focused: Boolean): ByteArray? {
        calls += "encodeFocus:$focused"
        return focusEncoding(focused)
    }

    override fun encodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ): ByteArray? {
        calls += "encodeMouse:$action/$button/$mods/$x,$y/$anyButtonPressed/$trackLastCell"
        return mouseEncoding(action)
    }

    override fun setMouseEncodingSize(
        handle: Long,
        screenWidth: Int,
        screenHeight: Int,
        cellWidth: Int,
        cellHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
        paddingLeft: Int,
        paddingRight: Int,
    ) {
        calls += "mouseSize:${screenWidth}x$screenHeight/${cellWidth}x$cellHeight/" +
            "$paddingTop,$paddingBottom,$paddingLeft,$paddingRight"
    }

    override fun drainPtyWrites(handle: Long): ByteArray {
        val out = ptyWrites.removeFirstOrNull() ?: ByteArray(0)
        calls += "drain:${out.toString(Charsets.UTF_8)}"
        return out
    }

    companion object {
        fun blankSnapshot(
            cols: Int = 2,
            rows: Int = 1,
            viewportScrollY: Int = 0,
        ): TerminalSnapshot {
            val cells = cols * rows
            return TerminalSnapshot(
                cols = cols,
                rows = rows,
                cursorX = 0,
                cursorY = 0,
                cursorVisible = true,
                defaultBgArgb = 0xFF1C1917.toInt(),
                defaultFgArgb = 0xFFE7E5E4.toInt(),
                codepoints = IntArray(cells) { 32 },
                fgArgb = IntArray(cells) { 0xFFE7E5E4.toInt() },
                bgArgb = IntArray(cells) { 0xFF1C1917.toInt() },
                flags = ByteArray(cells),
                viewportScrollY = viewportScrollY,
            )
        }
    }
}
