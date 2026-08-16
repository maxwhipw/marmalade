// steer-compact.test.ts — session.steer + session.compact (T2 #6 / #11a).
//
// Steer = a user message injected into the RUNNING turn (the harness merges
// it; verified live on Claude Code 2026-07-18). Bug classes ported: steering
// with no turn in flight (silent new-turn surprise), a steer that vanishes
// (must mint identity + persist to transcript + reach the harness), seq/P1
// corruption, runState stomping.
//
// Compact = trigger-only (engine stays harness-delegated, T3). Bug classes:
// compacting a mid-turn session (interleaving), a harness without the seam
// (must fail loud, not no-op), compaction events that don't reach
// subscribers or don't survive in the transcript, reaped-session revive.

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
  spawns: Array<{ daemonSessionId: string; resume?: string }>;
  prompts: string[];
  compacts: number;
  /** Set per-spawn: lets a test finish the in-flight turn on demand. */
  completeTurn: () => void;
  withCompact: boolean;
}

/** Fake adapter whose turns stay IN FLIGHT until the test completes them —
 *  steer needs a genuinely running turn. compact() replays the normalized
 *  event sequence the real adapter produces (started → completed → boundary
 *  + a zero-turn result). */
function makeAdapter(state: AdapterState) {
  return {
    name: "fake",
    spawn(_spec: unknown, opts: { daemonSessionId: string; resumeHarnessSessionId?: string }, cb: any) {
      state.spawns.push({ daemonSessionId: opts.daemonSessionId, resume: opts.resumeHarnessSessionId });
      cb.onHarnessSession(opts.resumeHarnessSessionId ?? `h-${opts.daemonSessionId}`);
      const ev = (type: string, payload: Record<string, unknown>) =>
        cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type, payload, session_id: opts.daemonSessionId } });
      state.completeTurn = () => {
        ev("message.delta", { text: "reply" });
        ev("message.complete", {});
        cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "test");
      };
      return {
        async send(prompt: string) {
          cb.onActivity();
          state.prompts.push(prompt);
          // Turn stays open — the test calls state.completeTurn() when ready.
        },
        async interrupt() {},
        async stop() {},
        ...(state.withCompact ? {
          async compact() {
            state.compacts++;
            ev("session.compaction", { status: "started" });
            ev("session.compaction", { status: "completed" });
            ev("session.compaction", { status: "boundary", trigger: "manual", pre_tokens: 100, post_tokens: 10 });
            cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 0 }, "test");
          },
        } : {}),
      };
    },
  };
}

