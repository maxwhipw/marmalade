package app.marmalade.android.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Server-pushed event from marmalade-agent's `tui_gateway` dispatcher.
 *
 * Arrives over the wire as a JSON-RPC notification:
 *
 *     { "jsonrpc": "2.0",
 *       "method":  "event",
 *       "params":  { "type": "...", "payload": <any>, "session_id": "..." } }
 *
 * Common event types (from
 * `hermes-agent upstream: apps/shared/src/json-rpc-gateway.ts:1`):
 *
 *   gateway.ready · session.info · message.start · message.delta ·
 *   message.complete · thinking.delta · reasoning.delta · reasoning.available ·
 *   status.update · tool.start · tool.progress · tool.complete · tool.generating ·
 *   clarify.request · approval.request · sudo.request · secret.request ·
 *   background.complete · error · skin.changed · subagent.spawn_requested ·
 *   subagent.start
 *
 * The set is intentionally open. `JsonRpcClient` leaves [payload] as a raw
 * [JsonElement]; typed deserialization happens per-handler in the chat
 * message-stream layer (see `chat/MarmaladeMessages.kt` once that lands).
 */
@Serializable
data class GatewayEvent(
    val type: String,
    val payload: JsonElement? = null,
    @SerialName("session_id") val sessionId: String? = null,
)
