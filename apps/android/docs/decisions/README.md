# Architecture Decision Records (ADRs)

This directory captures locked architectural decisions for marmalade-android.
Each ADR records one decision with its context, what was chosen, and the
consequences. ADRs are **immutable** — if a decision changes, write a new
ADR that supersedes the old one rather than editing.

The format below is the full pattern; the rationale for keeping ADRs on an
AI-assisted project is that they give a fresh session the locked decisions
without re-litigating them.

## Format

```
# NNNN. Short Title

Status: Accepted | Superseded by NNNN
Date: YYYY-MM-DD

## Context
## Decision
## Consequences
```

## Index

- [0001](0001-fork-openclaw-assistant.md) — Fork openclaw-assistant rather than rebuild
- [0002](0002-no-hilt-manual-singletons.md) — Manual singletons via NodeRuntime; no Hilt or DI framework
- [0003](0003-openwakeword-over-sherpa-kws.md) — openWakeWord over Sherpa-ONNX KWS for wake-word detection
- [0004](0004-local-android-tts-preferred.md) — Local Android TextToSpeech preferred over gateway `talk.speak` RPC
- [0005](0005-whisper-tiny-bundled-stt.md) — Whisper tiny int8 bundled as default STT engine
- [0006](0006-streaming-markdown-split.md) — Hand-rolled markdown parser for streaming text; library renderer for final
- [0007](0007-tink-and-bouncycastle-both-required.md) — Tink for signing, BouncyCastle for verification (both required)
- [0008](0008-mic-ownership-manager.md) — Microphone handoff via MicOwnershipManager, not broadcasts
- [0009](0009-mic-handoff-window-moved-into-startlistening.md) — Mic-handoff window lives inside startListening, not onShow (supersedes 0008's load-bearing-delays note)
- [0010](0010-in-repo-wake-word-pipeline.md) — In-repo wake-word pipeline replaces xyz.rementia:openwakeword (supersedes 0003)
- [0011](0011-kmp-shared-library-module.md) — KMP shared library module for desktop-client reuse
- [0012](0012-distil-small-bundled-stt.md) — Distil-Whisper distil-small.en bundled as default STT (supersedes 0005)
