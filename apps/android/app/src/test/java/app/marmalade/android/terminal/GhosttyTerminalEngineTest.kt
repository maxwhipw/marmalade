package app.marmalade.android.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digital twin for [GhosttyTerminalEngine]: a fake [TermCore] plus the test
 * scheduler's virtual clock, so the ordering contract and the 16 ms snapshot
 * throttle are provable offline. Nothing here loads the `.so`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GhosttyTerminalEngineTest {

    private val sent = mutableListOf<String>()

    private fun TestScope.engineFor(core: FakeTermCore) = GhosttyTerminalEngine(
        core = core,
        scope = this,
        sendUpstream = { sent += it.toString(Charsets.UTF_8) },
        dispatcher = StandardTestDispatcher(testScheduler),
        nowMs = { testScheduler.currentTime },
    )

    @Test
    fun `remote bytes drain pty writes before and after the write`() = runTest {
        val core = FakeTermCore()
        // Two drain rounds each side: the loop stops on the first empty answer.
        core.ptyWrites.addAll(
            listOf("A".toByteArray(), ByteArray(0), "B".toByteArray(), ByteArray(0)),
        )
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        core.calls.clear()

        engine.onRemoteBytes("x".toByteArray())
        advanceUntilIdle()

        assertEquals(
            listOf("drain:A", "drain:", "write:x", "drain:B", "drain:"),
            core.calls.filter { it.startsWith("drain") || it.startsWith("write") },
        )
        // The terminal's own answers reach the wire in the order it produced them.
        assertEquals(listOf("A", "B"), sent)
    }

    @Test
    fun `a burst of chunks coalesces into one trailing snapshot`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        assertEquals(1, core.snapshotCount)

        repeat(5) { engine.onRemoteBytes("x".toByteArray()) }
        runCurrent()
        // All five landed inside the same 16 ms window: no extra frame yet.
        assertEquals(1, core.snapshotCount)

        advanceUntilIdle()
        // ...but the burst still ends on a current picture.
        assertEquals(2, core.snapshotCount)
        assertEquals(GhosttyTerminalEngineTestConstants.SNAPSHOT_INTERVAL_MS, testScheduler.currentTime)
    }

    @Test
    fun `no snapshot mid image transmission, forced when it completes`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        val afterStart = core.snapshotCount

        core.imageLoading = true
        engine.onRemoteBytes("part1".toByteArray())
        advanceUntilIdle()
        // A frame here would paint a half-decoded image.
        assertEquals(afterStart, core.snapshotCount)

        core.onWrite = { core.imageLoading = false }
        engine.onRemoteBytes("part2".toByteArray())
        runCurrent()
        // Forced, not throttled — the image appears without waiting for more output.
        assertEquals(afterStart + 1, core.snapshotCount)
    }

    @Test
    fun `colors are replayed onto the terminal created by start`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.setColorScheme(dark = true)
        engine.setDefaultColors(
            fg = intArrayOf(0xE7, 0xE5, 0xE4),
            bg = intArrayOf(0x1C, 0x19, 0x17),
            cursor = intArrayOf(0xF9, 0x73, 0x16),
            palette = null,
        )
        advanceUntilIdle()
        // Nothing exists to configure yet.
        assertTrue(core.colorSchemes.isEmpty())
        assertTrue(core.defaultColors.isEmpty())

        engine.start(80, 24)
        advanceUntilIdle()

        assertEquals(listOf(1), core.colorSchemes)
        assertEquals(1, core.defaultColors.size)
        val createIndex = core.calls.indexOfFirst { it.startsWith("create") }
        assertTrue(createIndex >= 0)
        assertTrue(core.calls.indexOf("setColorScheme:1") > createIndex)
        assertTrue(core.calls.indexOf("setDefaultColors") > createIndex)
    }

    @Test
    fun `start after start destroys the previous terminal`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(80, 24)
        advanceUntilIdle()
        engine.start(100, 30)
        advanceUntilIdle()

        assertEquals(1, core.destroyed)
        assertEquals(2, core.calls.count { it.startsWith("create") })
    }

    @Test
    fun `stop destroys the terminal and clears the render state`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        assertNotNull(engine.state.value.snapshot)

        engine.stop()
        advanceUntilIdle()

        assertEquals(1, core.destroyed)
        assertNull(engine.state.value.snapshot)
        assertEquals(TerminalRenderState(), engine.state.value)
    }

    @Test
    fun `stop makes later remote bytes a no-op`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        engine.stop()
        advanceUntilIdle()
        core.calls.clear()

        engine.onRemoteBytes("x".toByteArray())
        advanceUntilIdle()

        assertTrue(core.calls.isEmpty())
    }

    @Test
    fun `paste is encoded by the terminal, not sent raw`() = runTest {
        val core = FakeTermCore()
        core.pasteEncoding = { "[200~$it[201~".toByteArray(Charsets.UTF_8) }
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()

        engine.paste("hello")
        advanceUntilIdle()

        assertTrue(core.calls.contains("encodePaste:hello"))
        // Bracketed by the emulator — the client never decides that itself.
        assertEquals(listOf("[200~hello[201~"), sent)
    }

    @Test
    fun `scroll drains the pty writes a mouse-mode app answers with`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        core.calls.clear()
        core.ptyWrites.addAll(listOf("wheel".toByteArray(), ByteArray(0)))

        engine.scroll(-3, 1f, 2f)
        advanceUntilIdle()

        assertEquals("scroll:-3", core.calls.first())
        assertEquals(listOf("wheel"), sent)
        assertTrue(core.calls.contains("snapshot"))
    }

    @Test
    fun `resize sizes the emulator and flushes the in-band size report`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(80, 24)
        advanceUntilIdle()
        core.calls.clear()
        core.ptyWrites.addAll(listOf("size-report".toByteArray(), ByteArray(0)))

        engine.resize(100, 30, 9, 20)
        advanceUntilIdle()

        assertEquals("resize:100 x 30/9 x 20", core.calls.first())
        assertEquals(listOf("size-report"), sent)
    }

    @Test
    fun `a degenerate grid is ignored rather than passed to the emulator`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(80, 24)
        advanceUntilIdle()
        core.calls.clear()

        engine.resize(0, 30, 9, 20)
        engine.resize(100, 30, 0, 20)
        advanceUntilIdle()

        assertTrue(core.calls.none { it.startsWith("resize") })
    }

    @Test
    fun `an unavailable native library surfaces as an error instead of a blank grid`() = runTest {
        val core = FakeTermCore(isLoaded = false)
        val engine = engineFor(core)
        engine.start(80, 24)
        advanceUntilIdle()

        assertNotNull(engine.state.value.error)
        assertNull(engine.state.value.snapshot)
        assertTrue(core.calls.isEmpty())
    }

    @Test
    fun `a key is encoded by the emulator and its bytes go upstream`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        core.calls.clear()

        engine.sendKey(
            key = GhosttyKey.ARROW_UP,
            codepoint = 0,
            mods = GhosttyMods.CTRL,
            action = GhosttyKeyAction.PRESS,
            utf8 = null,
        )
        advanceUntilIdle()

        assertEquals(listOf("encodeKey:78/0/2/1/-"), core.calls)
        assertEquals(listOf("K78.1"), sent)
    }

    @Test
    fun `a key that encodes to nothing sends nothing`() = runTest {
        val core = FakeTermCore()
        core.keyEncoding = { null }
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()

        engine.sendKey(GhosttyKey.KEY_A, 'a'.code, GhosttyMods.NONE, GhosttyKeyAction.RELEASE, null)
        advanceUntilIdle()
        assertTrue(sent.isEmpty())

        // Empty is the other "nothing" the encoder answers with.
        core.keyEncoding = { ByteArray(0) }
        engine.sendKey(GhosttyKey.KEY_A, 'a'.code, GhosttyMods.NONE, GhosttyKeyAction.RELEASE, null)
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a soft key sends a press and a release, in that order`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        core.calls.clear()

        engine.sendKeyPressRelease(GhosttyKey.ESCAPE, GhosttyMods.NONE)
        advanceUntilIdle()

        assertEquals(listOf("encodeKey:120/0/0/1/-", "encodeKey:120/0/0/0/-"), core.calls)
        assertEquals(listOf("K120.1", "K120.0"), sent)
    }

    @Test
    fun `keys before the terminal exists are dropped rather than queued`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)

        engine.sendKey(GhosttyKey.KEY_A, 'a'.code, GhosttyMods.NONE, GhosttyKeyAction.PRESS, "a")
        advanceUntilIdle()

        assertTrue(core.calls.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `focus reports go upstream, and silence means the app is not listening`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()

        engine.sendFocus(true)
        advanceUntilIdle()
        assertEquals(listOf("Ftrue"), sent)

        // DECSET 1004 off: the encoder answers empty and nothing is sent.
        core.focusEncoding = { ByteArray(0) }
        engine.sendFocus(false)
        advanceUntilIdle()
        assertEquals(listOf("Ftrue"), sent)
    }

    @Test
    fun `mouse events go upstream, and silence means no app is tracking`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        core.calls.clear()

        engine.sendMouse(
            action = GhosttyMouseAction.PRESS,
            button = GhosttyMouseButton.LEFT,
            mods = GhosttyMods.NONE,
            x = 12f,
            y = 34f,
            anyButtonPressed = true,
            trackLastCell = true,
        )
        advanceUntilIdle()
        assertEquals(listOf("encodeMouse:0/1/0/12.0,34.0/true/true"), core.calls)
        assertEquals(listOf("M0"), sent)

        core.mouseEncoding = { null }
        engine.sendMouse(GhosttyMouseAction.MOTION, GhosttyMouseButton.LEFT, 0, 1f, 1f, true, true)
        advanceUntilIdle()
        assertEquals(listOf("M0"), sent)
    }

    @Test
    fun `the mouse encoder is given the pixel geometry on start and on resize`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.resize(80, 24, 10, 20)
        engine.start(80, 24)
        advanceUntilIdle()

        // Grid x cell, not the canvas: the encoder's cell maths must agree with
        // the renderer's or a tap lands a column off at the right edge.
        assertEquals(
            listOf("mouseSize:800x480/10x20/0,0,0,0"),
            core.calls.filter { it.startsWith("mouseSize") },
        )

        core.calls.clear()
        engine.resize(40, 12, 10, 20)
        advanceUntilIdle()
        assertEquals(
            listOf("mouseSize:400x240/10x20/0,0,0,0"),
            core.calls.filter { it.startsWith("mouseSize") },
        )
    }

    @Test
    fun `selection text is read in screen space, offset by the scrollback`() = runTest {
        val core = FakeTermCore()
        // 4 columns, viewport scrolled 3 rows back: screen offset is 12 cells.
        core.nextSnapshot = { FakeTermCore.blankSnapshot(cols = 4, rows = 2, viewportScrollY = 3) }
        val engine = engineFor(core)
        engine.start(4, 2)
        advanceUntilIdle()
        core.calls.clear()

        val text = engine.selectionText(anchorIndex = 6, focusIndex = 1)
        advanceUntilIdle()

        // Endpoints normalised (1..6) and then shifted into screen space.
        assertEquals(listOf("formatScreen:13..18"), core.calls)
        assertEquals("screen[13..18]", text)
    }

    @Test
    fun `selection text falls back to viewport space, then to the snapshot`() = runTest {
        val core = FakeTermCore()
        core.nextSnapshot = { FakeTermCore.blankSnapshot(cols = 4, rows = 2) }
        core.screenSelectionText = { _, _ -> null }
        val engine = engineFor(core)
        engine.start(4, 2)
        advanceUntilIdle()
        core.calls.clear()

        assertEquals("viewport[1..6]", engine.selectionText(1, 6))
        assertEquals(listOf("formatScreen:1..6", "formatViewport:1..6"), core.calls)

        // Both native calls unavailable: the pure snapshot scrape answers, and
        // a blank grid trims to empty rows rather than failing.
        core.viewportSelectionText = { _, _ -> null }
        assertEquals("\n", engine.selectionText(1, 6))
    }

    @Test
    fun `there is no selection text before the first frame`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)

        assertNull(engine.selectionText(0, 1))
        assertTrue(core.calls.isEmpty())
    }

    @Test
    fun `bells accumulate across frames so the ui sees a monotonic tick`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()

        core.bells = 2
        engine.onRemoteBytes("x".toByteArray())
        advanceUntilIdle()
        assertEquals(2, engine.state.value.bellTick)

        core.bells = 1
        engine.onRemoteBytes("y".toByteArray())
        advanceUntilIdle()
        assertEquals(3, engine.state.value.bellTick)
    }

    @Test
    fun `the cursor style is read per frame and published beside the snapshot`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()

        // Default until the emulator says otherwise: a steady block, which is
        // what a terminal powers on in.
        assertEquals(TerminalCursorStyle.Default, engine.state.value.cursorStyle)

        core.cursorStyle = TerminalCursorStyle(TerminalCursorShape.BAR, blinking = true)
        engine.onRemoteBytes("x".toByteArray())
        advanceUntilIdle()
        assertEquals(
            TerminalCursorStyle(TerminalCursorShape.BAR, blinking = true),
            engine.state.value.cursorStyle,
        )

        // It has to be re-read every frame, not latched: DECSCUSR can switch
        // back, and a TUI that restores the block cursor on exit relies on it.
        core.cursorStyle = TerminalCursorStyle(TerminalCursorShape.UNDERLINE, blinking = false)
        engine.onRemoteBytes("y".toByteArray())
        advanceUntilIdle()
        assertEquals(
            TerminalCursorStyle(TerminalCursorShape.UNDERLINE, blinking = false),
            engine.state.value.cursorStyle,
        )
    }

    @Test
    fun `the cursor style is read on the engine thread, once per published frame`() = runTest {
        val core = FakeTermCore()
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        core.calls.clear()

        engine.onRemoteBytes("x".toByteArray())
        advanceUntilIdle()

        // Exactly as many style reads as snapshots — the style must not lag a
        // frame behind the grid it is drawn onto, nor cost an extra JNI call.
        assertEquals(
            core.calls.count { it == "snapshot" },
            core.calls.count { it == "cursorStyle" },
        )
        assertTrue(core.calls.contains("cursorStyle"))
    }

    @Test
    fun `stop clears the cursor style back to the default`() = runTest {
        val core = FakeTermCore()
        core.cursorStyle = TerminalCursorStyle(TerminalCursorShape.BAR, blinking = true)
        val engine = engineFor(core)
        engine.start(2, 1)
        advanceUntilIdle()
        assertEquals(TerminalCursorShape.BAR, engine.state.value.cursorStyle.shape)

        engine.stop()
        advanceUntilIdle()
        assertEquals(TerminalCursorStyle.Default, engine.state.value.cursorStyle)
    }
}

/** Mirrors the engine's private throttle constant; a drift here is a real bug. */
private object GhosttyTerminalEngineTestConstants {
    const val SNAPSHOT_INTERVAL_MS = 16L
}
