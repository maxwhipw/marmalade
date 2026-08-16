# 0015 — The terminal stays on xterm.js; native cores evaluated and deferred

**Status:** Accepted (maintainer, 2026-07-26)

## Context

The terminal screen embeds vendored **xterm.js 6.0.0** (MIT) in a WebView and
renders PTY bytes streamed from `marmaladed` over `terminal.data` /
`terminal.input`. It works, but has produced a run of Android-WebView-specific
defects — the container collapsing to 8px so the PTY sized itself to one row,
selection needing CSS overrides to hand off to Android, rAF starvation — and
**one still-live defect: touch scrolling does not work at all.**

Three parallel research passes (2026-07-26; records kept internally)
evaluated replacing it. Everything below was verified at source.

### The candidates

| | What it is | Terminal emulation | Android integration | Build cost |
|---|---|---|---|---|
| **xterm.js** (incumbent) | MIT, vendored JS in a WebView | Strong — sync output (2026), pixel mouse, reflow | Weakest — the WebView-layout hazard | none |
| **termlib** (`org.connectbot`) | Apache-2.0 **library**, Maven Central, prebuilt `.so`, libvterm (MIT) core | **Weakest** — no DECSET 2026, reflow present but disabled, no images | Very strong — IME, selection handles, magnifier, a11y all done | toolchain uplift to compileSdk 36 |
| **chuchu-derived** (libghostty) | MIT **application** to harvest | **Strongest** — libghostty, incl. kitty graphics | Strong, and proven on a phone | Zig + NDK, plus an extraction |

**Correction to an earlier framing:** xterm.js is emulation-superior to
*libvterm*, **not** to libghostty. Against libghostty it is at best equal and
loses on kitty graphics. xterm.js's advantage over a chuchu-derived core is
**not quality — it is cost and blast radius.**

### Why not termlib now

Its `aar-metadata` requires `minCompileSdk=36`; we are on compileSdk 35, AGP
8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01. That drags in a four-way version
uplift into a repo with a documented `NoSuchMethodError` history from
`:app`/`:shared` Compose classpath skew. And its libvterm core is the weakest
emulation of the three.

### Why not chuchu now

Its terminal core is excellent and solves problems we would otherwise have to
solve ourselves — `CELL_FLAG_SPACER` means **libghostty decides double-width
continuation cells**, so column width agrees with the remote `ncurses` (the #1
correctness risk in any emulator, removed rather than reimplemented); and
`nativeDrainPtyWrites` means it **answers terminal queries**, which de-risks
opencode's unconditional startup probe burst.

But **chuchu is an app, not a library.** No Maven artifact. Adopting it means
vendoring its renderer + snapshot decode + JNI shim and running **Zig + NDK** in
a repo with zero NDK usage today, and its Zig side is entangled with SSH, mosh
and backup we don't want — our daemon already owns the PTY. We want
`chuchu_snapshot.zig` and little else. That is a fork with an extraction, not a
dependency.

Native also costs things a WebView doesn't: a crash becomes a tombstone instead
of a Kotlin stack trace, libghostty is `!Send`/`!Sync` against a coroutine-heavy
app, and **a JNI boundary is exactly what our JVM-only digital twins cannot
exercise** — which is this project's primary verification model.

## Decision

1. **Stay on xterm.js.** Fix the touch-scroll defect in
   `app/src/main/assets/terminal/index.html`. `Gesture.addTarget` has **zero
   call sites** in the vendored bundle (verified), but the fix doesn't need that
   class: `scrollLines` / `scrollPages` / `scrollToBottom` are all public API.
2. **Record chuchu-derived (libghostty) as the preferred native successor** if
   we ever go native — ahead of termlib, on emulation quality.
3. **Do not adopt a native core on "it would be better" alone.** The incumbent
   is cheap, reversible, and has no native failure modes.

**This decision is not about how long anything takes** (global CLAUDE.md:
*don't let implementation time rule out a good idea*). It rests on
reversibility, blast radius, and what our test model can actually verify.

## Revisit triggers — any one of these reopens it

- **T1 — the probe test fails.** Run **opencode** against a candidate and check
  the first screen is clean. Its startup burst (XTGETTCAP, 5× DECRQM, `CSI ?u`,
  DA1, XTVERSION, kitty APC, OSC 99/1337) is mostly unconditional; a terminal
  that echoes unrecognised queries sprays garbage (`foot` needed an upstream
  special case). **libghostty answers it. Whether libvterm does is UNVERIFIED —
  settling that decides termlib vs chuchu-derived.**
- **T2 — the touch-scroll fix fails on device**, or selection proves broken with
  no CSS-level remedy. That flips the WebView from "working with warts" to
  structurally wrong.
- **T3 — the terminal becomes a primary surface** the maintainer lives in daily, rather
  than a secondary one.
- **T4 — a WebView-layout defect lands that we cannot reproduce off-device.**
  That hazard is unbounded by nature: desktop-Chromium twins do not reproduce
  Android WebView layout timing (`.memory/webview-twins-dont-catch-android-layout.md`).

## Consequences

- The WebView-layout hazard stays. Accepted knowingly; it is bounded in practice
  by Kotlin already owning the geometry.
- No kitty graphics, and no images in the terminal, until/unless we go native.
- **Shift+Enter will not work, and that is not the emulator's fault.** Claude
  Code only enables the kitty disambiguation protocol for an allowlist of
  terminals and our daemon sets no `TERM_PROGRAM` (`terminal.ts:142`). **`Ctrl+J`
  is the answer, not a fallback** — an extra-keys row with Ctrl is worth more
  than any keyboard-protocol work. Setting `TERM_PROGRAM` to an allowlisted
  value is a lever, but it also opts us into sync output, OSC 99 notifications
  and the DECSTBM fullscreen renderer — not a casual flag flip.
- If we later adopt chuchu-derived code, `CREDITS.md` needs entries for **chuchu
  (MIT)** and **libghostty (MIT, © 2024 Mitchell Hashimoto, Ghostty
  contributors)**. If termlib: **termlib (Apache-2.0)** and **libvterm (MIT, ©
  2008 Paul Evans)**, plus Apache-2.0 §4(d) NOTICE propagation.
- **Termux's terminal-emulator remains unusable** — its licence is ambiguous
  (root LICENSE says GPLv3 with an Apache exception naming the module, but there
  is no per-module LICENSE and no per-file headers, and the issue asking closed
  with no maintainer answer). Treat as GPL.

Supersede with a new ADR rather than editing this one.
