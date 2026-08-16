// types.ts — the webui's view models over the wire.
//
// The WIRE truth is @marmalade/protocol (frames/handshake/methods) — imported,
// never re-declared (spec: "the webui can never drift from the daemon the way a
// hand-copied types file can"). What lives HERE is only the client-side shape
// of things the protocol leaves as `z.unknown()` payloads: the stamped fields
// the daemon adds in identity.ts (message_id/seq/ts/origin) and the derived
// chat/session models the UI renders. These are payload READERS, not a second
// contract — the envelope is validated by the protocol schemas.

import type { ModelInfo, SettingsResult } from "@marmalade/protocol";
import type { EffortClamp } from "../components/efforts.js";

export type { ModelInfo };

/** The daemon-owned, client-editable settings slice (settings.get/update) —
 *  the new-session model + reasoning-effort defaults behind the Models card. */
export type DaemonSettings = SettingsResult;

/** Origin as it rides on stamped event payloads (identity.ts::wireOrigin). */
export interface WireOrigin {
  user_id: string;
  device_id: string;
  platform: string;
  /** "text" | "voice" | "cron" | future values — pass-through, never an
   *  exhaustive union: the daemon mints new sources (cron landed 2026-07-17)
   *  and old clients must not choke on them. */
  source: string;
  tz_offset?: number;
}

/** The stamped fields identity.ts adds to every session-scoped event payload.
 *  seq orders and dedups; ts is display metadata (never ordering) — the
 *  session-ids invariant. */
export interface StampedPayload {
  message_id?: string;
  parent_message_id?: string;
  seq?: number;
  ts?: number;
  origin?: WireOrigin;
  duration_ms?: number;
  [key: string]: unknown;
}

/** A row in the session list (router.ts session.list result). */
export interface SessionSummary {
  session_id: string;
  /** THE daemon-managed singleton main session (assistant surface). The daemon
   *  stamps exactly one row true (router.ts session.list). Clients pin it, show
   *  an "Assistant" chip, and hide delete/stop — it's daemon-owned; there is no
   *  "set main". Absent on a daemon that predates the feature; treated as
   *  false. */
  is_main?: boolean;
  purpose?: string;
  status?: string;
  lifecycle: string;
  run_state: string;
  harness?: string;
  last_active?: number;
  last_seq: number;
  seen_seq: number;
  model?: string;
  title?: string;
  topic?: string;
  summary?: string;
  summary_updated_at?: number;
  /** Fork lineage (T2 #3, additive): where this session branched from, or
   *  null. Pure display metadata for the "branched from …" chip — never a
   *  visibility filter (router.ts session.list). message_id is null for an
   *  end-of-session fork. */
  branched_from?: { session_id: string; message_id: string | null } | null;
  /** Archived flag (session.archive, additive 2026-07-24): shared list
   *  metadata, daemon-backed so every client agrees. Presentation only — the
   *  rail filters archived rows into a collapsed "Archived" section; the
   *  daemon never behaves differently for archived rows. Absent on a daemon
   *  that predates the feature; treated as false. */
  archived?: boolean;
  /** Context-window occupancy after this session's last completed turn
   *  (additive 2026-07-25) — persisted by the daemon from the usage the
   *  harness pushes, so a COLD-opened session shows context without waiting
   *  for a turn. null = unknown (never ran under a context-reporting harness,
   *  or the conversation was cleared); absent on a daemon that predates the
   *  fields, treated the same. Pure list metadata — never prompt input. */
  context_used?: number | null;
  context_max?: number | null;
  /** Derived daemon-side from the two above; the webui recomputes it from
   *  used/max (components/context.ts) rather than trusting a second source of
   *  truth, so this is carried for completeness only. */
  context_percent?: number | null;
  /** The workspace this session groups under, or null when its cwd falls
   *  outside every workspace folder (router.ts stamps it by deepest cwd-prefix
   *  match — DERIVED, never client-side). Absent on a daemon that predates the
   *  "workspaces" feature; treated as null. */
  workspace_id?: string | null;
}

/** session.subscribe result (methods.ts::SessionSubscribeResult). */
export interface SubscribeResult {
  session_id: string;
  replayed: number;
  last_seq: number;
  lifecycle: string;
  run_state: string;
}

/** A rendered chat message. Assistant messages accumulate deltas until the
 *  matching message.complete; user messages are one-shot. `id` is the
 *  server-minted message_id once bound; before the ack it may be a local
 *  synth id (session-ids rule 1). */
export interface ChatMessage {
  id: string;
  /** "notice" is a daemon-originated system line in the transcript (currently
   *  only effort.clamped) — not a turn, never a prompt input. It renders as a
   *  muted, non-interactive line and carries its structured payload in
   *  `clamp`; `text` stays empty because the wording is derived at render from
   *  the live model catalog. */
  role: "user" | "assistant" | "notice";
  text: string;
  /** The COMPLETE event's seq for a finished turn, the start's for a partial
   *  (session-ids rule 3). Drives the seen cursor. */
  seq: number;
  streaming: boolean;
  origin?: WireOrigin;
  /** Tool cards attached to this assistant message, in arrival order. */
  tools: ToolCard[];
  /** A user message sent mid-turn via session.steer (T2 #6) — rendered with a
   *  "steered" marker. Set optimistically by the sender and honored on the
   *  replayed message.user (payload steered:true) for other devices. */
  steered?: boolean;
  /** Whether this assistant message can serve as a session.fork cut (the
   *  daemon's has_cut_point on message.complete). false = hide "Branch from
   *  here" (fork-copied bubbles / no-uuid harnesses reject the cut);
   *  undefined = pre-flag transcript, keep the legacy offer. */
  hasCutPoint?: boolean;
  /** Set on role "notice" rows minted from effort.clamped — a per-model bound
   *  moved the requested reasoning effort (components/efforts.ts). */
  clamp?: EffortClamp;
}

/** A tool.start/complete pair rendered as a collapsible card. */
export interface ToolCard {
  toolUseId?: string;
  name: string;
  seq: number;
  running: boolean;
  durationMs?: number;
  detail?: string;
}

/** run_state values the daemon emits (session-manager.ts SessionRunState). */
export type RunState = "starting" | "idle" | "running" | "awaiting_input" | string;
