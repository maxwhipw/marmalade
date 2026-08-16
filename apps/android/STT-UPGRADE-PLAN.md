# STT Upgrade Plan — Marmalade Android

Upgrade plan for Marmalade's speech-to-text pipeline based on real-world
testing on Pixel 8a (April 2026).

## Background & Resources

This plan draws on a body of internal STT/TTS research notes that are not
part of this repo: a catalogue of 30+ models with streaming capability,
mobile readiness, license, and ethics columns, plus a survey of every
approach to making Whisper stream. The conclusions relevant to the Android
client are reproduced below.

## Current State

**File:** `app/.../speech/SherpaStreamingRecognizer.kt`

- Model: Zipformer transducer (larger than the 20M demo — works reasonably)
- Runtime: Sherpa-ONNX `OnlineRecognizer` (true streaming)
- Audio: 16kHz, 100ms chunks, greedy search
- `.lowercase()` has been removed (was on lines 157/165)
- **Remaining issues:** no punctuation, limited vocabulary, no capitalization

---

## Pixel 8a Benchmark Results (2026-04-02)

Tested all Sherpa-ONNX demo APKs. Results ranked best to worst:

| Rank | Model | Mode | Verdict | File Names | Caps | Size |
|------|-------|------|---------|------------|------|------|
| 1 | **Whisper tiny** | simulated streaming | Amazing. Best contextual accuracy. Eager punctuation at chunk seams but fixable | Good | Sometimes | ~150MB |
| 2 | **Nemotron 0.6B int8** | true streaming | Very good, arguably better than Whisper tiny. Chugs slightly but usable | Untested | **Consistent** | ~500MB |
| 3 | **Parakeet-110m** | simulated streaming | Fast, decent streaming feel. Contextually worse than Whisper — doesn't handle file names well | Poor | Partial | ~126MB |
| 4 | **Zipformer (Marmalade)** | true streaming | Current baseline. Works but needs improvement | Poor | No | ~80MB |
| 5 | **Zipformer + Moonshine** | 2-pass | About equal to Whisper tiny but bigger file. No clear advantage over Whisper | Untested | Untested | Large |
| 6 | **Zipformer-20M + Whisper tiny** | 2-pass | Whisper refine is great but the tiny Zipformer first pass is useless. Marmalade's larger Zipformer would be much better | Good (Whisper) | Sometimes | ~230MB |
| 7 | **Parakeet-0.6b v3** | simulated streaming | Half a gig, worse than Nemotron. Hard no | Untested | Untested | ~500MB |

### Key Findings

- **Whisper wins on contextual understanding** — handles file names,
  guesses intent better than Parakeet. This matters for an AI assistant.
- **Nemotron is the quality king** but 500MB is a lot to ask users to download
- **The 2-pass demo used the wrong Zipformer** — the tiny 20M version is
  garbage, but Marmalade's larger Zipformer works well. 2-pass with
  Marmalade's Zipformer + Whisper small would be a great combo.
- **Whisper eager punctuation** is a chunk-boundary artifact, fixable in code:
  strip trailing punctuation from partials, only keep in final result
- **Capitalization is model-dependent:** Zipformer=none, Whisper=sometimes,
  Nemotron=consistent

---

## APKs for Testing

All APKs: arm64-v8a, fully offline, Sherpa-ONNX v1.12.33.
Full browse: https://huggingface.co/csukuangfj2/sherpa-onnx-apk/tree/main
Doc index: https://k2-fsa.github.io/sherpa/onnx/android/prebuilt-apk.html

### STT

**Whisper tiny simulated streaming** (top pick):
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/vad-asr-simulated-streaming/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-simulated_streaming_asr-en-whisper_tiny.apk
```

**Nemotron 0.6B int8 true streaming** (quality pick, 500MB):
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/asr/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-asr-en-nemotron-speech-streaming-en-0.6b-int8-2026-01-14.apk
```

**Parakeet-110m simulated streaming** (fast, decent):
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/vad-asr-simulated-streaming/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-simulated_streaming_asr-en-parakeet_tdt_ctc_110m.apk
```

**2-pass: Zipformer + Whisper tiny** (streaming + correction):
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/asr-2pass/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-asr_2pass-en-small_zipformer_whisper_tiny.apk
```

**2-pass: Zipformer + Moonshine base int8**:
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/asr-2pass/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-asr_2pass-en-small_zipformer_moonshine_base_int8.apk
```

**Zipformer-20M streaming** (baseline):
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/asr/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-asr-en-small_zipformer_20M_2023_02_17.apk
```

### TTS

**Kokoro EN v0.19:**
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-new/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-en-tts-kokoro-en-v0_19.apk
```

**Kokoro Multi-lang v1.1 int8:**
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-new/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-zh_en-tts-kokoro-int8-multi-lang-v1_1.apk
```

