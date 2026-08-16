//! Root of libmarmalade_term.so.
//!
//! Not ported from chuchu — chuchu's root was its zignal_png.zig, which pulled
//! in the SSH, mosh, local-shell and backup bridges alongside the terminal.
//! We want the terminal core and nothing else: our daemon already owns the PTY,
//! so every byte of chuchu's transport is dead weight here.
//!
//! The `comptime` references are what make the library non-empty: they force
//! semantic analysis of the files where the 45 `export fn` declarations
//! actually live. 44 of them (the JNI surface + the bionic shm shims) are
//! chuchu's, in chuchu_snapshot.zig; the 45th is ours, in cursor_style.zig.

comptime {
    _ = @import("chuchu_snapshot.zig");
    _ = @import("cursor_style.zig");
}
