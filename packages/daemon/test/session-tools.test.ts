// The cross-session toolset (assistant plan 2026-07-19): list/turns/send/
// steer/interrupt/watch via the AdapterCallbacks.sessionTools seam, with the
// one-hop loop guard and the watch→main digest.

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
  const cbs = new Map<string, any>();
  return {
    cbs,
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      cbs.set(opts.daemonSessionId, cb);
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n\n/, "");
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          if (prompt.includes("USETOOL")) {
            cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "tool.start", payload: { id: "t1", name: "Bash", input: { command: "ls" } }, session_id: opts.daemonSessionId } });
            cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "tool.complete", payload: { tool_use_id: "t1" }, session_id: opts.daemonSessionId } });
          }
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1, text: `echo:${prompt}` }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harness() {
  const dir = join(tmpdir(), `mr-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const adapter = fakeAdapter();
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    usage: new UsageMeter(),
    adapter: adapter as any,
    today: () => "2026-07-19",
    now: () => 1000 + ++n,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  const settle = async () => { for (let i = 0; i < 20; i++) await new Promise((r) => setImmediate(r)); };
  return { router, sessions, transcripts, adapter, conn, settle, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("list_sessions reports is_main, run_state and metadata for every session", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    const s = (await h.router("session.create", { title: "build run" }, c)) as { session_id: string };
    const tools = h.adapter.cbs.get(mainId)!.sessionTools;
    const rows = tools.listSessions();
    const mainRow = rows.find((r: any) => r.session_id === mainId);
    const otherRow = rows.find((r: any) => r.session_id === s.session_id);
    assert.equal(mainRow.is_main, true);
    assert.equal(otherRow.is_main, false);
    assert.equal(otherRow.title, "build run");
    assert.equal(otherRow.run_state, "idle");
    assert.ok(otherRow.cwd);
  } finally { h.cleanup(); }
});

test("get_session_turns renders user/assistant text; tool calls only on opt-in; unknown session errors", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "please USETOOL now" }, c);
    const tools = h.adapter.cbs.get(mainId)!.sessionTools;
    const plain = tools.getSessionTurns(s.session_id, { turns: 3, includeToolCalls: false, includeThinking: false });
    assert.match(plain, /\[user\] please USETOOL now/);
    assert.match(plain, /\[assistant\] echo:/);
    assert.ok(!plain.includes("[tool]"), "tool calls excluded by default");
    const withTools = tools.getSessionTurns(s.session_id, { turns: 3, includeToolCalls: true, includeThinking: false });
    assert.match(withTools, /\[tool\] Bash/);
    assert.throws(() => tools.getSessionTurns("nope", { turns: 1, includeToolCalls: false, includeThinking: false }), /unknown session/);
  } finally { h.cleanup(); }
});

test("send_to_session queues an agent-origin turn in the target; the target's agent turn cannot chain (one hop)", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    const mainTools = h.adapter.cbs.get(mainId)!.sessionTools;
    const msg = await mainTools.sendToSession(s.session_id, "continue the build");
    assert.match(msg, /queued/);
    await h.settle();
    // The target ran the turn, and its transcript records source=agent from the sender.
    const userEv = h.transcripts.replay(s.session_id).find((e) => e.params.type === "message.user")!;
    const origin = (userEv.params.payload as any).origin;
    assert.equal(origin.source, "agent");
    assert.equal(origin.device_id, `session:${mainId}`);
    assert.ok(h.transcripts.replay(s.session_id).some((e) => JSON.stringify(e).includes("continue the build")));
    // Loop guard: the target's current turn origin is now agent — its own
    // send/steer/interrupt refuse to chain.
    const targetTools = h.adapter.cbs.get(s.session_id)!.sessionTools;
    await assert.rejects(() => targetTools.sendToSession(mainId, "ping back"), /one hop/);
    await assert.rejects(() => targetTools.interruptSession(mainId), /one hop/);
  } finally { h.cleanup(); }
});

test("self-target and steering an idle session are refused with usable messages", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    const tools = h.adapter.cbs.get(mainId)!.sessionTools;
    await assert.rejects(() => tools.sendToSession(mainId, "hi"), /THIS session/);
    await assert.rejects(() => tools.steerSession(s.session_id, "adjust"), /no turn in flight/);
  } finally { h.cleanup(); }
});

test("watch_session: one-shot digest lands in the main session when the watched turn completes", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    const s = (await h.router("session.create", { title: "release build" }, c)) as { session_id: string };
    const mainTools = h.adapter.cbs.get(mainId)!.sessionTools;
    const ack = mainTools.watchSession(s.session_id, "check the test count");
    assert.match(ack, /Watching/);
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "run it" }, c);
    await h.settle();
    const mainEvents = h.transcripts.replay(mainId);
    const digest = mainEvents.find((e) => e.params.type === "message.user" && JSON.stringify(e).includes("[session watch]"));
    assert.ok(digest, "digest turn arrived in main");
    const text = (digest!.params.payload as any).text as string;
    assert.match(text, /release build/);
    assert.match(text, /check the test count/);
    assert.match(text, /Last reply: echo:/);
    // One-shot: a second turn does not fire again.
    const before = h.transcripts.replay(mainId).filter((e) => JSON.stringify(e).includes("[session watch]")).length;
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "again" }, c);
    await h.settle();
    const after = h.transcripts.replay(mainId).filter((e) => JSON.stringify(e).includes("[session watch]")).length;
    assert.equal(after, before, "watch fired exactly once");
  } finally { h.cleanup(); }
});

test("watching the main session is refused (its digests land in itself)", async () => {
  const h = harness();
  try {
    const mainId = await h.router.ensureMain();
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    const tools = h.adapter.cbs.get(s.session_id)!.sessionTools;
    assert.throws(() => tools.watchSession(mainId), /cannot be watched/);
  } finally { h.cleanup(); }
});
