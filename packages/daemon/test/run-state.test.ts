import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync, mkdirSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { SessionManager, deriveStatus } from "../dist/session-manager.js";
import { Supervisor } from "../dist/supervisor.js";
import { createRouter } from "../dist/router.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { createSessionSpec } from "../dist/policy.js";
import { defaultConfig } from "../dist/config.js";

// P2: SessionStatus split into lifecycle (active|ended) + runState
// (starting|idle|running|awaiting_input|hung). No state transition ever mints
// or changes an id; clients get a legacy derived `status` for v1 compat.

const cfg = defaultConfig();
const spec = () => createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);

test("lifecycle + runState round-trip; legacy status is derived, not stored truth", () => {
  const m = SessionManager.inMemory();
  m.create("s", spec(), "claude-code", 1000);
  let r = m.get("s")!;
  assert.equal(r.lifecycle, "active");
  assert.equal(r.runState, "starting");
  assert.equal(r.status, "starting");

  m.setRunState("s", "running", 2000);
  r = m.get("s")!;
  assert.equal(r.runState, "running");
  assert.equal(r.status, "live", "derived legacy view");
  assert.equal(r.id, "s", "state flips never change the id");

  m.setRunState("s", "awaiting_input", 3000);
  assert.equal(m.get("s")!.status, "idle", "awaiting_input maps to legacy idle");

  m.end("s", 4000);
  r = m.get("s")!;
  assert.equal(r.lifecycle, "ended");
  assert.equal(r.status, "exited");

  m.revive("s", 5000);
  r = m.get("s")!;
  assert.equal(r.lifecycle, "active");
  assert.equal(r.runState, "starting");
  m.close();
});

test("deriveStatus covers the whole matrix", () => {
  assert.equal(deriveStatus("ended", "running"), "exited"); // ended wins
  assert.equal(deriveStatus("active", "starting"), "starting");
  assert.equal(deriveStatus("active", "running"), "live");
  assert.equal(deriveStatus("active", "idle"), "idle");
  assert.equal(deriveStatus("active", "awaiting_input"), "idle");
  assert.equal(deriveStatus("active", "hung"), "hung");
});

