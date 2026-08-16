package app.marmalade.desktop

import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.MAIN_SESSION_PLACEHOLDER
import app.marmalade.android.chat.PendingPrompt
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.chat.messages.MessageStream
import app.marmalade.android.chat.messages.OutboxDrainer
import app.marmalade.android.chat.messages.marmaladeRpcAdapter
import app.marmalade.android.chat.messages.text
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.buildDesktopDatabase
import app.marmalade.android.rpc.ConnectionState
import app.marmalade.android.rpc.JsonRpcClient
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.ui.chat.friendlySessionName
import app.marmalade.desktop.notify.NotificationRateLimiter
import app.marmalade.desktop.notify.Notifier
import app.marmalade.desktop.notify.NotifySendNotifier
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * The desktop composition root — the Phase 2 spike's answer to :app's
 * `MarmaladeRuntime`, cut down to exactly what a session list and a chat pane
 * need.
 *
 * What it drops relative to the Android runtime (all of it deliberately, this
 * is a walking skeleton): the plugin socket + `node.invoke` device tools, voice
 * / wake word / TTS, the foreground service, pairing, the terminal controller,
 * and every Android-system handler. What survives is the spine — one WS to the
 * daemon (with the same reconnect backoff Android uses), the shared Room store,
 * `MessageStream`, `OutboxDrainer`, `ChatController` — plus desktop
 * notifications ([Notifier]), which the tray made necessary: an app that hides
 * itself has to be able to speak up.
 *
 * **Auth:** none. The daemon trusts loopback remotes as the local user
 * (`packages/daemon/src/gateway.ts` — `LOOPBACK_HOSTS`), so a desktop client on
 * the same box needs no device token. A remote desktop client would have to
 * pair; that is out of scope here.
 */
