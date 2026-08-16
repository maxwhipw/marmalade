// fork.test.ts — session.fork (T2 #3, the "both" decision of 2026-07-18):
// harness-native fork for end AND mid-point cuts; new daemon session with
// copied transcript/identity (NEW message ids), lineage marker, ended/
// resumable shape (first prompt.submit auto-revives on the forked harness
// id). Guards: mid-turn, no harness state, non-assistant cut, no-fork
// harness.

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
import { FORK_UNSUPPORTED_REASON } from "@marmalade/protocol";

/** Fake adapter with fork support: each assistant turn binds a harness uuid
 *  (`hu-<n>`); forkSession records its args and returns a fresh harness id. */
function forkableAdapter(state: {
  spawns: Array<{ daemonSessionId: string; resume?: string }>;
  forks: Array<{ harnessSessionId: string; cwd: string; upToHarnessUuid?: string; title?: string }>;
  uuidN: number;
}) {
  return {
    name: "fake",
    async forkSession(harnessSessionId: string, opts: { cwd: string; upToHarnessUuid?: string; title?: string }) {
      state.forks.push({ harnessSessionId, ...opts });
      return { harnessSessionId: `h-forked-${state.forks.length}`, warning: "no undo history" };
    },
    spawn(_spec: unknown, opts: { daemonSessionId: string; resumeHarnessSessionId?: string }, cb: any) {
      state.spawns.push({ daemonSessionId: opts.daemonSessionId, resume: opts.resumeHarnessSessionId });
      cb.onHarnessSession(opts.resumeHarnessSessionId ?? `h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n\n/, "");
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          cb.onHarnessMessageUuid(`hu-${++state.uuidN}`);
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
  const dir = join(tmpdir(), `fk-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const state = { spawns: [], forks: [], uuidN: 0 } as Parameters<typeof forkableAdapter>[0];
  let n = 0;
  const now = { t: 1000 };
  const router = createRouter({
    cfg: defaultConfig({}),
    sessions, transcripts, usage: new UsageMeter(),
    adapter: forkableAdapter(state) as any,
    today: () => "2026-07-18",
    now: () => ++now.t,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = () => ({ ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any);
  return { router, sessions, transcripts, state, conn, cleanup: () => { sessions.close(); rmSync(dir, { recursive: true, force: true }); } };
}

test("end fork: new session, lineage, copied transcript with REMAPPED ids, resumable on the forked harness id", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);

    const r = (await h.router("session.fork", { session_id, title: "branch A" }, c)) as any;
    assert.notEqual(r.session_id, session_id);
    assert.deepEqual(r.forked_from, { session_id, message_id: null });
    assert.equal(r.full_context, true);
    assert.match(r.warning, /undo/);
    assert.equal(h.state.forks[0]!.harnessSessionId, `h-${session_id}`);
    assert.equal(h.state.forks[0]!.title, "branch A");
    assert.equal(h.state.forks[0]!.upToHarnessUuid, undefined, "end fork passes no cut");

    // Lineage + title on the row; fork is ended (resumable), not live.
    const rec = h.sessions.get(r.session_id)!;
    assert.equal(rec.branchedFromSessionId, session_id);
    assert.equal(rec.lifecycle, "ended");
    assert.equal(rec.harnessSessionId, "h-forked-1");
    assert.equal(rec.title, "branch A");

    // Transcript copied: both turns render; every message id is NEW.
    const srcIds = new Set(h.sessions.messages.list(session_id).map((m) => m.messageId));
    const copied = h.sessions.messages.list(r.session_id);
    assert.equal(copied.length, h.sessions.messages.list(session_id).length);
    for (const m of copied) assert.ok(!srcIds.has(m.messageId), "message ids must be re-minted");
    const events = h.transcripts.replay(r.session_id);
    assert.ok(events.some((e: any) => e.params.payload?.text === "echo:one"));
    assert.ok(events.some((e: any) => e.params.payload?.text === "echo:two"));
    for (const e of events) assert.equal((e as any).params.session_id, r.session_id);

    // First prompt into the fork auto-revives it ON the forked harness id.
    await h.router("prompt.submit", { session_id: r.session_id, prompt: "three" }, c);
    const revive = h.state.spawns.find((s) => s.daemonSessionId === r.session_id)!;
    assert.equal(revive.resume, "h-forked-1");
    // And the source session is untouched.
    assert.equal(h.sessions.get(session_id)!.lifecycle, "active");
  } finally { h.cleanup(); }
});

test("mid-point fork: cut at an assistant message passes its harness uuid and truncates the copy", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);
    // The FIRST assistant message (answered "one") is the cut.
    const firstAssistant = h.sessions.messages.list(session_id).find((m) => m.role === "assistant")!;
    assert.equal(firstAssistant.harnessMessageUuid, "hu-1");

    const r = (await h.router("session.fork", { session_id, at_message_id: firstAssistant.messageId }, c)) as any;
    assert.equal(r.forked_from.message_id, firstAssistant.messageId);
    assert.equal(h.state.forks[0]!.upToHarnessUuid, "hu-1");

    // Copied history stops at the cut: turn one present, turn two absent.
    const events = h.transcripts.replay(r.session_id);
    assert.ok(events.some((e: any) => e.params.payload?.text === "echo:one"));
    assert.ok(!events.some((e: any) => e.params.payload?.text === "echo:two"));
    const copied = h.sessions.messages.list(r.session_id);
    assert.equal(copied.length, 2, "user one + assistant one only");
    assert.ok(copied.every((m) => m.seq <= firstAssistant.seq));
  } finally { h.cleanup(); }
});

