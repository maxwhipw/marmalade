package app.marmalade.android.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.marmalade.android.CameraHudKind
import app.marmalade.android.CameraHudState
import app.marmalade.android.LocationMode
import app.marmalade.android.SecurePrefs
import app.marmalade.android.VoiceWakeMode
import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.MAIN_SESSION_PLACEHOLDER
import app.marmalade.android.chat.ChatSessionEntry
import app.marmalade.android.chat.OutgoingAttachment
import app.marmalade.android.chat.messages.MessageStream
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.getDatabase
import app.marmalade.android.identity.DeviceIdentity
import app.marmalade.android.mic.MicOwner
import app.marmalade.android.mic.MicOwnershipManager
import app.marmalade.android.rpc.ConnectionState
import app.marmalade.android.rpc.InvokeResult
import app.marmalade.android.rpc.JsonRpcClient
import app.marmalade.android.rpc.MarmaladeInvokeDispatcher
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.service.NodeForegroundService
import app.marmalade.android.terminal.TerminalController
import app.marmalade.android.service.MarmaladeNotificationListenerService
import app.marmalade.android.notification.ChatNotificationHelper
import app.marmalade.android.notification.NotificationPipelineLogic
import app.marmalade.android.ui.chat.friendlySessionName

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Coarse, at-a-glance connection state for the chat status chip + tab badge. */
enum class ConnectionPhase { NotConfigured, Connecting, Connected, Disconnected }

/** Pure predicate behind [MarmaladeRuntime.attachmentsSupported] — pulled out
 *  of the StateFlow pipeline so it's testable without an Android Context. */
internal fun computeAttachmentsSupported(features: List<String>): Boolean =
  features.contains("attachments")

/** Pure predicate behind [MarmaladeRuntime.undoSupported] (session.undo,
 *  T2 #6). Testable without an Android Context. */
internal fun computeUndoSupported(features: List<String>): Boolean =
  features.contains("undo")

/** Pure predicate behind [MarmaladeRuntime.transcriptionSupported]
 *  (audio.transcribe). Testable without an Android Context. */
internal fun computeTranscriptionSupported(features: List<String>): Boolean =
  features.contains("transcription")

/** Pure predicate behind [MarmaladeRuntime.workspacesSupported] (workspace.*
 *  + workspace_id on session.list rows). Testable without an Android Context. */
internal fun computeWorkspacesSupported(features: List<String>): Boolean =
  features.contains("workspaces")

/** Pure predicate behind [MarmaladeRuntime.settingsSupported] (settings.get /
 *  settings.update — the daemon-owned new-session model + effort defaults).
 *  Testable without an Android Context. */
internal fun computeSettingsSupported(features: List<String>): Boolean =
  features.contains("settings")

/** Pure predicate behind [MarmaladeRuntime.terminalSupported] (terminal.* PTY
 *  terminals — advertised only when the daemon's node-pty backend loaded AND
 *  config allows). Testable without an Android Context. */
internal fun computeTerminalSupported(features: List<String>): Boolean =
  features.contains("terminal")

/** Pure predicate behind [MarmaladeRuntime.searchSupported] (search.messages —
 *  the daemon's FTS5 sidecar). Testable without an Android Context. */
internal fun computeSearchSupported(features: List<String>): Boolean =
  features.contains("search")

/** Pure predicate behind [MarmaladeRuntime.searchArchiveSupported]
 *  (`scope.corpus="archive"` + `search.archive` — the pre-daemon
 *  `~/.claude/projects` corpus). Its own feature, NOT implied by "search": a
 *  daemon can have the FTS sidecar without having indexed the archive.
 *  Testable without an Android Context. */
internal fun computeSearchArchiveSupported(features: List<String>): Boolean =
  features.contains("search_archive")

/**
 * Application composition root. Owns the long-lived state graph the rest
 * of the app reads through: Android-side handler instances, prefs/Room
 * accessors, the chat controller, and the transport-status flows the UI
 * binds to.
 *
 * State of play: the OpenClaw transport (`GatewaySession` × 2 +
 * mDNS discovery + TLS pinning + node-pairing + dual-socket capability
 * negotiation) is gone. The marmalade-agent transport is wired here as
 * TWO clients:
 *  - [jsonRpcClient] → dashboard `/api/ws` (chat: session, prompt,
 *    message, tool, reasoning). Drives the user-facing connection chip
 *    via [isConnected] / [statusText] / [connectionPhase].
 *  - [pluginJsonRpcClient] → marmalade-android plugin (`node.invoke.*`
 *    Android device-tool callbacks). Separate [pluginConnectionPhase]
 *    for the Debugging tab; not visible in the main status chip.
 * The NetworkCallback drives auto-reconnect on network availability
 * for whichever socket(s) are enabled-but-down; OkHttp's 30s WS ping
 * keeps each socket warm through NAT timeouts.
 *
 * [ChatController] is constructed against [marmaladeRpc] + [messageStream]
 * (Task #11 — done). The MarmaladeInvokeDispatcher and Python server-side
 * bridge (Tasks #5 and #25) close the device-tool channel separately.
 * Android-system hookups (mic ownership, voice-wake combiner, notification
 * listener, foreground service) all survive the transition unchanged.
 *
 * Renames to `MarmaladeRuntime` in the final commit of Task #10.
 */
class MarmaladeRuntime(context: Context) : app.marmalade.android.rpc.DevicePairingHost {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val prefs = SecurePrefs(appContext)
  val camera = CameraCaptureManager(appContext)
  val location = LocationCaptureManager(appContext)
  val screenRecorder = ScreenRecordManager(appContext)
  val sms = SmsManager(appContext)
  private val json = Json { ignoreUnknownKeys = true }

  // ── Marmalade transport ──────────────────────────────────────
  //
  // OkHttpClient with a 30s WS ping interval. Mobile networks routinely
  // close idle connections at NAT timeouts (cell carriers 30-300s, hotel
  // wifi sometimes <60s); OkHttp's frame-level pings keep the socket
  // warm without requiring the marmalade server to expose an app-layer
  // heartbeat. This replaces OpenClaw's HeartbeatWatchdog which polled
  // `health` RPCs — server.py has no `health` method, so the watchdog
  // pattern doesn't translate.
  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .pingInterval(30, TimeUnit.SECONDS)
    .build()

  /**
   * Transport-side log ring buffer. The Debugging tab observes
   * [TransportLogBuffer.entries]; the runtime, JsonRpcClient warnings,
   * and ChatController dispatch notices all write through [transportLog.add].
   * One stream, one viewer.
   */
  val transportLog: TransportLogBuffer = TransportLogBuffer()

  // Logger funnels JsonRpcClient warnings + audit-pass infos (frame
  // in/out, connection events) into the same Debugging-tab buffer so
  // users never consult two streams to triage a bug.
  //
  // Two clients, by design:
  //  - [jsonRpcClient] talks to the dashboard's /api/ws (same surface
  //    desktop + web use). All chat — session.*, prompt.*, message.*,
  //    tool.*, reasoning.* — flows here.
  //  - [pluginJsonRpcClient] talks to the marmalade-android plugin's WS.
  //    Carries ONLY node.invoke.request / node.invoke.respond — the
  //    Android-specific device-tool callbacks (alarms, intents, camera).
  // Splitting these prevents the gateway's _notify_long_running heartbeat
  // (which the platform-adapter shim was turning into chat-bubble noise)
  // from ever reaching the message stream.
  private val jsonRpcClient: JsonRpcClient = JsonRpcClient(
    httpClient = httpClient,
    logger = object : JsonRpcClient.Logger {
      override fun warn(message: String) {
        transportLog.add(TransportLogLevel.WARN, "dashboard: $message")
      }
      override fun info(message: String) {
        transportLog.add(TransportLogLevel.DEBUG, "dashboard: $message", verbose = true)
      }
    },
  )

  private val pluginJsonRpcClient: JsonRpcClient = JsonRpcClient(
    httpClient = httpClient,
    logger = object : JsonRpcClient.Logger {
      override fun warn(message: String) {
        transportLog.add(TransportLogLevel.WARN, "plugin: $message")
      }
      override fun info(message: String) {
        transportLog.add(TransportLogLevel.DEBUG, "plugin: $message", verbose = true)
      }
    },
  )

