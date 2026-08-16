// search-store.test.ts — the FTS5 search sidecar (search-store.ts): MATCH-
// expression safety, the extraction rules (what is and is NOT message text),
// the watermark reconcile in both directions, and result shaping.
//
// The correctness spine is extraction: index `message.user` and CONSOLIDATED
// `message.delta` only, with `message.complete` as the no-delta fallback.
// Everything else — raw deltas, thinking, tools, status — stays out.

import { test } from "node:test";
import assert from "node:assert/strict";
import { SearchStore, extractMessages, buildMatchExpression, maxEventSeq } from "../dist/search-store.js";
import { SNIPPET_OPEN, SNIPPET_CLOSE } from "@marmalade/protocol";

function ev(type: string, payload: Record<string, unknown>, sessionId = "s1"): any {
  return { jsonrpc: "2.0", method: "event", params: { type, payload, session_id: sessionId } };
}

/** A settled turn as it looks on disk AFTER compaction. */
function turn(n: number, userText: string, assistantText: string, sessionId = "s1") {
  const base = n * 10;
  return [
    ev("message.user", { message_id: `u${n}`, seq: base + 1, ts: 1000 + base, text: userText }, sessionId),
    ev("message.delta", {
      message_id: `a${n}`, seq: base + 3, first_seq: base + 2, ts: 1000 + base + 3,
      text: assistantText, consolidated: true,
    }, sessionId),
    ev("message.complete", { message_id: `a${n}`, seq: base + 4, ts: 1000 + base + 4 }, sessionId),
  ];
}

function query(store: any, q: string, extra: Record<string, unknown> = {}, allowed = ["s1"]) {
  return store.search({ query: q, sort: "rank", limit: 20, offset: 0, ...extra }, allowed);
}

// ── MATCH-expression safety ────────────────────────────────────────────────

test("buildMatchExpression quotes every term — FTS operators arrive as literals", () => {
  assert.equal(buildMatchExpression("hello world"), `"hello" AND "world"`);
  // Operators are terms, not syntax.
  assert.equal(buildMatchExpression("cat OR dog"), `"cat" AND "OR" AND "dog"`);
  assert.equal(buildMatchExpression("a NEAR b"), `"a" AND "NEAR" AND "b"`);
  assert.equal(buildMatchExpression("-foo (bar)"), `"foo" AND "bar"`);
  // Paired quotes are phrases; a trailing `*` survives as the prefix marker.
  assert.equal(buildMatchExpression(`"seq high water" tail`), `"seq high water" AND "tail"`);
  assert.equal(buildMatchExpression("marma*"), `"marma"*`);
  // `_` is kept, and unicode61 splits on it INSIDE the quotes — so an
  // identifier queries as a phrase, which is what makes code-flavoured chat
  // searchable.
  assert.equal(buildMatchExpression("seq_high_water"), `"seq_high_water"`);
});

test("an identifier matches as a phrase, not as three loose words", () => {
  const store = new SearchStore(":memory:");
  store.reconcile("s1", 14, turn(1, "q", "the seq_high_water mark moved"));
  assert.equal(query(store, "seq_high_water").total, 1);
  // The words in the wrong order are NOT the phrase.
  assert.equal(query(store, "water_high_seq").total, 0);
  store.close();
});

test("Unicode queries survive sanitizing and match — Japanese, accents, folding", () => {
  // The sanitize class is \p{L}\p{N}_, not ASCII: an ASCII class would cut
  // "café" to "caf" and reduce a Japanese query to "" (silent empty result).
  assert.equal(buildMatchExpression("café"), `"café"`);
  assert.equal(buildMatchExpression("こんにちは世界"), `"こんにちは世界"`);
  const store = new SearchStore(":memory:");
  store.indexTail("s1", turn(1, "the café greeting", "こんにちは世界 was the reply"));
  // An unbroken CJK run is ONE unicode61 token, so the same run matches at
  // its boundary (mid-token would need a trigram tokenizer — documented).
  assert.equal(query(store, "こんにちは世界").total, 1);
  assert.equal(query(store, "café").total, 1);
  // remove_diacritics folds both sides: the unaccented query finds it too.
  assert.equal(query(store, "cafe").total, 1);
  store.close();
});

