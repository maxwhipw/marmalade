// archive-indexer.test.ts — the pre-daemon ~/.claude/projects corpus:
// extraction rules against synthetic JSONL, and the scan driver's freshness
// contract (mtime/size skip, wholesale reindex, rebuild-from-nothing).
//
// Nothing here touches the real ~/.claude — every fixture is written into a
// temp dir.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, rmSync, writeFileSync, utimesSync } from "node:fs";
import { extractArchiveSession, scanArchive, listArchiveFiles } from "../dist/archive-indexer.js";
import { SearchStore } from "../dist/search-store.js";

/** One JSONL line, shaped like the real corpus. */
function line(o: Record<string, unknown>): string {
  return JSON.stringify(o);
}
const user = (text: unknown, extra: Record<string, unknown> = {}) =>
  line({ type: "user", uuid: `u-${randomUUID()}`, cwd: "/home/user/proj", timestamp: "2026-01-02T03:04:05.000Z", isSidechain: false, message: { role: "user", content: text }, ...extra });
const asst = (content: unknown, extra: Record<string, unknown> = {}) =>
  line({ type: "assistant", uuid: `a-${randomUUID()}`, cwd: "/home/user/proj", timestamp: "2026-01-02T03:04:06.000Z", isSidechain: false, message: { role: "assistant", content }, ...extra });

test("extraction: string content, text blocks, and the joins", () => {
  const e = extractArchiveSession([
    user("plain string prompt"),
    user([{ type: "text", text: "block prompt" }]),
    asst([{ type: "text", text: "first" }, { type: "text", text: "second" }]),
  ].join("\n"));
  assert.equal(e.messages.length, 3);
  assert.equal(e.messages[0].text, "plain string prompt");
  assert.equal(e.messages[0].role, "user");
  assert.equal(e.messages[1].text, "block prompt");
  // Several text blocks in ONE message are one row — the hit unit is the message.
  assert.equal(e.messages[2].text, "first\nsecond");
  assert.equal(e.messages[2].role, "assistant");
  assert.deepEqual(e.messages.map((m) => m.ordinal), [0, 1, 2]);
});

test("extraction: tool_result, tool_use, thinking and image blocks are not conversation", () => {
  const e = extractArchiveSession([
    user([{ type: "tool_result", content: "exit 0" }, { type: "image", source: {} }, { type: "text", text: "keep me" }]),
    asst([{ type: "thinking", thinking: "hidden reasoning" }, { type: "tool_use", name: "Bash", input: {} }]),
    asst([{ type: "text", text: "the answer" }]),
  ].join("\n"));
  assert.deepEqual(e.messages.map((m) => m.text), ["keep me", "the answer"]);
  // The all-tool_use assistant message contributes nothing and takes no ordinal.
  assert.deepEqual(e.messages.map((m) => m.ordinal), [0, 1]);
});

test("extraction: isMeta lines and synthetic command envelopes are skipped", () => {
  const e = extractArchiveSession([
    user([{ type: "text", text: "Base directory for this skill: /home/user/.claude/skills/x" }], { isMeta: true }),
    user("<command-name>/clear</command-name>"),
    user("<local-command-stdout>ok</local-command-stdout>"),
    user("<task-notification>agent finished</task-notification>"),
    user("a real question"),
  ].join("\n"));
  assert.deepEqual(e.messages.map((m) => m.text), ["a real question"]);
});

test("extraction: title comes from ai-title (last wins), then summary, then first user text", () => {
  const withAi = extractArchiveSession([
    user("some prompt"),
    line({ type: "ai-title", aiTitle: "first title", sessionId: "s" }),
    line({ type: "ai-title", aiTitle: "final title", sessionId: "s" }),
  ].join("\n"));
  assert.equal(withAi.title, "final title");

  const withSummary = extractArchiveSession([user("hello"), line({ type: "summary", summary: "A summary line" })].join("\n"));
  assert.equal(withSummary.title, "A summary line");

  const fallback = extractArchiveSession([user("short prompt")].join("\n"));
  assert.equal(fallback.title, "short prompt");

  const long = extractArchiveSession(user("x".repeat(200)));
  assert.equal(long.title, `${"x".repeat(80)}…`);
  assert.equal(long.title!.length, 81);
});

