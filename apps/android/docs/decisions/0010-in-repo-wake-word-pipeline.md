# 0010. In-repo wake-word pipeline replaces xyz.rementia:openwakeword

Status: Accepted
Date: 2026-07-01

Supersedes [0003-openwakeword-over-sherpa-kws.md](0003-openwakeword-over-sherpa-kws.md).
0003's core call — openWakeWord's mel/embedding/classifier architecture
over Sherpa-ONNX KWS — still stands and is not revisited here. What
changes is *who owns the inference pipeline*: an in-repo implementation
now replaces the Maven package that pipeline previously came from.

## Context

`xyz.rementia:openwakeword:0.1.5` is a closed-source AAR with unknown
license. A dedicated audit (kept internally; decompiled via `javap` —
no source was ever available) found two
verified defects, both structural to that AAR and not fixable by
same-file tuning (its `WakeWordEngine` constructor owns its `AudioRecord`
and pipeline entirely internally, with no hooks for VAD injection, audio-
source override, or frame-skipping):

- **Battery — root cause.** `MelSpectrogram.computeMelSpectrogram()` and
  `EmbeddingModel.generateEmbeddings()` re-open the `.onnx` asset and
  call `OrtEnvironment.createSession(bytes)` — a full model load/optimize
  — on **every single 80ms audio chunk** (~12.5x/sec continuously while
  hotword detection is active), then close it. The classifier stage
  caches its session correctly; the mel-spectrogram and embedding stages
  do not. Compounding this: there is no VAD gate at all, so silence pays
  the same full inference cost as speech.
- **Sensitivity — root cause.** `WakeWordEngine$start$2$1$detectionResults$1$1`
  fires on a **single frame** crossing `score > threshold`
  (bytecode-confirmed) — no multi-frame confirmation, no smoothing, no
  verifier stage. A lone loud consonant or a TV ad jingle can trip it.

0003 additionally claimed "Use Silero VAD as a pre-filter" in its
Decision section. This was never implemented — the AAR has zero
VAD-related classes, and `silero_vad.onnx` in assets was used only by STT
(`WhisperRecognizer.kt`), never by `HotwordService`. That gap is closed
by this ADR, not by 0003 retroactively.

License risk compounds the defects: with no source available, the AAR
cannot be patched, forked, or audited beyond bytecode. Continuing to
ship it as a public-release dependency is both a battery/UX liability
and an unresolved license question.

## Decision

