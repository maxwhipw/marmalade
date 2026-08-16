// search-router.test.ts — search.messages end to end through the router:
// the principal + archived + scope resolution, the reply preview, and the
// index-maintenance hooks (turn end, delete, clear, undo, fork, boot).
//
// The fake adapter answers every prompt with "echo:<prompt>", so a real turn
// writes real transcript events and the router's turn-end hook indexes them —
// nothing here reaches into the store to fake a corpus.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, rmSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { SearchStore, maxEventSeq } from "../dist/search-store.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";

function echoAdapter(state: { uuidN: number }) {
  return {
    name: "fake",
    supportsResumeAt: true,
    async forkSession() {
      return { harnessSessionId: `h-forked-${randomUUID()}` };
    },
    spawn(_spec: unknown, opts: any, cb: any) {
      cb.onHarnessSession(opts.resumeHarnessSessionId ?? `h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n(\[mid-turn steer[^\]]*\]\n)?\n/, "");
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          cb.onHarnessMessageUuid(`hu-${++state.uuidN}`);
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  } as any;
}

function harness() {
  const home = join(tmpdir(), `srch-${randomUUID()}`);
  mkdirSync(join(home, "coding", "proj-a", "inner"), { recursive: true });
  mkdirSync(join(home, "coding", "proj-b"), { recursive: true });
  const sessions = SessionManager.inMemory({ workspaceHome: home });
  const transcripts = new TranscriptCache(join(home, ".transcripts"));
  const search = new SearchStore(":memory:");
  const state = { uuidN: 0 };
  let n = 0;
  const now = { t: 1000 };
  const router = createRouter({
    cfg: defaultConfig({}),
    sessions, transcripts, usage: new UsageMeter(), search,
    adapter: echoAdapter(state),
    today: () => "2026-07-24",
    now: () => ++now.t,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = (principal = "owner") =>
    ({ ws: { send: () => {} }, principal, legacy: false, capabilities: [] } as any);
  return {
    router, sessions, transcripts, search, home, conn,
    cleanup: () => { sessions.close(); search.close(); rmSync(home, { recursive: true, force: true }); },
  };
}

/** Create a session in `cwd` and run one turn through it. */
async function talk(h: ReturnType<typeof harness>, cwd: string | undefined, prompt: string, c = h.conn()) {
  const { session_id } = (await h.router("session.create", cwd ? { cwd } : {}, c)) as any;
  await h.router("prompt.submit", { session_id, prompt }, c);
  return session_id as string;
}

const search = (h: ReturnType<typeof harness>, params: Record<string, unknown>, c = h.conn()) =>
  h.router("search.messages", params, c) as Promise<any>;

test("a completed turn is searchable — both halves, with the deep-link tuple", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const sid = await talk(h, undefined, "the pangolin question");

  const r = await search(h, { query: "pangolin" });
  assert.equal(r.total, 2, "the user prompt AND the assistant echo");
  const user = r.hits.find((x: any) => x.role === "user");
  const asst = r.hits.find((x: any) => x.role === "assistant");
  assert.equal(user.session_id, sid);
  assert.equal(user.text, "the pangolin question");
  assert.equal(asst.text, "echo:the pangolin question");
  assert.ok(user.message_id && user.seq > 0 && user.ts > 0, "deep-link tuple is complete");
  // Session context rides along so the client needs no second call.
  assert.deepEqual(Object.keys(r.sessions), [sid]);
  assert.equal(r.sessions[sid].workspace_id, null);
  assert.equal(r.sessions[sid].archived, false);
});

test("a user hit carries the answering assistant message as reply_text", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  await talk(h, undefined, "what is a quokka");

  const r = await search(h, { query: "quokka", role: "user" });
  assert.equal(r.hits.length, 1);
  assert.equal(r.hits[0].reply_text, "echo:what is a quokka");
  // An assistant hit has no reply of its own.
  const a = await search(h, { query: "quokka", role: "assistant" });
  assert.equal(a.hits[0].reply_text, undefined);
});

test("scope: workspace multi-select, quick chats, and deepest-wins nesting", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const outer = join(h.home, "coding", "proj-a");
  const inner = join(outer, "inner");
  const other = join(h.home, "coding", "proj-b");
  const wOuter = ((await h.router("workspace.create", { path: outer }, h.conn())) as any).workspace.workspace_id;
  const wInner = ((await h.router("workspace.create", { path: inner }, h.conn())) as any).workspace.workspace_id;
  const wOther = ((await h.router("workspace.create", { path: other }, h.conn())) as any).workspace.workspace_id;

  const sOuter = await talk(h, outer, "badger outer");
  const sInner = await talk(h, inner, "badger inner");
  const sOther = await talk(h, other, "badger other");
  const sQuick = await talk(h, h.home, "badger quick");

  const ids = async (params: Record<string, unknown>) => {
    const r = await search(h, { query: "badger", ...params });
    return [...new Set(r.hits.map((x: any) => x.session_id))].sort();
  };

  assert.deepEqual(await ids({}), [sInner, sOther, sOuter, sQuick].sort(), "no scope = everywhere");
  // Deepest-wins: the nested session belongs to the INNER workspace, so
  // scoping to the umbrella folder excludes it (same rule as session.list).
  assert.deepEqual(await ids({ scope: { workspace_ids: [wOuter] } }), [sOuter]);
  assert.deepEqual(await ids({ scope: { workspace_ids: [wInner] } }), [sInner]);
  assert.deepEqual(await ids({ scope: { workspace_ids: [wOuter, wOther] } }), [sOther, sOuter].sort());
  assert.deepEqual(await ids({ scope: { quick_chats: true } }), [sQuick]);
  // The fields OR together.
  assert.deepEqual(await ids({ scope: { workspace_ids: [wInner], quick_chats: true } }), [sInner, sQuick].sort());
  // An empty scope object is the same as no scope at all.
  assert.deepEqual(await ids({ scope: {} }), [sInner, sOther, sOuter, sQuick].sort());
});

test("scope: session_ids is find-in-conversation, and never bypasses the principal", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const mine = await talk(h, undefined, "wombat here");
  // v0.1's policy factory refuses to SPAWN for a non-max principal, so the
  // other principal's session is seeded straight into the index + transcript —
  // the thing under test is the search filter, not the spawn gate.
  const theirs = "s_theirs";
  h.sessions.create(theirs, {
    principal: "someone-else", purpose: "coding", authClass: "local", origin: "text",
    cwd: h.home, authContext: { home: h.home, claudeConfigDir: h.home, authClass: "local" },
  } as any, "fake", 900);
  const theirEvents = [{
    jsonrpc: "2.0", method: "event",
    params: { type: "message.user", payload: { message_id: "u_theirs", seq: 1, ts: 900, text: "wombat there" }, session_id: theirs },
  }] as any[];
  for (const e of theirEvents) h.transcripts.append(theirs, e);
  h.search.reconcile(theirs, maxEventSeq(theirEvents), theirEvents);

  const one = await search(h, { query: "wombat", scope: { session_ids: [mine] } });
  assert.deepEqual([...new Set(one.hits.map((x: any) => x.session_id))], [mine]);

  // Naming another principal's session explicitly must return nothing — the
  // intersection is with what THIS connection may see, not a widening.
  const stolen = await search(h, { query: "wombat", scope: { session_ids: [theirs] } });
  assert.equal(stolen.total, 0);
  assert.deepEqual(stolen.sessions, {});
  // …and an unscoped search never leaks it either.
  const all = await search(h, { query: "wombat" });
  assert.deepEqual([...new Set(all.hits.map((x: any) => x.session_id))], [mine]);
  // The other principal sees only their own.
  const other = await search(h, { query: "wombat" }, h.conn("someone-else"));
  assert.deepEqual([...new Set(other.hits.map((x: any) => x.session_id))], [theirs]);
});

test("archived sessions are out by default and back in with include_archived", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const live = await talk(h, undefined, "marmot alive");
  const old = await talk(h, undefined, "marmot archived");
  await h.router("session.archive", { session_id: old, archived: true }, h.conn());

  const def = await search(h, { query: "marmot" });
  assert.deepEqual([...new Set(def.hits.map((x: any) => x.session_id))], [live]);
  const inc = await search(h, { query: "marmot", include_archived: true });
  assert.deepEqual([...new Set(inc.hits.map((x: any) => x.session_id))].sort(), [live, old].sort());
  assert.equal(inc.sessions[old].archived, true);
});

test("sort, paging and total are honoured through the router", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const first = await talk(h, undefined, "capybara one");
  const second = await talk(h, undefined, "capybara two");

  const recent = await search(h, { query: "capybara", sort: "recent", role: "user" });
  assert.deepEqual(recent.hits.map((x: any) => x.session_id), [second, first]);
  const page = await search(h, { query: "capybara", sort: "recent", role: "user", limit: 1, offset: 1 });
  assert.equal(page.total, 2);
  assert.deepEqual(page.hits.map((x: any) => x.session_id), [first]);
});

test("a query that sanitizes away returns empty rather than erroring", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  await talk(h, undefined, "aardvark");
  const r = await search(h, { query: `"""` });
  assert.deepEqual(r, { total: 0, hits: [], sessions: {} });
  // …and the min-length guard rejects a one-character query outright.
  await assert.rejects(search(h, { query: "a" }), /./);
});

// ── Index maintenance hooks ────────────────────────────────────────────────

test("session.delete drops the session's rows from the index", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const sid = await talk(h, undefined, "ocelot secret");
  assert.equal((await search(h, { query: "ocelot" })).total, 2);

  await h.router("session.delete", { session_id: sid }, h.conn());
  assert.equal((await search(h, { query: "ocelot" })).total, 0, "a hit that can't open a session is garbage");
});

test("session.clear drops the index rows but keeps the session searchable again", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const sid = await talk(h, undefined, "lemur before");
  await h.router("session.clear", { session_id: sid }, h.conn());
  assert.equal((await search(h, { query: "lemur" })).total, 0);

  // The id survives a clear, so the NEXT turn must index from scratch — this
  // is where a stale watermark would silently swallow the new conversation.
  await h.router("prompt.submit", { session_id: sid, prompt: "lemur after" }, h.conn());
  const r = await search(h, { query: "lemur" });
  assert.equal(r.total, 2);
  assert.ok(r.hits.every((x: any) => x.text.includes("after")));
});

test("session.undo un-indexes the popped turn and keeps the rest", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const c = h.conn();
  const sid = await talk(h, undefined, "tapir kept", c);
  await h.router("prompt.submit", { session_id: sid, prompt: "tapir popped" }, c);
  assert.equal((await search(h, { query: "tapir" })).total, 4);

  await h.router("session.undo", { session_id: sid }, c);
  const r = await search(h, { query: "tapir" });
  assert.equal(r.total, 2);
  assert.ok(r.hits.every((x: any) => x.text.includes("kept")), "only the surviving turn is findable");
});

test("a fork's copied history is indexed under the NEW session id", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const c = h.conn();
  const src = await talk(h, undefined, "gerbil history", c);
  const forked = (await h.router("session.fork", { session_id: src }, c)) as any;

  const r = await search(h, { query: "gerbil" });
  const bySession = new Set(r.hits.map((x: any) => x.session_id));
  assert.deepEqual([...bySession].sort(), [forked.session_id, src].sort());
  // Copied messages are remapped to new ids — the fork's hits must not point
  // at the source's message ids.
  const srcIds = new Set(r.hits.filter((x: any) => x.session_id === src).map((x: any) => x.message_id));
  for (const hit of r.hits.filter((x: any) => x.session_id === forked.session_id)) {
    assert.ok(!srcIds.has(hit.message_id), "fork hits carry the fork's own message ids");
  }
});

test("the boot reconcile rebuilds a deleted index and skips orphan transcripts", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const sid = await talk(h, undefined, "civet indexed");
  // An orphan NDJSON with no sessions.db row — the state a crash or an
  // out-of-band delete leaves behind.
  h.transcripts.append("s_orphan", {
    jsonrpc: "2.0", method: "event",
    params: { type: "message.user", payload: { message_id: "u9", seq: 1, ts: 5, text: "civet orphaned" }, session_id: "s_orphan" },
  } as any);

  // Wipe the index the way `rm search.db` does, then run the boot pass from
  // index.ts: iterate sessions.list() ONLY (never the transcript directory).
  h.search.dropSession(sid);
  assert.equal((await search(h, { query: "civet" })).total, 0);
  for (const rec of h.sessions.list()) {
    const events = h.transcripts.replay(rec.id);
    h.search.reconcile(rec.id, maxEventSeq(events), events);
  }

  const r = await search(h, { query: "civet" });
  assert.equal(r.total, 2, "the real session came back");
  assert.ok(r.hits.every((x: any) => x.session_id === sid), "the orphan transcript was never indexed");
});

test("search.messages 404s when the daemon has no search store wired", async (t) => {
  const home = join(tmpdir(), `srch-nostore-${randomUUID()}`);
  mkdirSync(home, { recursive: true });
  t.after(() => rmSync(home, { recursive: true, force: true }));
  const sessions = SessionManager.inMemory({ workspaceHome: home });
  const router = createRouter({
    cfg: defaultConfig({}),
    sessions,
    transcripts: new TranscriptCache(join(home, ".transcripts")),
    usage: new UsageMeter(),
    adapter: echoAdapter({ uuidN: 0 }),
    today: () => "2026-07-24",
    now: () => 1000,
    mintSessionId: () => "s_1",
  } as any);
  await assert.rejects(
    router("search.messages", { query: "anything" }, { ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any),
    /search not configured/,
  );
  sessions.close();
});