  /**
   * Typed wrapper over [jsonRpcClient] exposing the marmalade-agent RPC
   * surface (session.create/list/resume/history/interrupt, prompt.submit,
   * model.options, config.set, etc.). Exposed to [chat] / Task #11's
   * rewire and to the Task #5 invoke dispatcher.
   */
  // Public for ViewModel access from ui.settings (Plugins) that
  // can't see internal members across package boundaries. NOT a stable API
  // surface — call ChatController helpers when one exists for the operation
  // instead of reaching for marmaladeRpc directly.
  val marmaladeRpc: MarmaladeRpc = MarmaladeRpc(jsonRpcClient)

  private val externalAudioCaptureActive = MutableStateFlow(false)
  private val chatMicActive = MutableStateFlow(false)

  // True while KWS actually owns the mic. Drives the foreground-service
  // notification's "Voice Wake: Listening / Paused" suffix
  // (NodeForegroundService). Sourced from MicOwnershipManager.currentOwner
  // by the collector in init {}.
  private val _voiceWakeIsListening = MutableStateFlow(false)
  val voiceWakeIsListening: StateFlow<Boolean> = _voiceWakeIsListening.asStateFlow()

  private val _voiceWakeStatusText = MutableStateFlow("Off")
  val voiceWakeStatusText: StateFlow<String> = _voiceWakeStatusText.asStateFlow()

  private val deviceIdentity = DeviceIdentity.loadOrCreate(appContext)

  // ── Android-system handlers (device-tool executors) ───────────
  //
  // These run on the device regardless of transport state; the transport
  // layer only chooses *when* to dispatch them (via MarmaladeInvokeDispatcher,
  // Task #5). Each handler/manager is its own class so the runtime stays a
  // composition root, not a god class.
  //
  // CameraHandler.uploadBaseUrl + AppUpdateHandler.trustedHost both read
  // from prefs.dashboardUrl: /api/files/upload lives on the dashboard
  // (hermes_cli/web_server.py:1464), as does the APK-serving route. The
  // plugin host (port 9211) has no file endpoints. Lambdas (not
  // StateFlow.value snapshots) so a host change without restart is honored.

