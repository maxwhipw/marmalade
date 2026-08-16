import { test } from "node:test";
import assert from "node:assert/strict";
import { SessionManager } from "../dist/session-manager.js";
import { Supervisor } from "../dist/supervisor.js";
import { createSessionSpec } from "../dist/policy.js";
import { defaultConfig } from "../dist/config.js";

const cfg = defaultConfig();

test("supervisor detects a silent failure and alerts exactly once", () => {
  const sessions = SessionManager.inMemory();
  const spec = createSessionSpec({ principal: "owner", purpose: "cadence", origin: "cadence" }, cfg);
  sessions.create("stale", spec, "claude-code", 0); // heartbeat at t=0

  const alerts: string[] = [];
  let clock = 200_000; // 200s later, > default 120s timeout
  const sup = new Supervisor(sessions, {
    now: () => clock,
    onSilentFailure: (rec) => alerts.push(rec.id),
  });

  const first = sup.tick();
  assert.equal(first.length, 1);
  assert.equal(first[0].id, "stale");
  assert.equal(sessions.get("stale")?.status, "hung");

  // Second tick must NOT re-alert (latch).
  clock += 20_000;
  const second = sup.tick();
  assert.equal(second.length, 0);
  assert.equal(alerts.length, 1);
});

test("a session with a fresh heartbeat is not flagged", () => {
  const sessions = SessionManager.inMemory();
  const spec = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);
  sessions.create("healthy", spec, "claude-code", 0);

  let clock = 60_000;
  sessions.heartbeat("healthy", clock); // fresh heartbeat at 60s
  const sup = new Supervisor(sessions, { now: () => clock });
  assert.equal(sup.tick().length, 0);
});
