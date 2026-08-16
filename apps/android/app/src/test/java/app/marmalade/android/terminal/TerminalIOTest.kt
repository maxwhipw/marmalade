package app.marmalade.android.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-function tests for the terminal codec + extra-keys byte mapping. These
 * are the load-bearing invariants of the wire glue (base64 round-trips + the
 * sticky Ctrl/Alt mapping), so they stand alone from any Android plumbing.
 *
 * Escape sequences are written as explicit [byteArrayOf] (0x1b = ESC, 0x5b =
 * '[') — no raw control chars or \u escapes in the source, so the expectations
 * survive editors/diffs unambiguously.
 */
class TerminalIOTest {

    private val ESC: Byte = 0x1b
    private val LBRACKET: Byte = '['.code.toByte()

    @Test fun base64RoundTripsAsciiAndUnicode() {
        val samples = listOf(
            "ls -la\n",
            "echo \"hi\"",
            "",
            "unicode: café → 日本語",
        )
        for (s in samples) {
            val b64 = TerminalIO.textToB64(s)
            assertEquals(s, String(TerminalIO.b64ToBytes(b64), Charsets.UTF_8))
        }
    }

    @Test fun base64RoundTripsRawControlBytes() {
        // ^C, ESC, arrow-key CSI — the bytes that MUST survive JSON transport.
        val raw = byteArrayOf(0x03, ESC, LBRACKET, 'A'.code.toByte(), 0x00, 0x7f)
        val b64 = TerminalIO.bytesToB64(raw)
        assertArrayEquals(raw, TerminalIO.b64ToBytes(b64))
    }

    @Test fun ctrlLetterIsCharAnd0x1f() {
        // Ctrl+C = 0x03, Ctrl+D = 0x04, Ctrl+Z = 0x1a — char & 0x1f.
        assertArrayEquals(byteArrayOf(0x03), TerminalIO.applyModifiers("c".toByteArray(), ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x03), TerminalIO.applyModifiers("C".toByteArray(), ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x04), TerminalIO.applyModifiers("d".toByteArray(), ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x1a), TerminalIO.applyModifiers("z".toByteArray(), ctrl = true, alt = false))
    }

    @Test fun ctrlNonLetterFollowsMask() {
        // Ctrl+[ = ESC (0x1b); Ctrl+Space = NUL (0x00) — standard control mapping.
        assertArrayEquals(byteArrayOf(0x1b), TerminalIO.applyModifiers("[".toByteArray(), ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x00), TerminalIO.applyModifiers(" ".toByteArray(), ctrl = true, alt = false))
    }

    @Test fun altPrefixesEsc() {
        // Alt+f = ESC f (the meta prefix).
        assertArrayEquals(byteArrayOf(ESC, 'f'.code.toByte()), TerminalIO.applyModifiers("f".toByteArray(), ctrl = false, alt = true))
    }

    @Test fun ctrlAltCombines() {
        // Ctrl+Alt+c = ESC then 0x03.
        assertArrayEquals(byteArrayOf(ESC, 0x03), TerminalIO.applyModifiers("c".toByteArray(), ctrl = true, alt = true))
    }

    @Test fun noModifiersIsIdentity() {
        val bytes = "hello".toByteArray()
        assertArrayEquals(bytes, TerminalIO.applyModifiers(bytes, ctrl = false, alt = false))
    }

    @Test fun shiftTabIsTabWithShift() {
        // Back-tab — Claude Code's mode-cycle key. It is Tab with Shift rather
        // than a hardcoded CSI Z, so the emulator encodes it in whatever
        // protocol the running app negotiated.
        assertEquals(GhosttyKey.TAB, TerminalKey.SHIFT_TAB.ghosttyKey)
        assertEquals(GhosttyMods.SHIFT, TerminalKey.SHIFT_TAB.mods)
    }

    @Test fun newlineIsCtrlJ() {
        // 0x0a, NOT 0x0d: Enter (CR) submits, this inserts a newline.
        assertArrayEquals(byteArrayOf(0x0a), TerminalKey.NEWLINE.bytes)
    }

    @Test fun literalKeysAreSingleAsciiBytes() {
        assertArrayEquals(byteArrayOf('/'.code.toByte()), TerminalKey.SLASH.bytes)
        assertArrayEquals(byteArrayOf('~'.code.toByte()), TerminalKey.TILDE.bytes)
        assertArrayEquals(byteArrayOf('-'.code.toByte()), TerminalKey.HYPHEN.bytes)
        assertArrayEquals(byteArrayOf('|'.code.toByte()), TerminalKey.PIPE.bytes)
    }

    @Test fun byteOnlyKeysHaveNoPhysicalKey() {
        // The native screen dispatches on exactly this: UNIDENTIFIED means
        // "write these bytes", anything else means "encode this key".
        for (key in listOf(
            TerminalKey.NEWLINE, TerminalKey.SLASH, TerminalKey.TILDE,
            TerminalKey.HYPHEN, TerminalKey.PIPE,
        )) {
            assertEquals(GhosttyKey.UNIDENTIFIED, key.ghosttyKey)
        }
        // …and every other key does have one, or the row would send nothing at
        // all for it. Those must also carry NO bytes: a fixed escape sequence
        // beside a physical key is a mode-blind second answer that nothing
        // reads (it was the deleted xterm.js renderer's path).
        for (key in TerminalKey.entries - setOf(
            TerminalKey.NEWLINE, TerminalKey.SLASH, TerminalKey.TILDE,
            TerminalKey.HYPHEN, TerminalKey.PIPE,
        )) {
            assertNotEquals(GhosttyKey.UNIDENTIFIED, key.ghosttyKey)
            assertEquals(0, key.bytes.size)
        }
    }
}
