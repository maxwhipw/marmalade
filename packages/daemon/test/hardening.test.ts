// Hardening suite ("Daemon remaining items", an internal design note):
//  - concurrent prompt.submit at one live session (seq/identity assumptions)
//  - prompt.submit racing session.stop
//  - PromptQueue push-after-close throws (was a silent drop)
//  - cold-restart resume passes the harness session id to the new spawn

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
import { PromptQueue } from "../dist/claude-code-adapter.js";
import { StatusUpdatePayload, ErrorPayload, SessionDeletedPayload } from "@marmalade/protocol";

/** Minimal adapter that spawns cleanly and captures the router callbacks, so a
 *  test can fire a late `onEvent` (the adapter→router event path) at any time —
 *  including AFTER the session is deleted. */
function captureAdapter() {
  let cb: any;
  return {
    adapter: {
      name: "capture",
      spawn(_spec: any, opts: any, callbacks: any) {
        cb = callbacks;
        callbacks.onHarnessSession(`h-${opts.daemonSessionId}`);
        return { async send() {}, async interrupt() {}, async stop() {} };
      },
    },
    lateEmit: (sid: string, type = "message.delta") =>
      cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type, payload: { text: "late" }, session_id: sid } }),
  };
}

/** A connection that records every frame the router sends it (for asserting on
 *  daemon-originated event payloads). Auto-subscribed as the creating conn. */
function capturingConn() {
  const sent: any[] = [];
  return { conn: { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [] } as any, sent };
}

function framesOfType(sent: any[], type: string): any[] {
  return sent.filter((f) => f?.method === "event" && f?.params?.type === type).map((f) => f.params.payload);
}

/** Fake adapter whose send() blocks until the test releases it — the lever
 *  for racing submits against each other and against session.stop. */
function gatedAdapter(state: { spawnOpts: any[]; sent: string[]; stops: number }) {
  const gates: Array<() => void> = [];
  return {
    adapter: {
      name: "fake-gated",
      spawn(_spec: any, opts: any, cb: any) {
        state.spawnOpts.push(opts);
        cb.onHarnessSession(`h-${opts.daemonSessionId}`);
        return {
          async send(prompt: string) {
            state.sent.push(prompt);
            cb.onActivity();
            await new Promise<void>((resolve) => gates.push(resolve));
            cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: "ok" }, session_id: opts.daemonSessionId } });
            cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
            cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "test");
          },
          async interrupt() {},
          async stop() {
            state.stops++;
          },
        };
      },
    },
    /** Release the oldest blocked send. */
    release: () => gates.shift()?.(),
    pendingSends: () => gates.length,
  };
}

