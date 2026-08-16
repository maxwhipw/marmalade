// email-digest-schedule.test.ts — pins the two live email-digest cron entries
// (cj email-digest-am / email-digest-pm, 2026-08-11): 07:00 and 17:45
// America/Los_Angeles, daily. The jobs themselves are daemon state, not code;
// what these tests hold is that the schedule math keeps firing them at the
// configured wall-clock times — exactly (no stagger), across DST.

import { test } from "node:test";
import assert from "node:assert/strict";
import { computeNextRunAt, computeNextFireAt, validateSchedule, staggerWindowMs } from "../dist/cron-schedule.js";
import type { CronScheduleSpec } from "../dist/cron-schedule.js";

const AM: CronScheduleSpec = { kind: "cron", expr: "0 7 * * *", tz: "America/Los_Angeles" };
const PM: CronScheduleSpec = { kind: "cron", expr: "45 17 * * *", tz: "America/Los_Angeles" };

test("email digest: both schedules validate", () => {
  const now = Date.parse("2026-08-11T19:00:00.000Z");
  assert.equal(validateSchedule(AM, now), undefined);
  assert.equal(validateSchedule(PM, now), undefined);
});

test("email digest: fires at 07:00 / 17:45 Pacific DAYLIGHT time (UTC-7)", () => {
  const now = Date.parse("2026-08-11T19:00:00.000Z"); // 12:00 PDT
  assert.equal(computeNextRunAt(AM, now), Date.parse("2026-08-12T14:00:00.000Z"));
  assert.equal(computeNextRunAt(PM, now), Date.parse("2026-08-12T00:45:00.000Z"));
});

test("email digest: fall-back day fires at Pacific STANDARD time (UTC-8)", () => {
  // DST ends 2026-11-01 02:00 in America/Los_Angeles. Both slots that day
  // are after the change: 07:00 PST = 15:00Z, 17:45 PST = 01:45Z next day.
  const now = Date.parse("2026-11-01T04:00:00.000Z"); // 21:00 PDT Oct 31
  assert.equal(computeNextRunAt(AM, now), Date.parse("2026-11-01T15:00:00.000Z"));
  assert.equal(computeNextRunAt(PM, now), Date.parse("2026-11-02T01:45:00.000Z"));
});

test("email digest: no stagger — fire time IS the slot, for any job id", () => {
  // Neither expr is a top-of-hour recurring shape, so the default 5-minute
  // stagger must not apply: a "07:00 digest" arriving at 07:03 is a bug.
  assert.equal(staggerWindowMs(AM), 0);
  assert.equal(staggerWindowMs(PM), 0);
  const now = Date.parse("2026-08-11T19:00:00.000Z");
  for (const id of ["cj_e-tZ1jvh", "cj_Nu4t2KB8"]) {
    assert.equal(computeNextFireAt(AM, id, now), computeNextRunAt(AM, now));
    assert.equal(computeNextFireAt(PM, id, now), computeNextRunAt(PM, now));
  }
});

test("email digest: daily advance — firing a slot lands on tomorrow's slot", () => {
  // The #17852 daily-skip class, on the exact live exprs.
  const amSlot = Date.parse("2026-08-12T14:00:00.000Z");
  assert.equal(computeNextRunAt(AM, amSlot + 1000), amSlot + 24 * 3_600_000);
  const pmSlot = Date.parse("2026-08-12T00:45:00.000Z");
  assert.equal(computeNextRunAt(PM, pmSlot + 1000), pmSlot + 24 * 3_600_000);
});