test("buildMatchExpression returns empty when nothing survives sanitizing", () => {
  for (const junk of [`"`, `"""`, `- ( ) ^ :`, `   `, `***`, `""`]) {
    assert.equal(buildMatchExpression(junk), "", `junk input: ${JSON.stringify(junk)}`);
  }
});

test("hostile queries cannot crash the search or alter its semantics", () => {
  const store = new SearchStore(":memory:");
  store.indexTail("s1", turn(1, "find the needle", "the needle is here"));

  // A lone quote / operator soup must return an empty or literal-term result,
  // never throw: an FTS syntax error would 500 the whole method.
  for (const hostile of [`"`, `needle"`, `needle OR everything`, `NEAR(a b)`, `needle -*`, `^needle`, `"unclosed`]) {
    const r = query(store, hostile);
    assert.ok(typeof r.total === "number", `crashed on ${JSON.stringify(hostile)}`);
  }
  // `OR` does not widen: "needle OR nothingatall" is an AND of three literal
  // terms, so it matches nothing — the opposite of what FTS syntax would do.
  assert.equal(query(store, "needle OR nothingatall").total, 0);
  assert.equal(query(store, "needle").total, 2);
  store.close();
});

// ── Extraction ─────────────────────────────────────────────────────────────

test("extraction takes message.user + CONSOLIDATED deltas, and nothing else", () => {
  const rows = extractMessages([
    ev("message.user", { message_id: "u1", seq: 1, ts: 10, text: "user text" }),
    ev("thinking.delta", { message_id: "a1", seq: 2, ts: 11, text: "secret thoughts", consolidated: true }),
    ev("reasoning.delta", { message_id: "a1", seq: 3, ts: 11, text: "more thoughts", consolidated: true }),
    ev("tool.call", { message_id: "a1", seq: 4, ts: 12, text: "rm -rf tool payload" }),
    ev("tool.result", { seq: 5, ts: 12, text: "tool output" }),
    ev("message.delta", { message_id: "a1", seq: 6, ts: 13, text: "assistant prose", consolidated: true }),
    ev("status.update", { seq: 7, ts: 14, text: "idle" }),
    ev("session.info", { seq: 8, ts: 14, text: "info" }),
    ev("message.complete", { message_id: "a1", seq: 9, ts: 15, text: "assistant prose" }),
  ]);
  assert.deepEqual(rows.map((r) => [r.messageId, r.role, r.text]), [
    ["u1", "user", "user text"],
    ["a1", "assistant", "assistant prose"],
  ]);
  assert.equal(rows[1]!.seq, 6, "seq is the consolidated event's (run max)");
});

test("raw deltas are skipped — they belong to a live turn and re-arrive folded", () => {
  const live = [
    ev("message.user", { message_id: "u1", seq: 1, ts: 10, text: "question" }),
    ev("message.delta", { message_id: "a1", seq: 2, ts: 11, text: "half " }),
    ev("message.delta", { message_id: "a1", seq: 3, ts: 12, text: "an answer" }),
  ];
  assert.deepEqual(extractMessages(live).map((r) => r.messageId), ["u1"]);

  // After compaction the same message shows up whole, exactly once.
  const settled = [
    live[0],
    ev("message.delta", { message_id: "a1", seq: 3, first_seq: 2, ts: 12, text: "half an answer", consolidated: true }),
  ];
  assert.deepEqual(extractMessages(settled).map((r) => [r.messageId, r.text]), [
    ["u1", "question"], ["a1", "half an answer"],
  ]);
});

