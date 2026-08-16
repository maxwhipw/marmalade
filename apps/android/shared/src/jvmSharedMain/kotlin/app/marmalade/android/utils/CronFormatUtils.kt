package app.marmalade.android.utils

import app.marmalade.android.rpc.types.CronJob
import app.marmalade.android.rpc.types.CronSchedule
import java.text.DateFormat
import java.util.Date

/**
 * Pure display/parse helpers for the scheduled-prompts screen. The reference
 * UX is the daemon repo's cron CLI (packages/cli/src/cron-cli.ts) — parity
 * with its formatting is the floor. Pure so plain JVM unit tests cover them.
 */
object CronFormatUtils {

    /** ms → compact duration ("90s", "15m", "2h", "1d"). */
    fun formatMs(ms: Long): String = when {
        ms % 86_400_000L == 0L -> "${ms / 86_400_000L}d"
        ms % 3_600_000L == 0L -> "${ms / 3_600_000L}h"
        ms % 60_000L == 0L -> "${ms / 60_000L}m"
        else -> "${(ms + 500) / 1000}s"
    }

    /** Coarse human countdown ("in 3h 20m", "in 45s", "now"). */
    fun formatCountdown(deltaMs: Long): String {
        if (deltaMs <= 0) return "now"
        val s = (deltaMs + 500) / 1000
        if (s < 60) return "in ${s}s"
        val m = s / 60
        if (m < 60) return "in ${m}m"
        val h = m / 60
        if (h < 24) return "in ${h}h ${m % 60}m"
        return "in ${h / 24}d ${h % 24}h"
    }

    fun describeSchedule(s: CronSchedule): String = when (s.kind) {
        "cron" -> "cron \"${s.expr}\"" + (s.tz?.let { " ($it)" } ?: "")
        "every" -> "every ${formatMs(s.everyMs ?: 0)}"
        "at" -> "once at ${formatTime(s.atMs ?: 0)}"
        // Unknown kinds render opaquely instead of crashing (forward compat).
        else -> s.kind
    }

    /**
     * The state line: enabled → next-run countdown; disabled → why. A job the
     * scheduler disabled records the reason in lastError — surfacing it is
     * the point of listing disabled jobs at all. A self-disabled one-shot
     * that fired ok reads as "done", not broken.
     */
    fun stateLabel(job: CronJob, nowMs: Long): String {
        if (!job.enabled) {
            if (job.schedule.kind == "at" && job.lastStatus == "ok") return "done (one-shot fired)"
            val reason = job.lastError
            return if (reason != null && job.lastStatus != "error") "disabled — $reason" else "disabled"
        }
        val next = job.nextRunAt ?: return "never (unarmed)"
        return "next ${formatCountdown(next - nowMs)} · ${formatTime(next)}"
    }

    fun lastRunLabel(job: CronJob): String {
        val at = job.lastRunAt ?: return "never ran"
        val when_ = formatTime(at)
        return if (job.lastStatus == "error") {
            "last run failed at $when_" + (job.lastError?.let { " — $it" } ?: "")
        } else {
            "last ran ok at $when_"
        }
    }

    /**
     * "30s" | "15m" | "2h" | "1d" → ms; bare numbers are MINUTES (a duration
     * field defaulting to seconds invites accidental hammering). Null = bad
     * input or under the daemon's 1s floor.
     */
    fun parseDuration(raw: String): Long? {
        val m = Regex("^(\\d+(?:\\.\\d+)?)\\s*([smhd])?$").find(raw.trim()) ?: return null
        val unit = when (m.groupValues[2]) {
            "s" -> 1000L
            "m", "" -> 60_000L
            "h" -> 3_600_000L
            "d" -> 86_400_000L
            else -> return null
        }
        val ms = (m.groupValues[1].toDouble() * unit).toLong()
        return if (ms < 1000L) null else ms
    }

    private fun formatTime(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
}
