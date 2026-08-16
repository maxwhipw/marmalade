// UsageView.tsx — daily usage rollups from usage.summary (T2 #8). Read-only:
// the daemon owns the meter; this renders the window. Tokens are the primary
// metric; cost_usd renders only when nonzero, labeled notional (provider
// truth — under subscription auth the dollar figure is API-equivalent, not a
// real charge).

import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { UsageSummaryResult } from "@marmalade/protocol";
import { useGateway } from "../app/gateway-context.js";
import { budgetFraction, dayBarFraction, fmtTokens, formatBudgetLine, formatPlanLimitsHeader, formatPlanWindowLine, planWindowFraction, rollupByDay, windowTotals } from "../components/usage-format.js";

const WINDOWS = [7, 14, 30, 90] as const;

export function UsageView(): ReactNode {
  const { client, status } = useGateway();
  const [days, setDays] = useState<number>(7);
  const [summary, setSummary] = useState<UsageSummaryResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    client.usageSummary(days).then(
      (s) => { setSummary(s); setError(null); },
      (e: Error) => setError(e.message),
    );
  }, [client, days]);

  useEffect(() => {
    if (status === "connected") refresh();
  }, [status, refresh]);

  const rollups = summary ? rollupByDay(summary.entries) : [];
  const totals = summary ? windowTotals(summary.entries) : null;

  return (
    <div className="mm-settings">
      <section className="mm-card">
        <div className="mm-row" style={{ justifyContent: "space-between" }}>
          <h2 style={{ margin: 0 }}>Usage</h2>
          <div className="mm-row" style={{ gap: 8 }}>
            {WINDOWS.map((w) => (
              <button
                key={w}
                className={`mm-btn ghost small${w === days ? " active" : ""}`}
                aria-pressed={w === days}
                onClick={() => setDays(w)}
              >
                {w}d
              </button>
            ))}
            <button className="mm-btn ghost small" onClick={refresh}>Refresh</button>
          </div>
        </div>
        {error && <p className="mm-hint" style={{ color: "var(--error)" }}>{error}</p>}
        {summary && (
          <p className="mm-hint">
            Trailing {days} days through {summary.today}. Token counts are provider truth;
            dollar figures are the SDK&apos;s notional API-equivalent, not a real charge.
          </p>
        )}

        {/* Budget guardrail: rendered only when the daemon has one configured
            (usage.summary.budget != null). It gates SCHEDULED (cron) turns —
            never the user's own prompts. */}
        {summary?.budget && (
          <div className={`mm-budget${summary.budget.over ? " over" : ""}`}>
            <div className="mm-budget-line">
              <span>{formatBudgetLine(summary.budget)}</span>
            </div>
            <div className="mm-budget-bar" aria-hidden>
              <div
                className="mm-budget-fill"
                style={{ width: `${Math.round(budgetFraction(summary.budget) * 100)}%` }}
              />
            </div>
            <p className="mm-hint" style={{ marginTop: 6 }}>
              The daily budget gates scheduled (cron) turns only — your own prompts are never blocked.
            </p>
          </div>
        )}

        {/* Subscription plan limits (Claude Code /usage windows). Present
            only while a live session can report them; a future subscription
            harness (e.g. Codex) shows up as its own block automatically. */}
        {summary?.plan_limits.map((p) => (
          <div key={p.harness} className="mm-budget">
            <div className="mm-budget-line">
              <span>{formatPlanLimitsHeader(p)}</span>
            </div>
            {p.windows.map((w) => (
              <div key={w.id} style={{ marginTop: 6 }}>
                <div className="mm-row" style={{ justifyContent: "space-between" }}>
                  <span>{formatPlanWindowLine(w)}</span>
                  {w.resets_at && (
                    <span className="mm-hint" style={{ margin: 0 }}>
                      resets {new Date(w.resets_at).toLocaleString()}
                    </span>
                  )}
                </div>
                <div className="mm-usage-bar" aria-hidden>
                  <div className="mm-usage-bar-fill" style={{ width: `${Math.round(planWindowFraction(w) * 100)}%` }} />
                </div>
              </div>
            ))}
          </div>
        ))}

        {summary && rollups.length === 0 && !error && (
          <p className="mm-hint">No usage recorded in this window.</p>
        )}

        {rollups.length > 0 && totals && (
          <>
            <div className="mm-usage-days">
              {rollups.map((d) => (
                <div key={d.day} className="mm-usage-day">
                  <div className="mm-row" style={{ justifyContent: "space-between" }}>
                    <strong>{d.day}</strong>
                    <span className="mm-hint" style={{ margin: 0 }}>
                      {d.turns} turn{d.turns === 1 ? "" : "s"} · {fmtTokens(d.inputTokens)} in / {fmtTokens(d.outputTokens)} out
                      {d.costUsd > 0 ? ` · $${d.costUsd.toFixed(2)} notional` : ""}
                    </span>
                  </div>
                  <div className="mm-usage-bar" aria-hidden>
                    <div className="mm-usage-bar-fill" style={{ width: `${Math.round(dayBarFraction(d, rollups) * 100)}%` }} />
                  </div>
                  {d.entries.length > 1 && (
                    <div className="mm-hint" style={{ marginTop: 2 }}>
                      {d.entries.map((e) => (
                        <span key={e.purpose} style={{ marginRight: 12 }}>
                          {e.purpose}: {fmtTokens(e.input_tokens + e.output_tokens)} tok · {e.turns} turn{e.turns === 1 ? "" : "s"}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
            <div className="mm-row" style={{ justifyContent: "space-between", marginTop: 12 }}>
              <strong>Window total</strong>
              <span>
                {totals.turns} turn{totals.turns === 1 ? "" : "s"} · {fmtTokens(totals.inputTokens)} in / {fmtTokens(totals.outputTokens)} out
                {totals.costUsd > 0 ? ` · $${totals.costUsd.toFixed(2)} notional` : ""}
              </span>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
