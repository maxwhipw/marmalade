// model-list.test.ts — model.list (additive v1) + the model param on
// session.create. The daemon's model list comes from the harness adapter;
// a chosen model is stored on the session row and re-applied on resume
// (chosen once at create, sticks for the session's life). Ids pass through
// to the harness verbatim — model.list is a menu, not a gate.

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
import { ClaudeCodeAdapter } from "../dist/claude-code-adapter.js";

function fakeAdapter(spawns: Array<{ sessionId: string; model?: string }>, opts2: { withModels?: boolean } = {}) {
  return {
    name: "fake",
    ...(opts2.withModels
      ? { listModels: () => [{ id: "m-alpha", label: "Alpha" }, { id: "m-beta", label: "Beta" }] }
      : {}),
    spawn(_spec: any, opts: any, cb: any) {
      spawns.push({ sessionId: opts.daemonSessionId, model: opts.model });
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send() { cb.onActivity(); },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harness(opts: { withModels?: boolean } = {}) {
  const dir = join(tmpdir(), `mml-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const spawns: Array<{ sessionId: string; model?: string }> = [];
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts: new TranscriptCache(dir),
    usage: new UsageMeter(),
    adapter: fakeAdapter(spawns, opts) as any,
    today: () => "2026-07-11",
    now: () => 1000 + n,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = {
    ws: { send: () => {} },
    principal: "owner", legacy: false, capabilities: [],
  } as any;
  return { router, sessions, spawns, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("model.list returns the adapter's models; empty when the adapter has no model choice", async () => {
  const h = harness({ withModels: true });
  try {
    const r = (await h.router("model.list", {}, h.conn)) as { models: any[] };
    assert.deepEqual(r.models, [{ id: "m-alpha", label: "Alpha" }, { id: "m-beta", label: "Beta" }]);
  } finally { h.cleanup(); }

  const bare = harness();
  try {
    const r = (await bare.router("model.list", {}, bare.conn)) as { models: any[] };
    assert.deepEqual(r.models, []);
  } finally { bare.cleanup(); }
});

test("the real Claude adapter lists models with durable alias ids", () => {
  const adapter = new ClaudeCodeAdapter({ path: "" });
  const models = adapter.listModels();
  assert.ok(models.length >= 3, "has a real menu");
  for (const m of models) {
    assert.match(m.id, /^claude-[a-z0-9-]+$/, "alias id shape");
    assert.doesNotMatch(m.id, /\d{8}/, "no date-suffixed snapshot ids");
    assert.ok(m.label.length > 0, "has a picker label");
  }
});

test("session.create passes model to the adapter, stores it, and session.list exposes it", async () => {
  const h = harness({ withModels: true });
  try {
    const s = (await h.router("session.create", { model: "m-beta" }, h.conn)) as { session_id: string };
    assert.equal(h.spawns[0].model, "m-beta", "adapter spawn saw the model");
    assert.equal(h.sessions.get(s.session_id)!.model, "m-beta", "stored on the row");
    const list = (await h.router("session.list", {}, h.conn)) as { sessions: any[] };
    assert.equal(list.sessions.find((r) => r.session_id === s.session_id).model, "m-beta");
  } finally { h.cleanup(); }
});

test("session.create without model spawns with the harness default (no model), row is null", async () => {
  const h = harness({ withModels: true });
  try {
    const s = (await h.router("session.create", {}, h.conn)) as { session_id: string };
    assert.equal(h.spawns[0].model, undefined);
    assert.equal(h.sessions.get(s.session_id)!.model, null);
    const list = (await h.router("session.list", {}, h.conn)) as { sessions: any[] };
    assert.equal(list.sessions.find((r) => r.session_id === s.session_id).model, null);
  } finally { h.cleanup(); }
});

test("session.resume re-applies the stored model to the new spawn", async () => {
  const h = harness({ withModels: true });
  try {
    const s = (await h.router("session.create", { model: "m-alpha" }, h.conn)) as { session_id: string };
    await h.router("session.stop", { session_id: s.session_id }, h.conn);
    await h.router("session.resume", { session_id: s.session_id }, h.conn);
    assert.equal(h.spawns.length, 2, "resume spawned a second child");
    assert.equal(h.spawns[1].model, "m-alpha", "resume kept the session's model");
  } finally { h.cleanup(); }
});
