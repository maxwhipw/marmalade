// cron-cli.test.ts — the pure halves of `marmalade cron` (parsing, schedule
// construction, formatting) plus the subcommand flows against a fake RPC.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  parseDuration, parseAt, parseFlags, scheduleFromFlags,
  formatJob, cronCommand,
} from "../dist/cron-cli.js";
import type { CronJobView } from "../dist/cron-cli.js";

const T0 = Date.parse("2026-07-17T10:30:00.000Z");

test("parseDuration: units, bare seconds, garbage rejected", () => {
  assert.equal(parseDuration("30s"), 30_000);
  assert.equal(parseDuration("15m"), 900_000);
  assert.equal(parseDuration("2h"), 7_200_000);
  assert.equal(parseDuration("1d"), 86_400_000);
  assert.equal(parseDuration("45"), 45_000);
  assert.equal(parseDuration("1.5h"), 5_400_000);
  assert.throws(() => parseDuration("soon"), /invalid duration/);
});

test("parseFlags: --k v, --k=v, trailing bare flag, positionals", () => {
  const [flags, pos] = parseFlags(["add", "--prompt", "hi there", "--every=15m", "--verbose"]);
  assert.equal(flags.get("prompt"), "hi there");
  assert.equal(flags.get("every"), "15m");
  assert.equal(flags.get("verbose"), "true"); // trailing flag with no value
  assert.deepEqual(pos, ["add"]);
});

test("scheduleFromFlags: exclusive cron/every/at; every anchors at now", () => {
  assert.deepEqual(
    scheduleFromFlags(new Map([["cron", "0 9 * * *"], ["tz", "UTC"]]), T0),
    { kind: "cron", expr: "0 9 * * *", tz: "UTC" },
  );
  assert.deepEqual(
    scheduleFromFlags(new Map([["every", "15m"]]), T0),
    { kind: "every", every_ms: 900_000, anchor_ms: T0 },
  );
  const at = scheduleFromFlags(new Map([["at", "2026-07-18T09:00:00.000Z"]]), T0);
  assert.deepEqual(at, { kind: "at", at_ms: Date.parse("2026-07-18T09:00:00.000Z") });
  assert.throws(() => scheduleFromFlags(new Map(), T0), /exactly one/);
  assert.throws(() => scheduleFromFlags(new Map([["cron", "x"], ["every", "1h"]]), T0), /exactly one/);
  assert.throws(() => parseAt("whenever"), /invalid time/);
});

function job(over: Partial<CronJobView> = {}): CronJobView {
  return {
    job_id: "cj_abc", name: "digest", session_id: "s_1", prompt: "morning digest",
    schedule: { kind: "cron", expr: "0 9 * * *", tz: "UTC" }, enabled: true,
    next_run_at: T0 + 3_600_000, last_run_at: null, last_status: null, last_error: null,
    ...over,
  };
}

test("formatJob: enabled/disabled/error states all render the load-bearing facts", () => {
  const on = formatJob(job(), T0);
  assert.match(on, /cj_abc {2}digest/);
  assert.match(on, /next in 1h/);
  assert.match(on, /cron "0 9 \* \* \*" \(UTC\)/);

  const off = formatJob(job({ enabled: false, next_run_at: null, last_error: "target session deleted" }), T0);
  assert.match(off, /\[disabled\]/);
  assert.match(off, /reason: target session deleted/);

  const failed = formatJob(job({ last_run_at: T0 - 60_000, last_status: "error", last_error: "boom" }), T0);
  assert.match(failed, /last error .* — boom/);
});

function fakeCall(handlers: Record<string, (params: any) => any>) {
  const seen: Array<{ method: string; params: any }> = [];
  const call = async (method: string, params: any) => {
    seen.push({ method, params });
    const h = handlers[method];
    if (!h) throw new Error(`unexpected method ${method}`);
    return h(params);
  };
  return { call, seen };
}

test("cron add resolves --session last via session.list order", async () => {
  const { call, seen } = fakeCall({
    "session.list": () => ({ sessions: [{ session_id: "s_recent", last_active: 2 }, { session_id: "s_old", last_active: 1 }] }),
    "cron.create": (p) => ({ job: job({ job_id: "cj_new", session_id: p.session_id }) }),
  });
  const out: string[] = [];
  const code = await cronCommand(["add", "--prompt", "p", "--every", "1h"], call, (l) => out.push(l), T0);
  assert.equal(code, 0);
  const create = seen.find((s) => s.method === "cron.create")!;
  assert.equal(create.params.session_id, "s_recent");
  assert.deepEqual(create.params.schedule, { kind: "every", every_ms: 3_600_000, anchor_ms: T0 });
  assert.match(out[0]!, /created cj_new/);
});

test("cron list / rm / run / disable flows", async () => {
  const { call } = fakeCall({
    "cron.list": () => ({ jobs: [job()] }),
    "cron.delete": (p) => ({ deleted: p.job_id === "cj_abc" }),
    "cron.run_now": () => ({ fired: true }),
    "cron.update": (p) => ({ job: job({ enabled: p.enabled, next_run_at: p.enabled ? T0 + 1 : null }) }),
  });
  const out: string[] = [];
  assert.equal(await cronCommand(["list"], call, (l) => out.push(l), T0), 0);
  assert.equal(await cronCommand(["rm", "cj_abc"], call, (l) => out.push(l), T0), 0);
  assert.equal(await cronCommand(["rm", "cj_nope"], call, (l) => out.push(l), T0), 1);
  assert.equal(await cronCommand(["run", "cj_abc"], call, (l) => out.push(l), T0), 0);
  assert.equal(await cronCommand(["disable", "cj_abc"], call, (l) => out.push(l), T0), 0);
  assert.match(out.join("\n"), /deleted cj_abc/);
  assert.match(out.join("\n"), /fired cj_abc/);
  assert.match(out.join("\n"), /\[disabled\]/);
});

test("unknown subcommand prints usage and exits 1; help exits 0", async () => {
  const { call } = fakeCall({});
  const out: string[] = [];
  assert.equal(await cronCommand(["bogus"], call, (l) => out.push(l), T0), 1);
  assert.match(out[0]!, /usage: marmalade cron/);
  assert.equal(await cronCommand(["help"], call, () => {}, T0), 0);
  assert.equal(await cronCommand([], call, () => {}, T0), 0);
});