test("extraction: ISO timestamps become epoch ms, and lastTs is the newest", () => {
  const e = extractArchiveSession([
    user("one", { timestamp: "2026-01-02T03:04:05.000Z" }),
    asst([{ type: "text", text: "two" }], { timestamp: "2026-01-02T04:00:00.000Z" }),
  ].join("\n"));
  assert.equal(e.messages[0].ts, Date.parse("2026-01-02T03:04:05.000Z"));
  assert.equal(e.lastTs, Date.parse("2026-01-02T04:00:00.000Z"));
});

test("extraction: an empty file yields nothing, and a corrupt line never ends the parse", () => {
  assert.equal(extractArchiveSession("").messages.length, 0);
  assert.equal(extractArchiveSession("\n\n").messages.length, 0);

  // A half-written trailing line is NORMAL for a live file.
  const e = extractArchiveSession([user("before"), '{"type":"user","mess', asst([{ type: "text", text: "after" }])].join("\n"));
  assert.deepEqual(e.messages.map((m) => m.text), ["before", "after"]);
  assert.equal(e.badLines, 1);
  assert.deepEqual(e.messages.map((m) => m.ordinal), [0, 1]);
});

test("extraction: a sidechain first line disqualifies the whole file", () => {
  const e = extractArchiveSession([
    user("subagent prompt", { isSidechain: true }),
    asst([{ type: "text", text: "subagent answer" }], { isSidechain: true }),
  ].join("\n"));
  assert.equal(e.sidechain, true);
  assert.equal(e.messages.length, 0);
});

// ---- the scan driver -------------------------------------------------------

