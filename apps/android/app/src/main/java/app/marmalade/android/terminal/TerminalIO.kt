package app.marmalade.android.terminal

import java.util.Base64

/**
 * Pure terminal I/O helpers: the base64 codec twin of the webui's
 * `terminal-codec.ts`, plus the extra-keys byte mapping. No Android deps — all
 * of this is unit-tested directly ([app.marmalade.android.terminal.TerminalIOTest]).
 *
 * The wire is base64 in both directions (control bytes ^C / ESC sequences must
 * survive JSON transport intact — protocol methods.ts TerminalInputParams and
 * events.ts TerminalDataPayload).
 */
object TerminalIO {

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun bytesToB64(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Keyboard text (a possibly multi-byte-unicode String) → base64 UTF-8. */
    fun textToB64(text: String): String = bytesToB64(text.toByteArray(Charsets.UTF_8))

    /** Decode a base64 payload (terminal.data / attach snapshot) to raw bytes. */
    fun b64ToBytes(b64: String): ByteArray = decoder.decode(b64)

    const val ESC: Byte = 0x1b

    /**
     * Ctrl+key = key & 0x1f. Lowercase letters map by uppercasing first, so
     * Ctrl+c and Ctrl+C both yield 0x03. Matches every terminal's control-char
     * generation ('[' → ESC 0x1b, Space → NUL 0x00, etc.).
     */
    fun ctrlByte(b: Byte): Byte {
        val c = b.toInt() and 0xff
        val upper = if (c in 'a'.code..'z'.code) c - 32 else c
        return (upper and 0x1f).toByte()
    }

    /**
     * Apply the sticky extra-keys modifiers to input bytes:
     *  - Ctrl → each byte becomes [ctrlByte] (Ctrl+c → 0x03).
     *  - Alt → prefix ESC (Alt+f → ESC f), the standard meta-prefix.
     * Ctrl+Alt combines: ESC then the control byte(s).
     * With neither set this is identity — the common typing path pays nothing.
     */
    fun applyModifiers(input: ByteArray, ctrl: Boolean, alt: Boolean): ByteArray {
        var bytes = input
        if (ctrl) bytes = ByteArray(bytes.size) { ctrlByte(bytes[it]) }
        if (alt) bytes = byteArrayOf(ESC) + bytes
        return bytes
    }
}

/**
 * The keys the Compose extra-keys row sends.
 *
 * [ghosttyKey] is the physical key the screen hands to libghostty, so the
 * emulator writes whatever its current modes call for — which is what makes
 * the arrows right inside vim (DECCKM) and under a kitty-protocol app. [mods]
 * rides along for the keys that are a modifier combination rather than a key
 * of their own (Shift+Tab).
 *
 * A key whose [ghosttyKey] is [GhosttyKey.UNIDENTIFIED] has **no physical key
 * behind it** — it is the literal sequence in [bytes], sent verbatim (a literal
 * character, or Ctrl+J for a deliberate newline). There is nothing for the
 * emulator's encoder to decide about those, and inventing a physical key for
 * them would be layout-dependent guesswork (`|` is Shift+Backslash on a US
 * layout and something else elsewhere).
 *
 * [bytes] is therefore EMPTY for every mapped key: a fixed escape sequence next
 * to a physical key would be a second, mode-blind answer to the same question,
 * and nothing reads it. (It was the xterm.js renderer's path, deleted
 * 2026-07-28 — ADR 0016.)
 */
enum class TerminalKey(
    val ghosttyKey: Int = GhosttyKey.UNIDENTIFIED,
    val mods: Int = GhosttyMods.NONE,
    val bytes: ByteArray = ByteArray(0),
) {
    ESCAPE(GhosttyKey.ESCAPE),
    TAB(GhosttyKey.TAB),
    /** "Back-tab" — Claude Code cycles its modes with it. */
    SHIFT_TAB(GhosttyKey.TAB, GhosttyMods.SHIFT),
    ENTER(GhosttyKey.ENTER),
    /**
     * Ctrl+J. The deliberate-newline half of the Enter split: committed IME
     * text turns LF into CR so a soft Enter submits (see [TerminalImeCodec]),
     * which leaves this button as the only way to type a newline *into* a
     * prompt. Byte-only: Ctrl+J and Ctrl+Enter are not the same key to an app
     * that reads the kitty protocol, and 0x0a is what we mean.
     */
    NEWLINE(bytes = byteArrayOf(0x0a)),
    /** Characters an Android keyboard buries under a symbol page. Byte-only. */
    SLASH(bytes = byteArrayOf('/'.code.toByte())),
    TILDE(bytes = byteArrayOf('~'.code.toByte())),
    HYPHEN(bytes = byteArrayOf('-'.code.toByte())),
    PIPE(bytes = byteArrayOf('|'.code.toByte())),
    ARROW_UP(GhosttyKey.ARROW_UP),
    ARROW_DOWN(GhosttyKey.ARROW_DOWN),
    ARROW_RIGHT(GhosttyKey.ARROW_RIGHT),
    ARROW_LEFT(GhosttyKey.ARROW_LEFT),
    PAGE_UP(GhosttyKey.PAGE_UP),
    PAGE_DOWN(GhosttyKey.PAGE_DOWN),
    HOME(GhosttyKey.HOME),
    END(GhosttyKey.END),
}
