// usage-cli.test.ts — pure parsing/formatting for `marmalade usage`.

import { test } from "node:test";
import assert from "node:assert/strict";
import { fmtTokens, formatUsageLines, usageCommand } from "../dist/usage-cli.js";

test("fmtTokens: plain under 1k, k under 1M, M above", () => {
  assert.equal(fmtTokens(999), "999");
  assert.equal(fmtTokens(1_234), "1.2k");
  assert.equal(fmtTokens(12_345_678), "12.3M");
});

test("formatUsageLines: empty window says so", () => {
  const lines = formatUsageLines({ today: "2026-07-18", entries: [] }, 7);
  assert.ok(lines[0]!.includes("through 2026-07-18"));
  assert.ok(lines.some((l) => l.includes("no usage recorded")));
});

test("formatUsageLines: per-day subtotal only for multi-purpose days; total only for multi-day windows", () => {
  const entries = [
    { day: "2026-07-17", purpose: "main", cost_usd: 0, input_tokens: 1000, output_tokens: 100, turns: 2 },
    { day: "2026-07-17", purpose: "cron", cost_usd: 0, input_tokens: 500, output_tokens: 50, turns: 1 },
    { day: "2026-07-18", purpose: "main", cost_usd: 1.5, input_tokens: 2000, output_tokens: 200, turns: 3 },
  ];
  const lines = formatUsageLines({ today: "2026-07-18", entries }, 7);
  assert.equal(lines.filter((l) => l.includes("= day")).length, 1); // only 07-17 has 2 purposes
  assert.equal(lines.filter((l) => l.startsWith("TOTAL")).length, 1);
  const total = lines.find((l) => l.startsWith("TOTAL"))!;
  assert.ok(total.includes("6 turn(s)"));
  assert.ok(total.includes("$1.50"));
  // cost hidden when zero
  const cronLine = lines.find((l) => l.includes("cron"))!;
  assert.ok(!cronLine.includes("$"));
});

test("usageCommand: passes --days through, rejects junk", async () => {
  const calls: any[] = [];
  const out: string[] = [];
  const call = async (method: string, params: Record<string, unknown>) => {
    calls.push([method, params]);
    return { today: "2026-07-18", entries: [] };
  };
  const code = await usageCommand(["--days", "30"], call, (l) => out.push(l));
  assert.equal(code, 0);
  assert.deepEqual(calls, [["usage.summary", { days: 30 }]]);
  assert.ok(out.length > 0);

  await assert.rejects(usageCommand(["--days", "0"], call, () => {}), /1\.\.90/);
  await assert.rejects(usageCommand(["--days", "banana"], call, () => {}), /1\.\.90/);
  await assert.rejects(usageCommand(["extra"], call, () => {}), /unexpected argument/);
});

test("budget line: under, over, tokens metric; absent budget prints nothing", async () => {
  const { formatBudgetLine } = await import("../dist/usage-cli.js");
  assert.equal(
    formatBudgetLine({ metric: "usd", daily_limit: 20, today_total: 5, over: false }),
    "budget: $5.00 of $20.00/day (25%)",
  );
  assert.match(
    formatBudgetLine({ metric: "usd", daily_limit: 5, today_total: 6, over: true }),
    /OVER.*scheduled prompts are paused/,
  );
  assert.match(
    formatBudgetLine({ metric: "tokens", daily_limit: 5_000_000, today_total: 1_200_000, over: false }),
    /1\.2M of 5\.0M\/day \(24%\)/,
  );

  const out: string[] = [];
  const call = async () => ({ today: "2026-07-18", entries: [], budget: { metric: "usd", daily_limit: 5, today_total: 6, over: true } });
  await usageCommand([], call as any, (l) => out.push(l));
  assert.match(out.join("\n"), /budget: OVER/);

  const out2: string[] = [];
  const call2 = async () => ({ today: "2026-07-18", entries: [], budget: null });
  await usageCommand([], call2 as any, (l) => out2.push(l));
  assert.ok(!out2.some((l) => l.startsWith("budget:")));
});
