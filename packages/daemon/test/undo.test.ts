// undo.test.ts — session.undo (T2 #6 second half; design note kept internally,
// signed off 2026-07-18: delete popped rows, conversation-only v1,
// last-turn-only). Bug classes from the design:
//   pop = exactly one turn incl. steers; seq monotonic (no reuse after pop);
//   repeated undo walks back; first-turn undo → fresh harness; restart
//   survival of the pending rewind (+ cleared after the first result);
//   mid-turn / nothing-to-undo / no-seam / fork-copied-cut guards;
//   crash-window (transcript truncated, rows present) → re-undo recovers;
//   fork-after-undo forks the rewound state, not the popped tail.

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

interface FakeState {
  spawns: Array<{ daemonSessionId: string; resume?: string; resumeAt?: string }>;
  uuidN: number;
  /** When set, send() records the prompt and PARKS the turn (runState stays
   *  running) until finish() is called — lets tests steer mid-turn. */
  hold: boolean;
  finish: (() => void) | null;
}

function rewindableAdapter(state: FakeState) {
  return {
    name: "fake",
    supportsResumeAt: true,
    async forkSession(harnessSessionId: string, opts: { cwd: string; upToHarnessUuid?: string }) {
      state.forks.push({ harnessSessionId, upToHarnessUuid: opts.upToHarnessUuid });
      return { harnessSessionId: `h-forked-${state.forks.length}` };
    },
    spawn(_spec: unknown, opts: { daemonSessionId: string; resumeHarnessSessionId?: string; resumeAtHarnessUuid?: string }, cb: any) {
      state.spawns.push({ daemonSessionId: opts.daemonSessionId, resume: opts.resumeHarnessSessionId, resumeAt: opts.resumeAtHarnessUuid });
      cb.onHarnessSession(opts.resumeHarnessSessionId ?? `h-${opts.daemonSessionId}`);
      const completeTurn = (prompt: string) => {
        cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
        cb.onHarnessMessageUuid(`hu-${++state.uuidN}`);
        cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
        cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "test");
      };
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n(\[mid-turn steer[^\]]*\]\n)?\n/, "");
          if (state.hold) {
            state.finish = () => { state.finish = null; completeTurn(prompt); };
            return;
          }
          completeTurn(prompt);
        },
        async interrupt() {},
        async stop() {},
      };
    },
  } as any;
}