class DesktopRuntime(
    /** Daemon HTTP base. The daemon binds 127.0.0.1:9130 by default. */
    private val daemonHttpUrl: String = DEFAULT_DAEMON_URL,
    /**
     * SQLite file for the shared Room store. Defaults to `~/.marmalade-desktop/`;
     * the smoke test points this at a temp file so it never touches real state.
     */
    dbFilePath: String = defaultDbFilePath(),
    private val log: (String) -> Unit = { println("[marmalade-desktop] $it") },
    /**
     * Where agent-activity notifications go. Defaults to the `notify-send`
     * backend; tests and headless runs can hand in a spy or a no-op.
     */
    private val notifier: Notifier = NotifySendNotifier(log = log),
) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    val jsonRpcClient: JsonRpcClient = JsonRpcClient(
        httpClient = httpClient,
        json = json,
        logger = object : JsonRpcClient.Logger {
            override fun warn(message: String) = log("WARN rpc: $message")
            override fun info(message: String) = Unit
        },
    )

    val rpc: MarmaladeRpc = MarmaladeRpc(jsonRpcClient)

    private val database: AppDatabase = buildDesktopDatabase(dbFilePath)
    private val chatDao = database.chatDao()

    /** Server-advertised feature names from the `hello` handshake. */
    private val _serverFeatures = MutableStateFlow<List<String>>(emptyList())
    val serverFeatures: StateFlow<List<String>> = _serverFeatures.asStateFlow()

    /** THE daemon-managed singleton main session, resolved by `session.main`. */
    private val _mainSessionKey = MutableStateFlow(MAIN_SESSION_PLACEHOLDER)
    val mainSessionKey: StateFlow<String> = _mainSessionKey.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = jsonRpcClient.connectionState

    // Constructed before connect() so its consumer coroutine is hot by the time
    // the first frame lands — MessageStream's SharedFlow has replay=0.
    private val messageStream: MessageStream = MessageStream(
        jsonRpcClient,
        scope,
        chatDao,
        json,
        logWarn = { log("WARN MessageStream: $it") },
    )

    private val outboxDrainer: OutboxDrainer = OutboxDrainer(
        chatDao = chatDao,
        transport = marmaladeRpcAdapter(rpc),
        scope = scope,
        persistence = messageStream.persistence,
        logWarn = { log("WARN OutboxDrainer: $it") },
    ).also { it.start() }

    /**
     * True while the app window holds OS focus. Read by [ChatController]'s
     * `isForeground` gate, which decides whether a turn finishing on the bound
     * session may stamp this device's `session.seen` cursor: a turn that lands
     * while the maintainer is in another window was NOT read, and must stay unread for the
     * phone (the cursor is shared across clients).
     *
     * Written from the AWT focus listener on the Compose window (a Swing/EDT
     * thread), read from the chat coroutines — hence @Volatile. Defaults to
     * true so a headless runtime (tests) behaves like the Android default.
     */
    @Volatile
    var windowFocused: Boolean = true

    /**
     * `host = null` — the headless path. Every [app.marmalade.android.chat.ChatHost]
     * use degrades to a no-op, which for the desktop spike means: no voice-action
     * dispatch, no usage-snapshot cache, no notification dismissal. All three are
     * Android-platform edges with no desktop analogue yet.
     */
    val chat: ChatController = ChatController(
        scope = scope,
        rpc = rpc,
        messageStream = messageStream,
        outboxDrainer = outboxDrainer,
        json = json,
        chatDao = chatDao,
        isForeground = { windowFocused },
        host = null,
        // An assistant bubble finalizing in a session the user is not bound to
        // — the desktop half of Android's `handleOtherSessionMessage`. Fires
        // whether or not the window is focused: the user is looking at a
        // DIFFERENT session, so this one moved unseen either way.
        onOtherSessionMessage = { sessionKey, _, text ->
            notifySession(sessionKey, text)
        },
        // clarify / approval / secret / sudo raised in a session that is not
        // on screen. Same reasoning, higher stakes — the turn is BLOCKED until
        // someone answers it.
        onPromptNotification = { sessionKey, prompt ->
            notifySession(sessionKey, promptBody(prompt))
        },
        logWarn = { log("WARN chat: $it") },
        mainSessionKey = _mainSessionKey,
    )

    /** One notification per session per few seconds — see the class. */
    private val notificationRate = NotificationRateLimiter()

    /** The socket lifecycle loop, so [close] can stop it retrying. */
    private var lifecycleJob: Job? = null

    /**
     * Own the socket for the process lifetime: connect now, redo the post-open
     * work on every open, and reconnect with backoff whenever it drops.
     * Idempotent — a second call is a no-op.
     *
     * A daemon restart (or `marmaladed` being started after the client) must
     * heal without user action, so the retry loop is driven off
     * [JsonRpcClient.connectionState] rather than off the return of [connect]:
     * a socket that opens and later dies produces no exception anywhere, only a
     * Closed transition.
     */
    fun start() {
        if (lifecycleJob != null) return
        lifecycleJob = scope.launch {
            // Backoff loop + post-open work share one collector so the attempt
            // counter can be plain local state: StateFlow collection is
            // sequential, so nothing else can mutate it mid-await.
            launch {
                var attempt = ReconnectBackoff.resetAttempt()
                jsonRpcClient.connectionState.collect { state ->
                    when (state) {
                        ConnectionState.Open -> {
                            attempt = ReconnectBackoff.resetAttempt()
                            runPostOpen()
                        }

                        ConnectionState.Closed, ConnectionState.Error -> {
                            attempt = ReconnectBackoff.nextAttempt(attempt)
                            val delayMs = ReconnectBackoff.delayMs(attempt)
                            log("reconnecting in ${delayMs / 1000}s (attempt $attempt)")
                            delay(delayMs)
                            // The socket may have come back while we waited
                            // (StateFlow conflates, so the Open we'd otherwise
                            // see next could be stale by then).
                            if (jsonRpcClient.connectionState.value != ConnectionState.Open) {
                                connectTransport()
                            }
                        }

                        ConnectionState.Idle, ConnectionState.Connecting -> Unit
                    }
                }
            }
            launch { watchBoundTurnCompletions() }
            // The collector only reacts to transitions, and the initial state is
            // Idle — something has to strike the first match.
            connectTransport()
        }
    }

    /**
     * The notification case [ChatController] has no callback for: a turn
     * finishing in the session the user IS bound to, while the window is not
     * focused (behind another window, or hidden to the tray).
     *
     * `onOtherSessionMessage` deliberately skips it — from the controller's
     * point of view the user is looking right at it — and the `isForeground`
     * gate it feeds only decides whether to stamp `session.seen`. So the exact
     * turn the gate declares UNREAD is the one nothing tells the user about.
     * Derived here from observable state rather than by adding a callback to
     * `:shared`, which this milestone leaves untouched.
     *
     * [ChatController.isStreaming] going true→false is the signal, with two
     * guards, because that flag also drops for reasons that are not "the turn
     * finished in front of you":
     *  - the bound session must be the same one the run started in (a session
     *    switch resets the flag — and would notify about the wrong session);
     *  - the transcript must actually end in a finalized assistant message
     *    (a hydrate that finds `run_state != running` clears the flag with
     *    nothing new to say).
     */
    private suspend fun watchBoundTurnCompletions() {
        var streamingKey: String? = null
        chat.isStreaming.collect { streaming ->
            if (streaming) {
                streamingKey = chat.sessionKey.value
                return@collect
            }
            val startedIn = streamingKey ?: return@collect
            streamingKey = null
            if (windowFocused) return@collect
            if (startedIn != chat.sessionKey.value) return@collect
            val last = chat.messages.value.lastOrNull() ?: return@collect
            if (last.role != ChatRole.Assistant || last.pending) return@collect
            notifySession(startedIn, last.text())
        }
    }

    /**
     * Open the socket, run the v1 handshake, bind the main session, and pull the
     * session list. Throws on transport failure — the caller surfaces it.
     *
     * The one-shot path, for callers that want the failure (the daemon smoke
     * test). [start] deliberately does NOT use it: it opens the transport and
     * lets the connection-state collector run [runPostOpen], so a socket that
     * re-opens on its own gets the same treatment as the first one.
     */
    suspend fun connect() {
        openSocket()
        runPostOpen()
    }

    /** Transport only — everything above the socket is [runPostOpen]'s job. */
    private suspend fun openSocket() {
        val wsUrl = daemonHttpUrl.trim()
            .replace(Regex("^http://"), "ws://")
            .replace(Regex("^https://"), "wss://")
            .trimEnd('/') + "/api/ws"
        log("connecting to $wsUrl")
        jsonRpcClient.connect(wsUrl)
        log("connected")
    }

    /**
     * Retry-loop entry: never throws (a thrown connect must land the loop back
     * in backoff, not kill it) and no-ops while a connect is already in flight
     * or live — mirrors Android's `connectDashboard` guard, which exists
     * because several triggers (state loop, initial kick) can race.
     */
    private suspend fun connectTransport() {
        val current = jsonRpcClient.connectionState.value
        if (current == ConnectionState.Connecting || current == ConnectionState.Open) {
            return
        }
        runCatching { openSocket() }
            .onFailure { log("WARN connect failed: ${it.message ?: it::class.simpleName}") }
    }

    /**
     * Everything that is per-SOCKET rather than per-process, so it runs on every
     * open: `hello` (the negotiated feature list belongs to this socket, not to
     * this client) and the main-session bind + list pull that the UI reads.
     *
     * Best-effort per step — a daemon that is up but slow to answer one of these
     * shouldn't cost us the connection.
     *
     * Re-attaching the bound session's event stream is NOT done here on purpose:
     * [ChatController] runs its own Open handler that re-enters `load()`, which
     * dedupes against a concurrent UI-driven hydration. Calling `refresh()`
     * alongside it would reintroduce the documented `hydrateFromServer` race
     * that orphans an in-flight `session.resume`.
     */
    private suspend fun runPostOpen() {
        runCatching {
            // Negotiated v1 hello. No `auth` block: loopback is trusted.
            rpc.hello(
                deviceId = DESKTOP_DEVICE_ID,
                platform = "desktop",
                tzOffsetMinutes = TimeZone.getDefault()
                    .getOffset(System.currentTimeMillis()) / 60_000,
                capabilities = listOf("streaming", "stable-ids"),
                clientName = CLIENT_NAME,
                clientVersion = CLIENT_VERSION,
            )
        }
            .onSuccess { hello ->
                _serverFeatures.value = hello.features
                log("hello ok: server=${hello.server?.name} features=${hello.features.joinToString(",")}")
            }
            .onFailure { log("WARN hello failed: ${it.message}") }

        runCatching { rpc.sessionMain().session_id }
            .onSuccess { mainId ->
                if (mainId.isNotBlank()) {
                    _mainSessionKey.value = mainId
                    chat.applyMainSessionKey(mainId)
                    log("main session: $mainId")
                }
            }
            .onFailure { log("WARN session.main failed: ${it.message}") }

        chat.refreshSessions()
    }

    /**
     * Fire one agent-activity notification for [sessionKey], titled with the
     * session the way the sidebar names it.
     *
     * Rate-limited per session (a single turn can raise a prompt, finalize a
     * bubble and complete within a second of itself) and silent on empty text
     * — a notification with no body says nothing the tray icon doesn't.
     */
    private fun notifySession(sessionKey: String, body: String) {
        val text = body.trim()
        if (text.isEmpty()) return
        if (!notificationRate.allow(sessionKey)) return
        notifier.notify(sessionTitle(sessionKey), text)
    }

    /** The sidebar's label for a session, falling back to the key itself. */
    private fun sessionTitle(sessionKey: String): String =
        chat.sessions.value.firstOrNull { it.key == sessionKey }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: friendlySessionName(sessionKey)

    /** A prompt's one-line body: its question, plus detail when it carries any. */
    private fun promptBody(prompt: PendingPrompt): String {
        val kind = prompt.kind.name.lowercase()
        val detail = prompt.detail?.takeIf { it.isNotBlank() }
        return listOfNotNull("[$kind] ${prompt.title}".trim(), detail).joinToString(" — ")
    }

    fun close() {
        // Before jsonRpcClient.close(), or the Closed transition it publishes
        // sends the loop straight into a reconnect for a runtime we're tearing
        // down.
        lifecycleJob?.cancel()
        lifecycleJob = null
        chat.close()
        jsonRpcClient.close()
        jsonRpcClient.shutdown()
        database.close()
        // The notify-send backend owns a thread; a Notifier that doesn't is
        // simply not AutoCloseable.
        (notifier as? AutoCloseable)?.close()
    }

    companion object {
        const val DEFAULT_DAEMON_URL: String = "http://127.0.0.1:9130"

        private const val CLIENT_NAME = "marmalade-desktop"
        private const val CLIENT_VERSION = "0.1.0-spike"

        /**
         * Stable-per-machine device id. The daemon stamps message origins from
         * the authenticated connection, so this only needs to be stable, not
         * secret. A real desktop client would persist a minted UUID the way
         * Android's `DeviceIdentity` does.
         */
        private val DESKTOP_DEVICE_ID: String =
            "desktop-" + (System.getenv("HOSTNAME") ?: "local")

        fun defaultDbFilePath(): String {
            val dir = File(System.getProperty("user.home"), ".marmalade-desktop")
            dir.mkdirs()
            return File(dir, "marmalade.db").absolutePath
        }
    }
}
