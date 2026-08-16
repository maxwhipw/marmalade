package app.marmalade.android.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Routes server-pushed `node.invoke.request` events to the right Android-side
 * handler and replies with `node.invoke.respond`. This is the client half of
 * marmalade-agent's option-D dispatch architecture; the server-side bridge
 * plugin (live at `~/.marmalade/plugins/marmalade-android/adapter.py`)
 * emits the events and awaits the responses via its `_block()` pattern,
 * paralleling the existing `clarify.request` / `approval.request` semantics.
 *
 * Why this shape:
 *
 * - The phone is OUTBOUND-ONLY — no MCP listener, no open port, no new
 *   bearer token. Reuses the existing WS auth. On hostile networks (cell
 *   data, coffee-shop wifi) this is materially safer than running a
 *   listening server.
 * - The Python plugin owns per-tool authorization (which tools are exposed
 *   to the agent) and any rate-limiting. The Kotlin side just executes
 *   what arrives — confirmation prompts for dangerous tools (SMS send,
 *   call, write contacts) live in `voice.ActionDispatcher` (an :app type),
 *   which the chat layer already drives.
 *
 * Lifecycle: construct in `node.MarmaladeRuntime` composition with a handler map
 * populated from the 18 device handlers, then call [start] to subscribe.
 * [start]'s collector is bound to the runtime [scope]; cancelling the scope
 * tears it down.
 *
 * The gateway plugin is live and currently registers 4 tools —
 * `android_set_alarm`, `android_notify`, `android_device_status`,
 * `android_fire_intent` — so `node.invoke.request` events for those names
 * already arrive in production. The remaining entries in
 * `node.MarmaladeRuntime`'s handler table (camera,
 * SMS, contacts, calendar, etc.) are implemented client-side and tested via
 * [dispatch], but await gateway-side tool registration before the server
 * will ever emit an event naming them (registration plan: internal design
 * note, not in this repo).
 */
class MarmaladeInvokeDispatcher(
    private val rpcClient: JsonRpcClient,
    private val events: Flow<GatewayEvent>,
    private val scope: CoroutineScope,
    private val handlers: Map<String, suspend (paramsJson: String?) -> InvokeResult>,
    /**
     * Bounds how many `node.invoke.request` events are dispatched
     * concurrently. Each event's dispatch+respond runs in its own child
     * coroutine (see [start]) so one slow handler (camera capture, screen
     * recording) can't head-of-line-block every other device tool behind it
     * past the gateway's ~10s result timeout. The permit count also caps a
     * burst of concurrent requests from stampeding hardware handlers that
     * aren't safe to run in parallel (e.g. camera).
     */
    private val maxConcurrentDispatches: Int = 4,
) {
    private val dispatchSemaphore = Semaphore(maxConcurrentDispatches)

    /**
     * Dispatch a single tool call. Pure function — no event side-effects,
     * unit-testable in isolation. Returns [InvokeResult.error] with code
     * `UNKNOWN_TOOL` when no handler is registered for [tool].
     */
    suspend fun dispatch(tool: String, paramsJson: String?): InvokeResult =
        handlers[tool]?.invoke(paramsJson)
            ?: InvokeResult.error(
                code = "UNKNOWN_TOOL",
                message = "no handler registered for $tool",
            )

    /**
     * Subscribe to `node.invoke.request` events on [events]. Each event:
     *
     *   { "type": "node.invoke.request",
     *     "session_id": "...",
     *     "payload": { "name": "app.launch", "params": {...}, "request_id": "..." } }
     *
     * gets routed through [dispatch], then the result is sent back via
     * `node.invoke.respond`:
     *
     *   { "session_id": "...", "request_id": "...", "ok": true,
     *     "payload": "<handler payload JSON>" }   // or "code"/"message" if ok=false
     *
     * Returns the [Job] driving the collector so the caller can cancel it
     * directly (in addition to whatever the [scope]'s lifecycle does).
     *
     * Each event's dispatch+respond runs in its own child coroutine of
     * [scope] (launched from inside `collect`, not awaited inline), bounded
     * by [dispatchSemaphore] to [maxConcurrentDispatches] concurrent
     * in-flight handlers. Without this, `collect`'s per-event block used to
     * run inline — a single slow handler (camera, screen record) blocked the
     * collector from even reading the next event off the flow, so every
     * other device tool queued behind it until the slow one finished or the
     * gateway's ~10s result timeout fired for the queued ones too.
     */
    fun start(): Job = scope.launch {
        events.filter { it.type == "node.invoke.request" }.collect { event ->
            val payload = event.payload as? JsonObject ?: return@collect
            val name = payload.stringOrNull("name") ?: return@collect
            val requestId = payload.stringOrNull("request_id") ?: return@collect
            // params arrives as an arbitrary JsonElement; flatten to a JSON
            // string for the handlers, which all parse from String to keep
            // the boundary explicit and testable without coupling to
            // kotlinx.serialization on the handler side.
            val paramsJson = payload["params"]?.toString()

            launch {
                dispatchSemaphore.withPermit {
                    val result = try {
                        dispatch(name, paramsJson)
                    } catch (t: Throwable) {
                        InvokeResult.error(
                            code = "HANDLER_THREW",
                            message = "${name}: ${t.message ?: t.javaClass.simpleName}",
                        )
                    }

                    try {
                        rpcClient.request(
                            method = "node.invoke.respond",
                            params = buildJsonObject {
                                put("session_id", event.sessionId ?: "")
                                put("request_id", requestId)
                                put("ok", result.ok)
                                result.payloadJson?.let { put("payload", it) }
                                result.error?.let {
                                    put("code", it.code)
                                    put("message", it.message)
                                }
                            },
                        )
                    } catch (_: Throwable) {
                        // Respond may fail if the socket dropped between request
                        // and reply; the server times out the _block() and
                        // surfaces a tool-call timeout to the agent. Nothing
                        // further to do here.
                    }
                }
            }
        }
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