function archiveFixture() {
  const root = join(tmpdir(), `arch-${randomUUID()}`);
  const projA = join(root, "-home-max-proj-a");
  mkdirSync(projA, { recursive: true });
  const write = (dir: string, id: string, body: string) => {
    const p = join(dir, `${id}.jsonl`);
    writeFileSync(p, body);
    return p;
  };
  return { root, projA, write, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

const tight = { breathe: async () => {} };

test("scan: only top-level <dir>/<uuid>.jsonl is a session — subagent sidechains never are", async (t) => {
  const f = archiveFixture();
  t.after(f.cleanup);
  const sid = randomUUID();
  f.write(f.projA, sid, user("top level capybara"));
  // …/<uuid>/subagents/agent-*.jsonl — the same work seen from inside a Task.
  const side = join(f.projA, sid, "subagents");
  mkdirSync(side, { recursive: true });
  writeFileSync(join(side, "agent-deadbeef.jsonl"), user("subagent capybara"));

  const files = await listArchiveFiles(f.root);
  assert.deepEqual(files, [join(f.projA, `${sid}.jsonl`)]);

  const store = new SearchStore(":memory:");
  t.after(() => store.close());
  const s = await scanArchive(store, f.root, tight);
  assert.deepEqual(s, { indexed: 1, skipped: 0, empty: 0, failed: 0 });
  const hits = store.searchArchive({ query: "capybara", sort: "rank", limit: 20, offset: 0 }, [sid]);
  assert.equal(hits.total, 1);
});

test("scan: unchanged files are skipped; a touched file reindexes without doubling", async (t) => {
  const f = archiveFixture();
  t.after(f.cleanup);
  const store = new SearchStore(":memory:");
  t.after(() => store.close());
  const sid = randomUUID();
  const path = f.write(f.projA, sid, [user("wombat one"), asst([{ type: "text", text: "wombat two" }])].join("\n"));

  assert.deepEqual(await scanArchive(store, f.root, tight), { indexed: 1, skipped: 0, empty: 0, failed: 0 });
  // Same (mtime, size) → pure skip. This is what makes a normal restart cheap.
  assert.deepEqual(await scanArchive(store, f.root, tight), { indexed: 0, skipped: 1, empty: 0, failed: 0 });
  assert.equal(store.searchArchive({ query: "wombat", sort: "rank", limit: 20, offset: 0 }, [sid]).total, 2);

  // The file grows (Claude Code is still appending) → wholesale reindex.
  writeFileSync(path, [user("wombat one"), asst([{ type: "text", text: "wombat two" }]), user("wombat three")].join("\n"));
  assert.deepEqual(await scanArchive(store, f.root, tight), { indexed: 1, skipped: 0, empty: 0, failed: 0 });
  const after = store.searchArchive({ query: "wombat", sort: "rank", limit: 20, offset: 0 }, [sid]);
  assert.equal(after.total, 3, "reindex REPLACES — the first two rows are not duplicated");
  assert.equal(store.archiveSession(sid)!.messageCount, 3);

  // Same size, newer mtime still reindexes: content can change without growing.
  const rewritten = [user("badger one"), asst([{ type: "text", text: "wombat two" }]), user("wombat three")].join("\n");
  writeFileSync(path, rewritten);
  utimesSync(path, new Date(Date.now() + 60_000), new Date(Date.now() + 60_000));
  await scanArchive(store, f.root, tight);
  assert.equal(store.searchArchive({ query: "badger", sort: "rank", limit: 20, offset: 0 }, [sid]).total, 1);
});

test("scan: a fresh store rebuilds the whole corpus — search.db is disposable", async (t) => {
  const f = archiveFixture();
  t.after(f.cleanup);
  const ids = [randomUUID(), randomUUID()];
  f.write(f.projA, ids[0], user("numbat alpha"));
  f.write(f.projA, ids[1], user("numbat beta"));

  const first = new SearchStore(":memory:");
  assert.equal((await scanArchive(first, f.root, tight)).indexed, 2);
  first.close();

  // "rm search.db" — nothing carries over, so everything is indexed again.
  const rebuilt = new SearchStore(":memory:");
  t.after(() => rebuilt.close());
  assert.deepEqual(await scanArchive(rebuilt, f.root, tight), { indexed: 2, skipped: 0, empty: 0, failed: 0 });
  assert.equal(rebuilt.searchArchive({ query: "numbat", sort: "rank", limit: 20, offset: 0 }, ids).total, 2);
});

test("scan: text-free and sidechain files get no session row; a missing dir is a clean no-op", async (t) => {
  const f = archiveFixture();
  t.after(f.cleanup);
  const store = new SearchStore(":memory:");
  t.after(() => store.close());
  const empty = randomUUID();
  const toolsOnly = randomUUID();
  const chain = randomUUID();
  f.write(f.projA, empty, "");
  f.write(f.projA, toolsOnly, asst([{ type: "tool_use", name: "Bash", input: {} }]));
  f.write(f.projA, chain, user("sidechain text", { isSidechain: true }));

  const s = await scanArchive(store, f.root, tight);
  assert.deepEqual(s, { indexed: 0, skipped: 0, empty: 3, failed: 0 });
  for (const id of [empty, toolsOnly, chain]) assert.equal(store.archiveSession(id), undefined);

  assert.deepEqual(
    await scanArchive(store, join(f.root, "does-not-exist"), tight),
    { indexed: 0, skipped: 0, empty: 0, failed: 0 },
  );
});

test("store: archiveMessages pages in ordinal order past the 10-boundary", async (t) => {
  const f = archiveFixture();
  t.after(f.cleanup);
  const store = new SearchStore(":memory:");
  t.after(() => store.close());
  const sid = randomUUID();
  f.write(f.projA, sid, Array.from({ length: 12 }, (_, i) => user(`quoll ${i}`)).join("\n"));
  await scanArchive(store, f.root, tight);

  const all = store.archiveMessages(sid, 100, 0);
  assert.equal(all.total, 12);
  // Ordinals must sort NUMERICALLY — a text sort would put 10 before 2.
  assert.deepEqual(all.messages.map((m) => m.ordinal), [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);
  assert.deepEqual(all.messages.map((m) => m.text), Array.from({ length: 12 }, (_, i) => `quoll ${i}`));

  const page = store.archiveMessages(sid, 5, 8);
  assert.equal(page.total, 12, "total is the whole session, not the page");
  assert.deepEqual(page.messages.map((m) => m.ordinal), [8, 9, 10, 11]);
});
