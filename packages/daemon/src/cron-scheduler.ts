// cron-scheduler.ts — the thin marmalade-native scheduler over the ported
// schedule math (parity-map T2 #1). Deliberately NOT a port of OpenClaw's
// service.ts — no isolated-agent machinery, no delivery pipeline. A job fires
// prompt.submit into its target session (origin: cron) and v1 delivery is the
// normal session path.
//
// The bug classes this file must hold (named after the OpenClaw production
// regressions whose tests are the spec):
//   * restart-catchup — persisted next_run_at <= now at start() fires ONCE.
//   * duplicate-timer — a single armed timer, re-armed idempotently; per-job
//     single-flight so a slow fire can't overlap itself.
//   * daily-skip (#17852) — the schedule advances exactly when a slot fires,
//     never as a maintenance side effect.
//   * at-reschedule (#19676) — handled in the router: schedule updates
//     recompute next_run_at.
//   * unresolved next-run (#66019) — an enabled job that can't compute a next
//     fire time is disabled WITH a recorded error, never left silently dead.
//   * one-shot "at" jobs disable after firing (still listed, re-enable by
//     giving them a future at_ms).
//
// Crash semantics are at-most-once per slot: next_run_at advances BEFORE the
// fire, so a daemon that dies mid-fire misses that slot instead of replaying
// it on restart. For chat prompts a duplicate is worse than a miss.

import type { CronJobRecord, CronStore } from "./cron-store.js";
import { computeNextFireAt } from "./cron-schedule.js";

/** Timer sleeps are capped: cheap re-arm beats trusting one long setTimeout
 *  across suspend/clock-step (the wake just re-reads the store and re-arms). */
const MAX_ARM_MS = 60_000;

export interface CronSchedulerDeps {
  store: CronStore;
  now(): number;
  log?: (line: string) => void;
  /** Secondary alert sink (ntfy, hardening #2) — failed fires only, never
   *  successes. Absent = failures stay log-only. */
  alert?: (title: string, message: string) => void;
  /** false = no real timers (tests drive tick()/start() manually). */
  timers?: boolean;
}

export class CronScheduler {
  /** The fire seam — wired by index.ts to the router's internal cron submit.
   *  Rejection = the run failed; recorded on the job as last_status=error. */
  onFire?: (job: CronJobRecord) => Promise<void>;

  private running = new Set<string>();
  private timer: NodeJS.Timeout | null = null;
  private stopped = false;
  private log: (line: string) => void;

  constructor(private deps: CronSchedulerDeps) {
    this.log = deps.log ?? (() => {});
  }

  /** Boot: restart-catchup (fire everything missed while down, sequentially),
   *  then arm the timer for the earliest upcoming job. */
  async start(): Promise<void> {
    this.stopped = false;
    await this.tick();
    const missed = this.deps.store.list().filter((j) => j.enabled && j.nextRunAt === null);
    // Defensive: an enabled job with no next_run_at can't ever fire — repair
    // or disable it loudly rather than carrying it as a silent zombie.
    for (const job of missed) this.rearmJob(job);
    this.arm();
  }

  stop(): void {
    this.stopped = true;
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
  }

  /** Fire everything due, then re-arm. The single entry point for the timer
   *  AND for tests (which call it directly with an injected clock). */
  async tick(): Promise<void> {
    if (this.stopped) return;
    // Sequential fire: prompt.submit acks fast (queue-and-return), and firing
    // one at a time keeps a restart with many overdue jobs from spawning a
    // harness-child herd in one instant.
    for (const job of this.deps.store.due(this.deps.now())) {
      await this.fireJob(job, { advance: true });
    }
    this.arm();
  }

  /** cron.run_now: fire immediately WITHOUT advancing the schedule — a manual
   *  run is out-of-band; the next scheduled slot stays where it was. */
  async runNow(job: CronJobRecord): Promise<boolean> {
    return this.fireJob(job, { advance: false });
  }

  /** Recompute next_run_at after a create/update/enable — and re-arm. */
  jobChanged(): void {
    this.arm();
  }

