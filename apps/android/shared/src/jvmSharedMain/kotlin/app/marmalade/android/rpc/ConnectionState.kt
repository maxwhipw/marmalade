package app.marmalade.android.rpc

/**
 * Lifecycle states of a [JsonRpcClient] socket.
 *
 * Mirrors the desktop client's state machine
 * (`hermes-agent upstream: apps/shared/src/json-rpc-gateway.ts`):
 *
 * - [Idle] — never connected, or `close()` happened before a connect attempt.
 * - [Connecting] — `connect()` is in flight; the open handshake hasn't landed.
 * - [Open] — handshake complete, RPCs are dispatchable, events flow.
 * - [Closed] — socket closed cleanly (server or local `close()`).
 * - [Error] — connect timed out, transport failure, or unrecoverable protocol error.
 *
 * Observers reach this via [JsonRpcClient.connectionState]; reconnect logic and
 * UI status indicators sit on top of that flow.
 */
enum class ConnectionState { Idle, Connecting, Open, Closed, Error }
