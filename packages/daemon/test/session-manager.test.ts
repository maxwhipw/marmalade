import { test } from "node:test";
import assert from "node:assert/strict";
import { SessionManager } from "../dist/session-manager.js";
import { createSessionSpec } from "../dist/policy.js";
import { defaultConfig } from "../dist/config.js";

const cfg = defaultConfig();

function mgr() {
  return SessionManager.inMemory();
}

test("create + get round-trips a session record", () => {
  const m = mgr();
  const spec = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);
  const rec = m.create("sess1", spec, "claude-code", 1000);
  assert.equal(rec.status, "starting");
  const got = m.get("sess1");
  assert.equal(got?.principal, "owner");
  assert.equal(got?.purpose, "main");
  assert.equal(got?.authClass, "subscription");
  m.close();
});

test("list filters by principal and purpose, ordered by last_active", () => {
  const m = mgr();
  const main = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);
  const coding = createSessionSpec({ principal: "owner", purpose: "coding", origin: "cli" }, cfg);
  m.create("a", main, "claude-code", 1000);
  m.create("b", coding, "opencode", 2000);
  assert.equal(m.list({ purpose: "main" }).length, 1);
  assert.equal(m.list({ purpose: "coding" }).length, 1);
  assert.equal(m.list().length, 2);
  assert.equal(m.list()[0].id, "b"); // most recent first
  m.close();
});

test("bindHarnessSession records the SDK session id for cwd-sensitive resume", () => {
  const m = mgr();
  const spec = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);
  m.create("s", spec, "claude-code", 1000);
  m.bindHarnessSession("s", "sdk-uuid-xyz");
  assert.equal(m.get("s")?.harnessSessionId, "sdk-uuid-xyz");
  m.close();
});

test("findSilentlyDead flags a live session past its heartbeat timeout (M1.5)", () => {
  const m = mgr();
  const spec = createSessionSpec({ principal: "owner", purpose: "cadence", origin: "cadence" }, cfg);
  m.create("stale", spec, "claude-code", 0); // last_heartbeat = 0
  // 60s later, 30s timeout → stale is silently dead.
  const dead = m.findSilentlyDead(60_000, 30_000);
  assert.equal(dead.length, 1);
  assert.equal(dead[0].id, "stale");
  // A fresh heartbeat clears it.
  m.heartbeat("stale", 60_000);
  assert.equal(m.findSilentlyDead(61_000, 30_000).length, 0);
  m.close();
});

// ── principal rename migration (2026-08-15: "max" → "owner") ────────────────

import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

test("pre-rename rows with principal 'max' migrate to 'owner' on reopen", () => {
  const dbPath = join(tmpdir(), `mig-${randomUUID()}.db`);
  const m1 = new SessionManager(dbPath);
  const spec = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);
  // Simulate a pre-rename row: create normally, then rewrite the stored
  // principal to the legacy value (as an old daemon would have written it).
  m1.create("legacy1", spec, "claude-code", 1000);
  (m1 as unknown as { db: { exec(sql: string): void } }).db
    .exec(`UPDATE sessions SET principal = 'max' WHERE id = 'legacy1'`);
  // Same for a device token minted by an old daemon.
  const started = m1.pairing.startPairing(1000)!;
  const tok = m1.pairing.claim(started.token, "legacy-dev", "max", 1000)!;
  m1.close();

  const m2 = new SessionManager(dbPath);
  assert.equal(m2.get("legacy1")?.principal, "owner");
  assert.equal(m2.pairing.authenticate(tok, 2000)?.principal, "owner");
  m2.close();
});
