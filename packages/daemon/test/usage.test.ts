import { test } from "node:test";
import assert from "node:assert/strict";
import { UsageMeter } from "../dist/usage.js";

test("records accumulate per (day, purpose)", () => {
  const m = new UsageMeter();
  m.record("2026-07-11", "main", { costUsd: 0.01, inputTokens: 100, outputTokens: 50 });
  m.record("2026-07-11", "main", { costUsd: 0.02, inputTokens: 200, outputTokens: 60 });
  m.record("2026-07-11", "cadence", { costUsd: 0.005, inputTokens: 10, outputTokens: 5 });
  const main = m.breakdown("2026-07-11").find((e) => e.purpose === "main")!;
  assert.equal(main.costUsd, 0.03);
  assert.equal(main.inputTokens, 300);
  assert.equal(main.turns, 2);
});

test("dayTotal sums across purposes in the chosen metric", () => {
  const m = new UsageMeter();
  m.record("2026-07-11", "main", { costUsd: 0.03, inputTokens: 300, outputTokens: 110 });
  m.record("2026-07-11", "cadence", { costUsd: 0.005, inputTokens: 10, outputTokens: 5 });
  assert.ok(Math.abs(m.dayTotal("2026-07-11", "usd") - 0.035) < 1e-9);
  assert.equal(m.dayTotal("2026-07-11", "tokens"), 300 + 110 + 10 + 5);
});

test("budget breach detected (tokens metric — the reliable fallback)", () => {
  const m = new UsageMeter();
  m.record("2026-07-11", "cadence", { costUsd: 0, inputTokens: 900_000, outputTokens: 200_000 });
  assert.ok(m.isOverBudget("2026-07-11", { metric: "tokens", dailyLimit: 1_000_000 }));
  assert.ok(!m.isOverBudget("2026-07-11", { metric: "tokens", dailyLimit: 2_000_000 }));
});

test("days are isolated", () => {
  const m = new UsageMeter();
  m.record("2026-07-10", "main", { costUsd: 1, inputTokens: 1, outputTokens: 1 });
  assert.equal(m.dayTotal("2026-07-11", "usd"), 0);
});

// ---- persistence (T2 #8): the meter is db-backed; totals survive a restart

test("totals survive meter reconstruction over the same db file", async () => {
  const { DatabaseSync } = await import("node:sqlite");
  const { tmpdir } = await import("node:os");
  const { join } = await import("node:path");
  const { rmSync } = await import("node:fs");
  const path = join(tmpdir(), `usage-${process.pid}-${Date.now()}.db`);
  try {
    const db1 = new DatabaseSync(path);
    const a = new UsageMeter(db1);
    a.record("2026-07-18", "main", { costUsd: 0.5, inputTokens: 100, outputTokens: 10 });
    a.record("2026-07-18", "main", { costUsd: 0.25, inputTokens: 50, outputTokens: 5 });
    db1.close();
    const db2 = new DatabaseSync(path); // the "restart"
    const b = new UsageMeter(db2);
    assert.equal(b.dayTotal("2026-07-18", "tokens"), 165);
    const main = b.breakdown("2026-07-18").find((e) => e.purpose === "main")!;
    assert.equal(main.turns, 2);
    assert.ok(Math.abs(main.costUsd - 0.75) < 1e-9);
    db2.close();
  } finally { rmSync(path, { force: true }); }
});

test("summary returns only the [from..to] window, ordered by day", () => {
  const m = new UsageMeter();
  m.record("2026-07-10", "main", { costUsd: 0, inputTokens: 1, outputTokens: 1 }); // outside
  m.record("2026-07-12", "main", { costUsd: 0, inputTokens: 2, outputTokens: 2 });
  m.record("2026-07-14", "cadence", { costUsd: 0, inputTokens: 3, outputTokens: 3 });
  m.record("2026-07-18", "main", { costUsd: 0, inputTokens: 4, outputTokens: 4 });
  const win = m.summary("2026-07-12", "2026-07-18");
  assert.deepEqual(win.map((e) => e.day), ["2026-07-12", "2026-07-14", "2026-07-18"]);
  assert.ok(win.every((e) => e.day >= "2026-07-12" && e.day <= "2026-07-18"));
});