test("PRE-P2 FILE DB migrates: lifecycle/run_state backfilled from the legacy status enum", () => {
  const dir = join(tmpdir(), `mrsm-${randomUUID()}`);
  mkdirSync(dir, { recursive: true });
  const dbPath = join(dir, "old.db");
  try {
    // Build a db with the OLD schema (single status enum, no lifecycle).
    const old = new DatabaseSync(dbPath);
    old.exec(`
      CREATE TABLE sessions (
        id TEXT PRIMARY KEY, principal TEXT NOT NULL, purpose TEXT NOT NULL,
        harness TEXT NOT NULL, harness_session_id TEXT, cwd TEXT NOT NULL,
        auth_class TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL,
        created_at INTEGER NOT NULL, last_active INTEGER NOT NULL,
        last_heartbeat INTEGER NOT NULL, title TEXT
      );
    `);
    const ins = old.prepare(`INSERT INTO sessions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`);
    for (const [id, status] of [["a", "live"], ["b", "exited"], ["c", "hung"], ["d", "idle"], ["e", "starting"]]) {
      ins.run(id, "owner", "main", "claude-code", null, "/", "subscription", "text", status, 1, 1, 1, null);
    }
    old.close();

    const m = new SessionManager(dbPath);
    assert.deepEqual(
      ["a", "b", "c", "d", "e"].map((id) => { const r = m.get(id)!; return [r.lifecycle, r.runState]; }),
      [["active", "running"], ["ended", "idle"], ["active", "hung"], ["active", "idle"], ["active", "starting"]],
    );
    // And the derived legacy view matches what the old enum said.
    for (const [id, status] of [["a", "live"], ["b", "exited"], ["c", "hung"], ["d", "idle"], ["e", "starting"]]) {
      assert.equal(m.get(id as string)!.status, status);
    }
    m.close();
    // Re-open: migration must be idempotent (no double backfill, no throw).
    const m2 = new SessionManager(dbPath);
    assert.equal(m2.get("a")!.runState, "running");
    m2.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("R4 fix by construction: a stale-heartbeat IDLE session is NOT a silent failure; a RUNNING one is", () => {
  const m = SessionManager.inMemory();
  m.create("idle-one", spec(), "claude-code", 0);
  m.setRunState("idle-one", "idle", 0);       // waiting for input for hours — fine
  m.create("running-one", spec(), "claude-code", 0);
  m.setRunState("running-one", "running", 0); // mid-turn and silent — the wound
  m.create("waiting-one", spec(), "claude-code", 0);
  m.setRunState("waiting-one", "awaiting_input", 0); // waiting on the USER — fine

  const dead = m.findSilentlyDead(300_000, 120_000);
  assert.deepEqual(dead.map((r) => r.id), ["running-one"]);
  m.close();
});

test("hung self-heals on activity, and the supervisor re-alerts on a RE-hang", () => {
  const m = SessionManager.inMemory();
  m.create("s", spec(), "claude-code", 0);
  m.setRunState("s", "running", 0);

  const alerts: string[] = [];
  let clock = 200_000;
  const sup = new Supervisor(m, { now: () => clock, onSilentFailure: (r) => alerts.push(r.id) });

  sup.tick();
  assert.equal(m.get("s")!.runState, "hung");
  assert.equal(alerts.length, 1);

  // Activity resumes → heartbeat flips hung back to running (field flip, same id).
  m.heartbeat("s", clock);
  assert.equal(m.get("s")!.runState, "running");
  sup.tick(); // latch self-heals now that it's no longer hung
  assert.equal(alerts.length, 1, "healthy again — no re-alert");

  // It hangs AGAIN much later → a fresh alert must fire.
  clock += 300_000;
  sup.tick();
  assert.equal(m.get("s")!.runState, "hung");
  assert.equal(alerts.length, 2, "re-hang re-alerts");
  m.close();
});

// ── Router-level: status.update pushes + exposure ───────────────────────────

function routerHarness() {
  const dir = join(tmpdir(), `mrst-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  let n = 0;
  const adapter = {
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send() {
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: "x" }, session_id: opts.daemonSessionId } });
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "t");
        },
        async interrupt() {}, async stop() {},
      };
    },
  };
  const router = createRouter({
    cfg, sessions, transcripts: new TranscriptCache(dir), usage: new UsageMeter(),
    adapter: adapter as any, today: () => "d", now: () => 1000 + n++, mintSessionId: () => `s_${++n}`,
  });
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  return { router, sessions, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("runState flips are pushed as stamped status.update events: idle → running → idle", async () => {
  const h = routerHarness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "go" }, c);

    const updates = c._sent
      .filter((f: any) => f.params?.type === "status.update")
      .map((f: any) => f.params.payload.run_state);
    assert.deepEqual(updates, ["idle", "running", "idle"], "harness-ready, turn start, turn complete");
    // Stamped like every other session event: ordered in the same seq stream.
    const seqs = c._sent.filter((f: any) => f.params?.type === "status.update").map((f: any) => f.params.payload.seq);
    assert.ok(seqs.every((x: number, i: number) => i === 0 || x > seqs[i - 1]));

    // session.list exposes both fields (+ legacy status for v1 clients).
    const list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    const row = list.sessions.find((r) => r.session_id === s.session_id);
    assert.equal(row.lifecycle, "active");
    assert.equal(row.run_state, "idle");
    assert.equal(row.status, "idle");
  } finally { h.cleanup(); }
});

test("session.stop → lifecycle ended, pushed as status.update", async () => {
  const h = routerHarness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("session.stop", { session_id: s.session_id }, c);
    assert.equal(h.sessions.get(s.session_id)!.lifecycle, "ended");
    assert.equal(h.sessions.get(s.session_id)!.status, "exited");
    const last = c._sent.filter((f: any) => f.params?.type === "status.update").pop();
    assert.equal(last.params.payload.lifecycle, "ended");
  } finally { h.cleanup(); }
});

// ── Lazy-init adapters (the real Claude SDK shape) ──────────────────────────
// The SDK sends no init until the first prompt, so onHarnessSession never
// fires for a created-but-unprompted session. Before the post-spawn flip,
// such a session sat in `starting` until the supervisor false-flagged it
// hung (2026-07-11 Android live verify: a session materialized on open,
// never prompted, alerted at +120s).

function lazyRouterHarness() {
  const dir = join(tmpdir(), `mrst-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  let n = 0;
  const adapter = {
    name: "lazy-fake",
    spawn(_spec: any, opts: any, cb: any) {
      // NO cb.onHarnessSession here — init is lazy, like the Claude SDK.
      return {
        async send() {
          cb.onHarnessSession(`h-${opts.daemonSessionId}`); // init on first turn
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "t");
        },
        async interrupt() {}, async stop() {},
      };
    },
  };
  const router = createRouter({
    cfg, sessions, transcripts: new TranscriptCache(dir), usage: new UsageMeter(),
    adapter: adapter as any, today: () => "d", now: () => 1000 + n++, mintSessionId: () => `s_${++n}`,
  });
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  return { router, sessions, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("a created-but-unprompted session (lazy-init adapter) is idle, not starting — the supervisor must not flag it", async () => {
  const h = lazyRouterHarness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    assert.equal(h.sessions.get(s.session_id)!.runState, "idle", "spawn returned, no turn in flight");

    // The supervisor sees nothing to flag no matter how stale the heartbeat.
    const alerts: string[] = [];
    const sup = new Supervisor(h.sessions, { now: () => 10_000_000, onSilentFailure: (r) => alerts.push(r.id) });
    sup.tick();
    assert.deepEqual(alerts, []);

    // The full turn cycle still works from idle.
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "go" }, c);
    assert.equal(h.sessions.get(s.session_id)!.runState, "idle");
  } finally { h.cleanup(); }
});

test("resume of a never-reprompted session (lazy-init adapter) lands on idle, not starting", async () => {
  const h = lazyRouterHarness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("session.stop", { session_id: s.session_id }, c);
    assert.equal(h.sessions.get(s.session_id)!.lifecycle, "ended");

    const r = (await h.router("session.resume", { session_id: s.session_id }, c)) as { session_id: string };
    assert.equal(r.session_id, s.session_id, "resume never re-mints");
    assert.equal(h.sessions.get(s.session_id)!.lifecycle, "active");
    assert.equal(h.sessions.get(s.session_id)!.runState, "idle", "revive sets starting; post-spawn flip settles it");
  } finally { h.cleanup(); }
});