  /** True when the job fired; false when skipped (single-flight overlap). */
  private async fireJob(job: CronJobRecord, opts: { advance: boolean }): Promise<boolean> {
    if (this.running.has(job.id)) {
      this.log(`[cron] job ${job.id} still running — skipped (single-flight)`);
      return false;
    }
    this.running.add(job.id);
    const firedAt = this.deps.now();
    try {
      // A schedule that ERRORS at compute time disables the job AND skips the
      // fire — a job we can't reason about shouldn't submit prompts. (A
      // one-shot completing is different: it disables but its due slot fires.)
      if (opts.advance && !this.advance(job, firedAt)) return false;
      try {
        if (!this.onFire) throw new Error("cron scheduler has no fire target wired");
        await this.onFire(job);
        this.deps.store.recordRun(job.id, firedAt, "ok");
        this.log(`[cron] fired ${job.id}${job.name ? ` (${job.name})` : ""} → session ${job.sessionId}`);
      } catch (e) {
        // A failed fire is recorded, visible in cron.list, and the schedule
        // has already advanced — no tight retry loop, no eternal silence.
        this.deps.store.recordRun(job.id, firedAt, "error", (e as Error).message);
        this.log(`[cron] FIRE FAILED ${job.id}${job.name ? ` (${job.name})` : ""}: ${(e as Error).message}`);
        // Budget refusals carry their own once-per-breach alert (router
        // latch, marked alerted) — skip them here so a breach doesn't also
        // spam one alert per skipped fire.
        if ((e as { alerted?: boolean }).alerted !== true) {
          this.deps.alert?.(
            `Marmalade cron fire failed${job.name ? `: ${job.name}` : ""}`,
            `job ${job.id} → session ${job.sessionId}: ${(e as Error).message}`,
          );
        }
      }
      return true;
    } finally {
      this.running.delete(job.id);
    }
  }

  /** Advance the schedule past the slot that just fired (BEFORE the fire —
   *  at-most-once across a crash). One-shots disable; a recurring schedule
   *  that can't resolve a next slot disables with error (#66019). Returns
   *  false ONLY when the schedule errored — the caller then skips the fire. */
  private advance(job: CronJobRecord, now: number): boolean {
    let next: number | undefined;
    try {
      next = computeNextFireAt(job.schedule, job.id, now);
    } catch (e) {
      this.deps.store.disableWithError(job.id, now, `schedule error: ${(e as Error).message}`);
      this.log(`[cron] job ${job.id} DISABLED — schedule error: ${(e as Error).message}`);
      return false;
    }
    if (next === undefined) {
      if (job.schedule.kind === "at") {
        // One-shot complete: disabled but kept — the run record is the receipt.
        this.deps.store.update(job.id, { enabled: false, nextRunAt: null }, now);
      } else {
        this.deps.store.disableWithError(job.id, now, "schedule has no future occurrence — job disabled");
        this.log(`[cron] job ${job.id} DISABLED — schedule has no future occurrence`);
      }
      return true;
    }
    this.deps.store.update(job.id, { nextRunAt: next }, now);
    return true;
  }

  /** Repair an enabled job with no next_run_at (start()-time defense). */
  private rearmJob(job: CronJobRecord): void {
    const now = this.deps.now();
    let next: number | undefined;
    try {
      next = computeNextFireAt(job.schedule, job.id, now);
    } catch {
      next = undefined;
    }
    if (next === undefined) {
      this.deps.store.disableWithError(job.id, now, "schedule has no future occurrence — job disabled");
      this.log(`[cron] job ${job.id} DISABLED at startup — unresolvable schedule`);
    } else {
      this.deps.store.update(job.id, { nextRunAt: next }, now);
      this.log(`[cron] job ${job.id} re-armed at startup (next ${new Date(next).toISOString()})`);
    }
  }

  /** (Re-)arm the single timer for the earliest enabled job. Idempotent —
   *  always clears the previous timer first (duplicate-timer class). */
  private arm(): void {
    if (this.deps.timers === false || this.stopped) return;
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    const earliest = this.deps.store.earliestNextRun();
    if (earliest === null) return; // nothing enabled — jobChanged() re-arms
    const delay = Math.min(Math.max(0, earliest - this.deps.now()), MAX_ARM_MS);
    this.timer = setTimeout(() => {
      this.timer = null;
      this.tick().catch((e) => this.log(`[cron] tick failed: ${(e as Error).message}`));
    }, delay);
    this.timer.unref?.();
  }
}
