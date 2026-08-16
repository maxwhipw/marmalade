// cron-scheduler.test.ts — the scheduler bug classes, named after the
// OpenClaw production regressions that are this feature's spec:
// restart-catchup, duplicate-timer/single-flight, one-shot-disables,
// unresolved-next-run (#66019), daily-skip (#17852), error-doesn't-loop.

import { test } from "node:test";
import assert from "node:assert/strict";
import { SessionManager } from "../dist/session-manager.js";
import { CronScheduler } from "../dist/cron-scheduler.js";
import type { CronJobRecord } from "../dist/cron-store.js";

const T0 = Date.parse("2026-07-17T10:30:00.000Z");

function job(id: string, over: Partial<CronJobRecord> = {}): CronJobRecord {
  return {
    id, name: null, sessionId: "s_1", prompt: `prompt-${id}`,
    schedule: { kind: "every", every_ms: 60_000, anchor_ms: T0 - 3_600_000 },
    enabled: true, createdAt: T0 - 3_600_000, updatedAt: T0 - 3_600_000,
    nextRunAt: T0 - 60_000, lastRunAt: null, lastStatus: null, lastError: null,
    ...over,
  };
}

function harness(nowRef: { t: number }) {
  const sessions = SessionManager.inMemory();
  const fired: CronJobRecord[] = [];
  const s = new CronScheduler({ store: sessions.cron, now: () => nowRef.t, timers: false });
  s.onFire = async (j) => { fired.push(j); };
  return { sessions, fired, s, cleanup: () => sessions.close() };
}

test("restart-catchup: overdue persisted jobs fire ONCE on start, future ones don't", async () => {
  const now = { t: T0 };
  const h = harness(now);
  try {
    h.sessions.cron.create(job("overdue", { nextRunAt: T0 - 7_200_000 })); // missed while down
    h.sessions.cron.create(job("future", { nextRunAt: T0 + 60_000 }));
    await h.s.start();
    assert.deepEqual(h.fired.map((j) => j.id), ["overdue"], "exactly the missed job fires");
    const j = h.sessions.cron.get("overdue")!;
    assert.equal(j.lastStatus, "ok");
    assert.equal(j.lastRunAt, T0);
    assert.ok(j.nextRunAt! > T0, "next run recomputed into the future");
    // A second tick at the same instant must not re-fire (the slot advanced).
    await h.s.tick();
    assert.equal(h.fired.length, 1);
  } finally { h.cleanup(); }
});

test("restart-catchup: an enabled job with NULL next_run_at is repaired at start, never left a zombie", async () => {
  const now = { t: T0 };
  const h = harness(now);
  try {
    h.sessions.cron.create(job("zombie", { nextRunAt: null }));
    await h.s.start();
    const j = h.sessions.cron.get("zombie")!;
    assert.ok(j.enabled && j.nextRunAt! > T0, "re-armed with a future run");
  } finally { h.cleanup(); }
});

test("tick fires due jobs and advances BEFORE firing (at-most-once)", async () => {
  const now = { t: T0 };
  const h = harness(now);
  try {
    h.sessions.cron.create(job("a"));
    // Observe the persisted next_run_at from INSIDE the fire callback: it must
    // already be advanced — a crash mid-fire re-runs nothing on restart.
    let nextDuringFire: number | null = -1;
    h.s.onFire = async (j) => { nextDuringFire = h.sessions.cron.get(j.id)!.nextRunAt; };
    await h.s.tick();
    assert.ok(nextDuringFire !== null && nextDuringFire > T0, "schedule advanced before the fire ran");
  } finally { h.cleanup(); }
});

test("single-flight: a job mid-run is skipped, not double-fired", async () => {
  const now = { t: T0 };
  const h = harness(now);
  try {
    h.sessions.cron.create(job("slow"));
    let concurrent = 0, maxConcurrent = 0, releases: Array<() => void> = [];
    h.s.onFire = async () => {
      concurrent++; maxConcurrent = Math.max(maxConcurrent, concurrent);
      await new Promise<void>((r) => releases.push(r));
      concurrent--;
    };
    const first = h.s.tick();
    // While the fire is parked, run-now must refuse to overlap it.
    const overlapped = await h.s.runNow(h.sessions.cron.get("slow")!);
    assert.equal(overlapped, false, "run_now skipped while the job is mid-run");
    releases.forEach((r) => r());
    await first;
    assert.equal(maxConcurrent, 1);
  } finally { h.cleanup(); }
});

