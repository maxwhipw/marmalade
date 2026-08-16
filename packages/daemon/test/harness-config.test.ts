import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync, rmSync, readFileSync } from "node:fs";
import { HarnessConfigStore } from "../dist/harness-config.js";

// Fixture mirroring the REAL on-disk shapes (verified 2026-07-12):
// $CLAUDE_CONFIG_DIR/.claude.json {mcpServers}, settings.json
// {enabledPlugins}, plugins/installed_plugins.json {plugins}.
function fixture() {
  const root = join(tmpdir(), `hc-${randomUUID()}`);
  const configDir = join(root, "claude");
  mkdirSync(join(configDir, "plugins"), { recursive: true });
  writeFileSync(join(configDir, ".claude.json"), JSON.stringify({
    someOtherState: { keep: "me" },
    mcpServers: {
      qmd: { type: "stdio", command: "qmd", args: ["mcp"], env: { API_KEY: "sk-test-secret" } },
      wiki: { type: "stdio", command: "node", args: ["/x.js"], env: {} },
    },
  }));
  writeFileSync(join(configDir, "settings.json"), JSON.stringify({
    permissions: { allow: ["Bash(ls:*)"] },
    enabledPlugins: { "code-review@official": true, "playwright@official": false },
  }));
  // code-review has a real install path + manifest (version/description come
  // from there); the others are bare records (enrichment degrades cleanly).
  const crPath = join(configDir, "plugins", "cache", "official", "code-review", "1.2.0");
  mkdirSync(join(crPath, ".claude-plugin"), { recursive: true });
  writeFileSync(join(crPath, ".claude-plugin", "plugin.json"), JSON.stringify({
    name: "code-review", version: "1.2.0", description: "Review a pull request", author: "official",
  }));
  writeFileSync(join(configDir, "plugins", "installed_plugins.json"), JSON.stringify({
    version: 2,
    plugins: {
      "code-review@official": [{ scope: "user", installPath: crPath, version: "1.2.0" }],
      "playwright@official": [{ scope: "user" }],
      "fresh-install@official": [{ scope: "user" }],
    },
  }));
  const stashPath = join(root, "mcp-disabled.json");
  const store = new HarnessConfigStore(configDir, stashPath);
  return { root, configDir, stashPath, store, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

test("mcp: list shows active servers; toggle off stashes the definition losslessly", () => {
  const f = fixture();
  try {
    assert.deepEqual(f.store.listMcp().map((r) => [r.name, r.enabled]), [["qmd", true], ["wiki", true]]);
    const r = f.store.toggleMcp("qmd", false);
    assert.deepEqual(r, { applied: true, effective: "next_session" });
    assert.deepEqual(f.store.listMcp().map((x) => [x.name, x.enabled]), [["qmd", false], ["wiki", true]]);
    // The definition moved to the stash intact; the config keeps foreign keys.
    const claudeJson = JSON.parse(readFileSync(join(f.configDir, ".claude.json"), "utf8"));
    assert.equal(claudeJson.mcpServers.qmd, undefined);
    assert.deepEqual(claudeJson.someOtherState, { keep: "me" });
    const stash = JSON.parse(readFileSync(f.stashPath, "utf8"));
    assert.deepEqual(stash["claude-code"].qmd, { type: "stdio", command: "qmd", args: ["mcp"], env: { API_KEY: "sk-test-secret" } });
    // Toggle back on: definition restored verbatim.
    f.store.toggleMcp("qmd", true);
    const restored = JSON.parse(readFileSync(join(f.configDir, ".claude.json"), "utf8"));
    assert.deepEqual(restored.mcpServers.qmd, { type: "stdio", command: "qmd", args: ["mcp"], env: { API_KEY: "sk-test-secret" } });
    assert.deepEqual(f.store.listMcp().map((x) => [x.name, x.enabled]), [["qmd", true], ["wiki", true]]);
  } finally { f.cleanup(); }
});

test("mcp: unknown server errors; re-toggling the current state is idempotent", () => {
  const f = fixture();
  try {
    assert.throws(() => f.store.toggleMcp("nope", false), /unknown MCP server/);
    assert.equal(f.store.toggleMcp("qmd", true).applied, true, "already-on toggle is a no-op success");
    f.store.toggleMcp("qmd", false);
    assert.equal(f.store.toggleMcp("qmd", false).applied, true, "already-off toggle is a no-op success");
  } finally { f.cleanup(); }
});

test("plugins: list merges inventory + enabledPlugins; toggle flips the native flag", () => {
  const f = fixture();
  try {
    const rows = f.store.listPlugins();
    assert.deepEqual(rows.map((r) => [r.name, r.enabled]), [
      ["code-review@official", true],
      ["fresh-install@official", false], // installed but not in enabledPlugins
      ["playwright@official", false],
    ]);
    const r = f.store.togglePlugin("playwright@official", true);
    assert.deepEqual(r, { applied: true, effective: "next_session" });
    const settings = JSON.parse(readFileSync(join(f.configDir, "settings.json"), "utf8"));
    assert.equal(settings.enabledPlugins["playwright@official"], true);
    assert.deepEqual(settings.permissions, { allow: ["Bash(ls:*)"] }, "other settings preserved");
    assert.throws(() => f.store.togglePlugin("ghost@nowhere", true), /unknown plugin/);
  } finally { f.cleanup(); }
});

test("mcp: list carries the endpoint (stdio launch line) but never env", () => {
  const f = fixture();
  try {
    const rows = f.store.listMcp();
    const qmd = rows.find((r) => r.name === "qmd")!;
    assert.equal(qmd.command, "qmd mcp", "stdio command + args joined");
    assert.equal(qmd.url, undefined, "no url for a stdio server");
    // The fixture's qmd server carries a real-looking secret in env — the row
    // must not contain the key, the value, or an env field at all.
    assert.equal((qmd as Record<string, unknown>).env, undefined, "env is never forwarded");
    assert.ok(!JSON.stringify(qmd).includes("sk-test-secret"), "env value must not leak via any field");
    const wiki = rows.find((r) => r.name === "wiki")!;
    assert.equal(wiki.command, "node /x.js");
  } finally { f.cleanup(); }
});

test("plugins: list enriches with marketplace source + manifest version/description", () => {
  const f = fixture();
  try {
    const rows = f.store.listPlugins();
    const cr = rows.find((r) => r.name === "code-review@official")!;
    assert.equal(cr.source, "official", "marketplace parsed from the @suffix");
    assert.equal(cr.version, "1.2.0");
    assert.equal(cr.description, "Review a pull request");
    // A record with no installPath enriches source only — no crash, no version.
    const pw = rows.find((r) => r.name === "playwright@official")!;
    assert.equal(pw.source, "official");
    assert.equal(pw.version, undefined);
    assert.equal(pw.description, undefined);
  } finally { f.cleanup(); }
});

test("missing config files degrade to empty lists, never crash", () => {
  const root = join(tmpdir(), `hc-${randomUUID()}`);
  try {
    const store = new HarnessConfigStore(join(root, "no-such-dir"), join(root, "stash.json"));
    assert.deepEqual(store.listMcp(), []);
    assert.deepEqual(store.listPlugins(), []);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
