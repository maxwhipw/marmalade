package app.marmalade.android.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * JSON-RPC 2.0 client over WebSocket for marmalade-agent's `/api/ws` endpoint.
 *
 * Port of the desktop primitive at
 * `hermes-agent upstream: apps/shared/src/json-rpc-gateway.ts` with the
 * same wire dialect the web client uses at
 * `hermes-agent upstream: web/src/lib/gatewayClient.ts`.
 *
 * ## Scope: a single-socket primitive
 *
 * This client knows nothing about:
 *
 * - **Authentication.** The `wsUrl` you pass to [connect] carries the auth as
 *   `?token=...` (loopback session token) or `?ticket=...` (single-use OAuth
 *   ticket, 30-second TTL). Mint the URL elsewhere — a future
 *   `WsUrlResolver` matches desktop's
 *   `apps/desktop/src/lib/gateway-ws-url.ts:resolveGatewayWsUrl`.
 * - **Reconnect with backoff.** A future wrapper handles that — desktop's
 *   pattern lives in `apps/desktop/src/store/gateway.ts:Secondary`.
 * - **Multi-host registries.** A future `JsonRpcClientRegistry` (matching
 *   desktop's primary + secondary-per-profile model) sits on top.
 *
 * ## Wire protocol
 *
 *   Request:  `{"jsonrpc":"2.0","id":<id>,"method":<str>,"params":{...}}`
 *   Response: `{"jsonrpc":"2.0","id":<id>,"result":<any>}`  or  `{...,"error":{code,message,data}}`
 *   Event:    `{"jsonrpc":"2.0","method":"event","params":{"type":...,"payload":...,"session_id":...}}`
 *
 * The server sends `gateway.ready` as the first frame after accept (see
 * `tui_gateway/ws.py:handle_ws`). To catch it, **start collecting [events]
 * BEFORE calling [connect]** — the desktop and web clients both rely on this
 * ordering (registered listeners before awaiting open).
 *
 * IDs on the wire are always strings (this client's convention); the server
 * treats them as opaque and echoes them back verbatim.
 *
 * ## Threading model
 *
 * OkHttp delivers callbacks on its dispatcher thread. The client:
 *
 * - **Resolves pending RPCs synchronously** on the OkHttp thread — fast,
 *   non-blocking, no extra hop.
 * - **Drains server-pushed events through a single-consumer [Channel]** so
 *   order is preserved end-to-end (Dispatchers.Default would not have
 *   guaranteed it) and back-pressure is bounded by [Options.eventsBufferCapacity]
 *   rather than by an unbounded launch fan-out.
 *
 * Connection-state transitions are serialised by a [Mutex]; late callbacks
 * from a closed socket are dropped via an epoch counter (mirrors the TS
 * `this.socket !== socket` guard).
 *
 * ## Cancellation
 *
 * If the coroutine calling [request] is cancelled, the pending map entry is
 * removed before the [CancellationException] propagates. No leak.
 *
 * ## Lifecycle / structured concurrency
 *
 * Pass [parentContext] (typically `viewModelScope.coroutineContext` or a
 * service scope) so the client's internal scope becomes a *child* of the
 * caller's job. Caller cancellation then cancels the event-pump and any
 * scope.launch children automatically — no manual [shutdown] required.
 * The internal scope adds a [SupervisorJob] so a failing event consumer
 * doesn't tear down the parent.
 *
 * ## Speculative features beyond the desktop primitive
 *
 * - [JsonRpcException.code] / [JsonRpcException.rpcError.data] surfaced
 *   (desktop only exposes `message`) so handlers can branch on specific
 *   server-defined failure shapes.
 * - [notify] — fire-and-forget JSON-RPC notification (the spec allows it; no
 *   marmalade-agent RPC needs it today, but it's a small addition and useful
 *   for future cancel-style signalling).
 * - Per-call [Duration] timeout overrides via [request]'s `timeout` arg.
 *
 * ## TODO (post-PR1)
 *
 * - `streamingRequest(method, params): Flow<GatewayEvent>` — issue the
 *   request, filter events by the returned session_id, cancel server-side on
 *   Flow cancellation. Useful for ergonomic chat consumers.
 * - `AutoCloseable` implementation once the lifecycle ownership convention
 *   settles in `JsonRpcClientRegistry`.
 * - Inline reified `request<T>` overload IF [MarmaladeRpc] turns out to not
 *   want to own deserialization centrally.
 */
// The webSocketFactory constructor + WebSocketFactory below are public (not
// internal): they are a deliberate test seam, and since this class now lives in
// :shared/jvmSharedMain (ADR 0011) while its tests stay in :app, `internal` would
// hide them across the module boundary. Default logger is NoOp — every production
// site passes an explicit Logger (see MarmaladeRuntime), and no android.* logger
// lives in :shared.
open class JsonRpcClient constructor(
    private val webSocketFactory: WebSocketFactory,
    private val json: Json = DefaultJson,
    private val options: Options = Options(),
    private val logger: Logger = Logger.NoOp,
    parentContext: CoroutineContext = EmptyCoroutineContext,
) {
    /** Production constructor: wraps an OkHttpClient's WebSocket factory. */
    constructor(
        httpClient: OkHttpClient,
        json: Json = DefaultJson,
        options: Options = Options(),
        logger: Logger = Logger.NoOp,
        parentContext: CoroutineContext = EmptyCoroutineContext,
    ) : this(
        webSocketFactory = OkHttpWebSocketFactory(httpClient),
        json = json,
        options = options,
        logger = logger,
        parentContext = parentContext,
    )

    data class Options(
        val connectTimeout: Duration = 15.seconds,
        // Audit-window default (was 120s). Drop to 20s so silent hangs
        // surface as TimeoutCancellationException quickly while we triage
        // the "RPC never returns" symptom on Pixel 8a. Revisit once the
        // root cause is fixed.
        val requestTimeout: Duration = 20.seconds,
        val requestIdPrefix: String = "a",
        /**
         * Channel capacity for the inbound-event pump. Frames in excess
         * back-pressure the OkHttp callback thread (a slow consumer eventually
         * blocks new frame parsing — preferable to dropping events silently
         * since streaming `message.delta` ordering is load-bearing). 1024 is
         * generous for fast token streaming.
         */
        val eventsBufferCapacity: Int = 1024,
    )

    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    open val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(
        replay = 0,
        // Buffer events when subscribers are slow (e.g. MessageStream's
        // per-event Room writes lag behind the wire pump during a hot
        // streaming burst). Without this, `_events.emit` suspends until
        // EVERY subscriber resumes — under Room contention the pump
        // stalls and the user sees nothing until subscribers catch up.
        // 256 is generous; OkHttp's eventChannel ahead of this gives
        // another 1024-frame buffer between socket and SharedFlow.
        extraBufferCapacity = 256,
    )

    /**
     * All server-pushed events. SharedFlow — multiple collectors are fine and
     * each sees every frame. **Start collecting BEFORE calling [connect]** to
     * catch the initial `gateway.ready` frame.
     */
    open val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val pending = ConcurrentHashMap<String, PendingCall>()
    private val nextId = AtomicLong(0)
    private val socketRef = AtomicReference<WebSocket?>(null)

    /** Bumps every close/error so listener callbacks from a stale socket are dropped. */
    private val epoch = AtomicLong(0)

    /** Serialises connect/close transitions so two coroutines can't half-set state. */
    private val lifecycleMutex = Mutex()

    /**
     * The [CompletableDeferred] a currently in-flight [connect] is awaiting,
     * or `null` when no connect is in progress. [close] completes this
     * exceptionally so a `close()` that lands while a connect() is suspended
     * on `openDeferred.await()` fails fast instead of hanging until
     * [Options.connectTimeout] elapses — mirrors the TS reference's timeout
     * handler dropping the half-open socket so the *next* connect() starts
     * clean, except here `close()` is the trigger, not just the timer.
     *
     * Deliberately NOT epoch-gated: close() must unblock the in-flight
     * connect() regardless of epoch, because close() itself is what bumps
     * the epoch that would otherwise make the Listener's onOpen/onFailure
     * silently no-op without ever completing this deferred.
     */
    private val inFlightOpen = AtomicReference<CompletableDeferred<Unit>?>(null)

    private val scope = CoroutineScope(
        parentContext + SupervisorJob(parentContext[Job]) + CoroutineName("JsonRpcClient")
    )

    /**
     * Inbound-event pump. OkHttp's callback offers parsed [GatewayEvent]s into
     * this channel; the pump coroutine drains them into [_events] in strict
     * order. Single consumer → no race; bounded buffer → bounded memory.
     */
    private val eventChannel = Channel<GatewayEvent>(capacity = options.eventsBufferCapacity)

    init {
        scope.launch {
            for (event in eventChannel) {
                _events.emit(event)
            }
        }
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    /**
     * Open the WebSocket and wait for the handshake. Throws on timeout or
     * transport failure.
     *
     * Idempotent: if already open with a live socket, returns immediately.
     * If a connect is already in flight, waits for it under the lifecycle
     * mutex (mirrors TS desktop's behaviour of silently short-circuiting).
     */
    suspend fun connect(wsUrl: String, extraHeaders: Map<String, String> = emptyMap()) = lifecycleMutex.withLock {
        if (_connectionState.value == ConnectionState.Open && socketRef.get() != null) {
            return@withLock
        }

        _connectionState.value = ConnectionState.Connecting
        val myEpoch = epoch.incrementAndGet()
        val request = Request.Builder().url(wsUrl).apply {
            extraHeaders.forEach { (k, v) -> header(k, v) }
        }.build()

        try {
            withTimeout(options.connectTimeout) {
                val openDeferred = CompletableDeferred<Unit>()
                inFlightOpen.set(openDeferred)
                val listener = Listener(myEpoch, openDeferred)
                val socket = webSocketFactory.create(request, listener)
                socketRef.set(socket)
                try {
                    openDeferred.await()
                } finally {
                    // Clear only if we're still the registered deferred — a
                    // racing close() may have already replaced/read this
                    // reference; compareAndSet avoids clobbering a newer
                    // connect()'s registration in that window.
                    inFlightOpen.compareAndSet(openDeferred, null)
                }
            }
            _connectionState.value = ConnectionState.Open
        } catch (timeout: TimeoutCancellationException) {
            cleanupAfterError(myEpoch)
            throw IOException("connect timed out after ${options.connectTimeout}", timeout)
        } catch (e: Throwable) {
            cleanupAfterError(myEpoch)
            throw e
        }
    }

    /**
     * Close the WebSocket and reject all in-flight RPCs with [IOException].
     * Idempotent. The client remains usable — call [connect] again to reopen.
     *
     * Synchronous state transition: the listener's `onClosed` will fire later
     * (after OkHttp's close handshake completes) but its epoch check short-
     * circuits it. The single source of truth for the post-close transition
     * is this method.
     *
     * If a [connect] is currently suspended awaiting the WS handshake,
     * bumping the epoch first makes its Listener's onOpen/onFailure silent
     * no-ops — so completing [inFlightOpen] exceptionally here is what
     * actually wakes the suspended connect() coroutine. Without this, that
     * connect() would hang until [Options.connectTimeout] and hold
     * [lifecycleMutex] the whole time, blocking any subsequent connect()
     * (e.g. reconnectDashboard calling close() then connect() right after).
     */
    fun close() {
        epoch.incrementAndGet()
        socketRef.getAndSet(null)?.close(NORMAL_CLOSURE, "client close")
        inFlightOpen.getAndSet(null)?.completeExceptionally(
            IOException("WebSocket closed by client during connect")
        )
        rejectAllPending(IOException("WebSocket closed by client"))
        _connectionState.value = ConnectionState.Closed
    }

    /**
     * Permanently shut down: close the socket, drain the event channel, and
     * cancel the internal scope. After [shutdown] the client is unusable.
     *
     * Best-effort: events queued in the channel between [close] and
     * `scope.cancel` may not flush.
     */
    fun shutdown() {
        close()
        eventChannel.close()
        scope.cancel()
    }

    // ── primary ops ─────────────────────────────────────────────────────────

    /**
     * Send a JSON-RPC request and await the result.
     *
     * Throws:
     * - [IOException] if not connected, send queue is full, or transport drops.
     * - [JsonRpcException] (which extends [IOException]) for server-side errors.
     * - [TimeoutCancellationException] after [timeout].
     * - [CancellationException] if the calling coroutine is cancelled; the
     *   pending map entry is removed first so the client doesn't leak.
     *
     * Returns the raw `result` field as [JsonElement]. Some RPCs return no
     * payload (`result: null`) — that surfaces as `null` here, which is
     * valid. Typed callers (the `MarmaladeRpc.kt` layer) deserialize per
     * method.
     */
    suspend fun request(
        method: String,
        params: JsonObject = EmptyParams,
        timeout: Duration = options.requestTimeout,
    ): JsonElement? {
        if (_connectionState.value != ConnectionState.Open) {
            throw IOException("gateway not connected (state=${_connectionState.value})")
        }
        val socket = socketRef.get() ?: throw IOException("gateway not connected")

        val id = "${options.requestIdPrefix}${nextId.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = PendingCall(id, method, deferred)

        return try {
            val frame = json.encodeToString(JsonObject.serializer(), buildFrame(id, method, params))
            logger.info("↑ request id=$id method=$method")
            val sent = socket.send(frame)
            if (!sent) {
                throw IOException("WebSocket send queue full (socket closing?)")
            }
            withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // TimeoutCancellationException IS a CancellationException, so it
            // must be caught before the general CancellationException clause
            // below — otherwise the diagnostic log never fires.
            pending.remove(id)
            logger.warn("↧ TIMEOUT id=$id method=$method after $timeout — server never replied")
            throw e
        } catch (ce: CancellationException) {
            pending.remove(id)
            throw ce
        } catch (e: Throwable) {
            pending.remove(id)
            throw e
        }
    }

    /**
     * Fire-and-forget JSON-RPC notification (no `id` → no response expected
     * from the server). Speculative: no marmalade-agent RPC needs it today,
     * but the wire spec allows it and it's a useful primitive to have for
     * future cancel-style signalling.
     *
     * Throws [IOException] if not connected or send queue is full — same
     * shape as [request] so error handling is uniform.
     */
    fun notify(method: String, params: JsonObject = EmptyParams) {
        if (_connectionState.value != ConnectionState.Open) {
            throw IOException("gateway not connected (state=${_connectionState.value})")
        }
        val socket = socketRef.get() ?: throw IOException("gateway not connected")
        val sent = socket.send(json.encodeToString(JsonObject.serializer(), buildFrame(null, method, params)))
        if (!sent) {
            throw IOException("WebSocket send queue full (notify dropped: $method)")
        }
    }

    // ── observation helpers ─────────────────────────────────────────────────

    /** Filtered event flow for one event type. Cold; multiple collectors fine. */
    fun eventsOfType(type: String): Flow<GatewayEvent> = events.filter { it.type == type }

    // ── internals ───────────────────────────────────────────────────────────

    private fun buildFrame(id: String?, method: String, params: JsonObject): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            put("params", params)
        }

    private inner class Listener(
        private val myEpoch: Long,
        private val openDeferred: CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (myEpoch != epoch.get()) return
            logger.info("WS onOpen epoch=$myEpoch http=${response.code}")
            openDeferred.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (myEpoch != epoch.get()) return
            handleFrame(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (myEpoch != epoch.get()) return
            handleFrame(bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (myEpoch != epoch.get()) return
            logger.warn("WS onFailure epoch=$myEpoch http=${response?.code} err=${t.javaClass.simpleName}: ${t.message}")
            val err = IOException("WebSocket failure: ${t.message}", t)
            if (!openDeferred.isCompleted) {
                openDeferred.completeExceptionally(err)
            }
            _connectionState.value = ConnectionState.Error
            rejectAllPending(err)
            socketRef.compareAndSet(webSocket, null)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (myEpoch != epoch.get()) return
            logger.warn("WS onClosing epoch=$myEpoch code=$code reason=$reason")
            // Acknowledge the close handshake so the server-driven close completes.
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (myEpoch != epoch.get()) return
            logger.warn("WS onClosed epoch=$myEpoch code=$code reason=$reason pending=${pending.size}")
            _connectionState.value = ConnectionState.Closed
            rejectAllPending(IOException("WebSocket closed (code=$code, reason=$reason)"))
            socketRef.compareAndSet(webSocket, null)
        }
    }

    private fun handleFrame(text: String) {
        logger.info("↓ frame ${text.take(200)}")
        val frame = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Throwable) {
            logger.warn("ignoring malformed frame (${e.message}): ${text.take(120)}")
            return
        }

        val idElement = frame["id"]
        if (idElement != null && idElement !is JsonNull) {
            val id = idElement.jsonPrimitive.contentOrNull
            if (id != null) {
                resolvePending(id, frame)
                return
            }
        }

        // Server-pushed event: { "method": "event", "params": GatewayEvent }
        val method = frame["method"]?.jsonPrimitive?.contentOrNull
        if (method != "event") return
        val paramsElement = frame["params"] ?: return
        val event = runCatching {
            json.decodeFromJsonElement(GatewayEvent.serializer(), paramsElement)
        }.getOrElse {
            logger.warn("ignoring event with bad params: ${it.message}")
            return
        }
        // Channel preserves order end-to-end. When the buffer is full, block
        // the OkHttp callback thread on a suspending send — OkHttp serialises
        // callbacks per WS, so the next frame won't be parsed until this one
        // is queued. That back-pressures the wire (TCP window closes) and
        // preserves order without unbounded memory. Dropping frames here
        // would corrupt the streamed conversation; spawning a `scope.launch`
        // would race other launches and re-order frames in flight.
        val result = eventChannel.trySend(event)
        if (result.isFailure && !result.isClosed) {
            kotlinx.coroutines.runBlocking { eventChannel.send(event) }
        }
    }

    private fun resolvePending(id: String, frame: JsonObject) {
        val call = pending.remove(id)
        if (call == null) {
            // Silent drops here are a smoking gun for id-mismatch bugs —
            // log so it's visible during the audit window. pending.size
            // included for context (e.g. is the map empty or just keyed
            // differently than the server's echo?).
            logger.warn("↓ response for unknown id=$id pending=${pending.size} frame=${frame.toString().take(160)}")
            return
        }
        val errorElement = frame["error"]
        if (errorElement != null && errorElement !is JsonNull) {
            val rpcError = runCatching {
                json.decodeFromJsonElement(JsonRpcError.serializer(), errorElement)
            }.getOrElse {
                JsonRpcError(
                    code = JSON_RPC_CLIENT_DECODE_FAILURE,
                    message = errorElement.toString(),
                )
            }
            call.deferred.completeExceptionally(JsonRpcException(rpcError, call.method))
        } else {
            val result = frame["result"]?.takeIf { it !is JsonNull }
            call.deferred.complete(result)
        }
    }

    private fun rejectAllPending(error: Throwable) {
        val snapshot = pending.values.toList()
        pending.clear()
        for (call in snapshot) {
            call.deferred.completeExceptionally(error)
        }
    }

    private fun cleanupAfterError(failedEpoch: Long) {
        if (epoch.get() == failedEpoch) {
            socketRef.getAndSet(null)?.cancel()
            _connectionState.value = ConnectionState.Error
            rejectAllPending(IOException("connection failed"))
        }
    }

    private data class PendingCall(
        val id: String,
        val method: String,
        val deferred: CompletableDeferred<JsonElement?>,
    )

    /**
     * Logging seam — keeps this class free of `android.util.Log` (it lives in
     * :shared/jvmSharedMain, which has no Android SDK). Production passes an
     * explicit Logger (e.g. MarmaladeRuntime routes to its transport log); the
     * default is [NoOp], and tests can pass a no-op or a recording impl.
     */
    /**
     * Logging seam. `warn` is the long-standing surface. `info` is added for
     * the 2026-06-27 audit-window diagnostic pass (frame in/out tracing,
     * connection-event tracing) and routes to logcat at INFO level in
     * production. Default impl below makes tests source-compatible without
     * forcing every Logger SAM to implement it.
     */
    interface Logger {
        fun warn(message: String)
        fun info(message: String) {}

        companion object {
            val NoOp: Logger = object : Logger {
                override fun warn(message: String) {}
                override fun info(message: String) {}
            }
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private val EmptyParams = JsonObject(emptyMap())

        /**
         * Default [Json] config — forward-compat with server-side field
         * additions, but **strict** about value shape: we want server bugs to
         * surface as decode errors rather than silently coerced defaults that
         * mask the problem in production.
         */
        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

/** Seam so tests can inject a fake [WebSocket]. Public (not internal): :app's
 *  test suite implements it, and it now lives in :shared (ADR 0011). */
fun interface WebSocketFactory {
    fun create(request: Request, listener: WebSocketListener): WebSocket
}

private class OkHttpWebSocketFactory(private val client: OkHttpClient) : WebSocketFactory {
    override fun create(request: Request, listener: WebSocketListener): WebSocket =
        client.newWebSocket(request, listener)
}
