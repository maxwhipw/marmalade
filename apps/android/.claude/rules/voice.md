---
paths:
  - "app/src/main/java/app/marmalade/android/voice/**/*.kt"
  - "app/src/main/java/app/marmalade/android/service/Hotword*.kt"
  - "app/src/main/java/app/marmalade/android/service/OpenClawSession*.kt"
  - "app/src/main/java/app/marmalade/android/node/AndroidActionHandler.kt"
  - "shared/src/jvmSharedMain/kotlin/app/marmalade/android/rpc/MarmaladeInvokeDispatcher.kt"
---

# Voice subsystem rules

Subsystem-specific gotchas for wake-word, voice popup, intent dispatch,
and assistant-session integration. Background: ADR 0010 (in-repo
wake-word pipeline, supersedes ADR 0003), ADR 0005 (Whisper STT), ADR
0008 (mic ownership).

## Wake-word (in-repo pipeline, `speech/wake/`)

- `HotwordService` owns its own `AudioRecord` via `WakeWordPipeline`
  (`app.marmalade.android.speech.wake` — replaced the closed-source
  `xyz.rementia:openwakeword` AAR per ADR 0010; **do not** reintroduce
  that dependency). `AudioRecord` is single-owner — **do not** share it
  across services. Mic handoff between the wake word and STT goes
  through `MicOwnershipManager` — a single-owner token (`NONE` / `KWS` /
  `VOICE_SESSION` / `INLINE_STT`) with `requestMic` / `releaseMic` and a
  90 s safety-net timeout that force-reclaims a stuck mic for KWS. STT
  consumers (`OpenClawSession`, `InlineSTTState`) call `requestMic`
  before recording; `HotwordService` collects `currentOwner` and stops
  its pipeline whenever ownership leaves `KWS`, restarting it via the
  `setOnMicReleasedToKws` callback. The old `ACTION_PAUSE_HOTWORD` /
  `ACTION_RESUME_HOTWORD` broadcast handoff was retired — see ADR 0008.
- Classifier scores are sigmoid probabilities in [0,1]; a hop counts
  toward confirmation when `score > threshold`, so a lower threshold is
  more eager. A detection only fires once `ConfirmationTracker` sees 2 of
  the last 3 hops cross threshold (see below) — not on a single frame.
- Sensitivity slider IS the raw threshold. Range
  `MIN_WAKE_WORD_THRESHOLD`..`MAX_WAKE_WORD_THRESHOLD` (0.3–0.9),
  legacy string presets map low→0.7 / medium→0.5 / high→0.3. The
  earlier 0.03–0.20 mapping was ~6x too eager and spammed in crowds;
  `getWakeWordThreshold()` migrates any pre-remap float (< 0.3) to the
  0.5 default. Threshold is read from `SettingsRepository` at pipeline
  init, not per-frame, to avoid SharedPreferences hits in the hot path.
- Two behavioral fixes over the retired AAR (ADR 0010), both in
  `speech/wake/`:
  - **VAD gate** (`VadGate`, fed by `SileroVad`): the expensive mel→
    embedding→classifier chain (`OpenWakeWordChain`) only runs while
    Silero VAD reports speech probability > 0.5, with a 1.5s hangover
    after speech ends. Silence costs one small VAD inference per 80ms
    hop, not three full model passes. Gated/executed hop counters log at
    DEBUG every ~60s — check logcat to confirm the gate is doing its job
    on-device.
  - **Multi-frame confirmation** (`ConfirmationTracker`): 2 of the last
    3 hops must cross threshold before a detection fires. Cooldown
    (`HotwordService.COOLDOWN_MS`) and SINGLE_BEST (highest-scoring
    model wins a same-hop tie) are preserved from the old AAR's
    behavior; there is no other detection mode to select.
- Tensor shapes for all five bundled `.onnx` files are verified (not
  assumed) and documented in `MelWindowBuffer`'s doc comment and ADR
  0010 — re-verify with a throwaway onnxruntime JVM check before
  changing any window-size constant in `speech/wake/`.
