# Pipecat Android SDK (pipecat-client-android) API Reference

_Source: a local checkout of the `pipecat-client-android` repo — actual Kotlin source, use as authoritative._
_Package: `ai.pipecat.client`_
_RTVI protocol version: see `RTVI_PROTOCOL_VERSION` constant in the library._

---

## 1. Core Entry Point — PipecatClient

```kotlin
class PipecatClient<ConnectParams>(
    context: Context,
    transport: Transport<ConnectParams>,
    options: PipecatClientOptions,
) {
    fun connect(connectParams: ConnectParams): Future<Unit, RTVIError>
    fun disconnect(): Future<Unit, RTVIError>
    fun sendText(text: String, options: SendTextOptions = SendTextOptions()): Future<Unit, RTVIError>
    fun appendToContext(message: LLMContextMessage): Future<Unit, RTVIError>
    fun release()

    fun enableMic(enable: Boolean): Future<Unit, RTVIError>
    fun isMicEnabled(): Boolean
    fun getAllMics(): Future<List<MediaDeviceInfo>, RTVIError>
    fun updateMic(micId: MediaDeviceId): Future<Unit, RTVIError>

    fun state(): TransportState
    fun registerFunctionCallHandler(name: String, handler: (LLMFunctionCallData) -> LLMFunctionCallResult?)
}
```

---

## 2. Options

```kotlin
data class PipecatClientOptions(
    val callbacks: PipecatEventCallbacks,
    val enableMic: Boolean = true,    // enable microphone input
    val enableCam: Boolean = false,   // camera not needed for Marmalade
)
```

---

## 3. Event Callbacks — PipecatEventCallbacks

Extend this abstract class and override the events you need:

```kotlin
abstract class PipecatEventCallbacks {
    // Required:
    abstract fun onBackendError(message: String)

    // Connection lifecycle:
    open fun onConnected() {}
    open fun onDisconnected() {}
    open fun onTransportStateChanged(state: TransportState) {}
    open fun onBotReady(data: BotReadyData) {}
    open fun onBotConnected(participant: Participant) {}
    open fun onBotDisconnected(participant: Participant) {}

    // Speaking state (use for avatar animation and barge-in):
    open fun onBotStartedSpeaking() {}
    open fun onBotStoppedSpeaking() {}
    open fun onUserStartedSpeaking() {}
    open fun onUserStoppedSpeaking() {}

    // Audio levels (use for waveform visualizer):
    open fun onUserAudioLevel(level: Float) {}                         // 0.0–1.0
    open fun onRemoteAudioLevel(level: Float, participant: Participant) {}

    // Text/transcript (use to display in voice drawer and chat):
    open fun onUserTranscript(data: Transcript) {}      // user's speech → text
    open fun onBotLLMText(data: MsgServerToClient.Data.BotLLMTextData) {}  // streaming LLM output
    open fun onBotOutput(data: BotOutputData) {}        // final bot output
    open fun onBotTTSText(data: MsgServerToClient.Data.BotTTSTextData) {}  // text being spoken

    // LLM lifecycle:
    open fun onBotLLMStarted() {}
    open fun onBotLLMStopped() {}
    open fun onBotTTSStarted() {}
    open fun onBotTTSStopped() {}

    // Misc:
    open fun onMetrics(data: PipecatMetrics) {}
    open fun onServerMessage(data: Value) {}
    open fun onInputsUpdated(camera: Boolean, mic: Boolean) {}
    open fun onLLMFunctionCall(functionCallData: LLMFunctionCallData) {}
}
```

---

## 4. Transport Interface — How to Implement OpenClawWebSocketTransport

The `Transport<ConnectParams>` abstract class is the integration point. Implement all abstract methods:

```kotlin
abstract class Transport<ConnectParams> {

    // Called once during PipecatClient initialization:
    abstract fun initialize(ctx: TransportContext)

    // Deserialize connect params from JSON (return your ConnectParams data class):
    abstract fun deserializeConnectParams(json: String, startBotRequest: APIRequest): ConnectParams

    // Called once before connect — set up audio devices:
    abstract fun initDevices(): Future<Unit, RTVIError>

    // Called to release all resources:
    abstract fun release()

    // Establish connection using your ConnectParams:
    abstract fun connect(transportParams: ConnectParams): Future<Unit, RTVIError>

    // Tear down connection:
    abstract fun disconnect(): Future<Unit, RTVIError>

    // Mic/cam device management (cam not needed — stub with empty futures):
    abstract fun getAllMics(): Future<List<MediaDeviceInfo>, RTVIError>
    abstract fun getAllCams(): Future<List<MediaDeviceInfo>, RTVIError>
    abstract fun updateMic(micId: MediaDeviceId): Future<Unit, RTVIError>
    abstract fun updateCam(camId: MediaDeviceId): Future<Unit, RTVIError>
    abstract fun selectedMic(): MediaDeviceInfo?
    abstract fun selectedCam(): MediaDeviceInfo?
    abstract fun enableMic(enable: Boolean): Future<Unit, RTVIError>
    abstract fun enableCam(enable: Boolean): Future<Unit, RTVIError>
    abstract fun isCamEnabled(): Boolean
    abstract fun isMicEnabled(): Boolean

    // Send an RTVI control message to the backend (OpenClaw gateway):
    abstract fun sendMessage(message: MsgClientToServer): Future<Unit, RTVIError>

    // State management:
    abstract fun state(): TransportState
    abstract fun setState(state: TransportState)

    // Tracks (audio/video — return empty for text-only transport):
    abstract fun tracks(): Tracks
}
```

