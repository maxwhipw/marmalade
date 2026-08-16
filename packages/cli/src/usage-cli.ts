// usage-cli.ts — `marmalade usage` : daily token/turn rollups from the
// daemon's usage.summary (parity-map T2 #8). Provider truth only: token
// counts are the ground-truth metric; cost_usd is the SDK's notional
// API-equivalent figure (often 0/meaningless under subscription auth) and is
// shown only when nonzero. Pure parsing/formatting here (unit-tested); the
// WS plumbing is the shared one-shot RPC session in index.ts.

import { formatBudgetLine, formatPlanLimitsHeader, formatPlanWindowLine, fmtTokens, type PlanLimitsWire, type UsageBudget } from "@marmalade/protocol";
import { parseFlags, type CronCliCall } from "./cron-cli.js";

// Budget/token formatting lives in @marmalade/protocol (usage-format.ts) —
// the ONE copy shared with the webui, so the surfaces can't drift (2026-07-18
// review). Re-exported for existing importers (usage-cli.test.ts).
export { formatBudgetLine, fmtTokens };
export type UsageBudgetWire = UsageBudget;

export const USAGE_USAGE = `usage:
  marmalade usage [--days N]   daily rollups for the trailing N days (default 7, max 90)`;

export interface UsageEntryWire {
  day: string;
  purpose: string;
  cost_usd: number;
  input_tokens: number;
  output_tokens: number;
  turns: number;
}

/** Render the summary as aligned lines: one row per (day, purpose), a
 *  per-day subtotal when a day has multiple purposes, and a window total. */
export function formatUsageLines(result: { today: string; entries: UsageEntryWire[] }, days: number): string[] {
  const lines: string[] = [`usage — trailing ${days} day(s) through ${result.today}`, ""];
  if (result.entries.length === 0) {
    lines.push("(no usage recorded in this window)");
    return lines;
  }
  const byDay = new Map<string, UsageEntryWire[]>();
  for (const e of result.entries) {
    const l = byDay.get(e.day) ?? [];
    l.push(e);
    byDay.set(e.day, l);
  }
  const row = (label: string, purpose: string, turns: number, inTok: number, outTok: number, usd: number) =>
    `${label.padEnd(11)} ${purpose.padEnd(10)} ${String(turns).padStart(4)} turn(s)  ${
      (fmtTokens(inTok) + " in").padStart(10)} / ${(fmtTokens(outTok) + " out").padStart(11)}${
      usd > 0 ? `  $${usd.toFixed(2)}` : ""}`;
  const total = { turns: 0, inTok: 0, outTok: 0, usd: 0 };
  for (const [day, entries] of [...byDay.entries()].sort(([a], [b]) => (a < b ? -1 : 1))) {
    let d = { turns: 0, inTok: 0, outTok: 0, usd: 0 };
    for (const e of entries) {
      lines.push(row(day, e.purpose, e.turns, e.input_tokens, e.output_tokens, e.cost_usd));
      d = { turns: d.turns + e.turns, inTok: d.inTok + e.input_tokens, outTok: d.outTok + e.output_tokens, usd: d.usd + e.cost_usd };
    }
    if (entries.length > 1) lines.push(row("", "= day", d.turns, d.inTok, d.outTok, d.usd));
    total.turns += d.turns; total.inTok += d.inTok; total.outTok += d.outTok; total.usd += d.usd;
  }
  if (byDay.size > 1) {
    lines.push("");
    lines.push(row("TOTAL", "", total.turns, total.inTok, total.outTok, total.usd));
  }
  return lines;
}

export async function usageCommand(argv: string[], call: CronCliCall, print: (line: string) => void): Promise<number> {
  const [flags, positionals] = parseFlags(argv);
  if (positionals.length > 0) throw new Error(`unexpected argument "${positionals[0]}"`);
  const days = flags.has("days") ? Number(flags.get("days")) : 7;
  if (!Number.isInteger(days) || days < 1 || days > 90) {
    throw new Error(`--days must be an integer 1..90, got "${flags.get("days")}"`);
  }
  const result = (await call("usage.summary", { days })) as {
    today: string; entries: UsageEntryWire[]; budget?: UsageBudgetWire | null; plan_limits?: PlanLimitsWire[];
  };
  for (const line of formatUsageLines(result, days)) print(line);
  if (result.budget) {
    print("");
    print(formatBudgetLine(result.budget));
  }
  // Subscription plan-limit windows (Claude Code /usage) — present only when
  // a live session could report them. Reset times print as the daemon's ISO
  // strings (CLI convention; clients render them relative).
  for (const p of result.plan_limits ?? []) {
    print("");
    print(formatPlanLimitsHeader(p));
    for (const w of p.windows) {
      print(`  ${formatPlanWindowLine(w)}${w.resets_at ? ` — resets ${w.resets_at}` : ""}`);
    }
  }
  return 0;
}
