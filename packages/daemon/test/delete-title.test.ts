// delete-title.test.ts — session.delete cascade + session.title (additive v1).
//
// Delete is the server's job: one RPC removes the index row, every message
// identity row, every device's seen cursor, and the transcript cache — and
// stops a live harness first (no close-before-delete ritual). Title is
// metadata: renaming never changes the session_id.

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

function fakeAdapter(stopped: string[] = []) {
  return {
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n\n/, "");
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0.01, inputTokens: 5, outputTokens: 3 }, "test");
        },
        async interrupt() {},
        async stop() { stopped.push(opts.daemonSessionId); },
      };
    },
  };
}

function harness() {
  const dir = join(tmpdir(), `mdt-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const stopped: string[] = [];
  let n = 0;
  const cleared: string[] = [];
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    usage: new UsageMeter(),
    adapter: fakeAdapter(stopped) as any,
    today: () => "2026-07-11",
    now: () => 1000 + n,
    mintSessionId: () => `s_${++n}`,
    supervisor: { clear: (id: string) => cleared.push(id) },
  });
  const conn = (deviceId?: string) => {
    const sent: any[] = [];
    return {
      ws: { send: (s: string) => sent.push(JSON.parse(s)) },
      principal: "owner", legacy: false, capabilities: [],
      ...(deviceId ? { deviceId, platform: "android" } : {}),
      _sent: sent,
    } as any;
  };
  return { router, sessions, transcripts, stopped, cleared, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("session.delete cascades: index row, messages, every device's seen cursor, transcript", async () => {
  const h = harness();
  try {
    const phone = h.conn("test-phone");
    const desk = h.conn("desk");
    const s = (await h.router("session.create", {}, phone)) as { session_id: string };
    const r = (await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, phone)) as { seq: number };
    // A SECOND device has a seen cursor on the session too.
    await h.router("session.resume", { session_id: s.session_id }, desk);
    await h.router("session.seen", { session_id: s.session_id, seq: r.seq }, desk);
    assert.ok(h.transcripts.replay(s.session_id).length > 0, "transcript populated");
    assert.ok(h.sessions.messages.list(s.session_id).length > 0, "messages populated");

    await h.router("session.delete", { session_id: s.session_id }, phone);

    assert.equal(h.sessions.get(s.session_id), undefined, "index row gone");
    assert.equal(h.sessions.messages.list(s.session_id).length, 0, "message rows gone");
    assert.equal(h.sessions.seen.get("test-phone", s.session_id), 0, "submitter's cursor gone");
    assert.equal(h.sessions.seen.get("desk", s.session_id), 0, "other device's cursor gone");
    assert.equal(h.transcripts.replay(s.session_id).length, 0, "transcript file gone");
    const list = (await h.router("session.list", {}, phone)) as { sessions: any[] };
    assert.ok(!list.sessions.some((row) => row.session_id === s.session_id), "not listed");
  } finally { h.cleanup(); }
});

test("session.delete stops a live harness and clears the supervisor latch; subscribers get session.deleted", async () => {
  const h = harness();
  try {
    const phone = h.conn("test-phone");
    const desk = h.conn("desk");
    const s = (await h.router("session.create", {}, phone)) as { session_id: string };
    await h.router("session.subscribe", { session_id: s.session_id, since_seq: 0 }, desk);

    await h.router("session.delete", { session_id: s.session_id }, phone);

    assert.deepEqual(h.stopped, [s.session_id], "live harness stopped by delete");
    assert.deepEqual(h.cleared, [s.session_id], "supervisor latch cleared");
    for (const c of [phone, desk]) {
      assert.ok(
        (c as any)._sent.some((f: any) => f.params?.type === "session.deleted" && f.params.payload.session_id === s.session_id),
        "subscriber was told the session is gone",
      );
    }
    // A deleted session is really gone: no zombie routing.
    await assert.rejects(() => h.router("prompt.submit", { session_id: s.session_id, prompt: "x" }, phone));
    await assert.rejects(() => h.router("session.resume", { session_id: s.session_id }, phone));
  } finally { h.cleanup(); }
});

test("session.delete is idempotent-ish: second delete rejects as unknown, deleting one session leaves others intact", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const a = (await h.router("session.create", {}, c)) as { session_id: string };
    const b = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: b.session_id, prompt: "keep" }, c);

    await h.router("session.delete", { session_id: a.session_id }, c);
    await assert.rejects(() => h.router("session.delete", { session_id: a.session_id }, c), /unknown session/);

    assert.ok(h.sessions.get(b.session_id), "sibling session survives");
    assert.ok(h.sessions.messages.list(b.session_id).length > 0, "sibling messages survive");
    assert.ok(h.transcripts.replay(b.session_id).length > 0, "sibling transcript survives");
  } finally { h.cleanup(); }
});

test("session.title renames; session.list exposes title; id never changes", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    const r = (await h.router("session.title", { session_id: s.session_id, title: "Groceries plan" }, c)) as { title: string };
    assert.equal(r.title, "Groceries plan");

    const list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    const row = list.sessions.find((x) => x.session_id === s.session_id);
    assert.equal(row.title, "Groceries plan");
    assert.equal(row.session_id, s.session_id, "rename never re-mints the id");

    // Over-long titles are capped, not rejected (a label, not a document).
    const long = "x".repeat(500);
    const r2 = (await h.router("session.title", { session_id: s.session_id, title: long }, c)) as { title: string };
    assert.equal(r2.title.length, 200);

    // Empty titles are a schema error.
    await assert.rejects(() => h.router("session.title", { session_id: s.session_id, title: "" }, c));
    await assert.rejects(() => h.router("session.title", { session_id: "nope", title: "t" }, c), /unknown session/);
  } finally { h.cleanup(); }
});

test("session.create honors the title param", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", { title: "Named at birth" }, c)) as { session_id: string };
    const list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    assert.equal(list.sessions.find((x) => x.session_id === s.session_id).title, "Named at birth");
  } finally { h.cleanup(); }
});
