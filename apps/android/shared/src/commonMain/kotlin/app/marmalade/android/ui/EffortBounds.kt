package app.marmalade.android.ui

/**
 * Per-model reasoning-effort BOUNDS (daemon feature, 2026-07-27) — the pure
 * derivations every surface shares: the Models settings bounds editor, the
 * composer's Thinking sheet, and the `effort.clamped` transcript line.
 *
 * The daemon is the authority. It clamps a requested effort into the selected
 * model's `[effort_min, effort_max]` at session.create / session.effort and
 * returns the clamped value, so a client that ignores bounds still shows the
 * truth. Everything here is about not OFFERING a level the daemon would
 * silently move — and about naming the move in words a person recognises when
 * it happens anyway.
 *
 * The vocabulary is always the daemon's published `efforts` list, passed in as
 * [levels] (cheapest → deepest). Nothing in this file knows the level names;
 * bounds are compared by POSITION in that list, so a daemon that grows a level
 * needs no app release. A bound naming a level the vocabulary doesn't carry is
 * ignored rather than trusted — an unknown edge can't be positioned, and
 * dropping it degrades to "unbounded", which is the safe direction (the daemon
 * still clamps).
 */

/** The levels a model may actually run at: [levels] narrowed to the closed
 *  range `[min, max]`. Unknown / absent edges don't narrow. Never empty — a
 *  contradictory pair (min above max, which the daemon rejects on write but
 *  which a hand-edited config could still produce) degrades to the full list
 *  rather than an unpickable empty sheet. */
fun allowedEfforts(levels: List<String>, min: String?, max: String?): List<String> {
    if (levels.isEmpty()) return levels
    val lo = levels.indexOf(min).takeIf { it >= 0 } ?: 0
    val hi = levels.indexOf(max).takeIf { it >= 0 } ?: levels.lastIndex
    if (lo > hi) return levels
    return levels.subList(lo, hi + 1)
}

/**
 * Move [effort] into the model's allowed range, picking the NEAREST allowed
 * level — i.e. the bound that bit, since the range is contiguous. Returns
 * [effort] unchanged when it's already allowed (or when nothing here can
 * position it: an empty vocabulary, or a level the daemon doesn't publish,
 * which the send path already filters).
 *
 * This is what a model switch runs: the daemon does NOT re-clamp a session's
 * stored effort on `session.model` (router.ts only sets the model), so the
 * client owes it the snapped value or the session keeps running at a level the
 * new model's bounds forbid.
 */
fun snapEffortToBounds(effort: String, levels: List<String>, min: String?, max: String?): String {
    val allowed = allowedEfforts(levels, min, max)
    if (allowed.isEmpty() || effort in allowed) return effort
    val current = levels.indexOf(effort)
    if (current < 0) return effort
    val loIdx = levels.indexOf(allowed.first())
    val hiIdx = levels.indexOf(allowed.last())
    return if (current < loIdx) allowed.first() else allowed.last()
}

/** Which edge excludes [effort] for a model bounded by [min]/[max], or null
 *  when it's allowed. `"min"` = the pick is too shallow, `"max"` = too deep —
 *  the same vocabulary the daemon's `effort.clamped` payload uses. */
fun effortBoundViolated(effort: String, levels: List<String>, min: String?, max: String?): String? {
    val allowed = allowedEfforts(levels, min, max)
    if (allowed.isEmpty() || effort in allowed) return null
    val current = levels.indexOf(effort)
    if (current < 0) return null
    return if (current < levels.indexOf(allowed.first())) "min" else "max"
}

/**
 * The short caption under a disabled level in the Thinking sheet — why this
 * model can't run at it. Null when the level is allowed.
 *
 * Deliberately names the MODEL, not the config key: "Below Opus 5 minimum"
 * tells you the fix (pick another model, or raise the bound in Settings →
 * Models); "effort_min = high" tells you nothing you didn't just see.
 */
fun effortBoundCaption(
    effort: String,
    levels: List<String>,
    min: String?,
    max: String?,
    modelLabel: String,
): String? = when (effortBoundViolated(effort, levels, min, max)) {
    "min" -> "Below $modelLabel minimum"
    "max" -> "Above $modelLabel limit"
    else -> null
}

/**
 * The transcript line for a durable `effort.clamped` event (design-lab option
 * E3, maintainer 2026-07-27): "Thinking adjusted to High — Opus 5 minimum".
 *
 * Wording rules, from the sign-off: user-friendly, never technical. It names
 * the level the turn ACTUALLY runs at (the daemon's `effective`) and the model
 * whose bound moved it — not the raw effort ids, not "clamped", not the config
 * key. `bound = "min"` reads "minimum" (the ask was too shallow), `"max"` reads
 * "limit" (too deep).
 *
 * [modelLabel] is the model.list display label when the catalog is loaded, else
 * the raw id — an id is ugly but never wrong, and the line is durable so it must
 * be writable the instant the event lands.
 */
fun effortClampedLine(effective: String, bound: String, modelLabel: String): String {
    val edge = if (bound == "min") "minimum" else "limit"
    return "Thinking adjusted to ${effortLabel(effective)} — $modelLabel $edge"
}
