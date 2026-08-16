# 0001. Fork openclaw-assistant rather than rebuild

Status: Accepted
Date: 2026-04-24 (recording an earlier decision)

## Context

When marmalade-android started, the choice was between (a) building an
Android client to the OpenClaw gateway from scratch, or (b) forking
yuga-hashimoto/openclaw-assistant (MIT) and improving on it.

The fork already had working code for:
- Dual-session WebSocket connection pattern (operator + node)
- Device pairing and Ed25519 identity
- VoiceInteractionService integration with the system assistant slot
- Invoke handler dispatch for node-side commands
- A baseline chat UI

The known weaknesses were: hypersensitive Vosk wake word, no streaming
chat UI, cramped/dull chat design, missing quality-of-life features.

## Decision

Fork yuga-hashimoto/openclaw-assistant (MIT). Rebrand the package from
`com.openclaw.assistant` to `app.marmalade.android`. Improve the existing
app rather than building a parallel one.

Treat the official OpenClaw Android app (`apps/android/` in the upstream
OpenClaw repo, also MIT) as a reference for protocol details and invoke
handler patterns when the fork's implementation needs strengthening.

## Consequences

- Saved months of foundational work (WebSocket framing, pairing, identity)
- Inherited the codebase's existing architectural choices (manual
  singletons, single-module Gradle), which became locked-in for MVP
- Carried over some legacy patterns that needed polishing rather than
  redesign
- Attribution policy required for any code borrowed from openclaw-latest
  (see `CREDITS.md`)
- AGPL/GPL projects (Signal, Telegram) usable as **design references
  only** — no code copying

## Rejected alternatives

- **Ground-up rebuild.** Better long-term architectural cleanliness, but
  cost was prohibitive for a solo dev release window. Lost months of
  protocol/connection plumbing for marginal architectural gain.
- **Fork the official OpenClaw Android app directly** rather than
  yuga-hashimoto's variant. The official app was less mature at fork
  time and didn't have VoiceInteractionService integration.
