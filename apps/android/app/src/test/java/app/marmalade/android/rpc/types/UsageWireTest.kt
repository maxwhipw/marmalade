package app.marmalade.android.rpc.types

import app.marmalade.android.rpc.JsonRpcClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wire-conformance twin for usage.summary (T2 #8): decodes daemon-shaped JSON
 * mirroring marmalade/packages/protocol/src/methods.ts UsageSummaryResult.
 */
class UsageWireTest {

    private val json = JsonRpcClient.DefaultJson

    @Test fun decodesDaemonSummaryShape() {
        val wire = """
            {"today":"2026-07-18","entries":[
              {"day":"2026-07-18","purpose":"main","cost_usd":0.46894,
               "input_tokens":46885,"output_tokens":4,"turns":1},
              {"day":"2026-07-17","purpose":"cron","cost_usd":0,
               "input_tokens":100,"output_tokens":20,"turns":2}
            ]}
        """.trimIndent()
        val res = json.decodeFromString(UsageSummaryResponse.serializer(), wire)
        assertEquals("2026-07-18", res.today)
        assertEquals(2, res.entries.size)
        val main = res.entries.first()
        assertEquals("main", main.purpose)
        assertEquals(46885L, main.inputTokens)
        assertEquals(4L, main.outputTokens)
        assertEquals(1, main.turns)
        assertEquals(0.46894, main.costUsd, 1e-9)
    }

    @Test fun unknownPurposeStaysOpaque() {
        // purpose is a String, not an enum — a new daemon purpose must decode.
        val wire = """{"today":"2026-07-18","entries":[
            {"day":"2026-07-18","purpose":"voice-ambient","cost_usd":0,
             "input_tokens":1,"output_tokens":1,"turns":1}]}"""
        val res = json.decodeFromString(UsageSummaryResponse.serializer(), wire)
        assertEquals("voice-ambient", res.entries.single().purpose)
    }

    @Test fun budgetAbsentDecodesToNull() {
        // The daemon omits `budget` when none is configured (methods.ts: the
        // field is nullable). Absent must decode to null, not fail.
        val wire = """{"today":"2026-07-18","entries":[]}"""
        val res = json.decodeFromString(UsageSummaryResponse.serializer(), wire)
        assertEquals(null, res.budget)
    }

    @Test fun budgetExplicitNullDecodesToNull() {
        // The daemon actually SENDS explicit `budget: null` (zod .nullable(),
        // router.ts `b ? {…} : null`) — decode it, don't just tolerate absence.
        val wire = """{"today":"2026-07-18","entries":[],"budget":null}"""
        val res = json.decodeFromString(UsageSummaryResponse.serializer(), wire)
        assertEquals(null, res.budget)
    }

    @Test fun budgetOverStateDecodes() {
        val wire = """{"today":"2026-07-18","entries":[],
            "budget":{"metric":"usd","daily_limit":20,"today_total":24.5,"over":true}}"""
        val res = json.decodeFromString(UsageSummaryResponse.serializer(), wire)
        val b = res.budget!!
        assertEquals("usd", b.metric)
        assertEquals(20.0, b.daily_limit, 1e-9)
        assertEquals(24.5, b.today_total, 1e-9)
        assertEquals(true, b.over)
    }
}
