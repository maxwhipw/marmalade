//! Ours — not from chuchu. Exposes the terminal cursor's *visual* style
//! (block / bar / underline / hollow block) and its blink mode over JNI.
//!
//! chuchu only ever drew a filled block, so its bridge never surfaced this.
//! libghostty already computes both: `RenderState.update` copies
//! `screen.cursor.cursor_style` into `render_state.cursor.visual_style` and
//! `modes.get(.cursor_blinking)` into `.blinking`, so this file is a read,
//! not a computation. The vendored chuchu_snapshot.zig refreshes
//! `render_state` on every write / scroll / resize and again when it builds a
//! text snapshot, so the value read here is as fresh as the grid the client
//! draws it onto.
//!
//! This lives outside chuchu_snapshot.zig on purpose: that file is vendored
//! verbatim apart from the JNI prefix rename and the one `pub` marker on
//! `ChuchuTerminal` that lets this file name the type. Adding a snapshot
//! header field would have been a real edit to a file we want to keep
//! diffable against upstream chuchu.
//!
//! ## Wire format
//!
//! `nativeCursorStyle` returns one packed i32:
//!
//!   bits 0-2 (mask 0x7)  style: 0 bar, 1 block, 2 underline, 3 hollow block
//!   bit  3   (mask 0x8)  blinking
//!
//! The style ordinals are assigned by the explicit `switch` below rather than
//! by `@intFromEnum`, so reordering ghostty's `cursor.Style` cannot silently
//! change what the Kotlin side decodes — it becomes a compile error here
//! instead. The Kotlin half of this contract is
//! `app/src/main/java/app/marmalade/android/terminal/TerminalCursorStyle.kt`;
//! the two must be changed together.

const snapshot = @import("chuchu_snapshot.zig");

const c = @cImport({
    @cInclude("jni.h");
});

/// Steady block — what a terminal starts in, and what chuchu always drew.
/// Returned for a null handle so a torn-down terminal degrades to the
/// historical behaviour rather than to a bar or an invisible cursor.
const DEFAULT_PACKED: c.jint = 1;

const BLINK_FLAG: c.jint = 8;

fn terminalFromHandle(handle: c.jlong) ?*snapshot.ChuchuTerminal {
    if (handle == 0) return null;
    const raw_handle: u64 = @bitCast(handle);
    return @ptrFromInt(@as(usize, @truncate(raw_handle)));
}

export fn Java_app_marmalade_android_terminal_GhosttyBridge_nativeCursorStyle(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
) callconv(.c) c.jint {
    _ = env;
    _ = thiz;

    const terminal = terminalFromHandle(handle) orelse return DEFAULT_PACKED;
    const cursor = terminal.render_state.cursor;

    const style: c.jint = switch (cursor.visual_style) {
        .bar => 0,
        .block => 1,
        .underline => 2,
        .block_hollow => 3,
    };

    return style | (if (cursor.blinking) BLINK_FLAG else 0);
}
