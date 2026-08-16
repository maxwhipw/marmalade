package app.marmalade.android.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [KeyMapper] is pure and the `KeyEvent` constants inline, so this runs on the
 * JVM with no Android runtime. The table is the contract: a wrong [GhosttyKey]
 * does not crash, it silently sends the wrong escape sequence.
 */
class KeyMapperTest {

    private fun map(keyCode: Int, unicodeChar: Int = 0, metaState: Int = 0) =
        KeyMapper.map(keyCode, unicodeChar, metaState)

    @Test
    fun `arrows map to the ghostty physical keys`() {
        assertEquals(GhosttyKey.ARROW_UP, map(KeyEvent.KEYCODE_DPAD_UP)!!.key)
        assertEquals(GhosttyKey.ARROW_DOWN, map(KeyEvent.KEYCODE_DPAD_DOWN)!!.key)
        assertEquals(GhosttyKey.ARROW_LEFT, map(KeyEvent.KEYCODE_DPAD_LEFT)!!.key)
        assertEquals(GhosttyKey.ARROW_RIGHT, map(KeyEvent.KEYCODE_DPAD_RIGHT)!!.key)
    }

    @Test
    fun `volume keys double as vertical arrows`() {
        assertEquals(GhosttyKey.ARROW_UP, map(KeyEvent.KEYCODE_VOLUME_UP)!!.key)
        assertEquals(GhosttyKey.ARROW_DOWN, map(KeyEvent.KEYCODE_VOLUME_DOWN)!!.key)
    }

    @Test
    fun `editing and nav keys map`() {
        assertEquals(GhosttyKey.ENTER, map(KeyEvent.KEYCODE_ENTER)!!.key)
        assertEquals(GhosttyKey.BACKSPACE, map(KeyEvent.KEYCODE_DEL)!!.key)
        assertEquals(GhosttyKey.DELETE, map(KeyEvent.KEYCODE_FORWARD_DEL)!!.key)
        assertEquals(GhosttyKey.TAB, map(KeyEvent.KEYCODE_TAB)!!.key)
        assertEquals(GhosttyKey.ESCAPE, map(KeyEvent.KEYCODE_ESCAPE)!!.key)
        assertEquals(GhosttyKey.HOME, map(KeyEvent.KEYCODE_MOVE_HOME)!!.key)
        assertEquals(GhosttyKey.END, map(KeyEvent.KEYCODE_MOVE_END)!!.key)
        assertEquals(GhosttyKey.PAGE_UP, map(KeyEvent.KEYCODE_PAGE_UP)!!.key)
        assertEquals(GhosttyKey.PAGE_DOWN, map(KeyEvent.KEYCODE_PAGE_DOWN)!!.key)
    }

    @Test
    fun `letters and digits map by offset`() {
        assertEquals(GhosttyKey.KEY_A, map(KeyEvent.KEYCODE_A)!!.key)
        assertEquals(GhosttyKey.KEY_Z, map(KeyEvent.KEYCODE_Z)!!.key)
        assertEquals(GhosttyKey.DIGIT_0, map(KeyEvent.KEYCODE_0)!!.key)
        assertEquals(GhosttyKey.DIGIT_9, map(KeyEvent.KEYCODE_9)!!.key)
        assertEquals(GhosttyKey.F1, map(KeyEvent.KEYCODE_F1)!!.key)
        assertEquals(GhosttyKey.F12, map(KeyEvent.KEYCODE_F12)!!.key)
    }

    @Test
    fun `shift reports the unshifted codepoint but the shifted character`() {
        val shifted = map(KeyEvent.KEYCODE_A, unicodeChar = 'A'.code, metaState = KeyEvent.META_SHIFT_ON)!!
        assertEquals(GhosttyKey.KEY_A, shifted.key)
        // ghostty's protocols want the base key; the shifted form rides charCode.
        assertEquals('a'.code, shifted.codepoint)
        assertEquals('A'.code, shifted.charCode)
        assertEquals(GhosttyMods.SHIFT, shifted.mods)
        assertEquals("A", KeyMapper.utf8For(shifted, GhosttyKeyAction.PRESS))
    }

