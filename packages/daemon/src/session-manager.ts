// session-manager.ts — the SQLite session index + the supervisor seed.
//
// SQLite (node:sqlite, built-in — zero native deps) holds the INDEX only
// (simp-H1): relational queries by principal/purpose/lastActive and the
// cwd→harnessSessionId lookup for cwd-sensitive resume. The transcript itself
// is a flat NDJSON cache per session (transcript-cache.ts, M1), NOT an
// event-sourced store.
//
// The supervisor (M1.5, feas-H1 — the OpenClaw antidote) lives here in seed
// form: heartbeat timestamps + a liveness check. It becomes real when M1's
// adapter emits child-lifecycle events; the schema is here from M0 so it isn't
// bolted on later.

import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import type { SessionSpec } from "./policy.js";
import { MessageStore } from "./message-store.js";
import { SeenStore } from "./seen-store.js";
import { DeviceStore } from "./device-store.js";
import { PairingStore } from "./pairing.js";
import { CronStore } from "./cron-store.js";
import { UsageMeter } from "./usage.js";
import { WorkspaceStore } from "./workspace-store.js";

// P2 (identity plan): session state is TWO orthogonal fields, not one enum.
// lifecycle = does the session exist; runState = is a turn in progress NOW.
// No state transition ever mints or changes an id — "is the agent mid-run?"
// is runState==="running", a field flip, never a new id.
export type SessionLifecycle = "active" | "ended";
export type SessionRunState = "starting" | "idle" | "running" | "awaiting_input" | "hung";

/** LEGACY derived view (protocol v1 clients read `status`). Computed from
 *  (lifecycle, runState) — never stored as truth. */
export type SessionStatus = "starting" | "live" | "idle" | "exited" | "hung";

export function deriveStatus(lifecycle: SessionLifecycle, runState: SessionRunState): SessionStatus {
  if (lifecycle === "ended") return "exited";
  switch (runState) {
    case "starting": return "starting";
    case "running": return "live";
    case "hung": return "hung";
    default: return "idle"; // idle | awaiting_input
  }
}

