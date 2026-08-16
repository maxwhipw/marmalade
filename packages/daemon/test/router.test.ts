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

// A fake harness adapter: no child process, deterministic. `send` echoes the
// prompt as a message.delta + result; a "SUMMARY: t | s" prompt drives the
// summary callback. Lets us test router routing/isolation without a real model.
function fakeAdapter() {
  return {
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          // The router prepends a [turn origin — …] metadata line (P3); the
          // fake "model" treats it as metadata like a real one would.
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n\n/, "");
          if (prompt.startsWith("SUMMARY:")) {
            const [, rest] = prompt.split("SUMMARY:");
            const [topic, summary] = rest.split("|").map((x) => x.trim());
            cb.onSummaryUpdate?.({ topic, summary });
          } else {
            cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          }
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0.01, inputTokens: 5, outputTokens: 3 }, "test");
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
  const usage = new UsageMeter();
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    usage,
    adapter: fakeAdapter() as any,
    today: () => "2026-07-11",
    now: () => 1000 + n,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  return { router, sessions, transcripts, usage, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("two sessions run in parallel with isolated routing + transcripts", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const a = (await h.router("session.create", { cols: 80 }, c)) as { session_id: string };
    const b = (await h.router("session.create", { cols: 80 }, c)) as { session_id: string };
    assert.notEqual(a.session_id, b.session_id);

    await h.router("prompt.submit", { session_id: a.session_id, prompt: "alpha" }, c);
    await h.router("prompt.submit", { session_id: b.session_id, prompt: "beta" }, c);

    // Each session's transcript holds only its own events.
    const ta = h.transcripts.replay(a.session_id).filter((e) => e.params.type === "message.delta");
    const tb = h.transcripts.replay(b.session_id).filter((e) => e.params.type === "message.delta");
    assert.equal((ta[0].params.payload as any).text, "echo:alpha");
    assert.equal((tb[0].params.payload as any).text, "echo:beta");
    // No cross-contamination.
    assert.ok(!h.transcripts.replay(a.session_id).some((e) => JSON.stringify(e).includes("beta")));
  } finally { h.cleanup(); }
});

test("prompt.submit to an unknown session is rejected, not misrouted", async () => {
  const h = harness();
  try {
    await assert.rejects(() => h.router("prompt.submit", { session_id: "nope", prompt: "x" }, h.conn()));
  } finally { h.cleanup(); }
});

test("agent can update a session summary; session.summary + session.list expose it", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", { cols: 80 }, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "SUMMARY: M1 wiring | Wired the adapter; open: resume test" }, c);

    const sum = (await h.router("session.summary", { session_id: s.session_id }, c)) as any;
    assert.equal(sum.topic, "M1 wiring");
    assert.match(sum.summary, /open: resume test/);

    const list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    const row = list.sessions.find((r) => r.session_id === s.session_id);
    assert.equal(row.topic, "M1 wiring");
  } finally { h.cleanup(); }
});

test("origin is stamped from the AUTHENTICATED connection, never the message body (sec-H3)", async () => {
  const h = harness();
  try {
    // A connection whose hello bound android device identity.
    const c = h.conn();
    c.deviceId = "test-phone";
    c.platform = "android";
    c.tzOffset = 120;
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    // The client tries to SPOOF an origin in the body — it must be ignored.
    const r = (await h.router("prompt.submit", {
      session_id: s.session_id, prompt: "hi", source: "voice",
      origin: { user_id: "mallory", device_id: "evil-device", platform: "web" },
    }, c)) as { message_id: string; seq: number; ts: number };
    assert.ok(r.message_id, "prompt.submit returns the minted message id");
    const rec = h.sessions.messages.get(r.message_id)!;
    assert.equal(rec.origin.userId, "owner");
    assert.equal(rec.origin.deviceId, "test-phone", "origin came from the connection");
    assert.equal(rec.origin.platform, "android");
    assert.equal(rec.origin.tzOffset, 120);
    assert.equal(rec.origin.source, "voice", "source tag is data, not identity");
    // Nothing anywhere recorded the spoof.
    assert.ok(!h.transcripts.replay(s.session_id).some((e) => JSON.stringify(e).includes("evil-device")));
  } finally { h.cleanup(); }
});

test("every event a client sees carries message_id + monotonic seq; user message is in the transcript", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    const r1 = (await h.router("prompt.submit", { session_id: s.session_id, prompt: "one" }, c)) as { seq: number };
    const r2 = (await h.router("prompt.submit", { session_id: s.session_id, prompt: "two" }, c)) as { seq: number };

    // The fake adapter emits bare delta/complete — the router seam stamped
    // them all (structural: no adapter can skip identity).
    const sent = (c as any)._sent.filter((f: any) => f.method === "event" && f.params.session_id === s.session_id);
    const msgEvents = sent.filter((f: any) => f.params.type.startsWith("message."));
    assert.ok(msgEvents.length >= 4, "start(synthesized)+delta+complete per turn");
    for (const e of msgEvents) {
      assert.equal(typeof e.params.payload.message_id, "string", `${e.params.type} has message_id`);
      assert.equal(typeof e.params.payload.seq, "number");
      assert.equal(typeof e.params.payload.ts, "number");
    }
    // seq total-orders everything the session produced, RPC results included.
    const seqs = [r1.seq, r2.seq, ...sent.map((f: any) => f.params.payload.seq)].sort((a, b) => a - b);
    for (let i = 1; i < seqs.length; i++) assert.ok(seqs[i] > seqs[i - 1], "strictly increasing, no duplicates");

    // Turn threading: each turn's assistant deltas share one id; turns differ.
    const deltaIds = sent.filter((f: any) => f.params.type === "message.delta").map((f: any) => f.params.payload.message_id);
    assert.equal(new Set(deltaIds).size, 2, "two turns → two assistant message ids");

    // The user prompt is in the transcript (replay completeness) but was NOT
    // pushed to the submitting client (no double bubble).
    const userEvents = h.transcripts.replay(s.session_id).filter((e) => e.params.type === "message.user");
    assert.equal(userEvents.length, 2);
    assert.equal((userEvents[0].params.payload as any).text, "one");
    assert.ok(!sent.some((f: any) => f.params.type === "message.user"));
  } finally { h.cleanup(); }
});

test("usage accrues per turn across parallel sessions", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const a = (await h.router("session.create", {}, c)) as { session_id: string };
    const b = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: a.session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id: b.session_id, prompt: "two" }, c);
    // Both client-created (purpose=coding since the singleton-main flip)
    // turns → same (day, purpose) bucket, 2 turns.
    assert.equal(h.usage.breakdown("2026-07-11").find((e) => e.purpose === "coding")!.turns, 2);
  } finally { h.cleanup(); }
});