function harness(withCompact = true) {
  const dir = join(tmpdir(), `sc-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const state: AdapterState = { spawns: [], prompts: [], compacts: 0, completeTurn: () => {}, withCompact };
  let n = 0;
  const now = { t: 1000 };
  const router = createRouter({
    cfg: defaultConfig({}),
    sessions, transcripts, usage: new UsageMeter(),
    adapter: makeAdapter(state) as any,
    today: () => "2026-07-18",
    now: () => ++now.t,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = (sink?: string[]) =>
    ({ ws: { send: (f: string) => sink?.push(f) }, principal: "owner", legacy: false, capabilities: [] } as any);
  return { router, sessions, transcripts, state, conn, cleanup: () => { sessions.close(); rmSync(dir, { recursive: true, force: true }); } };
}

// ── session.steer ───────────────────────────────────────────────────────────

test("steer requires a live session and a turn in flight", async () => {
  const h = harness();
  try {
    const c = h.conn();
    await assert.rejects(h.router("session.steer", { session_id: "nope", prompt: "x" }, c), /not live/);
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    // Live but idle: no turn to steer — must NOT silently become a new turn.
    await assert.rejects(h.router("session.steer", { session_id, prompt: "x" }, c), /no turn in flight/);
    assert.equal(h.state.prompts.length, 0, "nothing reached the harness");
    assert.equal(h.sessions.messages.list(session_id).length, 0, "no identity minted for a rejected steer");
  } finally { h.cleanup(); }
});

test("mid-turn steer: mints identity, persists to transcript with steered flag, reaches the harness, keeps runState running", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    const sub = (await h.router("prompt.submit", { session_id, prompt: "start work" }, c)) as any;
    assert.equal(h.sessions.get(session_id)!.runState, "running");

    const r = (await h.router("session.steer", { session_id, prompt: "actually stop and do Y" }, c)) as any;
    // P1: a real user message with its own id, seq AFTER the submit's.
    assert.ok(r.message_id && r.message_id !== sub.message_id);
    assert.ok(r.seq > sub.seq, "seq stays monotonic");
    const rows = h.sessions.messages.list(session_id);
    const steerRow = rows.find((m) => m.messageId === r.message_id)!;
    assert.equal(steerRow.role, "user");

    // The harness got it, marked as a mid-turn steer, prompt text intact.
    assert.equal(h.state.prompts.length, 2);
    assert.match(h.state.prompts[1]!, /mid-turn steer/);
    assert.match(h.state.prompts[1]!, /actually stop and do Y/);

    // Transcript: message.user with steered:true (replay renders the steer).
    const userEvents = h.transcripts.replay(session_id).filter((e: any) => e.params.type === "message.user");
    assert.equal(userEvents.length, 2);
    assert.equal((userEvents[1] as any).params.payload.steered, true);
    assert.equal((userEvents[1] as any).params.payload.text, "actually stop and do Y");

    // Still the SAME turn: runState untouched, and the eventual single
    // result closes it normally.
    assert.equal(h.sessions.get(session_id)!.runState, "running");
    h.state.completeTurn();
    assert.equal(h.sessions.get(session_id)!.runState, "idle");
  } finally { h.cleanup(); }
});

// ── session.compact ─────────────────────────────────────────────────────────

test("compact guards: unknown session, harness without the seam, turn in flight", async () => {
  const h = harness();
  const noSeam = harness(false);
  try {
    const c = h.conn();
    await assert.rejects(h.router("session.compact", { session_id: "nope" }, c), /unknown session/);

    const cn = noSeam.conn();
    const { session_id: s2 } = (await noSeam.router("session.create", { cols: 80 }, cn)) as any;
    // No manual-compact support must fail LOUD (a silent no-op would leave a
    // client spinner waiting on events that never come).
    await assert.rejects(noSeam.router("session.compact", { session_id: s2 }, cn), /no manual-compact/);

    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "long job" }, c);
    await assert.rejects(h.router("session.compact", { session_id }, c), /turn in flight/);
    assert.equal(h.state.compacts, 0);
  } finally { h.cleanup(); noSeam.cleanup(); }
});

test("compact on an idle session: triggers the harness, events reach subscribers AND the transcript", async () => {
  const h = harness();
  try {
    const frames: string[] = [];
    const c = h.conn(frames);
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "hi" }, c);
    h.state.completeTurn();
    await h.router("session.subscribe", { session_id }, c);

    const r = (await h.router("session.compact", { session_id }, c)) as any;
    assert.deepEqual(r, {});
    assert.equal(h.state.compacts, 1);

    // Live fan-out: started → completed → boundary, stamped (seq/ts).
    const compaction = frames.map((f) => JSON.parse(f)).filter((e) => e.params?.type === "session.compaction");
    assert.deepEqual(compaction.map((e) => e.params.payload.status), ["started", "completed", "boundary"]);
    assert.equal(compaction[2].params.payload.pre_tokens, 100);
    for (const e of compaction) assert.ok(typeof e.params.payload.seq === "number", "stamped with seq");

    // Persisted: a client replaying later sees the boundary marker.
    const replayed = h.transcripts.replay(session_id).filter((e: any) => e.params.type === "session.compaction");
    assert.equal(replayed.length, 3);

    // The zero-turn result settles runState back to idle (metered, no bubble).
    assert.equal(h.sessions.get(session_id)!.runState, "idle");
  } finally { h.cleanup(); }
});

test("compact auto-revives a stopped session (mirror of prompt.submit)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "hi" }, c);
    h.state.completeTurn();
    await h.router("session.stop", { session_id }, c);

    await h.router("session.compact", { session_id }, c);
    assert.equal(h.state.compacts, 1);
    const revive = h.state.spawns.at(-1)!;
    assert.equal(revive.daemonSessionId, session_id);
    assert.equal(revive.resume, `h-${session_id}`, "revived on the SAME harness session");
  } finally { h.cleanup(); }
});
