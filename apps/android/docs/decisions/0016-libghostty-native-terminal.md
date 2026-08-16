# 0016 — The terminal goes native on libghostty-vt; xterm.js retained as fallback pending on-device sign-off

**Status:** Accepted (2026-07-27). Supersedes `0015-terminal-stays-on-xtermjs.md`.

## Context

ADR 0015 kept the WebView terminal on cost/blast-radius grounds and named
revisit triggers. **T2 fired twice within a day of the touch-scroll fix
shipping:**

- Gboard autocorrect doubled every corrected word. No HTML attribute can stop
  it (xterm.js already sets all three advisory attributes; Gboard ignores
  them); the fix had to be a Kotlin `InputConnection` that swallows the
  composing region — IME work the JVM twins could never have caught.
- Long-press copy was structurally broken: Blink starts a selection, then
  xterm's own `contextmenu` handler (`rightClickHandler` →
  `moveTextAreaUnderMouseCursor`) re-focuses the hidden helper textarea, and
  focusing an editable collapses a document selection in Blink. Verified over
  CDP with a focus-event stack trace on 2026-07-27. Every CSS/JS remedy tried
  failed (see the 2026-07-27 handoff's ruled-out table); fixing it means
  patching the vendored xterm bundle's event handling — deeper into the
  WebView, not out of it.

Both defects sit in exactly the class ADR 0015 flagged as the incumbent's
structural weakness: Android WebView behaviour our JVM-only tests cannot
reach. Meanwhile the two 2b blockers dissolved: the emulator rig now exists
(AVD `marmalade-test`, instrumented tests run headless against the real
daemon), and the Zig toolchain is installed and reproducible.

## Decision

1. **The terminal renders natively.** `libmarmalade_term.so` — libghostty-vt
   (Ghostty's headless VT state machine) behind chuchu's snapshot JNI bridge —
   feeds a Compose canvas. Kotlin owns the socket exactly as before; the
   daemon's `terminal.*` RPC surface is unchanged.
2. **The engine is transport-free and thread-confined.**
   `GhosttyTerminalEngine` owns the native handle on a single-thread
   dispatcher; the handle never crosses that boundary (selection extraction is
   a suspend call, not a leaked `Long` — deliberately unlike chuchu).
3. **xterm.js stays in the tree behind Settings → Developer → "Native
   terminal (libghostty)" (default ON)** until the maintainer signs off on-device
   (stage 2d). Deleting the WebView path is his call, not this ADR's.
4. **`chuchu_snapshot.zig` stays vendored-verbatim except the JNI symbol
   prefix.** Needs are met around it (own `root.zig`, PNG-stub
   `zignal_png.zig`) so re-syncing with upstream chuchu stays a diff.
5. **The prebuilt `.so` is committed** (`native/prebuilt/jniLibs/`), so the
   app build never needs Zig/NDK; rebuilding is documented in
   `native/README.md`.

## What decided it (beyond T2)

- libghostty **answers terminal queries** (`nativeDrainPtyWrites`): DA1 in →
  `ESC[?62;22c` out, nothing echoed to the grid — verified by instrumented
  test. This is what keeps opencode's startup probe burst off the screen.
- libghostty decides wide-char continuation (`CELL_FLAG_SPACER`), removing
  the #1 emulator-correctness risk rather than reimplementing it.
- Selection/copy/IME/scroll are now plain Android views and pure Kotlin —
  the exact surfaces that kept breaking are the exact surfaces that moved
  into reach of our test model (JVM twins for engine/selection/key logic,
  instrumented tests for the JNI boundary).

## Consequences

- **Native failure modes arrive:** a crash is a tombstone, not a Kotlin
  stack trace. Mitigated by thread confinement, the `.so`-load failure
  surfacing as an error state (tested), and the WebView fallback toggle.
- **arm64-v8a is symbol-verified but not yet run** — the emulator is x86_64.
  First Pixel launch is the real arm64 proof (flagged for the maintainer's daily use).
- **Kitty graphics: PNG payloads are dropped** (the zigimg stub). rgb/gray/
  RGBA decode fine. Restoring PNG = zigimg licence check + swap the stub, or
  `BitmapFactory` on the Kotlin side; tracked in `native/README.md`.
- **No cursor styles** (block only, no blink) — the snapshot carries no
  cursor-shape field; adding one is a Zig+Kotlin change.
- **Toolchain pin:** Zig 0.15.2 exactly, ghostty pinned by hash in
  `build.zig.zon`; `GhosttyKey` constants mirror `input/key.zig` declaration
  order and must be re-verified on every ghostty bump.
- Instrumented tests (`app/src/androidTest`) exist for the first time; the
  emulator rig is the repeatable gate for native-boundary work.
- Fonts: bundled per CREDITS.md (JetBrains Mono primary; Nerd-Font symbols
  for PUA prompt glyphs; Noto Symbols 2 fallback; system emoji).

Supersede with a new ADR rather than editing this one.
