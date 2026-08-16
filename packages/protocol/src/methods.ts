// A starter set of typed method contracts (Decision 1.3 — "typed contracts as
// code"). Grounded in the params the existing Android client actually sends
// (MarmaladeRpc.kt) so v1 stays wire-compatible.
//
// This is NOT the full surface — the fork exposes ~30 methods. We schema the
// chat-essential subset here and grow it as adapters need each one. Unknown
// methods still pass through the gateway; schemas are for validation + types
// at the seams that care.

import { z } from "zod";

/** session.create — every call carries the composer's model/effort/fast as
 *  per-session overrides (MarmaladeRpc.kt sessionCreate docblock). */
export const SessionCreateParams = z.object({
  cols: z.number().int().default(80),
  cwd: z.string().optional(),
  model: z.string().optional(),
  provider: z.string().optional(),
  reasoning_effort: z.string().optional(),
  fast: z.boolean().optional(),
  profile: z.string().optional(),
  title: z.string().optional(),
  /** Per-session approvals override (M2): "prompt" parks tool calls behind
   *  approval.request; "auto" approves-with-log. Stored on the session row,
   *  re-applied on resume. Absent = the daemon's global default. */
  approvals: z.enum(["auto", "prompt"]).optional(),
  // NOTE: a `messages` seed-array param existed here through 2026-07-18 but
  // was never consumed by the daemon; it was removed (delegated call)
  // when the last client fallback that sent it was deleted. Restore from git
  // + implement router-side seeding together, or not at all.
});
export type SessionCreateParams = z.infer<typeof SessionCreateParams>;

export const SessionCreateResult = z.object({
  session_id: z.string(),
});
export type SessionCreateResult = z.infer<typeof SessionCreateResult>;

/** prompt.submit — the load-bearing turn. `source` carries the voice-origin
 *  tag (fork patch 4e → protocol v1 requirement, Decision 1). */
export const PromptSubmitParams = z.object({
  session_id: z.string(),
  prompt: z.string(),
  source: z.enum(["text", "voice"]).optional(),
});
export type PromptSubmitParams = z.infer<typeof PromptSubmitParams>;

/** prompt.submit result (P1, additive): the server-minted identity of the user
 *  message just accepted, so an id-aware client can bind its local bubble to
 *  the server id immediately. Legacy clients ignore the result body. */
export const PromptSubmitResult = z.object({
  message_id: z.string(),
  seq: z.number().int(),
  ts: z.number(),
});
export type PromptSubmitResult = z.infer<typeof PromptSubmitResult>;

/** session.resume — continue a stored session (distinct from create). */
export const SessionResumeParams = z.object({
  session_id: z.string(),
  cols: z.number().int().optional(),
});
export type SessionResumeParams = z.infer<typeof SessionResumeParams>;

/** session.subscribe (P4) — attach this connection to a session's event
 *  stream: replay cached events with seq > since_seq, then stream live.
 *  Events go to ALL subscribers, not just the creating connection. A
 *  subscription lasts until unsubscribe or disconnect — it survives the
 *  session going idle/ended and resumes streaming if another device revives
 *  the session. */
export const SessionSubscribeParams = z.object({
  session_id: z.string(),
  /** Replay cursor: the highest seq this client has already seen. 0 (default)
   *  replays the whole cache. Dedup on the client is by message_id; order is
   *  by seq — never by clock. */
  since_seq: z.number().int().min(0).default(0),
});
export type SessionSubscribeParams = z.infer<typeof SessionSubscribeParams>;

export const SessionSubscribeResult = z.object({
  session_id: z.string(),
  /** How many cached events were replayed to this connection. */
  replayed: z.number().int(),
  /** Highest seq in the session's cache at subscribe time — the client's next
   *  since_seq cursor if it reconnects immediately. */
  last_seq: z.number().int(),
  lifecycle: z.string(),
  run_state: z.string(),
});
export type SessionSubscribeResult = z.infer<typeof SessionSubscribeResult>;

export const SessionUnsubscribeParams = z.object({
  session_id: z.string(),
});
export type SessionUnsubscribeParams = z.infer<typeof SessionUnsubscribeParams>;

/** session.seen (P4) — per-(device, session) read cursor: "this device has
 *  rendered up to seq". The seen-at patch (4k) done right: unread is
 *  arithmetic (last_seq > seen_seq), not wall-clock heuristics. Monotonic —
 *  a stale stamp never moves the cursor backward. */
export const SessionSeenParams = z.object({
  session_id: z.string(),
  seq: z.number().int().min(0),
});
export type SessionSeenParams = z.infer<typeof SessionSeenParams>;

export const SessionSeenResult = z.object({
  /** The stored cursor after the stamp (>= the seq sent — max-merge). */
  seq: z.number().int(),
});
export type SessionSeenResult = z.infer<typeof SessionSeenResult>;

/** session.delete (additive) — remove a session and EVERYTHING it owns:
 *  the index row, its message identity rows, every device's seen cursor, and
 *  the transcript cache. A live session is stopped first — no close-then-
 *  delete dance (the fork's error-4023 ritual is not part of this protocol).
 *  Deletion is the server's job; clients delete by the one visible id. */
export const SessionDeleteParams = z.object({
  session_id: z.string(),
});
export type SessionDeleteParams = z.infer<typeof SessionDeleteParams>;

/** session.title (additive) — set the human label shown in session lists.
 *  A title is metadata, not identity: renaming never changes the session_id. */
export const SessionTitleParams = z.object({
  session_id: z.string(),
  title: z.string().min(1),
});
export type SessionTitleParams = z.infer<typeof SessionTitleParams>;

export const SessionTitleResult = z.object({
  /** The stored title (server may cap length). */
  title: z.string(),
});
export type SessionTitleResult = z.infer<typeof SessionTitleResult>;

/** session.archive (additive, ratified 2026-07-23) — set a session's archived
 *  flag. Daemon-backed so the state is shared across clients (a Room-only
 *  flag would desync — the user reads on desktop too). Pure list metadata: an
 *  archived session still runs, resumes, and receives cron fires; clients
 *  filter archived rows out of their main list and show them in an
 *  "Archived" section. The main session cannot be archived (it is the
 *  pinned home surface). Idempotent: re-setting the current value is fine. */
export const SessionArchiveParams = z.object({
  session_id: z.string(),
  archived: z.boolean(),
});
export type SessionArchiveParams = z.infer<typeof SessionArchiveParams>;

export const SessionArchiveResult = z.object({
  /** The stored flag after the call. */
  archived: z.boolean(),
});
export type SessionArchiveResult = z.infer<typeof SessionArchiveResult>;

// ---- persisted context occupancy on session.list rows -----------------------
// (additive, 2026-07-25) — `context_used` / `context_max` / `context_percent`.
//
// The daemon already computes occupancy per turn (it rides the
// `message.complete` usage block), but that is transient: a client opening a
// COLD session — one that hasn't run a turn since the client connected — had
// no number to show. These three fields make it durable:
//
//   - `context_used` — tokens occupying the window after the last COMPLETED
//     turn (that turn's last API call: input + cache read/creation + output,
//     exactly what the live usage block reports).
//   - `context_max` — the harness-reported window of the model that carried
//     that turn.
//   - `context_percent` — DERIVED at read: both present →
//     `min(100, round(used / max * 100))`, else null. Never stored, so the
//     formula has one home.
//
// Stamped daemon-side at turn end from the usage the harness PUSHES; it is
// NEVER queried back from the harness on read, so listing costs nothing.
// null = unknown (never ran under a context-reporting harness — the ACP /
// OpenCode adapter reports no window — or the conversation was cleared, which
// resets both to null). An ABSENT field (a daemon predating this) reads as
// null; clients render nothing rather than guess.
//
// Pure LIST METADATA, exactly like `archived`: it decorates the session list
// for humans and is NEVER injected into a model prompt. (The daemon's separate
// context-pressure reminder is its own mechanism, untouched by this.) A
// session.undo leaves the stamp stale by design — it self-heals on the next
// turn.

