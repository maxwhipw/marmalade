// router.ts — turns gateway requests into harness sessions and streams events
// back. This is the M1 wiring that makes session.create / prompt.submit /
// session.resume actually do something (the M0 handler returned MethodNotFound).
//
// Every session goes through the policy factory (Decision 5 enforcement), gets
// a SQLite index row, a transcript cache, and heartbeats into the supervisor.

import { RpcMethodError, type Connection, type RequestHandler } from "./gateway.js";
import { ErrorCode, makeEvent, type JsonRpcEvent } from "@marmalade/protocol";
import {
  SessionCreateParams, PromptSubmitParams, SessionResumeParams,
  SessionSubscribeParams, SessionUnsubscribeParams, SessionSeenParams,
  SessionDeleteParams, SessionTitleParams, SessionArchiveParams,
  SessionInterruptParams, SessionStopParams, SessionSummaryParams,
  SessionSteerParams, SessionCompactParams, SessionUndoParams,
  SessionForkParams, FORK_UNSUPPORTED_REASON,
  SessionMainParams, SessionClearParams, SessionModelParams, SessionEffortParams,
  PairingClaimParams, DeviceRevokeParams,
  SkillsToggleParams, FsListParams,
  WorkspaceCreateParams, WorkspaceUpdateParams, WorkspaceDeleteParams,
  WorkspaceContextParams,
  McpToggleParams, PluginsToggleParams,
  ApprovalRespondParams, ClarifyRespondParams, SecretRespondParams, SessionApprovalsParams,
  CronCreateParams, CronUpdateParams, CronDeleteParams, CronRunNowParams,
  ImageAttachBytesParams, FileAttachParams, ImageDetachParams,
  AudioTranscribeParams,
  UsageSummaryParams,
  SearchMessagesParams, SearchArchiveParams, SEARCH_REPLY_CAP,
  TerminalCreateParams, TerminalAttachParams, TerminalDetachParams,
  TerminalInputParams, TerminalResizeParams, TerminalCloseParams,
  type TerminalInfoWire,
  type EffortClampedPayload,
  type SecretRequestPayload,
  type SecretResolvedPayload,
} from "@marmalade/protocol";
import { storeSecret as keyringStoreSecret } from "./keyring.js";
import { validateSchedule, computeNextFireAt } from "./cron-schedule.js";
import type { CronJobRecord } from "./cron-store.js";
import type { CronScheduler } from "./cron-scheduler.js";
import { randomBytes } from "node:crypto";
import { encodeSetupCode, detectTailnetIPv4 } from "./pairing.js";
import { sanitizeIdentityField } from "./identity.js";
import { listDirConfined } from "./fs-browse.js";
import { detectWorkspace, readWorkspaceContext, type WorkspaceRecord } from "./workspace-store.js";
import { renderAttachmentPreamble, type AttachmentStore } from "./attachments.js";
import type { SkillsStore } from "./skills-store.js";
import type { HarnessConfigStore } from "./harness-config.js";
import { homedir } from "node:os";
import { createSessionSpec, type Purpose, type Principal } from "./policy.js";
import {
  EFFORT_LEVELS,
  EffortBoundsSchema,
  clampEffort,
  envLockedSettings,
  writeConfigFile,
  type DaemonConfig,
  type EffortBounds,
  type EffortLevel,
} from "./config.js";
import type { SessionManager } from "./session-manager.js";
import type { TranscriptCache } from "./transcript-cache.js";
import { maxEventSeq, type SearchStore } from "./search-store.js";
import type { UsageMeter } from "./usage.js";
import type { HarnessAdapter, HarnessSession, SessionToolsApi } from "./adapter.js";
import { renderSessionTurns } from "./session-digest.js";
import { generateNaming, isPlaceholderTitle } from "./session-namer.js";
import { SessionIdentity, wireOrigin, originPreamble, mintMessageId, type Origin } from "./identity.js";
import type { ApprovalDecision, ApprovalInfo, ClarifyDecision, ClarifyQuestion } from "./adapter.js";
import { renderMainSystemPrompt } from "./behavior.js";
import { assembleStatePreload } from "./state-preload.js";
import { Subscriptions } from "./subscriptions.js";

export interface RouterDeps {
  cfg: DaemonConfig;
  sessions: SessionManager;
  transcripts: TranscriptCache;
  usage: UsageMeter;
  adapter: HarnessAdapter;
  /** Supplies the current day (YYYY-MM-DD) — injected so the core stays pure. */
  today: () => string;
  /** Monotonic-ish clock for heartbeats/records. */
  now: () => number;
  /** New daemon session id. */
  mintSessionId: () => string;
  /** Supervisor — so a failed/stopped session clears its silent-failure latch. */
  supervisor?: { clear(sessionId: string): void };
  /** Device ids with a gateway connection open RIGHT NOW (P3) — decorates the
   *  roster the `list_devices` tool returns. Injected from index.ts (the
   *  gateway owns connections); absent in tests = nothing connected. */
  connectedDevices?: () => Set<string>;
  /** Drop every live connection bound to a device id (M2 revocation must be
   *  immediate, not next-reconnect). Injected from index.ts. */
  disconnectDevice?: (deviceId: string) => void;
  /** Skills manifest + sync surface (Part A). Absent = skills.* unavailable. */
  skills?: SkillsStore;
  /** Harness MCP/plugins config surface (Part E). Absent = the mcp/plugins
   *  methods are unavailable. */
  harnessConfig?: HarnessConfigStore;
  /** The scheduled-prompts scheduler (T2 #1). Absent = cron.run_now is
   *  unavailable and CRUD skips re-arming (store-only, tests). */
  cron?: CronScheduler;
  /** Per-session attachment staging (T1). Absent = the attachment methods are
   *  unavailable (the "attachments" feature is still advertised statically,
   *  but index.ts always wires this in production). */
  attachments?: AttachmentStore;
  /** Server-side STT fallback (audio.transcribe). Absent = the method 404s;
   *  index.ts wires it only when the STT command resolves, and gates the
   *  "transcription" hello feature on the same check. */
  transcriber?: { transcribe(audioBase64: string, mime?: string): Promise<{ transcript: string; provider: string }> };
  /** Secondary alert publisher (ntfy, hardening #2). Absent = log-only. */
  ntfy?: { publish(title: string, message: string, opts?: { priority?: number }): Promise<void> };
  /** The FTS5 search sidecar (search.messages). Absent = the method 404s and
   *  every index-maintenance hook is a no-op; index.ts always wires it. */
  search?: SearchStore;
  /** PTY terminals (terminal.*). Absent = the methods
   *  404; index.ts wires it only when config allows AND node-pty loads, and
   *  gates the "terminal" hello feature on the same check. */
  terminals?: import("./terminal.js").TerminalManager;
  /** Where settings.update persists to. Injected so tests write to a temp
   *  file instead of the developer machine's real config; production leaves
   *  it unset and gets ~/.marmalade/daemon/config.json. */
  configPath?: string;
  /** The keyring WRITE side (secret-entry flow). Injected so tests can drive
   *  the flow without a real secret manager; absent = keyring.ts's storeSecret
   *  bound to cfg.keyring, which is what production runs. */
  storeSecret?: (entry: string, value: string) => Promise<void>;
  log?: (line: string) => void;
}

/** A live session's wiring: the harness handle plus the identity stamper and
 *  the stamped-emit path, so RPCs arriving later (prompt.submit, stop) share
 *  the same seq counter and event route as the adapter's stream. */
interface LiveSession {
  harness: HarnessSession;
  identity: SessionIdentity;
  /** Stamp (seq/ts/message ids) + append to transcript + send to the client. */
  emit(event: JsonRpcEvent): void;
  /** Flip runState + push a stamped status.update (P2). Never touches ids. */
  setRunState(runState: import("./session-manager.js").SessionRunState): void;
  /** Turn boundary: fold the transcript cache's raw delta runs into one
   *  consolidated event per message. Safe to call whenever no message is
   *  streaming; a streaming one is skipped, not corrupted. */
  compactTranscript(): void;
  /** M2 approvals state — parked tool calls waiting on a human. */
  approvals: SessionApprovals;
  /** Clarify state — parked agent questions (AskUserQuestion) waiting on a
   *  human answer. */
  clarifies: SessionClarifies;
  /** Secret-entry state — parked request_secret calls waiting on a human to
   *  type a credential the agent must never see. */
  secrets: SessionSecrets;
  /** Context-pressure reminder state. `percent` is the latest turn's
   *  context occupancy (normalize ResultInfo); `reminded` makes the nudge
   *  one-shot per threshold crossing — it re-arms when a later turn reports
   *  a percent back under the threshold (compaction/clear dropped it). */
  contextGuard: { percent?: number; reminded: boolean };
}

/** Per-session approvals bookkeeping (M2). The daemon SERIALIZES approval
 *  requests per session (promise chain in the router-side callback), so at
 *  most one approval.request is outstanding at a time and the client's
 *  session-keyed FIFO respond is structurally unambiguous. */
interface SessionApprovals {
  /** The adapter-facing seam: decide (or park) one tool call. */
  request(info: ApprovalInfo): Promise<ApprovalDecision>;
  /** request_id → parked request. Insertion order = FIFO. */
  pending: Map<string, {
    patternKey: string;
    /** The exact stamped frame that announced it — re-sent verbatim to a
     *  connection that subscribes mid-park (no re-stamp, no duplicate seq). */
    frame: string;
    resolve(d: ApprovalDecision): void;
  }>;
  /** pattern_keys allowed for the rest of this session (choice=session). */
  allowedPatterns: Set<string>;
  /** Resolve by request_id, or the OLDEST pending when absent. Returns false
   *  when nothing matches (unknown/already resolved). */
  respond(choice: "once" | "session" | "always" | "deny", requestId: string | undefined, deniedFrom: string): boolean;
  /** Resolve EVERY parked request as allow-with-log (the last subscriber
   *  detached mid-park — a dropped phone connection must not kill the turn). */
  drainAllow(reason: string): void;
  /** Resolve EVERY parked request as deny (session is stopping/deleting). */
  drainDeny(reason: string): void;
}

/** Per-session clarify bookkeeping — the approvals machinery's sibling for
 *  agent questions. Same serialization (one clarify.request outstanding at a
 *  time), same transient-event + mid-park-resubscribe compensation, but no
 *  mode gate and no pattern cache: a question is always for the user. Every
 *  drain resolves answered:false — for AskUserQuestion the "deny" message is
 *  just a tool_result telling the agent to use its own judgment; the run
 *  always survives. */
interface SessionClarifies {
  /** The adapter-facing seam: ask (or park) one agent question. */
  request(questions: ClarifyQuestion[]): Promise<ClarifyDecision>;
  /** request_id → parked question. Insertion order = FIFO. */
  pending: Map<string, {
    /** The exact stamped frame that announced it — re-sent verbatim to a
     *  connection that subscribes mid-park. */
    frame: string;
    resolve(d: ClarifyDecision): void;
  }>;
  /** Resolve by request_id, or the OLDEST pending when absent. No answers
   *  and no response = the user dismissed the question. Returns false when
   *  nothing matches. */
  respond(p: { requestId?: string; answers?: Record<string, string>; response?: string }): boolean;
  /** Settle EVERY parked question as unanswered WITH resolved events +
   *  runState restore (the last subscriber detached mid-park — the turn
   *  continues unattended). */
  drainUnanswered(reason: string): void;
  /** Resolve EVERY parked question directly, no events (session is
   *  stopping/deleting/erroring — mirrors approvals.drainDeny). */
  drainDrop(reason: string): void;
}

/** Per-session secret-entry bookkeeping — the approvals machinery's third
 *  sibling, and the one whose whole reason to exist is what it does NOT do.
 *
 *  Same shape as approvals: serialized (one outstanding), a pending map keyed
 *  by request_id whose stamped frame is re-sent verbatim to a mid-park
 *  subscriber, FIFO correlation on respond. Three deliberate differences:
 *
 *   1. NO auto-answer, ever. Approvals fall back to allow-with-log when nobody
 *      is attached (M2: a dropped phone must not kill a run). There is no
 *      equivalent for "please type your password" — an unanswerable request
 *      DENIES. That inverts the disconnect path relative to approvals, which
 *      is the single most important line in this file.
 *   2. The park requires a subscriber declaring the "secrets" CAPABILITY, not
 *      merely a subscriber: a client that cannot render a secure input will
 *      never answer, so parking for it is a hang. No capable client → the tool
 *      fails immediately with the terminal fallback (run gopass yourself).
 *   3. A TIMEOUT (approvals have none by design). A human who walks away from
 *      an approval card leaves a turn parked, which is recoverable; a
 *      credential prompt nobody answers should not hold a turn forever.
 *
 *  The VALUE never appears here: it enters through respond, goes to the
 *  keyring's stdin, and is gone. Nothing in this interface carries or returns
 *  it, and `resolve` only ever receives an outcome sentence. */
interface SessionSecrets {
  /** The tool-facing seam: park one credential request. Resolves to the
   *  sentence the agent reads as its tool result — never the value, never a
   *  rejection. */
  request(entry: string, description: string): Promise<string>;
  /** request_id → parked request. Insertion order = FIFO. */
  pending: Map<string, {
    entry: string;
    /** The exact stamped frame that announced it — re-sent verbatim to a
     *  secrets-capable connection that subscribes mid-park. */
    frame: string;
    resolve(message: string): void;
  }>;
  /** Settle by request_id, or the OLDEST pending when absent. With a value:
   *  writes it to the keyring and resolves the agent's tool call with "stored
   *  at <entry>". With deny: resolves with the refusal. Async because the
   *  keyring write is, and the client wants to know whether it landed. */
  respond(p: { requestId?: string; value?: string; deny?: true; reason?: string }):
    Promise<{ resolved: boolean; stored: boolean; error?: string }>;
  /** Resolve EVERY parked request as a denial (session stopping/deleting/
   *  erroring, or the last capable device went away). The ONLY drain there is. */
  drainDeny(reason: string): void;
}

/** Origin is derived from the AUTHENTICATED connection — never read from the
 *  message body (sec-H3). prompt.submit's zod schema strips any spoofed
 *  origin fields structurally; this is the only place origins are made. */
function originFromConn(conn: Connection, source: "text" | "voice"): Origin {
  return {
    userId: conn.principal,
    deviceId: conn.deviceId ?? "local",
    platform: conn.platform ?? (conn.legacy ? "legacy" : "unknown"),
    source,
    ...(conn.tzOffset === undefined ? {} : { tzOffset: conn.tzOffset }),
  };
}

/** The seen cursor is keyed by the same device identity origins use. */
function deviceIdFor(conn: Connection): string {
  return conn.deviceId ?? "local";
}

/** The origin the scheduler's fires carry — minted here, never from a body.
 *  originPreamble renders it as `via cron`, so the agent knows the turn is a
 *  scheduled prompt, not a user typing. */
/** Context occupancy as a percentage of the window, DERIVED at read from the
 *  two stored columns (session.list). Either half unknown → null: no window
 *  means no honest percentage, and clients render nothing rather than a
 *  fabricated bar. Same clamp/rounding as normalize.ts's live wire usage, so
 *  a cold-open number and the next turn's live number agree. */
function contextPercent(used: number | null, max: number | null): number | null {
  if (used === null || max === null || max <= 0) return null;
  return Math.min(100, Math.round((used / max) * 100));
}

/** Workspace row → wire shape, with the folder's live detection attached. */
function workspaceWire(rec: WorkspaceRecord) {
  const d = detectWorkspace(rec.path);
  return {
    workspace_id: rec.id,
    path: rec.path,
    name: rec.name,
    emoji: rec.emoji,
    created_at: rec.createdAt,
    updated_at: rec.updatedAt,
    detection: {
      git_branch: d.gitBranch,
      has_claude_md: d.hasClaudeMd,
      has_agents_md: d.hasAgentsMd,
      memory_notes: d.memoryNotes,
    },
  };
}

/** Bring the search index in line with ONE session's transcript. Always
 *  `reconcile`, never a bare `indexTail`: the watermark comparison costs one
 *  row read and makes every call site (turn end, undo, fork, boot) correct in
 *  both directions — a grown file indexes its tail, a truncated one rebuilds.
 *  Never throws: search is an accessory, and a failed index must not take a
 *  turn, an undo, or a fork down with it. */
function reindexSearch(deps: RouterDeps, sessionId: string, log: (line: string) => void): void {
  if (!deps.search) return;
  try {
    const events = deps.transcripts.replay(sessionId);
    deps.search.reconcile(sessionId, maxEventSeq(events), events);
  } catch (e) {
    log(`[search] reindex ${sessionId} failed: ${(e as Error).message}`);
  }
}

/** The model a model-less session.create is stamped with: the daemon config
 *  (env > config.json) first, else the ADAPTER's own preferred tier. Both
 *  model.list and session.create resolve through here so the picker's
 *  "Default (X)" label can never disagree with what a create actually gets. */
function effectiveDefaultModel(deps: RouterDeps): string | undefined {
  return deps.cfg.defaultModel ?? deps.adapter.defaultModel?.();
}

/** The configured effort bounds for a model id, if any (2026-07-27). A
 *  model-less session (no default, no pick) can't be bounded — there's nothing
 *  to look up — so it returns undefined and clampEffort is a no-op. */
function effortBoundsFor(deps: RouterDeps, model: string | undefined): EffortBounds | undefined {
  if (!model) return undefined;
  return deps.cfg.modelEfforts?.[model];
}

/** Record a clamp that actually CHANGED the requested effort as a durable
 *  `effort.clamped` transcript line (design lab option E3, signed off
 *  2026-07-27: quiet and permanent, never a toast to dismiss). Called at every
 *  clamp seam — session.create, the main session's create, session.effort.
 *
 *  No-op when nothing changed (the common case) or when there is no model —
 *  a model-less session has no bounds to hit, so `model` is always real here.
 *
 *  Live session → the session's own emit: stamped (seq/ts), appended to the
 *  transcript cache, fanned out to every subscriber. Not live (or the create
 *  seam before the child is up) → appended straight to the transcript with a
 *  seq issued the same way a spawn seeds one; the next spawn's startSeq reads
 *  it back via transcripts.lastSeq(), so seq stays monotonic and `since_seq`
 *  replay works by construction. Persisting is the load-bearing half. */