  private val cameraHandler: CameraHandler = CameraHandler(
    appContext = appContext,
    camera = camera,
    prefs = prefs,
    uploadBaseUrl = { prefs.dashboardUrl.value.trim().ifBlank { null } },
    externalAudioCaptureActive = externalAudioCaptureActive,
    showCameraHud = ::showCameraHud,
    triggerCameraFlash = ::triggerCameraFlash,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val debugHandler: DebugHandler = DebugHandler(
    appContext = appContext,
    deviceIdentity = deviceIdentity,
  )

  private val appUpdateHandler: AppUpdateHandler = AppUpdateHandler(
    appContext = appContext,
    trustedHost = {
      // Parse hostname out of the dashboardUrl (e.g. "https://m.example.com:9119/" → "m.example.com")
      // so the app.update tool can refuse APK downloads from any other origin.
      // The APK is served by the dashboard's /api/files/* surface.
      prefs.dashboardUrl.value.trim()
        .removePrefix("https://").removePrefix("http://")
        .substringBefore('/').substringBefore(':')
        .ifBlank { null }
    },
  )

  private val deviceHandler: DeviceHandler = DeviceHandler(
    appContext = appContext,
    prefs = prefs,
  )

  private val locationHandler: LocationHandler = LocationHandler(
    appContext = appContext,
    location = location,
    json = json,
    isForeground = { _isForeground.value },
    locationMode = { locationMode.value },
    locationPreciseEnabled = { locationPreciseEnabled.value },
  )

  private val screenHandler: ScreenHandler = ScreenHandler(
    screenRecorder = screenRecorder,
    setScreenRecordActive = { _screenRecordActive.value = it },
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val smsHandlerImpl: SmsHandler = SmsHandler(
    sms = sms,
  )

  // Drives `notifications.list` and `notifications.actions` tools.
  // Lost wiring here = NotificationListenerService keeps fielding tool
  // calls but receives zero StatusBarNotification events. Verbatim port
  // from the pre-strip runtime.
  private val notificationManager: NotificationManager = NotificationManager().also {
    MarmaladeNotificationListenerService.manager = it
  }

  private val notificationsHandler: NotificationsHandler = NotificationsHandler(
    context = appContext,
    notificationManager = notificationManager,
  )

  private val systemHandler: SystemHandler = SystemHandler(
    appContext = appContext,
  )

  private val photosHandler: PhotosHandler = PhotosHandler(
    appContext = appContext,
  )

  private val contactsHandler: ContactsHandler = ContactsHandler(
    appContext = appContext,
  )

  private val calendarHandler: CalendarHandler = CalendarHandler(
    appContext = appContext,
  )

  private val motionHandler: MotionHandler = MotionHandler(
    appContext = appContext,
  )

  private val wifiHandler = WifiHandler(
    context = appContext,
    json = json,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val clipboardHandler = ClipboardHandler(
    context = appContext,
    json = json,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val appHandler = AppHandler(
    context = appContext,
    json = json,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val androidActionHandler = AndroidActionHandler(
    context = appContext,
    json = json,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val voiceWakeHandler = VoiceWakeHandler(
    json = json,
    voiceWakeMode = { prefs.voiceWakeMode.value },
    setVoiceWakeMode = { mode ->
      prefs.setVoiceWakeMode(mode)
    },
    voiceWakeStatusText = { voiceWakeStatusText.value },
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  /**
   * Wire-name → handler-method registration table. The server-side bridge
   * plugin (live at `~/.marmalade/plugins/marmalade-android/adapter.py`)
   * emits `node.invoke.request` events tagged with these tool names;
   * whatever the plugin advertises to the agent must match a key here, or
   * the dispatch returns `UNKNOWN_TOOL`.
   *
   * Names mirror the OpenClaw-era set so existing curated prompts that
   * already know "app.launch" / "sms.send" / "calendar.add" / etc. keep
   * working without the plugin having to translate. Where OpenClaw used
   * camelCase wire-names (`voiceWake.*`) we keep them — the server is the
   * source of truth and a rename would force a coordinated client+server+
   * prompt change.
   *
   * The plugin currently registers only 4 tools server-side —
   * `android_set_alarm`, `android_notify`, `android_device_status`,
   * `android_fire_intent` — so only those wire names see live traffic
   * today. Every other handler below (camera, SMS, contacts, calendar,
   * motion, wifi, etc.) is implemented and testable but sits idle until
   * the gateway registers a matching tool name (registration plan: internal
   * design note, not in this repo).
   */
  private val invokeHandlers: Map<String, suspend (String?) -> InvokeResult> = mapOf(
    // Camera
    "camera.snap" to { cameraHandler.handleSnap(it) },
    "camera.clip" to { cameraHandler.handleClip(it) },
    "camera.list" to { cameraHandler.handleList(it) },
    // Location
    "location.get" to { locationHandler.handleLocationGet(it) },
    "location.history" to { locationHandler.handleLocationHistory(it) },
    "location.lastKnown" to { locationHandler.handleLocationLastKnown(it) },
    "location.setTracking" to { locationHandler.handleLocationSetTracking(it) },
    // Screen
    "screen.record" to { screenHandler.handleScreenRecord(it) },
    // SMS
    "sms.send" to { smsHandlerImpl.handleSmsSend(it) },
    "sms.readLatest" to { smsHandlerImpl.handleSmsReadLatest() },
    "sms.readUnread" to { smsHandlerImpl.handleSmsReadUnread() },
    // Notifications
    "notifications.list" to { notificationsHandler.handleList() },
    "notifications.actions" to { notificationsHandler.handleActions(it) },
    // System
    "system.notify" to { systemHandler.handleNotify(it) },
    "system.volume" to { systemHandler.handleVolume(it) },
    "system.brightness" to { systemHandler.handleBrightness(it) },
    "alarm.set" to { systemHandler.handleSetAlarm(it) },
    // Photos
    "photos.latest" to { photosHandler.handleLatest() },
    // Contacts
    "contacts.search" to { contactsHandler.handleSearch(it) },
    "contacts.add" to { contactsHandler.handleAdd(it) },
    "contacts.update" to { contactsHandler.handleUpdate(it) },
    "contacts.delete" to { contactsHandler.handleDelete(it) },
    // Calendar
    "calendar.events" to { calendarHandler.handleEvents(it) },
    "calendar.add" to { calendarHandler.handleAdd(it) },
    "calendar.update" to { calendarHandler.handleUpdate(it) },
    "calendar.delete" to { calendarHandler.handleDelete(it) },
    // Motion
    "motion.activity" to { motionHandler.handleActivity() },
    "motion.pedometer" to { motionHandler.handlePedometer() },
    // Wifi
    "wifi.list" to { wifiHandler.handleWifiList() },
    "wifi.status" to { wifiHandler.handleWifiStatus() },
    "wifi.connect" to { wifiHandler.handleWifiConnect(it) },
    // Apps
    "app.list" to { appHandler.handleAppList() },
    "app.launch" to { appHandler.handleAppLaunch(it) },
    "app.update" to { appUpdateHandler.handleUpdate(it) },
    // Clipboard
    "clipboard.read" to { clipboardHandler.handleClipboardRead() },
    "clipboard.write" to { clipboardHandler.handleClipboardWrite(it) },
    // Voice wake
    "voiceWake.getMode" to { voiceWakeHandler.handleVoiceWakeGetMode() },
    "voiceWake.setMode" to { voiceWakeHandler.handleVoiceWakeSetMode(it) },
    "voiceWake.status" to { voiceWakeHandler.handleVoiceWakeStatus() },
    // Device
    "device.status" to { deviceHandler.handleStatus() },
    "device.info" to { deviceHandler.handleInfo() },
    "device.permissions" to { deviceHandler.handlePermissions() },
    "device.health" to { deviceHandler.handleHealth() },
    // Android intent dispatch (voice "open YouTube" / "search ... in Spotify")
    "android_action" to { androidActionHandler.handleAndroidAction(it) },
    // Debug — debug builds only (handlers return UNAVAILABLE on release)
    "debug.identity" to { debugHandler.handleIdentity() },
    "debug.logs" to { debugHandler.handleLogs() },
  )

  /**
   * Routes inbound `node.invoke.request` events through [invokeHandlers]
   * and replies via `node.invoke.respond`. Started in init {}. The gateway
   * plugin is live and already sends events for its 4 registered tools
   * (`android_set_alarm`, `android_notify`, `android_device_status`,
   * `android_fire_intent`); the rest of [invokeHandlers] sits idle — not
   * because nothing arrives, but because no event names them yet — until
   * the gateway registers matching tools. The registration table is the
   * testable single source of truth for tool names either way.
   */
  // Invoke dispatcher binds entirely to the plugin client: node.invoke.request
  // events arrive there and node.invoke.respond replies need to land back on
  // the same socket the plugin's _block() pattern is awaiting. The dashboard
  // /api/ws never sees node.invoke traffic.
  private val invokeDispatcher: MarmaladeInvokeDispatcher = MarmaladeInvokeDispatcher(
    rpcClient = pluginJsonRpcClient,
    events = pluginJsonRpcClient.events,
    scope = scope,
    handlers = invokeHandlers,
  )

  // ── Connection state flows ────────────────────────────────────
  //
  // [jsonRpcClient] (dashboard chat) drives the user-facing flows below.
  // [pluginJsonRpcClient] gets its own [pluginConnectionPhase] for the
  // Debugging tab. Splitting matters: a user with chat working and the
  // optional plugin disabled should see "Connected", not "Partial".

  /** True while the WS connection is fully established and dispatching RPCs. */
  val isConnected: StateFlow<Boolean> = jsonRpcClient.connectionState
    .map { it == ConnectionState.Open }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /** Human-readable connection status text for Compose UI observation. */
  val statusText: StateFlow<String> = jsonRpcClient.connectionState
    .map { state ->
      when (state) {
        ConnectionState.Idle -> "Offline"
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Open -> "Connected"
        ConnectionState.Closed -> "Disconnected"
        ConnectionState.Error -> "Connection failed"
      }
    }
    .stateIn(scope, SharingStarted.Eagerly, "Offline")

  /** Alias for [statusText] — kept for the Compose UI's read site. */
  val connectionStatus: StateFlow<String> get() = statusText

  /**
   * Coarse connection phase for at-a-glance UI (status chip / tab badge).
   */
  val connectionPhase: StateFlow<ConnectionPhase> = combine(
    prefs.dashboardEnabled, jsonRpcClient.connectionState,
  ) { enabled, state ->
    when {
      !enabled -> ConnectionPhase.NotConfigured
      state == ConnectionState.Open -> ConnectionPhase.Connected
      state == ConnectionState.Connecting -> ConnectionPhase.Connecting
      else -> ConnectionPhase.Disconnected
    }
  }.stateIn(scope, SharingStarted.Eagerly, ConnectionPhase.NotConfigured)

  /** Plugin (node.invoke) socket health for the Debugging tab. */
  val pluginConnectionPhase: StateFlow<ConnectionPhase> = combine(
    prefs.marmaladeEnabled, pluginJsonRpcClient.connectionState,
  ) { enabled, state ->
    when {
      !enabled -> ConnectionPhase.NotConfigured
      state == ConnectionState.Open -> ConnectionPhase.Connected
      state == ConnectionState.Connecting -> ConnectionPhase.Connecting
      else -> ConnectionPhase.Disconnected
    }
  }.stateIn(scope, SharingStarted.Eagerly, ConnectionPhase.NotConfigured)

  /**
   * True when the user has toggled a capability (camera, SMS, etc.) since
   * the most recent successful connect. Marmalade declares per-connection
   * capabilities at the WS handshake — once the socket is open, toggling
   * a setting in the app doesn't propagate until the next reconnect.
   * Surfaced to the UI so a "Reconnect to apply" affordance can appear
   * instead of silently dropping the user's toggle. Resets to false each
   * time [jsonRpcClient.connectionState] enters [ConnectionState.Open].
   */
  private val _capabilitiesChangedSinceConnect = MutableStateFlow(false)
  val capabilitiesChangedSinceConnect: StateFlow<Boolean> =
    _capabilitiesChangedSinceConnect.asStateFlow()

  /**
   * Stable session key for the "main" chat session. Currently constant;
   * B/3b wires this from `session.most_recent` on connect so the UI binds
   * to whatever session the server considers active.
   */
  private val _mainSessionKey = MutableStateFlow(MAIN_SESSION_PLACEHOLDER)
  val mainSessionKey: StateFlow<String> = _mainSessionKey.asStateFlow()

  private val cameraHudSeq = AtomicLong(0)
  private val _cameraHud = MutableStateFlow<CameraHudState?>(null)
  val cameraHud: StateFlow<CameraHudState?> = _cameraHud.asStateFlow()

  private val _cameraFlashToken = MutableStateFlow(0L)
  val cameraFlashToken: StateFlow<Long> = _cameraFlashToken.asStateFlow()

  private val _screenRecordActive = MutableStateFlow(false)
  val screenRecordActive: StateFlow<Boolean> = _screenRecordActive.asStateFlow()

  private val _serverName = MutableStateFlow<String?>(null)
  val serverName: StateFlow<String?> = _serverName.asStateFlow()

  /** Features the daemon advertised in its hello result ("stable-ids",
   *  "subscribe", …). Empty until a hello succeeds on this socket. */
  private val _serverFeatures = MutableStateFlow<List<String>>(emptyList())
  val serverFeatures: StateFlow<List<String>> = _serverFeatures.asStateFlow()

  /**
   * True when the connected daemon advertises "attachments" in its hello
   * features. The daemon defines no such feature yet, so this is false
   * today by design — the composer's attach UI auto-lights the moment the
   * daemon starts advertising it, with no client release needed.
   */
  val attachmentsSupported: StateFlow<Boolean> = _serverFeatures
    .map { computeAttachmentsSupported(it) }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /**
   * True when the connected daemon advertises "undo" in its hello features
   * (session.undo, T2 #6). Gates the "Undo last turn" action in the chat
   * settings sheet — hidden until the daemon supports it, lit automatically
   * when it does, no client release needed.
   */
  val undoSupported: StateFlow<Boolean> = _serverFeatures
    .map { computeUndoSupported(it) }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /**
   * True when the connected daemon advertises "transcription" — the daemon
   * only does so when an STT command actually resolves on its host, so a
   * true here means an audio.transcribe fallback round trip can succeed.
   * Gates the voice popup's server-STT fallback (ServerRecognizer).
   */
  val transcriptionSupported: StateFlow<Boolean> = _serverFeatures
    .map { computeTranscriptionSupported(it) }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /**
   * True when the connected daemon advertises "workspaces" — the workspace.*
   * CRUD surface + workspace_id stamped on session.list rows. Gates the whole
   * workspace UI (grouping cards, the New workspace menu item); an old daemon
   * without it degrades to a flat RECENT-only list, no client release needed.
   */
  val workspacesSupported: StateFlow<Boolean> = _serverFeatures
    .map { computeWorkspacesSupported(it) }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /**
   * True when the connected daemon advertises "terminal" — daemon-hosted PTY
   * terminals (terminal.*). The daemon advertises it only when its node-pty
   * backend loaded AND config allows (terminal_enabled), so a true here means
   * terminal.create can succeed. Gates the whole terminal UI (the Settings
   * "Terminals" entry + its screens); hidden until the daemon supports it, lit
   * automatically when it does, no client release needed.
   */
  val terminalSupported: StateFlow<Boolean> = _serverFeatures
    .map { computeTerminalSupported(it) }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /**
   * True when the connected daemon advertises "settings" — settings.get /
   * settings.update, the writable new-session model + effort defaults behind
   * the Models settings screen. False on an older daemon: the screen still
   * SHOWS the defaults (model.list has carried them since 2026-07-23) but
   * renders read-only rather than offering a write that would 404.
   */
  val settingsSupported: StateFlow<Boolean> = _serverFeatures
    .map { computeSettingsSupported(it) }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /**
   * True when the connected daemon advertises "search" — the FTS5 sidecar
   * behind `search.messages`. The daemon wires the index at boot, but a daemon
   * without the sidecar answers MethodNotFound, so this gates both the drawer's
   * Search entry and find-in-conversation. There is deliberately NO client-side
   * fallback index: search is server-side by design (Room FTS has no bm25, and
   * a per-device index means results depend on which device you're holding).
   */
  val searchSupported: StateFlow<Boolean> = _serverFeatures
    .map { computeSearchSupported(it) }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /**
   * True when the connected daemon advertises "search_archive" — the pre-daemon
   * Claude Code corpus (`~/.claude/projects`), indexed read-only and years deep.
   *
   * Gates EVERY archive affordance: the Archive toggle in search, and the
   * read-only transcript viewer behind an archive hit. Independent of
   * [searchSupported] on purpose — a daemon can run the FTS sidecar over its own
   * sessions without having indexed the archive, and there is no useful
   * degraded mode. Ungated, an older daemon would silently ignore the unknown
   * `scope.corpus` and answer with LIVE results under an "Archive" chip, which
   * is worse than not offering the chip.
   */
  val searchArchiveSupported: StateFlow<Boolean> = _serverFeatures
    .map { computeSearchArchiveSupported(it) }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /** Gateway display name for Compose UI observation. Alias for serverName. */
  val gatewayName: StateFlow<String?> = _serverName.asStateFlow()

  // SettingsRepository for persisted dispatch-side prefs (dismissed
  // update versions). Held as a member so the handler doesn't pay the
  // EncryptedSharedPreferences init cost on every event.
  private val settings = SettingsRepository.getInstance(appContext)

  /**
   * Largest single inbound frame the gateway will accept. Stubbed to
   * null until B/3a wires it to `JsonRpcClient.serverPolicy.maxPayloadBytes`.
   * [ChatController] accepts null (treats it as "no client-side limit").
   */
  fun currentMaxPayloadBytes(): Long? = null

  /** Stable local device identifier. See [DeviceIdentity]. */
  override val deviceId: String
    get() = deviceIdentity.deviceId

  private val _isForeground = MutableStateFlow(true)
  val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

  private val database: AppDatabase = AppDatabase.getDatabase(appContext)
  private val chatDao = database.chatDao()

  // Boot recovery for stale 'sending' outbox + isStreaming messages rows
  // runs synchronously inside Room's onOpen callback (see AppDatabase.kt).
  // That ordering is load-bearing: scope.launch from here would race
  // MessageStream's events.collect on a fast warm-process reconnect (race
  // scenario 5 from Reviewer Checkpoint 1).

  /**
   * Per-session event-stream dispatcher. Constructed BEFORE the
   * connect call so its consumer coroutine is hot by the time the first
   * `gateway.ready` lands (MessageStream's SharedFlow has replay=0).
   */
  private val messageStream: MessageStream = MessageStream(
    jsonRpcClient,
    scope,
    chatDao,
    json,
    // logWarn/logDebug: MessageStream lives in :shared (no Android SDK), so
    // logcat is wired here. Tags match the pre-KMP `Log.w(TAG, …)` calls.
    logWarn = { android.util.Log.w("MessageStream", it) },
    logDebug = { android.util.Log.d("MessageStream", it) },
  )

  /**
   * Background drainer for the outbox table. Started immediately so it can
   * react to the first ConnectionState.Open emission. Survives transport
   * flaps via the per-row backoff in OutboxDrainer.
   */
  private val outboxDrainer: app.marmalade.android.chat.messages.OutboxDrainer =
    app.marmalade.android.chat.messages.OutboxDrainer(
      chatDao = chatDao,
      transport = app.marmalade.android.chat.messages.marmaladeRpcAdapter(marmaladeRpc),
      scope = scope,
      persistence = messageStream.persistence,
      logWarn = { android.util.Log.w("OutboxDrainer", it) },
    ).also { it.start() }

  /**
   * The Android half of [app.marmalade.android.chat.ChatHost] — the four
   * Context-bound facts ChatController/PromptCenter used to reach for
   * directly, before the chat slice moved to :shared. Each member does
   * exactly what the pre-port inline code did, in the same order and with
   * the same failure semantics (the caller wraps usage persistence in
   * runCatching; dispatch/cancel results are ignored).
   */
  private val chatHost = object : app.marmalade.android.chat.ChatHost {
    override fun dispatchVoiceAction(action: app.marmalade.android.voice.MarmaladeAction) {
      app.marmalade.android.voice.dispatchAction(appContext, action)
    }

    override fun saveSessionUsage(key: String, encodedJson: String) {
      SettingsRepository.getInstance(appContext).saveSessionUsage(key, encodedJson)
    }

    override fun loadSessionUsageJson(key: String): String? =
      SettingsRepository.getInstance(appContext).getSessionUsageJson(key)

    override fun cancelPromptNotification(sessionKey: String) {
      ChatNotificationHelper.cancelPromptNotification(appContext, sessionKey)
    }
  }

  val chat: ChatController =
    ChatController(
      scope = scope,
      rpc = marmaladeRpc,
      messageStream = messageStream,
      outboxDrainer = outboxDrainer,
      json = json,
      chatDao = chatDao,
      isForeground = { _isForeground.value },
      host = chatHost,
      onOtherSessionMessage = { sessionKey, _, text ->
        handleOtherSessionMessage(sessionKey, text)
      },
      onPromptNotification = { sessionKey, prompt ->
        handlePromptNotification(sessionKey, prompt)
      },
      onDispatchAction = { action ->
        app.marmalade.android.voice.dispatchAction(appContext, action)
      },
      logDispatch = { msg, runId ->
        transportLog.add(TransportLogLevel.INFO, msg, runId = runId)
      },
      logWarn = { msg ->
        transportLog.add(TransportLogLevel.WARN, msg)
      },
      maxPayloadBytes = { currentMaxPayloadBytes() },
      showUnknownFramesInChat = { settings.showUnknownFramesInChat },
      // marmaladed session.delete over JSON-RPC (the daemon is WS-only; the
      // fork-era REST DELETE is gone). The daemon stops a live harness itself
      // and cascades messages/seen/transcript — one call, no close-first
      // ritual, no "cannot delete active session" refusal.
      deleteSessionRemote = { storedId -> deleteChatSession(storedId) },
      // THE daemon-managed main session's id (session.main), resolved on
      // connect. Lets the controller tell when the bound session is Home —
      // driving Clear→session.clear (not a new session) and hiding delete.
      mainSessionKey = _mainSessionKey,
    )

  /**
   * Daemon-hosted PTY terminals (terminal.*). Owns the roster + the seven
   * terminal RPCs, and demuxes the transient terminal.data/terminal.exit
   * events off [jsonRpcClient] AROUND the chat/session path (no Room). The
   * terminal screens drive it; the whole surface is gated on [terminalSupported].
   */
  val terminal: TerminalController = TerminalController(
    rpc = marmaladeRpc,
    scope = scope,
    events = jsonRpcClient.events,
    json = json,
  )

  private fun resolveMainSessionKey(): String {
    val trimmed = _mainSessionKey.value.trim()
    return if (trimmed.isEmpty()) MAIN_SESSION_PLACEHOLDER else trimmed
  }

  // ── Persisted-pref accessors (pass-through to SecurePrefs) ────

  val instanceId: StateFlow<String> = prefs.instanceId
  val displayName: StateFlow<String> = prefs.displayName
  val cameraEnabled: StateFlow<Boolean> = prefs.cameraEnabled
  val locationMode: StateFlow<LocationMode> = prefs.locationMode
  val locationPreciseEnabled: StateFlow<Boolean> = prefs.locationPreciseEnabled
  val preventSleep: StateFlow<Boolean> = prefs.preventSleep
  val wakeWords: StateFlow<List<String>> = prefs.wakeWords
  val voiceWakeMode: StateFlow<VoiceWakeMode> = prefs.voiceWakeMode
  val smsEnabled: StateFlow<Boolean> = prefs.smsEnabled
  val marmaladeEnabled: StateFlow<Boolean> = prefs.marmaladeEnabled
  val marmaladeUrl: StateFlow<String> = prefs.marmaladeUrl
  val marmaladeToken: StateFlow<String> = prefs.marmaladeToken
  fun setMarmaladeEnabled(value: Boolean) = prefs.setMarmaladeEnabled(value)
  fun setMarmaladeUrl(value: String) = prefs.setMarmaladeUrl(value)
  fun setMarmaladeToken(value: String) = prefs.setMarmaladeToken(value)
  val dashboardEnabled: StateFlow<Boolean> = prefs.dashboardEnabled
  val dashboardUrl: StateFlow<String> = prefs.dashboardUrl
  val dashboardToken: StateFlow<String> = prefs.dashboardToken
  fun setDashboardEnabled(value: Boolean) = prefs.setDashboardEnabled(value)
  fun setDashboardUrl(value: String) = prefs.setDashboardUrl(value)
  fun setDashboardToken(value: String) = prefs.setDashboardToken(value)

  // ── Chat delegates ────────────────────────────────────────────
  //
  // The UI binds to [chat] directly (Task #13). No legacy-shape
  // adapter; the new ChatMessage IS render state.

  val chatSessionKey: StateFlow<String> = chat.sessionKey
  val chatSessionId: StateFlow<String?> = chat.sessionId
  val chatError: StateFlow<String?> = chat.errorText
  val chatHealthOk: StateFlow<Boolean> = chat.healthOk
  val chatThinkingLevel: StateFlow<String> = chat.thinkingLevel
  val chatSessions: StateFlow<List<ChatSessionEntry>> = chat.sessions
  val pendingRunCount: StateFlow<Int> = chat.pendingRunCount
  val pendingPrompts: StateFlow<List<app.marmalade.android.chat.PendingPrompt>> = chat.pendingPrompts
  /** Per-session running flag, keyed by live gateway session_id. See [ChatController.sessionRunning]. */
  val sessionRunning: StateFlow<Map<String, Boolean>> = chat.sessionRunning

  init {
    // Start the persistent foreground service for combined status notification.
    // NodeForegroundService manages the ongoing notification and auto-starts HotwordService.
    NodeForegroundService.start(appContext)

    // Seed the main-session binding from the persisted session.main id so an
    // OFFLINE cold start binds Home to the daemon's main conversation instead
    // of the "main" placeholder (which would render an empty phantom chat).
    // The id was cached the last time session.main resolved on connect. If it
    // isn't cached yet (never connected), fall back to any real cached session
    // so Home isn't stranded on the placeholder. compareAndSet: if
    // session.main already answered (fast connect), the authoritative value
    // wins and the seed is dropped.
    scope.launch {
      // Prefer the persisted session.main id; else the cached row the daemon
      // last stamped is_main (the Room column persists across restarts) — never
      // a random session, since _mainSessionKey now drives isBoundMain (which
      // hides Delete + turns Clear into session.clear). No is_main row yet
      // (never connected) → stay on the placeholder; connect installs the real
      // id and self-heals.
      val seed = settings.cachedMainSessionId?.takeIf { it.isNotBlank() }
        ?: chatDao.getAllSessions().first()
          .firstOrNull { it.isMain && it.key != MAIN_SESSION_PLACEHOLDER }?.key
      if (seed != null && _mainSessionKey.compareAndSet(MAIN_SESSION_PLACEHOLDER, seed)) {
        chat.applyMainSessionKey(seed)
        transportLog.add(TransportLogLevel.INFO, "main session seeded from cache: $seed")
      }
    }

    // Reconnect-on-network-available. Triggered each time a usable network
    // appears (wifi join, cell tower hop, VPN up). Idempotent: the JsonRpcClient
    // is a no-op if already in Connecting/Open. Survives unregistration on
    // process death — the foreground service keeps us alive long enough.
    val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.registerNetworkCallback(
      NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build(),
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          val needsDashboard = dashboardEnabled.value &&
            jsonRpcClient.connectionState.value != ConnectionState.Open
          val needsPlugin = marmaladeEnabled.value &&
            pluginJsonRpcClient.connectionState.value != ConnectionState.Open
          if (needsDashboard || needsPlugin) {
            transportLog.add(TransportLogLevel.INFO, "Network available — reconnecting")
            connectMarmalade()
          }
        }
      },
    )

    // Drain the JsonRpcClient event stream into the log buffer so the
    // Debugging tab and gateway-tab summary keep showing transport activity.
    // Verbose-flagged so it only appears when the user opts in (avoids
    // flooding the default log view with every message.delta during streaming).
    scope.launch {
      jsonRpcClient.events.collect { event ->
        transportLog.add(
          level = TransportLogLevel.DEBUG,
          message = "dashboard ↓ event ${event.type}",
          verbose = true,
          source = "event",
        )
      }
    }
    scope.launch {
      pluginJsonRpcClient.events.collect { event ->
        transportLog.add(
          level = TransportLogLevel.DEBUG,
          message = "plugin ↓ event ${event.type}",
          verbose = true,
          source = "event",
        )
      }
    }

    // Start the node-invoke dispatcher's event subscription. The gateway
    // plugin is live and already emits node.invoke.request for its 4
    // registered tools (android_set_alarm, android_notify,
    // android_device_status, android_fire_intent); the remaining handlers
    // in invokeHandlers wait on further gateway-side tool registration.
    invokeDispatcher.start()

    // Reset the "capabilities changed since connect" hint whenever the
    // connection transitions to Open — the fresh handshake just re-declared
    // them, so the user's earlier toggles are now live. Also seed
    // [_mainSessionKey] from server.session_most_recent so the UI binds to
    // the conversation the server considers active, not the hardcoded "main".
    scope.launch {
      jsonRpcClient.connectionState.collect { state ->
        if (state != ConnectionState.Open) {
          // Drop the host label when chat is down so ChatTopBar /
          // DoneStep stop showing "Connected to <host>" after the WS
          // dies. Without this clear, the chip lies about live status.
          _serverName.value = null
          // Features are per-socket (negotiated by hello) — a reconnect
          // renegotiates them.
          _serverFeatures.value = emptyList()
        }
        if (state == ConnectionState.Open) {
          _capabilitiesChangedSinceConnect.value = false
          // Surface the host from the configured dashboard URL as the
          // server "name" so ChatTopBar + DoneStep have something to show
          // instead of the null fallback. gateway.ready doesn't carry a
          // name; this is the best signal we have without inventing a
          // server.info-style RPC.
          _serverName.value = extractHostFromUrl(dashboardUrl.value)
          try {
            // THE main session (assistant plan 2026-07-19): get-or-create the
            // daemon-managed singleton and bind Home + voice to it. It is
            // NEVER created client-side and never appears as deletable — the
            // daemon owns the designation. Persist its id so the next OFFLINE
            // cold start seeds Home with it instead of an empty phantom.
            val mainId = marmaladeRpc.sessionMain().session_id
            if (mainId.isNotBlank()) {
              _mainSessionKey.value = mainId
              settings.cachedMainSessionId = mainId
              chat.applyMainSessionKey(mainId)
              transportLog.add(
                TransportLogLevel.INFO,
                "main session bound to session.main: $mainId",
              )
            }
          } catch (t: Throwable) {
            transportLog.add(
              TransportLogLevel.WARN,
              "session.main failed: ${t.message ?: t.javaClass.simpleName}",
            )
          }
          // Pull the sidebar list eagerly so Home + ChatTopBar can resolve
          // titles before the user navigates to Sessions. Without this,
          // a fresh cold-launch on Home shows the stored_session_id slug
          // ("20260629 180856 779e02") because the only Room row we have
          // is the displayName=null placeholder applyMainSessionKey
          // inserted above.
          chat.refreshSessions()
        }
      }
    }

    // Auto-connect on startup when configured. Dispatches to both sockets;
    // each has its own per-socket guard for empty URL/token, so we only need
    // the OR — a dashboard-only user must reconnect on warm start, not just
    // when the user revisits Connection settings.
    if (dashboardEnabled.value || marmaladeEnabled.value) {
      connectMarmalade()
    }

    // App-foreground hook (ON_START) → trigger a fresh chat.refresh() so
    // hydrateFromServer pulls the latest history + adopts the gateway's
    // current live session_id + seeds any inflight assistant snapshot.
    // Without this, a user who backgrounds the app mid-turn (or just
    // sits idle long enough for the socket to drop in Doze) and then
    // foregrounds without an explicit reconnect-state transition would
    // miss every delta event that fired in the meantime — the gateway
    // does NOT replay individual events, only an accumulated `inflight`
    // text snapshot in session.resume's response. Mirrors desktop's
    // `visibilitychange` → `reconnectNow` → resumeSession flow.
    //
    // Observe on the main thread (Lifecycle requires it). The actual
    // refresh dispatches to the controller's own scope so we don't
    // block the lifecycle event delivery.
    scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
      androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
        androidx.lifecycle.LifecycleEventObserver { _, event ->
          if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
            chat.refresh()
          }
        }
      )
    }

    // Auto-reconnect on Closed / Error: exponential backoff up to 15 s.
    // The NetworkCallback covers the "lost Wi-Fi" case but does nothing
    // when the WS closes on a healthy network (token rotation → 4401,
    // gateway restart → 1006, idle NAT drop, etc.). Pre-fix: the socket
    // stayed Closed forever and the maintainer would have to revisit Connection
    // settings to reconnect. Matches the desktop's Math.min(15_000,
    // 1_000 * 2 ** Math.min(reconnectAttempt, 4)) shape in
    // use-gateway-boot.ts:179-191.
    scope.launch {
      var attempt = 0
      jsonRpcClient.connectionState.collect { state ->
        when (state) {
          ConnectionState.Open -> attempt = 0
          ConnectionState.Closed, ConnectionState.Error -> if (dashboardEnabled.value) {
            attempt = (attempt + 1).coerceAtMost(4)
            val delayMs = (1_000L shl (attempt - 1)).coerceAtMost(15_000L)
            transportLog.add(
              TransportLogLevel.INFO,
              "Dashboard auto-reconnect in ${delayMs / 1000}s (attempt $attempt)",
            )
            delay(delayMs)
            if (jsonRpcClient.connectionState.value != ConnectionState.Open) {
              connectDashboard()
            }
          }
          else -> {}
        }
      }
    }
    scope.launch {
      var attempt = 0
      pluginJsonRpcClient.connectionState.collect { state ->
        when (state) {
          ConnectionState.Open -> attempt = 0
          ConnectionState.Closed, ConnectionState.Error -> if (marmaladeEnabled.value) {
            attempt = (attempt + 1).coerceAtMost(4)
            val delayMs = (1_000L shl (attempt - 1)).coerceAtMost(15_000L)
            transportLog.add(
              TransportLogLevel.INFO,
              "Plugin auto-reconnect in ${delayMs / 1000}s (attempt $attempt)",
            )
            delay(delayMs)
            if (pluginJsonRpcClient.connectionState.value != ConnectionState.Open) {
              connectPlugin()
            }
          }
          else -> {}
        }
      }
    }

    // Mic ownership is the single source of truth for the voice-wake status
    // shown to the user. Replaces the old ACTION_PAUSE/RESUME_HOTWORD
    // broadcasts that flipped chatMicActive (see ADR 0008):
    //  - chatMicActive feeds _voiceWakeStatusText below (in-app UI): true
    //    while an STT consumer holds the mic, so the status reads "Paused".
    //  - _voiceWakeIsListening feeds the foreground-service notification's
    //    "Listening / Paused" suffix: true only while KWS owns the mic.
    scope.launch {
      MicOwnershipManager.getInstance(appContext).currentOwner.collect { owner ->
        chatMicActive.value =
          owner == MicOwner.VOICE_SESSION || owner == MicOwner.INLINE_STT
        _voiceWakeIsListening.value = owner == MicOwner.KWS
      }
    }

    scope.launch {
      combine(
        voiceWakeMode,
        isForeground,
        externalAudioCaptureActive,
        wakeWords,
        chatMicActive,
      ) { mode: VoiceWakeMode, foreground: Boolean, externalAudio: Boolean, words: List<String>, chatMic: Boolean ->
        Quint(mode, foreground, externalAudio, words, chatMic)
      }
        .distinctUntilChanged()
        .collect { (mode, foreground, externalAudio, _, chatMic) ->
          val shouldListen =
            when (mode) {
              VoiceWakeMode.Off -> false
              VoiceWakeMode.Foreground -> foreground
              VoiceWakeMode.Always -> true
            } && !externalAudio && !chatMic

          _voiceWakeStatusText.value = if (!shouldListen) {
            if (mode == VoiceWakeMode.Off) "Off" else "Paused"
          } else if (!hasRecordAudioPermission()) {
            "Microphone permission required"
          } else {
            "Active"
          }
        }
    }
  }

  fun setForeground(value: Boolean) {
    _isForeground.value = value
  }

  fun setDisplayName(value: String) {
    prefs.setDisplayName(value)
  }

  fun setCameraEnabled(value: Boolean) {
    prefs.setCameraEnabled(value)
    if (isConnected.value) _capabilitiesChangedSinceConnect.value = true
  }

  fun setLocationMode(mode: LocationMode) {
    prefs.setLocationMode(mode)
  }

  fun setLocationPreciseEnabled(value: Boolean) {
    prefs.setLocationPreciseEnabled(value)
  }

  fun setPreventSleep(value: Boolean) {
    prefs.setPreventSleep(value)
  }

  fun setWakeWords(words: List<String>) {
    prefs.setWakeWords(words)
    // marmalade-agent has no wake-word RPC; wake-word config is client-local.
    // The OpenClaw gateway-sync side-effect lived here; gone.
  }

  fun resetWakeWordsDefaults() {
    setWakeWords(SecurePrefs.defaultWakeWords)
  }

  fun setVoiceWakeMode(mode: VoiceWakeMode) {
    prefs.setVoiceWakeMode(mode)
  }

  fun setSmsEnabled(value: Boolean) {
    prefs.setSmsEnabled(value)
    if (isConnected.value) _capabilitiesChangedSinceConnect.value = true
  }

  fun setScreenRecordActive(value: Boolean) {
    _screenRecordActive.value = value
  }

  fun stopScreenRecording() {
    screenRecorder.stopRecording()
  }

  private val _lastCapabilityError = MutableStateFlow<String?>(null)
  val lastCapabilityError: StateFlow<String?> = _lastCapabilityError.asStateFlow()

  fun clearCapabilityError() {
    _lastCapabilityError.value = null
  }

  internal fun reportCapabilityError(msg: String) {
    _lastCapabilityError.value = msg
  }

  private fun hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
  }

  /**
   * Connect to the marmalade-agent dashboard's `/api/ws` JSON-RPC endpoint
   * (the same surface the desktop + web clients use). If the user has also
   * configured the Android plugin (for `node.invoke.*` device-tool callbacks
   * like alarms / intents), connect that socket in parallel.
   *
   * Idempotent per-socket: each client returns silently if its connect is
   * already in flight or its socket is already open. Failures surface via
   * [statusText] (chat, drives the Compose status chip) and the transport
   * log buffer (Debugging tab).
   */
  fun connectMarmalade() {
    connectDashboard()
    connectPlugin()
  }

  /**
   * M2 device pairing: redeem a `marmalade pair` setup code for this
   * install's durable per-device bearer token, persist the connection
   * (dashboard URL + token), and connect.
   *
   * Uses a THROWAWAY socket to the setup code's URL — the daemon lets an
   * unauthenticated connection call ONLY `pairing.claim`, and sends no
   * `gateway.ready` until it authenticates, so the claim goes out right
   * after the WS opens. The main [jsonRpcClient] stays untouched until the
   * claim succeeds; on failure the existing connection config is unchanged.
   *
   * @return the verified device id the daemon bound the token to.
   * @throws Exception transport/claim failure (message is user-renderable).
   */
  override suspend fun claimPairing(setup: app.marmalade.android.rpc.SetupCode): String {
    require(!setup.isExpired()) { "Setup code expired — run `marmalade pair` again" }
    val claimClient = JsonRpcClient(
      httpClient = httpClient,
      logger = object : JsonRpcClient.Logger {
        override fun warn(message: String) = transportLog.add(TransportLogLevel.WARN, "pairing: $message")
        override fun info(message: String) = transportLog.add(TransportLogLevel.DEBUG, "pairing: $message", verbose = true)
      },
    )
    try {
      transportLog.add(TransportLogLevel.INFO, "Pairing: claiming against ${setup.dashboardHttpUrl()}")
      claimClient.connect(
        wsUrl = setup.url,
        extraHeaders = mapOf("X-Marmalade-Device-Id" to deviceIdentity.deviceId),
      )
      val claimed = MarmaladeRpc(claimClient).pairingClaim(
        token = setup.token,
        deviceId = deviceIdentity.deviceId,
        platform = "android",
      )
      // Persist ONLY after a successful claim, then reconnect the real client.
      jsonRpcClient.close()
      prefs.setDashboardUrl(setup.dashboardHttpUrl())
      prefs.setDashboardToken(claimed.device_token)
      prefs.setDashboardEnabled(true)
      transportLog.add(TransportLogLevel.INFO, "Pairing: claimed as ${claimed.device_id} — reconnecting")
      connectMarmalade()
      return claimed.device_id
    } finally {
      claimClient.close()
    }
  }

  private fun connectDashboard() {
    val url = dashboardUrl.value.trim()
    val token = dashboardToken.value.trim()
    if (url.isEmpty()) {
      transportLog.add(TransportLogLevel.WARN, "Cannot connect: dashboard URL not configured")
      return
    }
    if (token.isEmpty()) {
      transportLog.add(TransportLogLevel.WARN, "Cannot connect: dashboard token not set")
      return
    }
    val current = jsonRpcClient.connectionState.value
    if (current == ConnectionState.Connecting || current == ConnectionState.Open) {
      transportLog.add(TransportLogLevel.DEBUG, "connectDashboard: already $current — no-op", verbose = true)
      return
    }
    val wsUrl = buildWsUrl(url, token)
    scope.launch {
      try {
        transportLog.add(TransportLogLevel.INFO, "Connecting to dashboard $url")
        jsonRpcClient.connect(
          wsUrl = wsUrl,
          extraHeaders = mapOf("X-Marmalade-Device-Id" to deviceIdentity.deviceId),
        )
        transportLog.add(TransportLogLevel.INFO, "Dashboard connected")
        sendHello(token)
      } catch (t: Throwable) {
        transportLog.add(
          TransportLogLevel.ERROR,
          "Dashboard connect failed: ${t.message ?: t.javaClass.simpleName}",
        )
      }
    }
  }

  /**
   * Negotiated v1 `hello` (marmaladed handshake): declares this install's
   * stable device identity + platform + tz offset so the daemon stamps every
   * message origin from the authenticated connection, and negotiates server
   * features ("stable-ids", "subscribe"). Best-effort: a legacy fork gateway
   * has no hello route — log and continue on the legacy path.
   */
  private suspend fun sendHello(token: String) {
    try {
      val result = marmaladeRpc.hello(
        deviceId = deviceIdentity.deviceId,
        platform = "android",
        tzOffsetMinutes = java.util.TimeZone.getDefault()
          .getOffset(System.currentTimeMillis()) / 60_000,
        // "secrets" gates the daemon's secret-entry flow (handshake.ts
        // ClientCapability): it only pushes `secret.request` to subscribers
        // that declared it, and DENIES a parked request the moment the last
        // capable client disconnects. Declaring it is a promise that this
        // client renders a real secure input — masked, FLAG_SECURE, no
        // autofill, no clipboard, no draft persistence (see MainActivity +
        // SecretCard). Do not add it to a host that can't keep that promise.
        capabilities = listOf("streaming", "stable-ids", "secrets"),
        clientName = "marmalade-android",
        clientVersion = app.marmalade.android.BuildConfig.VERSION_NAME,
        token = token,
      )
      _serverFeatures.value = result.features
      result.server?.let { _serverName.value = it.name }
      transportLog.add(
        TransportLogLevel.INFO,
        "hello ok: server=${result.server?.name} ${result.server?.version ?: ""} " +
          "features=${result.features.joinToString(",")}",
      )
    } catch (t: Throwable) {
      _serverFeatures.value = emptyList()
      transportLog.add(
        TransportLogLevel.WARN,
        "hello failed (legacy gateway?): ${t.message ?: t.javaClass.simpleName}",
      )
    }
  }

  private fun connectPlugin() {
    val url = marmaladeUrl.value.trim()
    val token = marmaladeToken.value.trim()
    if (url.isEmpty() || token.isEmpty()) {
      // Plugin is optional — chat works without it. Skip silently when not
      // configured; the user only needs it to expose node.invoke device tools.
      return
    }
    val current = pluginJsonRpcClient.connectionState.value
    if (current == ConnectionState.Connecting || current == ConnectionState.Open) {
      transportLog.add(TransportLogLevel.DEBUG, "connectPlugin: already $current — no-op", verbose = true)
      return
    }
    val wsUrl = buildWsUrl(url, token)
    scope.launch {
      try {
        transportLog.add(TransportLogLevel.INFO, "Connecting to plugin $url")
        pluginJsonRpcClient.connect(
          wsUrl = wsUrl,
          extraHeaders = mapOf("X-Marmalade-Device-Id" to deviceIdentity.deviceId),
        )
        transportLog.add(TransportLogLevel.INFO, "Plugin connected")
      } catch (t: Throwable) {
        transportLog.add(
          TransportLogLevel.ERROR,
          "Plugin connect failed: ${t.message ?: t.javaClass.simpleName}",
        )
      }
    }
  }

  /** Strip scheme + path from a dashboard URL to produce a display name
   *  like `host.example.ts.net:8443`. */
  private fun extractHostFromUrl(httpUrl: String): String? {
    val trimmed = httpUrl.trim()
    if (trimmed.isEmpty()) return null
    return trimmed
      .removePrefix("https://")
      .removePrefix("http://")
      .substringBefore('/')
      .takeIf { it.isNotEmpty() }
  }

  /**
   * Turn a marmalade dashboard URL (e.g. `http://host:9119` or
   * `https://m.example.com`) into the matching WS endpoint with a
   * URL-encoded token query param. Mirrors the desktop electron client's
   * gateway-ws-probe shape.
   */
  private fun buildWsUrl(httpUrl: String, token: String): String {
    // Strip any pasted path so a URL copied straight from the dashboard's
    // browser tab (e.g. ".../sessions") doesn't suffix /api/ws onto it
    // and 403 against an unrelated route. Keep scheme + host + port only.
    val schemeAndRest = httpUrl.trim()
      .replace(Regex("^http://"), "ws://")
      .replace(Regex("^https://"), "wss://")
    val schemeEnd = schemeAndRest.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val afterScheme = schemeAndRest.substring(schemeEnd)
    val authority = afterScheme.substringBefore('/').substringBefore('?')
    val scheme = schemeAndRest.substring(0, schemeEnd)
    val encoded = URLEncoder.encode(token, "UTF-8")
    return "$scheme$authority/api/ws?token=$encoded"
  }

  /**
   * Tear down the transport + handler resources. Called when the user
   * forgets the connection or the runtime is being recycled.
   */
  fun disconnect() {
    jsonRpcClient.close()
    pluginJsonRpcClient.close()
    motionHandler.close()
  }

  /** Cycle just the dashboard socket — close + reconnect — leaving the
   *  plugin's socket alone. Used by ConnectionSettings on a save where
   *  only the dashboard creds changed. */
  fun reconnectDashboard() {
    jsonRpcClient.close()
    connectDashboard()
  }

  /** Cycle just the plugin socket. Counterpart of [reconnectDashboard]. */
  fun reconnectPlugin() {
    pluginJsonRpcClient.close()
    connectPlugin()
  }

  // ── Chat delegate methods ─────────────────────────────────────

  fun loadChat(sessionKey: String) {
    val key = sessionKey.trim().ifEmpty { resolveMainSessionKey() }
    chat.load(key)
  }

  fun refreshChat() {
    chat.refresh()
  }

  fun refreshChatSessions(limit: Int? = null) {
    chat.refreshSessions(limit = limit)
  }

  fun setChatThinkingLevel(level: String) {
    chat.setThinkingLevel(level)
  }

  fun switchChatSession(sessionKey: String) {
    chat.switchSession(sessionKey)
  }

  fun abortChat() {
    chat.abort()
  }

  fun sendChat(
    message: String,
    thinking: String,
    attachments: List<OutgoingAttachment>,
    voiceOrigin: Boolean = false,
    truncateBeforeUserOrdinal: Int? = null,
  ) {
    chat.sendMessage(
      message = message,
      thinkingLevel = thinking,
      attachments = attachments,
      voiceOrigin = voiceOrigin,
      truncateBeforeUserOrdinal = truncateBeforeUserOrdinal,
    )
  }

  /**
   * Create a new gateway session with the given [title] and persist the
   * returned id in Room. Returns the canonical local key (= the daemon's
   * immutable session_id). Throws on RPC failure so the caller can surface
   * the error to the UI without inserting a half-row.
   *
   * K1 semantics: marmaladed's session_id is the stable persistent
   * identifier; we use it as the Room row key so
   * [ChatController.ensureServerSessionId] finds an existing row on the first
   * chat open and skips a redundant session.create call.
   */
  suspend fun createGatewaySession(
    title: String,
    agentId: String? = null,
    cwd: String? = null,
  ): String {
    val created = marmaladeRpc.sessionCreate(
      title = title.ifBlank { "New Chat" },
      cwd = cwd,
    )
    val sid = created.session_id
    val canonicalKey = sid
    val now = System.currentTimeMillis()
    chatDao.insertSession(
      app.marmalade.android.data.local.entity.SessionEntity(
        key = canonicalKey,
        gatewaySessionId = sid,
        displayName = title.ifBlank { "New Chat" },
        agentId = agentId,
        createdAt = now,
        updatedAt = now,
        cwd = cwd?.takeIf { it.isNotBlank() },
      ),
    )
    return canonicalKey
  }

  /**
   * Rename a session on the server. Wraps [MarmaladeRpc.sessionTitle].
   * Returns true on success, false on RPC error (logged at WARN). The
   * older OpenClaw client called `sessions.patch` — a method that doesn't
   * exist server-side, so every rename silently failed; this is the
   * corrected path.
   */
  suspend fun patchChatSession(key: String, label: String): Boolean {
    // Resolve the local key to the gateway session_id — the server's
    // session.title handler looks up sessions by the gateway-side id, not
    // the local "chat-yyyymmdd-…" key. Pre-fix every rename silently
    // returned 4001 "session not found" and the error was logged at WARN
    // only; the user saw nothing.
    val serverId = chatDao.getSessionByKey(key)?.gatewaySessionId
    if (serverId.isNullOrBlank()) {
      transportLog.add(
        TransportLogLevel.WARN,
        "Cannot rename $key: no gateway session_id resolved yet",
      )
      return false
    }
    return try {
      marmaladeRpc.sessionTitle(sessionId = serverId, title = label)
      transportLog.add(TransportLogLevel.INFO, "Session renamed: $key ($serverId) → $label")
      true
    } catch (t: Throwable) {
      transportLog.add(
        TransportLogLevel.WARN,
        "session.title failed for $serverId: ${t.message ?: t.javaClass.simpleName}",
      )
      false
    }
  }

  /**
   * Delete a session on the server. Wraps [MarmaladeRpc.sessionDelete].
   */
  suspend fun deleteChatSession(key: String): Boolean {
    return try {
      marmaladeRpc.sessionDelete(sessionId = key)
      transportLog.add(TransportLogLevel.INFO, "Session deleted: $key")
      true
    } catch (t: Throwable) {
      transportLog.add(
        TransportLogLevel.WARN,
        "session.delete failed for $key: ${t.message ?: t.javaClass.simpleName}",
      )
      false
    }
  }

  /**
   * Send a chat message to a specific session — used by the notification
   * quick-reply ([ChatNotificationReceiver]) and the assistant-session
   * voice path ([MarmaladeVoiceSession]). Fire-and-forget over [MarmaladeRpc.promptSubmit];
   * the response stream comes back through [JsonRpcClient.events] and is
   * picked up by the chat layer once Task #11 rewires MessageStream.
   */
  fun sendChatToSession(sessionKey: String, message: String, voiceOrigin: Boolean = false) {
    scope.launch {
      try {
        // Forward voiceOrigin as source=voice — was previously accepted but
        // silently dropped. NOTE: this sends `sessionKey` straight to
        // prompt.submit, which only works if the gateway already has that
        // session live. The voice popup no longer uses this path (it routes
        // through ChatController.sendMessage for proper live-id resolution);
        // the notification quick-reply is the remaining caller.
        marmaladeRpc.promptSubmit(
          sessionId = sessionKey,
          text = message,
          source = if (voiceOrigin) "voice" else null,
        )
      } catch (t: Throwable) {
        transportLog.add(
          TransportLogLevel.WARN,
          "sendChatToSession failed for $sessionKey: ${t.message ?: t.javaClass.simpleName}",
        )
      }
    }
  }

  private fun triggerCameraFlash() {
    // Token is used as a pulse trigger; value doesn't matter as long as it changes.
    _cameraFlashToken.value = SystemClock.elapsedRealtimeNanos()
  }

  private fun showCameraHud(message: String, kind: CameraHudKind, autoHideMs: Long? = null) {
    val token = cameraHudSeq.incrementAndGet()
    _cameraHud.value = CameraHudState(token = token, kind = kind, message = message)

    if (autoHideMs != null && autoHideMs > 0) {
      scope.launch {
        kotlinx.coroutines.delay(autoHideMs)
        if (_cameraHud.value?.token == token) _cameraHud.value = null
      }
    }
  }

  /**
   * Called when a chat event arrives for a session other than the currently viewed one.
   * Checks mute status and foreground/session state via NotificationTriggerLogic,
   * then shows a notification with the actual message text if appropriate.
   */
  private fun handleOtherSessionMessage(sessionKey: String, messageText: String) {
    scope.launch {
      // Check mute status from Room
      val isMuted = try {
        chatDao.isSessionMuted(sessionKey) ?: false
      } catch (_: Throwable) {
        false
      }

      // Use pipeline logic (single authoritative decision point) to decide whether to show notification
      val shouldShow = NotificationPipelineLogic.shouldFireNotification(
        state = "final",
        text = messageText,
        isForeground = _isForeground.value,
        viewingSessionKey = chat.sessionKey.value,
        eventSessionKey = sessionKey,
        isMuted = isMuted,
      )
      if (!shouldShow) return@launch

      // Look up a friendly display name for the session
      val sessions = chat.sessions.value
      val sessionEntry = sessions.firstOrNull { it.key == sessionKey }
      val displayName = sessionEntry?.displayName
        ?: friendlySessionName(sessionKey)

      // Truncate notification text to a reasonable length
      val truncated = if (messageText.length > 500) messageText.take(500) + "..." else messageText

      ChatNotificationHelper.showChatNotification(
        context = appContext,
        sessionKey = sessionKey,
        sessionDisplayName = displayName,
        messageText = truncated,
        senderName = "Assistant",
      )
    }
  }

  /**
   * Fires an OS notification for a clarify/approval/secret/sudo prompt that arrived
   * in a session the user is not currently viewing (sessionKey != chat.sessionKey).
   *
   * Mirrors [handleOtherSessionMessage]: resolves the friendly display name from the
   * sessions list and delegates to [ChatNotificationHelper.showPromptNotification].
   * Muted sessions are silenced; the foreground check is intentionally omitted here
   * because prompt requests always need user attention regardless of foreground state.
   */
  private fun handlePromptNotification(
    sessionKey: String,
    prompt: app.marmalade.android.chat.PendingPrompt,
  ) {
    scope.launch {
      val isMuted = try {
        chatDao.isSessionMuted(sessionKey) ?: false
      } catch (_: Throwable) {
        false
      }
      if (isMuted) return@launch

      val sessions = chat.sessions.value
      val sessionEntry = sessions.firstOrNull { it.key == sessionKey }
      val displayName = sessionEntry?.displayName ?: friendlySessionName(sessionKey)

      ChatNotificationHelper.showPromptNotification(
        context = appContext,
        sessionKey = sessionKey,
        sessionDisplayName = displayName,
        prompt = prompt,
      )
    }
  }
}
