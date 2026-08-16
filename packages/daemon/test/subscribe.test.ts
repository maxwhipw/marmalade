// P4 — subscription & replay (the H2 pre-M2 gate). Event delivery is no
// longer welded to the creating connection: a per-session subscriber set fans
// events to every attached client; session.subscribe replays the transcript
// cache from a seq cursor, then streams live; session.seen keeps a
// per-(device, session) read cursor. Tested over persistent FILE dbs +
// failure injection per the locked guardrails, not just :memory:.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync, appendFileSync, readdirSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";

function fakeAdapter() {
  return {
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send(prompt: string) {
          cb.onActivity();
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0.01, inputTokens: 5, outputTokens: 3 }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

/** Router harness. `fileDb: true` uses a persistent sqlite FILE (guardrail:
 *  the resume bug shipped precisely because every test used :memory:). */
function harness(opts: { fileDb?: boolean } = {}) {
  const dir = join(tmpdir(), `msub-${randomUUID()}`);
  const dbPath = join(dir, "sessions.db");
  const sessions = opts.fileDb ? new SessionManager(dbPath) : SessionManager.inMemory();
  const transcripts = new TranscriptCache(join(dir, "transcripts"));
  const usage = new UsageMeter();
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    usage,
    adapter: fakeAdapter() as any,
    today: () => "2026-07-11",
    now: () => 1000 + ++n,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = (deviceId?: string, platform?: string) => {
    const sent: any[] = [];
    return {
      ws: { send: (s: string) => sent.push(JSON.parse(s)) },
      principal: "owner", legacy: false, capabilities: [],
      ...(deviceId ? { deviceId, platform: platform ?? "android" } : {}),
      _sent: sent,
    } as any;
  };
  const events = (c: any) => c._sent.filter((f: any) => f.method === "event");
  return {
    router, sessions, transcripts, dbPath, dir, conn, events,
    cleanup: () => { sessions.close(); rmSync(dir, { recursive: true, force: true }); },
  };
}

test("events fan out to every subscriber, not just the creating connection (H2)", async () => {
  const h = harness();
  try {
    const creator = h.conn();
    const phone = h.conn("test-phone");
    const s = (await h.router("session.create", {}, creator)) as { session_id: string };
    const sub = (await h.router("session.subscribe", { session_id: s.session_id }, phone)) as any;
    // Only the starting→idle status.update is cached so far — no messages.
    assert.equal(sub.replayed, h.events(phone).length);
    assert.ok(h.events(phone).every((e: any) => e.params.type === "status.update"));

    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, creator);

    const creatorDeltas = h.events(creator).filter((e: any) => e.params.type === "message.delta");
    const phoneDeltas = h.events(phone).filter((e: any) => e.params.type === "message.delta");
    assert.equal(creatorDeltas.length, 1, "legacy creator still receives (auto-subscribed)");
    assert.equal(phoneDeltas.length, 1, "subscriber receives the same stream");
    // Identical stamped frames: same message_id, same seq — one stamping, one truth.
    assert.deepEqual(phoneDeltas[0], creatorDeltas[0]);
  } finally { h.cleanup(); }
});

test("subscribe replays the cached tail from since_seq, ordered by seq, then streams live — no gap, no dup", async () => {
  const h = harness();
  try {
    const creator = h.conn();
    const s = (await h.router("session.create", {}, creator)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "one" }, creator);

    // Full replay: a fresh client gets the whole history, message.user included
    // (that's why the transcript-only event exists).
    const cold = h.conn("test-phone");
    const r1 = (await h.router("session.subscribe", { session_id: s.session_id, since_seq: 0 }, cold)) as any;
    const replayed = h.events(cold);
    assert.equal(r1.replayed, replayed.length);
    assert.ok(replayed.some((e: any) => e.params.type === "message.user"), "user message replays");
    assert.ok(replayed.some((e: any) => e.params.type === "message.delta"));
    const seqs = replayed.map((e: any) => e.params.payload.seq);
    for (let i = 1; i < seqs.length; i++) assert.ok(seqs[i] > seqs[i - 1], "replay is seq-ordered, strictly increasing");
    assert.equal(r1.last_seq, seqs[seqs.length - 1], "last_seq = the client's next cursor");

    // Cursor replay: a reconnecting client with since_seq=last_seq gets ONLY
    // what it missed — here nothing — then live events flow.
    const warm = h.conn("laptop");
    const r2 = (await h.router("session.subscribe", { session_id: s.session_id, since_seq: r1.last_seq }, warm)) as any;
    assert.equal(r2.replayed, 0, "nothing new past the cursor");
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "two" }, creator);
    const warmEvents = h.events(warm);
    assert.ok(warmEvents.length > 0, "live events flow after cursor replay");
    assert.ok(warmEvents.every((e: any) => e.params.payload.seq > r1.last_seq), "only NEW events — no replay/live overlap");
    const keys = warmEvents.map((e: any) => `${e.params.payload.seq}`);
    assert.equal(new Set(keys).size, keys.length, "no duplicate seq delivered");
  } finally { h.cleanup(); }
});