/** session.interrupt (additive) — interrupt the live turn without ending the
 *  session. The interrupted message keeps its id; only its status records the
 *  interruption (P1: no state transition mints or changes an id). */
export const SessionInterruptParams = z.object({
  session_id: z.string(),
});
export type SessionInterruptParams = z.infer<typeof SessionInterruptParams>;

/** session.steer (additive, T2 #6) — inject guidance into a RUNNING turn.
 *  The harness merges the message into the in-flight agent loop (verified on
 *  Claude Code 2026-07-18: a mid-turn streamed user message redirects the
 *  turn; one result). Requires a turn in flight — when the session is idle
 *  the client should use prompt.submit instead. The steer is a real user
 *  message (own id/seq); its transcript event carries `steered: true`. */
export const SessionSteerParams = z.object({
  session_id: z.string(),
  prompt: z.string(),
  source: z.enum(["text", "voice"]).optional(),
});
export type SessionSteerParams = z.infer<typeof SessionSteerParams>;

/** session.steer result — the minted identity of the steer message, same
 *  contract as prompt.submit (accepted-into-input, not turn completion). */
export const SessionSteerResult = PromptSubmitResult;
export type SessionSteerResult = z.infer<typeof SessionSteerResult>;

/** session.compact (additive, T2 #11a) — ask the HARNESS to compact the
 *  session's context now (the engine stays harness-delegated; this is the
 *  manual trigger). Queue-and-return: the RPC acks acceptance; progress and
 *  outcome arrive as `session.compaction` events (status: started →
 *  completed|failed, plus a boundary event with token counts). Rejects when
 *  a turn is in flight or the harness has no compact support. */
export const SessionCompactParams = z.object({
  session_id: z.string(),
});
export type SessionCompactParams = z.infer<typeof SessionCompactParams>;

/** session.stop (additive) — stop a live session and mark it ended. Idempotent:
 *  stopping an already-idle/unknown session is a no-op, not an error. */
export const SessionStopParams = z.object({
  session_id: z.string(),
});
export type SessionStopParams = z.infer<typeof SessionStopParams>;

/** session.fork (additive, T2 #3) — branch a session into a NEW session that
 *  carries the FULL harness context (conversation incl. tool calls/reasoning),
 *  optionally cut at a message. The daemon copies its transcript + message
 *  identity up to the cut (new message ids — ids are identities, a new
 *  session is a new identity space) and marks lineage (branched_from on
 *  session.list). The fork starts ended/resumable; the first prompt.submit
 *  revives it via the normal auto-revive path.
 *
 *  at_message_id must be an ASSISTANT message (forks cut at replies — the
 *  daemon only holds harness cut-points for assistant messages). A harness
 *  that cannot fork rejects with a clear message; the client's fallback is
 *  its own seed-create branch (which loses tool/reasoning context — warn).
 *
 *  Cut-point discovery (additive, 2026-07-18): the stamped message.complete
 *  event carries `has_cut_point: boolean` — true when that assistant message
 *  can serve as a fork cut. False on fork-COPIED bubbles and on harnesses
 *  with no per-message ids; absent on pre-flag transcripts (treat absent as
 *  "offer and let the daemon decide", i.e. the legacy behavior). */
export const SessionForkParams = z.object({
  session_id: z.string(),
  /** Cut point (inclusive). Absent = fork the whole session from its end. */
  at_message_id: z.string().optional(),
  title: z.string().optional(),
});
export type SessionForkParams = z.infer<typeof SessionForkParams>;

/** Structured discriminator carried on the no-fork-harness rejection's
 *  `error.data.reason` (additive, 2026-07-18 review): clients branch to their
 *  seed-create fallback / unavailability warning on THIS, not on substring-
 *  matching the human message (which stays free to reword). The other fork
 *  rejections (turn in flight, bad cut, no harness state) carry no reason. */
export const FORK_UNSUPPORTED_REASON = "fork_unsupported" as const;

export const SessionForkResult = z.object({
  /** The NEW session's id. */
  session_id: z.string(),
  forked_from: z.object({
    session_id: z.string(),
    /** The cut message, or null for an end fork. */
    message_id: z.string().nullable(),
  }),
  /** True: the harness carried the conversation context natively. */
  full_context: z.boolean(),
  /** Soft caveat from the harness (e.g. Claude Code forks don't copy
   *  file-history/undo snapshots). Show it, don't block on it. */
  warning: z.string().optional(),
});
export type SessionForkResult = z.infer<typeof SessionForkResult>;

/** session.undo (additive, T2 #6 second half; delegated sign-off
 *  2026-07-18: delete popped rows, conversation-only v1, last-turn-only) —
 *  pop the LAST COMPLETED TURN (the last turn-starting user message plus
 *  every assistant/steer message after it) from the SAME session. Undo is
 *  state surgery, not a new identity: session_id unchanged (the opposite of
 *  fork). Repeated undo walks back turn by turn.
 *
 *  Harness-side the rewind is non-destructive (resume-at; the harness keeps
 *  the popped tail off-chain), but the daemon's rows/transcript for the
 *  popped turn are DELETED. Rejects (retry-later, not failures) when a turn
 *  is in flight, when there is nothing to undo, when the harness has no
 *  rewind seam, and when the cut would land on a fork-copied message (no
 *  harness cut-point). Live subscribers get a transient `session.undone`
 *  event; replay after the truncation is consistent by construction. */
export const SessionUndoParams = z.object({
  session_id: z.string(),
});
export type SessionUndoParams = z.infer<typeof SessionUndoParams>;

export const SessionUndoResult = z.object({
  /** The new tip after the pop (an assistant message), or null when the
   *  session is now empty (first-turn undo). */
  last_message_id: z.string().nullable(),
  popped_message_ids: z.array(z.string()),
  /** v1 rewinds the CONVERSATION only — file edits made during the popped
   *  turn are NOT reverted. Always false until file checkpointing ships. */
  files_rewound: z.boolean(),
});
export type SessionUndoResult = z.infer<typeof SessionUndoResult>;

/** session.main (additive, assistant plan 2026-07-19) — the daemon-managed
 *  singleton MAIN session: always exists, always warm, never deleted. The
 *  daemon creates it at boot (and lazily here if missing) and resumes it when
 *  its child is not live. Clients render it as the Home/assistant surface and
 *  route wake-word/voice turns into it. There is no "set main" — the daemon
 *  owns the designation; users manage it only via compact/clear/model. */
export const SessionMainParams = z.object({});
export type SessionMainParams = z.infer<typeof SessionMainParams>;

export const SessionMainResult = z.object({
  session_id: z.string(),
});
export type SessionMainResult = z.infer<typeof SessionMainResult>;

/** session.clear (additive) — reset a session's CONVERSATION in place: same
 *  session_id (ids are identities — clearing is state surgery, not a new
 *  session), messages/transcript/seen cursors wiped, harness state dropped
 *  (the next turn starts a fresh harness session). Title and model persist;
 *  topic/summary reset. This is how the main session — which cannot be
 *  deleted — starts over. Rejects while a turn is in flight. Subscribers get
 *  a transient `session.cleared` event. */
export const SessionClearParams = z.object({
  session_id: z.string(),
});
export type SessionClearParams = z.infer<typeof SessionClearParams>;

export const SessionClearResult = z.object({
  cleared: z.boolean(),
});
export type SessionClearResult = z.infer<typeof SessionClearResult>;

/** session.model (additive) — change a session's model. Stored on the row;
 *  when the session is live and idle the child is restarted so the change
 *  applies NOW (context carries over via harness resume), otherwise it
 *  applies on the next spawn. Rejects while a turn is in flight. */
export const SessionModelParams = z.object({
  session_id: z.string(),
  /** Harness model id (model.list), passed to the harness verbatim. */
  model: z.string().min(1),
});
export type SessionModelParams = z.infer<typeof SessionModelParams>;

export const SessionModelResult = z.object({
  model: z.string(),
  /** Additive (2026-07-27): the session's post-switch effort — the daemon
   *  re-clamps the stored effort against the NEW model's bounds, so this is
   *  the truth a client should adopt instead of re-fetching the row. Absent
   *  when the session has no stored effort. */
  reasoning_effort: z.string().optional(),
});
export type SessionModelResult = z.infer<typeof SessionModelResult>;