**Piper ryan (male US, high quality):**
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-new/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-en-tts-vits-piper-en_US-ryan-high.apk
```

**Kitten TTS nano v0.2:**
```
https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/1.12.33/sherpa-onnx-1.12.33-arm64-v8a-en-tts-engine-kitten-nano-en-v0_2-fp16.apk
```

**NekoSpeak** (multi-engine: Pocket TTS, Piper, KittenTTS, Kokoro):
```
https://github.com/siva-sub/NekoSpeak/releases/tag/v1.4.2
→ app-arm64-v8a-release.apk (88MB)
```

### APK Types

| Type | Behavior |
|------|----------|
| **asr/** | True streaming — text word-by-word as you speak |
| **vad-asr-simulated-streaming/** | VAD + batch model showing partial results in real-time |
| **asr-2pass/** | Streaming first pass + batch refinement on pause |
| **tts-new/** | Standalone TTS demo app |
| **tts-engine-new/** | Registers as Android system TTS engine |

---

## Revised Plan (Post-Testing)

### Option 1: Whisper Simulated Streaming (Recommended First Step)

**Simplest high-impact upgrade.** Replace the current streaming Zipformer
with Whisper tiny in simulated streaming mode. Single model swap, no 2-pass
complexity.

| Property | Value |
|----------|-------|
| Model | Whisper tiny (or distil-small.en if size permits) |
| License | MIT (not copyleft, commercial OK) |
| Ethics | unclear (Whisper) / **clear** (distil variants) |
| Size | ~150MB (tiny) / ~350MB (distil-small.en) |
| Caps | Sometimes (Whisper) — better than current none |
| File names | Good — Whisper trained on diverse web content |

**What changes in code:**
- Switch from `OnlineRecognizer` to `OfflineRecognizer` with VAD
- Implement simulated streaming (emit partial results from growing audio)
- Handle eager punctuation: strip trailing `.?!` from partial results
- Reference: Sherpa-ONNX simulated streaming APK source

**Open question:** Does a Whisper small simulated streaming APK exist?
If not, build one — Whisper small would be meaningfully better than tiny
and the maintainer would personally use it. Could also try distil-small.en (ethically
cleaner, similar quality).

### Option 2: 2-Pass with Marmalade's Zipformer + Whisper (Best UX)

**Keep the streaming feel, add Whisper accuracy.** The 2-pass demo failed
because it used the tiny 20M Zipformer. Marmalade's existing larger
Zipformer actually works well as a first pass. Pair it with Whisper
small or distil-small.en as the refinement pass.

| Component | Model | Size | License | Ethics |
|-----------|-------|------|---------|--------|
| Pass 1 (streaming) | Marmalade's current Zipformer | ~80MB | Apache 2.0 | clear |
| Pass 2 (refine) | Whisper small or distil-small.en | ~350MB | MIT | clear (distil) |

**What changes:**
- Keep existing `SherpaStreamingRecognizer` for first pass (already works)
- Add `OfflineRecognizer` for Whisper refinement pass
- Accumulate audio buffer during streaming
- On endpoint detection → run Whisper on buffer → emit refined text
- UI: show streaming text, then smoothly replace with refined version
- Fix eager punctuation in partial results

**This is the "streaming with correction" UX** — text flows as you speak,
then gets more accurate when you pause.

### Option 3: User-Selectable Models in Settings

**Ship multiple options, let users choose based on their hardware.**

| Setting | Model | Size | Quality | For |
|---------|-------|------|---------|-----|
| Lightweight (default) | Whisper tiny streaming | ~150MB | Good | Most users |
| Balanced | Whisper small streaming | ~350MB | Better | Power users |
| Maximum quality | Nemotron 0.6B int8 | ~500MB | Best | The maintainer, enthusiasts |

Models downloaded on demand from settings. Default ships with Whisper tiny.
Users with newer phones (Tensor G4+, Snapdragon 8 Gen 3+) can opt into
heavier models.

NPU optimization could make Nemotron much more viable — worth investigating
Pixel 8a's Tensor G3 NPU support via ONNX Runtime or TFLite.

### Option 4: Context-Aware STT (Long-Term Vision)

**The WisprFlow approach.** After base STT is solid, add LLM post-processing
for code-speak. Route transcript through OpenClaw gateway:

```
STT output → "Fix code terms, file names, proper nouns.
              Context: AI assistant chat. Recent terms: [symbols]"
           → cleaned transcript
```

Open-source reference: **FreeFlow** (MIT, 1.1k stars) does exactly this.
https://github.com/zachlatta/freeflow

---

## Implementation Priority

```
Immediate (done):
    ✅ Remove .lowercase() from SherpaStreamingRecognizer.kt

Phase 1: Whisper simulated streaming (Option 1)
    Replace Zipformer with Whisper tiny in simulated streaming mode
    Test distil-small.en if size permits on Pixel 8a
    Handle eager punctuation in partial results

Phase 2: 2-pass upgrade (Option 2)
    Keep Marmalade's Zipformer streaming + add Whisper refine
    This gives the best UX: streaming + accuracy + file names

Phase 3: Model selection in settings (Option 3)
    Add model download/management in settings
    Default: Whisper tiny, optional: Whisper small, Nemotron 0.6B

Phase 4: Context-aware cleanup (Option 4)
    LLM post-processing via OpenClaw gateway
    Reference FreeFlow architecture
```

## Watch List

- **Whisper small/distil simulated streaming APK** — needs building or
  may appear in future Sherpa-ONNX releases. Would be the sweet spot.
- **NeMo canary-180m-flash ONNX** — ideal batch refinement pass
- **CarelessWhisper** — true causal streaming Whisper via LoRA
- **NPU support** — ONNX Runtime / TFLite on Tensor G3 NPU could make
  larger models viable on Pixel 8a

Tracked in internal research notes (not in this repo).

## License Summary

| Component | License | Copyleft? | Commercial OK? |
|-----------|---------|-----------|----------------|
| Sherpa-ONNX runtime | Apache 2.0 | No | Yes |
| Zipformer (current) | Apache 2.0 | No | Yes |
| Whisper tiny/small | MIT | No | Yes |
| distil-whisper-small.en | MIT | No | Yes |
| Nemotron 0.6B | NVIDIA Open | No | Yes (review terms) |
| Silero VAD | MIT | No | Yes |

**All clear — no copyleft, all commercially usable.**
