package app.marmalade.android.terminal

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the soft-keyboard input path. Runs on the JVM: every
 * `KeyEvent.KEYCODE_*` / `InputType.*` / `EditorInfo.IME_*` read is a
 * compile-time constant, so no Android class loads here.
 */
class TerminalImeCodecTest {

    @Test
    fun `committed text is utf-8`() {
        assertArrayEquals("ls".toByteArray(Charsets.UTF_8), TerminalImeCodec.textBytes("ls"))
    }

    @Test
    fun `multibyte and emoji survive`() {
        // A terminal that mangles these corrupts the byte stream, not just the
        // display — the PTY gets whatever we encode.
        assertArrayEquals("日本".toByteArray(Charsets.UTF_8), TerminalImeCodec.textBytes("日本"))
        assertArrayEquals("🍊".toByteArray(Charsets.UTF_8), TerminalImeCodec.textBytes("🍊"))
    }

    @Test
    fun `a committed newline becomes carriage return`() {
        // Gboard's soft Enter arrives as committed TEXT "\n", not as a key. 0x0a
        // at a prompt is Ctrl+J (insert a newline), so a prompt could never be
        // submitted until this translated to 0x0d.
        assertArrayEquals(byteArrayOf(0x0d), TerminalImeCodec.textBytes("\n"))
    }

    @Test
    fun `newlines inside committed text become carriage returns too`() {
        // Multi-line commits submit line by line, the way typing them would —
        // this is the same LF→CR translation xterm applies to typed input.
        assertArrayEquals(
            byteArrayOf('a'.code.toByte(), 0x0d, 'b'.code.toByte()),
            TerminalImeCodec.textBytes("a\nb"),
        )
    }

    @Test
    fun `a committed carriage return is left alone`() {
        assertArrayEquals(byteArrayOf(0x0d), TerminalImeCodec.textBytes("\r"))
    }

    @Test
    fun `delete becomes that many DEL bytes`() {
        assertArrayEquals(byteArrayOf(0x7f, 0x7f, 0x7f), TerminalImeCodec.deleteBytes(3))
    }

    @Test
    fun `delete of nothing sends nothing`() {
        assertArrayEquals(ByteArray(0), TerminalImeCodec.deleteBytes(0))
        assertArrayEquals(ByteArray(0), TerminalImeCodec.deleteBytes(-1))
    }

    @Test
    fun `backspace is DEL not backspace`() {
        // 0x08 would be wrong: stty erase expects 0x7f on every modern Unix.
        assertArrayEquals(byteArrayOf(0x7f), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_DEL, 0))
    }

    @Test
    fun `enter is carriage return not newline`() {
        // 0x0a would submit nothing at a shell prompt.
        assertArrayEquals(byteArrayOf(0x0d), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_ENTER, 0))
        assertArrayEquals(byteArrayOf(0x0d), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_NUMPAD_ENTER, 0))
    }

    @Test
    fun `nav keys are the normal-mode escape sequences`() {
        // This is the one remaining fixed-sequence path (an IME that sends key
        // events rather than committed text), so the constants are pinned here
        // rather than cross-checked against another table. Normal cursor form,
        // ESC [ A — DECCKM is not tracked on this path.
        val esc: Byte = 0x1b
        val lb = '['.code.toByte()
        fun csi(final: Char) = byteArrayOf(esc, lb, final.code.toByte())
        assertArrayEquals(csi('A'), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_DPAD_UP, 0))
        assertArrayEquals(csi('B'), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_DPAD_DOWN, 0))
        assertArrayEquals(csi('D'), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertArrayEquals(csi('C'), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        assertArrayEquals(csi('H'), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_MOVE_HOME, 0))
        assertArrayEquals(csi('F'), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_MOVE_END, 0))
        // PgUp/PgDn are ESC [ 5 ~ / ESC [ 6 ~.
        assertArrayEquals(
            byteArrayOf(esc, lb, '5'.code.toByte(), '~'.code.toByte()),
            TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_PAGE_UP, 0),
        )
        assertArrayEquals(
            byteArrayOf(esc, lb, '6'.code.toByte(), '~'.code.toByte()),
            TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_PAGE_DOWN, 0),
        )
        assertArrayEquals(byteArrayOf(esc), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_ESCAPE, 0))
        assertArrayEquals(byteArrayOf(0x09), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_TAB, 0))
    }

    @Test
    fun `an unmapped key with a character sends that character`() {
        assertArrayEquals(byteArrayOf('a'.code.toByte()), TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_A, 'a'.code))
    }

    @Test
    fun `an unmapped key with no character sends nothing`() {
        // Guessing at a modifier or a volume key would inject junk into the PTY.
        assertNull(TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        assertNull(TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_VOLUME_UP, 0))
    }

    @Test
    fun `suggestions off declares a visible password field`() {
        // VISIBLE_PASSWORD is the load-bearing flag: NO_SUGGESTIONS alone is a
        // hint Gboard ignores, and a composing keyboard is what echoes a word
        // at a time instead of a keystroke at a time.
        val type = TerminalImeCodec.imeInputType(suggestionsEnabled = false)
        assertEquals(InputType.TYPE_CLASS_TEXT, type and InputType.TYPE_MASK_CLASS)
        assertTrue(type and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        assertTrue(type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertTrue(type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT == 0)
    }

    @Test
    fun `suggestions on drops the password variation and asks for autocorrect`() {
        val type = TerminalImeCodec.imeInputType(suggestionsEnabled = true)
        assertEquals(InputType.TYPE_CLASS_TEXT, type and InputType.TYPE_MASK_CLASS)
        assertEquals(0, type and InputType.TYPE_MASK_VARIATION)
        assertTrue(type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0)
        assertTrue(type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0)
    }

    @Test
    fun `personalized learning is off in both suggestion modes`() {
        // Non-negotiable: hostnames, paths and tokens typed at a shell prompt
        // must not enter the keyboard's learned dictionary. imeOptions is the
        // same constant either way, so this asserts the flag is in it at all.
        assertTrue(
            TerminalImeCodec.IME_OPTIONS and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
        )
    }

    @Test
    fun `ctrl modifier composes with a typed key`() {
        // The sticky Ctrl in the extra-keys row applies to IME bytes too, since
        // both land on the same callback — Ctrl+c must still be 0x03.
        val typed = TerminalImeCodec.keyBytes(KeyEvent.KEYCODE_C, 'c'.code)!!
        assertArrayEquals(byteArrayOf(0x03), TerminalIO.applyModifiers(typed, ctrl = true, alt = false))
    }
}
