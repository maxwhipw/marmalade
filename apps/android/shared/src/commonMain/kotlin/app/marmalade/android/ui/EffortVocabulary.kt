package app.marmalade.android.ui

import app.marmalade.android.rpc.types.EFFORT_LEVELS

/**
 * How reasoning-effort levels are NAMED in the UI — one definition for every
 * selector in the app (the chat composer's Thinking sheet and the Models
 * settings screen; ChatSettingsSheet deliberately carries no effort control).
 *
 * The LEVELS themselves are the daemon's (`model.list` `efforts`), never this
 * file's: it only prettifies the ones we know about and passes anything else
 * through with its raw id, so a daemon that grows a level renders it instead
 * of dropping it. That direction matters — before 2026-07-25 the client owned
 * the list and drifted into offering `none` and `minimal`, which the daemon
 * rejects outright, while hiding the real `max` behind an "xhigh" mislabelled
 * as "Max".
 */
fun effortLabel(effort: String): String = when (effort) {
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "xhigh" -> "Very high"
    "max" -> "Max"
    else -> effort
}

/** One-line description for a level; empty for one we don't know. */
fun effortDescription(effort: String): String = when (effort) {
    "low" -> "Light reasoning — fastest replies."
    "medium" -> "Balanced."
    "high" -> "Deeper reasoning, slower."
    "xhigh" -> "Best for coding and agentic work."
    "max" -> "Highest effort — slowest and priciest."
    else -> ""
}

/** (level, label, description) rows for a selector. [levels] is the daemon's
 *  published vocabulary; empty falls back to the list this client shipped with
 *  (only reachable against a daemon predating `efforts`). */
fun effortOptions(levels: List<String>): List<Triple<String, String, String>> =
    levels.ifEmpty { EFFORT_LEVELS }.map { Triple(it, effortLabel(it), effortDescription(it)) }
