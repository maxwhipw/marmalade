// cron-format.test.ts — the CronView's pure display/parse helpers. The daemon
// semantics they must reflect live in packages/daemon/test/cron-router.test.ts;
// here we pin the client-side rendering rules: disabled jobs surface their
// reason, fired one-shots read as "done", durations round-trip.

import { describe, expect, it } from "vitest";
import type { CronJobWire } from "@marmalade/protocol";
import {
  buildSchedule,
  describeSchedule,
  formatCountdown,
  formatMs,
  jobStateLabel,
  lastRunLabel,
  parseDuration,
} from "../src/components/cron-format.js";

const NOW = 1_800_000_000_000;

function job(over: Partial<CronJobWire>): CronJobWire {
  return {
    job_id: "cj_1",
    name: "test",
    session_id: "s1",
    prompt: "p",
    schedule: { kind: "every", every_ms: 3_600_000 },
    enabled: true,
    created_at: NOW,
    updated_at: NOW,
    next_run_at: NOW + 3_600_000,
    last_run_at: null,
    last_status: null,
    last_error: null,
    ...over,
  };
}

describe("formatMs / formatCountdown", () => {
  it("picks the largest exact unit", () => {
    expect(formatMs(1000)).toBe("1s");
    expect(formatMs(90_000)).toBe("90s"); // not exactly minutes
    expect(formatMs(900_000)).toBe("15m");
    expect(formatMs(7_200_000)).toBe("2h");
    expect(formatMs(86_400_000)).toBe("1d");
  });
  it("countdown coarsens with distance and clamps the past to now", () => {
    expect(formatCountdown(-5)).toBe("now");
    expect(formatCountdown(45_000)).toBe("in 45s");
    expect(formatCountdown(5 * 60_000)).toBe("in 5m");
    expect(formatCountdown(3 * 3_600_000 + 20 * 60_000)).toBe("in 3h 20m");
    expect(formatCountdown(2 * 86_400_000 + 3 * 3_600_000)).toBe("in 2d 3h");
  });
});

describe("describeSchedule", () => {
  it("covers all three kinds", () => {
    expect(describeSchedule({ kind: "cron", expr: "0 9 * * *", tz: "UTC" })).toBe('cron "0 9 * * *" (UTC)');
    expect(describeSchedule({ kind: "cron", expr: "* * * * *" })).toBe('cron "* * * * *"');
    expect(describeSchedule({ kind: "every", every_ms: 900_000 })).toBe("every 15m");
    expect(describeSchedule({ kind: "at", at_ms: NOW })).toContain("once at ");
  });
});

describe("jobStateLabel", () => {
  it("enabled job shows the next-run countdown", () => {
    expect(jobStateLabel(job({}), NOW)).toContain("next in 1h");
  });
  it("enabled job with null next_run_at is unarmed, not blank", () => {
    expect(jobStateLabel(job({ next_run_at: null }), NOW)).toBe("never (unarmed)");
  });
  it("a scheduler-disabled job surfaces its reason from last_error", () => {
    const j = job({ enabled: false, last_error: "target session was deleted", last_status: "ok" });
    expect(jobStateLabel(j, NOW)).toBe("disabled — target session was deleted");
  });
  it("a fired one-shot reads as done, not broken", () => {
    const j = job({
      enabled: false,
      schedule: { kind: "at", at_ms: NOW - 1000 },
      last_run_at: NOW - 1000,
      last_status: "ok",
    });
    expect(jobStateLabel(j, NOW)).toBe("done (one-shot fired)");
  });
  it("a job disabled after an error run shows plain disabled (the error rides lastRunLabel)", () => {
    const j = job({ enabled: false, last_status: "error", last_error: "boom", last_run_at: NOW });
    expect(jobStateLabel(j, NOW)).toBe("disabled");
    expect(lastRunLabel(j)).toContain("failed");
    expect(lastRunLabel(j)).toContain("boom");
  });
});

describe("parseDuration", () => {
  it("units parse; bare numbers are minutes", () => {
    expect(parseDuration("30s")).toBe(30_000);
    expect(parseDuration("15m")).toBe(900_000);
    expect(parseDuration("2h")).toBe(7_200_000);
    expect(parseDuration("1d")).toBe(86_400_000);
    expect(parseDuration("90")).toBe(90 * 60_000);
  });
  it("rejects junk and sub-second intervals", () => {
    expect(() => parseDuration("soon")).toThrow(/invalid duration/);
    expect(() => parseDuration("0.5s")).toThrow(/at least 1s/);
  });
});

describe("buildSchedule", () => {
  it("cron kind requires an expr and omits blank tz", () => {
    expect(() => buildSchedule("cron", { expr: " " }, NOW)).toThrow(/expression/);
    expect(buildSchedule("cron", { expr: "0 9 * * *", tz: "" }, NOW)).toEqual({ kind: "cron", expr: "0 9 * * *" });
    expect(buildSchedule("cron", { expr: "0 9 * * *", tz: "UTC" }, NOW)).toEqual({ kind: "cron", expr: "0 9 * * *", tz: "UTC" });
  });
  it("every kind anchors at now (matching the CLI)", () => {
    expect(buildSchedule("every", { every: "15m" }, NOW)).toEqual({ kind: "every", every_ms: 900_000, anchor_ms: NOW });
  });
  it("at kind rejects the past and unparseable input", () => {
    expect(() => buildSchedule("at", { at: "" }, NOW)).toThrow(/date and time/);
    expect(() => buildSchedule("at", { at: new Date(NOW - 60_000).toISOString() }, NOW)).toThrow(/past/);
    const s = buildSchedule("at", { at: new Date(NOW + 60_000).toISOString() }, NOW);
    expect(s).toEqual({ kind: "at", at_ms: NOW + 60_000 });
  });
});