function harness(adapter: any, dbDir?: string) {
  const dir = dbDir ?? join(tmpdir(), `mh-${randomUUID()}`);
  const cfg = { ...defaultConfig(), stateDir: dir };
  const sessions = dbDir ? new SessionManager(defaultDbPath(cfg.stateDir)) : SessionManager.inMemory();
  const transcripts = new TranscriptCache(join(dir, "transcripts"));
  let n = 0;
  const router = createRouter({
    cfg, sessions, transcripts, usage: new UsageMeter(),
    adapter,
    today: () => "2026-07-12", now: () => 1000 + ++n, mintSessionId: () => `s_${++n}`,
  });
  const conn = () => ({ ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any);
  return { router, sessions, transcripts, conn, dir, close: () => sessions.close?.(), cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("two concurrent prompt.submit at one session: distinct ids, strictly increasing seq, both turns complete", async () => {
  const state = { spawnOpts: [] as any[], sent: [] as string[], stops: 0 };
  const g = gatedAdapter(state);
  const h = harness(g.adapter as any);
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };

    const p1 = h.router("prompt.submit", { session_id: s.session_id, prompt: "one" }, c) as Promise<any>;
    const p2 = h.router("prompt.submit", { session_id: s.session_id, prompt: "two" }, c) as Promise<any>;
    // Both sends are in flight (or queued) before either turn completes.
    // Release both; the promises must settle without error.
    while (g.pendingSends() < 1) await new Promise((r) => setTimeout(r, 1));
    g.release();
    const r1 = await p1;
    while (g.pendingSends() < 1) await new Promise((r) => setTimeout(r, 1));
    g.release();
    const r2 = await p2;

    assert.notEqual(r1.message_id, r2.message_id, "each submit minted its own id");
    assert.ok(r2.seq > r1.seq, "user seqs strictly increase across concurrent submits");
    // The transcript's stamped seqs are strictly increasing — no duplicate or
    // backward stamp even with two turns interleaving.
    const seqs = h.transcripts.replay(s.session_id).map((e: any) => e.params.seq).filter((x: any) => typeof x === "number");
    for (let i = 1; i < seqs.length; i++) assert.ok(seqs[i] > seqs[i - 1], `seq monotone at ${i}: ${seqs[i - 1]} → ${seqs[i]}`);
    // Both user messages persisted.
    const users = h.sessions.messages.list(s.session_id).filter((m: any) => m.role === "user");
    assert.equal(users.length, 2);
  } finally { h.cleanup(); }
});

test("prompt.submit racing session.stop: stop wins cleanly, submit settles, no zombie state", async () => {
  const state = { spawnOpts: [] as any[], sent: [] as string[], stops: 0 };
  const g = gatedAdapter(state);
  const h = harness(g.adapter as any);
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };

    // Submit blocks inside harness.send; stop fires while it's in flight.
    const submit = (h.router("prompt.submit", { session_id: s.session_id, prompt: "racing" }, c) as Promise<any>)
      .then(() => "resolved").catch(() => "rejected");
    while (g.pendingSends() < 1) await new Promise((r) => setTimeout(r, 1));
    await h.router("session.stop", { session_id: s.session_id }, c);
    assert.equal(state.stops, 1, "harness.stop ran");

    // Release the gated send; the submit must SETTLE (either way), never hang.
    g.release();
    const outcome = await submit;
    assert.ok(outcome === "resolved" || outcome === "rejected");

    // The user message stays complete (a submitted user message IS complete —
    // P1 mints it that way; closeOpen targets open ASSISTANT messages), and no
    // assistant message is left dangling in "streaming" after the stop.
    const msgs = h.sessions.messages.list(s.session_id);
    assert.equal(msgs.filter((m: any) => m.role === "user")[0].status, "complete");
    assert.ok(!msgs.some((m: any) => m.status === "streaming"), "no message left open after stop");
    // The stop ended the session and released its child…
    assert.equal(h.sessions.get(s.session_id)?.status, "exited");
    // …and a new submit AUTO-REVIVES it (reaping contract: a stopped/reaped
    // session resumes in place on submit — same id, fresh child) rather than
    // rejecting or dropping into a void.
    const after = h.router("prompt.submit", { session_id: s.session_id, prompt: "after" }, c) as Promise<any>;
    while (g.pendingSends() < 1) await new Promise((r) => setTimeout(r, 1));
    g.release();
    await after;
    assert.equal(state.spawnOpts.length, 2, "revive spawned a fresh child");
    assert.equal(state.spawnOpts[1].resumeHarnessSessionId, `h-${s.session_id}`, "revive resumed the recorded harness session");
    assert.equal(h.sessions.get(s.session_id)?.lifecycle, "active");
  } finally { h.cleanup(); }
});

test("PromptQueue: push after close throws (silent drop is a bug in the caller)", async () => {
  const q = new PromptQueue();
  q.push("before close");
  q.close();
  assert.throws(() => q.push("after close"), /closed/);
  // The iterator still drains what was pushed before the close.
  const drained: string[] = [];
  for await (const msg of q) drained.push(String(msg.message.content));
  assert.deepEqual(drained, ["before close"]);
});