test("unsubscribe stops delivery; disconnect prunes the connection from every subscriber set", async () => {
  const h = harness();
  try {
    const creator = h.conn();
    const phone = h.conn("test-phone");
    const s = (await h.router("session.create", {}, creator)) as { session_id: string };
    await h.router("session.subscribe", { session_id: s.session_id }, phone);
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "a" }, creator);
    const afterFirst = h.events(phone).length;
    assert.ok(afterFirst > 0);

    await h.router("session.unsubscribe", { session_id: s.session_id }, phone);
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "b" }, creator);
    assert.equal(h.events(phone).length, afterFirst, "no events after unsubscribe");

    // Disconnect prune: the gateway calls router.disconnect on socket close.
    h.router.disconnect(creator);
    await assert.doesNotReject(() => h.router("prompt.submit", { session_id: s.session_id, prompt: "c" }, h.conn()));
  } finally { h.cleanup(); }
});

test("a subscription survives the session going ended and streams again after another connection resumes it", async () => {
  const h = harness();
  try {
    const creator = h.conn();
    const phone = h.conn("test-phone");
    const s = (await h.router("session.create", {}, creator)) as { session_id: string };
    await h.router("session.subscribe", { session_id: s.session_id }, phone);
    await h.router("session.stop", { session_id: s.session_id }, creator);
    // The subscriber saw the terminal status.update.
    const ended = h.events(phone).filter((e: any) => e.params.type === "status.update" && e.params.payload.lifecycle === "ended");
    assert.equal(ended.length, 1);

    // Another device resumes; the phone's subscription streams the new turn.
    const desktop = h.conn("marmalade", "desktop");
    await h.router("session.resume", { session_id: s.session_id }, desktop);
    const before = h.events(phone).length;
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "back" }, desktop);
    assert.ok(h.events(phone).length > before, "subscriber receives post-resume events");
  } finally { h.cleanup(); }
});

