package app.marmalade.android.utils

import app.marmalade.android.rpc.types.PlanLimits
import app.marmalade.android.rpc.types.PlanLimitWindow

/**
 * Which subscription rate-limit windows the session Overview panel shows.
 *
 * ADR 0013 calls for two bars — the 5-hour window and the weekly one — because
 * those are the two that actually decide whether you can keep working. The
 * daemon reports windows harness-generically (`plan_limits` is an array, and a
 * future non-Claude-Code adapter arrives as its own entry with its own ids), so
 * this picks by id where it can and degrades to "whatever the harness reported"
 * rather than hard-coding two ids and rendering nothing when they change.
 */
object PlanWindowUtils {

    /** Claude Code's ids today. Matched case-insensitively and loosely, so
     *  `five_hour`, `fiveHour` and `5h` all land on the same bar. */
    private val FIVE_HOUR = listOf("five_hour", "fivehour", "5h", "5_hour")
    private val WEEKLY = listOf("seven_day", "sevenday", "7d", "weekly", "week")

    /** How many windows the panel shows before it stops being a summary. */
    const val MAX_WINDOWS = 2

    private fun idMatches(id: String, candidates: List<String>): Boolean {
        val normalized = id.lowercase().replace("-", "_")
        return candidates.any { normalized == it || normalized.startsWith(it) }
    }

    /**
     * The windows to render, in display order: 5-hour first (it is the one you
     * hit mid-session), then weekly.
     *
     * Falls back to the harness's own order when neither id is recognised — a
     * new harness naming its windows differently still gets bars, just not
     * re-ordered. Per-model windows ("model:Fable") are deliberately excluded
     * from the fallback: they multiply with the model list and would push the
     * two windows that matter off the panel.
     */
    fun forOverview(planLimits: List<PlanLimits>): List<PlanLimitWindow> {
        val all = planLimits.flatMap { it.windows }
        if (all.isEmpty()) return emptyList()

        val fiveHour = all.firstOrNull { idMatches(it.id, FIVE_HOUR) }
        val weekly = all.firstOrNull { idMatches(it.id, WEEKLY) }
        val picked = listOfNotNull(fiveHour, weekly)
        if (picked.isNotEmpty()) return picked

        return all
            .filterNot { it.id.lowercase().startsWith("model:") }
            .take(MAX_WINDOWS)
    }
}
