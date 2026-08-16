// cron-format.ts — pure display helpers for cron jobs, mirroring the CLI's
// formatting (packages/cli/src/cron-cli.ts is the reference UX; parity with it
// is the floor). Pure so vitest covers them without React.

import type { CronJobWire, CronScheduleWire } from "@marmalade/protocol";

/** ms → compact duration ("90s", "15m", "2h", "1d"). */
export function formatMs(ms: number): string {
  if (ms % 86_400_000 === 0) return `${ms / 86_400_000}d`;
  if (ms % 3_600_000 === 0) return `${ms / 3_600_000}h`;
  if (ms % 60_000 === 0) return `${ms / 60_000}m`;
  return `${Math.round(ms / 1000)}s`;
}

/** Human countdown, coarse on purpose ("in 3h 20m", "in 45s", "now"). */
export function formatCountdown(deltaMs: number): string {
  if (deltaMs <= 0) return "now";
  const s = Math.round(deltaMs / 1000);
  if (s < 60) return `in ${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `in ${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `in ${h}h ${m % 60}m`;
  const d = Math.floor(h / 24);
  return `in ${d}d ${h % 24}h`;
}

export function describeSchedule(s: CronScheduleWire): string {
  switch (s.kind) {
    case "cron": return `cron "${s.expr}"${s.tz ? ` (${s.tz})` : ""}`;
    case "every": return `every ${formatMs(s.every_ms)}`;
    case "at": return `once at ${new Date(s.at_ms).toLocaleString()}`;
  }
}

/** The state line: enabled → next-run countdown; disabled → why (a job the
 *  scheduler disabled records the reason in last_error — surfacing it is the
 *  point of listing disabled jobs at all). */
export function jobStateLabel(j: CronJobWire, nowMs: number): string {
  if (!j.enabled) {
    // A self-disabled one-shot that fired ok is "done", not broken.
    if (j.schedule.kind === "at" && j.last_status === "ok") return "done (one-shot fired)";
    return j.last_error && j.last_status !== "error" ? `disabled — ${j.last_error}` : "disabled";
  }
  if (j.next_run_at == null) return "never (unarmed)";
  return `next ${formatCountdown(j.next_run_at - nowMs)} · ${new Date(j.next_run_at).toLocaleString()}`;
}

export function lastRunLabel(j: CronJobWire): string {
  if (!j.last_run_at) return "never ran";
  const when = new Date(j.last_run_at).toLocaleString();
  if (j.last_status === "error") return `last run failed at ${when}${j.last_error ? ` — ${j.last_error}` : ""}`;
  return `last ran ok at ${when}`;
}

/** "30s" | "15m" | "2h" | "1d" → ms; bare numbers are MINUTES here (a UI
 *  duration field defaulting to seconds invites accidental hammering). */
export function parseDuration(raw: string): number {
  const m = /^(\d+(?:\.\d+)?)\s*(s|m|h|d)?$/.exec(raw.trim());
  if (!m) throw new Error(`invalid duration "${raw}" — use e.g. 30s, 15m, 2h, 1d`);
  const unit = { s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000 }[m[2] ?? "m"]!;
  const ms = Math.round(Number(m[1]) * unit);
  if (ms < 1000) throw new Error("duration must be at least 1s");
  return ms;
}

/** Build the wire schedule from the create form's fields. Throws with a
 *  user-facing message on bad input; callers surface it inline. */
export function buildSchedule(
  kind: "cron" | "every" | "at",
  fields: { expr?: string; tz?: string; every?: string; at?: string },
  nowMs: number,
): CronScheduleWire {
  if (kind === "cron") {
    const expr = fields.expr?.trim();
    if (!expr) throw new Error("a cron expression is required");
    const tz = fields.tz?.trim();
    return { kind: "cron", expr, ...(tz ? { tz } : {}) };
  }
  if (kind === "every") {
    return { kind: "every", every_ms: parseDuration(fields.every ?? ""), anchor_ms: nowMs };
  }
  const at = Date.parse(fields.at ?? "");
  if (!Number.isFinite(at)) throw new Error("pick a date and time for the one-shot");
  if (at <= nowMs) throw new Error("the one-shot time is in the past");
  return { kind: "at", at_ms: at };
}
