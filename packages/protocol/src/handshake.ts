// Gateway protocol v1 — the negotiated `hello` handshake (Decision 1.2).
//
// Design constraint (coh-H1 / sec-H5): the EXISTING Android client speaks
// legacy framing — auth rides the WS URL query (`?token=` loopback session
// token, or `?ticket=` single-use 30s OAuth ticket) and the server sends
// `gateway.ready` as the first frame. That client sends no hello.
//
// So `hello` is a NEGOTIATED, OPTIONAL first client→server request:
//   - If the daemon receives a `hello` request as/among the first frames,
//     it runs v1: validate protocolVersion, bind the principal from the
//     bearer token in params, reply with negotiated features.
//   - If no hello arrives and a legacy `?token=`/`?ticket=` is present, the
//     daemon binds `principal=owner, legacy` and sends `gateway.ready` — the
//     current client connects unchanged during the transition window.
//   - The legacy-acceptance path is removed at cutover (Decision 6, M5).
//
// `hello` is framed as a normal JSON-RPC request (has an id) so it gets a
// normal result/error response — no new envelope shape on the wire.

import { z } from "zod";
import { PROTOCOL_VERSION } from "./frames.js";

export const ClientCapability = z.enum([
  "streaming",       // consumes message.delta / reasoning.delta events
  "device-tools",    // hosts a device node (android-bridge origin)
  "approvals",       // can render + answer approval.request prompts
  "secrets",         // can render + answer secret.request prompts (secure input)
  "offline-cache",   // persists the transcript cache locally
  "stable-ids",      // honors server-minted message_id/seq (identity plan P1)
]);
export type ClientCapability = z.infer<typeof ClientCapability>;

export const HelloParams = z.object({
  protocolVersion: z.number().int().positive(),
  client: z.object({
    name: z.string(),
    version: z.string(),
    capabilities: z.array(ClientCapability).default([]),
    /** Origin identity (P1): stamped by the daemon onto every message this
     *  connection submits. Optional — legacy clients send none and get
     *  defaults. The daemon TRUSTS the authenticated connection, never the
     *  message body (sec-H3). Real deviceId issuance lands with pairing (M2). */
    deviceId: z.string().optional(),
    platform: z.string().optional(),   // desktop | android | cli | web
    /** Sender's UTC offset in minutes at connect time (origin metadata). */
    tzOffset: z.number().int().optional(),
  }),
  /** Per-device bearer token issued at pairing time (Decision 1.2).
   *  Absent only during the pairing bootstrap itself. */
  auth: z.object({ token: z.string() }).optional(),
});
export type HelloParams = z.infer<typeof HelloParams>;

export const HelloRequest = z.object({
  jsonrpc: z.literal("2.0"),
  id: z.union([z.string(), z.number()]),
  method: z.literal("hello"),
  params: HelloParams,
});
export type HelloRequest = z.infer<typeof HelloRequest>;

export const ServerFeature = z.enum([
  "device-bridge",   // android-bridge MCP facade is available
  "caldav",          // caldav MCP is available
  "state-reads",     // main-session state preload is available
  "stable-ids",      // events carry server-minted message_id + seq + ts + origin
  "subscribe",       // session.subscribe/unsubscribe/seen — multi-client replay (P4)
  "pairing",         // pairing.start/claim + device.list/revoke — token auth (M2)
  "attachments",     // image.attach_bytes/file.attach/image.detach — staged per-session (T1)
  "undo",            // session.undo + session.undone — pop the last turn (T2 #6)
  "transcription",   // audio.transcribe — server-side STT fallback (advertised only when an STT command resolves)
  "clarify",         // clarify.request event + clarify.respond — agent questions (AskUserQuestion) round-trip
  "workspaces",      // workspace.create/list/update/delete + workspace_id stamped on session.list rows
  "terminal",        // terminal.* PTY terminals (advertised only when node-pty loaded AND config allows)
  "search",          // search.messages — FTS5 full-text search over message text (daemon-side index)
  "search_archive",  // scope.corpus="archive" + search.archive — the pre-daemon ~/.claude/projects corpus, read-only
  "settings",        // settings.get/update — daemon-owned new-session model + effort defaults, editable from any client
]);
export type ServerFeature = z.infer<typeof ServerFeature>;

export const HelloResult = z.object({
  protocolVersion: z.literal(PROTOCOL_VERSION),
  server: z.object({ name: z.literal("marmaladed"), version: z.string() }),
  /** The principal this connection is now bound to (Decision 5). v0.1: "owner". */
  principal: z.string(),
  features: z.array(ServerFeature),
});
export type HelloResult = z.infer<typeof HelloResult>;

/** True when protocolVersion is one this daemon can serve. v0.1 serves only 1. */
export function isSupportedProtocolVersion(v: number): boolean {
  return v === PROTOCOL_VERSION;
}