/** session.effort (additive, 2026-07-25) — change a session's reasoning
 *  effort, the mutable twin of session.model. Same semantics: stored on the
 *  row, an idle live child is restarted so it applies NOW, rejected while a
 *  turn is in flight. Without this, effort could only be chosen at
 *  session.create, so a client's picker was cosmetic on every existing
 *  session (the daemon's stored value kept winning the session.info echo). */
export const SessionEffortParams = z.object({
  session_id: z.string(),
  /** One of ModelListResult.efforts — validated against the daemon's set. */
  reasoning_effort: z.string().min(1),
});
export type SessionEffortParams = z.infer<typeof SessionEffortParams>;

export const SessionEffortResult = z.object({
  reasoning_effort: z.string(),
});
export type SessionEffortResult = z.infer<typeof SessionEffortResult>;

/** session.summary (additive) — read the session's rollup metadata (topic,
 *  summary, lifecycle, run_state) by id. */
export const SessionSummaryParams = z.object({
  session_id: z.string(),
});
export type SessionSummaryParams = z.infer<typeof SessionSummaryParams>;

/** model.list (additive) — the models this daemon's harness can run a session
 *  on. Ids are the harness's own model identifiers (for Claude Code, the API
 *  aliases like "claude-opus-4-8"), passed verbatim back on session.create's
 *  `model` param. An adapter with no model choice returns an empty list. */
export const ModelListParams = z.object({});
export type ModelListParams = z.infer<typeof ModelListParams>;

export const ModelInfo = z.object({
  id: z.string(),
  /** Human label for pickers ("Opus 5"). */
  label: z.string(),
  /** One-line blurb for a settings list ("Most capable — agentic coding").
   *  Additive (2026-07-25); absent on older daemons, so clients render the
   *  label alone. */
  description: z.string().optional(),
  /** Per-model reasoning-effort bounds (additive, 2026-07-27). Present only
   *  when the daemon config bounds THIS model. The daemon CLAMPS effort into
   *  [effort_min, effort_max] at session.create / session.effort and returns
   *  the clamped value, so a client that ignores these keys still shows the
   *  truth — it just can't grey out the levels it will never get. Values are
   *  members of ModelListResult.efforts. */
  effort_min: z.string().optional(),
  effort_max: z.string().optional(),
});
export type ModelInfo = z.infer<typeof ModelInfo>;

/** A per-model effort bound in settings (additive, 2026-07-27). At least one
 *  of min/max is required and min must not exceed max — the daemon rejects a
 *  violating entry with InvalidParams. */
export const ModelEffortBounds = z.object({
  min: z.string().min(1).optional(),
  max: z.string().min(1).optional(),
});
export type ModelEffortBounds = z.infer<typeof ModelEffortBounds>;

export const ModelListResult = z.object({
  models: z.array(ModelInfo),
  /** Daemon-owned new-session defaults (additive, 2026-07-23): what a
   *  model-less / effort-less session.create will be stamped with. Absent =
   *  the harness's own default applies (the daemon can't know it until a
   *  turn runs — clients keep rendering "Default"). */
  default_model: z.string().optional(),
  default_effort: z.string().optional(),
  /** The reasoning-effort levels this daemon ACCEPTS, ordered cheapest →
   *  deepest (additive, 2026-07-25). The daemon validates `reasoning_effort`
   *  against exactly this set at session.create and settings.update, so a
   *  client that renders anything else offers the user a guaranteed error.
   *  Clients MUST prefer this over a hardcoded list; empty/absent = fall back
   *  to whatever the client shipped with. */
  efforts: z.array(z.string()).optional(),
});
export type ModelListResult = z.infer<typeof ModelListResult>;

/** settings.get (additive, 2026-07-25) — the daemon-owned, client-editable
 *  slice of ~/.marmalade/daemon/config.json. Today: the new-session model and
 *  reasoning-effort defaults, i.e. the "Models" settings screen. Read is
 *  unauthenticated-but-connected like every other RPC here; the daemon trusts
 *  the connection, never the body. */
export const SettingsGetParams = z.object({});
export type SettingsGetParams = z.infer<typeof SettingsGetParams>;

export const SettingsResult = z.object({
  /** Model stamped on a model-less session.create. null = defer to the
   *  harness's own default. */
  default_model: z.string().nullable(),
  /** Reasoning effort stamped on an effort-less session.create. null = defer
   *  to the harness. */
  default_effort: z.string().nullable(),
  /** Keys pinned by an environment variable (MARMALADE_DEFAULT_MODEL /
   *  _EFFORT). env outranks the config file, so writing one of these would
   *  persist a value the daemon then ignores — settings.update REJECTS a
   *  locked key and clients should render the control disabled. */
  locked: z.array(z.string()),
  /** Per-model reasoning-effort bounds, keyed by model id (additive,
   *  2026-07-27). `{}` when nothing is bounded. Never env-locked — bounds are
   *  file-only, so this key is always editable. */
  model_efforts: z.record(z.string(), ModelEffortBounds).optional(),
});
export type SettingsResult = z.infer<typeof SettingsResult>;

/** settings.update — patch semantics on the same slice. An OMITTED key is
 *  left alone; an explicit null CLEARS it (back to "defer to the harness").
 *  The write is validated, persisted to config.json, and applied to the live
 *  daemon in the same call — no restart. Returns the full post-write state
 *  (the same shape settings.get returns), which is authoritative: other
 *  connected clients are not pushed an event and re-read on their next
 *  settings.get / model.list. */
export const SettingsUpdateParams = z.object({
  default_model: z.string().min(1).nullable().optional(),
  default_effort: z.string().min(1).nullable().optional(),
  /** Per-model effort bounds — a PER-MODEL patch, not a whole-map replace: an
   *  omitted model id keeps its bounds, an explicit null REMOVES that model's
   *  entry, and an object REPLACES it wholesale (so `{min}` after `{min,max}`
   *  drops the max). Unknown model ids and invalid bounds are rejected. */
  model_efforts: z.record(z.string().min(1), ModelEffortBounds.nullable()).optional(),
});
export type SettingsUpdateParams = z.infer<typeof SettingsUpdateParams>;

/** pairing.start (additive, M2) — mint a single-use bootstrap token a new
 *  device can exchange for a per-device bearer token. Callable only from an
 *  AUTHENTICATED connection (loopback, or an already-paired device): pairing
 *  authority is itself gated by auth. The result carries a ready-to-render
 *  setup code (base64url JSON {url, token, expires_at_ms}) for QR display. */
export const PairingStartParams = z.object({});
export type PairingStartParams = z.infer<typeof PairingStartParams>;

export const PairingStartResult = z.object({
  /** The raw single-use bootstrap token (never persisted in plaintext). */
  token: z.string(),
  /** ws:// URL the new device should connect to. */
  url: z.string(),
  /** base64url(JSON {url, token, expires_at_ms}) — the QR/paste payload. */
  setup_code: z.string(),
  /** UTC ms when the bootstrap token expires. */
  expires_at: z.number(),
});
export type PairingStartResult = z.infer<typeof PairingStartResult>;

/** pairing.claim (additive, M2) — exchange a bootstrap token for a per-device
 *  bearer token. The ONLY method an unauthenticated connection may call.
 *  device_id/platform are declared here (sanitized server-side) and become
 *  the token-bound VERIFIED identity — a later hello cannot override them. */
export const PairingClaimParams = z.object({
  token: z.string(),
  device_id: z.string(),
  platform: z.string().optional(),
});
export type PairingClaimParams = z.infer<typeof PairingClaimParams>;

export const PairingClaimResult = z.object({
  /** The per-device bearer token — shown once, stored hashed at rest. The
   *  device presents it in hello's auth.token (or ?token=) from now on. */
  device_token: z.string(),
  /** The sanitized device id the token is bound to. */
  device_id: z.string(),
  principal: z.string(),
});
export type PairingClaimResult = z.infer<typeof PairingClaimResult>;

