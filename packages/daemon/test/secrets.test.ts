// secrets.test.ts — the secret-entry flow (design of 2026-08-11): the
// agent asks for a credential by KEYRING ENTRY, the user types it into a
// secure input on their client, and the daemon writes it to the keyring. The
// feature is defined by what does NOT happen: the value must never reach the
// agent's context, the message store, the transcript cache, the search index,
// or a log line.
//
// So the suite is half mechanism (park / respond / drain / re-send, mirroring
// approvals.test.ts) and half a leak hunt: every flow uses a SENTINEL value
// and the last test sweeps every durable surface for it.
//
// No gopass anywhere — the keyring write is either an injected fake or a
// /bin/sh script, which is also what proves the configurable-command design
// works for a non-gopass backend.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync, readFileSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { SearchStore } from "../dist/search-store.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";

const SENTINEL = "S3NT1NEL-hunter2";
const ENTRY = "marmalade/email/imap-password";

/** Digital twin of the whole path: a fake adapter that captures the
 *  AdapterCallbacks (so tests can call sessionTools.requestSecret exactly the
 *  way the MCP tool handler does), a real transcript cache + search index, a
 *  recording keyring writer, and a captured log. */
function harness(opts: { storeSecret?: (entry: string, value: string) => Promise<void> } = {}) {
  const dir = join(tmpdir(), `sec-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const search = new SearchStore(":memory:");
  const spawned: any[] = [];
  const stored: { entry: string; value: string }[] = [];
  const logs: string[] = [];
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    search,
    usage: new UsageMeter(),
    adapter: {
      name: "fake",
      spawn(_spec: any, _opts: any, cb: any) {
        spawned.push(cb);
        return { send: async () => {}, interrupt: async () => {}, stop: async () => {} };
      },
    } as any,
    storeSecret: opts.storeSecret ?? (async (entry: string, value: string) => { stored.push({ entry, value }); }),
    today: () => "2026-08-11",
    now: () => 1000 + n++,
    mintSessionId: () => `s_${spawned.length + 1}`,
    log: (line: string) => logs.push(line),
  } as any);

  /** capabilities decides whether this client can answer a secret.request —
   *  the daemon reads the hello-declared capability, not the platform. */
  const conn = (capabilities: string[] = ["secrets"], platform = "android") => {
    const sent: any[] = [];
    return {
      ws: { send: (s: string) => sent.push(JSON.parse(s)) },
      principal: "owner", legacy: false, capabilities,
      authenticated: true, deviceIdVerified: false, platform,
      _sent: sent,
    } as any;
  };
  return {
    router, sessions, transcripts, search, spawned, stored, logs, conn,
    cleanup: () => { search.close(); rmSync(dir, { recursive: true, force: true }); },
  };
}

function eventsOf(conn: any, type: string): any[] {
  return conn._sent.filter((f: any) => f.method === "event" && f.params?.type === type).map((f: any) => f.params.payload);
}

/** The seam the MCP tool handler calls (claude-code-adapter's request_secret). */
function tools(h: ReturnType<typeof harness>, i = 0) {
  return h.spawned[i].sessionTools;
}

const tick = () => new Promise((r) => setImmediate(r));

// ── the happy path ─────────────────────────────────────────────────────────

test("full flow: request_secret parks, secret.request reaches the capable client, respond stores it, the tool learns only the entry", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "IMAP password for the assistant's mailbox");
    await tick();

    const reqs = eventsOf(c, "secret.request");
    assert.equal(reqs.length, 1);
    assert.equal(reqs[0].entry, ENTRY);
    assert.equal(reqs[0].description, "IMAP password for the assistant's mailbox");
    assert.equal(reqs[0].session_id, session_id);
    assert.ok(reqs[0].request_id);
    assert.equal(typeof reqs[0].created_at, "number");
    assert.equal(typeof reqs[0].seq, "number", "transient events still get seq/ts");
    assert.equal(h.sessions.get(session_id)!.runState, "awaiting_input");

    const res = (await h.router("secret.respond", { session_id, value: SENTINEL }, c)) as any;
    assert.deepEqual(res, { resolved: true, stored: true });
    assert.deepEqual(h.stored, [{ entry: ENTRY, value: SENTINEL }], "the value reached the keyring writer verbatim");

    // The ONLY thing the agent learns.
    assert.equal(await toolP, `stored at ${ENTRY}`);
    assert.equal(h.sessions.get(session_id)!.runState, "running");

    const resolved = eventsOf(c, "secret.resolved");
    assert.equal(resolved.length, 1);
    assert.equal(resolved[0].request_id, reqs[0].request_id);
    assert.equal(resolved[0].outcome, "stored");
  } finally { h.cleanup(); }
});

test("the keyring write goes through the real storeSecret when no fake is injected — value on stdin, not argv", async () => {
  // End to end against a /bin/sh "backend", so the router→keyring.ts wiring
  // (including cfg.keyring.insertCommand) is exercised, not just the seam.
  const out = join(tmpdir(), `secrt-${randomUUID()}`);
  const dir = join(tmpdir(), `sec-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const spawned: any[] = [];
  let n = 0;
  const router = createRouter({
    cfg: {
      ...defaultConfig(),
      keyring: {
        insertCommand: ["/bin/sh", "-c", `printf '%s' "$*" > "${out}.argv"; cat > "${out}.stdin"`, "--", "{entry}"],
      },
    },
    sessions,
    transcripts: new TranscriptCache(dir),
    usage: new UsageMeter(),
    adapter: {
      name: "fake",
      spawn: (_s: any, _o: any, cb: any) => { spawned.push(cb); return { send: async () => {}, interrupt: async () => {}, stop: async () => {} }; },
    } as any,
    today: () => "2026-08-11",
    now: () => 1000 + n++,
    mintSessionId: () => "s_1",
  } as any);
  try {
    const c: any = { ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: ["secrets"], authenticated: true, deviceIdVerified: false };
    const { session_id } = (await router("session.create", {}, c)) as any;
    const toolP = spawned[0].sessionTools.requestSecret(ENTRY, "why");
    await tick();
    const res = (await router("secret.respond", { session_id, value: SENTINEL }, c)) as any;
    assert.equal(res.stored, true);
    assert.equal(await toolP, `stored at ${ENTRY}`);
    assert.equal(readFileSync(`${out}.stdin`, "utf8"), SENTINEL);
    assert.doesNotMatch(readFileSync(`${out}.argv`, "utf8"), /S3NT1NEL/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
    rmSync(`${out}.argv`, { force: true });
    rmSync(`${out}.stdin`, { force: true });
  }
});

// ── refusals and failures ──────────────────────────────────────────────────

test("deny: the tool is told the user declined, with the reason; nothing is stored", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    const res = (await h.router("secret.respond", { session_id, deny: true, reason: "not on this device" }, c)) as any;
    assert.deepEqual(res, { resolved: true, stored: false });

    const msg = await toolP;
    assert.match(msg, /did not provide the secret/);
    assert.match(msg, /not on this device/);
    assert.match(msg, /gopass insert/, "the agent gets the manual fallback, not a dead end");
    assert.equal(h.stored.length, 0);
    assert.equal(eventsOf(c, "secret.resolved")[0].outcome, "denied");
    assert.equal(h.sessions.get(session_id)!.runState, "running");
  } finally { h.cleanup(); }
});

