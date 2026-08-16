// usage-format.test.ts — pure helpers behind the Usage view (T2 #8).

import { describe, expect, test } from "vitest";
import {
  budgetFraction,
  dayBarFraction,
  fmtTokens,
  formatBudgetLine,
  rollupByDay,
  windowTotals,
  formatPlanLimitsHeader,
  formatPlanWindowLine,
  planWindowFraction,
  type UsageBudget,
} from "../src/components/usage-format.js";
import type { UsageEntryWire } from "@marmalade/protocol";

const e = (day: string, purpose: string, inTok: number, outTok: number, turns = 1, usd = 0): UsageEntryWire =>
  ({ day, purpose, cost_usd: usd, input_tokens: inTok, output_tokens: outTok, turns });

describe("fmtTokens", () => {
  test("plain under 1k, k under 1M, M above", () => {
    expect(fmtTokens(999)).toBe("999");
    expect(fmtTokens(46_885)).toBe("46.9k");
    expect(fmtTokens(2_500_000)).toBe("2.5M");
  });
});

describe("rollupByDay", () => {
  test("groups per day ascending with per-day totals", () => {
    const days = rollupByDay([
      e("2026-07-18", "main", 100, 10, 2, 0.5),
      e("2026-07-17", "main", 50, 5),
      e("2026-07-18", "cron", 30, 3),
    ]);
    expect(days.map((d) => d.day)).toEqual(["2026-07-17", "2026-07-18"]);
    const d18 = days[1]!;
    expect(d18.turns).toBe(3);
    expect(d18.inputTokens).toBe(130);
    expect(d18.outputTokens).toBe(13);
    expect(d18.costUsd).toBeCloseTo(0.5);
    expect(d18.entries.length).toBe(2);
  });
});

describe("windowTotals + dayBarFraction", () => {
  test("totals sum the window; the busiest day's bar is full-width", () => {
    const entries = [e("2026-07-17", "main", 100, 0), e("2026-07-18", "main", 25, 0)];
    const totals = windowTotals(entries);
    expect(totals.inputTokens).toBe(125);
    expect(totals.turns).toBe(2);
    const days = rollupByDay(entries);
    expect(dayBarFraction(days[0]!, days)).toBe(1);
    expect(dayBarFraction(days[1]!, days)).toBe(0.25);
  });

  test("empty window has no bars", () => {
    expect(dayBarFraction({ day: "x", entries: [], turns: 0, inputTokens: 0, outputTokens: 0, costUsd: 0 }, [])).toBe(0);
  });
});

describe("budget line + fraction", () => {
  const budget = (over: Partial<UsageBudget>): UsageBudget =>
    ({ metric: "usd", daily_limit: 20, today_total: 5, over: false, ...over });

  test("under-budget usd line matches the CLI wording", () => {
    expect(formatBudgetLine(budget({}))).toBe("budget: $5.00 of $20.00/day (25%)");
  });

  test("over-budget line is loud and names SCHEDULED prompts (not the user's)", () => {
    const line = formatBudgetLine(budget({ today_total: 24, over: true }));
    expect(line).toContain("OVER");
    expect(line).toContain("scheduled prompts are paused");
  });

  test("tokens metric humanizes the totals", () => {
    const line = formatBudgetLine(budget({ metric: "tokens", daily_limit: 2_000_000, today_total: 1_500_000 }));
    expect(line).toBe("budget: 1.5M of 2.0M/day (75%)");
  });

  test("fraction clamps to 1 when over and is 0 with no limit", () => {
    expect(budgetFraction(budget({ today_total: 30, daily_limit: 20 }))).toBe(1);
    expect(budgetFraction(budget({ today_total: 5, daily_limit: 20 }))).toBe(0.25);
    expect(budgetFraction(budget({ daily_limit: 0 }))).toBe(0);
  });
});

describe("plan limits (subscription windows)", () => {
  test("header names the harness and plan tier", () => {
    expect(formatPlanLimitsHeader({ harness: "claude-code", subscription_type: "max" })).toBe(
      "plan limits — claude-code (max plan)",
    );
    expect(formatPlanLimitsHeader({ harness: "claude-code", subscription_type: null })).toBe(
      "plan limits — claude-code",
    );
  });

  test("window line rounds utilization and dashes the unknown", () => {
    expect(formatPlanWindowLine({ label: "5-hour", utilization: 33.6 })).toBe("5-hour: 34% used");
    expect(formatPlanWindowLine({ label: "Weekly (Opus)", utilization: null })).toBe("Weekly (Opus): —");
  });

  test("fraction clamps to 0..1 and treats null as empty", () => {
    expect(planWindowFraction({ utilization: 34 })).toBe(0.34);
    expect(planWindowFraction({ utilization: 250 })).toBe(1);
    expect(planWindowFraction({ utilization: null })).toBe(0);
  });
});
