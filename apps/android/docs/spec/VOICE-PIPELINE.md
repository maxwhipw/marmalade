# Voice Pipeline

## Wake Word
- Sherpa-ONNX `KeywordSpotter` running in a foreground service
- Keyword: "Hey Marmalade" (configure via `keywords.txt`)
- 16kHz mono audio captured via `AudioRecord` on a dedicated thread
- On detection: activate conversation mode or process a single command

### Wake Word Service Flow
1. Service starts → acquire partial wake lock → create `AudioRecord` (16kHz, mono, PCM_16BIT)
2. Continuous loop: read audio chunk → feed to `KeywordSpotter.acceptWaveform()` → check `KeywordSpotter.isReady()`
3. On keyword detected: emit event via shared Flow → conversation mode activates
4. Service persists across app backgrounding via foreground notification

## Voice Activity Detection (VAD)
- Sherpa-ONNX Silero VAD runs on the same audio stream as STT
- Detects speech onset and offset to drive `onUserStartedSpeaking` / `onUserStoppedSpeaking` events
- Feeds completed speech segments to STT for recognition
- Config: threshold 0.5, minSilenceDuration 0.25s, windowSize 512 samples
- Model: `silero_vad.onnx` (~2MB, bundled in assets)

## Speech-to-Text
- Sherpa-ONNX `OnlineRecognizer` for streaming recognition during conversation mode
- Feed audio frames in real-time as they're captured
- Collect partial results (`getResult()`) for live transcript display
- Collect final result on endpoint detection (silence) for sending to gateway

### STT Integration with Pipecat
- During conversation mode, STT runs locally and produces text
- Text is sent to the gateway via `PipecatClient.sendText()` or equivalent transport message
- The transport wraps this as an RTVI `send-text` message over the OpenClaw WebSocket

## Text-to-Speech
- Sherpa-ONNX `OfflineTts` generates audio samples from assistant response text
- Write samples to `AudioTrack` in streaming mode
- Manage audio focus: request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` before playback

### TTS Chunking
- Assistant responses may arrive as streaming tokens
- Buffer tokens until a sentence boundary (period, question mark, exclamation) or a pause threshold
- Generate TTS for each sentence chunk to minimize latency (start speaking before full response is received)

## Conversation Mode (Full-Duplex)
- Managed by Pipecat Android SDK (`PipecatClient`)
- Custom `OpenClawWebSocketTransport` implements `Transport<OpenClawConnectParams>`
- Transport wraps the existing OpenClaw `GatewaySession` WebSocket connection
- RTVI message envelopes are sent/received over the OpenClaw WS protocol

### Transport Implementation Notes
- `connect()`: reuse existing `GatewaySession` if already connected; send `ClientReady` RTVI message
- `sendMessage()`: serialize `MsgClientToServer` to JSON, wrap in OpenClaw WS frame, send
- `onMessage` from gateway: parse incoming message, if RTVI-typed → deserialize to `MsgServerToClient` → call `ctx.onMessage()`
- Camera methods: stub with empty futures (Marmalade doesn't use Pipecat video)

### Barge-In
- When user starts speaking (`onUserStartedSpeaking` callback), stop TTS playback immediately
- Clear any buffered TTS audio
- Gateway-side: the agent should also stop generating once it sees new user input

### Audio Pipeline Summary
```
Mic → AudioRecord → Silero VAD → speech segments → Sherpa STT → text → transport → gateway LLM
                         ↓                                                              ↓
                  speaking state                                                     response
                         ↓                                                              ↓
Speaker ← AudioTrack ← Sherpa TTS ← text ← OpenClawWebSocketTransport ←────────────────┘
```

## OpenClawConnectParams
```kotlin
data class OpenClawConnectParams(
    val gatewayUrl: String,
    val authToken: String,
    val sessionId: String?,
)
```

## Audio Session Management
- Acquire `AudioManager.AUDIOFOCUS_GAIN` before starting conversation mode
- Release on conversation end
- Handle `AUDIOFOCUS_LOSS_TRANSIENT`: pause listening, resume when focus returns
- Handle `AUDIOFOCUS_LOSS`: end conversation mode gracefully
- Respect `AudioManager.MODE_IN_COMMUNICATION` for proper routing

## Model Assets

All Sherpa-ONNX models are bundled in `app/src/main/assets/`. No download step required.

| Model | File | Size | Purpose |
|-------|------|------|---------|
| Wake word | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/` | ~3MB | "Hey Marmalade" detection |
| STT | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/` | ~20MB | Streaming speech-to-text |
| TTS | `en_US-libritts-medium/` | ~100MB | Text-to-speech (Piper VITS) |
| VAD | `silero_vad.onnx` | ~2MB | Voice activity detection |

Total APK size impact: ~125MB. Download models from Sherpa-ONNX GitHub releases (see `docs/references/sherpa-onnx-api.md` for URLs) and place in `app/src/main/assets/`.
