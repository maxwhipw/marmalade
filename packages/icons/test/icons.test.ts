import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";
import assert from "node:assert/strict";

import { iconForTool, iconSvg, ICONS, UNKNOWN_TOOL_TOKEN } from "../dist/index.js";
import { loadMap, renderGenerated } from "../scripts/generate.ts";

test("wire names resolve to their token (case-insensitively)", () => {
  assert.equal(iconForTool("Bash"), "icon.tool.terminal");
  assert.equal(iconForTool("bash"), "icon.tool.terminal");
  assert.equal(iconForTool("  Read  "), "icon.tool.read");
  assert.equal(iconForTool("Glob"), "icon.tool.list");
  assert.equal(iconForTool("Grep"), "icon.tool.search");
  assert.equal(iconForTool("WebFetch"), "icon.tool.web.fetch");
  assert.equal(iconForTool("WebSearch"), "icon.tool.web.search");
  assert.equal(iconForTool("Skill"), "icon.tool.skill");
  assert.equal(iconForTool("AskUserQuestion"), "icon.tool.question");
  assert.equal(iconForTool("TodoWrite"), "icon.tool.todo");
  assert.equal(iconForTool("NotebookEdit"), "icon.tool.edit");
});

test("task/agent are the subagent token, not a wrench", () => {
  assert.equal(iconForTool("Task"), "icon.tool.subagent");
  assert.equal(iconForTool("Agent"), "icon.tool.subagent");
  assert.equal(iconForTool("subagent"), "icon.tool.subagent");
});

test("the mcp__ prefix wins over any name match", () => {
  assert.equal(iconForTool("mcp__wiki-helpers__daily_note_append"), "icon.tool.mcp");
  assert.equal(iconForTool("MCP__Venice__generate_image"), "icon.tool.mcp");
  // An MCP tool called `search` is still an MCP call — prefix beats name.
  assert.equal(iconForTool("mcp__server__search"), "icon.tool.mcp");
});

test("unknown, empty and nullish names fall back to the generic tool token", () => {
  assert.equal(iconForTool("SomeToolWeHaveNeverSeen"), UNKNOWN_TOOL_TOKEN);
  assert.equal(iconForTool(""), UNKNOWN_TOOL_TOKEN);
  assert.equal(iconForTool(null), UNKNOWN_TOOL_TOKEN);
  assert.equal(iconForTool(undefined), UNKNOWN_TOOL_TOKEN);
});

test("matching is exact — no substring bleed between search and websearch", () => {
  assert.notEqual(iconForTool("websearch"), iconForTool("search"));
  assert.equal(iconForTool("research"), UNKNOWN_TOOL_TOKEN);
});

test("every token carries drawable markup and a known license", () => {
  for (const [token, entry] of Object.entries(ICONS)) {
    assert.match(token, /^icon\.(tool|agent|ui)\./, `${token}: bad domain`);
    assert.ok(entry.svg.includes("<"), `${token}: empty markup`);
    assert.ok(["isc", "mit-feather"].includes(entry.license), `${token}: ${entry.license}`);
    assert.equal(iconSvg(token as keyof typeof ICONS), entry.svg);
  }
});

test("no sparkles glyph anywhere in the map (signed off 2026-08-01)", () => {
  for (const [token, entry] of Object.entries(ICONS)) {
    assert.ok(!entry.glyph.includes("sparkle"), `${token} uses a banned sparkles glyph`);
  }
});

test("generated.ts is in sync with map.json", () => {
  const onDisk = readFileSync(fileURLToPath(new URL("../src/generated.ts", import.meta.url)), "utf8");
  assert.equal(
    onDisk,
    renderGenerated(loadMap()),
    "src/generated.ts is stale — run: pnpm --filter @marmalade/icons generate",
  );
});
