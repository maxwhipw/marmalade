# System Contract

Hard rules. Every decision must comply. No exceptions.

## Identity

- **App name:** Marmalade
- **Package:** `app.marmalade.android`
- **Min SDK:** 31 (Android 12)
- **Target devices:** Pixel 8a (primary), Pixel 6a (secondary, Android 14 / API 34)
- **Language:** Kotlin, Jetpack Compose UI

## Voice

- All speech processing (wake word, STT, TTS) uses **Sherpa-ONNX** on-device
- Wake phrase: "Hey Marmalade"
- Full-duplex conversation via **Pipecat Android SDK** with a custom `OpenClawWebSocketTransport`
- Android `VoiceInteractionService` for system-level assistant integration

## Command Interpretation

- All voice command interpretation is performed by the **LLM via the gateway**
- The app never parses or regex-matches user speech locally
- The app sends transcribed text to the gateway; the LLM decides the action

## Tiered Action System

| Tier | Type | Behavior |
|------|------|----------|
| 1 | Safe / informational | Execute immediately (answer questions, read notifications) |
| 2 | App control / allowlisted | Execute with brief toast ("Opening YouTube...") |
| 3 | Outbound / sensitive | Require explicit user confirmation before executing |
| 4 | Blocked | Never execute (uninstall apps, factory reset, send money) |

## Gateway Architecture

- The app connects to **OpenClaw gateways** as a node client
- Multi-gateway support: the user can configure and connect to multiple gateways simultaneously
- The embedded gateway (via openclaw-termux's proot approach) is one entry in the gateway list
- External gateways connect via manual URL, mDNS discovery, QR code pairing, or deep link

## Licensing

- No copyleft dependencies (GPL, LGPL, AGPL)
- All dependencies must be permissively licensed (Apache 2.0, MIT, BSD)

## Context Tracking

- Enable token usage tracking via `sessions.patch` with `responseUsage: "tokens"` after connecting to each gateway
