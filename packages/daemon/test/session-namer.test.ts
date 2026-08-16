// session-namer.test.ts — the pure halves of the title/summary seed.
//
// A session's first title and summary come from one cheap side-call after its
// opening exchange (session-namer.ts); after that the session's own model owns
// the summary via update_session_summary, and the title belongs to the user.
//
// The model call itself isn't tested here (it's a network call to a small
// model). What IS tested is everything that decides whether a reply is usable
// and whether a session may be relabelled at all — the two places a bug would
// either lose every title silently or, much worse, overwrite a user-chosen name.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  parseNaming,
  isPlaceholderTitle,
  MAX_TITLE_CHARS,
  MAX_SUMMARY_CHARS,
} from "../src/session-namer.ts";

test("parses a clean JSON reply", () => {
  const n = parseNaming('{"title": "Fix terminal geometry", "summary": "Diagnosing a zero-height xterm cell."}');
  assert.equal(n?.title, "Fix terminal geometry");
  assert.equal(n?.summary, "Diagnosing a zero-height xterm cell.");
});

test("tolerates a code fence — small models add them constantly", () => {
  const n = parseNaming('```json\n{"title": "Wire the donut", "summary": "Seeds context from session.list."}\n```');
  assert.equal(n?.title, "Wire the donut");
});

test("tolerates a lead-in sentence before the JSON", () => {
  const n = parseNaming('Here is the label:\n{"title": "Add archive", "summary": "Archive rows in the drawer."}');
  assert.equal(n?.title, "Add archive");
});

test("strips quotes the model wrapped around the title", () => {
  assert.equal(parseNaming('{"title": "\\"Fix the build\\"", "summary": "x"}')?.title, "Fix the build");
});

test("caps an over-long title and summary rather than dropping them", () => {
  const n = parseNaming(JSON.stringify({ title: "T".repeat(200), summary: "S".repeat(2000) }));
  assert.equal(n?.title.length, MAX_TITLE_CHARS);
  assert.equal(n?.summary.length, MAX_SUMMARY_CHARS);
});

test("an empty title still yields a usable summary, and vice versa", () => {
  // The prompt tells the model to return "" for an exchange too thin to name
  // (a bare greeting). That must not throw the summary away with it.
  assert.equal(parseNaming('{"title": "", "summary": "Alice said hello."}')?.summary, "Alice said hello.");
  assert.equal(parseNaming('{"title": "Only a title", "summary": ""}')?.title, "Only a title");
});

test("returns null for replies with nothing usable in them", () => {
  assert.equal(parseNaming(""), null);
  assert.equal(parseNaming("I'm sorry, I can't do that."), null);
  assert.equal(parseNaming("{ not json at all"), null);
  assert.equal(parseNaming('{"title": "", "summary": ""}'), null);
  assert.equal(parseNaming('{"title": 42, "summary": null}'), null);
});

// ── Ownership: the half that must never be wrong ────────────────────────────

test("placeholder titles are claimable", () => {
  for (const t of ["New Chat", "new chat", "  NEW CHAT  ", "New Session", "Untitled", "Chat", "", null, undefined]) {
    assert.equal(isPlaceholderTitle(t), true, `expected ${JSON.stringify(t)} claimable`);
  }
});

test("a title the user chose is NEVER claimable", () => {
  // Including ones that merely contain a placeholder word — "New chat flow"
  // is a real name for real work, and relabelling it would be the worst
  // failure this feature has.
  for (const t of ["Fix terminal geometry", "New chat flow", "Chat UI polish", "Marmalade", "chats"]) {
    assert.equal(isPlaceholderTitle(t), false, `expected ${JSON.stringify(t)} protected`);
  }
});