test("subscribe to an ended session replays history (offline render); unknown session rejects", async () => {
  const h = harness();
  try {
    const creator = h.conn();
    const s = (await h.router("session.create", {}, creator)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hello" }, creator);
    await h.router("session.stop", { session_id: s.session_id }, creator);

    const late = h.conn("test-phone");
    const r = (await h.router("session.subscribe", { session_id: s.session_id }, late)) as any;
    assert.equal(r.lifecycle, "ended");
    assert.ok(r.replayed > 0, "history replays for an ended session");

    await assert.rejects(() => h.router("session.subscribe", { session_id: "nope" }, late));
    await assert.rejects(() => h.router("session.seen", { session_id: "nope", seq: 1 }, late));
  } finally { h.cleanup(); }
});

test("session.seen is monotonic and per-device; session.list exposes last_seq + this device's seen_seq", async () => {
  const h = harness();
  try {
    const phone = h.conn("test-phone");
    const desktop = h.conn("marmalade", "desktop");
    const s = (await h.router("session.create", {}, phone)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, phone);

    // Monotonic: a stale stamp never moves the cursor backward.
    assert.deepEqual(await h.router("session.seen", { session_id: s.session_id, seq: 5 }, desktop), { seq: 5 });
    assert.deepEqual(await h.router("session.seen", { session_id: s.session_id, seq: 3 }, desktop), { seq: 5 });
    assert.deepEqual(await h.router("session.seen", { session_id: s.session_id, seq: 9 }, desktop), { seq: 9 });

    // Per-device: the desktop's cursor is its own; the phone's came from its
    // prompt.submit auto-stamp (submitting IS seeing).
    const phoneList = (await h.router("session.list", {}, phone)) as { sessions: any[] };
    const phoneRow = phoneList.sessions.find((r) => r.session_id === s.session_id);
    assert.ok(phoneRow.last_seq >= 1, "last_seq reflects the messages table");
    assert.ok(phoneRow.seen_seq >= 1, "prompt.submit stamped the submitting device");
    assert.ok(phoneRow.seen_seq <= phoneRow.last_seq);

    const desktopList = (await h.router("session.list", {}, desktop)) as { sessions: any[] };
    const desktopRow = desktopList.sessions.find((r) => r.session_id === s.session_id);
    assert.equal(desktopRow.seen_seq, 9, "each device sees ITS cursor");
  } finally { h.cleanup(); }
});

test("P4 over a persistent FILE db: seen cursors and last_seq survive a daemon restart", async () => {
  const h = harness({ fileDb: true });
  try {
    const phone = h.conn("test-phone");
    const s = (await h.router("session.create", {}, phone)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "persist me" }, phone);
    await h.router("session.seen", { session_id: s.session_id, seq: 4 }, phone);
    h.sessions.close();

    // "Restart": a fresh SessionManager over the same file.
    const reopened = new SessionManager(h.dbPath);
    try {
      assert.equal(reopened.seen.get("test-phone", s.session_id), 4, "cursor survived");
      assert.ok((reopened.messages.maxSeqBySession().get(s.session_id) ?? 0) >= 1, "last_seq survives");
      assert.equal(reopened.seen.stamp("test-phone", s.session_id, 2, 99), 4, "still monotonic after reopen");
    } finally { reopened.close(); }
    rmSync(h.dir, { recursive: true, force: true });
  } catch (e) { h.cleanup(); throw e; }
});

test("failure injection: a corrupt transcript line doesn't brick replay — good lines still stream", async () => {
  const h = harness();
  try {
    const creator = h.conn();
    const s = (await h.router("session.create", {}, creator)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "good turn" }, creator);

    // Simulate a crash mid-append: garbage + a truncated JSON line at the tail.
    const dir = join(h.dir, "transcripts");
    const file = readdirSync(dir).find((f) => f.includes(s.session_id))!;
    appendFileSync(join(dir, file), 'not json at all\n{"jsonrpc":"2.0","met\n');

    const late = h.conn("test-phone");
    const r = (await h.router("session.subscribe", { session_id: s.session_id }, late)) as any;
    assert.ok(r.replayed > 0, "replay survived the corrupt tail");
    assert.ok(h.events(late).some((e: any) => e.params.type === "message.delta"));
  } finally { h.cleanup(); }
});

// ── Straddled replay (delta compaction) ────────────────────────────────────
// The cache folds a message's delta run into ONE event at turn end, so a
// client whose cursor sits INSIDE a folded run holds a partial prefix of text
// that event now carries whole. The replay re-sends that message's
// message.start from below the watermark; both clients treat a repeat start
// for a known id as "drop the stale partial and rebuild", so the consolidated
// event lands on a clean message instead of doubling the prose.

/** Seed a settled turn straight into the cache, then compact it. Seqs start
 *  well above whatever the router already cached for the session. */
function seedCompactedTurn(h: any, sessionId: string) {
  const ev = (type: string, payload: Record<string, unknown>) =>
    ({ jsonrpc: "2.0", method: "event", params: { type, payload, session_id: sessionId } }) as any;
  for (const e of [
    ev("message.user", { message_id: "u9", seq: 100, ts: 9000, text: "go" }),
    ev("message.start", { message_id: "a9", seq: 101, ts: 9001 }),
    ev("message.delta", { message_id: "a9", seq: 102, ts: 9002, text: "one " }),
    ev("message.delta", { message_id: "a9", seq: 103, ts: 9003, text: "two " }),
    ev("message.delta", { message_id: "a9", seq: 104, ts: 9004, text: "three" }),
    ev("message.complete", { message_id: "a9", seq: 105, ts: 9005, text: "one two three" }),
    ev("status.update", { seq: 106, ts: 9006, run_state: "idle" }),
  ]) h.transcripts.append(sessionId, e);
  h.transcripts.compact(sessionId);
}

