package app.marmalade.android.chat.messages

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Digital twin of the daemon's context-occupancy arithmetic and the
 * seed-vs-live precedence behind the composer donut. The Kotlin counterpart of
 * marmalade/packages/webui/test/context.test.ts — same formula, same "unknown
 * stays unknown" rule, so a cold-open number and the next live turn's number
 * can't disagree.
 */
class ContextOccupancyTest {

    // ── the formula ─────────────────────────────────────────────────────────

    @Test fun `percent is recomputed from used over max`() {
        val c = contextOccupancy(32_900L, 200_000L)
        assertEquals(16, c?.percent)
        assertEquals(32_900L, c?.used)
        assertEquals(200_000L, c?.max)
    }

    @Test fun `percent rounds, matching the daemon's Math_round`() {
        // 4.0542% → 4; 48.0% → 48; 0.5% → 1 (round-half-up, as JS Math.round).
        assertEquals(4, contextOccupancy(40_542L, 1_000_000L)?.percent)
        assertEquals(48, contextOccupancy(61_440L, 128_000L)?.percent)
        assertEquals(1, contextOccupancy(5L, 1_000L)?.percent)
    }

    @Test fun `percent clamps at 100 when the window is overrun`() {
        assertEquals(100, contextOccupancy(250_000L, 200_000L)?.percent)
    }

    // ── unknown stays unknown ───────────────────────────────────────────────

    @Test fun `both halves are required`() {
        assertNull("used without a window is unknown", contextOccupancy(9_000L, null))
        assertNull("a window without a reading is unknown", contextOccupancy(null, 200_000L))
        assertNull(contextOccupancy(null, null))
    }

    @Test fun `non-positive halves are unknown, never a zero donut`() {
        assertNull(contextOccupancy(0L, 200_000L))
        assertNull(contextOccupancy(32_900L, 0L))
        assertNull(contextOccupancy(-1L, 200_000L))
    }

    // ── the live wire block ─────────────────────────────────────────────────

    @Test fun `extractUsage recomputes the percent rather than trusting the block`() {
        // A daemon (or a future harness) that disagreed with itself must not put
        // a second number on screen — used/max is the source, one formula.
        val usage = MessageStream.extractUsage(
            payloadWithUsage {
                put("context_used", JsonPrimitive(61_440L))
                put("context_max", JsonPrimitive(128_000L))
                put("context_percent", JsonPrimitive(99L))
            },
        )
        assertEquals(48, usage?.contextPercent)
    }

    @Test fun `extractUsage drops a half-reported occupancy`() {
        val usage = MessageStream.extractUsage(
            payloadWithUsage {
                put("output_tokens", JsonPrimitive(120L))
                put("context_used", JsonPrimitive(9_000L))
            },
        )
        assertEquals("the turn's tallies still land", 120L, usage?.outputTokens)
        assertNull("used without a window is not a donut", usage?.contextPercent)
        assertNull(usage?.contextUsed)
        assertNull(usage?.contextMax)
    }

    // ── seed vs live precedence ─────────────────────────────────────────────

    @Test fun `the row seed fills an empty reading`() {
        val seeded = seedContext(null, contextOccupancy(32_900L, 200_000L))
        assertEquals(16, seeded?.contextPercent)
        assertEquals(32_900L, seeded?.contextUsed)
        assertEquals(200_000L, seeded?.contextMax)
    }

    @Test fun `the row seed keeps the tallies it lands on`() {
        val cached = MessageStream.UsageDelta(inputTokens = 10L, outputTokens = 20L, costUsd = 0.5)
        val seeded = seedContext(cached, contextOccupancy(32_900L, 200_000L))
        assertEquals(10L, seeded?.inputTokens)
        assertEquals(0.5, seeded?.costUsd)
        assertEquals(16, seeded?.contextPercent)
    }

    @Test fun `a live reading is never overwritten by the seed`() {
        val live = MessageStream.UsageDelta(contextUsed = 100_000L, contextMax = 200_000L, contextPercent = 50)
        val seeded = seedContext(live, contextOccupancy(32_900L, 200_000L))
        assertSame("live wins once seen — the seed only fills the gap", live, seeded)
    }

    @Test fun `an unknown seed changes nothing`() {
        val live = MessageStream.UsageDelta(outputTokens = 7L)
        assertSame(live, seedContext(live, null))
        assertNull(seedContext(null, null))
    }

    @Test fun `withoutContext drops the reading and keeps the tallies`() {
        val usage = MessageStream.UsageDelta(
            inputTokens = 10L, outputTokens = 20L,
            contextUsed = 32_900L, contextMax = 200_000L, contextPercent = 16,
        )
        val cleared = usage.withoutContext()
        assertNull(cleared.contextUsed)
        assertNull(cleared.contextMax)
        assertNull(cleared.contextPercent)
        assertEquals(10L, cleared.inputTokens)
        assertNotNull(cleared.outputTokens)
    }

    private fun payloadWithUsage(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject { put("usage", buildJsonObject(build)) }
}
