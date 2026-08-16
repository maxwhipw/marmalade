// ntfy.test.ts — the SECONDARY alert path (hardening #2). Off unless a topic
// is configured; publish never throws into a caller; the three alert seams
// (supervisor silent failure, cron fire failure, budget-breach cron pause)
// fire with sensible titles. No network: fetch is injected everywhere.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { NtfyNotifier } from "../dist/ntfy.js";
import { defaultConfig, ConfigFileSchema } from "../dist/config.js";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { CronScheduler } from "../dist/cron-scheduler.js";
import { Supervisor } from "../dist/supervisor.js";
import { createSessionSpec } from "../dist/policy.js";

const T0 = Date.parse("2026-07-18T10:30:00.000Z");

// ── config: off unless a topic is configured ────────────────────────────────

test("ntfy config: absent without a topic; defaults + overrides honored", () => {
  assert.equal(defaultConfig({}).ntfy, undefined, "no topic → feature off");

  const on = defaultConfig({ ntfy: { topic: "marm-alerts" } });
  assert.deepEqual(on.ntfy, { server: "https://ntfy.sh", topic: "marm-alerts" });

  const full = defaultConfig({ ntfy: { server: "https://ntfy.example", topic: "t", token: "tk_x" } });
  assert.deepEqual(full.ntfy, { server: "https://ntfy.example", topic: "t", token: "tk_x" });

  // Strict schema: a typo'd key inside the ntfy block fails validation.
  assert.equal(ConfigFileSchema.safeParse({ ntfy: { topic: "t", sever: "oops" } }).success, false);
  assert.equal(ConfigFileSchema.safeParse({ ntfy: { server: "not a url", topic: "t" } }).success, false);
});

test("ntfy config: env topic switches the feature on and wins over file", () => {
  process.env.MARMALADE_NTFY_TOPIC = "env-topic";
  try {
    const cfg = defaultConfig({ ntfy: { server: "https://ntfy.example", topic: "file-topic" } });
    assert.deepEqual(cfg.ntfy, { server: "https://ntfy.example", topic: "env-topic" });
  } finally {
    delete process.env.MARMALADE_NTFY_TOPIC;
  }
});

// ── publish: URL/headers/priority + failure swallowing ──────────────────────

function fakeFetch(result: { ok?: boolean; status?: number; throwMsg?: string } = {}) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fn = (async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init: init! });
    if (result.throwMsg) throw new Error(result.throwMsg);
    return { ok: result.ok ?? true, status: result.status ?? 200 } as Response;
  }) as typeof fetch;
  return { fn, calls };
}

test("publish POSTs to server/topic with Title, Priority, and Bearer auth", async () => {
  const f = fakeFetch();
  const n = new NtfyNotifier(
    { server: "https://ntfy.example/", topic: "marm", token: "tk_secret" },
    { fetchFn: f.fn },
  );
  await n.publish("Daemon says", "hello body", { priority: 5 });

  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].url, "https://ntfy.example/marm", "trailing slash collapsed");
  assert.equal(f.calls[0].init.method, "POST");
  assert.equal(f.calls[0].init.body, "hello body");
  const h = f.calls[0].init.headers as Record<string, string>;
  assert.equal(h.Title, "Daemon says");
  assert.equal(h.Priority, "5");
  assert.equal(h.Authorization, "Bearer tk_secret");
});

test("publish without token/priority omits those headers", async () => {
  const f = fakeFetch();
  const n = new NtfyNotifier({ server: "https://ntfy.sh", topic: "marm" }, { fetchFn: f.fn });
  await n.publish("T", "m");
  const h = f.calls[0].init.headers as Record<string, string>;
  assert.equal(h.Authorization, undefined);
  assert.equal(h.Priority, undefined);
});

test("publish failure is swallowed and logged — never thrown", async () => {
  const lines: string[] = [];
  const log = (l: string) => lines.push(l);

  const thrown = new NtfyNotifier({ server: "https://x", topic: "t" }, { fetchFn: fakeFetch({ throwMsg: "ECONNREFUSED" }).fn, log });
  await thrown.publish("T", "m"); // must resolve, not reject
  assert.equal(lines.length, 1);
  assert.match(lines[0], /\[ntfy\] publish failed: ECONNREFUSED/);

  const httpErr = new NtfyNotifier({ server: "https://x", topic: "t" }, { fetchFn: fakeFetch({ ok: false, status: 403 }).fn, log });
  await httpErr.publish("T", "m");
  assert.equal(lines.length, 2);
  assert.match(lines[1], /\[ntfy\] publish failed: HTTP 403/);
});

// ── seam: supervisor silent failure (wired as in index.ts) ──────────────────

test("supervisor silent failure publishes one max-priority alert", async () => {
  const f = fakeFetch();
  const ntfy = new NtfyNotifier({ server: "https://ntfy.sh", topic: "marm" }, { fetchFn: f.fn });
  const sessions = SessionManager.inMemory();
  const spec = createSessionSpec({ principal: "owner", purpose: "cadence", origin: "cadence" }, defaultConfig({}));
  sessions.create("stale", spec, "claude-code", 0);

  const published: Promise<void>[] = [];
  const sup = new Supervisor(sessions, {
    now: () => 200_000,
    onSilentFailure: (rec) => {
      published.push(ntfy.publish("Marmalade: silent session failure", `session ${rec.id} (${rec.purpose}) stopped heartbeating — marked hung`, { priority: 5 }));
    },
  });
  sup.tick();
  sup.tick(); // latch: no second alert
  await Promise.all(published);

  assert.equal(f.calls.length, 1);
  const h = f.calls[0].init.headers as Record<string, string>;
  assert.equal(h.Title, "Marmalade: silent session failure");
  assert.equal(h.Priority, "5");
  assert.match(String(f.calls[0].init.body), /session stale \(cadence\)/);
  sessions.close();
});

