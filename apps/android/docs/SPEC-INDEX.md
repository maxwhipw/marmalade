# Spec Index

Canonical links to the specification files in this repo. Each spec covers one
domain. Read the spec before building its corresponding feature.

An earlier generation of specs (project foundation, gateway/node, UI-UX,
deliverables, reference map, and the original OpenClaw WebSocket protocol
notes) is kept internally and is not part of this repo; where those documents
still mattered, their conclusions have been folded into the specs below, the
architecture decision records, and `SYSTEM-CONTRACT.md`.

| # | Spec | Domain |
|---|------|--------|
| 1 | [VOICE-PIPELINE.md](spec/VOICE-PIPELINE.md) | Wake word, STT, TTS, conversation mode, Pipecat transport |
| 2 | [SESSIONS-AND-CHAT.md](spec/SESSIONS-AND-CHAT.md) | Sessions, chat UI, message rendering, context strategy |
| 3 | [ASSISTANT-SERVICE.md](spec/ASSISTANT-SERVICE.md) | VoiceInteractionService, intent routing, tiered actions, foreground service |

## Supporting Documents

| Document | Purpose |
|----------|---------|
| [SYSTEM-CONTRACT.md](SYSTEM-CONTRACT.md) | Hard rules and non-negotiables |
| [MCP-INTEGRATION-SPEC.md](MCP-INTEGRATION-SPEC.md) | MCP tool exposure and integration |
| [decisions/](decisions/) | Architecture decision records |

## API References

| Reference | Contents |
|-----------|----------|
| [sherpa-onnx-api.md](references/sherpa-onnx-api.md) | Sherpa-ONNX Android API signatures (KeywordSpotter, Recognizer, TTS) |
| [pipecat-android-api.md](references/pipecat-android-api.md) | Pipecat Transport interface, RTVI protocol, event callbacks |
| [gateway-events.md](references/gateway-events.md) | Gateway event and RPC surface as consumed by the client |
