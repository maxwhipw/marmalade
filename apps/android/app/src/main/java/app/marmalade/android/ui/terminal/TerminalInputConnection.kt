package app.marmalade.android.ui.terminal

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import app.marmalade.android.terminal.TerminalImeCodec

/**
 * The [EditorInfo] a terminal wants. Applied by the invisible input view the
 * native libghostty canvas hosts ([TerminalScreen]); deliberately
 * renderer-independent, and free of any dependency on how the grid is drawn.
 *
 * The flags themselves live in [TerminalImeCodec.imeInputType] /
 * [TerminalImeCodec.IME_OPTIONS] so they can be asserted on the JVM. In short:
 * with [suggestionsEnabled] off (the default) the field is a `VISIBLE_PASSWORD`
 * and Gboard neither composes nor suggests, so every keystroke echoes as typed;
 * with it on the keyboard autocorrects and the terminal echoes a **word at a
 * time**, because [TerminalInputConnection] holds the composing region back
 * until the IME commits it. That hold is what makes the mode safe at all — a
 * corrected word still reaches the PTY exactly once. `NO_PERSONALIZED_LEARNING`
 * is set either way; shell input stays out of the keyboard's dictionary.
 *
 * The -1 selection says there is no document behind the connection, rather than
 * letting the IME assume a cursor at 0 and try to reconcile against text it
 * cannot read.
 *
 * A change to [suggestionsEnabled] only reaches the keyboard after
 * `InputMethodManager.restartInput`, which is what the row's toggle calls —
 * `EditorInfo` is read once when the connection is created.
 */
fun applyTerminalEditorInfo(outAttrs: EditorInfo, suggestionsEnabled: Boolean) {
    outAttrs.inputType = TerminalImeCodec.imeInputType(suggestionsEnabled)
    outAttrs.imeOptions = TerminalImeCodec.IME_OPTIONS
    outAttrs.initialSelStart = -1
    outAttrs.initialSelEnd = -1
}

/**
 * The terminal's own [android.view.inputmethod.InputConnection]: soft-keyboard
 * edits become PTY bytes directly, with no editable document in between.
 *
 * **Composition is swallowed, and that is the whole fix.** A composing region
 * is text the IME has not committed yet and reserves the right to *replace* —
 * which is exactly what autocorrect does. Emitting it as it arrives is what put
 * every corrected word on the wire twice (see [TerminalImeCodec] for the full
 * account). A terminal has no editable buffer to retract from, so the only
 * correct move is to wait: nothing goes out until the IME commits.
 *
 * Nothing is lost by waiting. [commitText] is the normal path and clears any
 * pending composition; [finishComposingText] flushes the case where an IME ends
 * a composition without committing it. The visible cost is that an IME which
 * composes anyway echoes a word-at-a-time rather than a character-at-a-time.
 * That is exactly the trade the "Abc" toggle in the extra-keys row offers:
 * suggestions off (the default) sets `VISIBLE_PASSWORD`, which stops Gboard
 * composing at all; suggestions on lets it compose, and this class is what keeps
 * the corrected word from being sent twice (see [applyTerminalEditorInfo]).
 *
 * Constructed with `fullEditor = false`: there is no editable document behind
 * this connection, so the IME's text queries answer empty. That is honest —
 * the "document" is a PTY the client cannot read back.
 *
 * @param emit receives terminal bytes; wired to the screen's typed-input path,
 *   so the sticky Ctrl/Alt modifiers still apply to typed keys.
 */
class TerminalInputConnection(
    view: View,
    private val emit: (ByteArray) -> Unit,
) : BaseInputConnection(view, /* fullEditor = */ false) {

    /** The uncommitted composing region, or null when there isn't one. */
    private var composing: CharSequence? = null

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composing = null // superseded — never emit it as well, that's the bug
        if (!text.isNullOrEmpty()) emit(TerminalImeCodec.textBytes(text))
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composing = text
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean = true

    override fun finishComposingText(): Boolean {
        val pending = composing
        composing = null
        if (!pending.isNullOrEmpty()) emit(TerminalImeCodec.textBytes(pending))
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // A pending composition was never sent, so there is nothing on the wire
        // to erase — dropping it locally is the whole deletion.
        if (composing != null) {
            composing = null
            return true
        }
        val bytes = TerminalImeCodec.deleteBytes(beforeLength)
        if (bytes.isNotEmpty()) emit(bytes)
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        // Key-up would double every keystroke; the terminal cares only about
        // the press. Deliberately NOT delegated to super, which would dispatch
        // the event back into the host view and encode it a second time.
        if (event == null || event.action != KeyEvent.ACTION_DOWN) return true
        TerminalImeCodec.keyBytes(event.keyCode, event.unicodeChar)?.let(emit)
        return true
    }

    /** The IME's action key. `IME_ACTION_NONE` is requested, but a keyboard may
     *  still surface one — Enter is the only sane reading in a terminal. */
    override fun performEditorAction(actionCode: Int): Boolean {
        finishComposingText()
        emit(byteArrayOf(0x0d))
        return true
    }
}
