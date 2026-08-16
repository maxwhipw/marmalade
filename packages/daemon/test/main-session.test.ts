// The singleton main session (assistant plan 2026-07-19): daemon-managed,
// always warm, never deleted — plus session.clear / session.model.

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

function fakeAdapter() {
  const spawns: { id: string; opts: any }[] = [];
  return {
    spawns,
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      spawns.push({ id: opts.daemonSessionId, opts });
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n\n/, "");
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1, text: `echo:${prompt}` }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harness(cfgOverrides: Record<string, unknown> = {}) {
  const dir = join(tmpdir(), `mr-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const usage = new UsageMeter();
  const adapter = fakeAdapter();
  let n = 0;
  const router = createRouter({
    cfg: { ...defaultConfig(), ...cfgOverrides },
    sessions,
    transcripts,
    usage,
    adapter: adapter as any,
    today: () => "2026-07-19",
    now: () => 1000 + ++n,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  return { router, sessions, transcripts, adapter, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("ensureMain is a singleton: created once, purpose=main, session.main returns the same id", async () => {
  const h = harness();
  try {
    const [a, b] = await Promise.all([h.router.ensureMain(), h.router.ensureMain()]);
    assert.equal(a, b, "concurrent ensureMain never double-creates");
    const viaRpc = (await h.router("session.main", {}, h.conn())) as { session_id: string };
    assert.equal(viaRpc.session_id, a);
    const rec = h.sessions.get(a)!;
    assert.equal(rec.purpose, "main");
    assert.equal(rec.title, "Marmalade");
    // Exactly one spawn for the main session.
    assert.equal(h.adapter.spawns.filter((s) => s.id === a).length, 1);
  } finally { h.cleanup(); }
});

test("client-created sessions are purpose=coding; session.list marks is_main on exactly the main", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    assert.equal(h.sessions.get(s.session_id)!.purpose, "coding");
    const list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    assert.equal(list.sessions.find((r) => r.session_id === mainId).is_main, true);
    assert.equal(list.sessions.find((r) => r.session_id === s.session_id).is_main, false);
  } finally { h.cleanup(); }
});

test("main cannot be deleted or stopped; other sessions still can", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    await assert.rejects(() => h.router("session.delete", { session_id: mainId }, c), /daemon-managed/);
    await assert.rejects(() => h.router("session.stop", { session_id: mainId }, c), /stays warm/);
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("session.delete", { session_id: s.session_id }, c); // no throw
  } finally { h.cleanup(); }
});

test("the idle reaper and cap eviction skip the main session", async () => {
  const h = harness({ idleReapMs: 0, maxLiveSessions: 2 });
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    const reaped = await h.router.reapIdle();
    assert.ok(reaped.includes(s.session_id), "idle coding session reaped");
    assert.ok(!reaped.includes(mainId), "main never reaped");
    // Cap eviction: with main + one new session at the cap, creating another
    // evicts the idle coding session, never main.
    const s2 = (await h.router("session.create", {}, c)) as { session_id: string };
    const s3 = (await h.router("session.create", {}, c)) as { session_id: string };
    assert.ok(s2.session_id && s3.session_id);
    assert.equal(h.sessions.get(mainId)!.lifecycle, "active", "main survived the cap squeeze");
  } finally { h.cleanup(); }
});

test("ensureMain after a daemon restart revives the SAME main: no re-mint", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    // Simulate a restart: orphans marked exited, fresh router (fresh live
    // map) over the SAME stores — exactly index.ts's boot sequence.
    h.sessions.markOrphansExited(5000);
    assert.equal(h.sessions.get(mainId)!.lifecycle, "ended");
    const router2 = createRouter({
      cfg: defaultConfig(),
      sessions: h.sessions,
      transcripts: h.transcripts,
      usage: new UsageMeter(),
      adapter: h.adapter as any,
      today: () => "2026-07-19",
      now: () => 99_000,
      mintSessionId: () => `s_fresh_${randomUUID()}`,
    });
    const again = await router2.ensureMain();
    assert.equal(again, mainId, "resume, never re-mint");
    assert.equal(h.sessions.get(mainId)!.lifecycle, "active");
  } finally { h.cleanup(); }
});

test("session.clear resets the conversation in place: same id, wiped history, seq never reissued, main respawns warm", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    await h.router("session.resume", { session_id: mainId }, c);
    const r1 = (await h.router("prompt.submit", { session_id: mainId, prompt: "before clear" }, c)) as { seq: number };
    assert.ok(h.sessions.messages.list(mainId).length > 0);
    const res = (await h.router("session.clear", { session_id: mainId }, c)) as { cleared: boolean };
    assert.equal(res.cleared, true);
    assert.equal(h.sessions.messages.list(mainId).length, 0, "message rows wiped");
    // The respawn appends fresh lifecycle events, but no conversation remains.
    assert.ok(
      !h.transcripts.replay(mainId).some((e) => String(e.params.type).startsWith("message.")),
      "no conversation events survive the clear",
    );
    assert.ok(!h.transcripts.replay(mainId).some((e) => JSON.stringify(e).includes("before clear")));
    const rec = h.sessions.get(mainId)!;
    assert.equal(rec.harnessSessionId, `h-${mainId}`, "main respawned warm (fresh harness bound)");
    assert.equal(rec.topic, null);
    // Subscribers were told, transiently.
    assert.ok((c as any)._sent.some((f: any) => f.params?.type === "session.cleared"));
    // Seq continues past the pre-clear high water (P1: no reuse).
    const r2 = (await h.router("prompt.submit", { session_id: mainId, prompt: "after clear" }, c)) as { seq: number };
    assert.ok(r2.seq > r1.seq, `post-clear seq ${r2.seq} > pre-clear ${r1.seq}`);
  } finally { h.cleanup(); }
});

test("session.model persists the model and restarts an idle live child with it", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    const res = (await h.router("session.model", { session_id: mainId, model: "claude-fable-5" }, c)) as { model: string };
    assert.equal(res.model, "claude-fable-5");
    assert.equal(h.sessions.get(mainId)!.model, "claude-fable-5");
    const last = h.adapter.spawns.filter((s) => s.id === mainId).at(-1)!;
    assert.equal(last.opts.model, "claude-fable-5", "respawn carried the new model");
    assert.equal(last.opts.resumeHarnessSessionId, `h-${mainId}`, "context carried via harness resume");
  } finally { h.cleanup(); }
});

test("session.effort persists the effort and restarts an idle live child with it", async () => {
  const h = harness({ defaultEffort: "high" });
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    assert.equal(h.sessions.get(mainId)!.reasoningEffort, "high", "create stamped the config default");
    const res = (await h.router("session.effort", { session_id: mainId, reasoning_effort: "medium" }, c)) as
      { reasoning_effort: string };
    assert.equal(res.reasoning_effort, "medium");
    assert.equal(h.sessions.get(mainId)!.reasoningEffort, "medium", "stored on the row, so resume re-applies it");
    const last = h.adapter.spawns.filter((s) => s.id === mainId).at(-1)!;
    assert.equal(last.opts.effort, "medium", "respawn carried the new effort — the turn actually runs at it");
    assert.equal(last.opts.resumeHarnessSessionId, `h-${mainId}`, "context carried via harness resume");
  } finally { h.cleanup(); }
});

test("session.effort rejects an unknown level and a mid-turn change", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await assert.rejects(
      () => h.router("session.effort", { session_id: s.session_id, reasoning_effort: "off" }, c),
      /not one of/,
      "the legacy client's \"off\" is not a level this daemon runs",
    );
    await assert.rejects(
      () => h.router("session.effort", { session_id: "nope", reasoning_effort: "low" }, c),
      /unknown session/,
    );
    // Mid-turn: same guard as session.model — a change now would restart the
    // child under a running turn.
    h.sessions.setRunState(s.session_id, "running", 2000);
    await assert.rejects(
      () => h.router("session.effort", { session_id: s.session_id, reasoning_effort: "low" }, c),
      /turn in flight/,
    );
  } finally { h.cleanup(); }
});
