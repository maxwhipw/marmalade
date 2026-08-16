// cron-router.test.ts — the cron.* RPC surface + the fire path end to end:
// a job fires prompt.submit into its session with origin source "cron",
// auto-reviving a non-live session (the reaper stays invisible to cron).

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";
import { CronScheduler } from "../dist/cron-scheduler.js";

const T0 = Date.parse("2026-07-17T10:30:00.000Z");

function fakeAdapter(spawns: string[] = []) {
  return {
    name: "fake",
    spawn(_spec: unknown, opts: { daemonSessionId: string }, cb: any) {
      spawns.push(opts.daemonSessionId);
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n\n/, "");
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harness() {
  const dir = join(tmpdir(), `cr-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const now = { t: T0 };
  const spawns: string[] = [];
  let n = 0;
  const scheduler = new CronScheduler({ store: sessions.cron, now: () => now.t, timers: false });
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    usage: new UsageMeter(),
    adapter: fakeAdapter(spawns) as any,
    today: () => "2026-07-17",
    now: () => now.t,
    mintSessionId: () => `s_${++n}`,
    cron: scheduler,
  });
  scheduler.onFire = (job) => router.submitCron(job.sessionId, job.prompt);
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  return { router, scheduler, sessions, transcripts, now, spawns, conn, cleanup: () => { sessions.close(); rmSync(dir, { recursive: true, force: true }); } };
}

test("cron.create validates, lists (disabled included), updates, deletes", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;

    // Unknown session / bad schedules are create-time errors.
    await assert.rejects(h.router("cron.create", { session_id: "s_nope", prompt: "x", schedule: { kind: "every", every_ms: 60_000 } }, c), /unknown session/);
    await assert.rejects(h.router("cron.create", { session_id, prompt: "x", schedule: { kind: "cron", expr: "garbage in" } }, c), /invalid cron/);
    await assert.rejects(h.router("cron.create", { session_id, prompt: "x", schedule: { kind: "at", at_ms: h.now.t - 1 } }, c), /past/);

    const { job } = (await h.router("cron.create", {
      session_id, prompt: "morning digest", name: "digest",
      schedule: { kind: "cron", expr: "0 9 * * *", tz: "UTC" },
    }, c)) as any;
    assert.equal(job.session_id, session_id);
    assert.equal(job.enabled, true);
    assert.ok(job.next_run_at > h.now.t, "armed into the future");

    // Disable → next_run_at clears; list still shows it (#16156 class).
    const upd = (await h.router("cron.update", { job_id: job.job_id, enabled: false }, c)) as any;
    assert.equal(upd.job.enabled, false);
    assert.equal(upd.job.next_run_at, null);
    const { jobs } = (await h.router("cron.list", {}, c)) as any;
    assert.equal(jobs.length, 1, "disabled jobs stay listed");

    // Re-enable re-arms; schedule change recomputes (at-reschedule #19676).
    const re = (await h.router("cron.update", { job_id: job.job_id, enabled: true, schedule: { kind: "at", at_ms: h.now.t + 5000 } }, c)) as any;
    assert.equal(re.job.next_run_at, h.now.t + 5000);

    const del = (await h.router("cron.delete", { job_id: job.job_id }, c)) as any;
    assert.equal(del.deleted, true);
    assert.deepEqual(((await h.router("cron.list", {}, c)) as any).jobs, []);
  } finally { h.cleanup(); }
});

test("cron.update of an enabled job does NOT silently advance a past-due slot (#17852 guard)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    const { job } = (await h.router("cron.create", { session_id, prompt: "p", schedule: { kind: "every", every_ms: 60_000, anchor_ms: h.now.t } }, c)) as any;
    // Time passes; the slot is now due but hasn't fired yet.
    h.now.t = job.next_run_at + 5000;
    const upd = (await h.router("cron.update", { job_id: job.job_id, name: "renamed" }, c)) as any;
    assert.equal(upd.job.next_run_at, job.next_run_at, "a metadata edit must not eat the due slot");
  } finally { h.cleanup(); }
});

test("a fire auto-revives a stopped session and submits with origin source=cron", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    const { job } = (await h.router("cron.create", {
      session_id, prompt: "scheduled hello",
      schedule: { kind: "every", every_ms: 60_000, anchor_ms: h.now.t },
    }, c)) as any;

    // Stop the session (reaper-equivalent) — the fire must revive it.
    await h.router("session.stop", { session_id }, c);
    assert.equal(h.spawns.length, 1);

    h.now.t = job.next_run_at + 1;
    await h.scheduler.tick();

    assert.equal(h.spawns.length, 2, "fire respawned the session (auto-revive)");
    const events = h.transcripts.replay(session_id);
    const userMsg = events.find((e: any) => e.params.type === "message.user" && e.params.payload.text === "scheduled hello") as any;
    assert.ok(userMsg, "cron prompt landed in the transcript");
    assert.equal(userMsg.params.payload.origin.source, "cron");
    assert.equal(userMsg.params.payload.origin.device_id, "cron");
    const echo = events.find((e: any) => e.params.type === "message.delta" && e.params.payload.text === "echo:scheduled hello");
    assert.ok(echo, "the harness saw the raw prompt (preamble stripped)");
    assert.equal(h.sessions.cron.get(job.job_id)!.lastStatus, "ok");
  } finally { h.cleanup(); }
});

test("a fire into a DELETED session records error and session.delete disables its jobs", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const a = (await h.router("session.create", { cols: 80 }, c)) as any;
    const b = (await h.router("session.create", { cols: 80 }, c)) as any;
    const ja = (await h.router("cron.create", { session_id: a.session_id, prompt: "pa", schedule: { kind: "every", every_ms: 60_000, anchor_ms: h.now.t } }, c)) as any;
    const jb = (await h.router("cron.create", { session_id: b.session_id, prompt: "pb", schedule: { kind: "every", every_ms: 60_000, anchor_ms: h.now.t } }, c)) as any;

    // Deleting session A disables its job with a reason; B's is untouched.
    await h.router("session.delete", { session_id: a.session_id }, c);
    const dja = h.sessions.cron.get(ja.job.job_id)!;
    assert.equal(dja.enabled, false);
    assert.match(dja.lastError!, /target session deleted/);
    assert.equal(h.sessions.cron.get(jb.job.job_id)!.enabled, true);

    // Belt-and-braces: a fire whose target vanished records last_status=error.
    h.sessions.cron.update(jb.job.job_id, { sessionId: "s_gone" }, h.now.t);
    h.now.t = jb.job.next_run_at + 1;
    await h.scheduler.tick();
    const djb = h.sessions.cron.get(jb.job.job_id)!;
    assert.equal(djb.lastStatus, "error");
    assert.match(djb.lastError!, /no longer exists/);
  } finally { h.cleanup(); }
});

test("cron.run_now fires immediately without moving the schedule", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    const { job } = (await h.router("cron.create", { session_id, prompt: "manual", schedule: { kind: "cron", expr: "0 9 * * *", tz: "UTC" } }, c)) as any;
    const res = (await h.router("cron.run_now", { job_id: job.job_id }, c)) as any;
    assert.equal(res.fired, true);
    const j = h.sessions.cron.get(job.job_id)!;
    assert.equal(j.nextRunAt, job.next_run_at, "schedule unmoved");
    assert.equal(j.lastStatus, "ok");
    const userMsg = h.transcripts.replay(session_id).find((e: any) => e.params.type === "message.user" && e.params.payload.text === "manual");
    assert.ok(userMsg);
    await assert.rejects(h.router("cron.run_now", { job_id: "cj_nope" }, c), /unknown cron job/);
  } finally { h.cleanup(); }
});

test("restart-catchup end to end: a new router+scheduler over a persisted store fires the missed slot", async () => {
  const dir = join(tmpdir(), `cr2-${randomUUID()}`);
  const db = join(tmpdir(), `cr2-${randomUUID()}.db`);
  try {
    const now = { t: T0 };
    // "First life": create a session + a job due while the daemon is 'down'.
    let sessions = new SessionManager(db);
    let transcripts = new TranscriptCache(dir);
    const spawns: string[] = [];
    let n = 0;
    const mk = () => {
      const scheduler = new CronScheduler({ store: sessions.cron, now: () => now.t, timers: false });
      const router = createRouter({
        cfg: defaultConfig(), sessions, transcripts, usage: new UsageMeter(),
        adapter: fakeAdapter(spawns) as any, today: () => "2026-07-17",
        now: () => now.t, mintSessionId: () => `s_${++n}`, cron: scheduler,
      });
      scheduler.onFire = (job) => router.submitCron(job.sessionId, job.prompt);
      return { scheduler, router };
    };
    const life1 = mk();
    const c = { ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any;
    const { session_id } = (await life1.router("session.create", { cols: 80 }, c)) as any;
    await life1.router("cron.create", { session_id, prompt: "missed me", schedule: { kind: "every", every_ms: 60_000, anchor_ms: now.t } }, c);

    // Daemon dies; time passes past the slot.
    sessions.close();
    now.t += 10 * 60_000;

    // "Second life": reopen the SAME db, reconcile orphans, start → catchup.
    sessions = new SessionManager(db);
    sessions.markOrphansExited(now.t);
    const life2 = mk();
    await life2.scheduler.start();

    const job2 = sessions.cron.list()[0]!;
    assert.equal(job2.lastStatus, "ok", "missed slot fired once on boot");
    assert.ok(job2.nextRunAt! > now.t);
    const userMsg = transcripts.replay(session_id).find((e: any) => e.params.type === "message.user" && e.params.payload.text === "missed me");
    assert.ok(userMsg, "the catch-up fire reached the session");
    sessions.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(db, { force: true });
  }
});
