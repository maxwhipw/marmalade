# Marmalade Android

The native Android client for [Marmalade](../../README.md) — the
personal-assistant orchestrator whose daemon (`marmaladed`) lives in this
same repository.

It speaks the daemon's protocol v1 (JSON-RPC over WebSocket on
`/api/ws`). The wire truth is
[`packages/protocol/src`](../../packages/protocol/src) and the daemon's
[`router.ts`](../../packages/daemon/src/router.ts) — this client mirrors that
contract and never extends it unilaterally.

## What it does

Everything the webui does — sessions, workspaces, streaming chat with
tool-call and reasoning rendering, search, attachments, approvals, device
pairing by QR setup code, model/effort pickers, terminals — plus what only a
phone can:

- **Voice**: wake word (in-repo openWakeWord-architecture pipeline), on-device
  STT (sherpa-onnx Whisper, distil-small.en int8), TTS replies, barge-in, and
  a conversation mode that routes into the daemon's main session.
- **System assistant role** (voice-interaction session), home-screen widget,
  lock-screen notifications with quick reply.
- A native terminal renderer (libghostty-vt behind a JNI bridge).

## Building

Large binary assets (STT/wake-word ONNX models, sherpa-onnx AARs, the
terminal JNI libraries) are not stored in git. Fetch them first:

```bash
cd apps/android
# one of MARMALADE_ASSETS_BASE_URL (release assets) or MARMALADE_ASSETS_DIR
# (a local copy) must be set — see the script header.
MARMALADE_ASSETS_BASE_URL=... ./scripts/fetch-assets.sh   # verified against assets-manifest.json
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Requirements: JDK 17+, an Android SDK (point `local.properties` at it),
min SDK 31, target SDK 35.

## Layout

- `app/` — the Android application (Jetpack Compose, Material 3).
- `shared/` — Kotlin Multiplatform library (`androidTarget` + desktop JVM):
  protocol types, RPC client, shared UI vocabulary.
- `native/` — the terminal JNI bridge and prebuilt libghostty-vt libraries.
- `docs/decisions/` — ADRs for locked architectural decisions.
- `.claude/rules/` — subsystem invariants for agent-assisted development.

## Credits & license

Parts of the Android system-integration layer were adapted from
[yuga-hashimoto/openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant)
(MIT) — an excellent project that deserves the shoutout. See
[`NOTICE`](NOTICE) and [`CREDITS.md`](CREDITS.md) for the full attribution
inventory of exactly what was used, including the terminal's chuchu-derived
JNI bridge and every bundled third-party component.

New contributions are licensed Apache-2.0 with the rest of the repository;
MIT-derived portions retain their original attribution.