- HotwordService auto-start eligibility predicate is in
  `HotwordService.shouldRunHotwordDetection(enabled, mode, hasMicPermission)`:
  `hotwordEnabled == true && voiceMode != VoiceWakeMode.Off && hasMicPermission`.
  Both `onTaskRemoved` (no doze-piercing restart alarm) and `onStartCommand`
  (no mic allocation) gate on it. Falsifying any precondition → service
  stops, no resource held.
- ONNX models in `app/src/main/assets/`:
  - `melspectrogram.onnx` (required, shared)
  - `embedding_model.onnx` (required, shared)
  - per-keyword classifier `.onnx` — currently `marmalade.onnx` and
    `openclaw.onnx`, both custom-trained on license-clean data. Loaded
    based on the user's preset selection via
    `SettingsRepository.getWakeWordAssetFilename()`.
  - `silero_vad.onnx` — now actually wired into the wake-word path (via
    `SileroVad` in `speech/wake/`), not just STT. It is the older
    stateful dual-tensor (`h`/`c`) Silero variant; see ADR 0010.
- The shipped `.onnx` classifiers are **self-trained on permissive data
  (LibriSpeech CC-BY 4.0 + Kokoro/Kitten/Piper TTS) — safe to
  redistribute commercially**. Upstream pre-trained openWakeWord models
  (CC-BY-NC-SA) are NOT shipped. The training recipe is published at
  https://github.com/maxwhipw/marmalade-openwakeword (license-clean
  pipeline: hard-negative phrases + MUSAN non-speech negatives). The
  pipeline architecture that *runs* these models is a from-scratch
  adaptation of the published openWakeWord parameters (Apache-2.0) — see
  CREDITS.md.

## Voice popup overlay

- `VoiceInteractionSession` overlay window does **not** support
  `ModalBottomSheet`. Use Compose primitives (`Box`, `Surface`,
  `Column`, `RoundedCornerShape`) — the system overlay context doesn't
  carry the sheet's lifecycle/state requirements.
- Mic button pulse animation: `rememberInfiniteTransition` +
  `animateFloat` (1.0→1.3 scale). Keep subtle; overlay context is
  performance-sensitive.
- Chat-bar tap vs long-press: **single `detectTapGestures` block** with
  both `onTap` and `onLongPress`. Don't combine `clickable` +
  `combinedClickable` — produces dual-trigger bugs.
- Mini chat `LazyColumn` clears on popup dismiss (UI only); messages
  persist in the gateway session and re-appear on next open if same
  session.

## Intent dispatch (`marmalade_action`)

- Two active dispatch paths:
  1. `ActionDispatcher` — parses `marmalade_action` JSON from
     assistant text response
  2. `InvokeDispatcher` RPC — `android_action` invoke command from
     gateway
  Both support the same action types for flexibility.
- `device.call` uses `ACTION_DIAL` (pre-fills dialer, user presses
  call) — **not** `ACTION_CALL` (which would require `CALL_PHONE`
  permission and bypasses user confirmation).
- `device.sms` uses `ACTION_SENDTO` to open messaging app with body
  pre-filled. User sends manually — that **is** the confirmation
  pattern.
- Generic `intent.generic` action: `intentAction` is mandatory;
  `intentData`, `intentExtras`, `intentCategory` are nullable.
- `PACKAGE_ADDED` / `PACKAGE_REMOVED` receivers must be registered
  **programmatically** (`context.registerReceiver`). Implicit broadcast
  restrictions on Android 8+ silently ignore manifest declarations for
  these.
- The receiver `IntentFilter` for `PACKAGE_ADDED` / `PACKAGE_REMOVED`
  must include `addDataScheme("package")` or it never fires.
- `SET_ALARM` permission required in manifest for `device.timer` and
  `device.alarm` (normal permission, auto-granted; just don't forget it).
