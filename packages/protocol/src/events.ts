// events.ts — zod payload schemas for daemon-ORIGINATED events.
//
// The envelope (frames.ts) is deliberately payload-open: most events flow
// harness→client and the client deserializes per-handler. But a handful of
// events are constructed by the DAEMON itself from known-good internal state —
// status.update, error, session.deleted. Those have a fixed shape the daemon
// owns end to end, so we pin it here: the construction sites assert against
// these types with `satisfies` (compile-time, zero runtime cost) and a daemon
// conformance test parses real emitted frames against them (drift lock).
//
// Additive to frozen protocol v1: these describe payloads the daemon already
// emits; nothing on the wire changes.

import { z } from "zod";

/** Session lifecycle (P2): active until a terminal stop/error/end. */
export const SessionLifecycle = z.enum(["active", "ended"]);
export type SessionLifecycle = z.infer<typeof SessionLifecycle>;

/** Session run-state (P2): the live "what is the agent doing" signal. */
export const SessionRunState = z.enum([
  "starting",
  "idle",
  "running",
  "awaiting_input",
  "hung",
]);
export type SessionRunState = z.infer<typeof SessionRunState>;

/** `status.update` — pushed on every lifecycle/run-state flip (P2). */
export const StatusUpdatePayload = z.object({
  session_id: z.string(),
  lifecycle: SessionLifecycle,
  run_state: SessionRunState,
});
export type StatusUpdatePayload = z.infer<typeof StatusUpdatePayload>;

/** `error` — a session-terminating failure made visible, not silent (M3/R2). */
export const ErrorPayload = z.object({
  kind: z.string(),
  message: z.string(),
  session_id: z.string(),
});
export type ErrorPayload = z.infer<typeof ErrorPayload>;

/** `session.compaction` — context-compaction surfacing (T2 #11a). The engine
 *  is harness-delegated; the daemon only relays the harness's signals:
 *  `started` when compaction begins (manual via session.compact OR the
 *  harness's own auto-compact), `completed`/`failed` when it settles, and a
 *  `boundary` marker carrying the token counts. Clients show "compacting…"
 *  on started and clear it on completed/failed. */
export const SessionCompactionPayload = z.object({
  status: z.enum(["started", "completed", "failed", "boundary"]),
  /** boundary only: what kicked compaction off. */
  trigger: z.enum(["manual", "auto"]).optional(),
  /** boundary only: context tokens before/after. */
  pre_tokens: z.number().optional(),
  post_tokens: z.number().optional(),
  /** failed only. */
  error: z.string().optional(),
});
export type SessionCompactionPayload = z.infer<typeof SessionCompactionPayload>;

/** `effort.clamped` — a per-model effort bound (config `model_efforts`) moved
 *  the requested reasoning effort to a different level. Emitted ONLY when the
 *  clamp actually CHANGED the value, at every clamp seam (session.create, the
 *  main session's create, session.effort).
 *
 *  DURABLE, unlike the other daemon-originated events here: it is stamped and
 *  appended to the transcript cache, so the record survives a cold load and
 *  replays in place. That's design-lab option E3 (decided 2026-07-27) — the clamp
 *  is a quiet, permanent transcript line, not a toast to dismiss.
 *
 *  `bound` names which edge bit ("min" = the requested level was too shallow,
 *  "max" = too deep) and `limit` is that edge's configured level — which for a
 *  single-sided bound equals `effective`, but not when both edges are set.
 *  Effort levels stay plain strings on the wire, matching the rest of protocol
 *  v1 (the daemon validates against its own EFFORT_LEVELS). */
export const EffortClampedPayload = z.object({
  /** What the caller asked for (client pick, or the daemon's default_effort). */
  requested: z.string(),
  /** What the session actually runs at — the stamped/returned value. */
  effective: z.string(),
  /** The model whose bounds applied. A model-less session can't be bounded. */
  model: z.string(),
  bound: z.enum(["min", "max"]),
  /** The configured bound level that bit. */
  limit: z.string(),
});
export type EffortClampedPayload = z.infer<typeof EffortClampedPayload>;