### TransportContext — Your Callback Interface to PipecatClient

```kotlin
interface TransportContext {
    val options: PipecatClientOptions
    val callbacks: PipecatEventCallbacks
    val thread: ThreadRef
    val protocolVersion: String  // RTVI protocol version string

    // Call this when the WS connection drops:
    fun onConnectionEnd()

    // Call this whenever an RTVI message arrives from the gateway:
    fun onMessage(msg: MsgServerToClient)
}
```

---

## 5. RTVI Message Protocol

### Client → Server (MsgClientToServer)

All messages have this envelope:
```json
{ "id": "<uuid>", "label": "rtvi-ai", "type": "<type>", "data": { ... } }
```

Key message types your transport needs to send:

```kotlin
// After connecting, send client-ready:
MsgClientToServer.ClientReady(
    rtviVersion = ctx.protocolVersion,
    library = "marmalade-android",
    libraryVersion = BuildConfig.VERSION_NAME,
    platform = "android",
    platformVersion = Build.VERSION.RELEASE
)

// Send text to the assistant (this is the primary message for OpenClaw):
MsgClientToServer.SendText(
    content = "transcribed text from Sherpa STT",
    options = SendTextOptions()
)

// Disconnect the bot:
MsgClientToServer.DisconnectBot()
```

### Server → Client (MsgServerToClient)

```kotlin
// Your transport calls ctx.onMessage(msg) for each received message.
// PipecatClient dispatches to PipecatEventCallbacks automatically.

// Key types to handle:
MsgServerToClient.Type.BotReady           // → onBotReady()
MsgServerToClient.Type.BotLlmText         // → onBotLLMText() — streaming text chunk
MsgServerToClient.Type.BotTtsText         // → onBotTTSText() — text being spoken
MsgServerToClient.Type.BotStartedSpeaking // → onBotStartedSpeaking()
MsgServerToClient.Type.BotStoppedSpeaking // → onBotStoppedSpeaking()
MsgServerToClient.Type.UserTranscription  // → onUserTranscript()
MsgServerToClient.Type.BotOutput          // → onBotOutput() — final bot output
MsgServerToClient.Type.Error              // → onBackendError()
MsgServerToClient.Type.Metrics            // → onMetrics()
```

---

## 6. OpenClawWebSocketTransport — Implementation Sketch

```kotlin
data class OpenClawConnectParams(
    val gatewayUrl: String,    // ws://192.168.1.x:18789 or wss://...
    val authToken: String,
    val sessionId: String?,    // target session ID; null = main gateway session
)

class OpenClawWebSocketTransport : Transport<OpenClawConnectParams>() {

    private lateinit var ctx: TransportContext
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    override fun initialize(ctx: TransportContext) {
        this.ctx = ctx
    }

    override fun deserializeConnectParams(
        json: String, startBotRequest: APIRequest
    ): OpenClawConnectParams = Json.decodeFromString(json)

    override fun connect(transportParams: OpenClawConnectParams): Future<Unit, RTVIError> {
        // 1. Open OkHttp WebSocket to transportParams.gatewayUrl
        // 2. Perform OpenClaw auth handshake (port from GatewaySession.kt)
        // 3. On success: setState(TransportState.Connected)
        //    Send MsgClientToServer.ClientReady(...)
        // 4. On failure: return RTVIError
    }

    override fun sendMessage(message: MsgClientToServer): Future<Unit, RTVIError> {
        // Serialize message to JSON, send over webSocket
        // Map RTVI message types to OpenClaw gateway WS protocol as needed
        // For SendText: extract content, send as chat message to target session
    }

    // In WebSocket.onMessage callback:
    // Parse incoming OpenClaw WS message → translate to MsgServerToClient → ctx.onMessage(msg)

    // ... (implement remaining abstract methods)
}
```

**Key insight:** The transport wraps the OpenClaw gateway WebSocket connection. On the OpenClaw side, the Marmalade channel plugin receives RTVI messages and routes them to the correct agent session. Outbound agent responses come back as RTVI `bot-llm-text` / `bot-tts-text` events.

**Reference for the auth + WS connection mechanics:** Port directly from `apps/android/app/src/main/java/ai/openclaw/android/gateway/GatewaySession.kt` in the OpenClaw repo.

**Reference for Electron Pipecat+OpenClaw transport:** an existing Electron implementation exists outside this repo — retrieve when available for exact message framing.

---

## 7. TransportState Values

```kotlin
enum class TransportState {
    Idle,
    Initializing,
    Initialized,
    Authenticating,
    Connecting,
    Connected,
    Ready,
    Disconnecting,
    Disconnected,
    Error,
}
```

---

## 8. Gradle Dependency

```kotlin
// libs.versions.toml
pipecat-client = "0.3.0"  // check latest at github.com/pipecat-ai/pipecat-client-android

[libraries]
pipecat-client-android = { module = "ai.pipecat:client", version.ref = "pipecat-client" }

// module build.gradle.kts
implementation(libs.pipecat.client.android)
```