test("NO capable subscriber → the tool fails IMMEDIATELY with the terminal fallback (never parks, never auto-anything)", async () => {
  const h = harness();
  try {
    // A subscriber that can render approvals but NOT a secure input. Present
    // and attentive — and still unable to answer, which is the whole point of
    // gating on the capability rather than on subscriber count.
    const c = h.conn(["approvals", "streaming"]);
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const msg = await tools(h).requestSecret(ENTRY, "why");
    assert.match(msg, /No client available to collect a secret/);
    assert.match(msg, new RegExp(`gopass insert ${ENTRY}`));
    assert.match(msg, /Do NOT ask them to type the secret to you/);
    assert.equal(eventsOf(c, "secret.request").length, 0, "nothing was pushed");
    assert.equal(h.sessions.get(session_id)!.runState, "idle", "never parked → runState untouched");
    assert.equal(h.stored.length, 0);
  } finally { h.cleanup(); }
});

test("a keyring failure resolves the tool with an error that does NOT contain the value", async () => {
  const h = harness({
    storeSecret: async () => { throw new Error(`keyring insert of "${ENTRY}" failed (exit 4): store is locked`); },
  });
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    const res = (await h.router("secret.respond", { session_id, value: SENTINEL }, c)) as any;
    assert.equal(res.resolved, true);
    assert.equal(res.stored, false);
    assert.match(res.error, /store is locked/);

    const msg = await toolP;
    assert.match(msg, /could not be stored/);
    assert.match(msg, /store is locked/);
    assert.doesNotMatch(msg, /S3NT1NEL|hunter2/, "a failure message must not become the leak");
    assert.equal(eventsOf(c, "secret.resolved")[0].outcome, "failed");
    // The card clears everywhere and the run is unblocked either way.
    assert.equal(h.sessions.get(session_id)!.runState, "running");
  } finally { h.cleanup(); }
});

