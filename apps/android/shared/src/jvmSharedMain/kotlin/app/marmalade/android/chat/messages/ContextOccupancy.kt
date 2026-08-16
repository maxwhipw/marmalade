package app.marmalade.android.chat.messages

import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure helpers behind the composer's context donut. Two sources feed the same
 * number and they must agree:
 *
 *  1. COLD SEED — the bound session's `session.list` row carries the daemon's
 *     persisted occupancy (`context_used`/`context_max`, additive 2026-07-25),
 *     mirrored into `sessions.contextUsed`/`contextMax` by SessionListSync.
 *     That is what makes a session opened WITHOUT running a turn show a donut
 *     at all.
 *  2. LIVE — every `message.complete` rides a `usage` block with the same
 *     snake_case keys (daemon normalize.ts::wireUsage).
 *
 * Precedence: the live value wins once the bound session has produced one;
 * before that the row seed shows. Unknown on both sides → null → NO donut. We
 * never fabricate a percentage: a harness that reports no window and a daemon
 * predating the fields both read as unknown, and that is the honest answer.
 *
 * Mirrors the webui's `components/context.ts` deliberately — same precedence,
 * same formula, so a cold-open number and the next turn's live number agree.
 */
data class ContextOccupancy(
    val used: Long,
    val max: Long,
    val percent: Int,
)

/**
 * Build a displayable reading from the two halves. BOTH are required — a
 * percentage is underivable without the window, and the donut has nothing to
 * draw without a percentage, so a partial reading is simply "unknown" (null).
 *
 * The percentage is RECOMPUTED here rather than taken from any carried
 * `context_percent`: the daemon derives it at read with this exact formula
 * (router.ts::contextPercent, normalize.ts::wireUsage), and one formula with
 * one home is what keeps cold and live from drifting.
 */
fun contextOccupancy(used: Long?, max: Long?): ContextOccupancy? {
    if (used == null || used <= 0L) return null
    if (max == null || max <= 0L) return null
    val percent = min(100L, (used.toDouble() / max * 100).roundToLong()).toInt()
    return ContextOccupancy(used, max, percent)
}

/** The reading a usage snapshot currently holds, or null when it has none. */
fun MessageStream.UsageDelta?.contextReading(): ContextOccupancy? =
    contextOccupancy(this?.contextUsed, this?.contextMax)

/**
 * Fill in [current]'s context reading from the bound session's row [seed] when
 * it has none yet — the cold-open gap before the first live turn. A live
 * reading is never overwritten, and re-binding re-reads the NEW session's own
 * row, so switching sessions reseeds by way of this same rule rather than
 * needing a step of its own.
 */
fun seedContext(
    current: MessageStream.UsageDelta?,
    seed: ContextOccupancy?,
): MessageStream.UsageDelta? {
    if (seed == null) return current
    if (current.contextReading() != null) return current
    return (current ?: MessageStream.UsageDelta()).copy(
        contextUsed = seed.used,
        contextMax = seed.max,
        contextPercent = seed.percent,
    )
}

/**
 * Drop the context reading, keeping the token tallies — what `session.cleared`
 * means for the donut. Mirrors the daemon nulling the two columns on
 * `session.clear`: the window is empty again, so the pre-clear number would be
 * a lie, and unknown is the honest state until the next turn.
 */
fun MessageStream.UsageDelta.withoutContext(): MessageStream.UsageDelta =
    copy(contextUsed = null, contextMax = null, contextPercent = null)
