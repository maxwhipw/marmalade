// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/service/terminal/GhosttyBridge.kt
//
// Changed: package (ours), library name ("marmalade_term", not "chuchu_jni"),
// and this header. The declarations themselves are chuchu's, and they have to
// stay in lockstep with the Zig exports in
// native/src/bridge/chuchu_snapshot.zig — JNI resolves a native method from
// this class's *package + class name*, so renaming or moving this class
// silently breaks every call unless the Zig prefix moves with it.
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.terminal

import java.nio.ByteBuffer

/**
 * The JNI surface of libghostty-vt (see `native/`, and
 * `docs/decisions/0015-terminal-stays-on-xtermjs.md` for why it exists).
 *
 * Bytes in via [nativeWriteRemote], a flat grid snapshot out via
 * [nativeSnapshot] (decode with [TerminalSnapshot.fromByteBuffer]). The
 * capability that motivated the port is [nativeDrainPtyWrites]: the terminal
 * *answers* queries (DA1, DSR, DEC 2048 size reports) rather than echoing
 * them, so an app's startup probe burst resolves instead of painting garbage.
 *
 * Load failure is not fatal by construction — [isLoaded] is false and the
 * caller falls back rather than the process dying at class-init.
 */
class GhosttyBridge {
    companion object {
        private val loadError: Throwable? = runCatching {
            System.loadLibrary("marmalade_term")
        }.exceptionOrNull()
    }

    fun nativeStatus(): String {
        return if (loadError == null) {
            "loaded"
        } else {
            val message = loadError.message?.takeIf { it.isNotBlank() } ?: "unknown"
            "not loaded (${loadError::class.simpleName}: $message)"
        }
    }

    fun isLoaded(): Boolean = loadError == null

    external fun nativeVersion(): String
    external fun nativeCreate(cols: Int, rows: Int, maxScrollback: Int): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeWriteRemote(handle: Long, data: ByteArray)
    external fun nativeResize(handle: Long, cols: Int, rows: Int, cellW: Int, cellH: Int)
    external fun nativeScroll(handle: Long, delta: Int, x: Float, y: Float)
    external fun nativeScrollToActive(handle: Long)
    external fun nativeSnapshot(handle: Long): ByteBuffer

    /**
     * The cursor's visual shape and blink mode, packed into one Int — decode
     * with [TerminalCursorStyle.decode], which documents the bit layout.
     *
     * Not part of the snapshot buffer: that writer lives in the vendored
     * chuchu_snapshot.zig, so this rides its own export in cursor_style.zig.
     */
    external fun nativeCursorStyle(handle: Long): Int
    external fun nativePollTitle(handle: Long): String?
    external fun nativePollPwd(handle: Long): String?
    external fun nativePollClipboard(handle: Long): ByteArray?
    external fun nativeDrainBellCount(handle: Long): Int
    external fun nativeSetColorScheme(handle: Long, scheme: Int)
    external fun nativeSetDefaultColors(
        handle: Long,
        fgRgb: IntArray?,
        bgRgb: IntArray?,
        cursorRgb: IntArray?,
        paletteRgb: ByteArray?,
    )
    external fun nativeEncodeKey(handle: Long, key: Int, cp: Int, mods: Int, action: Int, utf8: String?): ByteArray?
    external fun nativeEncodePaste(handle: Long, data: String): ByteArray?
    external fun nativeSetMouseEncodingSize(
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
    external fun nativeEncodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ): ByteArray?
    external fun nativeEncodeFocus(handle: Long, focused: Boolean): ByteArray?
    external fun nativeDrainPtyWrites(handle: Long): ByteArray
    external fun nativeSnapshotImages(handle: Long): ByteBuffer
    external fun nativeIsImageLoading(handle: Long): Boolean
    external fun nativeFormatSelectionRange(handle: Long, startCell: Int, endCell: Int): String?
    external fun nativeFormatSelectionScreenRange(handle: Long, startScreenCell: Int, endScreenCell: Int): String?
    external fun nativeSelectWordAt(handle: Long, cellX: Int, cellY: Int): String?
    external fun nativeSelectLineAt(handle: Long, cellX: Int, cellY: Int): String?
    external fun nativeSelectAll(handle: Long): String?
}
