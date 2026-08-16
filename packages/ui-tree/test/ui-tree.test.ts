// Marmalade UI v1 tree parsing — TS twin of the Android client's
// UiTreeParserTest.kt (same payloads, two renderers — drift-proof).
// Fixtures live in docs/dynamic-ui/fixtures/; the Android test embeds the
// same JSON verbatim (separate repo — mirror changes both ways).

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  parseUiTree,
  callbackMessage,
  type ButtonNode,
  type CardNode,
  type ChipGroupNode,
  type CheckboxNode,
  type ColumnNode,
  type RowNode,
  type SelectNode,
  type TableNode,
  type TextInputNode,
  type TextNode,
  type UnknownNode,
} from "../dist/index.js";

const fixture = (name: string): string =>
  readFileSync(fileURLToPath(new URL(`../../../docs/dynamic-ui/fixtures/${name}`, import.meta.url)), "utf8");

test("full v1 vocabulary parses into the node hierarchy", () => {
  const card = parseUiTree(fixture("full-vocabulary.json")) as CardNode;
  assert.equal(card.kind, "card");
  assert.equal(card.title, "Trip planner");
  assert.equal(card.children.length, 15);
  const text = card.children[0] as TextNode;
  assert.equal(text.style, "title");
  assert.equal(text.bold, true);
  const row = card.children[2] as RowNode;
  assert.equal((row.children[0] as TextInputNode).id, "dest");
  assert.equal((row.children[1] as CheckboxNode).checked, true);
  const select = card.children[3] as SelectNode;
  assert.deepEqual(select.options, [
    { id: "spring", label: "Spring" },
    { id: "summer", label: "summer" },
  ]);
  const chips = card.children[4] as ChipGroupNode;
  assert.equal(chips.multi, true);
  const table = card.children[6] as TableNode;
  assert.deepEqual(table.rows[0], ["Kyoto", "3"]);
  const button = card.children[12] as ButtonNode;
  assert.deepEqual(button.collectFrom, ["dest", "season", "vibe", "flex"]);
  assert.equal((card.children[13] as ButtonNode).action, "open_url");
});

test("partial node renders with field defaults — tolerant contract", () => {
  const node = parseUiTree('{"type":"text"}') as TextNode;
  assert.equal(node.text, "");
  assert.equal(node.style, "body");
  assert.equal(node.color, "default");
});

test("NDJSON lines compose into an implicit column", () => {
  const root = parseUiTree(fixture("ndjson-two-texts.ndjson")) as ColumnNode;
  assert.equal(root.kind, "column");
  assert.equal(root.children.length, 2);
  assert.equal((root.children[1] as TextNode).text, "two");
});

test("truncated tree repairs and renders the surviving prefix", () => {
  const root = parseUiTree(fixture("truncated-tree.txt")) as ColumnNode;
  assert.equal(root.kind, "column");
  assert.ok(root.children.length > 0);
  assert.equal((root.children[0] as TextNode).text, "kept");
});

test("unknown node type degrades to unknown with its text — never an error", () => {
  const root = parseUiTree(fixture("unknown-node.json")) as UnknownNode;
  assert.equal(root.kind, "unknown");
  assert.equal(root.type, "hologram");
  assert.equal(root.text, "future thing");
});

test("garbage returns null — caller degrades to a code block", () => {
  assert.equal(parseUiTree("not json at all"), null);
  assert.equal(parseUiTree(""), null);
});

test("input without an id is dropped rather than rendered uncollectable", () => {
  const root = parseUiTree(fixture("input-without-id.json")) as ColumnNode;
  assert.equal(root.children.length, 1);
  assert.equal(root.children[0].kind, "text");
});

// ── Interaction response grammar (spec §Interaction contract) ────────────────

test("callback without collect_from synthesizes Pressed line", () => {
  const b: ButtonNode = { kind: "button", label: "Confirm", action: "callback", event: "confirm", collectFrom: [], variant: "primary" };
  assert.equal(callbackMessage(b, new Map()), "Pressed: confirm");
  const noEvent: ButtonNode = { kind: "button", label: "OK", action: "callback", collectFrom: [], variant: "primary" };
  assert.equal(callbackMessage(noEvent, new Map()), "Pressed: OK");
});

test("callback with collect_from synthesizes Responded with line in collect order", () => {
  const b: ButtonNode = {
    kind: "button",
    label: "Plan it",
    action: "callback",
    event: "plan",
    collectFrom: ["dest", "vibe", "flex", "missing"],
    variant: "primary",
  };
  const msg = callbackMessage(
    b,
    new Map([
      ["dest", "kyoto"],
      ["vibe", "onsen,food"],
      ["flex", "true"],
    ]),
  );
  assert.equal(msg, "Responded with: plan: dest=kyoto; vibe=onsen,food; flex=true; missing=");
});