test("one-shot 'at' job fires then disables itself (still listed)", async () => {
  const now = { t: T0 };
  const h = harness(now);
  try {
    h.sessions.cron.create(job("once", {
      schedule: { kind: "at", at_ms: T0 - 1000 }, nextRunAt: T0 - 1000,
    }));
    await h.s.tick();
    assert.equal(h.fired.length, 1);
    const j = h.sessions.cron.get("once")!;
    assert.equal(j.enabled, false);
    assert.equal(j.nextRunAt, null);
    assert.equal(j.lastStatus, "ok");
    // Later ticks never fire it again.
    now.t += 3_600_000;
    await h.s.tick();
    assert.equal(h.fired.length, 1);
  } finally { h.cleanup(); }
});

test("unresolved next-run (#66019): job disables WITH an error, never spins", async () => {
  const now = { t: T0 };
  const h = harness(now);
  try {
    // A cron expr whose next occurrence can't resolve — simulate via an 'every'
    // record whose stored schedule was corrupted to a past one-shot.
    h.sessions.cron.create(job("dead", {
      schedule: { kind: "at", at_ms: T0 - 5000 }, nextRunAt: T0 - 1000,
    }));
    // Corrupt-schedule variant: garbage expr that throws at compute time.
    h.sessions.cron.create(job("garbage", {
      schedule: { kind: "cron", expr: "totally bogus" } as never,
      nextRunAt: T0 - 1000, createdAt: T0 - 3_599_000,
    }));
    await h.s.tick();
    const g = h.sessions.cron.get("garbage")!;
    assert.equal(g.enabled, false, "unresolvable job auto-disables");
    assert.match(g.lastError!, /schedule error/);
    // And it did NOT fire (advance failed before the fire).
    assert.ok(!h.fired.some((j) => j.id === "garbage"));
  } finally { h.cleanup(); }
});

test("a failing fire records error, schedule still advances (no tight retry loop)", async () => {
  const now = { t: T0 };
  const h = harness(now);
  try {
    h.sessions.cron.create(job("failing"));
    h.s.onFire = async () => { throw new Error("session gone"); };
    await h.s.tick();
    const j = h.sessions.cron.get("failing")!;
    assert.equal(j.lastStatus, "error");
    assert.equal(j.lastError, "session gone");
    assert.ok(j.nextRunAt! > T0, "advanced despite the failure");
    await h.s.tick(); // same instant — nothing due, no hammering
    assert.equal(h.sessions.cron.get("failing")!.lastRunAt, T0);
  } finally { h.cleanup(); }
});

test("run_now fires out-of-band and does NOT move next_run_at", async () => {
  const now = { t: T0 };
  const h = harness(now);
  try {
    h.sessions.cron.create(job("manual", { nextRunAt: T0 + 30_000 }));
    const fired = await h.s.runNow(h.sessions.cron.get("manual")!);
    assert.equal(fired, true);
    assert.equal(h.fired.length, 1);
    const j = h.sessions.cron.get("manual")!;
    assert.equal(j.nextRunAt, T0 + 30_000, "scheduled slot unmoved");
    assert.equal(j.lastStatus, "ok");
  } finally { h.cleanup(); }
});

test("daily-skip (#17852 class): a slot that fires advances exactly one period", async () => {
  const now = { t: Date.parse("2026-07-17T03:00:00.500Z") };
  const h = harness(now);
  try {
    const slot = Date.parse("2026-07-17T03:00:00.000Z");
    h.sessions.cron.create(job("daily", {
      schedule: { kind: "cron", expr: "0 3 * * *", tz: "UTC" }, nextRunAt: slot,
    }));
    await h.s.tick();
    assert.equal(h.fired.length, 1);
    assert.equal(h.sessions.cron.get("daily")!.nextRunAt, slot + 24 * 3_600_000,
      "next run is TOMORROW 03:00 — one day, not two");
  } finally { h.cleanup(); }
});

test("duplicate timers: real-timer mode arms idempotently and fires once per slot", async () => {
  const sessions = SessionManager.inMemory();
  try {
    const fired: string[] = [];
    const s = new CronScheduler({ store: sessions.cron, now: () => Date.now() });
    s.onFire = async (j) => { fired.push(j.id); };
    const t = Date.now();
    sessions.cron.create(job("rt", {
      schedule: { kind: "every", every_ms: 3_600_000, anchor_ms: t },
      nextRunAt: t + 40,
    }));
    await s.start();
    // Hammer jobChanged — the arm path must collapse to ONE live timer.
    for (let i = 0; i < 20; i++) s.jobChanged();
    await new Promise((r) => setTimeout(r, 150));
    s.stop();
    assert.equal(fired.length, 1, `expected exactly one fire, got ${fired.length}`);
  } finally { sessions.close(); }
});