/** device.list (additive, M2) — the device roster with pairing status. */
export const DeviceListParams = z.object({});
export type DeviceListParams = z.infer<typeof DeviceListParams>;

export const DeviceListResult = z.object({
  devices: z.array(z.object({
    device_id: z.string(),
    platform: z.string(),
    /** Device holds a live (non-revoked) bearer token. */
    paired: z.boolean(),
    /** Device has a gateway connection open right now. */
    connected: z.boolean(),
    first_seen: z.number(),
    last_seen: z.number(),
  })),
});
export type DeviceListResult = z.infer<typeof DeviceListResult>;

/** device.revoke (additive, M2) — delete a device's bearer tokens AND its
 *  roster row, and drop its live connections. Revocation is immediate. */
export const DeviceRevokeParams = z.object({
  device_id: z.string(),
});
export type DeviceRevokeParams = z.infer<typeof DeviceRevokeParams>;

export const DeviceRevokeResult = z.object({
  revoked: z.boolean(),
});
export type DeviceRevokeResult = z.infer<typeof DeviceRevokeResult>;

/** skills.list (additive, fork-rest-triage A) — the skills registry with
 *  per-harness enablement, for client settings screens. */
export const SkillsListParams = z.object({});
export type SkillsListParams = z.infer<typeof SkillsListParams>;

export const SkillsListResult = z.object({
  skills: z.array(z.object({
    name: z.string(),
    description: z.string().optional(),
    /** Enabled in at least one configured harness. */
    enabled: z.boolean(),
    /** The harnesses it is enabled for. */
    harnesses: z.array(z.string()),
  })),
});
export type SkillsListResult = z.infer<typeof SkillsListResult>;

/** skills.toggle (additive) — enable/disable a skill across the configured
 *  harnesses; the manifest persists and symlinks reconcile immediately. */
export const SkillsToggleParams = z.object({
  name: z.string(),
  enabled: z.boolean(),
});
export type SkillsToggleParams = z.infer<typeof SkillsToggleParams>;

export const SkillsToggleResult = z.object({
  applied: z.boolean(),
});
export type SkillsToggleResult = z.infer<typeof SkillsToggleResult>;

/** fs.defaults (additive, fork-rest-triage B) — defaults for the new-session
 *  workspace picker. */
export const FsDefaultsParams = z.object({});
export type FsDefaultsParams = z.infer<typeof FsDefaultsParams>;

export const FsDefaultsResult = z.object({
  /** The cwd session.create uses when none is passed. */
  default_cwd: z.string(),
});
export type FsDefaultsResult = z.infer<typeof FsDefaultsResult>;

/** fs.list (additive) — read-only directory listing for the workspace
 *  picker. Realpath-confined to the user's home directory: `..` traversal
 *  and symlinks pointing outside home are rejected. Names only, no contents. */
export const FsListParams = z.object({
  path: z.string(),
  /** Include dot-entries (hidden files/dirs) in the listing. Default false —
   *  the picker's "Show hidden" toggle sets this. Additive/back-compat:
   *  omitting it preserves the dotfile-hidden behavior. */
  show_hidden: z.boolean().optional(),
});
export type FsListParams = z.infer<typeof FsListParams>;

export const FsListResult = z.object({
  /** The resolved (real) path that was listed. */
  path: z.string(),
  entries: z.array(z.object({
    name: z.string(),
    dir: z.boolean(),
  })),
});
export type FsListResult = z.infer<typeof FsListResult>;

// ---- workspaces (additive, 2026-07-18) -------------------------------------
// Paseo-style folder workspaces: a human name + emoji over a folder path on
// the daemon host. Sessions belong to a workspace by cwd prefix match
// (deepest wins) — DERIVED, never stored: session.list stamps workspace_id
// per row so every client groups identically. A workspace is metadata, not a
// container: workspace.delete un-groups and never touches sessions.

/** What the folder brings to a session spawned in it — read live at list
 *  time. Display-only (the git chip is deliberately not a git UI). */
export const WorkspaceDetectionWire = z.object({
  /** Current branch, "detached", or null when the folder is not a git repo. */
  git_branch: z.string().nullable(),
  has_claude_md: z.boolean(),
  has_agents_md: z.boolean(),
  /** .md note count in the folder's .memory/ (0 when absent). */
  memory_notes: z.number().int(),
});
export type WorkspaceDetectionWire = z.infer<typeof WorkspaceDetectionWire>;

export const WorkspaceWire = z.object({
  workspace_id: z.string(),
  /** Realpath-resolved absolute folder path — the match key, immutable. */
  path: z.string(),
  name: z.string(),
  emoji: z.string().nullable(),
  created_at: z.number(),
  updated_at: z.number(),
  detection: WorkspaceDetectionWire,
});
export type WorkspaceWire = z.infer<typeof WorkspaceWire>;

/** workspace.create — realpath-confined to home (same rule as fs.list); the
 *  folder must exist. Absent name defaults to a prettified basename
 *  ("marmalade-client-android" → "Marmalade Client Android"). */
export const WorkspaceCreateParams = z.object({
  path: z.string().min(1),
  name: z.string().optional(),
  emoji: z.string().max(16).optional(),
});
export type WorkspaceCreateParams = z.infer<typeof WorkspaceCreateParams>;

export const WorkspaceCreateResult = z.object({ workspace: WorkspaceWire });
export type WorkspaceCreateResult = z.infer<typeof WorkspaceCreateResult>;

export const WorkspaceListParams = z.object({});
export type WorkspaceListParams = z.infer<typeof WorkspaceListParams>;

export const WorkspaceListResult = z.object({ workspaces: z.array(WorkspaceWire) });
export type WorkspaceListResult = z.infer<typeof WorkspaceListResult>;

/** workspace.update — rename / re-emoji only. The path is immutable (remove
 *  + re-add to move). emoji: null clears it. */
export const WorkspaceUpdateParams = z.object({
  workspace_id: z.string(),
  name: z.string().optional(),
  emoji: z.string().max(16).nullable().optional(),
});
export type WorkspaceUpdateParams = z.infer<typeof WorkspaceUpdateParams>;

export const WorkspaceUpdateResult = z.object({ workspace: WorkspaceWire });
export type WorkspaceUpdateResult = z.infer<typeof WorkspaceUpdateResult>;

/** workspace.context — read-only peek at what a session spawned in this
 *  workspace inherits: CLAUDE.md / AGENTS.md content (capped per file — a
 *  peek, not a file transfer; `truncated` says so), the .memory note names,
 *  and the current git branch. Files resolving outside home (symlinks) read
 *  as absent. No generic fs.read: the surface is workspace-scoped by id. */
export const WorkspaceContextParams = z.object({ workspace_id: z.string() });
export type WorkspaceContextParams = z.infer<typeof WorkspaceContextParams>;

export const WorkspaceContextFileWire = z.object({
  content: z.string(),
  truncated: z.boolean(),
});
export type WorkspaceContextFileWire = z.infer<typeof WorkspaceContextFileWire>;

export const WorkspaceContextResult = z.object({
  workspace_id: z.string(),
  claude_md: WorkspaceContextFileWire.nullable(),
  agents_md: WorkspaceContextFileWire.nullable(),
  /** .md note filenames in .memory/, sorted. Names only — notes may hold
   *  personal context; reading them stays on the host. */
  memory: z.array(z.string()),
  git_branch: z.string().nullable(),
});
export type WorkspaceContextResult = z.infer<typeof WorkspaceContextResult>;

/** workspace.delete — un-group. Sessions are kept, by design. */
export const WorkspaceDeleteParams = z.object({ workspace_id: z.string() });
export type WorkspaceDeleteParams = z.infer<typeof WorkspaceDeleteParams>;

export const WorkspaceDeleteResult = z.object({ deleted: z.boolean() });
export type WorkspaceDeleteResult = z.infer<typeof WorkspaceDeleteResult>;

/** mcp.list (additive, fork-rest-triage E) — the harness's MCP servers with
 *  marmalade-managed enablement. Enable/disable only in v1 — no add/remove/
 *  edit of definitions over the wire. */
