// archive-router.test.ts — the archive corpus through the ROUTER:
// scope.corpus="archive" on search.messages, the workspace matcher applied to
// archive cwds, the live-twin dedupe, and the read-only search.archive viewer.
//
// The two corpora must not leak into each other, so every test asserts both
// directions.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { SearchStore } from "../dist/search-store.js";
import { scanArchive } from "../dist/archive-indexer.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";

function echoAdapter() {
  let n = 0;
  return {
    name: "fake",
    supportsResumeAt: true,
    spawn(_spec: unknown, opts: any, cb: any) {
      cb.onHarnessSession(opts.resumeHarnessSessionId ?? `h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n(\[mid-turn steer[^\]]*\]\n)?\n/, "");
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          cb.onHarnessMessageUuid(`hu-${++n}`);
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
  const home = join(tmpdir(), `arch-r-${randomUUID()}`);
  const archiveRoot = join(home, ".claude-archive");
  mkdirSync(join(home, "coding", "proj-a"), { recursive: true });
  mkdirSync(join(home, "coding", "proj-b"), { recursive: true });
  mkdirSync(join(archiveRoot, "-proj"), { recursive: true });
  const sessions = SessionManager.inMemory({ workspaceHome: home });
  const search = new SearchStore(":memory:");
  let n = 0;
  const now = { t: 1000 };
  const router = createRouter({
    cfg: defaultConfig({}),
    sessions,
    transcripts: new TranscriptCache(join(home, ".transcripts")),
    usage: new UsageMeter(),
    search,
    adapter: echoAdapter(),
    today: () => "2026-07-28",
    now: () => ++now.t,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = (principal = "owner") =>
    ({ ws: { send: () => {} }, principal, legacy: false, capabilities: [] } as any);

  /** Drop one synthetic archive transcript into the fixture corpus. */
  const archive = (sessionId: string, cwd: string, texts: { role: "user" | "assistant"; text: string }[], title?: string) => {
    const lines = texts.map((m, i) => JSON.stringify({
      type: m.role,
      uuid: `${sessionId}-${i}`,
      cwd,
      timestamp: new Date(1_700_000_000_000 + i * 1000).toISOString(),
      isSidechain: false,
      message: { role: m.role, content: m.role === "user" ? m.text : [{ type: "text", text: m.text }] },
    }));
    if (title) lines.push(JSON.stringify({ type: "ai-title", aiTitle: title, sessionId }));
    writeFileSync(join(archiveRoot, "-proj", `${sessionId}.jsonl`), lines.join("\n"));
    return sessionId;
  };
  const scan = () => scanArchive(search, archiveRoot, { breathe: async () => {} });

  return {
    router, sessions, search, home, archiveRoot, conn, archive, scan,
    cleanup: () => { sessions.close(); search.close(); rmSync(home, { recursive: true, force: true }); },
  };
}

const find = (h: ReturnType<typeof harness>, params: Record<string, unknown>) =>
  h.router("search.messages", params, h.conn()) as Promise<any>;

async function talk(h: ReturnType<typeof harness>, cwd: string | undefined, prompt: string) {
  const c = h.conn();
  const { session_id } = (await h.router("session.create", cwd ? { cwd } : {}, c)) as any;
  await h.router("prompt.submit", { session_id, prompt }, c);
  return session_id as string;
}

test("the two corpora are separate: neither one's text appears in the other's results", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const aid = h.archive(randomUUID(), join(h.home, "coding", "proj-a"), [
    { role: "user", text: "the platypus question" },
    { role: "assistant", text: "a platypus answer" },
  ], "Platypus chat");
  await h.scan();
  await talk(h, undefined, "the pangolin question");

  // Default (no corpus) is the live path, byte-for-byte as before.
  const live = await find(h, { query: "platypus" });
  assert.equal(live.total, 0, "archive text is invisible to a live search");
  const livePangolin = await find(h, { query: "pangolin" });
  assert.equal(livePangolin.total, 2);
  assert.equal(livePangolin.sessions[Object.keys(livePangolin.sessions)[0]].corpus, undefined);

  const arch = await find(h, { query: "platypus", scope: { corpus: "archive" } });
  assert.equal(arch.total, 2);
  assert.ok(arch.hits.every((x: any) => x.session_id === aid));
  assert.deepEqual(arch.hits.map((x: any) => x.seq).sort(), [0, 1], "seq carries the archive ordinal");
  assert.equal(arch.hits[0].message_id.startsWith(aid), true, "message_id carries the line uuid");
  assert.equal(arch.sessions[aid].corpus, "archive");
  assert.equal(arch.sessions[aid].title, "Platypus chat");
  assert.equal(arch.sessions[aid].archived, false);

  // …and the live corpus is invisible from the archive scope.
  assert.equal((await find(h, { query: "pangolin", scope: { corpus: "archive" } })).total, 0);
  // corpus: "live" spelled out is the same as omitting it.
  assert.equal((await find(h, { query: "platypus", scope: { corpus: "live" } })).total, 0);
});

test("archive hits carry no reply_text — replyTo is live-corpus machinery", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  h.archive(randomUUID(), join(h.home, "coding", "proj-a"), [
    { role: "user", text: "what is a quokka" },
    { role: "assistant", text: "a quokka is a marsupial" },
  ]);
  await h.scan();
  const r = await find(h, { query: "quokka", role: "user", scope: { corpus: "archive" } });
  assert.equal(r.hits.length, 1);
  assert.equal(r.hits[0].reply_text, undefined);
});

test("archive scope runs through the SAME workspace matcher as live sessions", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const a = join(h.home, "coding", "proj-a");
  const b = join(h.home, "coding", "proj-b");
  const wA = ((await h.router("workspace.create", { path: a }, h.conn())) as any).workspace.workspace_id;
  const wB = ((await h.router("workspace.create", { path: b }, h.conn())) as any).workspace.workspace_id;

  const inA = h.archive(randomUUID(), a, [{ role: "user", text: "badger in a" }]);
  const inB = h.archive(randomUUID(), b, [{ role: "user", text: "badger in b" }]);
  const quick = h.archive(randomUUID(), h.home, [{ role: "user", text: "badger nowhere" }]);
  await h.scan();

  const ids = async (scope: Record<string, unknown>) => {
    const r = await find(h, { query: "badger", scope: { corpus: "archive", ...scope } });
    return [...new Set(r.hits.map((x: any) => x.session_id as string))].sort();
  };
  assert.deepEqual(await ids({}), [inA, inB, quick].sort(), "unscoped = the whole corpus");
  assert.deepEqual(await ids({ workspace_ids: [wA] }), [inA]);
  assert.deepEqual(await ids({ workspace_ids: [wA, wB] }), [inA, inB].sort());
  assert.deepEqual(await ids({ quick_chats: true }), [quick], "matched no workspace");
  // The fields OR together, exactly as on the live path.
  assert.deepEqual(await ids({ workspace_ids: [wA], quick_chats: true }), [inA, quick].sort());
  // session_ids name ARCHIVE ids here.
  assert.deepEqual(await ids({ session_ids: [inB] }), [inB]);
  assert.deepEqual(await ids({ session_ids: [inB], workspace_ids: [wA] }), [inA, inB].sort());
  // The workspace chip is stamped from the archive session's cwd.
  const r = await find(h, { query: "badger", scope: { corpus: "archive", workspace_ids: [wA] } });
  assert.equal(r.sessions[inA].workspace_id, wA);
});

test("an archive session a live session replays is hidden — found once, where it can be opened", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  // The fake adapter binds harness session `h-<daemon id>`; s_1 is the first
  // minted id, so this archive file IS that live session's history.
  const twin = h.archive("h-s_1", join(h.home, "coding", "proj-a"), [{ role: "user", text: "echidna twin" }]);
  const solo = h.archive(randomUUID(), join(h.home, "coding", "proj-a"), [{ role: "user", text: "echidna solo" }]);
  await h.scan();

  const before = await find(h, { query: "echidna", scope: { corpus: "archive" } });
  assert.deepEqual([...new Set(before.hits.map((x: any) => x.session_id))].sort(), [twin, solo].sort());

  const live = await talk(h, undefined, "echidna live");
  assert.equal(h.sessions.get(live)!.harnessSessionId, "h-s_1", "the fixture assumption still holds");

  const after = await find(h, { query: "echidna", scope: { corpus: "archive" } });
  assert.deepEqual([...new Set(after.hits.map((x: any) => x.session_id))], [solo], "the twin is gone");
  assert.equal(after.sessions[twin], undefined);
  // Explicitly naming the twin does not bring it back — dedupe is not a filter
  // the caller can opt out of.
  assert.equal((await find(h, { query: "echidna", scope: { corpus: "archive", session_ids: [twin] } })).total, 0);
});

test("the dedupe spans principals — a twin of ANYONE's live session is hidden", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const twin = h.archive("h-s_1", join(h.home, "coding", "proj-a"), [{ role: "user", text: "numbat twin" }]);
  await h.scan();
  await talk(h, undefined, "numbat live");
  assert.equal(h.sessions.get("s_1")!.harnessSessionId, "h-s_1");

  // A DIFFERENT principal cannot see max's live session at all, so the naive
  // "dedupe against what you can see" would hand it the stale archive copy.
  const other = h.conn("someone-else");
  const live = (await h.router("search.messages", { query: "numbat" }, other)) as any;
  assert.equal(live.total, 0, "the live row is invisible to this principal");
  const arch = (await h.router("search.messages", { query: "numbat", scope: { corpus: "archive" } }, other)) as any;
  assert.equal(arch.total, 0, "…and so is its archive twin");
  assert.equal(arch.sessions[twin], undefined);
});

test("role, since, sort and paging behave identically in the archive corpus", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const sid = h.archive(randomUUID(), join(h.home, "coding", "proj-a"), [
    { role: "user", text: "wombat one" },
    { role: "assistant", text: "wombat two" },
    { role: "user", text: "wombat three" },
  ]);
  await h.scan();
  const base = { query: "wombat", scope: { corpus: "archive" } };

  assert.equal((await find(h, { ...base, role: "user" })).total, 2);
  assert.equal((await find(h, { ...base, role: "assistant" })).total, 1);

  const recent = await find(h, { ...base, sort: "recent" });
  assert.deepEqual(recent.hits.map((x: any) => x.seq), [2, 1, 0]);

  const page = await find(h, { ...base, sort: "recent", limit: 2, offset: 1 });
  assert.equal(page.total, 3, "total is the whole match set, not the page");
  assert.deepEqual(page.hits.map((x: any) => x.seq), [1, 0]);

  // ts came from the ISO timestamps, so `since` is a real cut.
  const cut = 1_700_000_000_000 + 1500;
  const since = await find(h, { ...base, since: cut });
  assert.deepEqual(since.hits.map((x: any) => x.seq), [2]);
  assert.equal(since.sessions[sid].last_active, 1_700_000_000_000 + 2000);
});

test("search.archive serves the transcript from the index, in ordinal order, paged", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const cwd = join(h.home, "coding", "proj-a");
  const sid = h.archive(randomUUID(), cwd,
    Array.from({ length: 12 }, (_, i) => ({ role: (i % 2 ? "assistant" : "user") as "user" | "assistant", text: `line ${i}` })),
    "A long chat");
  await h.scan();

  const r = (await h.router("search.archive", { session_id: sid }, h.conn())) as any;
  assert.deepEqual(r.session, { title: "A long chat", cwd, last_active: 1_700_000_000_000 + 11_000, message_count: 12 });
  assert.equal(r.total, 12);
  assert.deepEqual(r.messages.map((m: any) => m.ordinal), [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);
  assert.deepEqual(r.messages.map((m: any) => m.text), Array.from({ length: 12 }, (_, i) => `line ${i}`));
  assert.deepEqual(r.messages.map((m: any) => m.role).slice(0, 3), ["user", "assistant", "user"]);
  assert.equal(r.messages[0].ts, 1_700_000_000_000);

  const page = (await h.router("search.archive", { session_id: sid, limit: 3, offset: 9 }, h.conn())) as any;
  assert.equal(page.total, 12);
  assert.deepEqual(page.messages.map((m: any) => m.ordinal), [9, 10, 11]);

  await assert.rejects(
    h.router("search.archive", { session_id: "no-such-session" }, h.conn()),
    /archive session no-such-session not found/,
  );
});

test("search.archive 404s when the daemon has no search store wired", async (t) => {
  const home = join(tmpdir(), `arch-nostore-${randomUUID()}`);
  mkdirSync(home, { recursive: true });
  t.after(() => rmSync(home, { recursive: true, force: true }));
  const sessions = SessionManager.inMemory({ workspaceHome: home });
  const router = createRouter({
    cfg: defaultConfig({}),
    sessions,
    transcripts: new TranscriptCache(join(home, ".transcripts")),
    usage: new UsageMeter(),
    adapter: echoAdapter(),
    today: () => "2026-07-28",
    now: () => 1000,
    mintSessionId: () => "s_1",
  } as any);
  const conn = { ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any;
  await assert.rejects(router("search.archive", { session_id: "x" }, conn), /search not configured/);
  await assert.rejects(
    router("search.messages", { query: "anything", scope: { corpus: "archive" } }, conn),
    /search not configured/,
  );
  sessions.close();
});
