# Credits

Attribution for borrowed code and bundled data assets, per the project
convention in `CLAUDE.md`. Patterns and ideas don't need a credit;
shipped code/data does.

## Bundled assets

### `app/src/main/assets/marmalade.onnx`, `app/src/main/assets/openclaw.onnx`

Custom-trained openWakeWord classifiers shipped with the app. Training
pipeline: [marmalade-openwakeword](https://github.com/maxwhipw/marmalade-openwakeword)
(separate repo, not part of this codebase).

**Training data sources:**

| Source | License | Role |
|---|---|---|
| [LibriSpeech](https://www.openslr.org/12) | CC-BY 4.0 | Negative samples (background speech) |
| [Kokoro TTS](https://github.com/hexgrad/kokoro) | Apache 2.0 | Positive sample synthesis |
| [Kitten TTS](https://github.com/KittenML/KittenTTS) | Apache 2.0 | Positive sample synthesis |
| [Piper TTS](https://github.com/rhasspy/piper) | MIT | Positive sample synthesis |

Hard-negative phrases (lemonade, grenade, marmite, open jaw, claw
machine, etc.) are rendered through ~38 English TTS voices to teach
the classifier to discriminate near-miss phonemes.

No YouTube-derived corpora and no non-commercial-licensed audio were
used. The bundled models are therefore safe to redistribute under this
project's license.

The previous `hello_world.onnx` placeholder (a CC-BY-NC-SA model from
the upstream openWakeWord pre-trained set) was removed in commit
`a101c84` because its non-commercial clause was incompatible with
public release.

### `app/src/main/assets/melspectrogram.onnx`, `app/src/main/assets/embedding_model.onnx`

openWakeWord's **shared feature models** — the mel-spectrogram front-end
and the speech-embedding backbone that every keyword classifier runs on
top of. Bundled verbatim from
[dscripka/openWakeWord](https://github.com/dscripka/openWakeWord) (repo
Apache-2.0). These preprocessing models are **Apache-2.0**, distinct from
openWakeWord's *pre-trained keyword classifiers* (CC-BY-NC-SA), which we
do NOT ship — our classifiers are self-trained (above). The embedding
model derives from Google's
[`speech_embedding`](https://tfhub.dev/google/speech_embedding/1) TFHub
module, also Apache-2.0. openWakeWord ships no upstream `NOTICE` file to
pass through (checked 2026-07-19). Safe to redistribute commercially.

### `app/src/main/assets/silero_vad.onnx`

[Silero VAD](https://github.com/snakers4/silero-vad) — the voice-activity
gate that fronts the wake-word chain (ADR 0010) and the STT recognizers.
**MIT**, Copyright (c) 2020-present Silero Team. Safe to redistribute
commercially.

### `app/src/main/assets/stt/distil-small.en-*.onnx`

The bundled on-device speech-to-text model — the default STT engine
(ADR 0012, replacing Whisper tiny). int8 `encoder` / `decoder` / `tokens`.

| Component | License | Source |
|---|---|---|
| [Distil-Whisper `distil-small.en`](https://huggingface.co/distil-whisper/distil-small.en) | **MIT** (Hugging Face) | Distilled English Whisper; the shipped weights |
| [OpenAI Whisper](https://github.com/openai/whisper) | **MIT** (OpenAI) | Teacher model distil-whisper is distilled from |
| [sherpa-onnx export](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-distil-small.en) | Apache 2.0 (k2-fsa) | int8 ONNX conversion for the sherpa-onnx runtime |

distil-whisper's distillation used documented open ASR corpora (People's
Speech, Common Voice, GigaSpeech, LibriSpeech), pseudo-labeled by Whisper
large-v2/v3 — a cleaner training-data provenance than stock Whisper's
undisclosed web-scraped audio (the reason it's bundled over the tiny.en it
replaced). All MIT/Apache — safe to redistribute commercially.

### `app/src/main/res/raw/activation.ogg`

The wake/ready voice-cue sound — a short (~150 ms) "pop" tone
procedurally generated for this project (commit `4ff6394`). It is not
sampled from, or derived from, any third-party recording, so no
external license applies; it ships under this project's own license.

### `app/src/main/res/font/` — the UI typefaces, OFL-1.1

The three UI faces. All **SIL Open Font License 1.1**, so all are
free to redistribute inside the APK (including commercially); the license
requires the notice to travel with them, which
`app/src/main/assets/licenses/OFL-1.1.txt` does — it carries every bundled
font's copyright line plus one verbatim copy of the shared license body.

| File | Family | Role | Copyright (verbatim, name table ID 0) |
|---|---|---|---|
| `momo_trust_display.ttf` | [Momo Trust Display](https://github.com/typeassociates/MomoTrustDisplay) | **Wordmark** — the official final face | Copyright 2024 The Momo Trust Project Authors |
| `manrope_variable.ttf` | [Manrope](https://github.com/sharanda/manrope) | Body + headings | Copyright 2019 The Manrope Project Authors |
| `space_mono_regular.ttf` | [Space Mono](https://github.com/googlefonts/spacemono) | Code / terminal | Copyright 2016 The Space Mono Project Authors |

Each file was taken from the Google Fonts distribution
(`github.com/google/fonts`), and each copyright line above was read out of
the shipped file's own name table rather than from upstream's README — the
two can differ per revision.

### `app/src/main/res/font/` — the terminal typefaces

The native terminal's three-face chain (`ui/terminal/GhosttyCanvas.kt`):
text in JetBrains Mono, private-use icon glyphs in Symbols Nerd Font Mono,
non-PUA symbols in Noto Sans Symbols 2. Emoji stays the system default
face, which is what carries Android's color-emoji chain — nothing is
bundled for it. The Nerd Font is the one that matters: without it, every
starship / powerlevel10k / opencode prompt draws tofu.

Each file was fetched from its project's own upstream release (not a
mirror, not a redistribution), bundled **unmodified**, under its original
filename and family name. Every copyright line was read out of the shipped
file's own name table.

| File | Family | Release | sha256 | License | Copyright (verbatim) |
|---|---|---|---|---|---|
| `jetbrains_mono_regular.ttf` | [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) | [v2.304](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304), `fonts/ttf/JetBrainsMono-Regular.ttf` | `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f` | OFL-1.1 | Copyright 2020 The JetBrains Mono Project Authors |
| `symbols_nerd_font_mono_regular.ttf` | [Symbols Nerd Font Mono](https://github.com/ryanoasis/nerd-fonts) | [v3.4.0](https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.4.0), `NerdFontsSymbolsOnly.zip` | `f0f624d9b474bea1662cf7e862d44aebe1ae1f6c7f9cb7a0ca5d0e5ac9561c60` | MIT (aggregate — see below) | Copyright (c) 2016, Ryan McIntyre |
| `noto_sans_symbols2_regular.ttf` | [Noto Sans Symbols 2](https://github.com/notofonts/symbols) | [NotoSansSymbols2-v2.008](https://github.com/notofonts/symbols/releases/tag/NotoSansSymbols2-v2.008), `googlefonts/ttf/` | `7d5fb73b7ca67a6798101741f5d280a3d016a56a197afcd4199dbb57b4b82a21` | OFL-1.1 | Copyright 2022 The Noto Project Authors |

The two OFL faces join `app/src/main/assets/licenses/OFL-1.1.txt`.
JetBrains Mono's and Noto Sans Symbols 2's own `OFL.txt` files were diffed
against the body already vendored there and are identical, so one copy
still serves. Neither is modified, so the Reserved Font Name clause is not
engaged. Noto Sans Symbols 2 is Google's, drawn by Monotype (name table
IDs 8/9: "Monotype Imaging Inc." / "Monotype Design Team").

**Symbols Nerd Font Mono's licensing is layered**, and that was the point
of vetting it rather than trusting the top-level badge. The file ships MIT
(© 2014 Ryan L McIntyre, from the LICENSE inside the release archive),
but it is a *merge* of thirteen upstream icon sets, each keeping its own
terms. All thirteen were traced to their own LICENSE file or source-font
name table:

| Set | License | Copyright holder |
|---|---|---|
| Seti-UI + Nerd Fonts "Original" | MIT | Jesse Weed (2014) / Ryan L McIntyre (2014) |
| Devicons | MIT | konpa (2015) |
| Font Awesome Free 6 | CC BY 4.0 (icons), OFL-1.1 (font files) | Fonticons, Inc. (2022) |
| Font Awesome Extension | MIT | André Luiz Gava (2017) |
| Material Design Icons | Apache-2.0 | The Pictogrammers icon group |
| Weather Icons | OFL-1.1 | Erik Flowers |
| GitHub Octicons | MIT | GitHub Inc. (2023) |
| Font Logos (ex Font Linux) | Unlicense | Lukas W (2014-2024) |
| Powerline Symbols | MIT | Kim Silkebækken and other contributors (2013) |
| Powerline Extra Symbols | MIT | Ryan L McIntyre (2016) |
| IEC Power Symbols | MIT | Joe Loughry (2013) |
| Pomicons | OFL-1.1 | Gabriele Lana (2021) |
| Codicons | CC BY 4.0 | Microsoft Corporation |

**Nothing in that list is GPL, LGPL, non-commercial, or
redistribution-restricted** — every obligation is attribution, which is
what the notice files discharge. (The only LGPL string anywhere in
nerd-fonts' own `license-audit.md` is the Overpass *patched font*, which
is not part of the symbols-only build.) Full inventory, every verbatim
copyright line, the trademark caveat on the brand icons, and the note on
the MIT/OFL clause-5 overlap:
`app/src/main/assets/licenses/SymbolsNerdFont.txt`, with the shared bodies
in `Apache-2.0.txt` and `CC-BY-4.0.txt` beside it.

**Momo Trust Display ships a single static 400 master**, so `Wordmark` in
`ui/theme/Type.kt` declares weight 400 and callers must not request a
heavier one (Compose would synthesize a fake bold). Fredoka, which stood in
for it while it was wrongly believed to be non-distributable, was removed in
the same change.

### `app/src/main/assets/katex/` — KaTeX 0.16.47, MIT

Vendored [KaTeX](https://github.com/KaTeX/KaTeX) distribution
(`katex.min.js`, `katex.min.css`, the woff2 font set, upstream
`LICENSE` alongside) used by `ui/chat/MathBlock.kt` to render display
math in chat. Copied verbatim from the katex npm package (same pin the
desktop client uses). Deliberately NOT a third-party Android wrapper
library (all unmaintained/license-unclear) and NOT jlatexmath (GPL —
forbidden here).

## Code

### openclaw-assistant — MIT (the fork base)

This app began as a fork of
[yuga-hashimoto/openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant)
(MIT, Copyright (c) 2026 yuga-hashimoto; full text in
`third_party/openclaw-assistant/LICENSE`). A systematic overlap
analysis (2026-08-15, against upstream HEAD) found the derivation is
still substantial, not historical:

- **Effectively verbatim (modulo package rename):** the node/device
  layer (`SmsManager`, `CameraCaptureManager`, `JpegSizeLimiter`,
  `CameraHudOverlay`/`CameraHudState`, `NotificationManager`,
  handlers for photos, location, contacts, calendar, screen, motion,
  app-update, system info, device, install), `VoiceDiagnostics`,
  `WakeWords`, `VoiceWakeMode`, `DeviceNames`, `SessionKey`,
  `app/lint.xml`, several `res/xml/` files, `ic_mic.xml`.
- **Majority-derived:** `TTSManager`, `AndroidTTSProvider`,
  `TTSUtils`, `NodeUtils`, `NodeForegroundService`,
  `SessionForegroundService`, `ScreenRecordManager`,
  `PermissionRequester`, `SecurePrefs`, `HotwordService` (large
  sections), plus most of `values/strings.xml` and all localized
  translations (de/es/fr/hi/ja/ru/zh — upstream's translation work).
- **Original to this project:** the chat UI (`chat.messages`,
  `ui.chat`), the wire/RPC layer (`rpc/`), the terminal stack
  (`terminal/`, credited separately below), persistence
  (`ChatDao`/`AppDatabase`), and theming.

### openWakeWord architecture — Apache-2.0

`app/src/main/java/app/marmalade/android/speech/wake/` (`MelWindowBuffer`,
`OpenWakeWordChain`) implements the pipeline architecture published by
[dscripka/openWakeWord](https://github.com/dscripka/openWakeWord)
(Apache-2.0) — specifically the `AudioFeatures` class's streaming
windowing scheme: 80ms/1280-sample audio hops, 8 new mel frames per hop,
a 76-frame sliding embedding window advanced by an 8-frame step, mel
output scaling (`x/10 + 2`), and a 16-embedding classifier window. These
parameters and the windowing algorithm were read from
`openwakeword/utils.py` and reimplemented as original Kotlin — no
openWakeWord source was copied verbatim.

This is a from-scratch adaptation of the *architecture*, not a port of
the code, and stands entirely apart from `xyz.rementia:openwakeword`
(the closed-source, unknown-license AAR this pipeline replaces — see
`docs/decisions/0010-in-repo-wake-word-pipeline.md`). No code, decompiled
or paraphrased, was taken from that AAR.

### Kai dynamic-UI parser repair — Apache-2.0

`app/src/main/java/app/marmalade/android/ui/blocks/JsonRepair.kt` is
adapted from [SimonSchubert/Kai](https://github.com/SimonSchubert/Kai)
(Apache-2.0), `composeApp/.../ui/dynamicui/KaiUiParser.kt` — the
syntax-repair stages (`fixJsonSyntax` / `sanitizeJson` /
`trimTrailingIncomplete`) that make truncated/damaged LLM JSON render as
partial trees instead of error blocks. The surrounding Marmalade UI v1
node vocabulary and renderer (`UiNode.kt`, `UiTreeParser.kt`,
`UiNodeRenderer.kt`) follow Kai's architecture (data-vocabulary trees in
markdown fences, interactions as plain user messages) but are original
code against the Marmalade spec (marmalade repo
`docs/dynamic-ui/marmalade-ui-v1.md`).

### Session running indicator — design after `svg-spinners`, MIT

The running animation in
`app/src/main/java/app/marmalade/android/ui/components/SessionStatusIndicator.kt`
follows the **`blocks-shuffle-3`** design from
[n3r4zzurr0/svg-spinners](https://github.com/n3r4zzurr0/svg-spinners)
(MIT, Copyright © Utkarsh Verma) — three rounded squares orbiting a 2×2
grid, one move at a time. The `WAVE` fallback in the same file follows
that project's **`3-dots-fade`**.

**No code was copied.** Those are SMIL-animated SVGs, which Compose
cannot render; the Kotlin is original Canvas drawing, re-timed and
re-proportioned for a 14dp indicator. The entry is here because the
*design* is theirs and MIT costs nothing to honour. Chosen from a
lineup in the umbrella workspace's design lab
(`design-lab/labs/session-status/round3.html`), which also credits
[SamHerbert/SVG-Loaders](https://github.com/SamHerbert/SVG-Loaders)
(MIT, Copyright © 2014 Sam Herbert) for options shown but not shipped.

### `MarmaladeIcons.kt` — Lucide icon geometry, dual ISC / MIT

`shared/src/commonMain/kotlin/app/marmalade/android/ui/icons/MarmaladeIcons.kt`
is the Marmalade icon map (design-lab `icon-map`, signed off 2026-08-01). The
26 glyphs are built from **[Lucide](https://lucide.dev)** SVG path data —
adapted, not copied verbatim: `<rect>`/`<circle>`/`<line>` elements are
expanded to equivalent path commands and arc flags de-compressed so Compose's
`PathParser` can read them. No icon library is on the dependency list; there is
nothing here at runtime but path strings.

Lucide's licence is a **dual** licence, not plain ISC, and both notices apply
to this file:

| Part | Licence | Copyright |
|---|---|---|
| Lucide originals | ISC | © Lucide Icons and Contributors |
| The Feather-derived subset | MIT | © 2013-present Cole Bemis |

Four of the glyphs we ship — **`feather`, `key`, `search`, `check`** — are on
Lucide's own list of icons derived from
[Feather](https://github.com/feathericons/feather), so the MIT notice is not
optional. The full upstream text, including that list, is vendored verbatim at
**`third_party/lucide/LICENSE`**.

### `native/` — chuchu (MIT) + libghostty-vt (MIT)

`native/` builds `libmarmalade_term.so`, the JNI bridge around **libghostty-vt**
— Ghostty's headless terminal state machine, with zigimg decoding the PNG
payloads kitty graphics arrive in. Three upstreams, all MIT, all verified at
source rather than from memory; the texts are vendored in
`native/licenses/` (MIT embeds its copyright line, so each component carries
its *own* copy rather than sharing one).

| Component | License | Copyright (verbatim, from the artifact) |
|---|---|---|
| [chuchu](https://github.com/jossephus/chuchu) | MIT | Copyright (c) 2026 jossephus |
| [Ghostty](https://github.com/ghostty-org/ghostty) @ `a746d0f` (1.3.2-dev) | MIT | Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors |
| [uucode](https://github.com/jacobsandlund/uucode) 0.2.0 | MIT | Copyright (c) 2026 Jacob Sandlund |
| [zigimg](https://github.com/zigimg/zigimg) @ `7b98e82` (`zig-0.15` branch) | MIT | Copyright (c) 2019-2021 zigimg developers |

**From chuchu**, which is an SSH client rather than a library, so this is a
harvest and not a dependency:

| File | What changed |
|---|---|
| `native/src/bridge/chuchu_snapshot.zig` | attribution header, plus **one** mechanical diff: the JNI export prefix renamed to `Java_app_marmalade_android_terminal_GhosttyBridge_*`, since JNI binds a native method by its declaring class's Java package. Otherwise verbatim — the terminal core bridge, and the reason for the whole exercise |
| `native/src/ndk.zig` | header, plus one generated-comment string renamed |
| `native/src/bridge/zignal_png.zig` | header, plus one deletion: upstream this file doubled as chuchu's build root, so it held a `comptime` block force-analysing the SSH / mosh / local-shell / backup bridges. That block is gone (our `root.zig` does the job, for the snapshot bridge alone); the zigimg decode bodies are verbatim, and both signatures must stay identical because the verbatim snapshot bridge calls them |
| `native/src/bridge/chuchu_jni_internal.h`, `version-script.map` | header only |
| `native/build.zig` | derived and heavily stripped — see below |
| `app/src/main/java/app/marmalade/android/terminal/GhosttyBridge.kt` | package, `System.loadLibrary("marmalade_term")`, header. The `external fun` declarations are chuchu's and must stay in lockstep with the Zig exports |
| `app/src/main/java/app/marmalade/android/terminal/TerminalSnapshot.kt` | package, header, and a split: chuchu's single file also held `ImagePlacement`/`parseImages`, which moved to `TerminalImages.kt` so the grid decode stays JVM-pure and unit-testable. Decode logic unchanged |
| `app/src/main/java/app/marmalade/android/terminal/TerminalImages.kt` | the Bitmap half of that split — `parseImages` was `TerminalSnapshot.Companion.parseImages` upstream; body unchanged |
| `app/src/main/java/app/marmalade/android/terminal/GhosttyTerminalEngine.kt` | the terminal-hosting seam of chuchu's `TerminalSessionEngine.kt` only (write/drain ordering, snapshot throttle, colour replay, resize/scroll/paste). Its SSH, mosh, local-shell, reconnect, host-key, SFTP and multiplexer machinery — roughly 700 of 1287 lines — is not ported; our transport is the daemon's `terminal.*` RPC and the screen owns it |
| `app/src/main/java/app/marmalade/android/terminal/TerminalRuns.kt` | the run-segmentation and paint-choice decisions from chuchu's `TerminalCanvas.kt`, lifted out of the draw loop into pure functions (glyph coverage asked through an interface) so they are JVM-unit-testable. Selection is not modelled |
| `app/src/main/java/app/marmalade/android/ui/terminal/GhosttyCanvas.kt` | chuchu's `TerminalCanvas.kt` drawing and its whole gesture multiplexer (scroll, pinch, long-press selection, selection drag, edge auto-scroll, double-tap word select, app-mouse drag). Dropped: the preview fit mode, and the raw `terminalHandle` parameter (a data race we are not inheriting). The typefaces are ours, not chuchu's — see the terminal font row above |
| `app/src/main/java/app/marmalade/android/terminal/TerminalSelection.kt` | chuchu's `TerminalSelection.kt` model, `wordAt` and `extractSelectionText`, plus `cellAt` and `remapSelectionForViewportScroll` from its `TerminalCanvas.kt`. Not ported: `buildSelectionState`, which formats selections by calling into the native terminal from the UI thread — that moved behind `GhosttyTerminalEngine.selectionText`. The remap returns a value instead of firing callbacks |
| `app/src/main/java/app/marmalade/android/ui/terminal/TerminalSelectionHandle.kt` | chuchu's `TerminalSelectionHandle` composable and the geometry half of its `TerminalSelectionState` |
| `app/src/main/java/app/marmalade/android/terminal/GhosttyKey.kt` | chuchu's `GhosttyKey.kt` + `GhosttyKeyAction.kt`, merged. Values re-verified against our pinned `ghostty-vt` `src/input/key.zig` rather than trusted; the numpad/media/f13+ blocks are omitted, and `Char.toGhosttyKey` is not ported |
| `app/src/main/java/app/marmalade/android/terminal/KeyMapper.kt` | chuchu's `KeyMapper.kt`, with its 36 spelled-out letter/digit branches folded into range arithmetic, plus the utf8 rule from its `TerminalViewModel.onHardwareKey` lifted into a pure, testable function |

`native/src/bridge/root.zig` is **ours**, not ported. It exists so that
`chuchu_snapshot.zig` could stay otherwise verbatim: chuchu's root dragged in
its SSH, mosh, local-shell and backup bridges, and we want the terminal core
alone.

`build.zig` dropped libssh2, OpenSSL and mosh entirely (our daemon owns the
PTY, so chuchu's whole transport is dead weight), along with the 32-bit
targets, the jniLibs copy step and the OpenSSL test step. zigimg stayed: it is
what decodes kitty-graphics PNG payloads, the format most real senders use.

**zigimg** is not vendored as source — it is fetched by pin and statically
linked into the shipped `.so`, so its notice travels with us
(`native/licenses/zigimg.LICENSE`, and `app/src/main/assets/licenses/zigimg.txt`
for the in-app screen). It is MIT with **no transitive dependencies** of its
own, so it adds exactly one notice and no copyleft risk. It carries one
embedded third-party notice — the HSLuv colour conversion adapted from
[hsluv-c](https://github.com/hsluv/hsluv-c) (MIT, © 2015 Alexei Boronine,
© 2015 Roger Tallada, © 2017 Martin Mitáš) — copied verbatim to
`native/licenses/zigimg-third-party/`. The pin is chuchu's own commit on the
`zig-0.15` branch, not master: zigimg master requires Zig 0.16 while this build
needs 0.15.2 exactly, so the version is load-bearing, not incidental.

**uucode** is not vendored as source — it is fetched by pin and statically
linked into the shipped `.so`, which is exactly why its notice has to travel
with us. It bundles further third-party notices for the Unicode data and
width tables it derives from (Unicode License V3, Björn Höhrmann's UTF-8 DFA,
and the MIT-licensed wcwidth family: go-runewidth, unicode-width, uniseg,
utf8proc, wcwidth, zg, ziglyph). All are copied verbatim into
`native/licenses/uucode-third-party/`.

**The invariant that keeps this clean:** depend on Ghostty's `ghostty-vt`
module, never its `ghostty` module. Ghostty's full manifest carries copyleft
— libintl, gtk4-layer-shell, glib/gobject and plasma-wayland-protocols are all
LGPL — but every one is declared `.lazy = true` and belongs to the GTK desktop
app, so none is fetched on the `ghostty-vt` path (which is `src/lib_vt.zig` +
unicode tables + uucode, with oniguruma disabled and SIMD off). Verified in
Ghostty's `build.zig.zon` and `src/build/GhosttyZig.zig` at the pinned commit.
Widening that dependency would pull LGPL into an app that forbids it.

### `com.openclaw.assistant` (fork base) — MIT

This app is a fork of [yuga-hashimoto/openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant)
(MIT), rebranded as Marmalade. The original protocol/connection layer,
VoiceInteractionService scaffold, invoke handlers, and chat plumbing
are inherited from that codebase.

### External reference projects

Patterns (not code) studied from MIT/Apache 2.0 Android projects are
listed in `docs/references/android-reference-projects.md`. When actual
code is borrowed from those projects, an entry will be added here with
the specific files/functions and the rationale.
