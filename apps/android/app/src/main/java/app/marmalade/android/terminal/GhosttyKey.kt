// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/ui/terminal/GhosttyKey.kt and
// .../GhosttyKeyAction.kt (merged; chuchu's `Char.toGhosttyKey` is not ported —
// nothing here needs a character→physical-key guess).
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.terminal

import android.view.KeyEvent

/**
 * `ghostty-vt`'s `input.Key` enum, which is `enum(c_int)` and therefore
 * **positional**: every constant below is that key's declaration index in
 * `src/input/key.zig`. They are W3C `KeyboardEvent.code` physical keys
 * (layout-independent), not characters.
 *
 * Verified against our pin — the zig-cache checkout
 * `ghostty-1.3.2-dev-…kOum`, `src/input/key.zig` — on 2026-07-27: the
 * declaration order matches these values exactly (`escape` = 120 because the
 * 41-entry numpad block occupies 80..119). **Re-verify on every ghostty bump**;
 * a key inserted upstream silently shifts every constant after it, and the
 * failure mode is not a crash but wrong escape sequences.
 *
 * Only the keys an Android keyboard can produce are declared. Upstream also has
 * f13–f25, the numpad block, media and browser keys (indices 80..119 and
 * 133..175) — omitted deliberately rather than transcribed blind.
 */
object GhosttyKey {
    const val UNIDENTIFIED = 0
    const val BACKQUOTE = 1
    const val BACKSLASH = 2
    const val BRACKET_LEFT = 3
    const val BRACKET_RIGHT = 4
    const val COMMA = 5
    const val DIGIT_0 = 6
    const val DIGIT_1 = 7
    const val DIGIT_2 = 8
    const val DIGIT_3 = 9
    const val DIGIT_4 = 10
    const val DIGIT_5 = 11
    const val DIGIT_6 = 12
    const val DIGIT_7 = 13
    const val DIGIT_8 = 14
    const val DIGIT_9 = 15
    const val EQUAL = 16
    const val KEY_A = 20
    const val KEY_Z = 45
    const val MINUS = 46
    const val PERIOD = 47
    const val QUOTE = 48
    const val SEMICOLON = 49
    const val SLASH = 50
    const val BACKSPACE = 53
    const val ENTER = 58
    const val SPACE = 63
    const val TAB = 64
    const val DELETE = 68
    const val END = 69
    const val HOME = 71
    const val INSERT = 72
    const val PAGE_DOWN = 73
    const val PAGE_UP = 74
    const val ARROW_DOWN = 75
    const val ARROW_LEFT = 76
    const val ARROW_RIGHT = 77
    const val ARROW_UP = 78
    const val ESCAPE = 120
    const val F1 = 121
    const val F2 = 122
    const val F3 = 123
    const val F4 = 124
    const val F5 = 125
    const val F6 = 126
    const val F7 = 127
    const val F8 = 128
    const val F9 = 129
    const val F10 = 130
    const val F11 = 131
    const val F12 = 132

    /** key_a..key_z are contiguous, so a letter maps by offset. */
    fun letter(offsetFromA: Int): Int = KEY_A + offsetFromA

    /** digit_0..digit_9 are contiguous. */
    fun digit(offsetFromZero: Int): Int = DIGIT_0 + offsetFromZero
}

/** `ghostty-vt`'s `input.Action` enum — also `enum(c_int)`, also positional. */
object GhosttyKeyAction {
    const val RELEASE = 0
    const val PRESS = 1
    const val REPEAT = 2

    fun fromAndroid(action: Int, repeatCount: Int = 0): Int? = when (action) {
        KeyEvent.ACTION_DOWN -> if (repeatCount > 0) REPEAT else PRESS
        KeyEvent.ACTION_UP -> RELEASE
        else -> null
    }
}

/**
 * `ghostty-vt`'s `input.mouse.Action` — also positional, verified against
 * `src/input/mouse.zig` at our pin. NOT `ButtonState`, whose order is the
 * reverse of this one.
 */
object GhosttyMouseAction {
    const val PRESS = 0
    const val RELEASE = 1
    const val MOTION = 2
}

/** `ghostty-vt`'s `input.mouse.Button`. Only the one a touchscreen can be. */
object GhosttyMouseButton {
    const val LEFT = 1
}

/**
 * `ghostty-vt`'s `input.Mods` packed struct, as the bitfield the JNI encoder
 * takes. Bit order is the field order in `src/input/key.zig`.
 */
object GhosttyMods {
    const val NONE = 0
    const val SHIFT = 1 shl 0
    const val CTRL = 1 shl 1
    const val ALT = 1 shl 2
    const val SUPER = 1 shl 3

    /** Ctrl/Alt/Super — the modifiers that mean "this is not text". */
    const val NON_TEXT = CTRL or ALT or SUPER

    fun fromMetaState(metaState: Int): Int {
        var mods = NONE
        if (metaState and KeyEvent.META_SHIFT_ON != 0) mods = mods or SHIFT
        if (metaState and KeyEvent.META_CTRL_ON != 0) mods = mods or CTRL
        if (metaState and KeyEvent.META_ALT_ON != 0) mods = mods or ALT
        if (metaState and KeyEvent.META_META_ON != 0) mods = mods or SUPER
        return mods
    }

    /** The sticky extra-keys chips, as mods. */
    fun sticky(ctrl: Boolean, alt: Boolean): Int =
        (if (ctrl) CTRL else NONE) or (if (alt) ALT else NONE)
}
