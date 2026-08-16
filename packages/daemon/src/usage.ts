// usage.ts — the usage meter (Decision 6 economics guardrail, review A1).
// A FILE, not a subsystem (simp-M1): accumulate cost + tokens per (day,
// purpose), alert on a daily budget breach.
//
// SDK-FACTS.md caveat (coh-M5): total_cost_usd is always present but its VALUE
// under subscription auth is unverified — may be 0/notional. So the meter
// tracks BOTH dollars and token counts; the budget guardrail can be driven off
// tokens (always reliable) if the dollar figure proves untrustworthy. The
// adapter records `apiKeySource` alongside so we can tell which regime we're in.

import { DatabaseSync } from "node:sqlite";
import type { Purpose } from "./policy.js";

export interface UsageEntry {
  day: string; // YYYY-MM-DD (caller supplies — no Date.now in pure core)
  purpose: Purpose;
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  turns: number;
}

export interface BudgetConfig {
  /** Daily ceiling. Which metric it applies to depends on trust (see above). */
  metric: "usd" | "tokens";
  dailyLimit: number;
}

export class UsageMeter {
  // Persisted in the session SQLite (usage.summary, parity-map T2 #8): an
  // in-memory-only meter zeroed on every restart makes the summary surface
  // lie. No handle (tests) → ephemeral :memory: db, same semantics, one
  // code path.
  private readonly db: DatabaseSync;

  constructor(db?: DatabaseSync) {
    this.db = db ?? new DatabaseSync(":memory:");
    this.db.exec(`CREATE TABLE IF NOT EXISTS usage_daily (
      day TEXT NOT NULL,
      purpose TEXT NOT NULL,
      cost_usd REAL NOT NULL DEFAULT 0,
      input_tokens INTEGER NOT NULL DEFAULT 0,
      output_tokens INTEGER NOT NULL DEFAULT 0,
      turns INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (day, purpose)
    )`);
  }

  record(day: string, purpose: Purpose, delta: { costUsd: number; inputTokens: number; outputTokens: number }): void {
    this.db.prepare(
      `INSERT INTO usage_daily (day, purpose, cost_usd, input_tokens, output_tokens, turns)
       VALUES (?, ?, ?, ?, ?, 1)
       ON CONFLICT(day, purpose) DO UPDATE SET
         cost_usd = cost_usd + excluded.cost_usd,
         input_tokens = input_tokens + excluded.input_tokens,
         output_tokens = output_tokens + excluded.output_tokens,
         turns = turns + 1`,
    ).run(day, purpose, delta.costUsd, delta.inputTokens, delta.outputTokens);
  }

  /** Total across all purposes for a day, in the budget's metric. */
  dayTotal(day: string, metric: BudgetConfig["metric"]): number {
    const row = this.db.prepare(
      `SELECT COALESCE(SUM(${metric === "usd" ? "cost_usd" : "input_tokens + output_tokens"}), 0) AS total
       FROM usage_daily WHERE day = ?`,
    ).get(day) as { total: number };
    return row.total;
  }

  /** True when the day's total has breached the budget — caller fires the push
   *  alert. Ambient purposes are the ones this protects (crons, future voice). */
  isOverBudget(day: string, budget: BudgetConfig): boolean {
    return this.dayTotal(day, budget.metric) > budget.dailyLimit;
  }

  breakdown(day: string): UsageEntry[] {
    return this.rows("WHERE day = ?", [day]);
  }

  /** Entries for the trailing window ending at `today` inclusive —
   *  `usage.summary`'s read. Days are lexicographic YYYY-MM-DD, so string
   *  comparison IS date comparison; the caller computes the window start
   *  (no Date math in the pure core). */
  summary(fromDay: string, toDay: string): UsageEntry[] {
    return this.rows("WHERE day >= ? AND day <= ? ORDER BY day, purpose", [fromDay, toDay]);
  }

  private rows(where: string, params: string[]): UsageEntry[] {
    const raw = this.db.prepare(
      `SELECT day, purpose, cost_usd, input_tokens, output_tokens, turns FROM usage_daily ${where}`,
    ).all(...params) as { day: string; purpose: string; cost_usd: number; input_tokens: number; output_tokens: number; turns: number }[];
    return raw.map((r) => ({
      day: r.day, purpose: r.purpose as Purpose, costUsd: r.cost_usd,
      inputTokens: r.input_tokens, outputTokens: r.output_tokens, turns: r.turns,
    }));
  }
}