Replace `xyz.rementia:openwakeword` with an in-repo pipeline,
`app/src/main/java/app/marmalade/android/speech/wake/`, implemented from
the **published openWakeWord architecture**
([dscripka/openWakeWord](https://github.com/dscripka/openWakeWord),
Apache-2.0) — not from the AAR. No code, decompiled or paraphrased, was
copied from the AAR; see CREDITS.md for the architecture-adaptation
credit.

- **Same bundled ONNX models**, unchanged: `melspectrogram.onnx`,
  `embedding_model.onnx`, `silero_vad.onnx`, and the per-keyword
  classifiers `marmalade.onnx` / `openclaw.onnx` (self-trained, CC-BY/
  Apache/MIT training data only — see CREDITS.md; these were already
  license-clean and are untouched by this change).
- **Tensor shapes verified, not assumed**, against the actual bundled
  `.onnx` files with a throwaway onnxruntime-1.18.0 JVM check
  (`OrtSession` input/output introspection) before any window size was
  hardcoded:
  - `melspectrogram.onnx`: `[batch, samples]` → `[time, 1, T, 32]`
  - `embedding_model.onnx`: `[batch, 76, 32, 1]` → `[batch, 1, 1, 96]`
  - `marmalade.onnx` / `openclaw.onnx`: `[1, 16, 96]` → `[1, 1]`
  - `silero_vad.onnx`: `x=[1,512]`, `h=[2,1,64]`, `c=[2,1,64]` →
    `prob=[1,1]`, `new_h`, `new_c` — the older stateful dual-tensor
    Silero variant, confirmed by inspecting the model's actual input
    names rather than assumed from current upstream silero-vad (which
    now uses a combined `state` tensor).
  - These confirm the openWakeWord reference implementation's published
    parameters exactly: 1280-sample (80ms) hops, 8 new mel frames per
    hop, a 76-frame sliding embedding window, mel scaling `x/10 + 2`, and
    a 16-embedding classifier window.
- **VAD gate now actually implemented** (closing the 0003 documentation
  drift): Silero VAD runs every 80ms hop (tiny model, cheap); the
  mel→embedding→classifier chain only runs while VAD reports speech
  probability > 0.5, with a 1.5s hangover after speech ends so the chain
  doesn't drop mid-utterance on a single noisy VAD dip. All three
  `OrtSession`s (mel, embedding, classifier) are created once at
  `start()` and closed at `stop()`/`release()` — this is the direct fix
  for the AAR's per-chunk session-recreation battery bug.
- **Multi-frame confirmation** replaces single-frame firing: a model
  must cross its threshold on 2 of the last 3 hops before it counts as a
  detection. Existing cooldown (`HotwordService.COOLDOWN_MS`) and
  SINGLE_BEST behavior (highest-scoring model wins a same-hop tie) are
  preserved.
- **Audio source changed from `MIC` to `VOICE_RECOGNITION`** — the
  AAR used `MIC`; `VOICE_RECOGNITION` gets the hardware AGC/noise-
  suppression path for free, helping false-accept rejection.
- **Drop-in API**: `WakeWordPipeline(context, models, cooldownMs, scope)`
  exposing `detections: Flow<WakeDetection>` / `start()` / `stop()` /
  `release()` — the same shape as the AAR's `WakeWordEngine`, so
  `HotwordService`'s integration diff is a construction-site swap. The
  eligibility predicate, cooldown defense-in-depth, and
  `MicOwnershipManager` contract (ADR 0008/0009) are untouched.
- **Pure logic is unit tested**: multi-frame confirmation, VAD gating +
  hangover, and mel/embedding/classifier windowing math are all plain
  Kotlin classes with no Android or ONNX dependency, tested with
  synthetic scores/probabilities/tensors. The ONNX chain orchestration
  (`OpenWakeWordChain`) constructor-injects its three inference
  functions so window-boundary and warm-up behavior is testable without
  a real `OrtSession`; the real session wiring is thin, untested glue.

## Consequences

- `com.microsoft.onnxruntime:onnxruntime-android` becomes a direct,
  pinned (1.18.0) dependency instead of an incidental transitive one
  from the AAR. The `libonnxruntime.so` packaging conflict with
  sherpa-onnx's bundled copy (`app/build.gradle.kts` `pickFirsts`) is
  unchanged in shape, just in which side supplies the Maven artifact.
- No new license question: onnxruntime is MIT, silero-vad is MIT, and
  the openWakeWord *architecture* (parameters, published in Apache-2.0
  code) is used without copying Apache-2.0 code verbatim — this repo's
  implementation is original.
- Battery: silence now costs one small Silero VAD inference per 80ms hop
  instead of three full model passes (mel + embedding + classifier).
  Verifiable on-device via a gated/executed hop counter logged at DEBUG
  every ~60s.
- Sensitivity: a single loud consonant or transient noise burst can no
  longer trigger a detection; 2 of the last 3 hops must cross threshold.
- `.claude/rules/voice.md`'s wake-word section, which described the
  rementia AAR's threshold semantics and detection-mode API, needed a
  pass to point at this ADR and the new package instead — done in the
  same commit series as this ADR.
- On-device verification (wake phrase trigger for both models, gated/
  executed counter behavior during silence, false-accept spot check with
  podcast/music playback, mic-handoff into STT still working) is
  **pending** as of this ADR's acceptance — no device was connected
  during implementation. Tracked for the next session with the Pixel 8a
  attached.

## Rejected alternatives

- **Patch or fork the AAR.** Not possible without source; decompiled
  bytecode is the only artifact available, and copying from it (even
  paraphrased) was explicitly ruled out given the unknown license.
- **Tune thresholds/cooldown in `HotwordService` without touching the
  AAR.** Rejected by the audit itself: the AAR's `WakeWordEngine`
  constructor owns `AudioRecord` and the full pipeline internally with
  no injection points for VAD, audio source, or frame-skipping — there
  is no safe same-file fix for either root cause.
- **Android SoundTrigger / DSP wake-word path.** Considered and set
  aside: third-party app access to low-power DSP wake-word detection is
  constrained on Tensor/Pixel devices; not pursued for this pass.
