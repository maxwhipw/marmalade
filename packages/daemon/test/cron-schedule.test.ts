// cron-schedule.test.ts — the schedule-math bug classes, ported from the
// OpenClaw regression family (schedule.test.ts, #22895, stagger.test.ts).
// The core invariant everywhere: NEVER return a past timestamp.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  computeNextRunAt, computeNextFireAt, validateSchedule,
  staggerOffsetMs, staggerWindowMs, isRecurringTopOfHourCronExpr,
  DEFAULT_TOP_OF_HOUR_STAGGER_MS,
} from "../dist/cron-schedule.js";
import type { CronScheduleSpec } from "../dist/cron-schedule.js";

const T0 = Date.parse("2026-07-17T10:30:00.000Z");

test("cron: next run is strictly in the future", () => {
  const s: CronScheduleSpec = { kind: "cron", expr: "0 15 * * *", tz: "UTC" };
  const next = computeNextRunAt(s, T0);
  assert.equal(next, Date.parse("2026-07-17T15:00:00.000Z"));
  // Evaluated exactly AT the slot → the NEXT slot, never now/past.
  const atSlot = computeNextRunAt(s, next!);
  assert.equal(atSlot, Date.parse("2026-07-18T15:00:00.000Z"));
});

test("cron: daily job advances exactly one day after firing (no 48h skip)", () => {
  // The #17852 shape: fire at 03:00, recompute from just after — must be
  // TOMORROW 03:00, not the day after.
  const s: CronScheduleSpec = { kind: "cron", expr: "0 3 * * *", tz: "UTC" };
  const threeAm = Date.parse("2026-07-18T03:00:00.000Z");
  assert.equal(computeNextRunAt(s, threeAm + 1000), threeAm + 24 * 3_600_000);
});

test("cron: timezone-aware evaluation", () => {
  // 09:00 in New York on 2026-07-17 (EDT, UTC-4) = 13:00Z.
  const s: CronScheduleSpec = { kind: "cron", expr: "0 9 * * *", tz: "America/New_York" };
  assert.equal(computeNextRunAt(s, T0), Date.parse("2026-07-17T13:00:00.000Z"));
  // Asia/Shanghai (the croner year-rollback zone) still yields a future run.
  const sh: CronScheduleSpec = { kind: "cron", expr: "30 6 * * *", tz: "Asia/Shanghai" };
  const next = computeNextRunAt(sh, T0)!;
  assert.ok(next > T0, `Shanghai next (${next}) must be after now (${T0})`);
});

test("cron: 6-field (seconds) expressions are accepted", () => {
  const s: CronScheduleSpec = { kind: "cron", expr: "30 0 * * * *", tz: "UTC" };
  assert.equal(computeNextRunAt(s, T0), Date.parse("2026-07-17T11:00:30.000Z"));
});

test("every: steps from the anchor, always at least one step ahead (#22895)", () => {
  const anchor = T0 - 90_000; // 90s ago, every 60s → slots at -30s, +30s...
  const s: CronScheduleSpec = { kind: "every", every_ms: 60_000, anchor_ms: anchor };
  assert.equal(computeNextRunAt(s, T0), anchor + 120_000); // 30s from now
  // Exactly on a slot boundary → the NEXT slot, never now.
  assert.equal(computeNextRunAt(s, anchor + 60_000), anchor + 120_000);
  // Far-overdue every job: still lands in the future, aligned to anchor.
  const overdue = computeNextRunAt(s, anchor + 7 * 24 * 3_600_000 + 1234)!;
  assert.ok(overdue > anchor + 7 * 24 * 3_600_000 + 1234);
  assert.equal((overdue - anchor) % 60_000, 0);
});

test("every: now before the anchor fires at the anchor", () => {
  const s: CronScheduleSpec = { kind: "every", every_ms: 60_000, anchor_ms: T0 + 500_000 };
  assert.equal(computeNextRunAt(s, T0), T0 + 500_000);
});

test("at: future fires once, past never resolves", () => {
  assert.equal(computeNextRunAt({ kind: "at", at_ms: T0 + 1000 }, T0), T0 + 1000);
  assert.equal(computeNextRunAt({ kind: "at", at_ms: T0 - 1 }, T0), undefined);
  assert.equal(computeNextRunAt({ kind: "at", at_ms: T0 }, T0), undefined);
});

test("validateSchedule: rejects garbage exprs and past at_ms, accepts good ones", () => {
  assert.equal(validateSchedule({ kind: "cron", expr: "0 3 * * *", tz: "UTC" }, T0), undefined);
  assert.match(validateSchedule({ kind: "cron", expr: "not a cron" }, T0)!, /invalid cron/);
  assert.match(validateSchedule({ kind: "at", at_ms: T0 - 1 }, T0)!, /past/);
  assert.equal(validateSchedule({ kind: "every", every_ms: 60_000 }, T0), undefined);
  // Bad IANA timezone must be a create-time error, not a fire-time surprise.
  assert.ok(validateSchedule({ kind: "cron", expr: "0 3 * * *", tz: "Mars/Olympus" }, T0));
});

test("stagger: top-of-hour recurring exprs get the default window, others none", () => {
  assert.ok(isRecurringTopOfHourCronExpr("0 * * * *"));
  assert.ok(isRecurringTopOfHourCronExpr("0 0 * * * *")); // 6-field
  assert.ok(!isRecurringTopOfHourCronExpr("15 * * * *"));
  assert.ok(!isRecurringTopOfHourCronExpr("0 9 * * *")); // fixed hour — no herd
  assert.equal(staggerWindowMs({ kind: "cron", expr: "0 * * * *" }), DEFAULT_TOP_OF_HOUR_STAGGER_MS);
  assert.equal(staggerWindowMs({ kind: "cron", expr: "0 * * * *", stagger_ms: 0 }), 0);
  assert.equal(staggerWindowMs({ kind: "cron", expr: "0 9 * * *" }), 0);
  assert.equal(staggerWindowMs({ kind: "every", every_ms: 60_000 }), 0);
});

test("stagger: offset is deterministic per job and inside the window", () => {
  const a = staggerOffsetMs("cj_alpha", DEFAULT_TOP_OF_HOUR_STAGGER_MS);
  assert.equal(a, staggerOffsetMs("cj_alpha", DEFAULT_TOP_OF_HOUR_STAGGER_MS));
  assert.ok(a >= 0 && a < DEFAULT_TOP_OF_HOUR_STAGGER_MS);
  assert.equal(staggerOffsetMs("cj_alpha", 0), 0);
});

test("stagger: fire time = slot + offset, and recomputing after the staggered fire lands on the NEXT slot", () => {
  const s: CronScheduleSpec = { kind: "cron", expr: "0 * * * *", tz: "UTC" };
  const jobId = "cj_test";
  const off = staggerOffsetMs(jobId, DEFAULT_TOP_OF_HOUR_STAGGER_MS);
  const slot = Date.parse("2026-07-17T11:00:00.000Z");
  assert.equal(computeNextFireAt(s, jobId, T0), slot + off);
  // Recompute from the moment the staggered fire happened: next hour's slot
  // (+ the same offset) — the stagger can't make an hourly job skip an hour.
  assert.equal(computeNextFireAt(s, jobId, slot + off), slot + 3_600_000 + off);
});