export const McpListParams = z.object({});
export type McpListParams = z.infer<typeof McpListParams>;

export const McpListResult = z.object({
  servers: z.array(z.object({
    name: z.string(),
    transport: z.string(),
    enabled: z.boolean(),
    harness: z.string(),
    // Additive detail (2026-07-22): stdio launch line and/or http url. env is
    // never carried (secret channel). Absent for definitions with neither.
    command: z.string().optional(),
    url: z.string().optional(),
  })),
});
export type McpListResult = z.infer<typeof McpListResult>;

/** mcp.toggle — a toggle takes effect on the NEXT session spawn; the result
 *  says so explicitly so clients can message it honestly. */
export const McpToggleParams = z.object({
  name: z.string(),
  enabled: z.boolean(),
});
export type McpToggleParams = z.infer<typeof McpToggleParams>;

export const McpToggleResult = z.object({
  applied: z.boolean(),
  effective: z.literal("next_session"),
});
export type McpToggleResult = z.infer<typeof McpToggleResult>;

/** plugins.list / plugins.toggle (additive, fork-rest-triage E) — the
 *  harness's installed plugins; toggling flips the native enabledPlugins
 *  flag. Same next-session semantics as mcp.toggle. */
export const PluginsListParams = z.object({});
export type PluginsListParams = z.infer<typeof PluginsListParams>;

export const PluginsListResult = z.object({
  plugins: z.array(z.object({
    name: z.string(),
    enabled: z.boolean(),
    harness: z.string(),
    // Additive detail (2026-07-22): marketplace source (from the "name@source"
    // key) + version/description from the install record & plugin manifest.
    source: z.string().optional(),
    version: z.string().optional(),
    description: z.string().optional(),
  })),
});
export type PluginsListResult = z.infer<typeof PluginsListResult>;

export const PluginsToggleParams = z.object({
  name: z.string(),
  enabled: z.boolean(),
});
export type PluginsToggleParams = z.infer<typeof PluginsToggleParams>;

export const PluginsToggleResult = z.object({
  applied: z.boolean(),
  effective: z.literal("next_session"),
});
export type PluginsToggleResult = z.infer<typeof PluginsToggleResult>;

/** approval.respond (M2) — answer a parked approval.request. Correlation is
 *  session-keyed FIFO with request_id carried anyway: when request_id is
 *  absent, the OLDEST pending request for the session resolves (matches the
 *  shipped Android client, which sends {choice, session_id} only). The daemon
 *  serializes approvals per session, so FIFO is structurally unambiguous. */
export const ApprovalRespondParams = z.object({
  session_id: z.string(),
  /** once = allow this call; session = allow + remember pattern_key for this
   *  session; always = treated as session in v1 (allow_permanent:false hides
   *  the button client-side); deny = refuse with a message. */
  choice: z.enum(["once", "session", "always", "deny"]),
  request_id: z.string().optional(),
});
export type ApprovalRespondParams = z.infer<typeof ApprovalRespondParams>;

export const ApprovalRespondResult = z.object({
  resolved: z.boolean(),
});
export type ApprovalRespondResult = z.infer<typeof ApprovalRespondResult>;

/** clarify.respond — answer a parked clarify.request. The agent asked a
 *  structured question (AskUserQuestion); the daemon parked the tool call and
 *  broadcast the questions as a clarify.request event. Correlation mirrors
 *  approval.respond: request_id optional, FIFO fallback (requests are
 *  serialized per session). `answers` maps question text → chosen answer
 *  (multi-select answers comma-joined — the harness contract); `response` is
 *  freeform text typed instead of picking an option. Sending NEITHER means
 *  the user dismissed the question: the agent is told to use its own
 *  judgment and continue. */
export const ClarifyRespondParams = z.object({
  session_id: z.string(),
  request_id: z.string().optional(),
  answers: z.record(z.string(), z.string()).optional(),
  response: z.string().optional(),
});
export type ClarifyRespondParams = z.infer<typeof ClarifyRespondParams>;

export const ClarifyRespondResult = z.object({
  resolved: z.boolean(),
});
export type ClarifyRespondResult = z.infer<typeof ClarifyRespondResult>;

/** secret.respond — answer a parked secret.request. The agent asked for a
 *  credential by KEYRING ENTRY (request_secret); the daemon parked the tool
 *  call and pushed a secret.request event; the user typed the value into a
 *  secure input and it comes back here.
 *
 *  This is the one RPC on the protocol whose params carry a live credential.
 *  The contract is that `value` goes to the keyring's insert command and
 *  NOWHERE else: it is never echoed into a frame, never written to the message
 *  store / transcript cache / search index, and never logged. Clients must
 *  hold it the same way — no draft persistence, no clipboard, no analytics.
 *
 *  Correlation mirrors approval.respond: request_id optional, FIFO fallback
 *  (requests are serialized per session). Exactly one of `value` (provide it)
 *  or `deny: true` (refuse, with an optional reason the agent sees). */
export const SecretRespondParams = z.object({
  session_id: z.string(),
  request_id: z.string().optional(),
  /** The credential. Never logged, never stored, never echoed. */
  value: z.string().min(1).optional(),
  /** The user declined to provide it. */
  deny: z.literal(true).optional(),
  /** Optional human reason shown to the agent alongside the denial. */
  reason: z.string().optional(),
}).strict().refine(
  (p) => (p.value !== undefined) !== (p.deny === true),
  { message: "secret.respond takes exactly one of `value` or `deny: true`" },
);
export type SecretRespondParams = z.infer<typeof SecretRespondParams>;

export const SecretRespondResult = z.object({
  /** A pending request matched and was settled. */
  resolved: z.boolean(),
  /** The keyring insert succeeded (false on deny, and on a keyring failure). */
  stored: z.boolean(),
  /** Why the insert failed, when it did. Redacted of the value by keyring.ts. */
  error: z.string().optional(),
});
export type SecretRespondResult = z.infer<typeof SecretRespondResult>;

/** session.approvals (M2) — flip a session's approvals mode while it runs.
 *  Unlike model, mode is mutable: it gates the NEXT tool call, no spawn
 *  implications. Persisted on the row (survives resume). */
export const SessionApprovalsParams = z.object({
  session_id: z.string(),
  mode: z.enum(["auto", "prompt"]),
});
export type SessionApprovalsParams = z.infer<typeof SessionApprovalsParams>;

export const SessionApprovalsResult = z.object({
  /** The now-effective mode for the session. */
  mode: z.enum(["auto", "prompt"]),
});
export type SessionApprovalsResult = z.infer<typeof SessionApprovalsResult>;

/** Scheduled prompts (cron, additive — parity-map T2 #1). A job fires
 *  prompt.submit into its target session with origin source "cron"; v1
 *  delivery is the normal session event path (subscribers + unread). */
export const CronScheduleSchema = z.discriminatedUnion("kind", [
  z.object({
    kind: z.literal("cron"),
    /** 5-field cron expr (6-field with leading seconds also accepted). */
    expr: z.string().min(1),
    /** IANA timezone; absent = the daemon host's timezone. */
    tz: z.string().optional(),
    /** Deterministic stagger window in ms (0 = exact). Absent = 5 min for
     *  recurring top-of-hour exprs, 0 otherwise. */
    stagger_ms: z.number().int().min(0).optional(),
  }),
  z.object({
    kind: z.literal("every"),
    every_ms: z.number().int().min(1000),
    /** Phase anchor (UTC ms); absent = anchored at create time. */
    anchor_ms: z.number().int().min(0).optional(),
  }),
  /** One-shot: fires once at at_ms, then the job disables itself. */
  z.object({ kind: z.literal("at"), at_ms: z.number().int().min(0) }),
]);
export type CronScheduleWire = z.infer<typeof CronScheduleSchema>;

