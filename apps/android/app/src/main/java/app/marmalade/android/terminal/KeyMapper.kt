// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/ui/terminal/KeyMapper.kt, plus
// the utf8 rule from .../ui/screens/Terminal/TerminalViewModel.kt::onHardwareKey.
//
// Changed: the letter/digit blocks are folded into range arithmetic (upstream
// spells out 36 identical branches), and the utf8 decision — upstream a private
// step inside a ViewModel — is a pure function here so it can be unit-tested.
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.terminal

import android.view.KeyEvent

/**
 * One hardware key press, in the shape libghostty's encoder wants.
 *
 * @param key the physical [GhosttyKey]; 0 ([GhosttyKey.UNIDENTIFIED]) when only
 *   a character is known.
 * @param codepoint the **unshifted** character the physical key produces —
 *   ghostty's keyboard protocols report the base key, not what shift made of it.
 * @param charCode what this press actually typed (`KeyEvent.unicodeChar`), which
 *   is what becomes the utf8 text.
 */
data class MappedKey(
    val key: Int,
    val codepoint: Int,
    val mods: Int,
    val charCode: Int = 0,
)

/**
 * Android `KeyEvent` → [MappedKey], for the **hardware** keyboard only.
 *
 * Soft-keyboard text does NOT come through here: an IME commits strings, and
 * those keep going out as raw UTF-8 through `TerminalInputConnection` (chuchu
 * splits the same way — its `writeText` is a plain byte write). This path exists
 * because a physical key carries a *physical identity* and a modifier state that
 * a committed string has thrown away, and that identity is what makes ghostty
 * emit DECCKM-correct arrows and kitty-protocol key reports.
 *
 * JVM-unit-testable despite the `KeyEvent` import: only `static final int`
 * constants are read, and the compiler inlines those, so no Android class is
 * loaded at test time. Keep it that way — never call a [KeyEvent] method here.
 */
object KeyMapper {

    fun map(keyCode: Int, codepoint: Int, metaState: Int): MappedKey? {
        val mods = GhosttyMods.fromMetaState(metaState)
        val key = physicalKeyFor(keyCode)
        if (key == null) {
            // Not a key we can name physically — pass the character through, or
            // drop it (modifiers, hardware buttons) rather than guessing.
            if (codepoint == 0) return null
            return MappedKey(GhosttyKey.UNIDENTIFIED, codepoint, mods, charCode = codepoint)
        }
        return MappedKey(key, unshiftedCodepointFor(keyCode), mods, charCode = codepoint)
    }

    /**
     * The text this press produces, or null when it produces none.
     *
     * Null whenever Ctrl/Alt/Super is held: with a modifier the press is a
     * *command*, and handing ghostty utf8 as well would make it encode the
     * literal character instead of (say) 0x03 for Ctrl+C. Also null on release —
     * a key-up types nothing.
     */
    fun utf8For(mapped: MappedKey, action: Int): String? {
        if (action == GhosttyKeyAction.RELEASE) return null
        if (mapped.mods and GhosttyMods.NON_TEXT != 0) return null
        val cp = effectiveCodepoint(mapped)
        if (cp <= 0) return null
        return String(Character.toChars(cp))
    }

    /** What was typed, falling back to the physical key's base character. */
    fun effectiveCodepoint(mapped: MappedKey): Int =
        if (mapped.charCode > 0) mapped.charCode else mapped.codepoint

    private fun physicalKeyFor(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            GhosttyKey.letter(keyCode - KeyEvent.KEYCODE_A)

        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            GhosttyKey.digit(keyCode - KeyEvent.KEYCODE_0)

        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            GhosttyKey.F1 + (keyCode - KeyEvent.KEYCODE_F1)

        // Volume doubles as page-scroll on phones without a hardware keyboard —
        // chuchu's affordance, and the only way to walk a TUI one-handed.
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> GhosttyKey.ARROW_UP
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> GhosttyKey.ARROW_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GhosttyKey.ARROW_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GhosttyKey.ARROW_RIGHT
        KeyEvent.KEYCODE_PAGE_UP -> GhosttyKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> GhosttyKey.PAGE_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> GhosttyKey.HOME
        KeyEvent.KEYCODE_MOVE_END -> GhosttyKey.END
        KeyEvent.KEYCODE_INSERT -> GhosttyKey.INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> GhosttyKey.DELETE
        KeyEvent.KEYCODE_DEL -> GhosttyKey.BACKSPACE
        KeyEvent.KEYCODE_TAB -> GhosttyKey.TAB
        KeyEvent.KEYCODE_ESCAPE -> GhosttyKey.ESCAPE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> GhosttyKey.ENTER
        KeyEvent.KEYCODE_SPACE -> GhosttyKey.SPACE
        KeyEvent.KEYCODE_GRAVE -> GhosttyKey.BACKQUOTE
        KeyEvent.KEYCODE_MINUS -> GhosttyKey.MINUS
        KeyEvent.KEYCODE_EQUALS -> GhosttyKey.EQUAL
        KeyEvent.KEYCODE_LEFT_BRACKET -> GhosttyKey.BRACKET_LEFT
        KeyEvent.KEYCODE_RIGHT_BRACKET -> GhosttyKey.BRACKET_RIGHT
        KeyEvent.KEYCODE_BACKSLASH -> GhosttyKey.BACKSLASH
        KeyEvent.KEYCODE_SEMICOLON -> GhosttyKey.SEMICOLON
        KeyEvent.KEYCODE_APOSTROPHE -> GhosttyKey.QUOTE
        KeyEvent.KEYCODE_COMMA -> GhosttyKey.COMMA
        KeyEvent.KEYCODE_PERIOD -> GhosttyKey.PERIOD
        KeyEvent.KEYCODE_SLASH -> GhosttyKey.SLASH
        else -> null
    }

    /** The character the physical key carries with no shift applied. */
    private fun unshiftedCodepointFor(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'a'.code + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + (keyCode - KeyEvent.KEYCODE_0)
        KeyEvent.KEYCODE_SPACE -> ' '.code
        KeyEvent.KEYCODE_GRAVE -> '`'.code
        KeyEvent.KEYCODE_MINUS -> '-'.code
        KeyEvent.KEYCODE_EQUALS -> '='.code
        KeyEvent.KEYCODE_LEFT_BRACKET -> '['.code
        KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'.code
        KeyEvent.KEYCODE_BACKSLASH -> '\\'.code
        KeyEvent.KEYCODE_SEMICOLON -> ';'.code
        KeyEvent.KEYCODE_APOSTROPHE -> '\''.code
        KeyEvent.KEYCODE_COMMA -> ','.code
        KeyEvent.KEYCODE_PERIOD -> '.'.code
        KeyEvent.KEYCODE_SLASH -> '/'.code
        else -> 0
    }
}
