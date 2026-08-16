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

// A failure MUST become a client-visible error + terminal status + removal
// from the live map — never eternal silence (the OpenClaw wound). This tests
// that whole lifecycle as a class (R2 init hang, R3 drain-death, R7 bad cwd,
// M3 no-error-event all converge here).

function harness(adapter: any) {
  const dir = join(tmpdir(), `mfl-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const cleared: string[] = [];
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(), sessions, transcripts, usage: new UsageMeter(),
    adapter, today: () => "2026-07-11", now: () => 1000 + n++, mintSessionId: () => `s_${n}`,
    supervisor: { clear: (id: string) => cleared.push(id) },
  });
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  return { router, sessions, conn, cleared, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("a spawn that throws → client gets an error event + terminal status, live map cleaned", async () => {
  const throwingAdapter = { name: "boom", spawn() { throw new Error("spawn exploded"); } };
  const h = harness(throwingAdapter);
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    // Client saw an error event (not silence).
    const err = c._sent.find((f: any) => f.params?.type === "error");
    assert.ok(err, "expected an error event to reach the client");
    assert.match(err.params.payload.message, /exploded/);
    // Terminal status; and a follow-up prompt is rejected (not misrouted into a void).
    assert.equal(h.sessions.get(s.session_id)?.status, "exited");
    await assert.rejects(() => h.router("prompt.submit", { session_id: s.session_id, prompt: "x" }, c));
    assert.ok(h.cleared.includes(s.session_id)); // supervisor latch cleared
  } finally { h.cleanup(); }
});

test("a mid-stream onError → error surfaced, session marked exited", async () => {
  const erroringAdapter = {
    name: "mid",
    spawn(_spec: any, _opts: any, cb: any) {
      cb.onHarnessSession("h1");
      return {
        async send() { cb.onError("stream_error", "connection reset mid-turn"); },
        async interrupt() {}, async stop() {},
      };
    },
  };
  const h = harness(erroringAdapter);
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "go" }, c);
    const err = c._sent.find((f: any) => f.params?.type === "error");
    assert.ok(err);
    assert.match(err.params.payload.message, /connection reset/);
    assert.equal(h.sessions.get(s.session_id)?.status, "exited");
  } finally { h.cleanup(); }
});

test("P1: a mid-stream failure closes the open message as status=error over a FILE db — id persists", async () => {
  // The adapter starts streaming a message, then dies. The message row must
  // keep its id and record the failure — never vanish or get a new id.
  const adapter = {
    name: "dies-mid-message",
    spawn(_spec: any, opts: any, cb: any) {
      cb.onHarnessSession("h1");
      return {
        async send() {
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.start", payload: {}, session_id: opts.daemonSessionId } });
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: "partial…" }, session_id: opts.daemonSessionId } });
          cb.onError("stream_error", "child died mid-message");
        },
        async interrupt() {}, async stop() {},
      };
    },
  };
  const dir = join(tmpdir(), `mfl-${randomUUID()}`);
  try {
    const sessions = new SessionManager(join(dir, "sessions.db"));
    let n = 0;
    const router = createRouter({
      cfg: defaultConfig(), sessions, transcripts: new TranscriptCache(join(dir, "t")), usage: new UsageMeter(),
      adapter: adapter as any, today: () => "d", now: () => 1000 + n++, mintSessionId: () => `s_${n}`,
    });
    const sent: any[] = [];
    const conn = { ws: { send: (x: string) => sent.push(JSON.parse(x)) }, principal: "owner", legacy: false, capabilities: [] } as any;
    const s = (await router("session.create", {}, conn)) as { session_id: string };
    const r = (await router("prompt.submit", { session_id: s.session_id, prompt: "go" }, conn)) as { message_id: string };

    const rows = sessions.messages.list(s.session_id);
    assert.equal(rows.length, 2, "user + assistant rows persisted");
    const assistant = rows.find((m) => m.role === "assistant")!;
    assert.equal(assistant.status, "error", "failure recorded on the row, id unchanged");
    assert.equal(assistant.parentMessageId, r.message_id, "threaded to the user message");
    // The delta the client DID receive names the same message id — a
    // reconnecting client can reconcile the partial bubble.
    const delta = sent.find((f) => f.params?.type === "message.delta");
    assert.equal(delta.params.payload.message_id, assistant.messageId);
    // And the error event itself is stamped into the same seq stream.
    const err = sent.find((f) => f.params?.type === "error");
    assert.ok(err.params.payload.seq > delta.params.payload.seq);
    sessions.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("a disk error in transcript append does NOT kill the drain loop (R3)", async () => {
  // onEvent's append is guarded; a throwing transcript must not stop delivery.
  let delivered = 0;
  const adapter = {
    name: "ok",
    spawn(_spec: any, _opts: any, cb: any) {
      cb.onHarnessSession("h1");
      return {
        async send() {
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: "a" }, session_id: "x" } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "t");
        },
        async interrupt() {}, async stop() {},
      };
    },
  };
  const h = harness(adapter);
  // Sabotage the transcript cache so append throws.
  (h as any).router; // no-op ref
  const badTranscripts = { append() { throw new Error("ENOSPC"); }, replay: () => [] };
  // Rebuild router with a throwing transcript cache.
  const sessions = SessionManager.inMemory();
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(), sessions, transcripts: badTranscripts as any, usage: new UsageMeter(),
    adapter, today: () => "d", now: () => n++, mintSessionId: () => `s_${n}`,
  });
  const sent: any[] = [];
  const conn = { ws: { send: (s: string) => { sent.push(JSON.parse(s)); delivered++; } }, principal: "owner", legacy: false, capabilities: [] } as any;
  const s = (await router("session.create", {}, conn)) as { session_id: string };
  await router("prompt.submit", { session_id: s.session_id, prompt: "go" }, conn);
  // Despite append throwing, the client still received delta + complete, and
  // the session completed normally (idle), not zombied.
  assert.ok(sent.some((f) => f.params?.type === "message.delta"));
  assert.equal(sessions.get(s.session_id)?.status, "idle");
  sessions.close();
  h.cleanup();
});
