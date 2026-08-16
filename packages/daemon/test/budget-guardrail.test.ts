// budget-guardrail.test.ts — the daily budget (config file → cfg.budget)
// gates UNATTENDED turns only: an over-budget day refuses cron fires (job
// records the refusal as last_error — visible, not silent) while interactive
// prompt.submit keeps working. usage.summary decorates with the budget state.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { defaultConfig } from "../dist/config.js";
import { CronScheduler } from "../dist/cron-scheduler.js";

const T0 = Date.parse("2026-07-18T10:30:00.000Z");

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
  const dir = join(tmpdir(), `bg-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const now = { t: T0 };
  let n = 0;
  const scheduler = new CronScheduler({ store: sessions.cron, now: () => now.t, timers: false });
  const cfg = { ...defaultConfig({}), ...(budget ? { budget } : {}) };
  const router = createRouter({
    cfg,
    sessions,
    transcripts: new TranscriptCache(dir),
    usage: sessions.usage,
    adapter: fakeAdapter() as any,
    today: () => "2026-07-18",
    now: () => now.t,
    mintSessionId: () => `s_${++n}`,
    cron: scheduler,
  });
  scheduler.onFire = (job) => router.submitCron(job.sessionId, job.prompt);
  const conn = () => ({ ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any);
  return { router, scheduler, sessions, now, conn, cleanup: () => { sessions.close(); rmSync(dir, { recursive: true, force: true }); } };
}

test("over budget: cron fire refused + recorded; interactive submit still works", async () => {
  const h = harness({ metric: "usd", dailyLimit: 5 });
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    // Two turns at $3 each → $6 > $5: over budget.
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);

    const { job } = (await h.router("cron.create", {
      session_id, prompt: "scheduled", schedule: { kind: "every", every_ms: 60_000, anchor_ms: h.now.t },
    }, c)) as any;
    h.now.t = job.next_run_at + 1;
    await h.scheduler.tick();

    const j = h.sessions.cron.get(job.job_id)!;
    assert.equal(j.lastStatus, "error", "cron fire refused while over budget");
    assert.match(j.lastError!, /daily budget exceeded/);
    assert.ok(j.nextRunAt! > h.now.t, "schedule still advances — resumes when a new day starts");

    // A user-typed prompt is NEVER blocked.
    const r = (await h.router("prompt.submit", { session_id, prompt: "three" }, c)) as any;
    assert.ok(r.message_id, "interactive submit unaffected");

    // usage.summary reports the breach.
    const s = (await h.router("usage.summary", {}, c)) as any;
    assert.equal(s.budget.over, true);
    assert.equal(s.budget.metric, "usd");
    assert.equal(s.budget.daily_limit, 5);
    assert.ok(s.budget.today_total > 5);
  } finally { h.cleanup(); }
});

test("under budget: cron fires normally; no budget config → budget: null", async () => {
  const under = harness({ metric: "usd", dailyLimit: 100 });
  try {
    const c = under.conn();
    const { session_id } = (await under.router("session.create", { cols: 80 }, c)) as any;
    const { job } = (await under.router("cron.create", {
      session_id, prompt: "ok", schedule: { kind: "every", every_ms: 60_000, anchor_ms: under.now.t },
    }, c)) as any;
    under.now.t = job.next_run_at + 1;
    await under.scheduler.tick();
    assert.equal(under.sessions.cron.get(job.job_id)!.lastStatus, "ok");
    const s = (await under.router("usage.summary", {}, c)) as any;
    assert.equal(s.budget.over, false);
  } finally { under.cleanup(); }

  const none = harness();
  try {
    const c = none.conn();
    await none.router("session.create", { cols: 80 }, c);
    const s = (await none.router("usage.summary", {}, c)) as any;
    assert.equal(s.budget, null);
  } finally { none.cleanup(); }
});
