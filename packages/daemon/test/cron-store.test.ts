// cron-store.test.ts — persistence is the point: jobs must survive a daemon
// restart byte-for-byte (the store is what restart-catchup reads).

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { SessionManager } from "../dist/session-manager.js";
import type { CronJobRecord } from "../dist/cron-store.js";

function job(id: string, over: Partial<CronJobRecord> = {}): CronJobRecord {
  return {
    id, name: null, sessionId: "s_1", prompt: "do the thing",
    schedule: { kind: "every", every_ms: 60_000, anchor_ms: 1000 },
    enabled: true, createdAt: 1000, updatedAt: 1000, nextRunAt: 61_000,
    lastRunAt: null, lastStatus: null, lastError: null,
    ...over,
  };
}

test("CRUD + list includes disabled jobs (#16156 class)", () => {
  const m = SessionManager.inMemory();
  m.cron.create(job("a"));
  m.cron.create(job("b", { enabled: false, nextRunAt: null, createdAt: 2000 }));
  assert.equal(m.cron.list().length, 2, "list must NOT skip disabled jobs");
  assert.equal(m.cron.get("b")!.enabled, false);

  const upd = m.cron.update("a", { prompt: "new prompt", name: "daily" }, 5000)!;
  assert.equal(upd.prompt, "new prompt");
  assert.equal(upd.updatedAt, 5000);
  assert.equal(m.cron.get("a")!.name, "daily");
  assert.equal(m.cron.update("nope", { prompt: "x" }, 1), undefined);

  assert.equal(m.cron.delete("a"), true);
  assert.equal(m.cron.delete("a"), false);
  assert.equal(m.cron.list().length, 1);
  m.close();
});

test("jobs survive close + reopen (restart persistence)", () => {
  const db = join(tmpdir(), `cron-${randomUUID()}.db`);
  try {
    const m1 = new SessionManager(db);
    m1.cron.create(job("persist", {
      schedule: { kind: "cron", expr: "0 3 * * *", tz: "America/New_York", stagger_ms: 0 },
      nextRunAt: 999_999, lastRunAt: 500, lastStatus: "error", lastError: "boom",
    }));
    m1.close();

    const m2 = new SessionManager(db);
    const j = m2.cron.get("persist")!;
    assert.deepEqual(j.schedule, { kind: "cron", expr: "0 3 * * *", tz: "America/New_York", stagger_ms: 0 });
    assert.equal(j.nextRunAt, 999_999);
    assert.equal(j.lastStatus, "error");
    assert.equal(j.lastError, "boom");
    m2.close();
  } finally {
    rmSync(db, { force: true });
  }
});

test("due() returns only enabled jobs whose time has arrived, ordered", () => {
  const m = SessionManager.inMemory();
  m.cron.create(job("late", { nextRunAt: 100 }));
  m.cron.create(job("later", { nextRunAt: 200, createdAt: 1001 }));
  m.cron.create(job("future", { nextRunAt: 10_000, createdAt: 1002 }));
  m.cron.create(job("off", { nextRunAt: 100, enabled: false, createdAt: 1003 }));
  m.cron.create(job("unarmed", { nextRunAt: null, createdAt: 1004 }));
  assert.deepEqual(m.cron.due(500).map((j) => j.id), ["late", "later"]);
  assert.equal(m.cron.earliestNextRun(), 100);
  m.close();
});

test("recordRun never touches next_run_at; disableWithError disarms loudly", () => {
  const m = SessionManager.inMemory();
  m.cron.create(job("r", { nextRunAt: 999 }));
  m.cron.recordRun("r", 800, "ok");
  let j = m.cron.get("r")!;
  assert.equal(j.nextRunAt, 999, "recordRun must not move the schedule");
  assert.equal(j.lastRunAt, 800);
  assert.equal(j.lastStatus, "ok");
  assert.equal(j.lastError, null);

  m.cron.recordRun("r", 900, "error", "target gone");
  assert.equal(m.cron.get("r")!.lastError, "target gone");

  m.cron.disableWithError("r", 950, "schedule has no future occurrence — job disabled");
  j = m.cron.get("r")!;
  assert.equal(j.enabled, false);
  assert.equal(j.nextRunAt, null);
  assert.match(j.lastError!, /no future occurrence/);
  m.close();
});
