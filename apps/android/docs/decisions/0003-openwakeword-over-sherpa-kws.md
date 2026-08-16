# 0003. openWakeWord over Sherpa-ONNX KWS for wake-word detection

Status: Accepted
Date: 2026-04-24 (recording the 2026-03-23 decision documented in phase 04.1)

## Context

The original wake-word implementation used Vosk's KWS mode and was
hypersensitive (excessive false positives, since it was effectively
running ASR continuously rather than a true wake-word model).

The first replacement attempt was Sherpa-ONNX `KeywordSpotter` with the
`sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` model. This
crashed at inference time with:

> `Reshape node '/downsample/Reshape_1' Input shape:{17,1,128},
> requested shape:{8,2,1,128}`

A native C++ bug in the chunk-16 encoder's downsample layer. Upgrading
the AAR from v1.12.28 to v1.12.32 did not resolve it (no relevant
changes in the 89 commits between, per phase 04.1 research). The bug is
not documented in sherpa-onnx GitHub issues.

## Decision

Replace Sherpa-ONNX KWS with **openWakeWord** via the Maven package
`xyz.rementia:openwakeword:0.1.5` (Apache-2.0).

- Bundle ONNX models in `app/src/main/assets/`:
  - `melspectrogram.onnx` (feature extraction)
  - `embedding_model.onnx` (Google's shared backbone)
  - per-keyword classifier `.onnx` (currently `hello_world.onnx` as
    placeholder — custom "Hey Marmalade" model is a TODO)
- Use Silero VAD (`silero_vad.onnx`) as a pre-filter
- Integration point: `HotwordService.kt` uses
  `com.rementia.openwakeword.lib.WakeWordEngine` (handles its own
  AudioRecord, melspec → embedding → classifier pipeline)
- Resolve the `libonnxruntime.so` packaging conflict between the new
  Maven package and the existing Sherpa-ONNX AAR via `pickFirsts` in
  `app/build.gradle.kts`

## Consequences

- APK grows ~15–20MB for ONNX Runtime; net savings vs Vosk's ~40MB Kaldi
  model
- Custom "Hey Marmalade" model requires Colab training (<1hr, synthetic
  TTS data, no recording session needed); currently using `hello_world`
  as placeholder
- openWakeWord pre-trained models are **CC-BY-NC-SA 4.0** — fine for
  personal use; would require self-trained models for commercial
  distribution
- Sherpa-ONNX AAR remains in `libs/` for STT (separate concern; see
  ADR 0005)
- Wake-word sensitivity exposed as Low/Medium/High slider mapped to
  thresholds (0.70 / 0.50 / 0.30)

## Rejected alternatives

- **Sherpa-ONNX KWS at v1.12.32.** Same Reshape crash. Tried.
- **Different Sherpa-ONNX KWS model files** (wenetspeech variant, the
  newer 2025-12-20 zh-en model). Did not pursue once openWakeWord was
  proven viable.
- **Picovoice Porcupine.** Strong product, but: requires AccessKey with
  phone-home activation, and licensing for custom Android wake words is
  unclear. Apache-2.0 SDK doesn't extend cleanly to custom-model use.
- **Vosk.** Was the original; rejected for hypersensitivity. No reason
  to revisit.
