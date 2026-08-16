import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager, defaultDbPath } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";

// Resume MUST be tested against a PERSISTENT (file) db, not :memory: — the H1
// crash (re-INSERT of an existing PK) only reproduces when the row survives.
// Every prior test used inMemory(), which is exactly why H1 shipped.

function fakeAdapter(spawns: string[]) {
  return {
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      spawns.push(opts.daemonSessionId);
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send(p: string) {
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${p}` }, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harnessOverDb(dbDir: string, spawns: string[]) {
  const cfg = { ...defaultConfig(), stateDir: dbDir };
  const sessions = new SessionManager(defaultDbPath(cfg.stateDir));
  const transcripts = new TranscriptCache(join(dbDir, "transcripts"));
  let n = 0;
  const router = createRouter({
    cfg, sessions, transcripts, usage: new UsageMeter(),
    adapter: fakeAdapter(spawns) as any,
    today: () => "2026-07-11", now: () => 1000 + n, mintSessionId: () => `s_${++n}`,
  });
  const conn = () => ({ ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any);
  return { router, sessions, conn, close: () => sessions.close() };
}

test("resume round-trips across a fresh router over the same db (H1 — no crash)", async () => {
  const dir = join(tmpdir(), `mrs-${randomUUID()}`);
  const spawns: string[] = [];
  try {
    // First "process": create a session, set a summary.
    let h = harnessOverDb(dir, spawns);
    const s = (await h.router("session.create", {}, h.conn())) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, h.conn());
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "SUMMARY-DIRECT" }, h.conn());
    h.sessions.setSummary(s.session_id, { topic: "resume test", summary: "open: verify resume" }, 5000);
    h.close();

    // Second "process": new router, SAME db file. Resume must not throw.
    h = harnessOverDb(dir, spawns);
    const r = (await h.router("session.resume", { session_id: s.session_id }, h.conn())) as { session_id: string };
    assert.equal(r.session_id, s.session_id);

    // The summary SURVIVED the resume (the H1×summary trap — reopen-and-remember).
    const sum = (await h.router("session.summary", { session_id: s.session_id }, h.conn())) as any;
    assert.equal(sum.topic, "resume test");
    assert.match(sum.summary, /verify resume/);
    h.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("resume-while-live returns the existing session, does not double-spawn (S2)", async () => {
  const dir = join(tmpdir(), `mrs-${randomUUID()}`);
  const spawns: string[] = [];
  try {
    const h = harnessOverDb(dir, spawns);
    const s = (await h.router("session.create", {}, h.conn())) as { session_id: string };
    const spawnsAfterCreate = spawns.length;
    // Session is still live — resuming it must NOT spawn a second child.
    const r = (await h.router("session.resume", { session_id: s.session_id }, h.conn())) as { session_id: string };
    assert.equal(r.session_id, s.session_id);
    assert.equal(spawns.length, spawnsAfterCreate); // no new spawn
    h.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("P1: message ids survive a daemon restart unchanged; seq continues after resume (never backward)", async () => {
  const dir = join(tmpdir(), `mrs-${randomUUID()}`);
  try {
    // Process 1: one full turn.
    let h = harnessOverDb(dir, []);
    const s = (await h.router("session.create", {}, h.conn())) as { session_id: string };
    const r1 = (await h.router("prompt.submit", { session_id: s.session_id, prompt: "first" }, h.conn())) as { message_id: string; seq: number };
    const idsBefore = h.sessions.messages.list(s.session_id).map((m) => m.messageId);
    const transcripts1 = new TranscriptCache(join(dir, "transcripts"));
    const maxWireSeq = transcripts1.lastSeq(s.session_id);
    assert.ok(maxWireSeq > r1.seq, "assistant events stamped past the user seq");
    h.close();

    // Process 2: same db + transcript dir. Resume.
    h = harnessOverDb(dir, []);
    await h.router("session.resume", { session_id: s.session_id }, h.conn());
    assert.deepEqual(
      h.sessions.messages.list(s.session_id).map((m) => m.messageId).slice(0, idsBefore.length),
      idsBefore,
      "resume rebinds the harness underneath — domain message ids never change",
    );
    const r2 = (await h.router("prompt.submit", { session_id: s.session_id, prompt: "second" }, h.conn())) as { message_id: string; seq: number };
    assert.ok(r2.seq > maxWireSeq, "seq seeded from BOTH stores — continues past every stamped event");
    assert.ok(!idsBefore.includes(r2.message_id), "new message, new id");
    h.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("session.stop removes the session and marks it exited; a later submit auto-revives", async () => {
  const dir = join(tmpdir(), `mrs-${randomUUID()}`);
  const spawns: string[] = [];
  try {
    const h = harnessOverDb(dir, spawns);
    const s = (await h.router("session.create", {}, h.conn())) as { session_id: string };
    await h.router("session.stop", { session_id: s.session_id }, h.conn());
    assert.equal(h.sessions.get(s.session_id)?.status, "exited");
    // prompt.submit to a stopped session auto-revives it in place (reaping
    // contract): same id, a second spawn, and the submit lands normally.
    const ack = (await h.router("prompt.submit", { session_id: s.session_id, prompt: "x" }, h.conn())) as { message_id: string };
    assert.ok(ack.message_id);
    assert.deepEqual(spawns, [s.session_id, s.session_id]);
    assert.equal(h.sessions.get(s.session_id)?.lifecycle, "active");
    // An UNKNOWN session id still rejects loudly — nothing to revive.
    await assert.rejects(() => h.router("prompt.submit", { session_id: "s_nope", prompt: "x" }, h.conn()));
    h.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
