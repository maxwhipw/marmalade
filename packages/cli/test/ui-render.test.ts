// CLI subset renderer + streaming fence filter (dynamic-ui plan step 5).
// Reads the shared fixtures from docs/dynamic-ui/fixtures/ — same JSON the
// ui-tree suite and the Android UiTreeParserTest cover.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { renderUiFence, UiFenceFilter } from "../dist/ui-render.js";

const fixture = (name: string): string =>
  readFileSync(fileURLToPath(new URL(`../../../docs/dynamic-ui/fixtures/${name}`, import.meta.url)), "utf8");

const strip = (s: string) => s.replace(/\x1b\[[0-9;]*m/g, "");

test("full vocabulary renders every subset node as readable lines", () => {
  const out = strip(renderUiFence(fixture("full-vocabulary.json")));
  assert.match(out, /Trip planner/); // card title
  assert.match(out, /Where to\?/); // text
  assert.match(out, /1\. pack light/); // ordered list
  assert.match(out, /Kyoto\s+3/); // table row
  assert.match(out, /echo hi/); // code
  assert.match(out, /\[warning\] Heads up: Peak season/); // alert
  assert.match(out, /Season.*:/); // select label
  assert.match(out, /◦ Spring/); // select option
  assert.match(out, /\[x\] Flexible dates/); // checkbox
  assert.match(out, /vibe \(pick any\):/); // multi chip_group
  assert.match(out, /● Searching/); // status
  assert.match(out, /⏱ Offer expires: 90s/); // countdown
  // callback button shows the exact reply grammar with fill-in slots
  assert.match(out, /▸ Plan it — reply: Responded with: plan: dest=…; season=…; vibe=…; flex=…/);
  assert.match(out, /▸ Open map → https:\/\/maps\.example/);
  assert.match(out, /▸ Copy \(copy: itinerary\)/);
});

test("callback button without collect_from shows the Pressed reply", () => {
  const out = strip(renderUiFence('{"type":"button","label":"Confirm","action":"callback","event":"confirm"}'));
  assert.match(out, /▸ Confirm — reply: Pressed: confirm/);
});

test("unknown node degrades to its text line — never an error", () => {
  const out = strip(renderUiFence(fixture("unknown-node.json")));
  assert.equal(out.trim(), "future thing");
});

test("garbage fence body degrades to the raw text, dimmed", () => {
  const out = strip(renderUiFence("not json at all"));
  assert.equal(out.trim(), "not json at all");
});

test("filter passes plain text and other fences through untouched", () => {
  const f = new UiFenceFilter();
  const chunks = ["hello **wor", "ld**\n```bash\necho hi\n`", "``\ntail\n"];
  const out = chunks.map((c) => f.feed(c)).join("") + f.flush();
  assert.equal(out, "hello **world**\n```bash\necho hi\n```\ntail\n");
});

test("filter holds a marmalade-ui fence and renders it natively on close", () => {
  const f = new UiFenceFilter();
  const md = "before\n```marmalade-ui\n" + fixture("unknown-node.json") + "```\nafter\n";
  // Split mid-fence to prove delta boundaries don't matter.
  const out = strip(f.feed(md.slice(0, 30)) + f.feed(md.slice(30)) + f.flush());
  assert.match(out, /before\n/);
  assert.match(out, /future thing/);
  assert.match(out, /after\n/);
  assert.doesNotMatch(out, /```/);
});

test("filter flush renders a truncated fence via the repair layer", () => {
  const f = new UiFenceFilter();
  const held = f.feed("```marmalade-ui\n" + fixture("truncated-tree.txt"));
  assert.equal(held, "");
  const out = strip(f.flush());
  assert.match(out, /kept/);
});
