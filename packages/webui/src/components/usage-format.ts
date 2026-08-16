// usage-format.ts — pure helpers for the Usage view (T2 #8). Provider truth
// only: tokens are the ground-truth metric; cost_usd is the SDK's notional
// API-equivalent figure (often meaningless under subscription auth) and is
// surfaced only when nonzero, labeled as notional. Mirrors the CLI's
// usage-cli.ts semantics so the two surfaces can't drift apart silently.

import { fmtTokens, formatBudgetLine, formatPlanLimitsHeader, formatPlanWindowLine, type UsageBudget, type UsageEntryWire } from "@marmalade/protocol";

// fmtTokens/formatBudgetLine live in @marmalade/protocol (usage-format.ts) —
// the ONE copy shared with the CLI, so the surfaces can't drift (2026-07-18
// review). Re-exported so views/tests keep importing from here.
export { fmtTokens, formatBudgetLine, formatPlanLimitsHeader, formatPlanWindowLine };
export type { UsageBudget };

/** Bar fill fraction (0..1, clamped) for one plan-limit window. */
export function planWindowFraction(w: { utilization: number | null }): number {
  return w.utilization === null ? 0 : Math.min(1, Math.max(0, w.utilization / 100));
}

/** Bar fill fraction (0..1, clamped) for the budget bar. 0 when no limit. */
export function budgetFraction(b: UsageBudget): number {
  if (b.daily_limit <= 0) return 0;
  return Math.min(1, b.today_total / b.daily_limit);
}

export interface DayRollup {
  day: string;
  entries: UsageEntryWire[];
  turns: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
}

/** Group window entries per day (ascending) with per-day totals. */
export function rollupByDay(entries: UsageEntryWire[]): DayRollup[] {
  const byDay = new Map<string, DayRollup>();
  for (const e of entries) {
    const d = byDay.get(e.day) ?? { day: e.day, entries: [], turns: 0, inputTokens: 0, outputTokens: 0, costUsd: 0 };
    d.entries.push(e);
    d.turns += e.turns;
    d.inputTokens += e.input_tokens;
    d.outputTokens += e.output_tokens;
    d.costUsd += e.cost_usd;
    byDay.set(e.day, d);
  }
  return [...byDay.values()].sort((a, b) => (a.day < b.day ? -1 : 1));
}

/** Window totals across all days. */
export function windowTotals(entries: UsageEntryWire[]): { turns: number; inputTokens: number; outputTokens: number; costUsd: number } {
  return entries.reduce(
    (t, e) => ({
      turns: t.turns + e.turns,
      inputTokens: t.inputTokens + e.input_tokens,
      outputTokens: t.outputTokens + e.output_tokens,
      costUsd: t.costUsd + e.cost_usd,
    }),
    { turns: 0, inputTokens: 0, outputTokens: 0, costUsd: 0 },
  );
}

/** Bar width fraction for a day relative to the window's busiest day —
 *  the per-window usage-bar UX (parity-map T2 #8 reference). 0 when the
 *  window is empty. */
export function dayBarFraction(day: DayRollup, days: DayRollup[]): number {
  const max = Math.max(...days.map((d) => d.inputTokens + d.outputTokens), 0);
  return max === 0 ? 0 : (day.inputTokens + day.outputTokens) / max;
}
