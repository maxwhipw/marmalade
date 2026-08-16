// config-file.test.ts — the daemon config file (~/.marmalade/daemon/
// config.json). The invariants: missing file = pure defaults; env beats file
// beats default per knob; and a malformed/unknown-key file FAILS LOUDLY —
// a silently ignored typo ("aprovals_mode") is the silent-failure class.

import { test } from "node:test";
import assert from "node:assert/strict";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { writeFileSync, rmSync } from "node:fs";
import { loadConfigFile, defaultConfig, clampEffort } from "../dist/config.js";

function withFile(content: string, fn: (path: string) => void): void {
  const path = join(tmpdir(), `mcfg-${randomUUID()}.json`);
  writeFileSync(path, content);
  try { fn(path); } finally { rmSync(path, { force: true }); }
}

test("missing file → empty config (all defaults)", () => {
  assert.deepEqual(loadConfigFile(join(tmpdir(), `nope-${randomUUID()}.json`)), {});
  const cfg = defaultConfig({});
  assert.equal(cfg.gatewayPort, 9130);
  assert.equal(cfg.budget, undefined);
});

test("valid file: every knob lands, snake_case → camelCase", () => {
  withFile(JSON.stringify({
    bind_host: "100.64.1.2", bind_port: 9131, approvals_mode: "prompt",
    max_live_sessions: 4, idle_reap_ms: 120_000,
    budget: { metric: "tokens", daily_limit: 5_000_000 },
  }), (path) => {
    const file = loadConfigFile(path);
    const cfg = defaultConfig(file);
    assert.deepEqual(cfg.gatewayHosts, ["127.0.0.1", "100.64.1.2"]);
    assert.equal(cfg.gatewayPort, 9131);
    assert.equal(cfg.approvalsMode, "prompt");
    assert.equal(cfg.maxLiveSessions, 4);
    assert.equal(cfg.idleReapMs, 120_000);
    assert.deepEqual(cfg.budget, { metric: "tokens", dailyLimit: 5_000_000 });
  });
});

test("userBehaviorPath defaults under ~/.marmalade (NOT the state dir), env-overridable", () => {
  const cfg = defaultConfig({});
  assert.equal(cfg.userBehaviorPath, join(homedir(), ".marmalade", "behavior.md"));
  assert.notEqual(cfg.userBehaviorPath, join(cfg.stateDir, "behavior.md"), "it's the user's file, not daemon state");
  process.env.MARMALADE_USER_BEHAVIOR = "/tmp/test-behavior.md";
  try {
    assert.equal(defaultConfig({}).userBehaviorPath, "/tmp/test-behavior.md");
  } finally { delete process.env.MARMALADE_USER_BEHAVIOR; }
});

test("env beats file per knob; file still fills the rest", () => {
  withFile(JSON.stringify({ bind_port: 9131, approvals_mode: "prompt" }), (path) => {
    process.env.MARMALADE_BIND_PORT = "9200";
    try {
      const cfg = defaultConfig(loadConfigFile(path));
      assert.equal(cfg.gatewayPort, 9200, "env wins");
      assert.equal(cfg.approvalsMode, "prompt", "file fills what env doesn't set");
    } finally {
      delete process.env.MARMALADE_BIND_PORT;
    }
  });
});

test("unknown key fails loudly (strict), not silently ignored", () => {
  withFile(JSON.stringify({ aprovals_mode: "prompt" }), (path) => {
    assert.throws(() => loadConfigFile(path), /invalid|Unrecognized/i);
  });
});

test("malformed JSON and invalid values fail with the file named", () => {
  withFile("{not json", (path) => {
    assert.throws(() => loadConfigFile(path), new RegExp(`${path.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}.*not valid JSON`));
  });
  withFile(JSON.stringify({ budget: { metric: "euros", daily_limit: 5 } }), (path) => {
    assert.throws(() => loadConfigFile(path), /budget\.metric/);
  });
  withFile(JSON.stringify({ idle_reap_ms: 5 }), (path) => {
    assert.throws(() => loadConfigFile(path), /idle_reap_ms/);
  });
});

// --- per-model effort bounds (2026-07-27) -------------------------------

test("model_efforts loads and lands on the config as modelEfforts", () => {
  withFile(JSON.stringify({
    model_efforts: {
      "claude-opus-5": { min: "high" },
      "claude-fable-5": { max: "medium" },
      "claude-sonnet-4-5": { min: "low", max: "high" },
    },
  }), (path) => {
    const cfg = defaultConfig(loadConfigFile(path));
    assert.deepEqual(cfg.modelEfforts, {
      "claude-opus-5": { min: "high" },
      "claude-fable-5": { max: "medium" },
      "claude-sonnet-4-5": { min: "low", max: "high" },
    });
  });
  // Absent = no bounds at all, not an empty map to be defensive about.
  assert.equal(defaultConfig({}).modelEfforts, undefined);
});

test("model_efforts: min>max, an empty entry, and a bad level all fail startup", () => {
  withFile(JSON.stringify({ model_efforts: { "m-a": { min: "max", max: "low" } } }), (path) => {
    assert.throws(() => loadConfigFile(path), /deeper than max/);
  });
  withFile(JSON.stringify({ model_efforts: { "m-a": {} } }), (path) => {
    assert.throws(() => loadConfigFile(path), /at least one of min\/max/);
  });
  withFile(JSON.stringify({ model_efforts: { "m-a": { min: "turbo" } } }), (path) => {
    assert.throws(() => loadConfigFile(path), /model_efforts\.m-a\.min/);
  });
  // Strict all the way down: a typo'd bound key is not silently dropped.
  withFile(JSON.stringify({ model_efforts: { "m-a": { minimum: "high" } } }), (path) => {
    assert.throws(() => loadConfigFile(path), /invalid|Unrecognized/i);
  });
  // ...and the top-level strictness is unchanged by the new key.
  withFile(JSON.stringify({ model_effort: {} }), (path) => {
    assert.throws(() => loadConfigFile(path), /invalid|Unrecognized/i);
  });
});

test("clampEffort walks EFFORT_LEVELS order and is a no-op without bounds", () => {
  assert.equal(clampEffort("low", undefined), "low");
  assert.equal(clampEffort("low", {}), "low");
  // Floor.
  assert.equal(clampEffort("low", { min: "high" }), "high");
  assert.equal(clampEffort("max", { min: "high" }), "max");
  // Ceiling.
  assert.equal(clampEffort("max", { max: "medium" }), "medium");
  assert.equal(clampEffort("low", { max: "medium" }), "low");
  // Both edges; in-range values pass through untouched.
  assert.equal(clampEffort("low", { min: "medium", max: "high" }), "medium");
  assert.equal(clampEffort("max", { min: "medium", max: "high" }), "high");
  assert.equal(clampEffort("high", { min: "medium", max: "high" }), "high");
  // A pinned model (min === max) collapses every request onto the one level.
  assert.equal(clampEffort("low", { min: "xhigh", max: "xhigh" }), "xhigh");
});