export interface SessionRecord {
  id: string;
  principal: string;
  purpose: string;
  harness: string;
  harnessSessionId: string | null;
  cwd: string;
  authClass: string;
  origin: string;
  lifecycle: SessionLifecycle;
  runState: SessionRunState;
  /** Derived from (lifecycle, runState) for v1 clients — see deriveStatus. */
  status: SessionStatus;
  createdAt: number;
  lastActive: number;
  lastHeartbeat: number;
  /** Per-session model override (harness model id), chosen at create and
   *  re-applied on every resume. null = the harness default. */
  model: string | null;
  /** Per-session reasoning effort (SDK EffortLevel), chosen at create (client
   *  value ?? config default_effort) and re-applied on every resume. null =
   *  the harness default. */
  reasoningEffort: string | null;
  /** Per-session approvals override (M2): "auto" | "prompt". null = the
   *  daemon's global default applies. Mutable while running. */
  approvals: string | null;
  title: string | null;
  /** Archived flag (session.archive) — shared list metadata, daemon-backed so
   *  every client agrees. Never a behavior filter: an archived session still
   *  runs, resumes, and receives cron fires. */
  archived: boolean;
  /** Context-window occupancy after the LAST completed turn, stamped from the
   *  harness-pushed usage (normalize.ts wireUsage) — never queried back from
   *  the harness. null = unknown (never ran under a context-reporting harness,
   *  or the conversation was cleared). List metadata only: it decorates
   *  session.list so a cold-opened session shows context immediately; it is
   *  NEVER injected into a model prompt. */
  contextUsed: number | null;
  contextMax: number | null;
  /** Short (<1000 char) agent-maintained summary of what this session is
   *  about + open items — so the user can reopen a session and remember. */
  topic: string | null;
  summary: string | null;
  summaryUpdatedAt: number | null;
  /** Lineage marker (session.fork, T2 #3): the source session/message this
   *  one branched from. Metadata only — a fork is a full first-class session
   *  (hermes' _branched_from lesson: markers, never visibility filters). */
  branchedFromSessionId: string | null;
  branchedFromMessageId: string | null;
  /** Pending rewind (session.undo): the PRIVATE harness uuid of the message
   *  the next spawn must resume AT. Consumed (cleared) after the first turn
   *  result on a rewound spawn — by then the harness JSONL tip IS the new
   *  branch and plain resume is correct. Re-consuming before any new turn is
   *  idempotent, so restart-catchup is free. */
  harnessResumeAt: string | null;
  /** Highest seq EVER issued for this session — survives undo popping the
   *  highest-seq rows/events. Resume seeds the counter from the max of this
   *  and the two stores, so a popped seq is never reissued (P1: a gap is
   *  harmless, reuse is corruption). */
  seqHighWater: number;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS sessions (
  id               TEXT PRIMARY KEY,
  principal        TEXT NOT NULL,
  purpose          TEXT NOT NULL,
  harness          TEXT NOT NULL,
  harness_session_id TEXT,
  cwd              TEXT NOT NULL,
  auth_class       TEXT NOT NULL,
  origin           TEXT NOT NULL,
  -- status is VESTIGIAL (kept for pre-P2 dbs; NOT NULL forces a value on
  -- insert). Truth is (lifecycle, run_state); readers derive legacy status.
  status           TEXT NOT NULL,
  lifecycle        TEXT NOT NULL DEFAULT 'active',
  run_state        TEXT NOT NULL DEFAULT 'starting',
  created_at       INTEGER NOT NULL,
  last_active      INTEGER NOT NULL,
  last_heartbeat   INTEGER NOT NULL,
  model            TEXT,
  reasoning_effort TEXT,
  approvals        TEXT,
  title            TEXT,
  archived         INTEGER NOT NULL DEFAULT 0,
  -- Context occupancy after the last completed turn (NULL = unknown).
  context_used     INTEGER,
  context_max      INTEGER,
  topic            TEXT,
  summary          TEXT,
  summary_updated_at INTEGER,
  harness_resume_at TEXT,
  seq_high_water   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sessions_principal ON sessions(principal);
CREATE INDEX IF NOT EXISTS idx_sessions_purpose   ON sessions(purpose);
CREATE INDEX IF NOT EXISTS idx_sessions_active    ON sessions(last_active);
-- cwd-sensitive resume lookup (SDK stores sessions by encoded cwd).
CREATE INDEX IF NOT EXISTS idx_sessions_cwd       ON sessions(cwd);
-- Daemon-owned key/value marks (e.g. main_session_id — the singleton main
-- session designation is the DAEMON's, never a client's).
CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
`;

export class SessionManager {
  private db: DatabaseSync;
  /** The messages identity table (P1) — same db file, one writer. */
  readonly messages: MessageStore;
  /** Per-(device, session) read cursors (P4) — same db file. */
  readonly seen: SeenStore;
  /** The device roster (P3) — same db file, fed by the hello handshake. */
  readonly devices: DeviceStore;
  /** Device bearer tokens, hashed at rest (M2 pairing) — same db file. */
  readonly pairing: PairingStore;
  /** Scheduled prompts (T2 #1) — same db file; jobs survive restart. */
  readonly cron: CronStore;
  /** Daily usage rollups (T2 #8) — same db file; totals survive restart. */
  readonly usage: UsageMeter;
  /** Named folder workspaces (Paseo-style grouping) — same db file. */
  readonly workspaces: WorkspaceStore;

  constructor(dbPath: string, opts: { workspaceHome?: string } = {}) {
    if (dbPath !== ":memory:") mkdirSync(dirname(dbPath), { recursive: true });
    this.db = new DatabaseSync(dbPath);
    this.db.exec(SCHEMA);
    this.migrate();
    this.messages = new MessageStore(this.db);
    this.seen = new SeenStore(this.db);
    this.devices = new DeviceStore(this.db);
    this.pairing = new PairingStore(this.db);
    this.cron = new CronStore(this.db);
    this.usage = new UsageMeter(this.db);
    this.workspaces = new WorkspaceStore(this.db, opts.workspaceHome);
  }

  /** Additive migrations for dbs created before a column existed. */
  private migrate(): void {
    const addColumn = (col: string): boolean => {
      try {
        this.db.exec(`ALTER TABLE sessions ADD COLUMN ${col}`);
        return true;
      } catch (e) {
        // Only swallow the expected "column already exists" — a locked/corrupt
        // db must surface, not silently run with missing columns (R14).
        if (!/duplicate column/i.test((e as Error).message)) throw e;
        return false;
      }
    };
    for (const col of ["topic TEXT", "summary TEXT", "summary_updated_at INTEGER", "model TEXT", "reasoning_effort TEXT", "approvals TEXT", "branched_from_session_id TEXT", "branched_from_message_id TEXT", "harness_resume_at TEXT", "seq_high_water INTEGER NOT NULL DEFAULT 0", "archived INTEGER NOT NULL DEFAULT 0", "context_used INTEGER", "context_max INTEGER"]) addColumn(col);
    // P2: lifecycle + run_state. Backfill from the legacy status enum exactly
    // once (when the column was just added to a pre-P2 db).
    const added = addColumn("lifecycle TEXT NOT NULL DEFAULT 'active'");
    addColumn("run_state TEXT NOT NULL DEFAULT 'starting'");
    if (added) {
      this.db.exec(`
        UPDATE sessions SET
          lifecycle = CASE WHEN status = 'exited' THEN 'ended' ELSE 'active' END,
          run_state = CASE status
            WHEN 'live' THEN 'running'
            WHEN 'starting' THEN 'starting'
            WHEN 'hung' THEN 'hung'
            ELSE 'idle' END
      `);
    }
    // One-time data migration for the 2026-08-15 principal rename ("max" →
    // "owner"): pre-rename rows must keep matching principal-scoped queries
    // and the policy gate. Idempotent.
    this.db.exec(`UPDATE sessions SET principal = 'owner' WHERE principal = 'max'`);
  }

  /** In-memory instance for tests. */
  static inMemory(opts: { workspaceHome?: string } = {}): SessionManager {
    return new SessionManager(":memory:", opts);
  }

  create(id: string, spec: SessionSpec, harness: string, now: number, model?: string, approvals?: string, reasoningEffort?: string): SessionRecord {
    const rec: SessionRecord = {
      id,
      principal: spec.principal,
      purpose: spec.purpose,
      harness,
      harnessSessionId: null,
      cwd: spec.cwd,
      authClass: spec.authClass,
      origin: spec.origin,
      lifecycle: "active",
      runState: "starting",
      status: deriveStatus("active", "starting"),
      createdAt: now,
      lastActive: now,
      lastHeartbeat: now,
      model: model ?? null,
      reasoningEffort: reasoningEffort ?? null,
      approvals: approvals ?? null,
      title: null,
      archived: false,
      contextUsed: null,
      contextMax: null,
      branchedFromSessionId: null,
      branchedFromMessageId: null,
      topic: null,
      summary: null,
      summaryUpdatedAt: null,
      harnessResumeAt: null,
      seqHighWater: 0,
    };
    this.db
      .prepare(
        `INSERT INTO sessions
         (id, principal, purpose, harness, harness_session_id, cwd, auth_class,
          origin, status, lifecycle, run_state, created_at, last_active,
          last_heartbeat, model, reasoning_effort, approvals, title)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      )
      .run(
        rec.id, rec.principal, rec.purpose, rec.harness, rec.harnessSessionId,
        rec.cwd, rec.authClass, rec.origin, rec.status, rec.lifecycle,
        rec.runState, rec.createdAt, rec.lastActive, rec.lastHeartbeat,
        rec.model, rec.reasoningEffort, rec.approvals, rec.title,
      );
    return rec;
  }

  /** Bind the harness's own session id once the adapter learns it (the SDK
   *  assigns it; resume is keyed by (cwd, harnessSessionId)). */
  bindHarnessSession(id: string, harnessSessionId: string): void {
    this.db
      .prepare(`UPDATE sessions SET harness_session_id = ? WHERE id = ?`)
      .run(harnessSessionId, id);
  }

  /** Activity signal: bumps timestamps only — heartbeats do NOT drive state
   *  (P2: the pre-split enum let any heartbeat overwrite status). One
   *  exception, in the same statement: activity on a hung session proves it
   *  is alive again, so hung self-heals back to running. */
  heartbeat(id: string, now: number): void {
    this.db
      .prepare(
        `UPDATE sessions SET last_heartbeat = ?, last_active = ?,
           run_state = CASE WHEN run_state = 'hung' THEN 'running' ELSE run_state END
         WHERE id = ?`,
      )
      .run(now, now, id);
  }

  /** Flip the run-progress field. NEVER mints or changes an id (P2 invariant). */
  setRunState(id: string, runState: SessionRunState, now: number): void {
    this.db
      .prepare(`UPDATE sessions SET run_state = ?, last_active = ? WHERE id = ?`)
      .run(runState, now, id);
  }

  /** Terminal lifecycle. The row (and every message id in it) persists. */
  end(id: string, now: number): void {
    this.db
      .prepare(`UPDATE sessions SET lifecycle = 'ended', last_active = ? WHERE id = ?`)
      .run(now, id);
  }

  /** Resume an ended/orphaned session: lifecycle back to active, runState
   *  starting. SAME id — resume never re-INSERTs (H1) and never re-mints. */
  revive(id: string, now: number): void {
    this.db
      .prepare(`UPDATE sessions SET lifecycle = 'active', run_state = 'starting', last_active = ? WHERE id = ?`)
      .run(now, id);
  }

  /** Cap on a human title — a list label, not a document. */
  static readonly MAX_TITLE = 200;

  /** Set the per-session approvals mode (M2). Mutable while running — it
   *  gates the NEXT tool call. null clears back to the global default. */
  setApprovals(id: string, mode: string | null): void {
    this.db.prepare(`UPDATE sessions SET approvals = ? WHERE id = ?`).run(mode, id);
  }

  /** Set the per-session model (session.model). Applied on the next spawn —
   *  the router restarts an idle live child so "next spawn" is now. */
  setModel(id: string, model: string | null): void {
    this.db.prepare(`UPDATE sessions SET model = ? WHERE id = ?`).run(model, id);
  }

  /** Set the per-session reasoning effort (session.effort) — same lifecycle as
   *  [setModel]: applied on the next spawn, which the router makes "now" by
   *  restarting an idle live child. null clears back to the config default. */
  setEffort(id: string, effort: string | null): void {
    this.db.prepare(`UPDATE sessions SET reasoning_effort = ? WHERE id = ?`).run(effort, id);
  }

  /** Daemon-owned key/value marks (meta table). */
  getMeta(key: string): string | null {
    const row = this.db.prepare(`SELECT value FROM meta WHERE key = ?`).get(key) as
      | { value: string }
      | undefined;
    return row?.value ?? null;
  }

  setMeta(key: string, value: string): void {
    this.db
      .prepare(`INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value`)
      .run(key, value);
  }

  /** session.clear: reset the CONVERSATION in place — same id (state surgery,
   *  not a new identity), message rows + seen cursors deleted, harness state
   *  dropped (next spawn starts a fresh harness session), topic/summary reset,
   *  context occupancy reset to unknown (a stale pre-clear number would
   *  overstate a now-empty window; the next turn re-stamps it).
   *  Title/model/approvals persist. seqHighWater remembers the highest seq
   *  ever issued so cleared seqs are never reissued (P1: reuse is corruption).
   *  Lifecycle goes to ended/idle — exactly a reaped session's resumable
   *  shape. The transcript NDJSON lives outside SQLite; the caller removes it
   *  alongside (same division as [delete]). */
  clearConversation(id: string, seqHighWater: number, now: number): void {
    this.db.exec("BEGIN");
    try {
      this.messages.deleteSession(id);
      this.seen.deleteSession(id);
      this.db
        .prepare(
          `UPDATE sessions SET
             harness_session_id = NULL,
             harness_resume_at = NULL,
             topic = NULL,
             summary = NULL,
             summary_updated_at = NULL,
             context_used = NULL,
             context_max = NULL,
             lifecycle = 'ended',
             run_state = 'idle',
             seq_high_water = MAX(seq_high_water, ?),
             last_active = ?
           WHERE id = ?`,
        )
        .run(seqHighWater, now, id);
      this.db.exec("COMMIT");
    } catch (e) {
      this.db.exec("ROLLBACK");
      throw e;
    }
  }

  /** Set the human label. Metadata only — never touches ids or state. */
  /** Stamp the fork lineage (session.fork) — set once at fork time. */
  setBranchedFrom(id: string, sourceSessionId: string, sourceMessageId: string | null): void {
    this.db
      .prepare(`UPDATE sessions SET branched_from_session_id = ?, branched_from_message_id = ? WHERE id = ?`)
      .run(sourceSessionId, sourceMessageId, id);
  }

  setTitle(id: string, title: string): void {
    this.db
      .prepare(`UPDATE sessions SET title = ? WHERE id = ?`)
      .run(title.slice(0, SessionManager.MAX_TITLE), id);
  }

  /** Flip the archived flag (session.archive). Metadata only — never touches
   *  ids, lifecycle, or the live child. */
  setArchived(id: string, archived: boolean): void {
    this.db
      .prepare(`UPDATE sessions SET archived = ? WHERE id = ?`)
      .run(archived ? 1 : 0, id);
  }

  /** Stamp the context occupancy reported by the turn that just finished.
   *  Pure list metadata (cold-open context for clients) — it never feeds a
   *  prompt. A harness that reports no window (`max` undefined) stores NULL
   *  rather than a guess: percent is then underivable and clients render
   *  nothing, which is honest. */
  setContext(id: string, used: number, max?: number): void {
    this.db
      .prepare(`UPDATE sessions SET context_used = ?, context_max = ? WHERE id = ?`)
      .run(used, max ?? null, id);
  }

  /** In-memory tombstones backing [isDeleted] — the emit-after-delete guard
   *  runs on EVERY emitted event including deltas, so it must be a Set lookup,
   *  not a SELECT. Complete by construction: deletes only happen through
   *  [delete] in this process, and a daemon-minted id is never reused
   *  (session.create mints fresh; resume of an unknown id errors). */
  private readonly deletedIds = new Set<string>();

  /** True once [delete] has run for [id] in this process. The hot-path
   *  liveness check for the router's per-session emit closure. */
  isDeleted(id: string): boolean {
    return this.deletedIds.has(id);
  }

  /** session.delete cascade: the index row + every message identity row +
   *  every device's seen cursor, in one transaction. The transcript NDJSON
   *  lives outside SQLite — the caller (router) removes it alongside. */
  delete(id: string): void {
    this.db.exec("BEGIN");
    try {
      this.messages.deleteSession(id);
      this.seen.deleteSession(id);
      this.db.prepare(`DELETE FROM sessions WHERE id = ?`).run(id);
      this.db.exec("COMMIT");
    } catch (e) {
      this.db.exec("ROLLBACK");
      throw e;
    }
    this.deletedIds.add(id);
  }

  /** session.undo's atomic half: delete the popped message rows and stamp the
   *  pending rewind + seq high-water in ONE transaction (the design's crash
   *  ordering: transcript truncation happens BEFORE this, so a crash between
   *  the two leaves a display-only inconsistency that a re-undo repairs).
   *  Returns the popped message ids in seq order. */
  undoTurn(
    id: string,
    cutSeq: number,
    opts: { resumeAtUuid: string | null; clearHarnessSession: boolean; seqHighWater: number },
  ): string[] {
    const popped = (this.db
      .prepare(`SELECT message_id FROM messages WHERE session_id = ? AND seq > ? ORDER BY seq ASC`)
      .all(id, cutSeq) as { message_id: string }[]).map((r) => r.message_id);
    this.db.exec("BEGIN");
    try {
      this.db.prepare(`DELETE FROM messages WHERE session_id = ? AND seq > ?`).run(id, cutSeq);
      this.db
        .prepare(
          `UPDATE sessions SET
             harness_resume_at = ?,
             seq_high_water = MAX(seq_high_water, ?),
             harness_session_id = CASE WHEN ? THEN NULL ELSE harness_session_id END
           WHERE id = ?`,
        )
        .run(opts.resumeAtUuid, opts.seqHighWater, opts.clearHarnessSession ? 1 : 0, id);
      this.db.exec("COMMIT");
    } catch (e) {
      this.db.exec("ROLLBACK");
      throw e;
    }
    return popped;
  }

  /** The rewound spawn's first turn result consumed the pending rewind. */
  clearHarnessResumeAt(id: string): void {
    this.db.prepare(`UPDATE sessions SET harness_resume_at = NULL WHERE id = ?`).run(id);
  }

  /** Maximum length of a session summary — kept short by design (<1000 chars). */
  static readonly MAX_SUMMARY = 1000;

  /** Update the agent-maintained session summary. Throws if over the cap so
   *  the agent is told to shorten rather than silently truncating. */
  setSummary(id: string, s: { topic?: string; summary: string }, now: number): void {
    if (s.summary.length > SessionManager.MAX_SUMMARY) {
      throw new Error(`summary too long (${s.summary.length} > ${SessionManager.MAX_SUMMARY} chars) — keep it short`);
    }
    // COALESCE so a summary-only update (topic omitted) keeps the prior topic
    // rather than nulling it (S1). Cap topic too so it can't grow unbounded.
    const topic = s.topic === undefined ? undefined : s.topic.slice(0, 120);
    this.db
      .prepare(`UPDATE sessions SET topic = COALESCE(?, topic), summary = ?, summary_updated_at = ? WHERE id = ?`)
      .run(topic ?? null, s.summary, now, id);
  }

  /** Reconcile orphans on daemon startup: a fresh daemon has no live children,
   *  so any session still marked working from a previous process is dead.
   *  Prevents the supervisor from flagging last-run's sessions as silent
   *  failures. Returns the count reconciled. */
  markOrphansExited(now: number): number {
    const res = this.db
      .prepare(`UPDATE sessions SET lifecycle = 'ended', last_active = ? WHERE lifecycle = 'active'`)
      .run(now);
    // Messages still `streaming` from the dead process were interrupted —
    // record that on the row (ids persist; status tells the story) (P1).
    this.messages.closeAllOpen(now);
    return Number(res.changes ?? 0);
  }

  get(id: string): SessionRecord | undefined {
    const row = this.db.prepare(`SELECT * FROM sessions WHERE id = ?`).get(id) as
      | Record<string, unknown>
      | undefined;
    return row ? rowToRecord(row) : undefined;
  }

  list(filter: { principal?: string; purpose?: string } = {}): SessionRecord[] {
    const clauses: string[] = [];
    const args: string[] = [];
    if (filter.principal) { clauses.push("principal = ?"); args.push(filter.principal); }
    if (filter.purpose) { clauses.push("purpose = ?"); args.push(filter.purpose); }
    const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
    const rows = this.db
      .prepare(`SELECT * FROM sessions ${where} ORDER BY last_active DESC`)
      .all(...args) as Record<string, unknown>[];
    return rows.map(rowToRecord);
  }

  /**
   * M1.5 supervisor (feas-H1): a session whose heartbeat is older than
   * `timeoutMs` while a turn is IN PROGRESS is a *silent failure* candidate —
   * the exact OpenClaw wound. P2 fixes the reviewer's R4 by construction: the
   * timeout applies to running/starting, never idle — a session waiting for
   * input is not a failure, and a long tool run is `running` (heartbeats from
   * stream activity keep it alive).
   */
  findSilentlyDead(now: number, timeoutMs: number): SessionRecord[] {
    const rows = this.db
      .prepare(
        `SELECT * FROM sessions
         WHERE lifecycle = 'active'
           AND run_state IN ('starting','running')
           AND (? - last_heartbeat) > ?`,
      )
      .all(now, timeoutMs) as Record<string, unknown>[];
    return rows.map(rowToRecord);
  }

  close(): void {
    this.db.close();
  }
}