test("guards: unknown session, non-assistant cut, foreign message, no harness state, running turn", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    const userMsg = h.sessions.messages.list(session_id).find((m) => m.role === "user")!;

    await assert.rejects(h.router("session.fork", { session_id: "s_nope" }, c), /unknown session/);
    await assert.rejects(h.router("session.fork", { session_id, at_message_id: userMsg.messageId }, c), /assistant/);
    await assert.rejects(h.router("session.fork", { session_id, at_message_id: "m_nope" }, c), /not found/);

    // A session mid-turn refuses to fork (half-written transcript).
    h.sessions.setRunState(session_id, "running", 99_999);
    await assert.rejects(h.router("session.fork", { session_id }, c), /turn in flight/);
    h.sessions.setRunState(session_id, "idle", 99_999);

    // Fresh session that never ran: no harness binding? Our fake binds on
    // spawn, so simulate by nulling it.
    const b = (await h.router("session.create", { cols: 80 }, c)) as any;
    h.sessions.bindHarnessSession(b.session_id, null as never);
    await assert.rejects(h.router("session.fork", { session_id: b.session_id }, c), /no harness state/);
  } finally { h.cleanup(); }
});

test("a harness without fork support rejects with the seed-create fallback message", async () => {
  const dir = join(tmpdir(), `fk2-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  try {
    let n = 0;
    const adapter = {
      name: "nofork",
      spawn(_s: unknown, opts: { daemonSessionId: string }, cb: any) {
        cb.onHarnessSession(`h-${opts.daemonSessionId}`);
        return { async send() { cb.onResult({ totalCostUsd: 0, inputTokens: 0, outputTokens: 0 }, "t"); }, async interrupt() {}, async stop() {} };
      },
    };
    const router = createRouter({
      cfg: defaultConfig({}), sessions, transcripts: new TranscriptCache(dir),
      usage: new UsageMeter(), adapter: adapter as any, today: () => "2026-07-18",
      now: () => 1, mintSessionId: () => `s_${++n}`,
    });
    const c = { ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any;
    const { session_id } = (await router("session.create", { cols: 80 }, c)) as any;
    // The CONTRACT is error.data.reason (clients branch on it); the human
    // message only needs the legacy "cannot fork" phrase for old clients'
    // substring fallback. Pin both.
    await assert.rejects(
      router("session.fork", { session_id }, c),
      (err: unknown) => {
        const e = err as { message: string; data?: { reason?: string } };
        assert.match(e.message, /cannot fork/);
        assert.equal(e.data?.reason, FORK_UNSUPPORTED_REASON);
        return true;
      },
    );
  } finally { sessions.close(); rmSync(dir, { recursive: true, force: true }); }
});

test("has_cut_point: true on live message.complete, false on fork-copied events", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);

    // Live turn: the fake adapter bound a harness uuid, so the stamped
    // message.complete advertises the cut point.
    const srcComplete = h.transcripts.replay(session_id)
      .find((e: any) => e.params.type === "message.complete") as any;
    assert.equal(srcComplete.params.payload.has_cut_point, true);

    // Fork copy: same event in the fork's transcript is flipped to false —
    // copied rows carry no harness uuids, so the affordance is a dead end.
    const r = (await h.router("session.fork", { session_id }, c)) as any;
    const copiedComplete = h.transcripts.replay(r.session_id)
      .find((e: any) => e.params.type === "message.complete") as any;
    assert.equal(copiedComplete.params.payload.has_cut_point, false);
  } finally { h.cleanup(); }
});

test("has_cut_point: false when the harness never reports per-message uuids", async () => {
  const dir = join(tmpdir(), `fk3-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  try {
    let n = 0;
    // ACP-shaped adapter: streams a turn but never calls onHarnessMessageUuid.
    const adapter = {
      name: "no-uuids",
      spawn(_s: unknown, opts: { daemonSessionId: string }, cb: any) {
        cb.onHarnessSession(`h-${opts.daemonSessionId}`);
        return {
          async send() {
            cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: "hi" }, session_id: opts.daemonSessionId } });
            cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
            cb.onResult({ totalCostUsd: 0, inputTokens: 0, outputTokens: 0 }, "t");
          },
          async interrupt() {}, async stop() {},
        };
      },
    };
    const router = createRouter({
      cfg: defaultConfig({}), sessions, transcripts,
      usage: new UsageMeter(), adapter: adapter as any, today: () => "2026-07-18",
      now: () => 1, mintSessionId: () => `s_${++n}`,
    });
    const c = { ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any;
    const { session_id } = (await router("session.create", { cols: 80 }, c)) as any;
    await router("prompt.submit", { session_id, prompt: "one" }, c);
    const complete = transcripts.replay(session_id)
      .find((e: any) => e.params.type === "message.complete") as any;
    assert.equal(complete.params.payload.has_cut_point, false);
  } finally { sessions.close(); rmSync(dir, { recursive: true, force: true }); }
});

test("session.list exposes branched_from on the fork, null elsewhere", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    const r = (await h.router("session.fork", { session_id }, c)) as any;
    const { sessions } = (await h.router("session.list", {}, c)) as any;
    const fork = sessions.find((s: any) => s.session_id === r.session_id);
    const orig = sessions.find((s: any) => s.session_id === session_id);
    assert.deepEqual(fork.branched_from, { session_id, message_id: null });
    assert.equal(orig.branched_from, null);
  } finally { h.cleanup(); }
});