test("secret.respond is strict: neither value nor deny, or both, is refused; unknown request_id errors", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    await assert.rejects(h.router("secret.respond", { session_id }, c));
    await assert.rejects(h.router("secret.respond", { session_id, value: "v", deny: true }, c));
    await assert.rejects(h.router("secret.respond", { session_id, value: "" }, c), /.*/);
    // Nothing parked at all.
    await assert.rejects(
      h.router("secret.respond", { session_id, value: "v" }, c),
      /no matching pending secret request/,
    );

    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    const rid = eventsOf(c, "secret.request")[0].request_id;
    await assert.rejects(
      h.router("secret.respond", { session_id, value: "v", request_id: "wrong-id" }, c),
      /no matching pending secret request/,
    );
    await h.router("secret.respond", { session_id, value: SENTINEL, request_id: rid }, c);
    assert.equal(await toolP, `stored at ${ENTRY}`);
  } finally { h.cleanup(); }
});

// ── drains ─────────────────────────────────────────────────────────────────

test("session.stop drain-DENIES a parked request so the tool promise never leaks", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    await h.router("session.stop", { session_id }, c);
    assert.match(await toolP, /did not provide the secret/);
    assert.equal(h.stored.length, 0);
  } finally { h.cleanup(); }
});

test("session.delete drain-DENIES a parked request", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    await h.router("session.delete", { session_id }, c);
    assert.match(await toolP, /did not provide the secret/);
  } finally { h.cleanup(); }
});

test("THE INVERSION: a dropped connection drain-DENIES (approvals drain-ALLOW — there is no auto-answer for a password)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    await h.router("session.create", {}, c);
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    (h.router as any).disconnect(c);
    const msg = await toolP;
    assert.match(msg, /did not provide the secret/);
    assert.match(msg, /secrets-capable device disconnected/);
    assert.equal(h.stored.length, 0);
  } finally { h.cleanup(); }
});

test("losing the last SECRETS-CAPABLE device denies even though other subscribers remain", async () => {
  const h = harness();
  try {
    const phone = h.conn(["secrets"], "android");
    const desktop = h.conn(["approvals"], "desktop");
    const { session_id } = (await h.router("session.create", {}, phone)) as any;
    await h.router("session.subscribe", { session_id, since_seq: 0 }, desktop);
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    assert.equal(eventsOf(phone, "secret.request").length, 1);

    // The desktop is still attached — subs.count() > 0 — but it cannot answer.
    (h.router as any).disconnect(phone);
    assert.match(await toolP, /secrets-capable device disconnected/);
  } finally { h.cleanup(); }
});

