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

// Session reaping (hardening): a cap on live harness children + an idle
// reaper, so a client reconnect loop / exited CLI can't strand a process
// herd. Reaping ends the session (resumable, same id) via the session.stop
// path; prompt.submit auto-revives, so clients never notice.

function fakeAdapter() {
  const cbs = new Map<string, any>();
  const state = { spawns: 0 };
  return {
    name: "fake",
    state,
    cbs,
    spawn(_spec: any, opts: any, cb: any) {
      state.spawns++;
      cbs.set(opts.daemonSessionId, cb);
      cb.onHarnessSession(`h-${opts.daemonSessionId}-${state.spawns}`);
      return { async send() {}, async interrupt() {}, async stop() {} };
    },
  };
}

function harness(overrides: { maxLiveSessions?: number; idleReapMs?: number } = {}) {
  const dir = join(tmpdir(), `mreap-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const adapter = fakeAdapter();
  const clock = { now: 0 };
  let n = 0;
  const router = createRouter({
    cfg: { ...defaultConfig(), maxLiveSessions: 8, idleReapMs: 60_000, ...overrides },
    sessions, transcripts: new TranscriptCache(dir), usage: new UsageMeter(),
    adapter, today: () => "2026-07-17", now: () => clock.now, mintSessionId: () => `s_${++n}`,
  });
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  const endTurn = (id: string) =>
    adapter.cbs.get(id).onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "sub");
  return { router, sessions, adapter, clock, conn, endTurn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("reapIdle stops sessions idle past the threshold; running + fresh-idle survive", async () => {
  const h = harness({ idleReapMs: 60_000 });
  try {
    const c = h.conn();
    const a = (await h.router("session.create", {}, c)) as { session_id: string };
    const b = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: b.session_id, prompt: "work" }, c); // b: running
    h.clock.now = 61_000;
    const fresh = (await h.router("session.create", {}, c)) as { session_id: string }; // idle but fresh
    const reaped = await (h.router as any).reapIdle();
    assert.deepEqual(reaped, [a.session_id]);
    assert.equal(h.sessions.get(a.session_id)?.lifecycle, "ended");
    assert.equal(h.sessions.get(b.session_id)?.lifecycle, "active"); // running: untouched
    assert.equal(h.sessions.get(fresh.session_id)?.lifecycle, "active"); // not idle long enough
    // The reap is announced like a stop — terminal status.update, not silence.
    const ended = c._sent.find((f: any) => f.params?.type === "status.update"
      && f.params.session_id === a.session_id && f.params.payload.lifecycle === "ended");
    assert.ok(ended, "reap must push a terminal status.update");
  } finally { h.cleanup(); }
});

test("a reaped session auto-revives on prompt.submit — same id, new child", async () => {
  const h = harness({ idleReapMs: 60_000 });
  try {
    const c = h.conn();
    const a = (await h.router("session.create", {}, c)) as { session_id: string };
    h.clock.now = 61_000;
    await (h.router as any).reapIdle();
    assert.equal(h.sessions.get(a.session_id)?.lifecycle, "ended");
    const spawnsBefore = h.adapter.state.spawns;
    const ack = (await h.router("prompt.submit", { session_id: a.session_id, prompt: "hi again" }, c)) as { message_id: string };
    assert.ok(ack.message_id, "submit to a reaped session must succeed");
    assert.equal(h.adapter.state.spawns, spawnsBefore + 1, "revive spawns a fresh child");
    assert.equal(h.sessions.get(a.session_id)?.lifecycle, "active");
    assert.equal(h.sessions.get(a.session_id)?.runState, "running");
  } finally { h.cleanup(); }
});

test("live cap evicts the LONGEST-idle idle session to admit a create", async () => {
  const h = harness({ maxLiveSessions: 2 });
  try {
    const c = h.conn();
    const a = (await h.router("session.create", {}, c)) as { session_id: string };
    h.clock.now = 1_000; // b goes idle later than a
    const b = (await h.router("session.create", {}, c)) as { session_id: string };
    h.clock.now = 2_000;
    const d = (await h.router("session.create", {}, c)) as { session_id: string };
    assert.equal(h.sessions.get(a.session_id)?.lifecycle, "ended"); // oldest idle evicted
    assert.equal(h.sessions.get(b.session_id)?.lifecycle, "active");
    assert.equal(h.sessions.get(d.session_id)?.lifecycle, "active");
  } finally { h.cleanup(); }
});

test("live cap with every slot BUSY: create fails visibly, running turns survive", async () => {
  const h = harness({ maxLiveSessions: 1 });
  try {
    const c = h.conn();
    const a = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: a.session_id, prompt: "work" }, c); // running
    await assert.rejects(() => h.router("session.create", {}, c), /cap reached/);
    assert.equal(h.sessions.get(a.session_id)?.lifecycle, "active"); // never killed for room
    // The slot frees when the turn ends: the next create evicts the now-idle a.
    h.endTurn(a.session_id);
    const b = (await h.router("session.create", {}, c)) as { session_id: string };
    assert.equal(h.sessions.get(b.session_id)?.lifecycle, "active");
    assert.equal(h.sessions.get(a.session_id)?.lifecycle, "ended");
  } finally { h.cleanup(); }
});

test("resume-while-live only attaches — it is never blocked by the cap", async () => {
  const h = harness({ maxLiveSessions: 1 });
  try {
    const c = h.conn();
    const a = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: a.session_id, prompt: "work" }, c); // running, cap full
    const r = (await h.router("session.resume", { session_id: a.session_id }, h.conn())) as { session_id: string };
    assert.equal(r.session_id, a.session_id);
  } finally { h.cleanup(); }
});