function harness() {
  const dir = join(tmpdir(), `un-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const state: FakeState & { forks: Array<{ harnessSessionId: string; upToHarnessUuid?: string }> } =
    { spawns: [], uuidN: 0, hold: false, finish: null, forks: [] };
  let n = 0;
  const now = { t: 1000 };
  const mkRouter = () => createRouter({
    cfg: defaultConfig({}),
    sessions, transcripts, usage: new UsageMeter(),
    adapter: rewindableAdapter(state),
    today: () => "2026-07-18",
    now: () => ++now.t,
    mintSessionId: () => `s_${++n}`,
  });
  const router = mkRouter();
  const conn = (sent: string[] = []) =>
    ({ ws: { send: (f: string) => sent.push(f) }, principal: "owner", legacy: false, capabilities: [] } as any);
  return { router, mkRouter, sessions, transcripts, state, conn, cleanup: () => { sessions.close(); rmSync(dir, { recursive: true, force: true }); } };
}

test("undo pops exactly the last turn: rows deleted, transcript truncated, tip + event correct", async () => {
  const h = harness();
  try {
    const sent: string[] = [];
    const c = h.conn(sent);
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);
    const before = h.sessions.messages.list(session_id);
    assert.equal(before.length, 4, "u1 a1 u2 a2");
    const [, a1, u2, a2] = before;

    const r = (await h.router("session.undo", { session_id }, c)) as any;
    assert.equal(r.last_message_id, a1!.messageId);
    assert.deepEqual(r.popped_message_ids, [u2!.messageId, a2!.messageId]);
    assert.equal(r.files_rewound, false);

    // Rows: turn two gone, turn one intact.
    const after = h.sessions.messages.list(session_id);
    assert.deepEqual(after.map((m) => m.messageId), [before[0]!.messageId, a1!.messageId]);
    // Transcript: echo:two gone, echo:one intact; no event references a popped id.
    const events = h.transcripts.replay(session_id);
    assert.ok(events.some((e: any) => e.params.payload?.text === "echo:one"));
    assert.ok(!events.some((e: any) => e.params.payload?.text === "echo:two"));
    // Pending rewind = the previous turn's final assistant uuid.
    assert.equal(h.sessions.get(session_id)!.harnessResumeAt, "hu-1");
    // Transient session.undone reached the subscriber and is NOT in the cache.
    const undone = sent.map((f) => JSON.parse(f)).find((f: any) => f.params?.type === "session.undone");
    assert.ok(undone, "session.undone delivered");
    assert.deepEqual(undone.params.payload.popped_message_ids, r.popped_message_ids);
    assert.ok(!events.some((e: any) => e.params.type === "session.undone"));
  } finally { h.cleanup(); }
});

test("next prompt resumes AT the rewind point; the pending rewind clears after the first result; seqs never reuse popped values", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);
    const maxSeqBefore = Math.max(h.sessions.messages.maxSeq(session_id), h.transcripts.lastSeq(session_id));
    await h.router("session.undo", { session_id }, c);

    // Auto-revive on submit consumes the rewind.
    await h.router("prompt.submit", { session_id, prompt: "two-b" }, c);
    const revive = h.state.spawns.at(-1)!;
    assert.equal(revive.resume, `h-${session_id}`);
    assert.equal(revive.resumeAt, "hu-1");
    // First result consumed it — plain resume from now on.
    assert.equal(h.sessions.get(session_id)!.harnessResumeAt, null);
    // No seq of the new turn may reuse a popped seq (P1: reuse is corruption).
    const newRows = h.sessions.messages.list(session_id).filter((m) => m.seq > maxSeqBefore - 10);
    const newTurn = h.sessions.messages.list(session_id).slice(2);
    assert.equal(newTurn.length, 2, "u2b a2b");
    for (const m of newTurn) assert.ok(m.seq > maxSeqBefore, `seq ${m.seq} must be > pre-undo max ${maxSeqBefore}`);
    void newRows;
  } finally { h.cleanup(); }
});

test("repeated undo walks back turn by turn; first-turn undo clears the harness binding", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);

    await h.router("session.undo", { session_id }, c); // pops turn two
    const r2 = (await h.router("session.undo", { session_id }, c)) as any; // pops turn one
    assert.equal(r2.last_message_id, null);
    assert.equal(h.sessions.messages.list(session_id).length, 0);
    // Pre-turn status.update events may survive (no message scope); nothing
    // message-scoped may.
    assert.ok(h.transcripts.replay(session_id).every(
      (e: any) => (e.params.payload?.message_id === undefined && !String(e.params.type).startsWith("message.")),
    ));
    // Fresh-harness edge: no binding, no pending rewind.
    const rec = h.sessions.get(session_id)!;
    assert.equal(rec.harnessSessionId, null);
    assert.equal(rec.harnessResumeAt, null);
    // Third undo: nothing left.
    await assert.rejects(h.router("session.undo", { session_id }, c), /nothing to undo/);
    // And the next prompt starts a FRESH harness session (no resume).
    await h.router("prompt.submit", { session_id, prompt: "anew" }, c);
    const spawn = h.state.spawns.at(-1)!;
    assert.equal(spawn.resume, undefined);
    assert.equal(spawn.resumeAt, undefined);
  } finally { h.cleanup(); }
});

test("undo survives a daemon restart: harness_resume_at is consumed by the post-restart revive", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);
    await h.router("session.undo", { session_id }, c);

    // "Restart": a fresh router over the same stores.
    h.sessions.markOrphansExited(999_999);
    const router2 = h.mkRouter();
    await router2("prompt.submit", { session_id, prompt: "post-restart" }, c);
    const revive = h.state.spawns.at(-1)!;
    assert.equal(revive.resumeAt, "hu-1");
    assert.equal(h.sessions.get(session_id)!.harnessResumeAt, null, "consumed after the first result");
  } finally { h.cleanup(); }
});

test("a steered turn pops as ONE unit (steer rows are not turn boundaries)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    // Turn two runs held-open so a steer can land mid-turn.
    h.state.hold = true;
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);
    await h.router("session.steer", { session_id, prompt: "actually, be brief" }, c);
    h.state.finish!();
    h.state.hold = false;
    // Rows: u1 a1 u2 steer a2 (the held turn's assistant finalizes last).
    const rows = h.sessions.messages.list(session_id);
    assert.equal(rows.filter((m) => m.steered).length, 1);

    const r = (await h.router("session.undo", { session_id }, c)) as any;
    // Pop = u2 + steer + every assistant of turn two; tip = a1.
    const after = h.sessions.messages.list(session_id);
    assert.equal(after.length, 2, "u1 + a1 remain");
    assert.ok(after.every((m) => !m.steered));
    assert.equal(r.popped_message_ids.length, rows.length - 2);
    assert.equal(h.sessions.get(session_id)!.harnessResumeAt, "hu-1");
  } finally { h.cleanup(); }
});

test("guards: unknown session, mid-turn, no rewind seam, fork-copied cut", async () => {
  const h = harness();
  try {
    const c = h.conn();
    await assert.rejects(h.router("session.undo", { session_id: "s_nope" }, c), /unknown session/);

    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    h.sessions.setRunState(session_id, "running", 99_999);
    await assert.rejects(h.router("session.undo", { session_id }, c), /turn in flight/);
    h.sessions.setRunState(session_id, "idle", 99_999);

    // Fork-copied cut: fork the session, run ONE turn in the fork, then undo
    // it — the cut would be a copied row with no harness uuid.
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);
    const fork = (await h.router("session.fork", { session_id }, c)) as any;
    await h.router("prompt.submit", { session_id: fork.session_id, prompt: "in-fork" }, c);
    await assert.rejects(
      h.router("session.undo", { session_id: fork.session_id }, c),
      /fork-copied/,
    );
  } finally { h.cleanup(); }
});

test("no-seam harness rejects undo loudly", async () => {
  const dir = join(tmpdir(), `un2-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  try {
    let n = 0;
    const adapter = {
      name: "no-rewind",
      spawn(_s: unknown, opts: { daemonSessionId: string }, cb: any) {
        cb.onHarnessSession(`h-${opts.daemonSessionId}`);
        return {
          async send() {
            cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
            cb.onResult({ totalCostUsd: 0, inputTokens: 0, outputTokens: 0 }, "t");
          },
          async interrupt() {}, async stop() {},
        };
      },
    };
    const router = createRouter({
      cfg: defaultConfig({}), sessions, transcripts: new TranscriptCache(dir),
      usage: new UsageMeter(), adapter: adapter as any, today: () => "2026-07-18",
      now: () => 1, mintSessionId: () => `s_${++n}`,
    });
    const c = { ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any;
    const { session_id } = (await router("session.create", { cols: 80 }, c)) as any;
    await router("prompt.submit", { session_id, prompt: "one" }, c);
    await assert.rejects(router("session.undo", { session_id }, c), /cannot rewind/);
  } finally { sessions.close(); rmSync(dir, { recursive: true, force: true }); }
});

test("crash window: transcript truncated but rows present → re-undo recovers cleanly", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);
    const rows = h.sessions.messages.list(session_id);
    const popped = new Set(rows.slice(2).map((m) => m.messageId));

    // Simulate the crash: truncate the transcript only (the step before the
    // SQLite tx), leaving rows + session columns untouched.
    h.transcripts.truncateFromMessages(session_id, popped);
    assert.equal(h.sessions.messages.list(session_id).length, 4, "rows survived the crash");

    // Re-undo recomputes the same cut from the surviving rows and completes.
    const r = (await h.router("session.undo", { session_id }, c)) as any;
    assert.equal(r.popped_message_ids.length, 2);
    assert.equal(h.sessions.messages.list(session_id).length, 2);
    assert.equal(h.sessions.get(session_id)!.harnessResumeAt, "hu-1");
    const events = h.transcripts.replay(session_id);
    assert.ok(events.some((e: any) => e.params.payload?.text === "echo:one"));
    assert.ok(!events.some((e: any) => e.params.payload?.text === "echo:two"));
  } finally { h.cleanup(); }
});

test("fork after a pending undo forks the REWOUND state, not the popped tail", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { cols: 80 }, c)) as any;
    await h.router("prompt.submit", { session_id, prompt: "one" }, c);
    await h.router("prompt.submit", { session_id, prompt: "two" }, c);
    await h.router("session.undo", { session_id }, c);

    // End fork while the rewind is still pending: the harness JSONL tip still
    // holds the popped turn, so the fork must cut at the rewind point.
    await h.router("session.fork", { session_id }, c);
    assert.equal(h.state.forks.at(-1)!.upToHarnessUuid, "hu-1");
  } finally { h.cleanup(); }
});