// ── multi-client ───────────────────────────────────────────────────────────

test("mid-park subscribe re-emits the pending secret.request verbatim — to capable clients only", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    const original = eventsOf(c, "secret.request")[0];

    // An incapable client attaching mid-park gets the replay but NOT the card.
    const web = h.conn(["approvals"], "web");
    await h.router("session.subscribe", { session_id, since_seq: 0 }, web);
    assert.equal(eventsOf(web, "secret.request").length, 0, "a client that can't render a secure input is not offered one");

    const c2 = h.conn(["secrets"], "desktop");
    await h.router("session.subscribe", { session_id, since_seq: 0 }, c2);
    const reEmitted = eventsOf(c2, "secret.request");
    assert.equal(reEmitted.length, 1);
    assert.deepEqual(reEmitted[0], original, "same frame, no re-stamp, no duplicate seq");

    // Either capable device may answer; c2 does.
    await h.router("secret.respond", { session_id, value: SENTINEL }, c2);
    assert.equal(await toolP, `stored at ${ENTRY}`);
    // Everyone's card clears, including the incapable subscriber's (it just
    // has nothing to clear) — resolution is a plain session event.
    assert.equal(eventsOf(c, "secret.resolved").length, 1);
    assert.equal(eventsOf(c2, "secret.resolved").length, 1);
  } finally { h.cleanup(); }
});

test("SERIALIZATION: a second request parks behind the first; both settle in order", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const first = tools(h).requestSecret("a/one", "first");
    const second = tools(h).requestSecret("b/two", "second");
    await tick();

    assert.equal(eventsOf(c, "secret.request").length, 1, "one secure input on screen at a time");
    assert.equal(eventsOf(c, "secret.request")[0].entry, "a/one");

    await h.router("secret.respond", { session_id, value: "v1" }, c);
    assert.equal(await first, "stored at a/one");
    await tick();

    const reqs = eventsOf(c, "secret.request");
    assert.equal(reqs.length, 2);
    assert.equal(reqs[1].entry, "b/two");
    await h.router("secret.respond", { session_id, deny: true }, c);
    assert.match(await second, /did not provide the secret/);
    assert.deepEqual(h.stored, [{ entry: "a/one", value: "v1" }]);
  } finally { h.cleanup(); }
});

// ── the point of the whole feature ─────────────────────────────────────────