function recordEffortClamp(
  deps: RouterDeps,
  live: Map<string, LiveSession>,
  sessionId: string,
  requested: EffortLevel,
  effective: EffortLevel,
  model: string | undefined,
  log: (line: string) => void,
): void {
  if (requested === effective || !model) return;
  const order = EFFORT_LEVELS as readonly string[];
  // Clamped UP = the floor bit; clamped DOWN = the ceiling.
  const bound = order.indexOf(effective) > order.indexOf(requested) ? "min" : "max";
  const bounds = effortBoundsFor(deps, model);
  const payload = {
    requested, effective, model, bound,
    // With one edge configured the limit IS the effective level; spelled out
    // anyway so a client never has to infer it from the bounds map.
    limit: (bound === "min" ? bounds?.min : bounds?.max) ?? effective,
  } satisfies EffortClampedPayload;
  const event = makeEvent("effort.clamped", payload, sessionId);
  const s = live.get(sessionId);
  if (s) { s.emit(event); return; }
  const seq = Math.max(
    deps.sessions.messages.maxSeq(sessionId),
    deps.transcripts.lastSeq(sessionId),
    deps.sessions.get(sessionId)?.seqHighWater ?? 0,
  ) + 1;
  try {
    deps.transcripts.append(sessionId, {
      ...event,
      params: { ...event.params, payload: { ...payload, seq, ts: deps.now() } },
    });
  } catch (e) {
    log(`[transcript] effort.clamped append failed: ${(e as Error).message}`);
  }
}

/** settings.get's payload — also settings.update's return, so a client always
 *  reads the post-write truth from the same shape. */
function settingsSnapshot(deps: RouterDeps): {
  default_model: string | null;
  default_effort: string | null;
  locked: string[];
  model_efforts: Record<string, EffortBounds>;
} {
  return {
    // The EFFECTIVE default (config ?? adapter), not the raw config value:
    // the question a Models screen asks is "what will my next session run
    // on", and answering with null when the adapter has a perfectly good
    // default would render an empty selection.
    default_model: effectiveDefaultModel(deps) ?? null,
    // No adapter fallback for effort — null genuinely means "whatever the
    // harness picks", which clients render as its own option.
    default_effort: deps.cfg.defaultEffort ?? null,
    locked: envLockedSettings(),
    // Always an object ({} when unset) — a client rendering a bounds editor
    // shouldn't have to distinguish "no bounds" from "old daemon".
    model_efforts: deps.cfg.modelEfforts ?? {},
  };
}

function cronOrigin(): Origin {
  return { userId: "owner", deviceId: "cron", platform: "daemon", source: "cron" };
}

/** The router: the request handler plus the gateway's disconnect hook, so a
 *  closed connection is pruned from every subscriber set (no dead sockets
 *  accumulating, no events "delivered" into the void). */
export interface Router extends RequestHandler {
  disconnect(conn: Connection): void;
  /** The scheduler's fire seam: submit a scheduled prompt into a session with
   *  origin source "cron". Auto-revives a non-live session (same path as a
   *  client prompt.submit — the reaper stays invisible to cron too). Throws
   *  when the target session no longer exists; the scheduler records that as
   *  the job's last_error. */
  submitCron(sessionId: string, prompt: string): Promise<void>;
  /** One reaper pass (hardening): stop the harness child of every session
   *  that has sat runState=idle longer than cfg.idleReapMs. Returns the
   *  reaped ids. index.ts runs this on an interval; tests call it directly.
   *  The MAIN session is exempt — it stays warm by design. */
  reapIdle(): Promise<string[]>;
  /** The daemon-managed singleton main session (assistant plan 2026-07-19):
   *  returns its id, creating it (purpose=main, behavior-injected, state-
   *  preloaded) if absent and resuming its child if not live. index.ts calls
   *  this at boot so the assistant is warm before the first wake word; the
   *  session.main RPC routes here too. */
  ensureMain(): Promise<string>;
}

/** Router-internal shared state threaded through the spawn/resume helpers:
 *  the live-cap enforcer and the session-tools watch registry. */
interface RouterCtx {
  enforceCap(): Promise<void>;
  /** watch_session registry: watched session id → digest config. One-shot —
   *  removed when it fires. In-memory by design (a watch is about the run in
   *  front of you; it does not survive a daemon restart). */
  watches: Map<string, { note?: string; by: string }>;
}

/** The identity a cross-session prompt carries: minted HERE only, like cron's
 *  (never accepted from a client body). The device id names the sending
 *  session so the receiving agent knows who is talking. */
function agentOrigin(fromSessionId: string): Origin {
  return { userId: "owner", deviceId: `session:${fromSessionId}`, platform: "daemon", source: "agent" };
}

/** The meta key holding the singleton main session's id. */
const MAIN_SESSION_KEY = "main_session_id";