export const CronJobWire = z.object({
  job_id: z.string(),
  name: z.string().nullable(),
  session_id: z.string(),
  prompt: z.string(),
  schedule: CronScheduleSchema,
  enabled: z.boolean(),
  created_at: z.number(),
  updated_at: z.number(),
  /** Next fire time (stagger included); null when it will never fire. */
  next_run_at: z.number().nullable(),
  last_run_at: z.number().nullable(),
  last_status: z.enum(["ok", "error"]).nullable(),
  last_error: z.string().nullable(),
});
export type CronJobWire = z.infer<typeof CronJobWire>;

export const CronCreateParams = z.object({
  session_id: z.string(),
  prompt: z.string().min(1),
  schedule: CronScheduleSchema,
  name: z.string().max(120).optional(),
  enabled: z.boolean().default(true),
});
export type CronCreateParams = z.infer<typeof CronCreateParams>;

export const CronCreateResult = z.object({ job: CronJobWire });
export type CronCreateResult = z.infer<typeof CronCreateResult>;

export const CronListParams = z.object({});
export type CronListParams = z.infer<typeof CronListParams>;

/** cron.list returns EVERY job, disabled included — a list that silently
 *  skips jobs is how a dead job goes unnoticed for days. */
export const CronListResult = z.object({ jobs: z.array(CronJobWire) });
export type CronListResult = z.infer<typeof CronListResult>;

export const CronUpdateParams = z.object({
  job_id: z.string(),
  name: z.string().max(120).nullable().optional(),
  session_id: z.string().optional(),
  prompt: z.string().min(1).optional(),
  /** A schedule change recomputes next_run_at immediately (at-reschedule). */
  schedule: CronScheduleSchema.optional(),
  enabled: z.boolean().optional(),
});
export type CronUpdateParams = z.infer<typeof CronUpdateParams>;

export const CronUpdateResult = z.object({ job: CronJobWire });
export type CronUpdateResult = z.infer<typeof CronUpdateResult>;

export const CronDeleteParams = z.object({ job_id: z.string() });
export type CronDeleteParams = z.infer<typeof CronDeleteParams>;

export const CronDeleteResult = z.object({ deleted: z.boolean() });
export type CronDeleteResult = z.infer<typeof CronDeleteResult>;

/** cron.run_now — fire immediately, out-of-band: the scheduled next_run_at
 *  does not move. fired=false means the job is mid-run (single-flight). */
export const CronRunNowParams = z.object({ job_id: z.string() });
export type CronRunNowParams = z.infer<typeof CronRunNowParams>;

export const CronRunNowResult = z.object({ fired: z.boolean() });
export type CronRunNowResult = z.infer<typeof CronRunNowResult>;

// ── Attachments (T1) — staged per-session, consumed by the NEXT prompt.submit.
// Shapes MATCH the Android client's existing (dormant) calls: MarmaladeRpc.kt
// imageAttachBytes/fileAttach/imageDetach. Ported from hermes-agent (MIT).

/** image.attach_bytes — upload image bytes (base64, optionally a data-URL) and
 *  queue them on the session. The image TYPE is decided by magic bytes, not
 *  the declared filename (which is only a display hint). */
export const ImageAttachBytesParams = z.object({
  session_id: z.string(),
  content_base64: z.string(),
  filename: z.string().optional(),
});
export type ImageAttachBytesParams = z.infer<typeof ImageAttachBytesParams>;

/** Mirrors the client's ImageAttachResponse. `path` is the staged file (used
 *  by image.detach); `count` is the session's pending-attachment total. */
export const ImageAttachResult = z.object({
  attached: z.boolean(),
  path: z.string(),
  count: z.number().int(),
});
export type ImageAttachResult = z.infer<typeof ImageAttachResult>;

/** file.attach — stage a non-image file (base64 `data:` URL). A PDF is
 *  page-rendered to images (vision path); everything else is stored verbatim
 *  and returns an `@file:` ref the client prepends to the prompt text. */
export const FileAttachParams = z.object({
  session_id: z.string(),
  name: z.string(),
  data_url: z.string(),
});
export type FileAttachParams = z.infer<typeof FileAttachParams>;

/** Mirrors the client's FileAttachResponse. `ref_text`/`ref_path` are null for
 *  a PDF (its pages ride the queue as images — no text ref). */
export const FileAttachResult = z.object({
  attached: z.boolean(),
  name: z.string(),
  path: z.string(),
  ref_path: z.string().nullable().optional(),
  ref_text: z.string().nullable().optional(),
  uploaded: z.boolean(),
});
export type FileAttachResult = z.infer<typeof FileAttachResult>;

/** image.detach — drop a queued attachment by the `path` a prior attach
 *  returned, before it's consumed by a submit. */
export const ImageDetachParams = z.object({
  session_id: z.string(),
  path: z.string(),
});
export type ImageDetachParams = z.infer<typeof ImageDetachParams>;

export const ImageDetachResult = z.object({
  detached: z.boolean(),
  count: z.number().int(),
});
export type ImageDetachResult = z.infer<typeof ImageDetachResult>;

// ---- audio.transcribe ------------------------------------------------------
// Server-side STT fallback for clients whose on-device recognizer failed
// (Android voice popup: Whisper model load / sherpa-onnx inference errors).
// Gated on the "transcription" hello feature — the daemon advertises it only
// when an STT command resolves on the host, so clients never wait through a
// doomed round trip. This is a FALLBACK: on-device STT stays primary (private,
// low-latency); a server round trip per utterance is the degraded mode.

/** audio.transcribe — one finished utterance in, one transcript out. Audio is
 *  base64 (optionally a `data:` URL); `mime` picks the temp-file extension the
 *  STT command sees (default audio/wav). */
export const AudioTranscribeParams = z.object({
  audio_base64: z.string().min(1),
  mime: z.string().optional(),
});
export type AudioTranscribeParams = z.infer<typeof AudioTranscribeParams>;

export const AudioTranscribeResult = z.object({
  transcript: z.string(),
  /** Which STT ran, e.g. "whisper-ctranslate2" — diagnostic, clients display-only. */
  provider: z.string(),
});
export type AudioTranscribeResult = z.infer<typeof AudioTranscribeResult>;

// ---- usage.summary (parity-map T2 #8) --------------------------------------
// Daily rollups per purpose. Philosophy (OpenClaw): report provider truth, no
// cost guessing — cost_usd is the SDK's notional API-equivalent figure and may
// be untrustworthy under subscription auth; token counts are the ground-truth
// metric. Clients aggregate; the daemon just returns the window's rows.

export const UsageSummaryParams = z.object({
  /** Trailing window in days, ending today (daemon-local). Default 7. */
  days: z.number().int().min(1).max(90).default(7),
});
export type UsageSummaryParams = z.infer<typeof UsageSummaryParams>;

export const UsageEntryWire = z.object({
  day: z.string(), // YYYY-MM-DD, daemon-local
  purpose: z.string(),
  cost_usd: z.number(),
  input_tokens: z.number().int(),
  output_tokens: z.number().int(),
  turns: z.number().int(),
});
export type UsageEntryWire = z.infer<typeof UsageEntryWire>;

/** One subscription rate-limit window (e.g. Claude Code's 5-hour / weekly). */
export const PlanLimitWindowWire = z.object({
  /** Harness-native window id ("five_hour", "seven_day", "model:Fable"…). */
  id: z.string(),
  /** Human label ("5-hour", "Weekly (Opus)"). */
  label: z.string(),
  /** Percent of the window used, 0–100, or null when the harness can't say. */
  utilization: z.number().nullable(),
  /** ISO 8601 reset time, or null. */
  resets_at: z.string().nullable(),
});
export type PlanLimitWindowWire = z.infer<typeof PlanLimitWindowWire>;

/** Subscription plan limits as ONE harness reports them. An array on the wire
 *  so a future multi-harness daemon (e.g. a Codex adapter alongside Claude
 *  Code) adds its own entry — tagged by `harness` — with no wire change. */
export const PlanLimitsWire = z.object({
  /** Adapter name ("claude-code" today). */
  harness: z.string(),
  /** Plan tier ("pro" | "max" | …) or null when the harness doesn't say. */
  subscription_type: z.string().nullable(),
  windows: z.array(PlanLimitWindowWire),
});
export type PlanLimitsWire = z.infer<typeof PlanLimitsWire>;