function rowToRecord(row: Record<string, unknown>): SessionRecord {
  const lifecycle = row.lifecycle as SessionLifecycle;
  const runState = row.run_state as SessionRunState;
  return {
    id: row.id as string,
    principal: row.principal as string,
    purpose: row.purpose as string,
    harness: row.harness as string,
    harnessSessionId: (row.harness_session_id as string | null) ?? null,
    cwd: row.cwd as string,
    authClass: row.auth_class as string,
    origin: row.origin as string,
    lifecycle,
    runState,
    status: deriveStatus(lifecycle, runState),
    createdAt: row.created_at as number,
    lastActive: row.last_active as number,
    lastHeartbeat: row.last_heartbeat as number,
    model: (row.model as string | null) ?? null,
    reasoningEffort: (row.reasoning_effort as string | null) ?? null,
    approvals: (row.approvals as string | null) ?? null,
    title: (row.title as string | null) ?? null,
    archived: (row.archived as number | null) === 1,
    contextUsed: (row.context_used as number | null) ?? null,
    contextMax: (row.context_max as number | null) ?? null,
    topic: (row.topic as string | null) ?? null,
    summary: (row.summary as string | null) ?? null,
    summaryUpdatedAt: (row.summary_updated_at as number | null) ?? null,
    branchedFromSessionId: (row.branched_from_session_id as string | null) ?? null,
    branchedFromMessageId: (row.branched_from_message_id as string | null) ?? null,
    harnessResumeAt: (row.harness_resume_at as string | null) ?? null,
    seqHighWater: (row.seq_high_water as number | null) ?? 0,
  };
}

export function defaultDbPath(stateDir: string): string {
  return join(stateDir, "sessions.db");
}