test("message.complete is the fallback ONLY when the message has no delta event", () => {
  // Cached-without-streaming: a complete with text and no deltas at all.
  const cached = extractMessages([
    ev("message.complete", { message_id: "a1", seq: 2, ts: 11, text: "cached answer" }),
  ]);
  assert.deepEqual(cached.map((r) => [r.messageId, r.role, r.text]), [["a1", "assistant", "cached answer"]]);

  // With deltas present, complete must NOT double-index (it carries only the
  // final text block, so trusting it would also lose inter-tool prose).
  const streamed = extractMessages([
    ev("message.delta", { message_id: "a1", seq: 2, ts: 11, text: "streamed answer", consolidated: true }),
    ev("message.complete", { message_id: "a1", seq: 3, ts: 12, text: "streamed answer" }),
  ]);
  assert.equal(streamed.length, 1);
  assert.equal(streamed[0]!.text, "streamed answer");

  // A live turn's raw deltas also block the fallback — the folded event will
  // arrive later; indexing complete now would drop inter-tool prose forever.
  const midTurn = extractMessages([
    ev("message.delta", { message_id: "a1", seq: 2, ts: 11, text: "part" }),
    ev("message.complete", { message_id: "a1", seq: 3, ts: 12, text: "final block only" }),
  ]);
  assert.deepEqual(midTurn, []);
});

test("prose split across a tool call folds back into ONE row per message", () => {
  // compact() closes a run at any intervening event, so one message_id can own
  // two consolidated events. The hit unit is the MESSAGE, so they rejoin.
  const rows = extractMessages([
    ev("message.delta", { message_id: "a1", seq: 2, ts: 11, text: "before the tool", consolidated: true }),
    ev("tool.call", { message_id: "a1", seq: 3, ts: 12 }),
    ev("message.delta", { message_id: "a1", seq: 4, ts: 13, text: "after the tool", consolidated: true }),
  ]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0]!.text, "before the tool\nafter the tool");
  assert.equal(rows[0]!.seq, 4, "seq is the highest of the parts");
});

test("partial messages are indexed normally — their text is real", () => {
  const rows = extractMessages([
    ev("message.delta", { message_id: "a1", seq: 2, ts: 11, text: "interrupted mid-thou", consolidated: true, partial: true }),
  ]);
  assert.deepEqual(rows.map((r) => r.text), ["interrupted mid-thou"]);
});

test("maxEventSeq is the file's high-water mark, 0 for an empty transcript", () => {
  assert.equal(maxEventSeq([]), 0);
  assert.equal(maxEventSeq(turn(1, "a", "b")), 14);
});

// ── Watermark / reconcile ──────────────────────────────────────────────────

test("reconcile indexes the tail when the transcript grew", () => {
  const store = new SearchStore(":memory:");
  const t1 = turn(1, "first question", "first answer");
  store.reconcile("s1", maxEventSeq(t1), t1);
  assert.equal(query(store, "first").total, 2);

  const both = [...t1, ...turn(2, "second question", "second answer")];
  store.reconcile("s1", maxEventSeq(both), both);
  assert.equal(query(store, "second").total, 2);
  assert.equal(query(store, "question").total, 2, "no duplicate rows from re-seeing turn one");
  store.close();
});

test("reconcile REBUILDS when the transcript shrank below the watermark (undo/clear)", () => {
  const store = new SearchStore(":memory:");
  const both = [...turn(1, "kept question", "kept answer"), ...turn(2, "popped question", "popped answer")];
  store.reconcile("s1", maxEventSeq(both), both);
  assert.equal(query(store, "popped").total, 2);

  const truncated = turn(1, "kept question", "kept answer");
  store.reconcile("s1", maxEventSeq(truncated), truncated);
  assert.equal(query(store, "popped").total, 0, "the popped turn stops being findable");
  assert.equal(query(store, "kept").total, 2, "…and the surviving turn is still indexed exactly once");
  store.close();
});

test("reconcile is a no-op at the watermark, and indexTail never double-inserts", () => {
  const store = new SearchStore(":memory:");
  const t1 = turn(1, "stable question", "stable answer");
  store.reconcile("s1", maxEventSeq(t1), t1);
  const before = query(store, "stable").total;
  store.reconcile("s1", maxEventSeq(t1), t1);
  store.reconcile("s1", maxEventSeq(t1), t1);
  assert.equal(query(store, "stable").total, before);
  // Even a forced re-index of the same file replaces rather than duplicates.
  store.indexTail("s1", t1);
  assert.equal(query(store, "stable").total, before);
  store.close();
});