test("cold-restart resume hands the recorded harness session id to the new spawn (resume wiring)", async () => {
  const dir = join(tmpdir(), `mh-${randomUUID()}`);
  try {
    // Process 1: create + one turn; the adapter reported h-<id> which the
    // router persists for resume.
    const s1 = { spawnOpts: [] as any[], sent: [] as string[], stops: 0 };
    const g1 = gatedAdapter(s1);
    let h = harness(g1.adapter as any, dir);
    const s = (await h.router("session.create", {}, h.conn())) as { session_id: string };
    const p = h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, h.conn());
    while (g1.pendingSends() < 1) await new Promise((r) => setTimeout(r, 1));
    g1.release();
    await p;
    assert.equal(s1.spawnOpts[0].resumeHarnessSessionId, undefined, "fresh create is not a resume");
    h.close();

    // Process 2 (cold restart): same state dir, fresh router + adapter. The
    // resume spawn must carry the harness id recorded in process 1 — a
    // lifecycle claim that used to be asserted only by hand, now pinned.
    const s2 = { spawnOpts: [] as any[], sent: [] as string[], stops: 0 };
    const g2 = gatedAdapter(s2);
    h = harness(g2.adapter as any, dir);
    const r = (await h.router("session.resume", { session_id: s.session_id }, h.conn())) as { session_id: string };
    assert.equal(r.session_id, s.session_id);
    assert.equal(s2.spawnOpts.length, 1, "resume spawned exactly one harness");
    assert.equal(s2.spawnOpts[0].resumeHarnessSessionId, `h-${s.session_id}`, "SDK resume id round-tripped the restart");
    // And the resumed session still takes prompts.
    const p2 = h.router("prompt.submit", { session_id: s.session_id, prompt: "again" }, h.conn());
    while (g2.pendingSends() < 1) await new Promise((r2) => setTimeout(r2, 1));
    g2.release();
    await p2;
    assert.ok(s2.sent[0].includes("again"));
    h.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("session.interrupt/stop/summary reject malformed params (zod, not a silent '' default)", async () => {
  const cap = captureAdapter();
  const h = harness(cap.adapter as any);
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    // A missing session_id used to cast to "" and fall through; now each method
    // validates its params up front and rejects.
    await assert.rejects(() => h.router("session.interrupt", {}, c), "interrupt with no session_id");
    await assert.rejects(() => h.router("session.stop", {}, c), "stop with no session_id");
    await assert.rejects(() => h.router("session.summary", {}, c), "summary with no session_id");
    // Valid params still work: summary returns the rollup shape.
    const sum = (await h.router("session.summary", { session_id: s.session_id }, c)) as any;
    assert.ok("lifecycle" in sum && "run_state" in sum, "valid session.summary still returns the rollup");
  } finally { h.cleanup(); }
});

test("emit-after-delete: a late harness event does not resurrect the deleted transcript", async () => {
  const cap = captureAdapter();
  const h = harness(cap.adapter as any);
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("session.delete", { session_id: s.session_id }, c);
    // The row + transcript are gone; the live harness (being torn down) fires
    // one more event on the router's per-session emit path.
    cap.lateEmit(s.session_id, "message.delta");
    cap.lateEmit(s.session_id, "message.complete");
    assert.equal(h.sessions.get(s.session_id), undefined, "session stays deleted");
    assert.equal(
      h.transcripts.replay(s.session_id).length, 0,
      "late emit must not append to (recreate) the deleted session's transcript",
    );
  } finally { h.cleanup(); }
});

test("daemon-originated event payloads conform to the protocol schemas (status.update, session.deleted)", async () => {
  const state = { spawnOpts: [] as any[], sent: [] as string[], stops: 0 };
  const g = gatedAdapter(state);
  const h = harness(g.adapter as any);
  try {
    const { conn, sent } = capturingConn();
    const s = (await h.router("session.create", {}, conn)) as { session_id: string };
    const p = h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, conn) as Promise<any>;
    while (g.pendingSends() < 1) await new Promise((r) => setTimeout(r, 1));
    g.release();
    await p;

    const statusFrames = framesOfType(sent, "status.update");
    assert.ok(statusFrames.length > 0, "the turn pushed at least one status.update");
    for (const payload of statusFrames) StatusUpdatePayload.parse(payload); // throws on drift

    await h.router("session.delete", { session_id: s.session_id }, conn);
    const deletedFrames = framesOfType(sent, "session.deleted");
    assert.equal(deletedFrames.length, 1, "delete broadcast exactly one session.deleted");
    SessionDeletedPayload.parse(deletedFrames[0]);
  } finally { h.cleanup(); }
});

test("daemon-originated error payload conforms to the protocol schema", async () => {
  const throwingAdapter = { name: "boom", spawn() { throw new Error("spawn exploded"); } };
  const h = harness(throwingAdapter as any);
  try {
    const { conn, sent } = capturingConn();
    await h.router("session.create", {}, conn);
    const errorFrames = framesOfType(sent, "error");
    assert.ok(errorFrames.length > 0, "a failed spawn emits an error event");
    for (const payload of errorFrames) ErrorPayload.parse(payload); // throws on drift
    assert.match(errorFrames[0].message, /exploded/);
  } finally { h.cleanup(); }
});
