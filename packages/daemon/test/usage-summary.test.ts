// usage-summary.test.ts — the usage.summary RPC surface (parity-map T2 #8):
// window math, wire mapping, and the real path — a prompt.submit's recorded
// usage is readable back through the SAME persisted store the summary reads.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { defaultConfig } from "../dist/config.js";

const TODAY = "2026-07-18";
const T0 = Date.parse("2026-07-18T10:00:00.000Z");

function fakeAdapter() {
  return {
    name: "fake",
    spawn(_spec: unknown, opts: { daemonSessionId: string }, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send() {
          cb.onActivity();
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0.01, inputTokens: 120, outputTokens: 30 }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harness(adapter: any = fakeAdapter()) {
  const dir = join(tmpdir(), `us-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    usage: sessions.usage, // the persisted store IS the deps meter (as in index.ts)
    adapter,
    today: () => TODAY,
    now: () => T0,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = () =>
    ({ ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] }) as any;
  return { router, sessions, conn, cleanup: () => { sessions.close(); rmSync(dir, { recursive: true, force: true }); } };
}

test("usage.summary windows by day, maps to wire names, defaults to 7 days", async () => {
  const h = harness();
  try {
    h.sessions.usage.record("2026-07-11", "main", { costUsd: 0, inputTokens: 1, outputTokens: 1 }); // outside a 7-day window ending 07-18
    h.sessions.usage.record("2026-07-12", "main", { costUsd: 0.5, inputTokens: 10, outputTokens: 5 }); // window start, inclusive
    h.sessions.usage.record(TODAY, "cadence", { costUsd: 0, inputTokens: 20, outputTokens: 8 });

    const res = (await h.router("usage.summary", {}, h.conn())) as any;
    assert.equal(res.today, TODAY);
    // No live session (and the fake adapter has no planUsage seam anyway).
    assert.deepEqual(res.plan_limits, []);
    assert.deepEqual(res.entries.map((e: any) => e.day), ["2026-07-12", TODAY]);
    const first = res.entries[0];
    // Wire names are snake_case; internal camelCase must not leak.
    assert.deepEqual(first, { day: "2026-07-12", purpose: "main", cost_usd: 0.5, input_tokens: 10, output_tokens: 5, turns: 1 });

    const narrow = (await h.router("usage.summary", { days: 1 }, h.conn())) as any;
    assert.deepEqual(narrow.entries.map((e: any) => e.day), [TODAY]);

    await assert.rejects(h.router("usage.summary", { days: 0 }, h.conn()));
    await assert.rejects(h.router("usage.summary", { days: 91 }, h.conn()));
  } finally { h.cleanup(); }
});

test("a real prompt.submit's usage lands in the summary (record and read share one store)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "hi" }, c);
    // The adapter's onResult fires asynchronously after the ack — settle.
    for (let i = 0; i < 50 && h.sessions.usage.breakdown(TODAY).length === 0; i++) {
      await new Promise((r) => setImmediate(r));
    }
    const res = (await h.router("usage.summary", { days: 1 }, c)) as any;
    // Client-created sessions are purpose=coding since the singleton-main flip.
    const entry = res.entries.find((e: any) => e.purpose === "coding");
    assert.ok(entry, "prompt.submit usage recorded under purpose coding");
    assert.equal(entry.turns, 1);
    assert.equal(entry.input_tokens, 120);
    assert.equal(entry.output_tokens, 30);
  } finally { h.cleanup(); }
});

test("usage.summary surfaces subscription plan limits from a live session's planUsage seam", async () => {
  // The Claude Code adapter's shape; a Codex-style adapter would surface the
  // same way — the router only cares about the seam, tagged with adapter.name.
  const adapter = fakeAdapter() as any;
  const baseSpawn = adapter.spawn.bind(adapter);
  adapter.spawn = (spec: unknown, opts: any, cb: any) => ({
    ...baseSpawn(spec, opts, cb),
    async planUsage() {
      return {
        subscriptionType: "max",
        windows: [
          { id: "five_hour", label: "5-hour", utilization: 34, resetsAt: "2026-07-18T14:00:00Z" },
          { id: "seven_day", label: "Weekly (all models)", utilization: null, resetsAt: null },
        ],
      };
    },
  });
  const h = harness(adapter);
  try {
    const c = h.conn();
    await h.router("session.create", { cols: 80 }, c);
    const res = (await h.router("usage.summary", {}, c)) as any;
    // Internal camelCase (resetsAt) maps to wire snake_case and must not leak.
    assert.deepEqual(res.plan_limits, [{
      harness: "fake",
      subscription_type: "max",
      windows: [
        { id: "five_hour", label: "5-hour", utilization: 34, resets_at: "2026-07-18T14:00:00Z" },
        { id: "seven_day", label: "Weekly (all models)", utilization: null, resets_at: null },
      ],
    }]);
  } finally { h.cleanup(); }
});

test("usage.summary plan limits degrade to empty when the seam resolves null or rejects", async () => {
  for (const planUsage of [async () => null, async () => { throw new Error("claude.ai unreachable"); }]) {
    const adapter = fakeAdapter() as any;
    const baseSpawn = adapter.spawn.bind(adapter);
    adapter.spawn = (spec: unknown, opts: any, cb: any) => ({ ...baseSpawn(spec, opts, cb), planUsage });
    const h = harness(adapter);
    try {
      const c = h.conn();
      await h.router("session.create", { cols: 80 }, c);
      const res = (await h.router("usage.summary", {}, c)) as any;
      assert.deepEqual(res.plan_limits, []);
    } finally { h.cleanup(); }
  }
});
