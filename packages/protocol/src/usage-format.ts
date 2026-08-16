// usage-format.ts — the ONE copy of the usage/budget display formatting shared
// by every TS surface (CLI `marmalade usage`, webui Usage tab). Previously the
// CLI and webui each hand-mirrored these with "keep in sync" comments; a
// reword in one silently drifted the other (2026-07-18 review finding). The
// Android client remains a justified hand-mirror (Kotlin) — its
// UsageFormatUtilsTest pins this exact wording.
//
// Formatting is deliberately locale-independent (template strings + toFixed):
// these lines are cross-surface protocol prose, not user-locale display text.

import type { PlanLimitWindowWire, PlanLimitsWire, UsageSummaryResult } from "./methods.js";

/** The daemon's daily budget block (usage.summary.budget), non-null. */
export type UsageBudget = NonNullable<UsageSummaryResult["budget"]>;

/** 1234 → "1.2k", 12_345_678 → "12.3M", 999 → "999". */
export function fmtTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

/** One line describing the daily budget state. The `over` copy names
 *  SCHEDULED (cron) turns only — budget never blocks interactive prompts,
 *  and no surface may imply otherwise (T2 #8 guardrail). */
export function formatBudgetLine(b: UsageBudget): string {
  const fmt = (n: number) => (b.metric === "usd" ? `$${n.toFixed(2)}` : fmtTokens(n));
  const pct = b.daily_limit > 0 ? Math.round((b.today_total / b.daily_limit) * 100) : 0;
  return b.over
    ? `budget: OVER — ${fmt(b.today_total)} of ${fmt(b.daily_limit)}/day (${pct}%) — scheduled prompts are paused until tomorrow`
    : `budget: ${fmt(b.today_total)} of ${fmt(b.daily_limit)}/day (${pct}%)`;
}

/** Header for one harness's subscription plan-limit block
 *  (usage.summary.plan_limits[i]): `plan limits — claude-code (max plan)`. */
export function formatPlanLimitsHeader(p: Pick<PlanLimitsWire, "harness" | "subscription_type">): string {
  return `plan limits — ${p.harness}${p.subscription_type ? ` (${p.subscription_type} plan)` : ""}`;
}

/** One plan-limit window line: `5-hour: 34% used`. Reset-time rendering stays
 *  surface-specific (relative on Android/webui, ISO on the CLI) — only the
 *  utilization prose is protocol-shared. */
export function formatPlanWindowLine(w: Pick<PlanLimitWindowWire, "label" | "utilization">): string {
  return w.utilization === null ? `${w.label}: —` : `${w.label}: ${Math.round(w.utilization)}% used`;
}
