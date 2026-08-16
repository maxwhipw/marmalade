package app.marmalade.android.speech.wake

/**
 * Multi-frame confirmation + cooldown gate for wake-word classifier scores.
 *
 * The retired rementia AAR fired on a single 80ms hop crossing its threshold
 * (bytecode-confirmed by an audit kept internally) — a lone loud
 * consonant or a TV ad jingle could trip it. This tracker requires **2 of the
 * last 3 hops** to exceed a model's threshold before it counts as a real
 * detection, then applies a cooldown so one utterance can't re-fire on its
 * own decaying tail.
 *
 * One instance tracks all configured models together so SINGLE_BEST
 * tie-breaking (highest score wins when multiple models confirm in the same
 * hop) has visibility across all of them. Pure logic — no coroutines, no
 * Android, no ONNX — so it is fully unit-testable with synthetic score
 * sequences.
 */
class ConfirmationTracker(
    private val models: List<WakeModel>,
    private val cooldownMs: Long,
    private val historySize: Int = HOP_HISTORY_SIZE,
    private val requiredHits: Int = REQUIRED_HITS,
) {
    companion object {
        const val HOP_HISTORY_SIZE = 3
        const val REQUIRED_HITS = 2
    }

    // Per-model ring of the last [historySize] pass/fail booleans (threshold crossings).
    private val history: MutableMap<String, ArrayDeque<Boolean>> =
        models.associate { it.assetFilename to ArrayDeque<Boolean>(historySize) }.toMutableMap()

    private var lastDetectionTimeMs: Long = 0L

    /**
     * Feed one hop's worth of per-model scores (keyed by [WakeModel.assetFilename]).
     * Returns the confirmed [WakeDetection] for this hop, or null if nothing
     * confirmed (either no model reached 2-of-3, or cooldown suppressed it).
     *
     * @param scores model asset filename -> raw sigmoid score for this hop.
     * @param nowMs caller-supplied wall-clock reading in ms (testability;
     *   this file is strict commonMain, so the clock source is the caller's).
     */
    fun offer(scores: Map<String, Float>, nowMs: Long): WakeDetection? {
        var best: WakeDetection? = null

        for (model in models) {
            val score = scores[model.assetFilename] ?: continue
            val ring = history.getOrPut(model.assetFilename) { ArrayDeque(historySize) }
            ring.addLast(score > model.threshold)
            while (ring.size > historySize) ring.removeFirst()

            val hits = ring.count { it }
            if (hits >= requiredHits) {
                if (best == null || score > best.score) {
                    best = WakeDetection(model.displayName, score)
                }
            }
        }

        if (best == null) return null

        if (!shouldTrigger(nowMs)) return null

        lastDetectionTimeMs = nowMs
        return best
    }

    private fun shouldTrigger(nowMs: Long): Boolean {
        if (lastDetectionTimeMs == 0L) return true
        return (nowMs - lastDetectionTimeMs) >= cooldownMs
    }

    /** Clears score history and cooldown state (e.g. after a VAD hangover reset). */
    fun reset() {
        history.values.forEach { it.clear() }
        lastDetectionTimeMs = 0L
    }
}
