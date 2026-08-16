# `native/` — libghostty-vt terminal core (Zig + NDK)

Builds `libmarmalade_term.so`: the JNI bridge around **libghostty-vt**, the
headless terminal state machine from [Ghostty](https://github.com/ghostty-org/ghostty).
Bytes in, a flat snapshot of the grid out. It is not wired into the app yet —
this directory currently only has to *build*.

Why this exists at all is `docs/decisions/0015-terminal-stays-on-xtermjs.md`
(and whichever ADR supersedes it): the WebView terminal works but carries an
Android-layout hazard our JVM tests cannot reach, and libghostty is the one
candidate core that **answers terminal queries** (`nativeDrainPtyWrites`)
rather than echoing them — which is what decides whether opencode's
unconditional startup probe burst paints a clean first screen.

## Build

```sh
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/26.3.11579264
zig build native -Doptimize=ReleaseFast          # from this directory
```

Outputs, ready to drop into Gradle's `jniLibs` layout:

```
zig-out/jniLibs/arm64-v8a/libmarmalade_term.so     # every real device
zig-out/jniLibs/x86_64/libmarmalade_term.so        # emulator
```

`zig-out/` is gitignored — it is an intermediate. The `.so` the app actually
ships is a **committed** copy under `prebuilt/jniLibs/`, which `:app` picks up
via `jniLibs.srcDir("../native/prebuilt/jniLibs")`. That is deliberate: it
keeps Zig and the NDK entirely off the Gradle build path, so a fresh clone can
build the APK with nothing but the Android SDK. After any change here, rebuild
**both** ABIs, copy them across, and commit the binaries with the source
change that produced them:

```sh
cp zig-out/jniLibs/arm64-v8a/libmarmalade_term.so prebuilt/jniLibs/arm64-v8a/
cp zig-out/jniLibs/x86_64/libmarmalade_term.so    prebuilt/jniLibs/x86_64/
```

**Requires Zig 0.15.2 exactly** — that is Ghostty's `minimum_zig_version` at
the pinned commit, and Zig's build API is still moving fast enough that a
neighbouring version will not compile this. It is not installed system-wide;
see the handoff for where it lives.

`zig build` fetches Ghostty (plus uucode, transitively) and zigimg over the
network into a global Zig cache on first run. All are pinned by hash in
`build.zig.zon`, so the fetch is reproducible and a tampered artifact fails
the build.

Verify a build without a device:

```sh
llvm-nm -D --defined-only zig-out/jniLibs/arm64-v8a/libmarmalade_term.so | grep -c ' T '
# 44 — the JNI surface plus the two bionic shims
```

## What is ported and what is ours

| File | Origin |
|---|---|
| `src/bridge/chuchu_snapshot.zig` | chuchu, verbatim but for its header and one mechanical rename: the JNI export prefix now names *our* Kotlin class (`Java_app_marmalade_android_terminal_GhosttyBridge_*`), because JNI binds by Java package + class name |
| `src/ndk.zig` | chuchu, one string changed |
| `src/bridge/chuchu_jni_internal.h`, `version-script.map` | chuchu, verbatim |
| `src/bridge/zignal_png.zig` | chuchu, verbatim bar its header and the dropped `comptime` block (upstream this file was chuchu's build root and force-analysed its SSH/mosh/backup bridges; ours is `root.zig`) |
| `build.zig` | derived from chuchu's, heavily stripped |
| `src/bridge/root.zig` | ours |

Every ported file carries a per-file header naming its origin and what
changed. `CREDITS.md` in the repo root is the index; `licenses/` holds the
texts. Keep those in step with any file you add here.

`chuchu_snapshot.zig` is deliberately kept verbatim (bar that prefix rename,
which reverses in one search-and-replace) so that re-syncing against upstream
chuchu stays a diff rather than an archaeology exercise. Its one local need was
met *around* it: our `root.zig` replaces a chuchu root that dragged in the
SSH/mosh/backup bridges. (Its header still describes `zignal_png.zig` as a
stub; that is stale as of the PNG restore, and left alone only because the file
is kept byte-stable.)

**Kitty graphics are complete.** The pipeline is vendored and live — image
storage, placements, the U+10EEEE placeholder, the snapshot buffer — and
`prepareImageData` decodes `rgb`, `gray` and `gray_alpha` inline while raw RGBA
passes straight through. `format = .png` payloads go through
`zignal_png.decodePng`, chuchu's zigimg wrapper, restored on 2026-07-27; PNG is
what most real senders use, so this was the format that mattered. zigimg is
**MIT with no transitive dependencies**, so it does not widen the licence
surface — it is pinned to chuchu's commit on the `zig-0.15` branch because
zigimg master requires Zig 0.16 and this build needs 0.15.2 exactly.

## The licensing invariant — read before touching `build.zig.zon`

Depend on Ghostty's **`ghostty-vt`** module, never its `ghostty` module.

`ghostty-vt` resolves to `src/lib_vt.zig` + generated unicode tables + uucode,
with oniguruma explicitly disabled and SIMD off in our build. All MIT.

Ghostty's full manifest *does* carry copyleft — libintl (LGPL), gtk4-layer-shell
(LGPL), glib/gobject (LGPL), plasma-wayland-protocols — but every one of those
is declared `.lazy = true` and belongs to the GTK desktop app, so none is ever
fetched on this path. Depending on the full module would pull them in and break
this project's no-copyleft rule. That is the whole reason the dependency list
here is two entries long — the other, zigimg, is MIT with no dependencies of
its own, so it adds one notice and no risk. Anything you add here gets the same
audit: read the artifact's own LICENSE and its manifest, not your memory of it.
