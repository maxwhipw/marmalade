# 0004. Local Android TextToSpeech preferred over gateway `talk.speak` RPC

Status: Accepted
Date: 2026-04-22

## Context

OpenClaw's gateway exposes a `talk.speak` RPC that synthesizes audio
through whatever TTS provider is configured server-side (ElevenLabs,
OpenAI, Google Gemini, MiniMax, Microsoft via `node-edge-tts`). The
upstream Android client (`apps/android/` in the OpenClaw repo)
calls `talk.speak` with a `FallbackToLocal` sum-type result, falling
through to `android.speech.tts.TextToSpeech` only if the gateway returns
`talk_unconfigured`, `talk_provider_unsupported`, or `method_unavailable`.

For marmalade-android, the question was whether to mirror that pattern
(prefer gateway TTS, fall back to local) or invert it (prefer local,
treat gateway TTS as opt-in).

## Decision

**Use Android's built-in `android.speech.tts.TextToSpeech` for all
assistant audio output.** Do not call `talk.speak`.

Defer gateway-mediated TTS until the local path is polished and a
specific need arises (e.g., users who actually want ElevenLabs voices).
If/when added later, it should be opt-in via a settings toggle, not the
default.

## Consequences

- **Zero latency** — no round-trip; audio starts the moment the reply
  is rendered
- **Zero bandwidth cost** — important on mobile data
- **Works fully offline** for assistant replies (assuming the reply
  itself didn't require gateway round-trip, which it does, but the
  audio layer is independent)
- **No API key management** for TTS providers; gateway operator doesn't
  need to configure anything
- **Voice quality is "good enough", not "great"** — Android's bundled
  voices vary by device. Pixel devices have decent neural voices;
  cheaper devices have lower-quality fallbacks. Acceptable for v1.
- Aligns with the design principle that **users still want text
  transcripts visible alongside audio** — the TTS layer is purely a
  rendering of text already shown, so high fidelity isn't critical
- Future opt-in `talk.speak` path can mirror the upstream Android
  reference's `TalkSpeakClient.kt` (MIT) with a clean `FallbackToLocal`
  sum-type — reusable when needed

## Rejected alternatives

- **Mirror upstream's `talk.speak`-first pattern.** Adds round-trip
  latency, bandwidth cost, and provider configuration for marginal
  voice-quality improvement that most users won't care about for
  assistant replies. The upstream pattern is sound; just not
  appropriate as the default for this client.
- **Direct ElevenLabs API integration from the device** (the macOS/iOS
  upstream pattern). Requires API key in client; bypasses gateway's
  provider-routing logic; not the right architectural shape.
