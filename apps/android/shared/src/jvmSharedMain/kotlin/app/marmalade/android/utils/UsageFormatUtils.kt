package app.marmalade.android.utils

import app.marmalade.android.rpc.types.PlanLimitWindow
import app.marmalade.android.rpc.types.PlanLimits
import app.marmalade.android.rpc.types.UsageBudget
import app.marmalade.android.rpc.types.UsageEntry
import java.util.Locale

/**
 * Pure helpers for the Usage screen (daemon usage.summary, T2 #8). Mirrors
 * the daemon CLI's usage-cli.ts / webui's usage-format.ts semantics so the
 * three surfaces can't drift apart silently. Provider truth: token counts
 * are the ground-truth metric; costUsd is the SDK's notional API-equivalent
 * figure — render it secondary, and only when nonzero.
 *
 * All number formatting is pinned to [Locale.US] so a comma-decimal device
 * locale can't render "$5,00" and desync from the CLI/webui (which use
 * toFixed — locale-independent). The unit tests would otherwise pass only
 * because the JVM default is en-US.
 */
object UsageFormatUtils {

    /** 1234 -> "1.2k", 12_345_678 -> "12.3M", 999 -> "999". */
    fun fmtTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    data class DayRollup(
        val day: String,
        val entries: List<UsageEntry>,
        val turns: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val costUsd: Double,
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens
    }

    /** Group window entries per day, ascending, with per-day totals. */
    fun rollupByDay(entries: List<UsageEntry>): List<DayRollup> =
        entries.groupBy { it.day }.map { (day, dayEntries) ->
            DayRollup(
                day = day,
                entries = dayEntries,
                turns = dayEntries.sumOf { it.turns },
                inputTokens = dayEntries.sumOf { it.inputTokens },
                outputTokens = dayEntries.sumOf { it.outputTokens },
                costUsd = dayEntries.sumOf { it.costUsd },
            )
        }.sortedBy { it.day }

    /** Bar width fraction for a day relative to the window's busiest day
     *  (the per-window usage-bar UX). 0 when the window is empty. */
    fun dayBarFraction(day: DayRollup, days: List<DayRollup>): Float {
        val max = days.maxOfOrNull { it.totalTokens } ?: 0L
        return if (max == 0L) 0f else day.totalTokens.toFloat() / max
    }

    /** "3 turns · 46.9k in / 1.2k out" (+ " · $0.47 notional" when nonzero). */
    fun summaryLine(turns: Int, inputTokens: Long, outputTokens: Long, costUsd: Double): String {
        val turnsPart = if (turns == 1) "1 turn" else "$turns turns"
        val cost = if (costUsd > 0) String.format(Locale.US, " · $%.2f notional", costUsd) else ""
        return "$turnsPart · ${fmtTokens(inputTokens)} in / ${fmtTokens(outputTokens)} out$cost"
    }

    /** One line describing the daily budget state — mirrors the CLI's
     *  formatBudgetLine (usage-cli.ts) verbatim so the surfaces can't drift.
     *  The `over` copy names SCHEDULED (cron) turns only; interactive prompts
     *  are never blocked. */
    fun formatBudgetLine(b: UsageBudget): String {
        val fmt = { n: Double -> if (b.metric == "usd") String.format(Locale.US, "$%.2f", n) else fmtTokens(n.toLong()) }
        val pct = if (b.daily_limit > 0) Math.round(b.today_total / b.daily_limit * 100).toInt() else 0
        return if (b.over) {
            "budget: OVER — ${fmt(b.today_total)} of ${fmt(b.daily_limit)}/day ($pct%) — scheduled prompts are paused until tomorrow"
        } else {
            "budget: ${fmt(b.today_total)} of ${fmt(b.daily_limit)}/day ($pct%)"
        }
    }

    /** Budget bar fill fraction (0..1, clamped). 0 when there's no limit. */
    fun budgetFraction(b: UsageBudget): Float {
        if (b.daily_limit <= 0) return 0f
        return minOf(1f, (b.today_total / b.daily_limit).toFloat())
    }

    // ── Plan limits (usage.summary.plan_limits — Claude Code /usage) ─────────
    // Mirrors the protocol's formatPlanLimitsHeader/formatPlanWindowLine
    // verbatim (usage-format.ts) so the surfaces can't drift. Harness-generic
    // by design: a future Codex-style adapter arrives as its own PlanLimits
    // entry and renders with zero client changes.

    /** "plan limits — claude-code (max plan)". */
    fun planLimitsHeader(p: PlanLimits): String =
        "plan limits — ${p.harness}" + (p.subscriptionType?.let { " ($it plan)" } ?: "")

    /** "5-hour: 34% used", or "5-hour: —" when the harness can't say. */
    fun planWindowLine(w: PlanLimitWindow): String =
        w.utilization?.let { "${w.label}: ${Math.round(it)}% used" } ?: "${w.label}: —"

    /** Bar fill fraction (0..1, clamped); null utilization renders empty. */
    fun planWindowFraction(w: PlanLimitWindow): Float =
        ((w.utilization ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()

    /** "resets in 2d 3h" / "resets in 2h 15m" / "resets in 40m" relative to
     *  [nowMs]; "resets soon" once past. Null (no timestamp, unparseable)
     *  keeps the row clean. */
    fun resetsInText(resetsAtIso: String?, nowMs: Long): String? {
        if (resetsAtIso == null) return null
        val at = try {
            java.time.OffsetDateTime.parse(resetsAtIso).toInstant().toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            return null
        }
        val mins = (at - nowMs) / 60_000
        if (mins <= 0) return "resets soon"
        val d = mins / (60 * 24)
        val h = (mins / 60) % 24
        val m = mins % 60
        return when {
            d > 0 -> "resets in ${d}d ${h}h"
            h > 0 -> "resets in ${h}h ${m}m"
            else -> "resets in ${m}m"
        }
    }
}