test("dropSession removes rows AND the watermark, so a reindex starts clean", () => {
  const store = new SearchStore(":memory:");
  const t1 = turn(1, "orphan question", "orphan answer");
  store.reconcile("s1", maxEventSeq(t1), t1);
  store.dropSession("s1");
  assert.equal(query(store, "orphan").total, 0);
  // Watermark gone → a later reconcile with the SAME seqs still indexes.
  store.reconcile("s1", maxEventSeq(t1), t1);
  assert.equal(query(store, "orphan").total, 2);
  store.close();
});

// ── Query surface ──────────────────────────────────────────────────────────

test("hits carry snippet markers, capped text, and the deep-link tuple", () => {
  const store = new SearchStore(":memory:");
  store.reconcile("s1", 14, turn(1, "where is the needle", "the needle is in the haystack"));
  const r = query(store, "needle");
  const hit = r.hits.find((h: any) => h.role === "assistant")!;
  assert.ok(hit.snippet.includes(`${SNIPPET_OPEN}needle${SNIPPET_CLOSE}`), `snippet was: ${hit.snippet}`);
  assert.equal(hit.sessionId, "s1");
  assert.equal(hit.messageId, "a1");
  assert.equal(hit.seq, 13);
  assert.equal(hit.ts, 1013);
  assert.equal(hit.text, "the needle is in the haystack", "full text rides along for peek");
  store.close();
});

test("text is capped rather than returning a whole transcript", () => {
  const store = new SearchStore(":memory:");
  const long = `needle ${"x".repeat(9000)}`;
  store.reconcile("s1", 2, [ev("message.delta", { message_id: "a1", seq: 2, ts: 11, text: long, consolidated: true })]);
  assert.equal(query(store, "needle").hits[0]!.text.length, 4096);
  store.close();
});

test("rank sorts by bm25 (best first); recent sorts by ts", () => {
  const store = new SearchStore(":memory:");
  store.reconcile("s1", 99, [
    // Old + dense with the term = best by bm25, worst by recency.
    ev("message.delta", { message_id: "a1", seq: 2, ts: 100, text: "needle needle needle", consolidated: true }),
    ev("message.delta", { message_id: "a2", seq: 4, ts: 500, text: `needle ${"filler ".repeat(60)}`, consolidated: true }),
  ]);
  assert.deepEqual(query(store, "needle").hits.map((h: any) => h.messageId), ["a1", "a2"]);
  assert.deepEqual(query(store, "needle", { sort: "recent" }).hits.map((h: any) => h.messageId), ["a2", "a1"]);
  store.close();
});

test("role, since, limit/offset and total behave together", () => {
  const store = new SearchStore(":memory:");
  const events = [...turn(1, "needle one", "needle two"), ...turn(2, "needle three", "needle four")];
  store.reconcile("s1", maxEventSeq(events), events);

  assert.equal(query(store, "needle").total, 4);
  assert.equal(query(store, "needle", { role: "user" }).total, 2);
  assert.equal(query(store, "needle", { role: "assistant" }).total, 2);
  // ts of turn two's user message is 1020.
  assert.equal(query(store, "needle", { since: 1020 }).total, 2);

  const page = query(store, "needle", { sort: "recent", limit: 1, offset: 1 });
  assert.equal(page.total, 4, "total counts the whole match set, not the page");
  assert.equal(page.hits.length, 1);
  store.close();
});

test("an empty allowed-session set returns empty without querying", () => {
  const store = new SearchStore(":memory:");
  store.reconcile("s1", 14, turn(1, "needle", "needle"));
  assert.deepEqual(query(store, "needle", {}, []), { total: 0, hits: [] });
  // …and a session outside the allowed set is invisible.
  assert.equal(query(store, "needle", {}, ["s2"]).total, 0);
  store.close();
});

test("textOf returns one message's indexed text for the reply preview", () => {
  const store = new SearchStore(":memory:");
  store.reconcile("s1", 14, turn(1, "question", "the answer text"));
  assert.equal(store.textOf("a1"), "the answer text");
  assert.equal(store.textOf("a1", 3), "the");
  assert.equal(store.textOf("nope"), undefined);
  store.close();
});
