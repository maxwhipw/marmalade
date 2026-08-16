package app.marmalade.android.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/**
 * JSON-RPC 2.0 error envelope.
 *
 * Standard codes (https://www.jsonrpc.org/specification#error_object):
 *
 *   -32700  parse error
 *   -32600  invalid request
 *   -32601  method not found
 *   -32602  invalid params
 *   -32603  internal error
 *   -32000..-32099  reserved for implementation-defined server errors
 *
 * marmalade-agent specifics:
 *
 * - `tui_gateway/ws.py` returns -32700 with `id: null` for unparseable client
 *   frames (these are silently dropped by the client today — they're a
 *   client-side bug).
 * - `tui_gateway/server.dispatch` returns -32603 with the original `id` when
 *   the handler thread crashes.
 *
 * The desktop / web TS clients only surface `message` to callers. This Kotlin
 * port keeps `code` and `data` too so handlers can branch on specific failure
 * shapes without re-parsing.
 */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/**
 * Synthetic code used when the server's error envelope cannot be decoded. Sits
 * *outside* the JSON-RPC reserved server-error range (-32000..-32099) so a
 * caller branching on [JsonRpcException.code] can distinguish a real
 * server-defined error from a client-side decode fallback.
 */
const val JSON_RPC_CLIENT_DECODE_FAILURE: Int = -33000

/**
 * Thrown by [JsonRpcClient.request] when the server returns an error response,
 * OR when the error envelope itself is malformed (with [code] set to
 * [JSON_RPC_CLIENT_DECODE_FAILURE]).
 *
 * Extends [IOException] so callers can `catch (e: IOException)` to handle both
 * transport-level (connect/close/timeout) and protocol-level failures in one
 * arm. The typed [code]/[rpcError] properties are still available when the
 * caller wants to branch on a specific shape.
 *
 * The originating [method] is captured so log lines can identify the failed
 * call without re-threading the method name to the catch site.
 */
class JsonRpcException(
    val rpcError: JsonRpcError,
    val method: String? = null,
) : IOException(buildMessage(rpcError, method)) {
    val code: Int get() = rpcError.code

    private companion object {
        fun buildMessage(error: JsonRpcError, method: String?): String =
            if (method != null) "$method failed (rpc ${error.code}): ${error.message}"
            else "JSON-RPC ${error.code}: ${error.message}"
    }
}
