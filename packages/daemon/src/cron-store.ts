// cron-store.ts — persisted scheduled-prompt jobs (parity-map T2 #1).
//
// Same SQLite file as the session index (one db, one writer — the
// MessageStore/SeenStore pattern). Persistence is the point: a job must
// survive a daemon restart, and the persisted next_run_at is what the
// scheduler's restart-catchup pass reads to fire slots missed while down.
// A restart silently dropping jobs is the silent-failure class this whole
// feature exists to avoid.

import type { DatabaseSync } from "node:sqlite";
import type { CronScheduleSpec } from "./cron-schedule.js";

export type CronRunStatus = "ok" | "error";

export interface CronJobRecord {
  id: string;
  name: string | null;
  /** The target session the job submits into (origin: cron). */
  sessionId: string;
  prompt: string;
  schedule: CronScheduleSpec;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
  /** The next moment this job fires (stagger included), or null when it never
   *  will (disabled one-shot, unresolvable schedule). An ENABLED job must
   *  always carry a next_run_at — the scheduler enforces that invariant. */
  nextRunAt: number | null;
  lastRunAt: number | null;
  lastStatus: CronRunStatus | null;
  lastError: string | null;
}

export interface CronJobPatch {
  name?: string | null;
  sessionId?: string;
  prompt?: string;
  schedule?: CronScheduleSpec;
  enabled?: boolean;
  nextRunAt?: number | null;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS cron_jobs (
  id           TEXT PRIMARY KEY,
  name         TEXT,
  session_id   TEXT NOT NULL,
  prompt       TEXT NOT NULL,
  schedule     TEXT NOT NULL,
  enabled      INTEGER NOT NULL DEFAULT 1,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,
  next_run_at  INTEGER,
  last_run_at  INTEGER,
  last_status  TEXT,
  last_error   TEXT
);
CREATE INDEX IF NOT EXISTS idx_cron_next ON cron_jobs(enabled, next_run_at);
`;

export class CronStore {
  constructor(private db: DatabaseSync) {
    db.exec(SCHEMA);
  }

  create(rec: CronJobRecord): void {
    this.db
      .prepare(
        `INSERT INTO cron_jobs
         (id, name, session_id, prompt, schedule, enabled, created_at,
          updated_at, next_run_at, last_run_at, last_status, last_error)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
      )
      .run(
        rec.id, rec.name, rec.sessionId, rec.prompt, JSON.stringify(rec.schedule),
        rec.enabled ? 1 : 0, rec.createdAt, rec.updatedAt, rec.nextRunAt,
        rec.lastRunAt, rec.lastStatus, rec.lastError,
      );
  }

  get(id: string): CronJobRecord | undefined {
    const row = this.db.prepare(`SELECT * FROM cron_jobs WHERE id = ?`).get(id) as
      | Record<string, unknown>
      | undefined;
    return row ? rowToJob(row) : undefined;
  }

  /** ALL jobs, disabled included — a list that silently skips jobs is the
   *  OpenClaw #16156 bug class. Stable creation order. */
  list(): CronJobRecord[] {
    const rows = this.db
      .prepare(`SELECT * FROM cron_jobs ORDER BY created_at, id`)
      .all() as Record<string, unknown>[];
    return rows.map(rowToJob);
  }

  /** Apply a patch; returns the updated record or undefined when unknown. */
  update(id: string, patch: CronJobPatch, now: number): CronJobRecord | undefined {
    const cur = this.get(id);
    if (!cur) return undefined;
    const next: CronJobRecord = {
      ...cur,
      ...(patch.name !== undefined ? { name: patch.name } : {}),
      ...(patch.sessionId !== undefined ? { sessionId: patch.sessionId } : {}),
      ...(patch.prompt !== undefined ? { prompt: patch.prompt } : {}),
      ...(patch.schedule !== undefined ? { schedule: patch.schedule } : {}),
      ...(patch.enabled !== undefined ? { enabled: patch.enabled } : {}),
      ...(patch.nextRunAt !== undefined ? { nextRunAt: patch.nextRunAt } : {}),
      updatedAt: now,
    };
    this.db
      .prepare(
        `UPDATE cron_jobs SET name=?, session_id=?, prompt=?, schedule=?,
           enabled=?, updated_at=?, next_run_at=? WHERE id=?`,
      )
      .run(
        next.name, next.sessionId, next.prompt, JSON.stringify(next.schedule),
        next.enabled ? 1 : 0, next.updatedAt, next.nextRunAt, id,
      );
    return next;
  }

  /** Record a fire outcome. Never touches next_run_at — advancing the
   *  schedule and recording the run are separate writes by design (the
   *  scheduler advances BEFORE firing, so a crash mid-run can never re-fire
   *  the same slot). */
  recordRun(id: string, ranAt: number, status: CronRunStatus, error?: string): void {
    this.db
      .prepare(`UPDATE cron_jobs SET last_run_at=?, last_status=?, last_error=? WHERE id=?`)
      .run(ranAt, status, error ?? null, id);
  }

  /** Disable with a recorded reason (unresolvable schedule, #66019 class) —
   *  a job that can never fire again must say so, not sit enabled forever. */
  disableWithError(id: string, now: number, error: string): void {
    this.db
      .prepare(`UPDATE cron_jobs SET enabled=0, next_run_at=NULL, last_error=?, updated_at=? WHERE id=?`)
      .run(error, now, id);
  }

  delete(id: string): boolean {
    const res = this.db.prepare(`DELETE FROM cron_jobs WHERE id = ?`).run(id);
    return Number(res.changes ?? 0) > 0;
  }

  /** Enabled jobs whose fire time has arrived. */
  due(now: number): CronJobRecord[] {
    const rows = this.db
      .prepare(
        `SELECT * FROM cron_jobs
         WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ?
         ORDER BY next_run_at, id`,
      )
      .all(now) as Record<string, unknown>[];
    return rows.map(rowToJob);
  }

  /** The earliest fire time among enabled jobs — what the timer arms to. */
  earliestNextRun(): number | null {
    const row = this.db
      .prepare(`SELECT MIN(next_run_at) AS m FROM cron_jobs WHERE enabled = 1 AND next_run_at IS NOT NULL`)
      .get() as { m: number | null } | undefined;
    return row?.m ?? null;
  }
}

function rowToJob(row: Record<string, unknown>): CronJobRecord {
  return {
    id: row.id as string,
    name: (row.name as string | null) ?? null,
    sessionId: row.session_id as string,
    prompt: row.prompt as string,
    schedule: JSON.parse(row.schedule as string) as CronScheduleSpec,
    enabled: Number(row.enabled) === 1,
    createdAt: row.created_at as number,
    updatedAt: row.updated_at as number,
    nextRunAt: (row.next_run_at as number | null) ?? null,
    lastRunAt: (row.last_run_at as number | null) ?? null,
    lastStatus: (row.last_status as CronRunStatus | null) ?? null,
    lastError: (row.last_error as string | null) ?? null,
  };
}
