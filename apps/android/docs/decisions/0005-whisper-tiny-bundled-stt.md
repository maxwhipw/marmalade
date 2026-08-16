# 0005. Whisper tiny int8 bundled as default STT engine

Status: Accepted
Date: 2026-04-24 (recording phase 08 decision)

## Context

The original STT was Sherpa-ONNX Zipformer streaming (~80MB). On-device
testing (Pixel 8a, 2026-04-02 — benchmark notes kept internally, not in
this repo) ranked seven candidate models. Whisper tiny ranked #1 for
contextual understanding —
critical for an AI assistant that fields commands like "open YouTube"
and file/app names that benefit from language-model context.

Tradeoffs:
- Whisper does not stream natively (chunks of audio in, transcript out)
- Punctuation is "eager" at chunk seams — a code-level fix, not a model
  problem
- Whisper tiny int8 is ~117MB vs Zipformer's ~80MB

## Decision

Bundle **Whisper tiny.en int8** as the default STT engine. Implement
**simulated streaming** in `WhisperRecognizer.kt`:
- Silero VAD detects speech boundaries
- Re-transcribe the entire growing buffer each cycle (not per-segment)
- Strip trailing punctuation from partial results
- Emit a final transcript when VAD reports silence past threshold

Replace the dual-engine pattern (`SpeechRecognizerManager` for inline,
`SherpaStreamingRecognizer` for voice popup) with a single
`WhisperRecognizer` for both entry points. Use an `enableEndpoint` flag
to distinguish patient-listening mode (false — accumulate until explicit
stop) from normal mode (true — finalize on VAD silence).

Offer Nemotron 0.6B int8 (~500MB) and Whisper small as **downloadable
upgrades** for users who want more accuracy and have the storage budget.
`STTModelManager` handles inventory, download, and model selection.

## Consequences

- APK net change: +37MB (Zipformer 80MB removed, Whisper tiny 117MB
  added)
- Better accuracy on file names, app names, and contextual phrases —
  matters for command-mode use
- Removed dual-engine complexity; one code path for STT
- VAD sensitivity exposed as a slider (0.3s–1.5s silence window before
  segmenting); default 0.9s
- Mic ownership conflicts with openWakeWord must be handled explicitly
  via `ACTION_PAUSE_HOTWORD` / `ACTION_RESUME_HOTWORD` broadcasts
- OfflineRecognizer model load is 2–3 seconds — must `warmup()` on
  Dispatchers.IO at startup, not on first STT invocation
- Whisper int8 is significantly smaller than float32 (221MB) with
  acceptable accuracy loss; bundle int8 only

## Rejected alternatives

- **Stay on Zipformer.** Faster streaming feel, but contextually shallow
  on file names and proper nouns. Lower-quality STT for the use case.
- **Nemotron 0.6B as default.** Highest quality (true streaming, proper
  capitalization), but 500MB bundle is too large for default install.
  Offered as opt-in download.
- **Parakeet-110m.** Fast streaming but contextually worse than Whisper.
- **Two-pass (Zipformer first pass + Whisper refine).** Complexity not
  justified; the single Whisper pass is good enough.
