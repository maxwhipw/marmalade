package app.marmalade.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Per-model reasoning-effort bounds — the pure derivations in
 * `:shared` EffortBounds.kt (daemon feature, 2026-07-27).
 *
 * Two invariants carry the whole feature:
 *
 *  - **Bounds are positional, never named.** Everything compares indices in
 *    the daemon's published `efforts` vocabulary, so a daemon that grows a
 *    level needs no app release — and an edge naming a level this daemon
 *    doesn't publish degrades to "unbounded" instead of being trusted.
 *  - **Degrading is always toward the daemon's own clamp.** Every unknown /
 *    absent / contradictory input has to land on "offer everything"; the
 *    daemon clamps regardless, so a client that guesses wrong here only ever
 *    loses the grey-out, never correctness.
 */
class EffortBoundsTest {

    private val levels = listOf("low", "medium", "high", "xhigh", "max")

    // ── allowedEfforts ──────────────────────────────────────────────────────

    @Test
    fun `an unbounded model offers the whole vocabulary`() {
        assertEquals(levels, allowedEfforts(levels, null, null))
    }

    @Test
    fun `a min bound drops everything below it`() {
        assertEquals(listOf("high", "xhigh", "max"), allowedEfforts(levels, "high", null))
    }

    @Test
    fun `a max bound drops everything above it`() {
        assertEquals(listOf("low", "medium"), allowedEfforts(levels, null, "medium"))
    }

    @Test
    fun `both edges narrow to the closed range`() {
        assertEquals(listOf("medium", "high"), allowedEfforts(levels, "medium", "high"))
    }

    @Test
    fun `a bound naming a level this daemon does not publish is ignored`() {
        // The client must never invent a position for a level it can't place —
        // "unbounded" is the safe direction (the daemon still clamps).
        assertEquals(levels, allowedEfforts(levels, "minimal", "ultra"))
    }

    @Test
    fun `a contradictory pair degrades to the full list rather than an empty sheet`() {
        // settings.update rejects min > max, but a hand-edited config.json can
        // still produce one — an unpickable sheet would be the worse failure.
        assertEquals(levels, allowedEfforts(levels, "max", "low"))
    }

    // ── snapEffortToBounds ──────────────────────────────────────────────────

    @Test
    fun `an in-range level is left alone`() {
        assertEquals("high", snapEffortToBounds("high", levels, "medium", "max"))
    }

    @Test
    fun `a level below the minimum snaps up to the minimum`() {
        assertEquals("high", snapEffortToBounds("low", levels, "high", null))
    }

    @Test
    fun `a level above the maximum snaps down to the maximum`() {
        assertEquals("medium", snapEffortToBounds("max", levels, null, "medium"))
    }

    @Test
    fun `snapping picks the NEAR edge, not the first one`() {
        // The range is contiguous, so "nearest allowed" is always the bound
        // that bit — a naive `allowed.first()` would send "max" down to "medium".
        assertEquals("high", snapEffortToBounds("max", levels, "medium", "high"))
        assertEquals("medium", snapEffortToBounds("low", levels, "medium", "high"))
    }

    @Test
    fun `an unbounded model never snaps`() {
        levels.forEach { assertEquals(it, snapEffortToBounds(it, levels, null, null)) }
    }

    @Test
    fun `a level outside the vocabulary is passed through untouched`() {
        // Legacy Room rows hold "off", a level marmaladed never accepted. The
        // send path already filters it; inventing a snap here would only make
        // up a value nobody asked for.
        assertEquals("off", snapEffortToBounds("off", levels, "high", null))
    }

    @Test
    fun `an empty vocabulary cannot snap`() {
        assertEquals("high", snapEffortToBounds("high", emptyList(), "xhigh", null))
    }

    // ── captions ────────────────────────────────────────────────────────────

    @Test
    fun `an allowed level has no bound caption`() {
        assertNull(effortBoundCaption("high", levels, "medium", "max", "Opus 5"))
    }

    @Test
    fun `a too-shallow level names the model minimum`() {
        assertEquals(
            "Below Opus 5 minimum",
            effortBoundCaption("low", levels, "high", null, "Opus 5"),
        )
    }

    @Test
    fun `a too-deep level names the model limit`() {
        assertEquals(
            "Above Fable 5 limit",
            effortBoundCaption("max", levels, null, "medium", "Fable 5"),
        )
    }

    // ── the effort.clamped transcript line ──────────────────────────────────

    @Test
    fun `a min clamp reads as a minimum, in the model's own name`() {
        assertEquals(
            "Thinking adjusted to High — Opus 5 minimum",
            effortClampedLine(effective = "high", bound = "min", modelLabel = "Opus 5"),
        )
    }

    @Test
    fun `a max clamp reads as a limit`() {
        assertEquals(
            "Thinking adjusted to Medium — Fable 5 limit",
            effortClampedLine(effective = "medium", bound = "max", modelLabel = "Fable 5"),
        )
    }

    @Test
    fun `the line uses the friendly effort label, never the wire id`() {
        // "xhigh" is exactly the kind of id the maintainer must never be shown.
        assertEquals(
            "Thinking adjusted to Very high — Opus 5 minimum",
            effortClampedLine(effective = "xhigh", bound = "min", modelLabel = "Opus 5"),
        )
    }

    @Test
    fun `an unknown model label still produces a readable line`() {
        // The catalog may not have loaded when a replayed clamp lands; the raw
        // model id is ugly but never a lie.
        assertEquals(
            "Thinking adjusted to Low — claude-fable-5 limit",
            effortClampedLine(effective = "low", bound = "max", modelLabel = "claude-fable-5"),
        )
    }
}
