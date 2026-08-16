// defaults.test.ts — daemon-owned new-session defaults (2026-07-23).
//
// The daemon OWNS what a model-less / effort-less session.create gets:
// config default_model / default_effort are stamped on the row (so clients
// see real values on session rows, not an opaque "Default"), passed to the
// harness spawn, re-applied on resume, and declared via model.list. The
// client's explicit values always win. reasoning_effort — accepted by the
// wire schema since v1 but dropped until now — is validated against the SDK
// levels and threaded to the adapter as SpawnOptions.effort.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { makeEvent } from "@marmalade/protocol";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";

type Spawn = { sessionId: string; model?: string; effort?: string };

function harness(file: Record<string, unknown> = {}) {
  const dir = join(tmpdir(), `dflt-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const spawns: Spawn[] = [];
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(file as never),
    sessions,
    transcripts,
    usage: new UsageMeter(),
    adapter: {
      name: "fake",
      listModels: () => [{ id: "m-alpha", label: "Alpha" }],
      spawn(_spec: unknown, opts: { daemonSessionId: string; model?: string; effort?: string }, cb: { onHarnessSession(id: string): void; onEvent(ev: unknown): void }) {
        spawns.push({ sessionId: opts.daemonSessionId, model: opts.model, effort: opts.effort });
        cb.onHarnessSession(`h-${opts.daemonSessionId}`);
        // Mirror the SDK init: session.info carries the model only — the
        // router decorates reasoning_effort onto it.
        cb.onEvent(makeEvent("session.info", { session_id: opts.daemonSessionId, model: opts.model ?? "sdk-default", tools: [] }, opts.daemonSessionId));
        return { async send() {}, async interrupt() {}, async stop() {} };
      },
    } as never,
    today: () => "2026-07-23",
    now: () => 1000 + n,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = { ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as never;
  return { router, sessions, transcripts, spawns, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("config: default_effort validates against the SDK levels; env/file both honored", () => {
  assert.equal(defaultConfig({ default_effort: "xhigh" } as never).defaultEffort, "xhigh");
  assert.equal(defaultConfig({ default_model: "claude-opus-4-8" } as never).defaultModel, "claude-opus-4-8");
  assert.equal(defaultConfig().defaultModel, undefined);
  assert.equal(defaultConfig().defaultEffort, undefined);
  assert.throws(() => defaultConfig({ default_effort: "turbo" } as never), /not one of/);
});

test("model-less create gets config defaults stamped: row, spawn opts, session.list", async () => {
  const h = harness({ default_model: "m-alpha", default_effort: "high" });
  try {
    const { session_id } = (await h.router("session.create", { cols: 80 }, h.conn)) as { session_id: string };
    assert.equal(h.spawns[0].model, "m-alpha");
    assert.equal(h.spawns[0].effort, "high");
    const rec = h.sessions.get(session_id)!;
    assert.equal(rec.model, "m-alpha");
    assert.equal(rec.reasoningEffort, "high");
    const list = (await h.router("session.list", {}, h.conn)) as { sessions: Array<{ session_id: string; model: string | null; reasoning_effort: string | null }> };
    const row = list.sessions.find((s) => s.session_id === session_id)!;
    assert.equal(row.model, "m-alpha");
    assert.equal(row.reasoning_effort, "high");
  } finally { h.cleanup(); }
});

test("explicit client model/effort always beat the config defaults", async () => {
  const h = harness({ default_model: "m-alpha", default_effort: "low" });
  try {
    const { session_id } = (await h.router("session.create", { cols: 80, model: "m-custom", reasoning_effort: "max" }, h.conn)) as { session_id: string };
    assert.equal(h.spawns[0].model, "m-custom");
    assert.equal(h.spawns[0].effort, "max");
    assert.equal(h.sessions.get(session_id)!.reasoningEffort, "max");
  } finally { h.cleanup(); }
});

test("no defaults configured → behavior unchanged (nothing stamped, nothing passed)", async () => {
  const h = harness();
  try {
    const { session_id } = (await h.router("session.create", { cols: 80 }, h.conn)) as { session_id: string };
    assert.equal(h.spawns[0].model, undefined);
    assert.equal(h.spawns[0].effort, undefined);
    assert.equal(h.sessions.get(session_id)!.model, null);
    assert.equal(h.sessions.get(session_id)!.reasoningEffort, null);
  } finally { h.cleanup(); }
});

test("an unknown reasoning_effort is rejected loudly, not silently dropped", async () => {
  const h = harness();
  try {
    await assert.rejects(
      () => h.router("session.create", { cols: 80, reasoning_effort: "ultra" }, h.conn),
      /not one of low\/medium\/high\/xhigh\/max/,
    );
  } finally { h.cleanup(); }
});

test("resume re-applies the stored effort (like model)", async () => {
  const h = harness({ default_effort: "xhigh" });
  try {
    const { session_id } = (await h.router("session.create", { cols: 80 }, h.conn)) as { session_id: string };
    await h.router("session.stop", { session_id }, h.conn);
    await h.router("session.resume", { session_id }, h.conn);
    assert.equal(h.spawns.length, 2);
    assert.equal(h.spawns[1].effort, "xhigh");
  } finally { h.cleanup(); }
});

test("model.list declares the daemon defaults; absent when unset", async () => {
  const h = harness({ default_model: "m-alpha", default_effort: "medium" });
  try {
    const r = (await h.router("model.list", {}, h.conn)) as Record<string, unknown>;
    assert.equal(r.default_model, "m-alpha");
    assert.equal(r.default_effort, "medium");
  } finally { h.cleanup(); }
  const bare = harness();
  try {
    const r = (await bare.router("model.list", {}, bare.conn)) as Record<string, unknown>;
    assert.equal("default_model" in r, false);
    assert.equal("default_effort" in r, false);
  } finally { bare.cleanup(); }
});

test("session.info is decorated with the spawn's effort (the clients' adopt path)", async () => {
  const h = harness({ default_effort: "high" });
  try {
    const { session_id } = (await h.router("session.create", { cols: 80 }, h.conn)) as { session_id: string };
    const info = h.transcripts.replay(session_id).find((e) => e.params.type === "session.info")!;
    assert.equal((info.params.payload as { reasoning_effort?: string }).reasoning_effort, "high");
  } finally { h.cleanup(); }
  // Without an effort the payload is untouched — no phantom field.
  const bare = harness();
  try {
    const { session_id } = (await bare.router("session.create", { cols: 80 }, bare.conn)) as { session_id: string };
    const info = bare.transcripts.replay(session_id).find((e) => e.params.type === "session.info")!;
    assert.equal("reasoning_effort" in (info.params.payload as Record<string, unknown>), false);
  } finally { bare.cleanup(); }
});