// ── seams: cron fire failure + budget-breach latch ───────────────────────────

function fakeAdapter() {
  return {
    name: "fake",
    spawn(_spec: unknown, opts: { daemonSessionId: string }, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send() {
          cb.onActivity();
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 3, inputTokens: 100, outputTokens: 50 }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harness(budget?: { metric: "usd" | "tokens"; dailyLimit: number }) {
  const dir = join(tmpdir(), `ntfy-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const now = { t: T0 };
  const day = { d: "2026-07-18" };
  let n = 0;
  const alerts: { title: string; message: string; priority?: number }[] = [];
  const ntfy = {
    publish: async (title: string, message: string, opts?: { priority?: number }) => {
      alerts.push({ title, message, ...(opts?.priority !== undefined ? { priority: opts.priority } : {}) });
    },
  };
  const scheduler = new CronScheduler({
    store: sessions.cron,
    now: () => now.t,
    timers: false,
    // Same wiring shape as index.ts: fire failures fan into ntfy.
    alert: (title, message) => { void ntfy.publish(title, message, { priority: 4 }); },
  });
  const cfg = { ...defaultConfig({}), ...(budget ? { budget } : {}) };
  const router = createRouter({
    cfg,
    sessions,
    transcripts: new TranscriptCache(dir),
    usage: sessions.usage,
    adapter: fakeAdapter() as any,
    today: () => day.d,
    now: () => now.t,
    mintSessionId: () => `s_${++n}`,
    cron: scheduler,
    ntfy,
  });
  scheduler.onFire = (job) => router.submitCron(job.sessionId, job.prompt);
  const conn = () => ({ ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any);
  return { router, scheduler, sessions, now, day, alerts, conn, cleanup: () => { sessions.close(); rmSync(dir, { recursive: true, force: true }); } };
}

test("cron fire failure alerts (with job name); success does not", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    const { job } = (await h.router("cron.create", {
      session_id, name: "morning brief", prompt: "go", schedule: { kind: "every", every_ms: 60_000, anchor_ms: h.now.t },
    }, c)) as any;

    // Success first: no alert.
    h.now.t = job.next_run_at + 1;
    await h.scheduler.tick();
    assert.equal(h.sessions.cron.get(job.job_id)!.lastStatus, "ok");
    assert.equal(h.alerts.length, 0, "successful fire never alerts");

    // Kill the target session behind the router's back (the RPC delete would
    // disable the job) → the fire fails → one alert.
    await h.router("session.stop", { session_id }, c);
    h.sessions.delete(session_id);
    h.now.t = h.sessions.cron.get(job.job_id)!.nextRunAt! + 1;
    await h.scheduler.tick();
    assert.equal(h.sessions.cron.get(job.job_id)!.lastStatus, "error");
    assert.equal(h.alerts.length, 1);
    assert.equal(h.alerts[0].title, "Marmalade cron fire failed: morning brief");
    assert.match(h.alerts[0].message, /no longer exists/);
  } finally { h.cleanup(); }
});

test("budget breach alerts ONCE per episode; latch resets when back under", async () => {
  const h = harness({ metric: "usd", dailyLimit: 5 });
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    // Two $3 turns → $6 > $5: over budget.
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);

    const { job } = (await h.router("cron.create", {
      session_id, prompt: "scheduled", schedule: { kind: "every", every_ms: 60_000, anchor_ms: h.now.t },
    }, c)) as any;

    // Two refused fires in the same breach → exactly ONE alert (and the
    // scheduler's generic fire-failed alert stays quiet for budget errors).
    h.now.t = job.next_run_at + 1;
    await h.scheduler.tick();
    h.now.t = h.sessions.cron.get(job.job_id)!.nextRunAt! + 1;
    await h.scheduler.tick();
    assert.equal(h.sessions.cron.get(job.job_id)!.lastStatus, "error");
    assert.equal(h.alerts.length, 1);
    assert.equal(h.alerts[0].title, "Marmalade: daily budget exceeded — cron paused");
    assert.match(h.alerts[0].message, /daily budget exceeded/);

    // New day → under budget → fire succeeds, latch resets, no new alert.
    h.day.d = "2026-07-19";
    h.now.t = h.sessions.cron.get(job.job_id)!.nextRunAt! + 1;
    await h.scheduler.tick();
    assert.equal(h.sessions.cron.get(job.job_id)!.lastStatus, "ok");
    assert.equal(h.alerts.length, 1);

    // Breach again on the new day → a SECOND alert (new episode).
    await h.router("prompt.submit", { session_id, prompt: "three" }, c);
    await h.router("prompt.submit", { session_id, prompt: "four" }, c);
    h.now.t = h.sessions.cron.get(job.job_id)!.nextRunAt! + 1;
    await h.scheduler.tick();
    assert.equal(h.alerts.length, 2);
    assert.equal(h.alerts[1].title, "Marmalade: daily budget exceeded — cron paused");
  } finally { h.cleanup(); }
});
