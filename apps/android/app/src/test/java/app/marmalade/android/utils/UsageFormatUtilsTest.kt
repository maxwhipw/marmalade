package app.marmalade.android.utils

import app.marmalade.android.rpc.types.PlanLimitWindow
import app.marmalade.android.rpc.types.PlanLimits
import app.marmalade.android.rpc.types.UsageBudget
import app.marmalade.android.rpc.types.UsageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageFormatUtilsTest {

    private fun e(day: String, purpose: String, inTok: Long, outTok: Long, turns: Int = 1, usd: Double = 0.0) =
        UsageEntry(day = day, purpose = purpose, costUsd = usd, inputTokens = inTok, outputTokens = outTok, turns = turns)

    @Test fun fmtTokensTiers() {
        assertEquals("999", UsageFormatUtils.fmtTokens(999))
        assertEquals("46.9k", UsageFormatUtils.fmtTokens(46_885))
        assertEquals("2.5M", UsageFormatUtils.fmtTokens(2_500_000))
    }

    @Test fun rollupGroupsPerDayAscendingWithTotals() {
        val days = UsageFormatUtils.rollupByDay(
            listOf(
                e("2026-07-18", "main", 100, 10, turns = 2, usd = 0.5),
                e("2026-07-17", "main", 50, 5),
                e("2026-07-18", "cron", 30, 3),
            ),
        )
        assertEquals(listOf("2026-07-17", "2026-07-18"), days.map { it.day })
        val d18 = days[1]
        assertEquals(3, d18.turns)
        assertEquals(130L, d18.inputTokens)
        assertEquals(13L, d18.outputTokens)
        assertEquals(0.5, d18.costUsd, 1e-9)
        assertEquals(2, d18.entries.size)
    }

    @Test fun barFractionRelativeToBusiestDay() {
        val days = UsageFormatUtils.rollupByDay(
            listOf(e("2026-07-17", "main", 100, 0), e("2026-07-18", "main", 25, 0)),
        )
        assertEquals(1f, UsageFormatUtils.dayBarFraction(days[0], days))
        assertEquals(0.25f, UsageFormatUtils.dayBarFraction(days[1], days))
        assertEquals(0f, UsageFormatUtils.dayBarFraction(days[0], emptyList()))
    }

    @Test fun summaryLineHidesZeroCostAndSingularizesTurn() {
        assertEquals("1 turn · 46.9k in / 4 out", UsageFormatUtils.summaryLine(1, 46_885, 4, 0.0))
        assertEquals(
            "3 turns · 1.5k in / 100 out · $0.47 notional",
            UsageFormatUtils.summaryLine(3, 1_500, 100, 0.46894),
        )
    }

    private fun budget(metric: String = "usd", limit: Double = 20.0, total: Double = 5.0, over: Boolean = false) =
        UsageBudget(metric = metric, daily_limit = limit, today_total = total, over = over)

    @Test fun budgetLineMatchesCliWording() {
        assertEquals("budget: \$5.00 of \$20.00/day (25%)", UsageFormatUtils.formatBudgetLine(budget()))
    }

    @Test fun budgetLineOverIsLoudAndNamesScheduledPrompts() {
        val line = UsageFormatUtils.formatBudgetLine(budget(total = 24.0, over = true))
        assert(line.contains("OVER"))
        assert(line.contains("scheduled prompts are paused"))
    }

    @Test fun budgetLineTokensMetricHumanizes() {
        assertEquals(
            "budget: 1.5M of 2.0M/day (75%)",
            UsageFormatUtils.formatBudgetLine(budget(metric = "tokens", limit = 2_000_000.0, total = 1_500_000.0)),
        )
    }

    @Test fun budgetFractionClampsAndHandlesNoLimit() {
        assertEquals(1f, UsageFormatUtils.budgetFraction(budget(total = 30.0, limit = 20.0)))
        assertEquals(0.25f, UsageFormatUtils.budgetFraction(budget(total = 5.0, limit = 20.0)))
        assertEquals(0f, UsageFormatUtils.budgetFraction(budget(limit = 0.0)))
    }

    // ── Plan limits (usage.summary.plan_limits) — pins the exact wording of
    // the protocol's formatPlanLimitsHeader/formatPlanWindowLine (usage-
    // format.ts) so this hand-mirror can't drift from the CLI/webui.

    private fun window(utilization: Double? = 34.0, resetsAt: String? = null) =
        PlanLimitWindow(id = "five_hour", label = "5-hour", utilization = utilization, resetsAt = resetsAt)

    @Test fun planHeaderNamesHarnessAndTier() {
        assertEquals(
            "plan limits — claude-code (max plan)",
            UsageFormatUtils.planLimitsHeader(PlanLimits(harness = "claude-code", subscriptionType = "max")),
        )
        assertEquals(
            "plan limits — claude-code",
            UsageFormatUtils.planLimitsHeader(PlanLimits(harness = "claude-code")),
        )
    }

    @Test fun planWindowLineRoundsAndDashesUnknown() {
        assertEquals("5-hour: 34% used", UsageFormatUtils.planWindowLine(window(utilization = 33.6)))
        assertEquals("5-hour: —", UsageFormatUtils.planWindowLine(window(utilization = null)))
    }

    @Test fun planWindowFractionClampsAndTreatsNullAsEmpty() {
        assertEquals(0.34f, UsageFormatUtils.planWindowFraction(window(utilization = 34.0)))
        assertEquals(1f, UsageFormatUtils.planWindowFraction(window(utilization = 250.0)))
        assertEquals(0f, UsageFormatUtils.planWindowFraction(window(utilization = null)))
    }

    @Test fun resetsInTextTiersAndEdges() {
        val now = java.time.Instant.parse("2026-07-18T12:00:00Z").toEpochMilli()
        assertEquals("resets in 40m", UsageFormatUtils.resetsInText("2026-07-18T12:40:30Z", now))
        assertEquals("resets in 2h 15m", UsageFormatUtils.resetsInText("2026-07-18T14:15:00Z", now))
        assertEquals("resets in 2d 3h", UsageFormatUtils.resetsInText("2026-07-20T15:30:00Z", now))
        assertEquals("resets soon", UsageFormatUtils.resetsInText("2026-07-18T11:59:00Z", now))
        // Offset (non-Zulu) ISO forms parse too; junk and absence stay null.
        assertEquals("resets in 40m", UsageFormatUtils.resetsInText("2026-07-18T14:40:00+02:00", now))
        assertNull(UsageFormatUtils.resetsInText("not-a-date", now))
        assertNull(UsageFormatUtils.resetsInText(null, now))
    }

    @Test fun formattingIsLocaleIndependent() {
        // A comma-decimal device locale must NOT render "$5,00" — that would
        // desync from the CLI/webui (toFixed). Pin to en-US-style dots.
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("budget: \$5.00 of \$20.00/day (25%)", UsageFormatUtils.formatBudgetLine(budget()))
            assertEquals("2.5M", UsageFormatUtils.fmtTokens(2_500_000))
            assertEquals(
                "1 turn · 1.5k in / 4 out · $0.47 notional",
                UsageFormatUtils.summaryLine(1, 1_500, 4, 0.46894),
            )
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