export const UsageSummaryResult = z.object({
  /** The daemon's current day — the window's inclusive end; clients anchor
   *  "today" on THIS, not their own clock (tz drift). */
  today: z.string(),
  entries: z.array(UsageEntryWire),
  /** The daemon's daily budget guardrail (config file), or null when none is
   *  configured. `over` gates UNATTENDED turns only (cron fires are refused
   *  and recorded as job errors); interactive prompts are never blocked. */
  budget: z.object({
    metric: z.enum(["usd", "tokens"]),
    daily_limit: z.number(),
    /** Today's total in the budget's metric. */
    today_total: z.number(),
    over: z.boolean(),
  }).nullable(),
  /** Subscription plan-limit windows (Claude Code's /usage data), per
   *  harness. Empty when the harness has no plan-usage seam (OpenCode), no
   *  session is live to ask, or the account isn't on a subscription. */
  plan_limits: z.array(PlanLimitsWire),
});
export type UsageSummaryResult = z.infer<typeof UsageSummaryResult>;

// ---- terminal.* (additive, 2026-07-19) -------------------------------------
// Daemon-hosted PTY terminals alongside agent sessions (the T3
// "only on request" trigger was pulled; design note kept internally). A terminal is NOT
// a session: no identity, no transcript, no replay cache. Output is transient
// and attach-scoped — `terminal.data` events go only to connections currently
// attached to that terminal; a reconnecting client re-attaches and repaints
// from the snapshot in the attach RESULT. Feature-gated on hello "terminal"
// (advertised only when the daemon's PTY backend loaded AND config allows).
//
// SECURITY: a terminal is an arbitrary shell as the daemon's user. That is
// not a NEW trust boundary (any paired device can already drive the agent,
// which runs commands), but it bypasses the harness's approval layer — so the
// whole surface has a config kill-switch (terminal_enabled: false).

/** terminal.create — spawn a shell ($SHELL, login) as the daemon's user.
 *  cwd defaults to the daemon process's cwd. The creating connection is
 *  auto-attached (mirror of session.create's auto-subscribe). */
export const TerminalCreateParams = z.object({
  cols: z.number().int().min(2).max(500).default(80),
  rows: z.number().int().min(2).max(500).default(24),
  cwd: z.string().optional(),
});
export type TerminalCreateParams = z.infer<typeof TerminalCreateParams>;

export const TerminalInfoWire = z.object({
  terminal_id: z.string(),
  /** The spawned shell binary, for list display ("bash"). */
  shell: z.string(),
  cwd: z.string(),
  cols: z.number().int(),
  rows: z.number().int(),
  pid: z.number().int(),
  created_at: z.number(),
  /** Last input/output activity (UTC ms). */
  last_active: z.number(),
  /** Workspace membership, DERIVED from cwd exactly like session.list rows
   *  (cwd-prefix match, deepest wins — workspaces.matcher). null = not under
   *  any workspace ("quick terminal"). Stamped by the router; the manager is
   *  workspace-ignorant. */
  workspace_id: z.string().nullable(),
});
export type TerminalInfoWire = z.infer<typeof TerminalInfoWire>;

export const TerminalCreateResult = z.object({ terminal: TerminalInfoWire });
export type TerminalCreateResult = z.infer<typeof TerminalCreateResult>;

/** terminal.attach — join a terminal's live output stream. The result carries
 *  the scrollback snapshot (base64); the attach + snapshot are atomic, so a
 *  client writes the snapshot then applies subsequent terminal.data events
 *  with no gap and no overlap. Re-attach (reconnect) is the same call. */
export const TerminalAttachParams = z.object({
  terminal_id: z.string(),
});
export type TerminalAttachParams = z.infer<typeof TerminalAttachParams>;

export const TerminalAttachResult = z.object({
  terminal: TerminalInfoWire,
  /** Ring-buffer scrollback (base64 raw bytes; may begin mid-escape-sequence
   *  after eviction — terminal emulators tolerate that). */
  snapshot_b64: z.string(),
});
export type TerminalAttachResult = z.infer<typeof TerminalAttachResult>;

/** terminal.detach — leave the output stream (client navigated away). The
 *  terminal keeps running; this only stops delivery to THIS connection. */
export const TerminalDetachParams = z.object({
  terminal_id: z.string(),
});
export type TerminalDetachParams = z.infer<typeof TerminalDetachParams>;

/** terminal.input — write keystrokes/paste to the PTY. Base64 so control
 *  bytes (^C, ESC sequences from arrow keys) survive JSON transport intact. */
export const TerminalInputParams = z.object({
  terminal_id: z.string(),
  data_b64: z.string().min(1),
});
export type TerminalInputParams = z.infer<typeof TerminalInputParams>;

/** terminal.resize — the client's emulator geometry changed (rotation,
 *  keyboard, fit). The PTY gets SIGWINCH; full-screen apps re-draw. */
export const TerminalResizeParams = z.object({
  terminal_id: z.string(),
  cols: z.number().int().min(2).max(500),
  rows: z.number().int().min(2).max(500),
});
export type TerminalResizeParams = z.infer<typeof TerminalResizeParams>;

/** terminal.close — kill the shell. Attached connections get a terminal.exit
 *  event when the process actually dies; the roster row goes with it. */
export const TerminalCloseParams = z.object({
  terminal_id: z.string(),
});
export type TerminalCloseParams = z.infer<typeof TerminalCloseParams>;

export const TerminalCloseResult = z.object({ closed: z.boolean() });
export type TerminalCloseResult = z.infer<typeof TerminalCloseResult>;

export const TerminalListParams = z.object({});
export type TerminalListParams = z.infer<typeof TerminalListParams>;

export const TerminalListResult = z.object({
  terminals: z.array(TerminalInfoWire),
});
export type TerminalListResult = z.infer<typeof TerminalListResult>;

// ---- search.* (additive, 2026-07-24) ---------------------------------------
// Full-text search across session MESSAGE TEXT — user prompts and assistant
// prose only. Tool calls, tool results, thinking/reasoning and system prompts
// are never indexed (they live in separate event types, so the exclusion falls
// out of the data model rather than being filtered).
//
// Own namespace, not session.*: the call is cross-session, and a future
// `search.sessions` over titles/summaries slots in beside it.
//
// SCOPE is resolved daemon-side through the SAME workspace matcher session.list
// stamps rows with (cwd prefix, deepest wins) — never a SQL path match, or
// search scope would eventually disagree with the session list's grouping.
// The scope fields OR together; an absent (or empty) scope means everywhere the
// caller's principal can see. Archived sessions are OUT by default: archived is
// what you pushed out of view.

export const SearchScope = z.object({
  /** Sessions whose cwd matches one of these workspaces (deepest wins, so a
   *  nested repo workspace does NOT come back under its umbrella folder —
   *  same rule as session.list grouping). */
  workspace_ids: z.array(z.string()).optional(),
  /** Sessions matching NO workspace. */
  quick_chats: z.boolean().optional(),
  /** Explicit sessions — find-in-conversation is scope-of-one through this
   *  same method. Intersected with what the principal may see, never a
   *  bypass. */
  session_ids: z.array(z.string()).optional(),
  /** Which corpus to search. Absent = "live" — the daemon's own sessions,
   *  byte-identical to the behaviour before this field existed.
   *
   *  "archive" = the PRE-DAEMON Claude Code corpus (`~/.claude/projects`),
   *  indexed read-only. It is a different corpus, not a filter: nothing there
   *  can be opened, resumed or written. Within it —
   *    - `workspace_ids` / `quick_chats` scope by the archive session's `cwd`
   *      through the SAME workspace matcher live sessions use (deepest wins),
   *    - `session_ids` name ARCHIVE session ids (the Claude Code session
   *      UUIDs), not daemon session ids,
   *    - `include_archived` is meaningless and ignored — the whole corpus is
   *      historical, and none of it has an `archived` flag.
   *  An archive session that a live session already replays (its
   *  harness_session_id) is hidden, so a migrated conversation is found once,
   *  in the live corpus, where it can be opened. */
  corpus: z.enum(["live", "archive"]).optional(),
});
export type SearchScope = z.infer<typeof SearchScope>;