test("a since_seq straddling a folded run re-sends that message's start so the client rebuilds", async () => {
  const h = harness();
  try {
    const creator = h.conn();
    const s = (await h.router("session.create", {}, creator)) as { session_id: string };
    seedCompactedTurn(h, s.session_id);

    // Cursor at 103: the client applied "one two " live, then dropped. The
    // folded event spans first_seq=102 .. seq=104, so 102 <= 103 < 104.
    const warm = h.conn("web");
    const r = (await h.router("session.subscribe", { session_id: s.session_id, since_seq: 103 }, warm)) as any;
    const got = h.events(warm);
    assert.deepEqual(
      got.map((e: any) => [e.params.type, e.params.payload.seq]),
      [["message.start", 101], ["message.delta", 104], ["message.complete", 105], ["status.update", 106]],
    );
    assert.equal(r.replayed, got.length);
    assert.equal(r.last_seq, 106, "last_seq still reports the true high-water");
    // The rebuild start is the ONLY thing sent from below the watermark, and
    // it's for the straddled message.
    const below = got.filter((e: any) => e.params.payload.seq <= 103);
    assert.equal(below.length, 1);
    assert.equal(below[0].params.payload.message_id, "a9");
    // It precedes the consolidated event it exists to reset — file order.
    assert.equal(got[1].params.payload.text, "one two three");
    assert.equal(got[1].params.payload.consolidated, true);
  } finally { h.cleanup(); }
});

test("a cursor outside every folded run replays exactly as it did before compaction existed", async () => {
  const h = harness();
  try {
    const creator = h.conn();
    const s = (await h.router("session.create", {}, creator)) as { session_id: string };
    seedCompactedTurn(h, s.session_id);

    // Below the run (client saw the start, no deltas): no rebuild needed —
    // the start is at the cursor, not straddled, so it is NOT re-sent.
    const before = h.conn("web-a");
    await h.router("session.subscribe", { session_id: s.session_id, since_seq: 101 }, before);
    assert.deepEqual(
      h.events(before).map((e: any) => e.params.type),
      ["message.delta", "message.complete", "status.update"],
    );

    // At the run's end (client saw the whole run): nothing below the watermark.
    const after = h.conn("web-b");
    await h.router("session.subscribe", { session_id: s.session_id, since_seq: 104 }, after);
    assert.deepEqual(
      h.events(after).map((e: any) => e.params.payload.seq),
      [105, 106],
    );

    // Past the turn: nothing at all.
    const done = h.conn("web-c");
    const r = (await h.router("session.subscribe", { session_id: s.session_id, since_seq: 106 }, done)) as any;
    assert.equal(r.replayed, 0);

    // Full replay is untouched by the straddle rule (no id is straddled at 0).
    const cold = h.conn("web-d");
    await h.router("session.subscribe", { session_id: s.session_id, since_seq: 0 }, cold);
    const seqs = h.events(cold).map((e: any) => e.params.payload.seq);
    for (let i = 1; i < seqs.length; i++) assert.ok(seqs[i] > seqs[i - 1], "still strictly increasing");
  } finally { h.cleanup(); }
});

test("the rebuild exemption doesn't leak: unstamped events still skip a cursored replay", async () => {
  const h = harness();
  try {
    const creator = h.conn();
    const s = (await h.router("session.create", {}, creator)) as { session_id: string };
    seedCompactedTurn(h, s.session_id);
    // A pre-P1 event with no seq at all, and an unstamped message.start for
    // the straddled id — neither may ride the exemption (it is seq-gated).
    h.transcripts.append(s.session_id, { jsonrpc: "2.0", method: "event", params: { type: "message.start", payload: { message_id: "a9" }, session_id: s.session_id } } as any);

    const warm = h.conn("web");
    await h.router("session.subscribe", { session_id: s.session_id, since_seq: 103 }, warm);
    const starts = h.events(warm).filter((e: any) => e.params.type === "message.start");
    assert.equal(starts.length, 1, "only the stamped start replays");
    assert.equal(starts[0].params.payload.seq, 101);

    const cold = h.conn("web-cold");
    await h.router("session.subscribe", { session_id: s.session_id, since_seq: 0 }, cold);
    assert.equal(
      h.events(cold).filter((e: any) => e.params.type === "message.start").length,
      2,
      "a full replay still includes the unstamped event",
    );
  } finally { h.cleanup(); }
});
