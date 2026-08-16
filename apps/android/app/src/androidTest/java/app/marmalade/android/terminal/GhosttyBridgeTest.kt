package app.marmalade.android.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stage-2b proof: libghostty-vt actually runs inside our APK.
 *
 * This is the one part of the terminal core a JVM digital twin cannot cover —
 * `libmarmalade_term.so` is native code, so "does the JNI boundary resolve and
 * does the terminal behave" can only be answered on a device. Everything
 * downstream of the snapshot bytes is unit-tested in
 * `app/src/test/.../TerminalSnapshotTest.kt` instead; keep it that way.
 *
 * Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class GhosttyBridgeTest {

    private val cols = 80
    private val rows = 24
    private val scrollback = 1000

    private inline fun <T> withTerminal(body: (GhosttyBridge, Long) -> T): T {
        val bridge = GhosttyBridge()
        assertTrue("native library not loaded: ${bridge.nativeStatus()}", bridge.isLoaded())
        val handle = bridge.nativeCreate(cols, rows, scrollback)
        assertTrue("nativeCreate returned a null handle", handle != 0L)
        return try {
            body(bridge, handle)
        } finally {
            bridge.nativeDestroy(handle)
        }
    }

    private fun snapshot(bridge: GhosttyBridge, handle: Long): TerminalSnapshot =
        TerminalSnapshot.fromByteBuffer(bridge.nativeSnapshot(handle))

    @Test
    fun writingTextLandsInTheGrid() {
        withTerminal { bridge, handle ->
            bridge.nativeWriteRemote(handle, "hello".toByteArray(Charsets.UTF_8))

            val snap = snapshot(bridge, handle)
            assertEquals(cols, snap.cols)
            assertEquals(rows, snap.rows)
            val hit = (0 until snap.rows).any { snap.rowText(it).contains("hello") }
            assertTrue("no row contained \"hello\"; row0=\"${snap.rowText(0)}\"", hit)
        }
    }

    @Test
    fun wideGlyphIsFollowedByASpacerCell() {
        withTerminal { bridge, handle ->
            bridge.nativeWriteRemote(handle, "你".toByteArray(Charsets.UTF_8))

            val snap = snapshot(bridge, handle)
            assertEquals(0x4F60, snap.codepoints[0])
            assertEquals("spacer cell must serialize as a space", 32, snap.codepoints[1])
            assertEquals(
                "cell after a wide glyph must carry CELL_FLAG_SPACER",
                TerminalSnapshot.CELL_FLAG_SPACER,
                snap.flags[1].toInt() and 0xFF and TerminalSnapshot.CELL_FLAG_SPACER,
            )
        }
    }

    @Test
    fun terminalAnswersDeviceAttributesQuery() {
        // The capability that motivated libghostty over a dumb emulator: a DA1
        // request (ESC [ c) is *answered* on the pty-write channel, not echoed
        // into the grid. opencode's startup probe burst depends on this.
        withTerminal { bridge, handle ->
            bridge.nativeWriteRemote(handle, byteArrayOf(0x1b, '['.code.toByte(), 'c'.code.toByte()))

            val reply = bridge.nativeDrainPtyWrites(handle)
            assertTrue("DA1 produced no reply", reply.isNotEmpty())
            assertEquals("DA1 reply must start with ESC, got ${reply.toHex()}", 0x1b, reply[0].toInt())

            // ...and the request must not have painted anything into the grid.
            val snap = snapshot(bridge, handle)
            assertEquals("DA1 was echoed into the grid: \"${snap.rowText(0)}\"", " ".repeat(cols), snap.rowText(0))

            // Surfaced so the emulator run records the actual bytes.
            println("DA1 reply hex: ${reply.toHex()}")
        }
    }

    @Test
    fun resizeEmitsAnInBandSizeReport() {
        withTerminal { bridge, handle ->
            // Enable DEC mode 2048 (in-band window size reports) — without it
            // the terminal has no reason to tell the app about a resize.
            bridge.nativeWriteRemote(handle, "\u001b[?2048h".toByteArray(Charsets.UTF_8))
            bridge.nativeDrainPtyWrites(handle) // drop the mode-set acknowledgement

            bridge.nativeResize(handle, 100, 30, 8, 16)

            val report = bridge.nativeDrainPtyWrites(handle)
            assertTrue("resize produced no in-band size report", report.isNotEmpty())
            println("resize report hex: ${report.toHex()}")

            val snap = snapshot(bridge, handle)
            assertEquals(100, snap.cols)
            assertEquals(30, snap.rows)
        }
    }

    @Test
    fun bridgeReportsItsVersion() {
        val bridge = GhosttyBridge()
        assertTrue("native library not loaded: ${bridge.nativeStatus()}", bridge.isLoaded())
        assertEquals("loaded", bridge.nativeStatus())
        assertTrue(bridge.nativeVersion().isNotEmpty())
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
}