test("NON-PERSISTENCE: after a full flow the sentinel appears in NO durable surface and no log line", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "IMAP password for the assistant's mailbox");
    await tick();
    const rid = eventsOf(c, "secret.request")[0].request_id;
    await h.router("secret.respond", { session_id, value: SENTINEL }, c);
    assert.equal(await toolP, `stored at ${ENTRY}`);
    // It DID reach the keyring — otherwise the sweep below proves nothing.
    assert.deepEqual(h.stored, [{ entry: ENTRY, value: SENTINEL }]);

    // 1. Every frame this client was ever sent, minus none — including the
    //    secret.request and secret.resolved events themselves.
    const allFrames = JSON.stringify(c._sent);
    assert.ok(!allFrames.includes(SENTINEL), "no emitted frame carries the value");
    assert.ok(!allFrames.includes("hunter2"), "not even a fragment");

    // 2. The transcript cache (what session.subscribe replays, and what the
    //    namer/digest/search all read).
    const cached = h.transcripts.replay(session_id);
    assert.ok(!JSON.stringify(cached).includes(SENTINEL), "transcript cache is clean");
    assert.equal(
      cached.filter((e: any) => e.params.type === "secret.request" || e.params.type === "secret.resolved").length,
      0,
      "secret.request is transient — it must not be in the replayable frame cache at all",
    );

    // 3. The message store (identity rows — what fork/undo/reply-to read).
    assert.ok(!JSON.stringify(h.sessions.messages.list(session_id)).includes(SENTINEL), "message store is clean");

    // 4. The FTS index — reconciled from the same transcript the router
    //    indexes at every turn end.
    h.search.reconcile(session_id, 0, cached);
    const find = (q: string) => h.search.search({ query: q, sort: "rank", limit: 20, offset: 0 }, [session_id]).total;
    assert.equal(find("S3NT1NEL"), 0, "search index is clean");
    assert.equal(find("hunter2"), 0);
    assert.equal(find(ENTRY), 0, "not even the entry name — secret.request never reaches the transcript to be indexed");

    // 5. The log. The entry NAME and the fact of a store ARE logged — that is
    //    the audit trail this feature is meant to leave — but never the value.
    const logText = h.logs.join("\n");
    assert.ok(!logText.includes(SENTINEL), `a log line leaked the value: ${logText}`);
    assert.ok(!logText.includes("hunter2"));
    assert.match(logText, new RegExp(`\\[secret\\] parked ${rid}: "${ENTRY}"`));
    assert.match(logText, new RegExp(`\\[secret\\] stored "${ENTRY}" for session ${session_id}`));
  } finally { h.cleanup(); }
});

test("NON-PERSISTENCE holds on the failure path too (an error message is the other place a value escapes)", async () => {
  const h = harness({
    // A backend that echoes what it was given — the shape keyring.ts redacts.
    storeSecret: async (_e: string, v: string) => { throw new Error(`insert failed: backend said ${v}`); },
  });
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    const res = (await h.router("secret.respond", { session_id, value: SENTINEL }, c)) as any;

    // NOTE: the router surfaces the thrown message as-is; keyring.ts is what
    // guarantees a REAL KeyringError is already redacted (see keyring.test.ts).
    // What this test pins is the surfaces the router itself owns: nothing the
    // failure path writes is durable.
    assert.equal(res.stored, false);
    await toolP;
    assert.ok(!JSON.stringify(h.transcripts.replay(session_id)).includes(SENTINEL), "transcript clean on the failure path");
    assert.ok(!JSON.stringify(h.sessions.messages.list(session_id)).includes(SENTINEL), "message store clean on the failure path");
  } finally { h.cleanup(); }
});

test("the parked request denies ITSELF after 10 minutes instead of holding the turn forever", async (t) => {
  // Approvals deliberately have no timeout (a human deciding is not a
  // failure); a credential prompt nobody answers has no other recovery path,
  // so this one does. Mocked timers so the assertion is the real code path,
  // not a stand-in for it.
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    assert.equal(h.sessions.get(session_id)!.runState, "awaiting_input");

    t.mock.timers.tick(9 * 60_000);
    assert.equal(h.sessions.get(session_id)!.runState, "awaiting_input", "still parked at 9 minutes — generous, not twitchy");

    t.mock.timers.tick(60_001);
    assert.match(await toolP, /timed out with no answer/);
    assert.equal(h.sessions.get(session_id)!.runState, "running", "the turn is released, not left parked");
    assert.equal(eventsOf(c, "secret.resolved")[0].outcome, "denied", "a timeout is a denial, never an auto-anything");
    assert.equal(h.stored.length, 0);
  } finally { h.cleanup(); }
});

test("answering cancels the timer — a settled request cannot fire a late denial", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    const toolP = tools(h).requestSecret(ENTRY, "why");
    await tick();
    await h.router("secret.respond", { session_id, value: SENTINEL }, c);
    assert.equal(await toolP, `stored at ${ENTRY}`);
    t.mock.timers.tick(30 * 60_000);
    assert.equal(eventsOf(c, "secret.resolved").length, 1, "no second resolution");
  } finally { h.cleanup(); }
});
