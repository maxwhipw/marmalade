package app.marmalade.android.terminal

/**
 * [TermCore] backed by the real libghostty-vt JNI surface.
 *
 * Thin by design: the only logic here is the snapshot decode (grid buffer +
 * image buffer → [TerminalSnapshot]), which has to happen on this side of the
 * seam because [TerminalImages] needs `android.graphics.Bitmap`.
 */
class GhosttyTermCore(
    private val bridge: GhosttyBridge = GhosttyBridge(),
) : TermCore {

    override val isLoaded: Boolean get() = bridge.isLoaded()

    override fun status(): String = bridge.nativeStatus()

    override fun create(cols: Int, rows: Int, maxScrollback: Int): Long =
        bridge.nativeCreate(cols, rows, maxScrollback)

    override fun destroy(handle: Long) = bridge.nativeDestroy(handle)

    override fun writeRemote(handle: Long, data: ByteArray) =
        bridge.nativeWriteRemote(handle, data)

    override fun resize(handle: Long, cols: Int, rows: Int, cellW: Int, cellH: Int) =
        bridge.nativeResize(handle, cols, rows, cellW, cellH)

    override fun scroll(handle: Long, delta: Int, x: Float, y: Float) =
        bridge.nativeScroll(handle, delta, x, y)

    override fun scrollToActive(handle: Long) = bridge.nativeScrollToActive(handle)

    override fun snapshot(handle: Long): TerminalSnapshot {
        val images = TerminalImages.parseImages(bridge.nativeSnapshotImages(handle))
        return TerminalSnapshot.fromByteBuffer(bridge.nativeSnapshot(handle), images)
    }

    override fun cursorStyle(handle: Long): TerminalCursorStyle =
        TerminalCursorStyle.decode(bridge.nativeCursorStyle(handle))

    override fun isImageLoading(handle: Long): Boolean = bridge.nativeIsImageLoading(handle)

    override fun pollTitle(handle: Long): String? = bridge.nativePollTitle(handle)

    override fun pollPwd(handle: Long): String? = bridge.nativePollPwd(handle)

    override fun pollClipboard(handle: Long): ByteArray? = bridge.nativePollClipboard(handle)

    override fun drainBellCount(handle: Long): Int = bridge.nativeDrainBellCount(handle)

    override fun setColorScheme(handle: Long, scheme: Int) =
        bridge.nativeSetColorScheme(handle, scheme)

    override fun setDefaultColors(
        handle: Long,
        fg: IntArray?,
        bg: IntArray?,
        cursor: IntArray?,
        palette: ByteArray?,
    ) = bridge.nativeSetDefaultColors(handle, fg, bg, cursor, palette)

    override fun encodePaste(handle: Long, text: String): ByteArray? =
        bridge.nativeEncodePaste(handle, text)

    override fun encodeKey(
        handle: Long,
        key: Int,
        codepoint: Int,
        mods: Int,
        action: Int,
        utf8: String?,
    ): ByteArray? = bridge.nativeEncodeKey(handle, key, codepoint, mods, action, utf8)

    override fun formatSelectionScreenRange(handle: Long, startCell: Int, endCell: Int): String? =
        bridge.nativeFormatSelectionScreenRange(handle, startCell, endCell)

    override fun formatSelectionRange(handle: Long, startCell: Int, endCell: Int): String? =
        bridge.nativeFormatSelectionRange(handle, startCell, endCell)

    override fun encodeFocus(handle: Long, focused: Boolean): ByteArray? =
        bridge.nativeEncodeFocus(handle, focused)

    override fun encodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ): ByteArray? =
        bridge.nativeEncodeMouse(handle, action, button, mods, x, y, anyButtonPressed, trackLastCell)

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
    ) = bridge.nativeSetMouseEncodingSize(
        handle,
        screenWidth,
        screenHeight,
        cellWidth,
        cellHeight,
        paddingTop,
        paddingBottom,
        paddingLeft,
        paddingRight,
    )

    override fun drainPtyWrites(handle: Long): ByteArray = bridge.nativeDrainPtyWrites(handle)
}
