import { test } from "node:test";
import assert from "node:assert/strict";
import { SessionManager } from "../dist/session-manager.js";
import { createSessionSpec } from "../dist/policy.js";
import { defaultConfig } from "../dist/config.js";

const cfg = defaultConfig();

test("setSummary stores topic + summary + timestamp on the record", () => {
  const m = SessionManager.inMemory();
  const spec = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);
  m.create("s1", spec, "claude-code", 1000);
  m.setSummary("s1", { topic: "orchestrator M1", summary: "wired the adapter; open: live resume test" }, 2000);
  const rec = m.get("s1")!;
  assert.equal(rec.topic, "orchestrator M1");
  assert.match(rec.summary!, /live resume test/);
  assert.equal(rec.summaryUpdatedAt, 2000);
  m.close();
});

test("a fresh session has a null summary until the agent sets one", () => {
  const m = SessionManager.inMemory();
  const spec = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);
  m.create("s1", spec, "claude-code", 1000);
  assert.equal(m.get("s1")!.summary, null);
  m.close();
});

test("summary over the 1000-char cap is rejected (agent told to shorten)", () => {
  const m = SessionManager.inMemory();
  const spec = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);
  m.create("s1", spec, "claude-code", 1000);
  assert.throws(() => m.setSummary("s1", { summary: "x".repeat(1001) }, 2000), /too long/);
  // A summary exactly at the cap is fine.
  assert.doesNotThrow(() => m.setSummary("s1", { summary: "x".repeat(1000) }, 2000));
  m.close();
});

test("summary survives (persists in the row) and updates in place", () => {
  const m = SessionManager.inMemory();
  const spec = createSessionSpec({ principal: "owner", purpose: "coding", origin: "cli" }, cfg);
  m.create("s1", spec, "claude-code", 1000);
  m.setSummary("s1", { topic: "a", summary: "first" }, 2000);
  m.setSummary("s1", { topic: "b", summary: "second" }, 3000);
  const rec = m.get("s1")!;
  assert.equal(rec.topic, "b");
  assert.equal(rec.summary, "second");
  assert.equal(rec.summaryUpdatedAt, 3000);
  m.close();
});
