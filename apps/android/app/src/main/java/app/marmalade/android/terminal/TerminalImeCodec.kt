package app.marmalade.android.terminal

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * IME edit → terminal bytes, plus the flags the terminal asks its keyboard for.
 * The pure half of the terminal's soft-keyboard input path; the Android half is
 * `ui/terminal/TerminalInputConnection.kt`.
 *
 * This stays JVM-unit-testable despite the `KeyEvent` / `InputType` /
 * `EditorInfo` imports because every value read from them is `static final int`
 * — the compiler inlines them, so no Android class is ever loaded at test time.
 * Keep it that way: read constants here, never call a [KeyEvent] or
 * [EditorInfo] method.
 *
 * Why this exists at all: Gboard types through a *composing region* — text it
 * has not committed and reserves the right to replace, which is exactly what
 * autocorrect does. Under the xterm.js renderer (deleted 2026-07-28) the page's
 * hidden textarea forwarded characters as they were typed, then autocorrect
 * replaced the region and sent the corrected word too; the PTY had already
 * eaten the first copy, so every corrected word arrived twice (maintainer, on device,
 * 2026-07-27). The "don't do that" attributes (`autocorrect=off`,
 * `autocapitalize=off`, `spellcheck=false`) are advisory and Gboard ignores all
 * three. The fix was to own the IME on the Kotlin side, which is what this
 * object plus `TerminalInputConnection` do — renderer-independent, and still
 * the terminal's input path now that the canvas is native.
 */
object TerminalImeCodec {

    /** What a terminal's Backspace actually sends. NOT 0x08 — DEL is what
     *  `stty erase` expects on every modern Unix, and what xterm sends. */
    const val DEL: Byte = 0x7f

    private const val ESC: Byte = 0x1b

    /**
     * The `EditorInfo.inputType` the terminal asks for.
     *
     * With [suggestionsEnabled] off (the default) the field is declared a
     * `VISIBLE_PASSWORD` — the load-bearing flag, because it is what actually
     * stops Gboard suggesting and composing, where `NO_SUGGESTIONS` alone is
     * only a hint many keyboards ignore. Every keystroke then reaches the PTY
     * as it is typed, which is what a shell prompt wants.
     *
     * With it on, plain text + `AUTO_CORRECT`: the keyboard composes and
     * corrects normally. That is safe *only* because
     * `ui/terminal/TerminalInputConnection.kt` swallows the composing region and
     * emits on commit alone, so a corrected word goes out exactly once. The cost
     * is the echo: typed characters appear in the terminal a **word at a time**
     * rather than a character at a time, and a shell that reads raw keys (a TUI,
     * a pager, `read -n1`) sees nothing until the word lands. Hence the default.
     */
    fun imeInputType(suggestionsEnabled: Boolean): Int =
        if (suggestionsEnabled) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        } else {
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

    /**
     * The `EditorInfo.imeOptions` the terminal asks for — the same in both
     * suggestion modes. `NO_PERSONALIZED_LEARNING` is non-negotiable: whatever
     * is typed at a shell prompt (hostnames, paths, tokens) must stay out of the
     * keyboard's learned dictionary, and turning suggestions on does not change
     * that.
     */
    const val IME_OPTIONS: Int = EditorInfo.IME_ACTION_NONE or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
        EditorInfo.IME_FLAG_NO_FULLSCREEN

    /** ESC [ <final> — the CSI form the nav keys use. */
    private fun csi(final: Char): ByteArray =
        byteArrayOf(ESC, '['.code.toByte(), final.code.toByte())

    /**
     * Committed IME text → UTF-8. Multi-byte glyphs and emoji survive.
     *
     * **LF is rewritten to CR**, because a soft Enter does not reach us as a
     * key. Gboard's Enter arrives as *committed text* `"\n"` (0x0a), and 0x0a
     * at a prompt is Ctrl+J — a literal newline in the input buffer, not a
     * submit. On device that meant a prompt in Claude Code could never be sent
     * (maintainer, 2026-07-27). Real terminals do the same translation for typed and
     * pasted input (xterm's `translations`), so this applies to *every*
     * committed string, not just a lone "\n" — a multi-line commit must submit
     * each line the same way a typed one does. Explicit pastes bypass this
     * entirely: they go through the emulator's bracketed-paste path
     * (`engine.paste`), where the running app decides what a newline means.
     *
     * A deliberate newline is still reachable: the extra-keys row's ⏎ sends
     * 0x0a directly ([TerminalKey.NEWLINE]), which never passes through here.
     */
    fun textBytes(text: CharSequence): ByteArray =
        text.toString().replace('\n', '\r').toByteArray(Charsets.UTF_8)

    /**
     * Gboard deletes by asking the editor to drop N characters before the
     * cursor rather than by sending Backspace. A terminal has no editable
     * buffer to drop them from, so this becomes N erase bytes and the running
     * program does the deleting.
     */
    fun deleteBytes(count: Int): ByteArray =
        if (count <= 0) ByteArray(0) else ByteArray(count) { DEL }

    /**
     * A key event from the IME → the bytes a terminal sends for it, or null
     * when the key means nothing to a terminal (modifiers, volume, …) and
     * should be dropped rather than guessed at.
     *
     * Arrows use the **normal** cursor form (`ESC [ A`), not the application
     * form (`ESC O A`): DECCKM is not tracked on this path. It is the last
     * fixed-sequence path in the client — the extra-keys row hands physical
     * keys to the emulator instead ([TerminalKey]), and so does every hardware
     * key the IME does not consume. Only an IME that sends key events rather
     * than committed text lands here.
     */
    fun keyBytes(keyCode: Int, unicodeChar: Int): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> byteArrayOf(0x0d)
        KeyEvent.KEYCODE_DEL -> byteArrayOf(DEL)
        KeyEvent.KEYCODE_FORWARD_DEL -> byteArrayOf(ESC, '['.code.toByte(), '3'.code.toByte(), '~'.code.toByte())
        KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(ESC)
        KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
        KeyEvent.KEYCODE_DPAD_UP -> csi('A')
        KeyEvent.KEYCODE_DPAD_DOWN -> csi('B')
        KeyEvent.KEYCODE_DPAD_RIGHT -> csi('C')
        KeyEvent.KEYCODE_DPAD_LEFT -> csi('D')
        KeyEvent.KEYCODE_MOVE_HOME -> csi('H')
        KeyEvent.KEYCODE_MOVE_END -> csi('F')
        KeyEvent.KEYCODE_PAGE_UP -> byteArrayOf(ESC, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())
        KeyEvent.KEYCODE_PAGE_DOWN -> byteArrayOf(ESC, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())
        else -> if (unicodeChar != 0) textBytes(unicodeChar.toChar().toString()) else null
    }
}
