// Gateway protocol v1 — the frozen JSON-RPC 2.0 wire envelope.
//
// Grounded (2026-07-10) in the wire dialect the existing marmalade-android
// client and the fork gateway already speak, verified against:
//   - the Android client's JsonRpcClient/GatewayEvent (wire dialect + event shape)
//   - the upstream hermes-agent shared json-rpc-gateway (event names)
//
// Wire dialect (unchanged from today — Decision 1 "freeze JSON-RPC v1"):
//   Request:   {"jsonrpc":"2.0","id":<str>,"method":<str>,"params":{...}}
//   Response:  {"jsonrpc":"2.0","id":<id>,"result":<any>}
//              {"jsonrpc":"2.0","id":<id>,"error":{code,message,data?}}
//   Event:     {"jsonrpc":"2.0","method":"event",
//               "params":{"type":<name>,"payload":<any>,"session_id"?:<str>}}
//   IDs are strings by this client's convention; the server echoes them opaquely.

import { z } from "zod";

export const PROTOCOL_VERSION = 1;

/** JSON-RPC id — the client always sends strings; the server echoes verbatim. */
export const RpcId = z.union([z.string(), z.number()]);

export const JsonRpcRequest = z.object({
  jsonrpc: z.literal("2.0"),
  id: RpcId,
  method: z.string(),
  params: z.record(z.string(), z.unknown()).optional(),
});
export type JsonRpcRequest = z.infer<typeof JsonRpcRequest>;

export const JsonRpcResultResponse = z.object({
  jsonrpc: z.literal("2.0"),
  id: RpcId,
  result: z.unknown(),
});

export const JsonRpcError = z.object({
  code: z.number(),
  message: z.string(),
  data: z.unknown().optional(),
});
export type JsonRpcError = z.infer<typeof JsonRpcError>;

export const JsonRpcErrorResponse = z.object({
  jsonrpc: z.literal("2.0"),
  id: RpcId.nullable(),
  error: JsonRpcError,
});

// ERROR branch FIRST (bug fix, 2026-07-18): zod treats `result: z.unknown()`
// as an optional key, so with the result branch first an ERROR frame parsed
// as a result response with the `error` key STRIPPED — consumers reading the
// parsed object (webui resolvePending) resolved daemon errors as `undefined`
// results instead of rejecting. Error frames must carry `error` and result
// frames must not, so error-first is unambiguous. No wire change.
export const JsonRpcResponse = z.union([
  JsonRpcErrorResponse,
  JsonRpcResultResponse,
]);
export type JsonRpcResponse = z.infer<typeof JsonRpcResponse>;

// ── Server-pushed events ────────────────────────────────────────────────────
// The authoritative set is intentionally open (the client leaves payload raw
// and deserializes per-handler). We validate the ENVELOPE, not each payload.
// This list mirrors json-rpc-gateway.ts + GatewayEvent.kt as of 2026-07-10.

export const KnownGatewayEventName = z.enum([
  "gateway.ready",
  "session.info",
  "message.start",
  "message.delta",
  "message.complete",
  "message.user",       // transcript-only user-message record (P1 identity)
  "thinking.delta",
  "reasoning.delta",
  "reasoning.available",
  "status.update",
  "tool.start",
  "tool.progress",
  "tool.complete",
  "tool.generating",
  "clarify.request",
  "approval.request",
  "sudo.request",
  "secret.request",
  "background.complete",
  "error",
  "session.deleted",    // daemon-originated: a session was deleted (payload schema in events.ts)
  "session.undone",     // daemon-originated: last turn popped (payload schema in events.ts)
  "session.cleared",    // daemon-originated: conversation reset in place (payload schema in events.ts)
  "session.compaction", // daemon-originated: compaction lifecycle (started/completed/failed/boundary)
  "effort.clamped",     // daemon-originated: a per-model bound moved the requested effort (payload schema in events.ts)
  "terminal.data",      // daemon-originated: PTY output to ATTACHED connections only (payload schema in events.ts)
  "terminal.exit",      // daemon-originated: the shell died; roster row dropped (payload schema in events.ts)
  "skin.changed",
  "subagent.spawn_requested",
  "subagent.start",
  "subagent.complete",  // daemon-originated: a spawned subagent settled (report + run totals)
]);
export type KnownGatewayEventName = z.infer<typeof KnownGatewayEventName>;

/** Event names are open — accept any string, but keep the known list for docs
 *  and exhaustiveness checks elsewhere. */
export const GatewayEventName = z.string();

export const GatewayEventParams = z.object({
  type: GatewayEventName,
  payload: z.unknown().optional(),
  session_id: z.string().optional(),
});
export type GatewayEventParams = z.infer<typeof GatewayEventParams>;

export const JsonRpcEvent = z.object({
  jsonrpc: z.literal("2.0"),
  method: z.literal("event"),
  params: GatewayEventParams,
});
export type JsonRpcEvent = z.infer<typeof JsonRpcEvent>;

/** Any inbound frame from a client: a request, or a v1 hello (also a request
 *  by framing — see handshake.ts). Events only flow server→client. */
export const ClientFrame = JsonRpcRequest;

/** Any outbound frame to a client: a response or an event. */
export const ServerFrame = z.union([JsonRpcResponse, JsonRpcEvent]);

// ── Constructors (server side) ──────────────────────────────────────────────

export function makeResult(id: string | number, result: unknown): JsonRpcResponse {
  return { jsonrpc: "2.0", id, result };
}

export function makeError(
  id: string | number | null,
  code: number,
  message: string,
  data?: unknown,
): JsonRpcResponse {
  return { jsonrpc: "2.0", id, error: data === undefined ? { code, message } : { code, message, data } };
}

export function makeEvent(
  type: string,
  payload?: unknown,
  sessionId?: string,
): JsonRpcEvent {
  const params: GatewayEventParams = { type };
  if (payload !== undefined) params.payload = payload;
  if (sessionId !== undefined) params.session_id = sessionId;
  return { jsonrpc: "2.0", method: "event", params };
}

/** JSON-RPC error codes we use (standard range + marmalade extensions). */
export const ErrorCode = {
  ParseError: -32700,
  InvalidRequest: -32600,
  MethodNotFound: -32601,
  InvalidParams: -32602,
  InternalError: -32603,
  // marmalade extensions (-32000..-32099 = server-defined per JSON-RPC spec)
  Unauthenticated: -32001,
  ProtocolVersionUnsupported: -32002,
  PrincipalForbidden: -32003,
} as const;
