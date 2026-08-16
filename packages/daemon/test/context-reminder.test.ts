// context-reminder.test.ts — the context-pressure nudge (advisory, one-shot).
//
// When a turn's reported context occupancy crosses cfg.contextReminderPercent,
// the NEXT prompt.submit carries a harness-only <system-reminder> preamble
// telling the agent to persist durable state at its next natural stopping
// point. Bug classes locked here: reminder repeating every turn (nag),
// reminder never re-arming after compaction drops the percent, reminder
// firing when disabled (threshold 0), and the transcript accidentally
// carrying the preamble (clients must never render it).

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

interface AdapterState {
  prompts: string[];
  /** Finish the in-flight turn, reporting the given context occupancy. */
  completeTurn: (contextPercent?: number) => void;
}

function makeAdapter(state: AdapterState) {
  return {
    name: "fake",
    spawn(_spec: unknown, opts: { daemonSessionId: string }, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      const ev = (type: string, payload: Record<string, unknown>) =>
        cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type, payload, session_id: opts.daemonSessionId } });
      state.completeTurn = (contextPercent?: number) => {
        ev("message.complete", {});
        cb.onResult(
          { subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1, contextPercent },
          "test",
        );
      };
      return {
        async send(prompt: string) {
          cb.onActivity();
          state.prompts.push(prompt);
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harness(contextReminderPercent: number) {
  const dir = join(tmpdir(), `cr-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const state: AdapterState = { prompts: [], completeTurn: () => {} };
  let n = 0;
  const now = { t: 1000 };
  const router = createRouter({
    cfg: { ...defaultConfig({}), contextReminderPercent },
    sessions, transcripts, usage: new UsageMeter(),
    adapter: makeAdapter(state) as any,
    today: () => "2026-07-19",
    now: () => ++now.t,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = () =>
    ({ ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any);
  return { router, sessions, transcripts, state, conn, cleanup: () => { sessions.close(); rmSync(dir, { recursive: true, force: true }); } };
}

const REMINDER = /<system-reminder>Context usage is at/;

test("reminder fires ONCE on the first submit after crossing the threshold — and never repeats while over it", async () => {
  const h = harness(75);
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;

    await h.router("prompt.submit", { session_id, prompt: "turn 1" }, c);
    assert.doesNotMatch(h.state.prompts[0]!, REMINDER, "no reminder before any usage report");
    h.state.completeTurn(60); // under threshold

    await h.router("prompt.submit", { session_id, prompt: "turn 2" }, c);
    assert.doesNotMatch(h.state.prompts[1]!, REMINDER, "60% < 75% — no reminder");
    h.state.completeTurn(82); // crossed

    await h.router("prompt.submit", { session_id, prompt: "turn 3" }, c);
    assert.match(h.state.prompts[2]!, REMINDER);
    assert.match(h.state.prompts[2]!, /82%/);
    assert.match(h.state.prompts[2]!, /turn 3/, "the real prompt still rides the same send");
    h.state.completeTurn(88); // still over — must NOT re-fire

    await h.router("prompt.submit", { session_id, prompt: "turn 4" }, c);
    assert.doesNotMatch(h.state.prompts[3]!, REMINDER, "one-shot: no nag while still over");
  } finally { h.cleanup(); }
});

test("re-arms when occupancy drops back under the threshold (compaction), fires again on the next crossing", async () => {
  const h = harness(75);
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;

    await h.router("prompt.submit", { session_id, prompt: "t1" }, c);
    h.state.completeTurn(80);
    await h.router("prompt.submit", { session_id, prompt: "t2" }, c);
    assert.match(h.state.prompts[1]!, REMINDER);
    h.state.completeTurn(20); // compaction dropped it — re-arm

    await h.router("prompt.submit", { session_id, prompt: "t3" }, c);
    assert.doesNotMatch(h.state.prompts[2]!, REMINDER, "under threshold again");
    h.state.completeTurn(90); // second crossing

    await h.router("prompt.submit", { session_id, prompt: "t4" }, c);
    assert.match(h.state.prompts[3]!, REMINDER, "re-armed reminder fires on the second crossing");
  } finally { h.cleanup(); }
});

test("threshold 0 disables the reminder entirely", async () => {
  const h = harness(0);
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "t1" }, c);
    h.state.completeTurn(99);
    await h.router("prompt.submit", { session_id, prompt: "t2" }, c);
    assert.doesNotMatch(h.state.prompts[1]!, REMINDER);
  } finally { h.cleanup(); }
});

test("the transcript keeps the RAW prompt — the reminder preamble is harness-only", async () => {
  const h = harness(75);
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "t1" }, c);
    h.state.completeTurn(80);
    await h.router("prompt.submit", { session_id, prompt: "the visible prompt" }, c);
    assert.match(h.state.prompts[1]!, REMINDER, "harness saw the reminder");
    const replayed = h.transcripts.replay(session_id, 0).map((e: any) => JSON.stringify(e)).join("\n");
    assert.ok(replayed.includes("the visible prompt"));
    assert.ok(!replayed.includes("system-reminder"), "clients must never render the reminder");
  } finally { h.cleanup(); }
});