/** `session.undone` — the last completed turn was popped (session.undo).
 *  Transient like session.deleted: sent directly to subscribers, never cached
 *  (the transcript was just truncated; a replayed undone event would name
 *  message ids that no longer exist). Live clients drop the popped bubbles;
 *  clients that ignore it reconcile on their next replay. */
export const SessionUndonePayload = z.object({
  session_id: z.string(),
  /** The new tip (assistant message id), or null when the session emptied. */
  last_message_id: z.string().nullable(),
  popped_message_ids: z.array(z.string()),
});
export type SessionUndonePayload = z.infer<typeof SessionUndonePayload>;

/** `session.cleared` — the session's conversation was reset in place
 *  (session.clear): same session_id, messages/transcript gone, next turn
 *  starts fresh. Transient like session.deleted — sent directly to
 *  subscribers, never cached (a replayed cleared event would describe a
 *  transcript that no longer exists). Live clients empty their view. */
export const SessionClearedPayload = z.object({
  session_id: z.string(),
});
export type SessionClearedPayload = z.infer<typeof SessionClearedPayload>;

/** `session.deleted` — broadcast to every subscriber before a session's data is
 *  cascaded away (sent directly, NOT through the stamped/cached emit path, so a
 *  replay never resurrects the deleted session). */
export const SessionDeletedPayload = z.object({
  session_id: z.string(),
});
export type SessionDeletedPayload = z.infer<typeof SessionDeletedPayload>;

/** `secret.request` — the agent needs a credential and must not be the one to
 *  see it. The daemon parks the agent's `request_secret` tool call and pushes
 *  this to every subscriber declaring the "secrets" client capability; the
 *  client renders a VISUALLY DISTINCT secure input (masked, no autofill/
 *  clipboard/draft persistence) and answers with the `secret.respond` RPC.
 *
 *  Transient like approval.request, and more strictly so: never stamped into
 *  the transcript cache, never in the message store, never indexed for search.
 *  Only the entry NAME and the description travel here — a value never appears
 *  on any frame in either direction except `secret.respond`'s params.
 *
 *  Exactly one request is outstanding per session (serialized, same gate as
 *  approvals), so `request_id` correlation has a FIFO fallback. */
export const SecretRequestPayload = z.object({
  session_id: z.string(),
  request_id: z.string(),
  /** Keyring entry path the value will be written to, e.g.
   *  "marmalade/email/imap-password". Shown to the user — it IS the promise
   *  about where the credential lands. */
  entry: z.string(),
  /** Agent-supplied, human-readable: what the secret is for. Model-authored
   *  text — clients must render it as untrusted content, never as markup. */
  description: z.string(),
  created_at: z.number(),
});
export type SecretRequestPayload = z.infer<typeof SecretRequestPayload>;

/** `secret.resolved` — the parked request settled; every client clears its
 *  card (the answering device is not necessarily the only one showing it).
 *  Carries the OUTCOME only, never the value. `error` is the keyring failure,
 *  already redacted of the value by keyring.ts. */
export const SecretResolvedPayload = z.object({
  request_id: z.string(),
  outcome: z.enum(["stored", "denied", "failed"]),
  error: z.string().optional(),
});
export type SecretResolvedPayload = z.infer<typeof SecretResolvedPayload>;

/** `terminal.data` — PTY output. Sent ONLY to connections attached to the
 *  terminal (terminal.create/attach), never stamped, never cached, never
 *  replayed: scrollback recovery is the attach result's snapshot, not event
 *  replay. Base64 raw bytes — chunk boundaries are arbitrary (mid-escape-
 *  sequence splits are fine; emulators buffer). */
export const TerminalDataPayload = z.object({
  terminal_id: z.string(),
  data_b64: z.string(),
});
export type TerminalDataPayload = z.infer<typeof TerminalDataPayload>;

/** `terminal.exit` — the shell process died (exit, kill, or terminal.close).
 *  Transient like session.deleted: sent to attached connections, then the
 *  roster row is gone. exit_code is null when the process died to a signal. */
export const TerminalExitPayload = z.object({
  terminal_id: z.string(),
  exit_code: z.number().int().nullable(),
});
export type TerminalExitPayload = z.infer<typeof TerminalExitPayload>;