export function createRouter(deps: RouterDeps): Router {
  const live = new Map<string, LiveSession>(); // daemon session id → wiring
  // P4: per-session subscriber registry — event delivery is no longer welded
  // to the creating connection (the H2 pre-M2 gate). The creator is auto-
  // subscribed (legacy clients that never call session.subscribe keep
  // working); other connections attach via session.subscribe. A subscription
  // is connection-scoped interest: it survives the session going idle/ended
  // (a later resume streams to it) and dies with the connection.
  const subs = new Subscriptions();
  const log = deps.log ?? (() => {});
  // Budget-breach alert latch: ONE ntfy alert per breach episode (set on the
  // first refused cron fire, reset when a fire goes through under budget) —
  // not one per skipped fire.
  let budgetBreachAlerted = false;

  // ── Reaping (hardening) ────────────────────────────────────────────────────
  // One stop path shared by session.stop, the idle reaper, and cap eviction:
  // the session ENDS (resumable, same id) and its child is released — this is
  // exactly what session.stop has always done, so clients see a known state.
  const stopLive = async (id: string, s: LiveSession, reason: string): Promise<void> => {
    s.approvals.drainDeny(reason);
    s.clarifies.drainDrop(reason);
    s.secrets.drainDeny(reason);
    await s.harness.stop().catch((e) => log(`[session ${id}] harness stop failed (${reason}): ${(e as Error).message}`));
    s.identity.closeOpen("incomplete");
    live.delete(id);
    deps.sessions.end(id, deps.now());
    deps.supervisor?.clear(id);
    s.emit(makeEvent("status.update", { session_id: id, lifecycle: "ended", run_state: "idle" }, id));
    // Terminal for this child: no further result will arrive, so fold here.
    s.compactTranscript();
  };

  const reapIdle = async (): Promise<string[]> => {
    const now = deps.now();
    const reaped: string[] = [];
    for (const [id, s] of [...live]) {
      // The main session is exempt: always warm is its contract.
      if (id === deps.sessions.getMeta(MAIN_SESSION_KEY)) continue;
      const rec = deps.sessions.get(id);
      // Only idle sessions are reapable: running/starting have a turn in
      // flight, awaiting_input is a human decision (not a failure, not
      // abandonment), and hung belongs to the supervisor's alert flow.
      if (!rec || rec.runState !== "idle") continue;
      if (now - rec.lastActive < deps.cfg.idleReapMs) continue;
      await stopLive(id, s, "idle-reaped");
      reaped.push(id);
      log(`[reaper] stopped idle session ${id} (idle ${now - rec.lastActive}ms)`);
    }
    return reaped;
  };

  // Cap on live children: evict the longest-idle IDLE session to make room;
  // if every slot has a turn in flight, fail the create/resume visibly —
  // silently killing a running turn to admit a new one is worse.
  const enforceCap = async (): Promise<void> => {
    while (live.size >= deps.cfg.maxLiveSessions) {
      let victim: { id: string; s: LiveSession; lastActive: number } | null = null;
      for (const [id, s] of live) {
        // Never evict the main session — it stays warm by design.
        if (id === deps.sessions.getMeta(MAIN_SESSION_KEY)) continue;
        const rec = deps.sessions.get(id);
        if (!rec || rec.runState !== "idle") continue;
        if (!victim || rec.lastActive < victim.lastActive) victim = { id, s, lastActive: rec.lastActive };
      }
      if (!victim) {
        throw new RpcMethodError(
          ErrorCode.InvalidParams,
          `live session cap reached (${live.size}/${deps.cfg.maxLiveSessions}, all busy) — stop a session or raise MARMALADE_MAX_LIVE_SESSIONS`,
        );
      }
      await stopLive(victim.id, victim.s, "evicted: live session cap");
      log(`[reaper] evicted idle session ${victim.id} for the live cap`);
    }
  };

  const ctx: RouterCtx = { enforceCap, watches: new Map() };

  const mainSessionId = (): string | null => deps.sessions.getMeta(MAIN_SESSION_KEY);

  // The singleton main session (assistant plan): get-or-create + keep warm.
  // Serialized through one in-flight promise so concurrent session.main calls
  // (boot + first client) can't race two creates.
  let ensuring: Promise<string> | null = null;
  const ensureMain = (): Promise<string> => {
    ensuring ??= (async () => {
      const existing = mainSessionId();
      if (existing && deps.sessions.get(existing)) {
        if (!live.has(existing)) {
          await enforceCap();
          await resumeSession(deps, live, subs, null, { session_id: existing }, log, ctx);
        }
        return existing;
      }
      // First boot (or the row vanished with a wiped db): mint THE main
      // session. cwd defaults to home; the behavior spec + state preload ride
      // the purpose=main path in spawnAndWire.
      await enforceCap();
      const spec = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, deps.cfg);
      const id = deps.mintSessionId();
      // The main session gets the daemon defaults too (it's daemon-created,
      // so there's no client value to defer to).
      const mainModel = effectiveDefaultModel(deps);
      // Same per-model clamp as session.create; an unset default stays unset.
      const mainEffort = deps.cfg.defaultEffort === undefined
        ? undefined
        : clampEffort(deps.cfg.defaultEffort, effortBoundsFor(deps, mainModel));
      deps.sessions.create(id, spec, deps.adapter.name, deps.now(), mainModel, undefined, mainEffort);
      deps.sessions.setTitle(id, "Marmalade");
      deps.sessions.setMeta(MAIN_SESSION_KEY, id);
      spawnAndWire(deps, live, subs, null, id, spec, {
        ...(mainModel ? { model: mainModel } : {}),
        ...(mainEffort ? { effort: mainEffort } : {}),
      }, log, ctx);
      // E3: same durable record as session.create, after the spawn for the
      // same reason — the main session is daemon-created, so a default_effort
      // the main model doesn't allow leaves its note at the top of the session.
      if (deps.cfg.defaultEffort !== undefined) {
        recordEffortClamp(deps, live, id, deps.cfg.defaultEffort, mainEffort!, mainModel, log);
      }
      log(`[main-session] created singleton main ${id}`);
      return id;
    })().finally(() => { ensuring = null; });
    return ensuring;
  };

  const handler: RequestHandler = async (method, params, conn) => {
    switch (method) {
      case "session.create":
        await enforceCap();
        return createSession(deps, live, subs, conn, SessionCreateParams.parse(params ?? {}), log, ctx);
      case "session.main": {
        SessionMainParams.parse(params ?? {});
        return { session_id: await ensureMain() };
      }
      case "session.resume": {
        const p = SessionResumeParams.parse(params ?? {});
        // Cap only when this resume will actually spawn a child — the
        // resume-while-live path just attaches a subscriber.
        if (!live.has(p.session_id)) await enforceCap();
        return resumeSession(deps, live, subs, conn, p, log, ctx);
      }
      case "session.subscribe": {
        const p = SessionSubscribeParams.parse(params ?? {});
        const rec = deps.sessions.get(p.session_id);
        if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        // Replay-then-attach, with NO await in between: emits are synchronous
        // (append happens before send in emit), so everything cached when the
        // replay was read is sent here, and everything after lands via the
        // subscription — gapless, no duplicates across the boundary.
        const cached = deps.transcripts.replay(p.session_id);
        // Pass 1 — the STRADDLE case. The cache folds a message's delta run
        // into one event at turn end (TranscriptCache.compact), so a client
        // that dropped MID-run and reconnects after the turn settled holds a
        // partial prefix of text the one replayed event now carries WHOLE:
        // appending it would duplicate the prose. Detect it by span —
        // first_seq <= since_seq < seq — and remember the message id. Only a
        // folded run can straddle, and seq is monotonic through the file, so
        // this is at most the one message the cursor sits inside.
        const straddled = new Set<string>();
        for (const ev of cached) {
          const pl = ev.params.payload as
            | { consolidated?: boolean; message_id?: string; first_seq?: number; seq?: number }
            | undefined;
          if (pl?.consolidated !== true) continue;
          if (typeof pl.message_id !== "string" || typeof pl.first_seq !== "number" || typeof pl.seq !== "number") continue;
          if (pl.first_seq <= p.since_seq && p.since_seq < pl.seq) straddled.add(pl.message_id);
        }
        let replayed = 0;
        let lastSeq = 0;
        for (const ev of cached) {
          const pl = ev.params.payload as { seq?: number; message_id?: string } | undefined;
          const seq = pl?.seq;
          if (typeof seq === "number") {
            if (seq > lastSeq) lastSeq = seq;
            // A straddled message's message.start is re-sent from BELOW the
            // watermark: both clients treat a repeat start for an id they
            // already hold as "drop the stale partial and rebuild" (webui
            // startAssistantMessage; Android onMessageStart deletes the row),
            // so the consolidated event that follows lands on a clean message
            // instead of doubling it. File order puts the start first.
            const rebuild = ev.params.type === "message.start"
              && typeof pl?.message_id === "string"
              && straddled.has(pl.message_id);
            if (seq <= p.since_seq && !rebuild) continue;
          } else if (p.since_seq > 0) {
            continue; // unstamped pre-P1 event: only full replays include it
          }
          try { conn.ws.send(JSON.stringify(ev)); replayed++; } catch { break; }
        }
        subs.add(p.session_id, conn);
        // M2: approval.request is transient (never cached), so compensate for
        // a mid-park attach — re-send any still-pending request AFTER replay,
        // verbatim (same stamped frame, no duplicate seq).
        const liveSub = live.get(p.session_id);
        if (liveSub) {
          for (const pa of liveSub.approvals.pending.values()) {
            try { conn.ws.send(pa.frame); } catch { /* connection gone */ }
          }
          // clarify.request is transient too — same mid-park compensation.
          for (const pc of liveSub.clarifies.pending.values()) {
            try { conn.ws.send(pc.frame); } catch { /* connection gone */ }
          }
          // secret.request likewise, but only to a client that declared the
          // "secrets" capability: one that can't render a secure input has
          // nothing to do with the frame, and the request parked on the
          // premise that a capable device is watching.
          if (conn.capabilities.includes("secrets")) {
            for (const ps of liveSub.secrets.pending.values()) {
              try { conn.ws.send(ps.frame); } catch { /* connection gone */ }
            }
          }
        }
        return {
          session_id: p.session_id,
          replayed,
          last_seq: lastSeq,
          lifecycle: rec.lifecycle,
          run_state: rec.runState,
        };
      }
      case "session.unsubscribe": {
        const p = SessionUnsubscribeParams.parse(params ?? {});
        subs.remove(p.session_id, conn);
        return {};
      }
      case "session.seen": {
        const p = SessionSeenParams.parse(params ?? {});
        if (!deps.sessions.get(p.session_id)) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        const seq = deps.sessions.seen.stamp(deviceIdFor(conn), p.session_id, p.seq, deps.now());
        return { seq };
      }
      case "prompt.submit": {
        const p = PromptSubmitParams.parse(params ?? {});
        // Auto-revive (hardening): a submit to a known-but-not-live session
        // (reaped, stopped, or errored) resumes it in place — reaping stays
        // invisible to clients beyond first-turn latency. A revive that fails
        // to spawn leaves the session non-live and the submit rejects below.
        if (!live.has(p.session_id) && deps.sessions.get(p.session_id)) {
          await enforceCap();
          await resumeSession(deps, live, subs, conn, { session_id: p.session_id }, log, ctx);
        }
        return submitPrompt(deps, live, conn, p);
      }
      case "session.interrupt": {
        const id = SessionInterruptParams.parse(params ?? {}).session_id;
        const s = live.get(id);
        if (!s) throw new RpcMethodError(ErrorCode.InvalidParams, `session ${id} not live`);
        await s.harness.interrupt();
        // The interrupted message's id persists; only its status records the
        // interruption (P1: no state transition mints or changes an id).
        s.identity.closeOpen("incomplete");
        return {};
      }
      case "session.steer": {
        // T2 #6: inject guidance into the RUNNING turn. The harness merges a
        // mid-turn streamed user message into the in-flight agent loop
        // (verified live on Claude Code 2026-07-18) — one turn, one result.
        const p = SessionSteerParams.parse(params ?? {});
        const s = live.get(p.session_id);
        if (!s) throw new RpcMethodError(ErrorCode.InvalidParams, `session ${p.session_id} not live`);
        if (deps.sessions.get(p.session_id)?.runState !== "running") {
          // No turn to steer. Not auto-downgraded to a submit: the client
          // asked for mid-turn semantics; a stale steer landing as a fresh
          // prompt would be a silent surprise. (A turn completing in the race
          // window after this check just queues the message as the next turn
          // — same effect, benign.)
          throw new RpcMethodError(ErrorCode.InvalidParams, "no turn in flight — use prompt.submit");
        }
        const origin = originFromConn(conn, p.source ?? "text");
        const u = await steerCore(deps, s, p.session_id, p.prompt, origin);
        deps.sessions.seen.stamp(deviceIdFor(conn), p.session_id, u.seq, u.ts);
        return { message_id: u.messageId, seq: u.seq, ts: u.ts };
      }
      case "session.compact": {
        // T2 #11a: manual compact TRIGGER only — the engine stays
        // harness-delegated (T3). Queue-and-return: outcome arrives as
        // session.compaction events (started → completed|failed + boundary).
        const p = SessionCompactParams.parse(params ?? {});
        if (!deps.sessions.get(p.session_id)) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        // Same auto-revive as prompt.submit: compacting a reaped session is
        // the expected use ("shrink it before I come back to it").
        if (!live.has(p.session_id)) {
          await enforceCap();
          await resumeSession(deps, live, subs, conn, { session_id: p.session_id }, log, ctx);
        }
        const s = live.get(p.session_id);
        if (!s) throw new RpcMethodError(ErrorCode.InvalidParams, `session ${p.session_id} not live`);
        if (!s.harness.compact) {
          throw new RpcMethodError(ErrorCode.InvalidParams, `harness "${deps.adapter.name}" has no manual-compact support`);
        }
        const st = deps.sessions.get(p.session_id)?.runState;
        if (st === "running" || st === "starting") {
          // Compacting under a half-finished turn interleaves badly — same
          // posture as session.fork's mid-turn guard: retry when it completes.
          throw new RpcMethodError(ErrorCode.InvalidParams, "session has a turn in flight — compact after it completes");
        }
        await s.harness.compact();
        return {};
      }
      case "session.stop": {
        const id = SessionStopParams.parse(params ?? {}).session_id;
        if (id === mainSessionId()) {
          throw new RpcMethodError(ErrorCode.InvalidParams,
            "the main session stays warm by design — session.interrupt stops its turn, session.clear resets it");
        }
        const s = live.get(id);
        if (s) await stopLive(id, s, "session stopped");
        return {};
      }
      case "session.clear": {
        // Reset the conversation IN PLACE: same session id (state surgery,
        // not a new identity — the fork/undo family's sibling). This is how
        // the main session, which cannot be deleted, starts over.
        const p = SessionClearParams.parse(params ?? {});
        const rec = deps.sessions.get(p.session_id);
        if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        if (rec.runState === "running" || rec.runState === "starting") {
          throw new RpcMethodError(ErrorCode.InvalidParams, "session has a turn in flight — clear after it completes");
        }
        const s = live.get(p.session_id);
        if (s) await stopLive(p.session_id, s, "session cleared");
        // High-water BEFORE the wipe (P1: a cleared seq is never reissued).
        const seqHighWater = Math.max(
          deps.sessions.messages.maxSeq(p.session_id),
          deps.transcripts.lastSeq(p.session_id),
          rec.seqHighWater,
        );
        deps.sessions.clearConversation(p.session_id, seqHighWater, deps.now());
        deps.transcripts.delete(p.session_id);
        // The conversation is gone; the session id survives. Drop its rows AND
        // its watermark so the next turn indexes from scratch.
        deps.search?.dropSession(p.session_id);
        deps.attachments?.clear(p.session_id);
        // Transient, like session.deleted/undone: a replayed cleared event
        // would describe a transcript that no longer exists.
        subs.sendRaw(p.session_id, JSON.stringify(makeEvent("session.cleared", { session_id: p.session_id }, p.session_id)));
        // The main session goes straight back to warm — and since its
        // harness_session_id is now null, the respawn re-injects the state
        // preload like a first boot.
        if (p.session_id === mainSessionId()) {
          await enforceCap();
          await resumeSession(deps, live, subs, null, { session_id: p.session_id }, log, ctx);
        }
        log(`[session ${p.session_id}] cleared in place (seq high-water ${seqHighWater})`);
        return { cleared: true };
      }
      case "session.model": {
        // Change the session's model. Stored on the row; a live idle child is
        // restarted so it applies NOW (context survives via harness resume).
        const p = SessionModelParams.parse(params ?? {});
        const rec = deps.sessions.get(p.session_id);
        if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        if (rec.runState === "running" || rec.runState === "starting") {
          throw new RpcMethodError(ErrorCode.InvalidParams, "session has a turn in flight — change the model after it completes");
        }
        deps.sessions.setModel(p.session_id, p.model);
        // Re-clamp the stored effort against the NEW model's bounds — the
        // model switch is exactly the seam bounds exist for (Opus@xhigh →
        // Fable must not keep xhigh on the row). Done server-side so EVERY
        // client is protected, not just ones that know to re-send
        // session.effort after a model change. Clamp before the restart so
        // the respawn already carries the effective level; null stays null.
        let effort = (rec.reasoningEffort ?? null) as EffortLevel | null;
        if (effort !== null && (EFFORT_LEVELS as readonly string[]).includes(effort)) {
          const clamped = clampEffort(effort, effortBoundsFor(deps, p.model));
          if (clamped !== effort) {
            deps.sessions.setEffort(p.session_id, clamped);
            recordEffortClamp(deps, live, p.session_id, effort, clamped, p.model, log);
            effort = clamped;
          }
        }
        const s = live.get(p.session_id);
        if (s) {
          await stopLive(p.session_id, s, "model change");
          await enforceCap();
          await resumeSession(deps, live, subs, conn, { session_id: p.session_id }, log, ctx);
        }
        log(`[session ${p.session_id}] model → ${p.model}${s ? " (child restarted)" : " (applies on next spawn)"}`);
        // reasoning_effort is additive: the post-clamp truth, so a client can
        // adopt it from the result instead of re-fetching the session row.
        return { model: p.model, ...(effort ? { reasoning_effort: effort } : {}) };
      }
      case "session.effort": {
        // The mutable twin of session.model (2026-07-25). Effort used to be
        // create-only, so a client's picker was a no-op on every existing
        // session AND visibly snapped back: the pick was never stored, and the
        // next spawn's session.info re-announced the row's stored effort.
        const p = SessionEffortParams.parse(params ?? {});
        const rec = deps.sessions.get(p.session_id);
        if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        if (!(EFFORT_LEVELS as readonly string[]).includes(p.reasoning_effort)) {
          throw new RpcMethodError(
            ErrorCode.InvalidParams,
            `reasoning_effort "${p.reasoning_effort}" is not one of ${EFFORT_LEVELS.join("/")}`,
          );
        }
        if (rec.runState === "running" || rec.runState === "starting") {
          throw new RpcMethodError(ErrorCode.InvalidParams, "session has a turn in flight — change the reasoning effort after it completes");
        }
        // Clamp into this session's model's bounds and store/return the
        // CLAMPED value — the result carries reasoning_effort, so a client
        // that renders the answer shows the clamp by construction.
        const effort = clampEffort(
          p.reasoning_effort as EffortLevel,
          effortBoundsFor(deps, rec.model ?? undefined),
        );
        deps.sessions.setEffort(p.session_id, effort);
        // E3: the durable record goes in HERE, at this point in the transcript
        // — before the child is stopped for the restart below, so it rides the
        // still-live session's emit (stamped + cached + broadcast).
        recordEffortClamp(
          deps, live, p.session_id,
          p.reasoning_effort as EffortLevel, effort, rec.model ?? undefined, log,
        );
        const s = live.get(p.session_id);
        if (s) {
          await stopLive(p.session_id, s, "effort change");
          await enforceCap();
          await resumeSession(deps, live, subs, conn, { session_id: p.session_id }, log, ctx);
        }
        const clamped = effort === p.reasoning_effort ? "" : ` (clamped from ${p.reasoning_effort})`;
        log(`[session ${p.session_id}] effort → ${effort}${clamped}${s ? " (child restarted)" : " (applies on next spawn)"}`);
        return { reasoning_effort: effort };
      }
      case "session.undo": {
        // T2 #6 second half (design note kept internally; signed off
        // 2026-07-18): pop the LAST COMPLETED TURN in place — same
        // session id, rows/transcript deleted, harness rewound lazily via a
        // pending resume-at consumed by the next spawn.
        const p = SessionUndoParams.parse(params ?? {});
        const rec = deps.sessions.get(p.session_id);
        if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        if (rec.runState === "running" || rec.runState === "starting") {
          throw new RpcMethodError(ErrorCode.InvalidParams, "session has a turn in flight — undo after it completes");
        }
        if (!deps.adapter.supportsResumeAt) {
          throw new RpcMethodError(ErrorCode.InvalidParams, `harness "${deps.adapter.name}" cannot rewind sessions`);
        }
        const rows = deps.sessions.messages.list(p.session_id);
        // The turn to pop starts at the last NON-steer user message (steer
        // rows are mid-turn injections, popped along with their turn).
        const turnStart = [...rows].reverse().find((m) => m.role === "user" && !m.steered);
        if (!turnStart) throw new RpcMethodError(ErrorCode.InvalidParams, "nothing to undo — no completed turn");
        // The cut = the assistant message that ends the PREVIOUS turn. Its
        // private harness uuid is where the next spawn resumes at.
        const cut = [...rows].reverse().find((m) => m.role === "assistant" && m.seq < turnStart.seq);
        if (cut && !cut.harnessMessageUuid) {
          // Fork-copied rows carry no harness uuids — rewinding "to" one is
          // impossible. Rare and honest: undo stops at the fork boundary.
          throw new RpcMethodError(ErrorCode.InvalidParams, "cannot undo across fork-copied messages — the cut has no harness state");
        }
        const cutSeq = cut ? cut.seq : turnStart.seq - 1;
        const poppedIds = rows.filter((m) => m.seq > cutSeq).map((m) => m.messageId);
        // Design ordering: stop child → truncate transcript → SQLite tx →
        // emit. The harness side has no crash window (nothing harness-side
        // mutates until the next spawn consumes harness_resume_at); a crash
        // between truncate and tx is display-only and a re-undo repairs it.
        const s = live.get(p.session_id);
        if (s) await stopLive(p.session_id, s, "undo");
        // High-water BEFORE truncation: popping deletes the highest seqs from
        // both seeding stores, and a reissued seq is corruption (P1).
        const seqHighWater = Math.max(
          deps.sessions.messages.maxSeq(p.session_id),
          deps.transcripts.lastSeq(p.session_id),
        );
        deps.transcripts.truncateFromMessages(p.session_id, new Set(poppedIds));
        // The transcript shrank below the watermark — reconcile rebuilds this
        // session's index, so the popped turn stops being findable.
        reindexSearch(deps, p.session_id, log);
        deps.sessions.undoTurn(p.session_id, cutSeq, {
          resumeAtUuid: cut?.harnessMessageUuid ?? null,
          // First-turn undo: no previous assistant — next prompt starts a
          // FRESH harness session instead of resuming one that still holds
          // the popped turn.
          clearHarnessSession: !cut,
          seqHighWater,
        });
        const lastMessageId = cut?.messageId ?? null;
        // Transient, like session.deleted: never stamped/cached — a replayed
        // undone event would name message ids that no longer exist.
        subs.sendRaw(p.session_id, JSON.stringify(makeEvent(
          "session.undone",
          { session_id: p.session_id, last_message_id: lastMessageId, popped_message_ids: poppedIds },
          p.session_id,
        )));
        log(`[undo] ${p.session_id}: popped ${poppedIds.length} message(s), tip ${lastMessageId ?? "(empty)"}${cut ? ` resume-at ${cut.harnessMessageUuid}` : " (fresh harness)"}`);
        return { last_message_id: lastMessageId, popped_message_ids: poppedIds, files_rewound: false };
      }
      case "session.delete": {
        const p = SessionDeleteParams.parse(params ?? {});
        if (p.session_id === mainSessionId()) {
          throw new RpcMethodError(ErrorCode.InvalidParams,
            "the main session is daemon-managed and cannot be deleted — use session.clear to reset it");
        }
        if (!deps.sessions.get(p.session_id)) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        // A live session is stopped first — delete subsumes stop; no
        // close-before-delete ritual for clients.
        const s = live.get(p.session_id);
        if (s) {
          s.approvals.drainDeny("session deleted");
          s.clarifies.drainDrop("session deleted");
          s.secrets.drainDeny("session deleted");
          await s.harness.stop();
          live.delete(p.session_id);
          deps.supervisor?.clear(p.session_id);
        }
        // Tell every subscriber (this device included) before the data goes.
        // Sent directly, NOT via emit: emit stamps + appends to the very
        // transcript being deleted. Clients that predate the event ignore it
        // and notice on their next session.list.
        const gone = JSON.stringify(makeEvent("session.deleted", { session_id: p.session_id }, p.session_id));
        subs.sendRaw(p.session_id, gone);
        subs.dropSession(p.session_id);
        // A watch on a deleted session can never fire — drop it.
        ctx.watches.delete(p.session_id);
        // The cascade: index row + message identity rows + every device's
        // seen cursor (one transaction), then the transcript NDJSON.
        deps.sessions.delete(p.session_id);
        deps.transcripts.delete(p.session_id);
        // …and its search rows: a hit that can't open a session is garbage.
        deps.search?.dropSession(p.session_id);
        // Any still-staged attachments (queue + on-disk files) go with the
        // session (T1) — a deleted session can't consume them.
        deps.attachments?.clear(p.session_id);
        // Cron jobs targeting the deleted session can never fire again —
        // disable them WITH a reason instead of letting them error on every
        // future slot (the silent-decay class this feature is paranoid about).
        for (const job of deps.sessions.cron.list()) {
          if (job.sessionId === p.session_id && job.enabled) {
            deps.sessions.cron.disableWithError(job.id, deps.now(), "target session deleted");
            log(`[cron] job ${job.id} disabled — target session ${p.session_id} deleted`);
          }
        }
        log(`[session ${p.session_id}] deleted (cascade: messages, seen, transcript)`);
        return {};
      }
      case "session.title": {
        const p = SessionTitleParams.parse(params ?? {});
        const rec = deps.sessions.get(p.session_id);
        if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        deps.sessions.setTitle(p.session_id, p.title);
        return { title: deps.sessions.get(p.session_id)!.title };
      }
      case "session.archive": {
        const p = SessionArchiveParams.parse(params ?? {});
        const rec = deps.sessions.get(p.session_id);
        if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        // The main session is the pinned home surface — archiving would hide
        // it. Same refusal class as delete/stop.
        if (p.session_id === mainSessionId()) {
          throw new RpcMethodError(ErrorCode.InvalidParams,
            "the main session is daemon-managed and cannot be archived");
        }
        deps.sessions.setArchived(p.session_id, p.archived);
        return { archived: p.archived };
      }
      case "model.list": {
        // Additive: the harness's model choices for pickers. Ids are names,
        // not gates — session.create passes them to the SDK verbatim (an
        // unknown id fails the spawn visibly rather than being pre-filtered
        // against a list that could go stale).
        const defaultModel = effectiveDefaultModel(deps);
        return {
          // Rows carry their configured effort bounds (2026-07-27) so a picker
          // can grey out levels this model will never run at. Bounds for ids
          // the harness no longer lists just don't render — the catalog is the
          // harness's, the bounds are the daemon's, and they need not agree.
          models: (deps.adapter.listModels?.() ?? []).map((m) => {
            const b = deps.cfg.modelEfforts?.[m.id];
            return {
              ...m,
              ...(b?.min ? { effort_min: b.min } : {}),
              ...(b?.max ? { effort_max: b.max } : {}),
            };
          }),
          // Daemon-owned new-session defaults (additive): what a model-less /
          // effort-less session.create will get. Absent = the harness's own
          // default applies (unknown to the daemon until a turn runs).
          ...(defaultModel ? { default_model: defaultModel } : {}),
          ...(deps.cfg.defaultEffort ? { default_effort: deps.cfg.defaultEffort } : {}),
          // The effort levels this daemon ACCEPTS (additive, 2026-07-25).
          // Clients used to hardcode their own list and drifted — Android
          // offered none/minimal, which session.create rejects.
          efforts: [...EFFORT_LEVELS],
        };
      }
      case "settings.get": {
        return settingsSnapshot(deps);
      }
      case "settings.update": {
        const p = params as import("@marmalade/protocol").SettingsUpdateParams;
        const locked = envLockedSettings();
        const patch: Partial<import("./config.js").ConfigFile> = {};
        if (p.default_model !== undefined) {
          if (locked.includes("default_model")) {
            throw new RpcMethodError(
              ErrorCode.InvalidParams,
              "default_model is pinned by MARMALADE_DEFAULT_MODEL — unset the env var to edit it here",
            );
          }
          if (p.default_model !== null) {
            // Unlike session.create's per-session model (deliberately
            // ungated — a bad id fails that one spawn, visibly), a bad
            // DEFAULT would break every future session, so it is checked
            // against the harness catalog when the harness publishes one.
            const known = deps.adapter.listModels?.() ?? [];
            if (known.length > 0 && !known.some((m) => m.id === p.default_model)) {
              throw new RpcMethodError(
                ErrorCode.InvalidParams,
                `unknown model "${p.default_model}" — one of ${known.map((m) => m.id).join(", ")}`,
              );
            }
          }
          patch.default_model = p.default_model ?? undefined;
        }
        if (p.default_effort !== undefined) {
          if (locked.includes("default_effort")) {
            throw new RpcMethodError(
              ErrorCode.InvalidParams,
              "default_effort is pinned by MARMALADE_DEFAULT_EFFORT — unset the env var to edit it here",
            );
          }
          if (p.default_effort !== null
              && !(EFFORT_LEVELS as readonly string[]).includes(p.default_effort)) {
            throw new RpcMethodError(
              ErrorCode.InvalidParams,
              `default_effort "${p.default_effort}" is not one of ${EFFORT_LEVELS.join("/")}`,
            );
          }
          patch.default_effort = (p.default_effort as EffortLevel | null) ?? undefined;
        }
        // Per-model effort bounds (2026-07-27). A PER-MODEL patch, not a
        // whole-map replace: omitted ids keep their bounds, null removes one,
        // an object replaces that id's entry wholesale. Never env-locked —
        // bounds are file-only, so there's no lock check here.
        let mergedBounds: Record<string, EffortBounds> | undefined;
        if (p.model_efforts !== undefined) {
          mergedBounds = { ...(deps.cfg.modelEfforts ?? {}) };
          const known = deps.adapter.listModels?.() ?? [];
          for (const [modelId, bounds] of Object.entries(p.model_efforts)) {
            if (bounds === null) {
              delete mergedBounds[modelId];
              continue;
            }
            // Same gate as default_model: a bad id would bound a model that
            // can never run, silently. Only enforced when the harness
            // publishes a catalog at all.
            if (known.length > 0 && !known.some((m) => m.id === modelId)) {
              throw new RpcMethodError(
                ErrorCode.InvalidParams,
                `unknown model "${modelId}" — one of ${known.map((m) => m.id).join(", ")}`,
              );
            }
            const parsed = EffortBoundsSchema.safeParse(bounds);
            if (!parsed.success) {
              throw new RpcMethodError(
                ErrorCode.InvalidParams,
                `model_efforts["${modelId}"] invalid — ${parsed.error.issues.map((i) => i.message).join("; ")}`,
              );
            }
            mergedBounds[modelId] = parsed.data;
          }
          // An emptied map clears the key rather than persisting `{}`.
          patch.model_efforts = Object.keys(mergedBounds).length > 0 ? mergedBounds : undefined;
        }
        // Persist FIRST, apply to the LIVE config second: a failed write
        // (disk full, bad permissions) then leaves the daemon exactly as it
        // was, rather than serving a default it will forget on restart.
        writeConfigFile(patch, deps.configPath);
        if (p.default_model !== undefined) deps.cfg.defaultModel = p.default_model ?? undefined;
        if (p.default_effort !== undefined) {
          deps.cfg.defaultEffort = (p.default_effort as EffortLevel | null) ?? undefined;
        }
        if (mergedBounds !== undefined) {
          deps.cfg.modelEfforts = Object.keys(mergedBounds).length > 0 ? mergedBounds : undefined;
        }
        log(`settings.update ${JSON.stringify(patch)}`);
        return settingsSnapshot(deps);
      }
      case "session.list": {
        // Reopen-and-remember: sessions with their summaries + open items.
        const filter = conn.principal ? { principal: conn.principal } : {};
        // Unread decoration (P4): last message seq vs THIS device's cursor.
        // The client derives unread as last_seq > seen_seq — arithmetic, no
        // wall-clock heuristics.
        const lastSeqs = deps.sessions.messages.maxSeqBySession();
        const seen = deps.sessions.seen.forDevice(deviceIdFor(conn));
        // Workspace membership is DERIVED (cwd prefix, deepest wins), stamped
        // per row so every client groups identically without path logic.
        const workspaceFor = deps.sessions.workspaces.matcher();
        const mainId = mainSessionId();
        return {
          sessions: deps.sessions.list(filter).map((r) => ({
            session_id: r.id,
            // THE daemon-managed main session (assistant surface). Clients
            // pin it to the Home tab and hide delete/stop for it.
            is_main: r.id === mainId,
            purpose: r.purpose,
            status: r.status, // legacy derived view (v1 clients)
            lifecycle: r.lifecycle,
            run_state: r.runState,
            harness: r.harness,
            last_active: r.lastActive,
            last_seq: lastSeqs.get(r.id) ?? 0,
            seen_seq: seen.get(r.id) ?? 0,
            model: r.model,
            // Additive: the session's reasoning effort (null = harness
            // default). Stamped at create (client value ?? default_effort).
            reasoning_effort: r.reasoningEffort,
            // Effective approvals mode (M2): session override ?? global.
            approvals: r.approvals ?? deps.cfg.approvalsMode,
            title: r.title,
            // Archived flag (additive): clients filter archived rows out of
            // the main list and show an "Archived" section. List metadata
            // only — the daemon never behaves differently for archived rows.
            archived: r.archived,
            // Context occupancy after this session's last completed turn
            // (additive) — stamped daemon-side from the harness-pushed usage,
            // never queried on read, so a cold-opened session shows context
            // without waiting for a turn. null = unknown. Percent is DERIVED
            // here, not stored, so the formula lives in exactly one place.
            context_used: r.contextUsed,
            context_max: r.contextMax,
            context_percent: contextPercent(r.contextUsed, r.contextMax),
            topic: r.topic,
            summary: r.summary,
            summary_updated_at: r.summaryUpdatedAt,
            // Fork lineage (T2 #3, additive): where this session branched
            // from, or null. Metadata for "branched from X" chips — never a
            // visibility filter.
            branched_from: r.branchedFromSessionId
              ? { session_id: r.branchedFromSessionId, message_id: r.branchedFromMessageId }
              : null,
            workspace_id: workspaceFor(r.cwd),
          })),
        };
      }
      case "approval.respond": {
        // Session-keyed FIFO with request_id carried anyway (M2 decision 3):
        // absent request_id resolves the OLDEST pending request — matches the
        // shipped Android client. Unknown/already-resolved → error.
        const p = ApprovalRespondParams.parse(params ?? {});
        const s = live.get(p.session_id);
        if (!s) throw new RpcMethodError(ErrorCode.InvalidParams, `session ${p.session_id} not live`);
        const resolved = s.approvals.respond(p.choice, p.request_id, conn.platform ?? "unknown");
        if (!resolved) throw new RpcMethodError(ErrorCode.InvalidParams, "no matching pending approval");
        return { resolved: true };
      }
      case "clarify.respond": {
        // Answer (or dismiss) a parked agent question. Same correlation as
        // approval.respond: request_id optional, FIFO fallback. No answers
        // and no response = dismissed — the agent proceeds on its own.
        const p = ClarifyRespondParams.parse(params ?? {});
        const s = live.get(p.session_id);
        if (!s) throw new RpcMethodError(ErrorCode.InvalidParams, `session ${p.session_id} not live`);
        const resolved = s.clarifies.respond({ requestId: p.request_id, answers: p.answers, response: p.response });
        if (!resolved) throw new RpcMethodError(ErrorCode.InvalidParams, "no matching pending clarify");
        return { resolved: true };
      }
      case "secret.respond": {
        // THE ONE RPC THAT CARRIES A CREDENTIAL. `p.value` goes to
        // SessionSecrets.respond, which hands it to the keyring child's stdin
        // and drops it. It is deliberately NOT logged here, not echoed into
        // the result, and not passed to anything that stamps, caches, or
        // indexes — there is no generic params-stringifying log path in the
        // router or the gateway for it to leak through either (checked).
        const p = SecretRespondParams.parse(params ?? {});
        const s = live.get(p.session_id);
        if (!s) throw new RpcMethodError(ErrorCode.InvalidParams, `session ${p.session_id} not live`);
        const outcome = await s.secrets.respond({
          requestId: p.request_id,
          ...(p.value !== undefined ? { value: p.value } : {}),
          ...(p.deny ? { deny: true as const } : {}),
          ...(p.reason ? { reason: p.reason } : {}),
        });
        if (!outcome.resolved) throw new RpcMethodError(ErrorCode.InvalidParams, "no matching pending secret request");
        return outcome;
      }
      case "session.approvals": {
        // Flip a session's approvals mode (M2 decision 1). Mutable while
        // running — it gates the NEXT tool call; persisted on the row.
        const p = SessionApprovalsParams.parse(params ?? {});
        if (!deps.sessions.get(p.session_id)) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        deps.sessions.setApprovals(p.session_id, p.mode);
        log(`[approval] session ${p.session_id} mode → ${p.mode}`);
        return { mode: p.mode };
      }
      case "pairing.start": {
        // Mint a single-use bootstrap token for a NEW device. Only reachable
        // from an authenticated connection (the gateway's auth gate) — pairing
        // authority is itself gated by auth. The setup code targets the
        // tailnet URL; when the daemon is still loopback-bound the code names
        // the right future target (widen via MARMALADE_BIND_HOST).
        const started = deps.sessions.pairing.startPairing(deps.now());
        if (!started) throw new RpcMethodError(ErrorCode.InvalidParams, "pairing unavailable: pending-code cap reached or lockout active — retry later");
        const nonLoopback = deps.cfg.gatewayHosts.find(
          (h) => h !== "127.0.0.1" && h !== "::1" && h !== "localhost");
        const host = nonLoopback ?? detectTailnetIPv4() ?? deps.cfg.gatewayHosts[0];
        const url = `ws://${host}:${deps.cfg.gatewayPort}/api/ws`;
        const setupCode = encodeSetupCode({ url, token: started.token, expires_at_ms: started.expiresAt });
        log(`[pairing] bootstrap code minted (expires ${new Date(started.expiresAt).toISOString()})`);
        return { token: started.token, url, setup_code: setupCode, expires_at: started.expiresAt };
      }
      case "pairing.claim": {
        // The ONLY method an unauthenticated connection may call (gateway
        // gate). Exchange bootstrap → per-device bearer token and bind this
        // connection's VERIFIED identity in one step.
        const p = PairingClaimParams.parse(params ?? {});
        const deviceId = sanitizeIdentityField(p.device_id);
        if (!deviceId) throw new RpcMethodError(ErrorCode.InvalidParams, "invalid device_id");
        const deviceToken = deps.sessions.pairing.claim(p.token, deviceId, "max", deps.now());
        if (!deviceToken) throw new RpcMethodError(ErrorCode.Unauthenticated, "invalid or expired pairing token");
        conn.authenticated = true;
        conn.principal = "owner"; // v0.1: single principal
        conn.deviceId = deviceId;
        conn.deviceIdVerified = true;
        conn.platform = sanitizeIdentityField(p.platform);
        deps.sessions.devices.touch(deviceId, conn.platform ?? "unknown", [], deps.now());
        log(`[pairing] device "${deviceId}" paired (${conn.platform ?? "unknown"})`);
        return { device_token: deviceToken, device_id: deviceId, principal: conn.principal };
      }
      case "device.list": {
        const connected = deps.connectedDevices?.() ?? new Set<string>();
        return {
          devices: deps.sessions.devices.list().map((d) => ({
            device_id: d.deviceId,
            platform: d.platform,
            paired: deps.sessions.pairing.isPaired(d.deviceId),
            connected: connected.has(d.deviceId),
            first_seen: d.firstSeen,
            last_seen: d.lastSeen,
          })),
        };
      }
      case "device.revoke": {
        const p = DeviceRevokeParams.parse(params ?? {});
        const tokens = deps.sessions.pairing.revokeDevice(p.device_id);
        const hadRow = deps.sessions.devices.delete(p.device_id);
        // Revocation is immediate: any live connection bound to this device
        // drops now, not at its next reconnect.
        deps.disconnectDevice?.(p.device_id);
        if (tokens > 0 || hadRow) log(`[pairing] device "${p.device_id}" revoked (${tokens} token(s))`);
        return { revoked: tokens > 0 || hadRow };
      }
      case "skills.list": {
        if (!deps.skills) throw new RpcMethodError(ErrorCode.MethodNotFound, "skills management not configured");
        return { skills: deps.skills.list() };
      }
      case "skills.toggle": {
        if (!deps.skills) throw new RpcMethodError(ErrorCode.MethodNotFound, "skills management not configured");
        const p = SkillsToggleParams.parse(params ?? {});
        try {
          const r = deps.skills.toggle(p.name, p.enabled);
          log(`[skills] ${p.name} ${p.enabled ? "enabled" : "disabled"} (${r.results.map((x) => `${x.harness}: +${x.linked.length}/-${x.removed.length}`).join(", ")})`);
          return { applied: r.applied };
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "mcp.list": {
        if (!deps.harnessConfig) throw new RpcMethodError(ErrorCode.MethodNotFound, "MCP management not configured");
        return { servers: deps.harnessConfig.listMcp() };
      }
      case "mcp.toggle": {
        if (!deps.harnessConfig) throw new RpcMethodError(ErrorCode.MethodNotFound, "MCP management not configured");
        const p = McpToggleParams.parse(params ?? {});
        try {
          return deps.harnessConfig.toggleMcp(p.name, p.enabled);
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "plugins.list": {
        if (!deps.harnessConfig) throw new RpcMethodError(ErrorCode.MethodNotFound, "plugin management not configured");
        return { plugins: deps.harnessConfig.listPlugins() };
      }
      case "plugins.toggle": {
        if (!deps.harnessConfig) throw new RpcMethodError(ErrorCode.MethodNotFound, "plugin management not configured");
        const p = PluginsToggleParams.parse(params ?? {});
        try {
          return deps.harnessConfig.togglePlugin(p.name, p.enabled);
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "fs.defaults": {
        // The cwd session.create uses when none is passed (policy.ts default).
        return { default_cwd: homedir() };
      }
      case "fs.list": {
        const p = FsListParams.parse(params ?? {});
        try {
          return listDirConfined(p.path, undefined, p.show_hidden ?? false);
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "workspace.create": {
        // Paseo-style folder workspace: name+emoji over a home-confined path.
        // Existing sessions in the folder adopt by cwd match — nothing to
        // migrate, membership is derived at session.list time.
        const p = WorkspaceCreateParams.parse(params ?? {});
        try {
          const rec = deps.sessions.workspaces.create(p, deps.now());
          log(`[workspace] "${rec.name}" added (${rec.path})`);
          return { workspace: workspaceWire(rec) };
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "workspace.list": {
        return { workspaces: deps.sessions.workspaces.list().map(workspaceWire) };
      }
      case "workspace.update": {
        const p = WorkspaceUpdateParams.parse(params ?? {});
        try {
          return { workspace: workspaceWire(deps.sessions.workspaces.update(p.workspace_id, p, deps.now())) };
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "workspace.context": {
        // Read-only context peek for the workspace detail screen. Scoped by
        // workspace id (no generic fs.read surface); file symlinks resolving
        // outside home read as absent.
        const p = WorkspaceContextParams.parse(params ?? {});
        const rec = deps.sessions.workspaces.get(p.workspace_id);
        if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown workspace ${p.workspace_id}`);
        const ctx = readWorkspaceContext(rec.path, deps.sessions.workspaces.home);
        return {
          workspace_id: rec.id,
          claude_md: ctx.claudeMd,
          agents_md: ctx.agentsMd,
          memory: ctx.memory,
          git_branch: ctx.gitBranch,
        };
      }
      case "workspace.delete": {
        // Un-group only: sessions keep their cwd and fall back to ungrouped.
        const p = WorkspaceDeleteParams.parse(params ?? {});
        return { deleted: deps.sessions.workspaces.delete(p.workspace_id) };
      }
      case "image.attach_bytes": {
        // T1: stage image bytes for the NEXT prompt.submit. UNTRUSTED input
        // from a paired device — the store magic-byte-sniffs, caps at 25 MB,
        // and confines the write to a per-session dir (see attachments.ts).
        if (!deps.attachments) throw new RpcMethodError(ErrorCode.MethodNotFound, "attachments not configured");
        const p = ImageAttachBytesParams.parse(params ?? {});
        if (!deps.sessions.get(p.session_id)) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        try {
          return deps.attachments.attachImageBytes(p.session_id, p.content_base64, p.filename);
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "file.attach": {
        // T1: stage a non-image file (PDFs are page-rendered to images). Same
        // confinement + cap; the name is validated as a safe basename.
        if (!deps.attachments) throw new RpcMethodError(ErrorCode.MethodNotFound, "attachments not configured");
        const p = FileAttachParams.parse(params ?? {});
        if (!deps.sessions.get(p.session_id)) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        try {
          return deps.attachments.attachFile(p.session_id, p.name, p.data_url);
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "image.detach": {
        // T1: drop a staged attachment before it's consumed. Only ever unlinks
        // a file THIS session staged (the path can't reach outside the queue).
        if (!deps.attachments) throw new RpcMethodError(ErrorCode.MethodNotFound, "attachments not configured");
        const p = ImageDetachParams.parse(params ?? {});
        if (!deps.sessions.get(p.session_id)) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        return deps.attachments.detach(p.session_id, p.path);
      }
      case "audio.transcribe": {
        // Server-side STT fallback (Android voice popup, on-device Whisper
        // failure). Session-independent — the utterance hasn't reached a
        // prompt yet. Long-running (whisper reloads its model per call), so
        // the transcriber execs async and the router awaits it.
        if (!deps.transcriber) {
          throw new RpcMethodError(ErrorCode.MethodNotFound, "transcription not configured");
        }
        const p = AudioTranscribeParams.parse(params ?? {});
        try {
          return await deps.transcriber.transcribe(p.audio_base64, p.mime);
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "session.fork": {
        // T2 #3, ratified shape (2026-07-18): harness-native fork for
        // BOTH end and mid-point cuts (the SDK's forkSession supports
        // upToMessageId); the client's seed-create branch remains the
        // fallback for harnesses that can't fork — with a context-loss
        // warning surfaced client-side.
        const p = SessionForkParams.parse(params ?? {});
        const src = deps.sessions.get(p.session_id);
        if (!src) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        if (src.runState === "running" || src.runState === "starting") {
          // Mid-turn fork would slice a half-written transcript (the hermes
          // 4009-style guard). Not a failure — retry when the turn completes.
          throw new RpcMethodError(ErrorCode.InvalidParams, "session has a turn in flight — fork after it completes");
        }
        if (!src.harnessSessionId) {
          throw new RpcMethodError(ErrorCode.InvalidParams, "session has no harness state to fork yet (no turn has run)");
        }
        if (!deps.adapter.forkSession) {
          // data.reason is the CONTRACT clients branch on (their fallback /
          // unavailability warning); the message is human prose and free to
          // reword. Review finding 2026-07-18: substring-matching the message
          // was a shadow contract across two client codebases.
          throw new RpcMethodError(
            ErrorCode.InvalidParams,
            `harness "${deps.adapter.name}" cannot fork sessions`,
            { reason: FORK_UNSUPPORTED_REASON },
          );
        }
        // Resolve the cut point: an assistant message's PRIVATE harness uuid.
        // User messages (and messages copied by an earlier fork) carry no
        // harness cut point — clients branch at assistant replies.
        let cutMsg: import("./message-store.js").MessageRecord | null = null;
        if (p.at_message_id) {
          const m = deps.sessions.messages.get(p.at_message_id);
          if (!m || m.sessionId !== p.session_id) {
            throw new RpcMethodError(ErrorCode.InvalidParams, `message ${p.at_message_id} not found in session ${p.session_id}`);
          }
          if (m.role !== "assistant" || !m.harnessMessageUuid) {
            throw new RpcMethodError(ErrorCode.InvalidParams, "fork cut must be an assistant reply with harness state — pick an assistant message");
          }
          cutMsg = m;
        }
        const forked = await deps.adapter.forkSession(src.harnessSessionId, {
          cwd: src.cwd,
          // A pending undo rewind (harness_resume_at) means the harness JSONL
          // tip still holds the popped tail — an END fork must cut at the
          // rewind point, not the stale tip. An explicit cut is always ≤ the
          // rewind point (the popped rows are gone, so their ids can't be
          // named) and wins as-is.
          ...(cutMsg?.harnessMessageUuid
            ? { upToHarnessUuid: cutMsg.harnessMessageUuid }
            : src.harnessResumeAt
              ? { upToHarnessUuid: src.harnessResumeAt }
              : {}),
          ...(p.title ? { title: p.title } : {}),
        });
        const newId = deps.mintSessionId();
        const spec = createSessionSpec(
          { principal: src.principal as Principal, purpose: src.purpose as Purpose, origin: "text", cwd: src.cwd },
          deps.cfg,
        );
        deps.sessions.create(newId, spec, deps.adapter.name, deps.now(), src.model ?? undefined, src.approvals ?? undefined, src.reasoningEffort ?? undefined);
        deps.sessions.bindHarnessSession(newId, forked.harnessSessionId);
        deps.sessions.setBranchedFrom(newId, src.id, cutMsg?.messageId ?? null);
        deps.sessions.setTitle(newId, p.title ?? (src.title ? `${src.title} (fork)` : "fork"));
        // No child is spawned: the fork starts ended/RESUMABLE (exactly a
        // reaped session's shape) — the first prompt.submit auto-revives it
        // with resumeHarnessSessionId = the forked harness id. Marking it
        // ended also keeps the supervisor from flagging a never-heartbeating
        // "starting" session as a silent failure.
        deps.sessions.end(newId, deps.now());
        copyForkHistory(deps, src.id, newId, cutMsg, log);
        // The fork's copied history is real, searchable text under a NEW
        // session id (copied message ids are remapped) — index it now rather
        // than waiting for its first turn.
        reindexSearch(deps, newId, log);
        log(`[fork] ${src.id} → ${newId}${cutMsg ? ` (cut at ${cutMsg.messageId})` : " (end)"} harness ${forked.harnessSessionId}`);
        return {
          session_id: newId,
          forked_from: { session_id: src.id, message_id: cutMsg?.messageId ?? null },
          full_context: true,
          ...(forked.warning ? { warning: forked.warning } : {}),
        };
      }
      case "session.summary": {
        const rec = deps.sessions.get(SessionSummaryParams.parse(params ?? {}).session_id);
        if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, "unknown session");
        return {
          topic: rec.topic,
          summary: rec.summary,
          summary_updated_at: rec.summaryUpdatedAt,
          lifecycle: rec.lifecycle,
          run_state: rec.runState,
        };
      }
      case "cron.create": {
        const p = CronCreateParams.parse(params ?? {});
        if (!deps.sessions.get(p.session_id)) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        const now = deps.now();
        const invalid = validateSchedule(p.schedule, now);
        if (invalid) throw new RpcMethodError(ErrorCode.InvalidParams, invalid);
        const id = `cj_${randomBytes(6).toString("base64url")}`;
        const rec: CronJobRecord = {
          id, name: p.name ?? null, sessionId: p.session_id, prompt: p.prompt,
          schedule: p.schedule, enabled: p.enabled, createdAt: now, updatedAt: now,
          nextRunAt: p.enabled ? (computeNextFireAt(p.schedule, id, now) ?? null) : null,
          lastRunAt: null, lastStatus: null, lastError: null,
        };
        deps.sessions.cron.create(rec);
        deps.cron?.jobChanged();
        log(`[cron] created ${id}${rec.name ? ` (${rec.name})` : ""} → session ${rec.sessionId}, next ${rec.nextRunAt ? new Date(rec.nextRunAt).toISOString() : "never (disabled)"}`);
        return { job: cronJobWire(rec) };
      }
      case "cron.list": {
        // EVERY job, disabled included (#16156: a list that skips jobs hides
        // exactly the dead ones you need to notice).
        return { jobs: deps.sessions.cron.list().map(cronJobWire) };
      }
      case "usage.summary": {
        // Daily rollups, trailing window ending today (T2 #8). Provider truth
        // only: cost_usd is the SDK's notional figure (may be 0 under
        // subscription auth); tokens are the ground-truth metric.
        const p = UsageSummaryParams.parse(params ?? {});
        const today = deps.today();
        // Same UTC-day convention as deps.today() (toISOString) — window math
        // off now() so the two can't disagree on what "today" is.
        const fromDay = new Date(deps.now() - (p.days - 1) * 86_400_000).toISOString().slice(0, 10);
        const entries = deps.usage.summary(fromDay, today).map((e) => ({
          day: e.day, purpose: e.purpose, cost_usd: e.costUsd,
          input_tokens: e.inputTokens, output_tokens: e.outputTokens, turns: e.turns,
        }));
        // Budget decoration (config file): today's total in the budget's
        // metric + the over flag that gates unattended (cron) turns.
        const b = deps.cfg.budget;
        const budget = b ? {
          metric: b.metric,
          daily_limit: b.dailyLimit,
          today_total: deps.usage.dayTotal(today, b.metric),
          over: deps.usage.isOverBudget(today, b),
        } : null;
        // Subscription plan limits (Claude Code's /usage windows): ACCOUNT-
        // global, so any ONE live session answers for the whole harness — the
        // first with the seam is asked, capped at 5s so a slow claude.ai
        // fetch can't stall the summary. Empty when the harness has no seam
        // (OpenCode), nothing is live, or the account has no plan. A future
        // subscription harness (e.g. Codex) implements HarnessSession
        // .planUsage and lands here as its own entry — no wire change.
        const plan_limits = [];
        for (const s of live.values()) {
          if (!s.harness.planUsage) continue;
          const u = await Promise.race([
            s.harness.planUsage().catch(() => null),
            new Promise<null>((resolve) => setTimeout(() => resolve(null), 5000).unref?.()),
          ]);
          if (u) {
            plan_limits.push({
              harness: deps.adapter.name,
              subscription_type: u.subscriptionType,
              windows: u.windows.map((w) => ({ id: w.id, label: w.label, utilization: w.utilization, resets_at: w.resetsAt })),
            });
          }
          break; // one live session speaks for the account
        }
        return { today, entries, budget, plan_limits };
      }
      case "cron.update": {
        const p = CronUpdateParams.parse(params ?? {});
        const cur = deps.sessions.cron.get(p.job_id);
        if (!cur) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown cron job ${p.job_id}`);
        if (p.session_id !== undefined && !deps.sessions.get(p.session_id)) {
          throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${p.session_id}`);
        }
        const now = deps.now();
        const schedule = p.schedule ?? cur.schedule;
        const enabled = p.enabled ?? cur.enabled;
        // Recompute next_run_at ONLY when the schedule changed (#19676
        // at-reschedule) or the job flips disabled→enabled. A plain edit of an
        // enabled job must NOT touch a past-due next_run_at — silently
        // advancing one is the daily-skip bug class (#17852).
        const rearm = p.schedule !== undefined || (enabled && !cur.enabled);
        let nextRunAt = cur.nextRunAt;
        if (!enabled) {
          nextRunAt = null;
        } else if (rearm) {
          const invalid = validateSchedule(schedule, now);
          if (invalid) throw new RpcMethodError(ErrorCode.InvalidParams, invalid);
          nextRunAt = computeNextFireAt(schedule, cur.id, now) ?? null;
        }
        const updated = deps.sessions.cron.update(p.job_id, {
          ...(p.name !== undefined ? { name: p.name } : {}),
          ...(p.session_id !== undefined ? { sessionId: p.session_id } : {}),
          ...(p.prompt !== undefined ? { prompt: p.prompt } : {}),
          schedule, enabled, nextRunAt,
        }, now)!;
        deps.cron?.jobChanged();
        log(`[cron] updated ${p.job_id} (enabled=${enabled}, next ${nextRunAt ? new Date(nextRunAt).toISOString() : "never"})`);
        return { job: cronJobWire(updated) };
      }
      case "cron.delete": {
        const p = CronDeleteParams.parse(params ?? {});
        const deleted = deps.sessions.cron.delete(p.job_id);
        deps.cron?.jobChanged();
        if (deleted) log(`[cron] deleted ${p.job_id}`);
        return { deleted };
      }
      case "cron.run_now": {
        const p = CronRunNowParams.parse(params ?? {});
        const job = deps.sessions.cron.get(p.job_id);
        if (!job) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown cron job ${p.job_id}`);
        if (!deps.cron) throw new RpcMethodError(ErrorCode.MethodNotFound, "cron scheduler not running");
        const fired = await deps.cron.runNow(job);
        return { fired };
      }
      // ── terminal.* — PTY terminals ───────────────────────────────────────────
      // Absent manager = feature off (config) or node-pty didn't load; clients
      // that honor the "terminal" hello feature never land here. Manager errors
      // (unknown id, cap) surface as InvalidParams with the manager's message.
      case "terminal.create": {
        const t = requireTerminals(deps);
        const p = TerminalCreateParams.parse(params ?? {});
        try {
          return { terminal: stampTerminalWorkspace(deps, t.create(p, conn)) };
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "terminal.attach": {
        const t = requireTerminals(deps);
        const p = TerminalAttachParams.parse(params ?? {});
        try {
          const r = t.attach(p.terminal_id, conn);
          return { ...r, terminal: stampTerminalWorkspace(deps, r.terminal) };
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "search.messages": {
        // Full-text search over MESSAGE TEXT across sessions (search-store.ts).
        // Three filters compose, in this order, and none may be skipped:
        //   1. principal — the same `conn.principal` filter session.list uses,
        //      or search leaks across principals;
        //   2. archived — out by default (archived is what you pushed away);
        //   3. scope — workspace membership via the SAME matcher session.list
        //      stamps rows with (deepest wins), quick chats = matched nothing,
        //      explicit session_ids INTERSECTED with the above (never a
        //      bypass). The scope fields OR together; no scope = everywhere.
        if (!deps.search) throw new RpcMethodError(ErrorCode.MethodNotFound, "search not configured");
        const p = SearchMessagesParams.parse(params ?? {});
        if (p.scope?.corpus === "archive") {
          // THE ARCHIVE CORPUS — the pre-daemon ~/.claude/projects history
          // (archive-indexer.ts). A separate corpus, not a filter: none of it
          // is openable, so none of the live machinery (principal rows,
          // archived flag, reply lookup) applies.
          //
          // NO PRINCIPAL FILTER, deliberately: archive rows have no principal
          // column and never can — the files predate the concept. This is a
          // single-user history sitting on the daemon's own disk, so it is
          // served to any AUTHENTICATED connection. If marmaladed ever grows a
          // second real principal, this corpus must be gated (or dropped)
          // before that lands.
          const workspaceFor = deps.sessions.workspaces.matcher();
          const wsWanted = new Set(p.scope.workspace_ids ?? []);
          const idWanted = p.scope.session_ids ? new Set(p.scope.session_ids) : null;
          const scoped = wsWanted.size > 0 || p.scope.quick_chats === true || idWanted !== null;
          // A live session REPLAYS its harness session's archive file. Showing
          // both would find the same conversation twice and offer the dead copy
          // first. The live row wins — it can be opened. Taken over ALL rows,
          // not the principal-filtered set: an archive twin of anyone's live
          // session is a duplicate regardless of who asked.
          const replayed = new Set(
            deps.sessions.list({}).map((r) => r.harnessSessionId).filter((x): x is string => x !== null),
          );
          const archiveRows = deps.search.archiveSessionCwds().filter((a) => {
            if (replayed.has(a.sessionId)) return false;
            if (!scoped) return true;
            const ws = workspaceFor(a.cwd);
            if (ws !== null && wsWanted.has(ws)) return true;
            if (p.scope?.quick_chats === true && ws === null) return true;
            return idWanted?.has(a.sessionId) ?? false;
          });
          const { total, hits } = deps.search.searchArchive(
            { query: p.query, role: p.role, since: p.since, sort: p.sort, limit: p.limit, offset: p.offset },
            archiveRows.map((a) => a.sessionId),
          );
          const sessions: Record<string, unknown> = {};
          for (const h of hits) {
            if (sessions[h.sessionId]) continue;
            const meta = deps.search.archiveSession(h.sessionId);
            if (!meta) continue;
            sessions[h.sessionId] = {
              title: meta.title,
              workspace_id: workspaceFor(meta.cwd),
              archived: false,
              last_active: meta.lastTs,
              corpus: "archive",
            };
          }
          return {
            total,
            // No reply_text: replyTo is live-corpus machinery keyed on the
            // daemon's own message index, which knows nothing of these ids.
            hits: hits.map((h) => ({
              session_id: h.sessionId,
              message_id: h.messageId,
              seq: h.seq,
              role: h.role,
              ts: h.ts,
              snippet: h.snippet,
              text: h.text,
            })),
            sessions,
          };
        }
        const visible = deps.sessions
          .list(conn.principal ? { principal: conn.principal } : {})
          .filter((r) => p.include_archived || !r.archived);
        const workspaceFor = deps.sessions.workspaces.matcher();
        const wsWanted = new Set(p.scope?.workspace_ids ?? []);
        const idWanted = p.scope?.session_ids ? new Set(p.scope.session_ids) : null;
        const scoped = wsWanted.size > 0 || p.scope?.quick_chats === true || idWanted !== null;
        const allowed = visible.filter((r) => {
          if (!scoped) return true;
          const ws = workspaceFor(r.cwd);
          if (ws !== null && wsWanted.has(ws)) return true;
          if (p.scope?.quick_chats === true && ws === null) return true;
          return idWanted?.has(r.id) ?? false;
        });
        const { total, hits } = deps.search.search(
          { query: p.query, role: p.role, since: p.since, sort: p.sort, limit: p.limit, offset: p.offset },
          allowed.map((r) => r.id),
        );
        // Session context for the page's hits only — the client renders title +
        // workspace chip without re-deriving membership from paths.
        const byId = new Map(visible.map((r) => [r.id, r]));
        const sessions: Record<string, unknown> = {};
        for (const h of hits) {
          const rec = byId.get(h.sessionId);
          if (!rec || sessions[h.sessionId]) continue;
          sessions[h.sessionId] = {
            title: rec.title,
            workspace_id: workspaceFor(rec.cwd),
            archived: rec.archived,
            last_active: rec.lastActive,
          };
        }
        return {
          total,
          hits: hits.map((h) => {
            // A user hit carries the answer's opening as a preview — the peek
            // shows the exchange, not a lone question. The answer is the
            // assistant message that names this one as its parent.
            const reply = h.role === "user"
              ? deps.sessions.messages.replyTo(h.messageId)
              : undefined;
            const replyText = reply ? deps.search!.textOf(reply.messageId, SEARCH_REPLY_CAP) : undefined;
            return {
              session_id: h.sessionId,
              message_id: h.messageId,
              seq: h.seq,
              role: h.role,
              ts: h.ts,
              snippet: h.snippet,
              text: h.text,
              ...(replyText ? { reply_text: replyText } : {}),
            };
          }),
          sessions,
        };
      }
      case "search.archive": {
        // Read-only transcript fetch for ONE archive session — the viewer
        // behind an archive hit. Served from the index (the FTS table holds the
        // full text), so this never re-reads the .jsonl and a since-deleted
        // file still renders. Same principal note as the archive branch of
        // search.messages above: no principal column exists to filter on.
        if (!deps.search) throw new RpcMethodError(ErrorCode.MethodNotFound, "search not configured");
        const p = SearchArchiveParams.parse(params ?? {});
        const meta = deps.search.archiveSession(p.session_id);
        if (!meta) {
          throw new RpcMethodError(ErrorCode.InvalidParams, `archive session ${p.session_id} not found`);
        }
        const { total, messages } = deps.search.archiveMessages(p.session_id, p.limit, p.offset);
        return {
          session: {
            title: meta.title,
            cwd: meta.cwd,
            last_active: meta.lastTs,
            message_count: meta.messageCount,
          },
          total,
          messages,
        };
      }
      case "terminal.detach": {
        const t = requireTerminals(deps);
        const p = TerminalDetachParams.parse(params ?? {});
        t.detach(p.terminal_id, conn);
        return {};
      }
      case "terminal.input": {
        const t = requireTerminals(deps);
        const p = TerminalInputParams.parse(params ?? {});
        try {
          t.input(p.terminal_id, p.data_b64);
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
        return {};
      }
      case "terminal.resize": {
        const t = requireTerminals(deps);
        const p = TerminalResizeParams.parse(params ?? {});
        try {
          return t.resize(p.terminal_id, p.cols, p.rows);
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
      }
      case "terminal.close": {
        const t = requireTerminals(deps);
        const p = TerminalCloseParams.parse(params ?? {});
        try {
          t.close(p.terminal_id);
        } catch (e) {
          throw new RpcMethodError(ErrorCode.InvalidParams, (e as Error).message);
        }
        return { closed: true };
      }
      case "terminal.list": {
        const t = requireTerminals(deps);
        // One matcher for the whole roster (mirrors session.list stamping).
        const workspaceFor = deps.sessions.workspaces.matcher();
        return { terminals: t.list().map((row) => ({ ...row, workspace_id: workspaceFor(row.cwd) })) };
      }
      default:
        throw new RpcMethodError(ErrorCode.MethodNotFound, `method not routable: ${method}`);
    }
  };

  return Object.assign(handler, {
    async submitCron(sessionId: string, prompt: string): Promise<void> {
      // The budget guardrail bites HERE and only here (unattended spend):
      // an over-budget day refuses scheduled turns — the scheduler records
      // the refusal as the job's last_error, so it's visible in cron.list
      // and `marmalade cron list`, not silent. Interactive prompts are
      // never blocked; a user-typed prompt always goes through.
      if (deps.cfg.budget && deps.usage.isOverBudget(deps.today(), deps.cfg.budget)) {
        const msg = `daily budget exceeded (${deps.usage.dayTotal(deps.today(), deps.cfg.budget.metric)} > ${deps.cfg.budget.dailyLimit} ${deps.cfg.budget.metric}) — scheduled prompt refused`;
        if (!budgetBreachAlerted) {
          budgetBreachAlerted = true;
          void deps.ntfy?.publish("Marmalade: daily budget exceeded — cron paused", msg, { priority: 4 });
        }
        // alerted marks the error so the scheduler's per-fire failure alert
        // skips it (the latch above already covered this breach).
        throw Object.assign(new Error(msg), { alerted: true });
      }
      budgetBreachAlerted = false;
      const rec = deps.sessions.get(sessionId);
      if (!rec) throw new Error(`target session ${sessionId} no longer exists`);
      // Same auto-revive as a client prompt.submit — a cron firing into a
      // reaped/idle session brings it back in place. No connection: the
      // session simply has no cron-side subscriber (and the M2 unattended
      // fallback keeps approvals from hanging a headless turn).
      if (!live.has(sessionId)) {
        await enforceCap();
        await resumeSession(deps, live, subs, null, { session_id: sessionId }, log, ctx);
      }
      const s = live.get(sessionId);
      if (!s) throw new Error(`target session ${sessionId} failed to revive`);
      await submitPromptCore(deps, s, sessionId, prompt, cronOrigin());
    },
    ensureMain,
    disconnect(conn: Connection): void {
      // Read the membership BEFORE the removal: parked secrets care about
      // losing the only CAPABLE device, which can happen while other
      // subscribers remain (so subs.disconnect's last-subscriber list misses it).
      const touched = subs.sessionsWith(conn);
      // Sessions that just lost their LAST subscriber: resolve any parked
      // approvals as allow-with-log (M2 decision: a dropped phone connection
      // must not kill a running turn — matches "WS reconnect must not kill
      // the run"). Logged loudly inside drainAllow.
      for (const sessionId of subs.disconnect(conn)) {
        const l = live.get(sessionId);
        l?.approvals.drainAllow("last subscriber disconnected while parked");
        // Questions can't be allow-drained — nobody answered. The agent is
        // told to proceed on its own judgment; the run survives either way.
        l?.clarifies.drainUnanswered("the last device disconnected");
      }
      // Secrets invert the approvals rule. An approval falls back to ALLOW
      // because a dropped connection must not kill a run; a credential prompt
      // has no such fallback — there is no sane auto-answer to "type your
      // password", so it DENIES the moment no secrets-capable client is left
      // to answer it (not merely when the last subscriber goes).
      for (const sessionId of touched) {
        if (subs.countCapable(sessionId, "secrets") > 0) continue;
        live.get(sessionId)?.secrets.drainDeny("the last secrets-capable device disconnected");
      }
      // Terminal attach sets too — the shells keep running (a dropped phone
      // must not kill a terminal any more than it kills a run); the client
      // re-attaches on reconnect and repaints from the snapshot.
      deps.terminals?.disconnect(conn);
    },
    reapIdle,
  });
}

function purposeFor(conn: Connection): Purpose {
  // Assistant plan (2026-07-19): THE main session is a daemon-managed
  // singleton (ensureMain / session.main) — client-created sessions are
  // coding sessions with the harness default persona. Clients reach the
  // assistant by resolving session.main, never by minting another main.
  return "coding";
}

/** terminal.* guard: absent manager = off by config or node-pty load failure.
 *  MethodNotFound (not InternalError) so feature-honoring clients that raced a
 *  config change get the same class of error as any unroutable method. */
/** Terminal row → wire shape: stamp workspace_id from cwd, exactly the
 *  session.list derivation (cwd-prefix, deepest wins). The manager stays
 *  workspace-ignorant. */
function stampTerminalWorkspace(
  deps: RouterDeps,
  row: import("./terminal.js").TerminalInfoBare,
): TerminalInfoWire {
  return { ...row, workspace_id: deps.sessions.workspaces.matcher()(row.cwd) };
}

function requireTerminals(deps: RouterDeps): import("./terminal.js").TerminalManager {
  if (!deps.terminals) {
    throw new RpcMethodError(
      ErrorCode.MethodNotFound,
      "terminal support unavailable (disabled by config or PTY backend missing)",
    );
  }
  return deps.terminals;
}

function spawnAndWire(
  deps: RouterDeps,
  live: Map<string, LiveSession>,
  subs: Subscriptions,
  conn: Connection | null,
  daemonSessionId: string,
  spec: ReturnType<typeof createSessionSpec>,
  opts: { resume?: boolean; resumeHarnessSessionId?: string; resumeAtHarnessUuid?: string; model?: string; effort?: string },
  log: (line: string) => void,
  ctx: RouterCtx,
): void {
  // The creating/resuming connection is auto-subscribed (P4): legacy clients
  // that never call session.subscribe keep the single-conn behavior they had.
  // A cron revive has no connection — no subscriber is added (deliberately:
  // a phantom subscriber would defeat the M2 unattended approval fallback).
  if (conn) subs.add(daemonSessionId, conn);
  // The identity stamper (P1). Fresh sessions start seq at 0; resume continues
  // after the highest seq ever issued — from BOTH stores, so seq can't go
  // backward even if one lost writes. IDs are minted here and only here.
  // seqHighWater: after an undo popped the highest-seq rows/events, the two
  // stores alone would re-seed LOWER and reissue popped seqs (P1: reuse is
  // corruption) — the session row remembers the true high-water mark.
  const startSeq = opts.resume
    ? Math.max(
        deps.sessions.messages.maxSeq(daemonSessionId),
        deps.transcripts.lastSeq(daemonSessionId),
        deps.sessions.get(daemonSessionId)?.seqHighWater ?? 0,
      )
    : 0;
  const identity = new SessionIdentity(daemonSessionId, conn ? originFromConn(conn, "text") : cronOrigin(), {
    store: deps.sessions.messages,
    now: deps.now,
    startSeq,
  });

  // ONE outbound path: every session event — from the adapter OR the router —
  // is stamped (message_id/seq/ts/origin), cached, and fanned out to ALL
  // subscribers (P4), not just the creating connection. Append happens BEFORE
  // send: a client subscribing mid-stream replays the cache and misses
  // nothing. Append + send are guarded separately: a disk error must not kill
  // the drain loop (R3), and one dead socket must not starve the others.
  const emit = (event: JsonRpcEvent): void => {
    // Guard emit-after-delete (transcript resurrection race): a late in-flight
    // harness event that fires after session.delete cascaded the index row +
    // transcript must not recreate the NDJSON, nor fan out for a session that's
    // gone. isDeleted is an in-memory tombstone Set flipped by
    // SessionManager.delete — this guard runs on EVERY emitted event
    // including deltas, so it must not pay the SELECT * that sessions.get()
    // costs (and deletes only happen in-process, so the Set is complete).
    // session.stop/error only END the row (no delete), so their terminal
    // status.update/error still emit.
    if (deps.sessions.isDeleted(daemonSessionId)) return;
    for (const stamped of identity.stampEvent(event)) {
      try { deps.transcripts.append(daemonSessionId, stamped); } catch (e) { log(`[transcript] append failed: ${(e as Error).message}`); }
      subs.sendRaw(daemonSessionId, JSON.stringify(stamped));
    }
  };

  // Turn boundary: fold the turn's delta runs into ONE consolidated event per
  // message in the transcript cache. Deltas append RAW during the turn (crash
  // durability — they're the only copy of a half-written message); by the time
  // this runs the turn is settled, so the file can be rewritten. Message
  // statuses drive the two id sets: still-`streaming` (a slash-command turn
  // that emitted no message.complete) is left raw, `incomplete`/`error` folds
  // with partial:true. The whole-session sets are a harmless superset — earlier
  // turns are already consolidated and pass straight through.
  const compactTranscript = (): void => {
    if (deps.sessions.isDeleted(daemonSessionId)) return; // same tombstone guard as emit
    try {
      const { streaming, partial } = deps.sessions.messages.unsettledIds(daemonSessionId);
      deps.transcripts.compact(daemonSessionId, { skipMessageIds: streaming, partialMessageIds: partial });
    } catch (e) {
      log(`[transcript] compact failed: ${(e as Error).message}`);
    }
    // The turn's text is whole on disk now — index it. This is THE hot index
    // hook: every turn end (result, error, stop/reap) funnels through here.
    reindexSearch(deps, daemonSessionId, log);
  };

  // P2: runState flips are pushed as stamped status.update events, so clients
  // render "the agent is working / idle / hung" live without polling.
  const setRunState = (runState: import("./session-manager.js").SessionRunState): void => {
    deps.sessions.setRunState(daemonSessionId, runState, deps.now());
    emit(makeEvent("status.update", { session_id: daemonSessionId, lifecycle: "active", run_state: runState }, daemonSessionId));
  };

  // Transient emit (M2 decision 6): stamp + fan out, SKIP the transcript
  // cache — a replayed already-decided approval card is wrong. Returns the
  // stamped frames so a pending approval.request can be re-sent verbatim to
  // a mid-park subscriber.
  const emitTransient = (event: JsonRpcEvent): string[] => {
    const frames: string[] = [];
    for (const stamped of identity.stampEvent(event)) {
      const frame = JSON.stringify(stamped);
      frames.push(frame);
      subs.sendRaw(daemonSessionId, frame);
    }
    return frames;
  };

  // ── M2 approvals ──────────────────────────────────────────────────────────
  const approvals = makeSessionApprovals({
    deps, daemonSessionId, subs, emitTransient, setRunState, log,
  });
  const clarifies = makeSessionClarifies({
    daemonSessionId, subs, emitTransient, setRunState, log,
  });
  const secrets = makeSessionSecrets({
    deps, daemonSessionId, subs, emitTransient, setRunState, log,
  });

  const emitError = (kind: string, message: string) => {
    // Make failures visible, not silent (M3/R2/R3/R7): terminal lifecycle,
    // remove from live, clear the supervisor latch, and tell the client.
    // The in-flight message keeps its id; status=error records the failure.
    approvals.drainDeny(`session error: ${kind}`); // unblock parked canUseTool promises
    clarifies.drainDrop(`session error: ${kind}`);
    secrets.drainDeny(`session error: ${kind}`);
    identity.closeOpen("error");
    deps.sessions.end(daemonSessionId, deps.now());
    live.delete(daemonSessionId);
    deps.supervisor?.clear(daemonSessionId);
    emit(makeEvent("error", { kind, message, session_id: daemonSessionId }, daemonSessionId));
    emit(makeEvent("status.update", { session_id: daemonSessionId, lifecycle: "ended", run_state: "idle" }, daemonSessionId));
    compactTranscript(); // the error path is a turn end too — closeOpen("error") already ran
    // A watched session dying is exactly what a watcher needs to hear about.
    fireWatch(`errored (${kind}): ${message}`);
    log(`[session ${daemonSessionId}] error (${kind}): ${message}`);
  };

  // Context-pressure reminder state (advisory nudge; see LiveSession).
  const contextGuard: LiveSession["contextGuard"] = { reminded: false };

  // Title/summary seed: at most ONE attempt per live session, whether it
  // succeeds, returns nothing, or throws. Without this a session that the
  // namer declines to name (too thin an exchange) would pay for a side-call
  // on every turn forever.
  let namingAttempted = false;

  /**
   * After the first completed turn, label the session from its opening
   * exchange (see session-namer.ts). Fire-and-forget: naming is decoration,
   * so it never blocks the turn and never surfaces an error to the client.
   *
   * Ownership rules, which are the whole point:
   *  - the MAIN session is never renamed (it is "Marmalade" by design);
   *  - a title the user set is never overwritten — only a placeholder is
   *    claimed;
   *  - the summary is seeded only when there isn't one. From then on the
   *    session's own model owns it via `update_session_summary`, and this
   *    must not stomp what it wrote.
   */
  function maybeSeedNaming(): void {
    if (namingAttempted) return;
    const rec = deps.sessions.get(daemonSessionId);
    if (!rec) return;
    // purpose "main" is the ASSISTANT PERSONA family — THE daemon-managed main
    // session and the cron sessions (Daily Briefing, Project Radar). Their
    // names are chosen by whoever created them, so they are never relabelled.
    //
    // Their SUMMARY is a different question, and skipping the whole seed for
    // them was wrong (reported 2026-07-25: session summaries were not working
    // in practice): the app cold-starts INTO the main session, so its panel is
    // the Summary card users see most, and it could only ever be empty until
    // the agent happened to call update_session_summary itself.
    const wantsTitle = spec.purpose !== "main" && isPlaceholderTitle(rec.title);
    const wantsSummary = !rec.summary;
    if (!wantsTitle && !wantsSummary) return;
    // Explicit empty string = seeding disabled (MARMALADE_NAMING_MODEL="").
    // The daemon's own test script sets exactly that: several suites spawn
    // real sessions, and a unit suite must not make live model calls (they
    // cost tokens, add ~25s, and their replies land after the test ends).
    if (deps.cfg.namingModel === "") return;
    namingAttempted = true;

    void (async () => {
      // Two turns, no tool calls, no thinking: the namer wants the shape of
      // the conversation, and tool transcripts would bury it.
      const digest = renderSessionTurns(deps.transcripts.replay(daemonSessionId), {
        turns: 2,
        includeToolCalls: false,
        includeThinking: false,
      });
      const naming = await generateNaming(digest, { model: deps.cfg.namingModel, log });
      if (!naming) return;
      // The side-call outlives the turn that triggered it, so this session can
      // be stopped — or the whole daemon shut down and its store closed —
      // while the namer is still thinking. Naming is decoration: if the
      // session is no longer live, drop the result rather than write into a
      // torn-down store. (Found by the daemon's own suite, which spawns real
      // sessions and closes the database at test end: the write landed as an
      // unhandled "database is not open" rejection AFTER the test finished.)
      if (!live.has(daemonSessionId)) return;

      try {
        // Re-read: the turn that triggered this may have called
        // update_session_summary itself while the side-call was in flight, and
        // the agent's own summary always wins over a transcript reader's.
        const now = deps.sessions.get(daemonSessionId);
        if (!now) return;
        let changed = false;
        if (wantsTitle && naming.title && isPlaceholderTitle(now.title)) {
          deps.sessions.setTitle(daemonSessionId, naming.title);
          changed = true;
        }
        if (naming.summary && !now.summary) {
          deps.sessions.setSummary(daemonSessionId, { summary: naming.summary }, deps.now());
          changed = true;
        }
        if (!changed) return;
        const seeded = deps.sessions.get(daemonSessionId)!;
        log(`[namer] ${daemonSessionId}: ${seeded.title ?? "(untitled)"}`);
        // Tell connected clients. They already refresh their session list on
        // session.info (that is how a cross-client rename propagates), so the
        // drawer relabels itself without anyone pulling to refresh.
        emit(makeEvent("session.info", {
          session_id: daemonSessionId,
          title: seeded.title,
          summary: seeded.summary,
        }, daemonSessionId));
      } catch (e) {
        // One catch for the whole write: a rejected summary (length), a store
        // that closed between the liveness check and the write. Nothing here
        // is worth failing a turn over — this path is decoration on a
        // fire-and-forget promise, so an escape becomes an unhandled rejection.
        log(`[namer] write skipped: ${(e as Error).message}`);
      }
    })();
  }

  // Behavior injection (M4a). systemPrompt is per-PROCESS identity → applied on
  // every spawn of a main session, INCLUDING resume (a resumed session must not
  // lose the marmalade persona). The state preload is per-CONVERSATION context
  // → fresh sessions only, never re-injected on resume (coh-H3).
  let systemPrompt: string | undefined;
  let firstMessageContext: string | undefined;
  if (spec.purpose === "main") {
    try {
      systemPrompt = renderMainSystemPrompt(deps.cfg.behaviorDir, deps.cfg.userBehaviorPath, log) || undefined;
      // Preload keys on harness state, not on create-vs-resume: a resume with
      // no harness session (post-clear, or created but never prompted) is a
      // fresh conversation and gets the preload again (coh-H3 still holds —
      // once per conversation GENERATION, never re-injected mid-conversation).
      if (!opts.resumeHarnessSessionId) {
        firstMessageContext = assembleStatePreload(deps.cfg.wikiRoot) || undefined;
        if (firstMessageContext) log(`[main-session] preloaded state (${firstMessageContext.length} chars)`);
      }
    } catch (e) {
      // A bad/unreadable wiki file (Syncthing race) must not stillbirth the
      // session — proceed without preload rather than throwing (R6).
      log(`[main-session] preload skipped: ${(e as Error).message}`);
    }
  }

  // ── Cross-session toolset (assistant plan 2026-07-19) ────────────────────
  // Every session gets it (decision: full everywhere), loop-guarded: a turn that
  // was itself started by another session (source=agent) cannot send/steer/
  // interrupt — chains stop at one hop, so two sessions can't ping-pong.
  const guardAgentChain = (): void => {
    if (identity.currentTurnOrigin().source === "agent") {
      throw new Error("this turn was started by another session — agent-to-agent chains stop at one hop; report to the user instead of chaining");
    }
  };
  const guardNotSelf = (targetId: string): void => {
    if (targetId === daemonSessionId) throw new Error("that session_id is THIS session — cross-session tools target other sessions");
  };
  const requireTarget = (targetId: string) => {
    const rec = deps.sessions.get(targetId);
    if (!rec) throw new Error(`unknown session ${targetId} — check list_sessions`);
    return rec;
  };
  const sessionTools: SessionToolsApi = {
    listSessions: () => {
      const workspaceFor = deps.sessions.workspaces.matcher();
      const wsName = new Map(deps.sessions.workspaces.list().map((w) => [w.id, w.name]));
      const mainId = deps.sessions.getMeta(MAIN_SESSION_KEY);
      return deps.sessions.list().map((r) => ({
        session_id: r.id,
        is_main: r.id === mainId,
        title: r.title,
        archived: r.archived,
        topic: r.topic,
        summary: r.summary,
        workspace: (() => { const id = workspaceFor(r.cwd); return id ? wsName.get(id) ?? null : null; })(),
        cwd: r.cwd,
        run_state: r.runState,
        lifecycle: r.lifecycle,
        model: r.model,
        last_active: r.lastActive,
        created_at: r.createdAt,
      }));
    },
    getSessionTurns: (targetId, o) => {
      requireTarget(targetId);
      return renderSessionTurns(deps.transcripts.replay(targetId), o);
    },
    sendToSession: async (targetId, prompt) => {
      guardAgentChain();
      guardNotSelf(targetId);
      requireTarget(targetId);
      if (!live.has(targetId)) {
        await ctx.enforceCap();
        await resumeSession(deps, live, subs, null, { session_id: targetId }, log, ctx);
      }
      const t = live.get(targetId);
      if (!t) throw new Error(`session ${targetId} failed to revive`);
      await submitPromptCore(deps, t, targetId, prompt, agentOrigin(daemonSessionId));
      log(`[session-tools] ${daemonSessionId} → prompt into ${targetId}`);
      return `Prompt queued in session ${targetId} — it runs there as the next turn. Use watch_session(${targetId}) if you need to know when it completes.`;
    },
    steerSession: async (targetId, prompt) => {
      guardAgentChain();
      guardNotSelf(targetId);
      requireTarget(targetId);
      const t = live.get(targetId);
      if (!t || deps.sessions.get(targetId)?.runState !== "running") {
        throw new Error(`session ${targetId} has no turn in flight — use send_to_session`);
      }
      await steerCore(deps, t, targetId, prompt, agentOrigin(daemonSessionId));
      log(`[session-tools] ${daemonSessionId} → steer into ${targetId}`);
      return `Steer delivered into session ${targetId}'s running turn.`;
    },
    interruptSession: async (targetId) => {
      guardAgentChain();
      guardNotSelf(targetId);
      requireTarget(targetId);
      const t = live.get(targetId);
      if (!t) throw new Error(`session ${targetId} is not live — nothing to interrupt`);
      await t.harness.interrupt();
      t.identity.closeOpen("incomplete");
      log(`[session-tools] ${daemonSessionId} interrupted ${targetId}`);
      return `Interrupted session ${targetId}'s turn. The session stays usable.`;
    },
    watchSession: (targetId, note) => {
      guardNotSelf(targetId);
      requireTarget(targetId);
      if (targetId === deps.sessions.getMeta(MAIN_SESSION_KEY)) {
        throw new Error("the main session cannot be watched — watch digests land IN the main session");
      }
      ctx.watches.set(targetId, { ...(note ? { note } : {}), by: daemonSessionId });
      return `Watching session ${targetId} — the main session gets a digest when its turn completes (one-shot).`;
    },
    // The secret-entry flow, on THIS session (unlike the cross-session tools
    // above, which take a target id): the prompt goes to the devices watching
    // the conversation the agent is having. Never throws — every outcome,
    // including "nobody can be asked", is a sentence the model reads.
    requestSecret: (entry, description) => secrets.request(entry, description),
  };

  // A watched session's turn settled (or died): drop the one-shot digest
  // into the MAIN session. source=agent, so the digest turn is itself
  // loop-guarded (main can relay to the user, not chain more agent prompts).
  const fireWatch = (outcome: string, lastReply?: string): void => {
    const w = ctx.watches.get(daemonSessionId);
    if (!w) return;
    ctx.watches.delete(daemonSessionId);
    void (async () => {
      const mainId = deps.sessions.getMeta(MAIN_SESSION_KEY);
      if (!mainId || mainId === daemonSessionId || !deps.sessions.get(mainId)) return;
      const rec = deps.sessions.get(daemonSessionId);
      const label = rec?.title ? `"${rec.title}" (${daemonSessionId})` : daemonSessionId;
      const lines = [`[session watch] ${label} ${outcome}.`];
      if (w.note) lines.push(`Watch note: ${w.note}`);
      if (lastReply) {
        const t = lastReply.trim();
        lines.push(`Last reply: ${t.length > 600 ? `${t.slice(0, 600)}…` : t}`);
      }
      if (!live.has(mainId)) {
        await ctx.enforceCap();
        await resumeSession(deps, live, subs, null, { session_id: mainId }, log, ctx);
      }
      const m = live.get(mainId);
      if (!m) throw new Error("main session failed to revive");
      await submitPromptCore(deps, m, mainId, lines.join("\n"), agentOrigin(daemonSessionId));
      log(`[session-tools] watch fired: ${daemonSessionId} → main`);
    })().catch((e) => log(`[session-tools] watch digest failed for ${daemonSessionId}: ${(e as Error).message}`));
  };

  let pendingRewind = Boolean(opts.resumeAtHarnessUuid);
  let session: HarnessSession;
  try {
    session = deps.adapter.spawn(spec, {
      daemonSessionId,
      resumeHarnessSessionId: opts.resumeHarnessSessionId,
      ...(opts.resumeAtHarnessUuid ? { resumeAtHarnessUuid: opts.resumeAtHarnessUuid } : {}),
      systemPrompt,
      firstMessageContext,
      ...(opts.model ? { model: opts.model } : {}),
      ...(opts.effort ? { effort: opts.effort } : {}),
    }, {
      // session.info from the SDK init carries the model; decorate it with
      // the effort this spawn was given so the clients' (already-shipped)
      // reasoning_effort adopt path lights up without a new event.
      onEvent: opts.effort
        ? (ev) => {
            if (ev.params.type === "session.info") {
              (ev.params.payload as Record<string, unknown>).reasoning_effort = opts.effort;
            }
            emit(ev);
          }
        : emit,
      onHarnessSession: (hsid) => {
        deps.sessions.bindHarnessSession(daemonSessionId, hsid);
        // Harness ready: starting → idle. Guarded so a prompt that raced in
        // first (already running) isn't stomped back to idle.
        if (deps.sessions.get(daemonSessionId)?.runState === "starting") setRunState("idle");
      },
      onHarnessMessageUuid: (uuid) => identity.captureHarnessUuid(uuid),
      onResult: (result, apiKeySource) => {
        // A rewound spawn's first result means the harness JSONL tip IS the
        // new branch now — plain resume is correct from here on (undo).
        if (pendingRewind) {
          pendingRewind = false;
          deps.sessions.clearHarnessResumeAt(daemonSessionId);
        }
        // Context-pressure tracking: remember the latest occupancy and
        // re-arm the one-shot reminder once compaction/clear drops it back
        // under the threshold.
        if (result.contextPercent !== undefined) {
          contextGuard.percent = result.contextPercent;
          const threshold = deps.cfg.contextReminderPercent;
          if (threshold > 0 && result.contextPercent < threshold) contextGuard.reminded = false;
        }
        // Persist the occupancy on the row so a client opening this session
        // COLD (no turn since it connected) shows context immediately —
        // pushed data, zero extra harness queries. Deliberately not undone by
        // session.undo: the number goes briefly stale and self-heals on the
        // next turn, which beats guessing what the popped turn cost.
        if (result.contextUsed !== undefined) {
          deps.sessions.setContext(daemonSessionId, result.contextUsed, result.contextMax);
        }
        deps.usage.record(deps.today(), spec.purpose, {
          costUsd: result.totalCostUsd,
          inputTokens: result.inputTokens,
          outputTokens: result.outputTokens,
        });
        setRunState("idle"); // turn complete
        compactTranscript(); // after the terminal status.update is on disk
        // Seed the title + first summary from the opening exchange. AFTER
        // compactTranscript so the turn we are about to read is on disk.
        maybeSeedNaming();
        // One-shot watch (session tools): the turn settled — digest to main.
        fireWatch(result.isError ? `finished with an error (${result.subtype})` : "finished a turn", result.text);
        log(`[usage] ${spec.purpose} +$${result.totalCostUsd} (${result.inputTokens}+${result.outputTokens} tok, auth=${apiKeySource ?? "?"})`);
      },
      onActivity: () => deps.sessions.heartbeat(daemonSessionId, deps.now()),
      onError: (kind, message) => emitError(kind, message),
      // M2: both adapters route tool-use approvals through this one seam.
      // Adapters stay mode-ignorant — auto mode allows-with-log here.
      requestApproval: approvals.request,
      // Agent questions (AskUserQuestion) park here — never mode-gated.
      requestClarify: clarifies.request,
      // Cross-session tools (list/turns/send/steer/interrupt/watch) — backs
      // the marmalade MCP session toolset on every session.
      sessionTools,
      // The device roster (P3): backs the list_devices MCP tool. Read live on
      // every call — the roster grows as devices hello, no session restart.
      listDevices: () => {
        const connected = deps.connectedDevices?.() ?? new Set<string>();
        return deps.sessions.devices.list().map((d) => ({
          device_id: d.deviceId,
          platform: d.platform,
          connected: connected.has(d.deviceId),
          capabilities: d.capabilities,
          first_seen: d.firstSeen,
          last_seen: d.lastSeen,
        }));
      },
      onSummaryUpdate: (s) => {
        try {
          deps.sessions.setSummary(daemonSessionId, s, deps.now());
          log(`[summary] ${daemonSessionId}: ${(s.topic ? s.topic + " — " : "")}${s.summary.slice(0, 60)}…`);
        } catch (e) {
          log(`[summary] rejected: ${(e as Error).message}`);
        }
      },
    });
  } catch (e) {
    emitError("spawn_failed", (e as Error).message);
    return;
  }

  live.set(daemonSessionId, { harness: session, identity, emit, setRunState, compactTranscript, approvals, clarifies, secrets, contextGuard });

  // Spawn returned → the child is up, and no turn is in flight yet. Flip
  // starting → idle HERE, not only in onHarnessSession: the Claude SDK inits
  // lazily (no init until the first prompt), so a created-or-resumed session
  // that never gets a prompt would sit in `starting` until the supervisor
  // false-flags it hung (seen in the 2026-07-11 Android live verify). The
  // first real turn is covered by running + heartbeats; a spawn that throws
  // never reaches this line. Guarded like onHarnessSession so an adapter
  // that already advanced the state (eager init, or a raced-in prompt) isn't
  // stomped back to idle.
  if (deps.sessions.get(daemonSessionId)?.runState === "starting") setRunState("idle");
}

async function createSession(
  deps: RouterDeps,
  live: Map<string, LiveSession>,
  subs: Subscriptions,
  conn: Connection,
  params: import("@marmalade/protocol").SessionCreateParams,
  log: (line: string) => void,
  ctx: RouterCtx,
): Promise<{ session_id: string }> {
  const spec = createSessionSpec(
    { principal: conn.principal as Principal, purpose: purposeFor(conn), origin: "text", cwd: params.cwd },
    deps.cfg,
  );
  const id = deps.mintSessionId();
  // Defaults slice (2026-07-23): the daemon OWNS new-session defaults. A
  // model-less / effort-less create gets config default_model /
  // default_effort stamped on the row — so clients see the real values on
  // session rows instead of an opaque "Default". Effort is validated here
  // (the wire schema stays a plain string for compat; the SDK would silently
  // drop garbage, which is the failure class this daemon refuses).
  if (params.reasoning_effort !== undefined
      && !(EFFORT_LEVELS as readonly string[]).includes(params.reasoning_effort)) {
    throw new RpcMethodError(
      ErrorCode.InvalidParams,
      `reasoning_effort "${params.reasoning_effort}" is not one of ${EFFORT_LEVELS.join("/")}`,
    );
  }
  const model = params.model ?? effectiveDefaultModel(deps);
  const requestedEffort = params.reasoning_effort ?? deps.cfg.defaultEffort;
  // Per-model bounds (2026-07-27): clamp BEFORE the row is stamped and the
  // child spawned, so session rows / session.info always show the effective
  // truth rather than a level the model was never going to run at. An absent
  // effort stays absent — null means "let the harness pick", which is not the
  // same as "run at the floor", so bounds must not manufacture a value.
  const effort = requestedEffort === undefined
    ? undefined
    : clampEffort(requestedEffort as EffortLevel, effortBoundsFor(deps, model));
  // model is stored on the row so RESUME re-applies it — a session's model is
  // chosen once at create and sticks for the session's whole life. approvals
  // likewise persists (but stays mutable via session.approvals).
  deps.sessions.create(id, spec, deps.adapter.name, deps.now(), model, params.approvals, effort);
  // The schema has always accepted title; now that session.title exists,
  // honor it on create too (same cap, same column).
  if (params.title) deps.sessions.setTitle(id, params.title);
  spawnAndWire(deps, live, subs, conn, id, spec, {
    ...(model ? { model } : {}),
    ...(effort ? { effort } : {}),
  }, log, ctx);
  // E3: recorded AFTER the spawn so it rides the live session's emit (stamped,
  // cached, and broadcast to the creating connection, which spawnAndWire just
  // subscribed) rather than racing the fresh identity's seq counter. A spawn
  // that failed leaves no live entry — the helper then appends directly.
  if (requestedEffort !== undefined) {
    recordEffortClamp(deps, live, id, requestedEffort as EffortLevel, effort!, model, log);
  }
  return { session_id: id };
}

async function resumeSession(
  deps: RouterDeps,
  live: Map<string, LiveSession>,
  subs: Subscriptions,
  conn: Connection | null,
  params: import("@marmalade/protocol").SessionResumeParams,
  log: (line: string) => void,
  ctx: RouterCtx,
): Promise<{ session_id: string }> {
  const rec = deps.sessions.get(params.session_id);
  if (!rec) throw new RpcMethodError(ErrorCode.InvalidParams, `unknown session ${params.session_id}`);
  // Resume-while-live: the session already has a running child — attach this
  // connection (subscribe it) rather than spawning a second child that
  // orphans the first and interleaves into one transcript (S2).
  if (live.has(rec.id)) {
    if (conn) subs.add(rec.id, conn);
    return { session_id: rec.id };
  }
  // The row already exists — resume UPDATEs it, never re-INSERTs (H1) and
  // never re-mints: SAME sessionId, lifecycle back to active (P2). Only the
  // private harnessSessionId may rebind underneath.
  deps.sessions.revive(rec.id, deps.now());
  const spec = createSessionSpec(
    { principal: rec.principal as Principal, purpose: rec.purpose as Purpose, origin: "text", cwd: rec.cwd },
    deps.cfg,
  );
  spawnAndWire(deps, live, subs, conn, rec.id, spec, {
    resume: true,
    resumeHarnessSessionId: rec.harnessSessionId ?? undefined,
    // Pending rewind (session.undo): consumed by this spawn; cleared after
    // its first turn result. Idempotent to re-consume before any new turn.
    ...(rec.harnessResumeAt ? { resumeAtHarnessUuid: rec.harnessResumeAt } : {}),
    ...(rec.model ? { model: rec.model } : {}),
    ...(rec.reasoningEffort ? { effort: rec.reasoningEffort } : {}),
  }, log, ctx);
  return { session_id: rec.id };
}

/** Human-readable one-liner for an approval card (M2 decision 5). */
function approvalCommand(info: ApprovalInfo): string {
  const input = info.input as Record<string, unknown> | undefined;
  if (typeof input?.command === "string") return input.command;
  let compact = "";
  try { compact = JSON.stringify(info.input) ?? ""; } catch { compact = String(info.input); }
  return `${info.toolName}(${compact.slice(0, 200)})`;
}

/** pattern_key (M2 decision 4): tool name, except Bash-like tools where it's
 *  "<tool>:<first token of command>" — dumb and legible by design. */
function approvalPatternKey(info: ApprovalInfo): string {
  const input = info.input as Record<string, unknown> | undefined;
  if (typeof input?.command === "string") {
    const first = input.command.trim().split(/\s+/)[0] ?? "";
    return `${info.toolName}:${first}`;
  }
  return info.toolName;
}

function makeSessionApprovals(ctx: {
  deps: RouterDeps;
  daemonSessionId: string;
  subs: Subscriptions;
  emitTransient(event: JsonRpcEvent): string[];
  setRunState(runState: import("./session-manager.js").SessionRunState): void;
  log(line: string): void;
}): SessionApprovals {
  const { deps, daemonSessionId, subs, emitTransient, setRunState, log } = ctx;
  const pending: SessionApprovals["pending"] = new Map();
  const allowedPatterns = new Set<string>();
  // Serialization gate (M2 decision 3): a second concurrent approval request
  // parks BEHIND the first — its approval.request is only emitted after the
  // first resolves, so the FIFO invariant is structurally true even when the
  // harness issues parallel tool calls.
  let chain: Promise<unknown> = Promise.resolve();

  const effectiveMode = (): string =>
    deps.sessions.get(daemonSessionId)?.approvals ?? deps.cfg.approvalsMode;

  const requestInner = async (info: ApprovalInfo): Promise<ApprovalDecision> => {
    const command = approvalCommand(info);
    if (effectiveMode() !== "prompt") {
      log(`[approval] auto-approved (mode=auto) ${info.toolName} ${command.slice(0, 120)}`);
      return { behavior: "allow" };
    }
    const patternKey = approvalPatternKey(info);
    if (allowedPatterns.has(patternKey)) {
      log(`[approval] allowed by session pattern "${patternKey}"`);
      return { behavior: "allow" };
    }
    // Unattended fallback (decision 2): a headless/cron session must not
    // hang. With subscribers present we park indefinitely — awaiting_input
    // is not a failure, and there is deliberately NO timeout-auto-approve.
    if (subs.count(daemonSessionId) === 0) {
      log(`[approval] auto-approved (no subscribers) ${info.toolName} ${command.slice(0, 120)}`);
      return { behavior: "allow" };
    }
    const requestId = mintMessageId();
    return new Promise<ApprovalDecision>((resolve) => {
      const frames = emitTransient(makeEvent("approval.request", {
        request_id: requestId,
        tool_name: info.toolName,
        command,
        description: info.description ?? command,
        pattern_key: patternKey,
        allow_permanent: false, // 'always' is not offered in v1 (decision 4)
      }, daemonSessionId));
      pending.set(requestId, { patternKey, frame: frames[frames.length - 1] ?? "", resolve });
      setRunState("awaiting_input"); // only when actually parking (decision 7)
      log(`[approval] parked ${requestId}: ${info.toolName} ${command.slice(0, 120)}`);
    });
  };

  const settle = (requestId: string, decision: ApprovalDecision, choice: string): boolean => {
    const p = pending.get(requestId);
    if (!p) return false;
    pending.delete(requestId);
    if ((choice === "session" || choice === "always") && decision.behavior === "allow") {
      allowedPatterns.add(p.patternKey);
    }
    setRunState("running");
    emitTransient(makeEvent("approval.resolved", { request_id: requestId, choice }, daemonSessionId));
    p.resolve(decision);
    return true;
  };

  return {
    pending,
    allowedPatterns,
    request(info: ApprovalInfo): Promise<ApprovalDecision> {
      const result = chain.then(() => requestInner(info));
      chain = result.catch(() => {});
      return result;
    },
    respond(choice, requestId, deniedFrom): boolean {
      const id = requestId ?? pending.keys().next().value; // FIFO fallback
      if (!id) return false;
      // Carry the choice on the decision so the adapter can record it against
      // the tool call it settles. Only a REAL answer gets one — the
      // auto-approve / pattern-allowlist / no-subscribers paths above return
      // early without it, so the transcript never claims the user approved
      // something they were never shown.
      const decision: ApprovalDecision = choice === "deny"
        ? { behavior: "deny", message: `Denied by user from ${deniedFrom} device`, choice }
        : { behavior: "allow", choice };
      return settle(id, decision, choice);
    },
    drainAllow(reason): void {
      for (const id of [...pending.keys()]) {
        log(`[approval] AUTO-APPROVING parked ${id}: ${reason} — a dropped connection must not kill the run`);
        settle(id, { behavior: "allow" }, "once");
      }
    },
    drainDeny(reason): void {
      for (const id of [...pending.keys()]) {
        const p = pending.get(id)!;
        pending.delete(id);
        p.resolve({ behavior: "deny", message: reason });
      }
    },
  };
}

/** The message the agent sees when a question could not be answered. Deny on
 *  AskUserQuestion is a tool_result, not a failure — phrase it as guidance. */
function clarifyUnanswered(reason: string): string {
  return `The user could not answer (${reason}) — proceed with your best judgment.`;
}

function makeSessionClarifies(ctx: {
  daemonSessionId: string;
  subs: Subscriptions;
  emitTransient(event: JsonRpcEvent): string[];
  setRunState(runState: import("./session-manager.js").SessionRunState): void;
  log(line: string): void;
}): SessionClarifies {
  const { daemonSessionId, subs, emitTransient, setRunState, log } = ctx;
  const pending: SessionClarifies["pending"] = new Map();
  // Same serialization gate as approvals: a second concurrent question parks
  // BEHIND the first, so at most one clarify.request is outstanding.
  let chain: Promise<unknown> = Promise.resolve();

  const requestInner = async (questions: ClarifyQuestion[]): Promise<ClarifyDecision> => {
    const label = questions.map((q) => q.header || q.question).join(" / ").slice(0, 120);
    // Unattended fallback: a headless/cron session must not hang on a
    // question nobody can see — the agent is told to decide for itself.
    if (subs.count(daemonSessionId) === 0) {
      log(`[clarify] unanswered (no subscribers): ${label}`);
      return { answered: false, message: clarifyUnanswered("no one is connected") };
    }
    const requestId = mintMessageId();
    return new Promise<ClarifyDecision>((resolve) => {
      const frames = emitTransient(makeEvent("clarify.request", {
        request_id: requestId,
        questions: questions.map((q) => ({
          question: q.question,
          header: q.header,
          multi_select: q.multiSelect,
          options: q.options.map((o) => ({ label: o.label, description: o.description })),
        })),
      }, daemonSessionId));
      pending.set(requestId, { frame: frames[frames.length - 1] ?? "", resolve });
      setRunState("awaiting_input");
      log(`[clarify] parked ${requestId}: ${label}`);
    });
  };

  const settle = (requestId: string, decision: ClarifyDecision): boolean => {
    const p = pending.get(requestId);
    if (!p) return false;
    pending.delete(requestId);
    setRunState("running");
    emitTransient(makeEvent("clarify.resolved", { request_id: requestId }, daemonSessionId));
    p.resolve(decision);
    return true;
  };

  return {
    pending,
    request(questions: ClarifyQuestion[]): Promise<ClarifyDecision> {
      const result = chain.then(() => requestInner(questions));
      chain = result.catch(() => {});
      return result;
    },
    respond({ requestId, answers, response }): boolean {
      const id = requestId ?? pending.keys().next().value; // FIFO fallback
      if (!id) return false;
      const answered = (answers !== undefined && Object.keys(answers).length > 0) || Boolean(response);
      const decision: ClarifyDecision = answered
        ? { answered: true, answers: answers ?? {}, ...(response ? { response } : {}) }
        : { answered: false, message: "The user dismissed the question — proceed with your best judgment." };
      return settle(id, decision);
    },
    drainUnanswered(reason: string): void {
      for (const id of [...pending.keys()]) {
        log(`[clarify] settling parked ${id} unanswered: ${reason}`);
        settle(id, { answered: false, message: clarifyUnanswered(reason) });
      }
    },
    drainDrop(reason: string): void {
      for (const id of [...pending.keys()]) {
        const p = pending.get(id)!;
        pending.delete(id);
        p.resolve({ answered: false, message: clarifyUnanswered(reason) });
      }
    },
  };
}

/** How long a credential prompt may sit unanswered before it denies itself.
 *  Approvals deliberately have no timeout; this one does because an abandoned
 *  password prompt has no recovery path but time (nobody else can answer it,
 *  and there is no safe default). Generous: the user may be mid-something, and 10
 *  minutes is "you walked away", not "you're typing carefully". */
const SECRET_PARK_TIMEOUT_MS = 10 * 60_000;

/** The tool result when nobody can be asked. Names the exact command so the
 *  agent can hand the user something to run instead of inventing a workaround
 *  (or, worse, asking for the secret in chat). */
function secretTerminalFallback(entry: string): string {
  return `No client available to collect a secret: no connected device can render a secure input. `
    + `Ask the user to store it themselves by running: gopass insert ${entry} — `
    + `then continue. Do NOT ask them to type the secret to you.`;
}

function makeSessionSecrets(ctx: {
  deps: RouterDeps;
  daemonSessionId: string;
  subs: Subscriptions;
  emitTransient(event: JsonRpcEvent): string[];
  setRunState(runState: import("./session-manager.js").SessionRunState): void;
  log(line: string): void;
}): SessionSecrets {
  const { deps, daemonSessionId, subs, emitTransient, setRunState, log } = ctx;
  const pending = new Map<string, {
    entry: string;
    frame: string;
    timer: ReturnType<typeof setTimeout>;
    resolve(message: string): void;
  }>();
  // Same serialization gate as approvals/clarifies: one secure input on screen
  // at a time, so FIFO correlation is structurally unambiguous.
  let chain: Promise<unknown> = Promise.resolve();

  // Production writes through keyring.ts with the daemon's keyring config;
  // tests inject a fake so the suite needs no secret manager.
  const write = deps.storeSecret ?? ((entry: string, value: string) => keyringStoreSecret(entry, value, deps.cfg.keyring));

  const requestInner = async (entry: string, description: string): Promise<string> => {
    // The capability gate (see SessionSecrets note 2). Fail fast rather than
    // park: nobody is coming, and a hung turn teaches the agent nothing.
    if (subs.countCapable(daemonSessionId, "secrets") === 0) {
      log(`[secret] refused "${entry}" for session ${daemonSessionId}: no secrets-capable client connected`);
      return secretTerminalFallback(entry);
    }
    const requestId = mintMessageId();
    return new Promise<string>((resolve) => {
      const payload = {
        session_id: daemonSessionId,
        request_id: requestId,
        entry,
        description,
        created_at: deps.now(),
      } satisfies SecretRequestPayload;
      const frames = emitTransient(makeEvent("secret.request", payload, daemonSessionId));
      const timer = setTimeout(() => {
        // Settle through the same path a denial takes, so runState and the
        // clients' cards recover identically.
        settleDenied(requestId, "the prompt timed out with no answer");
      }, SECRET_PARK_TIMEOUT_MS);
      // The daemon must still be able to exit with a prompt on screen.
      timer.unref?.();
      pending.set(requestId, { entry, frame: frames[frames.length - 1] ?? "", timer, resolve });
      setRunState("awaiting_input");
      // The entry NAME and the fact a secret was asked for are the audit
      // trail we want. The value is what never appears.
      log(`[secret] parked ${requestId}: "${entry}" for session ${daemonSessionId}`);
    });
  };

  /** Pull a parked request out of the map, cancelling its timer. */
  const take = (requestId: string) => {
    const p = pending.get(requestId);
    if (!p) return undefined;
    pending.delete(requestId);
    clearTimeout(p.timer);
    return p;
  };

  const finish = (
    p: { entry: string; resolve(m: string): void },
    requestId: string,
    outcome: SecretResolvedPayload["outcome"],
    message: string,
    error?: string,
  ): void => {
    setRunState("running");
    // Outcome only — every client clears its card, none of them learns anything
    // it didn't already have.
    emitTransient(makeEvent("secret.resolved", {
      request_id: requestId,
      outcome,
      ...(error ? { error } : {}),
    } satisfies SecretResolvedPayload, daemonSessionId));
    p.resolve(message);
  };

  function settleDenied(requestId: string, reason: string): boolean {
    const p = take(requestId);
    if (!p) return false;
    log(`[secret] denied ${requestId} ("${p.entry}"): ${reason}`);
    finish(p, requestId, "denied", `The user did not provide the secret (${reason}). Do not ask for it in the conversation — proceed without it, or ask them to run: gopass insert ${p.entry}`);
    return true;
  }

  return {
    pending: pending as SessionSecrets["pending"],
    request(entry: string, description: string): Promise<string> {
      const result = chain.then(() => requestInner(entry, description));
      chain = result.catch(() => {});
      return result;
    },
    async respond({ requestId, value, deny, reason }) {
      const id = requestId ?? pending.keys().next().value; // FIFO fallback
      if (!id) return { resolved: false, stored: false };
      if (deny) {
        const settled = settleDenied(id, reason ?? "declined on the device");
        return { resolved: settled, stored: false };
      }
      const p = take(id);
      if (!p) return { resolved: false, stored: false };
      try {
        // THE handoff: from the RPC params straight into the keyring child's
        // stdin. `value` is a local that dies with this frame — it is not put
        // on the pending record, not on an event, not in a log line.
        await write(p.entry, value!);
        log(`[secret] stored "${p.entry}" for session ${daemonSessionId}`);
        finish(p, id, "stored", `stored at ${p.entry}`);
        return { resolved: true, stored: true };
      } catch (e) {
        // KeyringError messages are redacted of the value by keyring.ts, so
        // this is safe to surface to the agent, the client, and the log.
        const message = (e as Error).message;
        log(`[secret] store of "${p.entry}" FAILED for session ${daemonSessionId}: ${message}`);
        finish(p, id, "failed", `The secret could not be stored at ${p.entry}: ${message}. Do not ask the user to type it in the conversation.`, message);
        return { resolved: true, stored: false, error: message };
      }
    },
    drainDeny(reason: string): void {
      for (const id of [...pending.keys()]) settleDenied(id, reason);
    },
  };
}

async function submitPrompt(
  deps: RouterDeps,
  live: Map<string, LiveSession>,
  conn: Connection,
  params: import("@marmalade/protocol").PromptSubmitParams,
): Promise<import("@marmalade/protocol").PromptSubmitResult> {
  const s = live.get(params.session_id);
  if (!s) throw new RpcMethodError(ErrorCode.InvalidParams, `session ${params.session_id} not live`);
  // Mint the user message's immutable identity (P1). Origin is derived from
  // the authenticated connection — the schema already stripped anything a
  // client tried to smuggle into the body (sec-H3).
  const origin = originFromConn(conn, params.source ?? "text");
  // Submitting IS seeing (P4): the device sending a prompt has rendered the
  // conversation up to its own message — stamp its cursor so the session
  // doesn't badge unread on the very device that's driving it. (A cron
  // submit deliberately does NOT stamp any cursor — the resulting turn
  // SHOULD badge unread everywhere; that's the v1 delivery path.)
  return submitPromptCore(deps, s, params.session_id, params.prompt, origin, deviceIdFor(conn));
}

/** The shared submit path: client prompts (with a seen-cursor stamp) and
 *  scheduler fires (origin: cron, no cursor) both land here. */
async function submitPromptCore(
  deps: RouterDeps,
  s: LiveSession,
  sessionId: string,
  prompt: string,
  origin: Origin,
  seenDeviceId?: string,
): Promise<import("@marmalade/protocol").PromptSubmitResult> {
  const u = s.identity.beginUserMessage(origin);
  // Record the user message in the transcript cache (replay completeness for
  // the P4 subscribe/replay gate). Transcript-only: the submitting client
  // already renders its own bubble; the RPC result below binds it to our id.
  try {
    deps.transcripts.append(sessionId, makeEvent(
      "message.user",
      { message_id: u.messageId, seq: u.seq, ts: u.ts, origin: wireOrigin(origin), text: prompt },
      sessionId,
    ));
  } catch (e) { (deps.log ?? (() => {}))(`[transcript] user append failed: ${(e as Error).message}`); }
  if (seenDeviceId) deps.sessions.seen.stamp(seenDeviceId, sessionId, u.seq, u.ts);
  s.setRunState("running"); // a turn is in progress — pushed as status.update
  // Consume attachments staged since the last submit (T1): they're drained
  // into a HARNESS-only preamble listing the staged file paths, exactly like
  // the origin preamble — the transcript above kept the raw prompt, so clients
  // never render it. Cron never stages, so this is a no-op for scheduled fires.
  const staged = deps.attachments?.consume(sessionId) ?? [];
  const attachPreamble = staged.length ? `\n\n${renderAttachmentPreamble(staged)}` : "";
  // Context-pressure reminder: one-shot harness-only preamble when the last
  // turn's context occupancy crossed the configured threshold. Advisory by
  // design — the model decides WHEN a stopping point is (the daemon can't),
  // and the framing must never trigger a panicked mid-task bail; the safe
  // action it asks for (persist durable state) is never wasted. Re-arms in
  // onResult when the percent drops back under the threshold.
  const ctxThreshold = deps.cfg.contextReminderPercent;
  let ctxPreamble = "";
  if (
    ctxThreshold > 0 && !s.contextGuard.reminded &&
    s.contextGuard.percent !== undefined && s.contextGuard.percent >= ctxThreshold
  ) {
    s.contextGuard.reminded = true;
    ctxPreamble = `\n\n<system-reminder>Context usage is at ${s.contextGuard.percent}% of the model's window (daemon-measured). When you reach a natural stopping point, persist durable state — memory, handoff notes, wiki — while full fidelity remains, and prefer wrapping the current task cleanly so the next task can start in a fresh session. Do NOT abandon or rush the task in progress, and do not mention this reminder unless asked.</system-reminder>`;
  }
  // Per-turn origin injection (P3): the HARNESS sees one turn-metadata line
  // before the prompt; the transcript above kept the raw prompt (clients
  // never render the preamble). For cron the preamble reads "via cron" — the
  // agent knows the turn is a scheduled prompt, not a user typing.
  await s.harness.send(
    `${originPreamble(origin, u.ts)}${attachPreamble}${ctxPreamble}\n\n${prompt}`,
    origin.source === "voice" ? "voice" : "text",
  );
  return { message_id: u.messageId, seq: u.seq, ts: u.ts };
}

/** The shared steer path (session.steer RPC + the steer_session tool): mint
 *  the steered user message's identity, cache it, and merge it into the
 *  RUNNING turn. Callers check runState==="running" first. No runState flip —
 *  the turn is already running. */
async function steerCore(
  deps: RouterDeps,
  s: LiveSession,
  sessionId: string,
  prompt: string,
  origin: Origin,
): Promise<{ messageId: string; seq: number; ts: number }> {
  const u = s.identity.beginUserMessage(origin, { steered: true });
  // Transcript: a real user message with its own identity; `steered` marks it
  // for distinct rendering (additive — clients may ignore it).
  try {
    deps.transcripts.append(sessionId, makeEvent(
      "message.user",
      { message_id: u.messageId, seq: u.seq, ts: u.ts, origin: wireOrigin(origin), text: prompt, steered: true },
      sessionId,
    ));
  } catch (e) { (deps.log ?? (() => {}))(`[transcript] steer append failed: ${(e as Error).message}`); }
  // Same preamble as a submit, plus an explicit mid-turn marker for the model.
  await s.harness.send(
    `${originPreamble(origin, u.ts)}\n[mid-turn steer — course-correct the work in progress]\n\n${prompt}`,
    origin.source === "voice" ? "voice" : "text",
  );
  return u;
}

/** Copy the daemon-side history of a fork's source into the new session, up
 *  to the cut (inclusive): message identity rows (NEW message ids — the PK is
 *  global and ids are identities; a new session is a new identity space,
 *  parent links remapped consistently) and the transcript NDJSON (payload
 *  message ids rewritten via the same map, session_id retagged). This is what
 *  makes the fork RENDER as a conversation on subscribe/replay — the harness
 *  context came from adapter.forkSession; this is the display half. */
function copyForkHistory(
  deps: RouterDeps,
  srcId: string,
  newId: string,
  cutMsg: import("./message-store.js").MessageRecord | null,
  log: (line: string) => void,
): void {
  const idMap = new Map<string, string>();
  const mapId = (old: string): string => {
    let n = idMap.get(old);
    if (!n) { n = mintMessageId(); idMap.set(old, n); }
    return n;
  };
  const cutSeq = cutMsg ? cutMsg.seq : Infinity;
  for (const m of deps.sessions.messages.list(srcId)) {
    if (m.seq > cutSeq) break; // list is seq-ordered
    deps.sessions.messages.insert({
      messageId: mapId(m.messageId),
      sessionId: newId,
      role: m.role,
      parentMessageId: m.parentMessageId ? mapId(m.parentMessageId) : null,
      origin: m.origin,
      seq: m.seq,
      startedAt: m.startedAt,
      endedAt: m.endedAt ?? m.startedAt,
      // A copied message is history, never in flight.
      status: m.status === "streaming" ? "incomplete" : m.status,
      // Steer markers survive the copy — the fork's own undo needs the same
      // turn boundaries the source had.
      steered: m.steered,
    });
  }
  // Transcript: copy events in order; for a cut, stop AFTER the cut message's
  // message.complete (its deltas/tool events precede it). Trailing
  // status.updates past the cut are dropped — the fork's own lifecycle
  // events start fresh on revive.
  for (const ev of deps.transcripts.replay(srcId)) {
    const payload = { ...((ev.params.payload as Record<string, unknown>) ?? {}) };
    if (typeof payload.message_id === "string") payload.message_id = mapId(payload.message_id);
    if (typeof payload.parent_message_id === "string") payload.parent_message_id = mapId(payload.parent_message_id);
    if (typeof payload.session_id === "string") payload.session_id = newId;
    // Copied rows carry no harness uuids (the SDK remapped them in ITS copy),
    // so a copied bubble can never be a fork cut — flip the flag so clients
    // hide the affordance instead of hitting the "no cut point" rejection.
    if (ev.params.type === "message.complete" && "has_cut_point" in payload) payload.has_cut_point = false;
    const copied: JsonRpcEvent = {
      ...ev,
      params: { ...ev.params, session_id: newId, payload },
    };
    try {
      deps.transcripts.append(newId, copied);
    } catch (e) {
      log(`[fork] transcript copy append failed: ${(e as Error).message}`);
      break;
    }
    if (cutMsg && ev.params.type === "message.complete"
      && (ev.params.payload as { message_id?: string } | undefined)?.message_id === cutMsg.messageId) {
      break;
    }
  }
}

/** CronJobRecord → the snake_case wire shape (CronJobWire). */
function cronJobWire(j: CronJobRecord): Record<string, unknown> {
  return {
    job_id: j.id,
    name: j.name,
    session_id: j.sessionId,
    prompt: j.prompt,
    schedule: j.schedule,
    enabled: j.enabled,
    created_at: j.createdAt,
    updated_at: j.updatedAt,
    next_run_at: j.nextRunAt,
    last_run_at: j.lastRunAt,
    last_status: j.lastStatus,
    last_error: j.lastError,
  };
}
