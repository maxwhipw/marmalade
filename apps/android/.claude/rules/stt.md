---
paths:
  - "app/src/main/java/app/marmalade/android/speech/WhisperRecognizer*.kt"
  - "app/src/main/java/app/marmalade/android/speech/AudioPreprocessor*.kt"
  - "app/src/main/java/app/marmalade/android/speech/STTEngineProvider*.kt"
  - "app/src/main/java/app/marmalade/android/speech/STTModelManager*.kt"
  - "app/src/main/java/app/marmalade/android/ui/settings/SpeechRecognitionScreen.kt"
---

# STT rules

Subsystem-specific gotchas for the Whisper-based speech recognizer.
Background: ADR 0012 (distil-small.en bundled, supersedes 0005). The
Nemotron streaming transducer was removed 2026-07-04 (poor real-world
accuracy) — Whisper is the only backend; `ModelType` has a single variant.

## Where the code lives (since KMP inc 3g, 2026-07-24)

- The capture/segmentation loop is `WhisperStreamingEngine` in
  `shared/src/jvmSharedMain` — it consumes the mic seam (`openMicCapture`)
  and the `OfflineTranscriber`/`SpeechVad` facades (commonMain). It never
  touches sherpa directly: the Android AAR and the desktop release jars
  expose different APIs under the same class names
  (proven by a desktop spike; notes kept internally).
- `WhisperRecognizer` (:app) is the shell: sherpa model/VAD lifecycle,
  model selection, facade adapters. Loop changes go in the engine +
  `WhisperEngineTest` digital twins, not the shell.

## Recognizer lifecycle

- `OfflineRecognizer` model load is **2–3 seconds**. Always
  `warmup()` on `Dispatchers.IO` at app startup, not on first STT
  invocation. Otherwise the user perceives a 2–3s freeze the first time
  they invoke voice.
- `WhisperRecognizer` is a singleton; both inline chat-bar STT and
  voice popup STT share the same instance. Don't construct a second
  recognizer per entry point.
- The recognizer config is derived from `STTModelManager.getActiveModel()`
  at load time: bundled models load via `assetManager`, downloaded models
  via absolute `filesDir` paths (no assetManager → sherpa-onnx
  `newFromFile`). The cached recognizer reloads when the active model id
  changes between sessions. Do NOT hardcode asset paths in the config —
  that was the bug that made an activated Whisper Small silently keep
  running Tiny (fixed 2026-07-04).

## Mic-input preprocessing (order matters)

1. `AudioRecord(source = VOICE_RECOGNITION)` — CDD-mandated flat capture,
   no telephony DSP. Do not switch back to `MIC`.
2. Hardware `NoiseSuppressor` + `AutomaticGainControl` attached to the
   record session when available (`isAvailable()` guard; logged per
   stream as "Mic effects: …"; released with the AudioRecord).
3. `AudioPreprocessor` (software 100Hz Butterworth high-pass) runs in the
   producer loop, so **both** Silero VAD and Whisper see the cleaned
   signal. It is stateful across chunks — one instance per stream, never
   shared between concurrent streams.
- `AudioPreprocessor` is pure Kotlin and unit-tested offline with
  synthesized PCM (`AudioPreprocessorTest` — the digital twin). Hardware
  effects can't run in JVM tests; real-world accuracy is verified
  on-device by the maintainer's daily use.

## Simulated streaming

- VAD segment boundaries are **not** transcription boundaries. Accumulate
  the entire audio buffer across segments and re-transcribe the whole
  accumulated buffer each cycle. Per-segment transcription drops context
  and produces fragmented output.
- Strip trailing punctuation from partial results — Whisper's eager
  punctuation at chunk seams is a known artifact, fix it at the consumer
  side.
- `enableEndpoint` flag distinguishes:
  - `true` (default) — finalize on VAD silence past threshold
  - `false` (patient listening) — accumulate until explicit stop;
    used for the "wake-word triggered, wait for full command" mode

## VAD sensitivity

- Linear slider: 0.0→0.3s, 0.5→0.9s, 1.0→1.5s minimum silence before
  treating a pause as a segment boundary.
- Default 0.5 (0.9s) — balances responsiveness against fragmenting
  thoughtful pauses mid-sentence.

## Model files

- Bundled assets in `app/src/main/assets/stt/` (ADR 0012):
  - `distil-small.en-encoder.int8.onnx`
  - `distil-small.en-decoder.int8.onnx`
  - `distil-small.en-tokens.txt`
- Bundle **int8** quantization, not float32 (~298MB int8; the bundled
  default, `DEFAULT_MODEL_ID = "distil_small_en"`).
- No downloadable tier ships (distil-medium is server-side only — too big
  for mobile). The download machinery (`STTModelManager` inventory +
  `ModelDownloadService`) is retained for future tiers; orphaned
  `whisper_small` / `nemotron` downloads are reclaimed on init.

## Mic ownership

- `AudioRecord` is single-owner. Mic handoff between wake word and STT
  goes through `MicOwnershipManager` (ADR 0008) — token-based
  `requestMic`/`releaseMic`, not the retired PAUSE/RESUME_HOTWORD
  broadcasts. See `.claude/rules/voice.md`.
