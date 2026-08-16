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

// Digital-twin harness for the clarify round-trip (agent questions): a fake
// adapter that CAPTURES the requestClarify callback so tests can drive the
// AskUserQuestion seam exactly the way the canUseTool bridge does.
function harness() {
  const dir = join(tmpdir(), `clar-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const spawned: any[] = []; // captured AdapterCallbacks per spawn
  let n = 0;
  const router = createRouter({
    cfg: { ...defaultConfig(), approvalsMode: "auto" },
    sessions,
    transcripts,
    usage: new UsageMeter(),
    adapter: {
      name: "fake",
      spawn(_spec: any, _opts: any, cb: any) {
        spawned.push(cb);
        return { send: async () => {}, interrupt: async () => {}, stop: async () => {} };
      },
    } as any,
    today: () => "2026-07-18",
    now: () => 1000 + n++,
    mintSessionId: () => `s_${spawned.length + 1}`,
  });
  const conn = (platform = "android") => {
    const sent: any[] = [];
    return {
      ws: { send: (s: string) => sent.push(JSON.parse(s)) },
      principal: "owner", legacy: false, capabilities: [],
      authenticated: true, deviceIdVerified: false, platform,
      _sent: sent,
    } as any;
  };
  return { router, sessions, transcripts, spawned, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

function eventsOf(conn: any, type: string): any[] {
  return conn._sent.filter((f: any) => f.method === "event" && f.params?.type === type).map((f: any) => f.params.payload);
}

const QUESTIONS = [{
  question: "Which library should we use?",
  header: "Library",
  options: [
    { label: "Ktor", description: "Kotlin-native HTTP" },
    { label: "OkHttp", description: "Battle-tested" },
  ],
  multiSelect: false,
}];

test("a question parks EVEN IN AUTO MODE: clarify.request emitted, awaiting_input, answers round-trip", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any; // approvals: auto (global default)
    const decisionP = h.spawned[0].requestClarify(QUESTIONS);
    await new Promise((r) => setImmediate(r));

    const reqs = eventsOf(c, "clarify.request");
    assert.equal(reqs.length, 1);
    assert.ok(reqs[0].request_id);
    assert.equal(reqs[0].questions.length, 1);
    assert.equal(reqs[0].questions[0].question, "Which library should we use?");
    assert.equal(reqs[0].questions[0].header, "Library");
    assert.equal(reqs[0].questions[0].multi_select, false);
    assert.deepEqual(reqs[0].questions[0].options[0], { label: "Ktor", description: "Kotlin-native HTTP" });
    assert.ok(typeof reqs[0].seq === "number", "transient events still get seq/ts");
    assert.equal(h.sessions.get(session_id)!.runState, "awaiting_input");
    // Transient — never in the transcript cache (a replayed already-answered
    // question card would be wrong).
    const cached = h.transcripts.replay(session_id).filter((e: any) => e.params.type === "clarify.request");
    assert.equal(cached.length, 0);

    const r = (await h.router("clarify.respond", {
      session_id, request_id: reqs[0].request_id,
      answers: { "Which library should we use?": "Ktor" },
    }, c)) as any;
    assert.equal(r.resolved, true);
    assert.deepEqual(await decisionP, { answered: true, answers: { "Which library should we use?": "Ktor" } });
    assert.equal(h.sessions.get(session_id)!.runState, "running");
    const resolved = eventsOf(c, "clarify.resolved");
    assert.equal(resolved.length, 1);
    assert.equal(resolved[0].request_id, reqs[0].request_id);
  } finally { h.cleanup(); }
});

test("dismiss (no answers, no response) resolves answered:false with a proceed-on-your-own message", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const decisionP = h.spawned[0].requestClarify(QUESTIONS);
    await new Promise((r) => setImmediate(r));
    await h.router("clarify.respond", { session_id }, c); // FIFO fallback, no payload
    const d = (await decisionP) as any;
    assert.equal(d.answered, false);
    assert.match(d.message, /dismissed.*judgment/);
  } finally { h.cleanup(); }
});

test("freeform response alone counts as an answer", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const decisionP = h.spawned[0].requestClarify(QUESTIONS);
    await new Promise((r) => setImmediate(r));
    await h.router("clarify.respond", { session_id, response: "Neither — use the platform client" }, c);
    assert.deepEqual(await decisionP, { answered: true, answers: {}, response: "Neither — use the platform client" });
  } finally { h.cleanup(); }
});

test("unattended fallback: no subscribers → answered:false immediately (headless must not hang)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    await h.router("session.create", {}, c);
    (h.router as any).disconnect(c); // creator detaches → zero subscribers
    const d = (await h.spawned[0].requestClarify(QUESTIONS)) as any;
    assert.equal(d.answered, false);
    assert.match(d.message, /no one is connected/);
  } finally { h.cleanup(); }
});

test("last subscriber detaching MID-PARK settles the question unanswered (run survives)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const parked = h.spawned[0].requestClarify(QUESTIONS);
    await new Promise((r) => setImmediate(r));
    assert.equal(eventsOf(c, "clarify.request").length, 1);
    (h.router as any).disconnect(c);
    const d = (await parked) as any;
    assert.equal(d.answered, false);
    assert.match(d.message, /disconnected/);
    assert.equal(h.sessions.get(session_id)!.runState, "running", "turn continues unattended");
  } finally { h.cleanup(); }
});

test("mid-park subscribe re-emits the pending clarify.request verbatim; either device answers", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const parked = h.spawned[0].requestClarify(QUESTIONS);
    await new Promise((r) => setImmediate(r));
    const original = eventsOf(c, "clarify.request")[0];

    const c2 = h.conn("web");
    await h.router("session.subscribe", { session_id, since_seq: 0 }, c2);
    const reEmitted = eventsOf(c2, "clarify.request");
    assert.equal(reEmitted.length, 1);
    assert.deepEqual(reEmitted[0], original, "same frame, no re-stamp");

    await h.router("clarify.respond", { session_id, answers: { "Which library should we use?": "OkHttp" } }, c2);
    assert.deepEqual(await parked, { answered: true, answers: { "Which library should we use?": "OkHttp" } });
    // Both devices see clarify.resolved so cards clear everywhere.
    assert.equal(eventsOf(c, "clarify.resolved").length, 1);
    assert.equal(eventsOf(c2, "clarify.resolved").length, 1);
  } finally { h.cleanup(); }
});

test("SERIALIZATION: a second concurrent question parks behind the first", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const q2 = [{ ...QUESTIONS[0], question: "Second question?" }];
    const first = h.spawned[0].requestClarify(QUESTIONS);
    const second = h.spawned[0].requestClarify(q2);
    await new Promise((r) => setImmediate(r));

    assert.equal(eventsOf(c, "clarify.request").length, 1, "only the first is visible until it resolves");
    await h.router("clarify.respond", { session_id, answers: { "Which library should we use?": "Ktor" } }, c);
    assert.equal((await first as any).answered, true);
    await new Promise((r) => setImmediate(r));

    const reqs = eventsOf(c, "clarify.request");
    assert.equal(reqs.length, 2);
    assert.equal(reqs[1].questions[0].question, "Second question?");
    await h.router("clarify.respond", { session_id }, c);
    assert.equal((await second as any).answered, false);
  } finally { h.cleanup(); }
});

test("clarify.respond with nothing pending errors; wrong request_id errors", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    await assert.rejects(h.router("clarify.respond", { session_id }, c), /no matching pending clarify/);

    const parked = h.spawned[0].requestClarify(QUESTIONS);
    await new Promise((r) => setImmediate(r));
    await assert.rejects(
      h.router("clarify.respond", { session_id, request_id: "wrong-id", answers: { x: "y" } }, c),
      /no matching pending clarify/,
    );
    await h.router("clarify.respond", { session_id, answers: { "Which library should we use?": "Ktor" } }, c);
    assert.equal((await parked as any).answered, true);
  } finally { h.cleanup(); }
});

test("session.stop resolves parked questions so canUseTool promises never leak", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const parked = h.spawned[0].requestClarify(QUESTIONS);
    await new Promise((r) => setImmediate(r));
    await h.router("session.stop", { session_id }, c);
    const d = (await parked) as any;
    assert.equal(d.answered, false);
    assert.match(d.message, /judgment/);
  } finally { h.cleanup(); }
});