    @Test
    fun `an unmapped key with no character is dropped`() {
        assertNull(map(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertNull(map(KeyEvent.KEYCODE_CAMERA))
    }

    @Test
    fun `an unmapped key that types a character passes the character through`() {
        val mapped = map(KeyEvent.KEYCODE_NUMPAD_ADD, unicodeChar = '+'.code)!!
        assertEquals(GhosttyKey.UNIDENTIFIED, mapped.key)
        assertEquals('+'.code, mapped.codepoint)
        assertEquals("+", KeyMapper.utf8For(mapped, GhosttyKeyAction.PRESS))
    }

    @Test
    fun `mods translate to the ghostty bitfield`() {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON
        assertEquals(
            GhosttyMods.CTRL or GhosttyMods.ALT or GhosttyMods.SUPER,
            map(KeyEvent.KEYCODE_C, unicodeChar = 'c'.code, metaState = meta)!!.mods,
        )
    }

    @Test
    fun `ctrl combos carry no utf8 - that is what makes Ctrl+C an interrupt`() {
        val ctrlC = map(KeyEvent.KEYCODE_C, unicodeChar = 'c'.code, metaState = KeyEvent.META_CTRL_ON)!!
        assertEquals(GhosttyKey.letter('c' - 'a'), ctrlC.key)
        assertNull(KeyMapper.utf8For(ctrlC, GhosttyKeyAction.PRESS))
    }

    @Test
    fun `alt and super also suppress utf8`() {
        val altF = map(KeyEvent.KEYCODE_F, unicodeChar = 'f'.code, metaState = KeyEvent.META_ALT_ON)!!
        assertNull(KeyMapper.utf8For(altF, GhosttyKeyAction.PRESS))
        val superK = map(KeyEvent.KEYCODE_K, unicodeChar = 'k'.code, metaState = KeyEvent.META_META_ON)!!
        assertNull(KeyMapper.utf8For(superK, GhosttyKeyAction.PRESS))
    }

    @Test
    fun `a release types nothing`() {
        val a = map(KeyEvent.KEYCODE_A, unicodeChar = 'a'.code)!!
        assertEquals("a", KeyMapper.utf8For(a, GhosttyKeyAction.PRESS))
        assertNull(KeyMapper.utf8For(a, GhosttyKeyAction.RELEASE))
    }

    @Test
    fun `a key that types nothing has no utf8 even on press`() {
        assertNull(KeyMapper.utf8For(map(KeyEvent.KEYCODE_DPAD_UP)!!, GhosttyKeyAction.PRESS))
    }

    @Test
    fun `android key actions translate, with repeat distinguished`() {
        assertEquals(GhosttyKeyAction.PRESS, GhosttyKeyAction.fromAndroid(KeyEvent.ACTION_DOWN))
        assertEquals(GhosttyKeyAction.REPEAT, GhosttyKeyAction.fromAndroid(KeyEvent.ACTION_DOWN, repeatCount = 3))
        assertEquals(GhosttyKeyAction.RELEASE, GhosttyKeyAction.fromAndroid(KeyEvent.ACTION_UP))
        // Anything that is neither down nor up (the deprecated ACTION_MULTIPLE
        // is the only such value) has no terminal meaning.
        assertNull(GhosttyKeyAction.fromAndroid(2))
    }

    @Test
    fun `sticky chips translate to mods`() {
        assertEquals(GhosttyMods.NONE, GhosttyMods.sticky(ctrl = false, alt = false))
        assertEquals(GhosttyMods.CTRL, GhosttyMods.sticky(ctrl = true, alt = false))
        assertEquals(GhosttyMods.CTRL or GhosttyMods.ALT, GhosttyMods.sticky(ctrl = true, alt = true))
    }

    @Test
    fun `extra-keys rows agree on the physical key behind each byte sequence`() {
        assertEquals(GhosttyKey.ARROW_UP, TerminalKey.ARROW_UP.ghosttyKey)
        assertEquals(GhosttyKey.ESCAPE, TerminalKey.ESCAPE.ghosttyKey)
        assertEquals(GhosttyKey.PAGE_DOWN, TerminalKey.PAGE_DOWN.ghosttyKey)
    }
}