export const SearchMessagesParams = z.object({
  /** Raw user text. The daemon builds the FTS MATCH expression from it —
   *  clients must NOT send FTS syntax and the daemon never interpolates the
   *  raw string (a lone double-quote is a syntax error, not a no-op).
   *  `"quoted phrases"` are honoured; everything else is AND-ed terms, with a
   *  trailing `*` kept as a prefix marker. */
  query: z.string().min(2),
  scope: SearchScope.optional(),
  role: z.enum(["user", "assistant"]).optional(),
  /** Lower bound on message ts (UTC ms). */
  since: z.number().optional(),
  include_archived: z.boolean().default(false),
  /** "rank" = bm25 relevance (default), "recent" = newest first. */
  sort: z.enum(["rank", "recent"]).default("rank"),
  limit: z.number().int().min(1).max(50).default(20),
  offset: z.number().int().min(0).default(0),
});
export type SearchMessagesParams = z.infer<typeof SearchMessagesParams>;

/** Wrappers around the matched spans in `snippet` — Unicode private-use
 *  characters, so they can never collide with message text. The client strips
 *  them and applies its own styling. */
export const SNIPPET_OPEN = "\uE000";
export const SNIPPET_CLOSE = "\uE001";

/** Full hit text is capped — a peek, not a transcript fetch. */
export const SEARCH_TEXT_CAP = 4096;
/** The answering assistant message's preview is capped harder still. */
export const SEARCH_REPLY_CAP = 500;

export const SearchHitWire = z.object({
  // The deep-link tuple: which session, which message, where in the stream.
  session_id: z.string(),
  message_id: z.string(),
  seq: z.number().int(),
  role: z.enum(["user", "assistant"]),
  ts: z.number(),
  /** Match context with the spans wrapped in SNIPPET_OPEN/SNIPPET_CLOSE. */
  snippet: z.string(),
  /** The whole message, capped at SEARCH_TEXT_CAP — powers "peek" with no
   *  second round-trip. */
  text: z.string(),
  /** For role=user hits: the answering assistant message's text, capped at
   *  SEARCH_REPLY_CAP. Absent when the turn has no answer yet (or the hit is
   *  an assistant message). */
  reply_text: z.string().optional(),
});
export type SearchHitWire = z.infer<typeof SearchHitWire>;

/** Enough session context to render a hit without a second call. workspace_id
 *  comes from the daemon's matcher, so the client draws the workspace chip
 *  without re-deriving membership from paths. */
export const SearchSessionWire = z.object({
  title: z.string().nullable(),
  workspace_id: z.string().nullable(),
  archived: z.boolean(),
  last_active: z.number(),
  /** Present ONLY on entries from the pre-daemon archive corpus. Absent = a
   *  live daemon session (the client may open/resume it). An archive entry is
   *  read-only: `session.resume` and friends will not know its id — the
   *  transcript is fetched with `search.archive` instead. */
  corpus: z.literal("archive").optional(),
});
export type SearchSessionWire = z.infer<typeof SearchSessionWire>;

export const SearchMessagesResult = z.object({
  /** Total matches in scope — the page is `hits`. */
  total: z.number().int(),
  hits: z.array(SearchHitWire),
  sessions: z.record(z.string(), SearchSessionWire),
});
export type SearchMessagesResult = z.infer<typeof SearchMessagesResult>;

// ---- search.archive (additive, 2026-07-28) ---------------------------------
// Read-only transcript fetch for ONE pre-daemon archive session — the viewer
// behind an archive hit. Served entirely FROM THE INDEX (the FTS table stores
// the full message text), so a fetch never re-reads the .jsonl at query time
// and a since-deleted file still renders. There is no archive equivalent of
// session.resume by design: this corpus is history, not state.

export const SearchArchiveParams = z.object({
  /** An ARCHIVE session id (the Claude Code session UUID), as returned in an
   *  archive hit's `session_id`. */
  session_id: z.string(),
  limit: z.number().int().min(1).max(200).default(100),
  offset: z.number().int().min(0).default(0),
});
export type SearchArchiveParams = z.infer<typeof SearchArchiveParams>;

export const SearchArchiveMessageWire = z.object({
  /** 0-based position within the session's extracted messages — the archive's
   *  stand-in for `seq`, and the paging key. */
  ordinal: z.number().int(),
  role: z.enum(["user", "assistant"]),
  ts: z.number(),
  text: z.string(),
});
export type SearchArchiveMessageWire = z.infer<typeof SearchArchiveMessageWire>;

export const SearchArchiveResult = z.object({
  session: z.object({
    title: z.string().nullable(),
    cwd: z.string(),
    last_active: z.number(),
    message_count: z.number().int(),
  }),
  /** Total indexed messages in the session — the page is `messages`. */
  total: z.number().int(),
  /** Ascending by `ordinal`. */
  messages: z.array(SearchArchiveMessageWire),
});
export type SearchArchiveResult = z.infer<typeof SearchArchiveResult>;

/** A registry of method-name → param schema, for gateway-side validation. */
export const MethodParamSchemas = {
  "session.create": SessionCreateParams,
  "prompt.submit": PromptSubmitParams,
  "session.resume": SessionResumeParams,
  "session.subscribe": SessionSubscribeParams,
  "session.unsubscribe": SessionUnsubscribeParams,
  "session.seen": SessionSeenParams,
  "session.delete": SessionDeleteParams,
  "session.title": SessionTitleParams,
  "session.archive": SessionArchiveParams,
  "session.interrupt": SessionInterruptParams,
  "session.steer": SessionSteerParams,
  "session.compact": SessionCompactParams,
  "session.stop": SessionStopParams,
  "session.undo": SessionUndoParams,
  "session.summary": SessionSummaryParams,
  "session.main": SessionMainParams,
  "session.clear": SessionClearParams,
  "session.model": SessionModelParams,
  "session.effort": SessionEffortParams,
  "model.list": ModelListParams,
  "settings.get": SettingsGetParams,
  "settings.update": SettingsUpdateParams,
  "pairing.start": PairingStartParams,
  "pairing.claim": PairingClaimParams,
  "device.list": DeviceListParams,
  "device.revoke": DeviceRevokeParams,
  "skills.list": SkillsListParams,
  "skills.toggle": SkillsToggleParams,
  "fs.defaults": FsDefaultsParams,
  "fs.list": FsListParams,
  "workspace.create": WorkspaceCreateParams,
  "workspace.list": WorkspaceListParams,
  "workspace.update": WorkspaceUpdateParams,
  "workspace.delete": WorkspaceDeleteParams,
  "workspace.context": WorkspaceContextParams,
  "mcp.list": McpListParams,
  "mcp.toggle": McpToggleParams,
  "plugins.list": PluginsListParams,
  "plugins.toggle": PluginsToggleParams,
  "approval.respond": ApprovalRespondParams,
  "clarify.respond": ClarifyRespondParams,
  "secret.respond": SecretRespondParams,
  "session.approvals": SessionApprovalsParams,
  "session.fork": SessionForkParams,
  "cron.create": CronCreateParams,
  "cron.list": CronListParams,
  "cron.update": CronUpdateParams,
  "cron.delete": CronDeleteParams,
  "cron.run_now": CronRunNowParams,
  "image.attach_bytes": ImageAttachBytesParams,
  "file.attach": FileAttachParams,
  "image.detach": ImageDetachParams,
  "audio.transcribe": AudioTranscribeParams,
  "usage.summary": UsageSummaryParams,
  "search.messages": SearchMessagesParams,
  "search.archive": SearchArchiveParams,
  "terminal.create": TerminalCreateParams,
  "terminal.attach": TerminalAttachParams,
  "terminal.detach": TerminalDetachParams,
  "terminal.input": TerminalInputParams,
  "terminal.resize": TerminalResizeParams,
  "terminal.close": TerminalCloseParams,
  "terminal.list": TerminalListParams,
} as const;

export type KnownMethod = keyof typeof MethodParamSchemas;
