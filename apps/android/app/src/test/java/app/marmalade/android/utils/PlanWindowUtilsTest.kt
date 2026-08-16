package app.marmalade.android.utils

import app.marmalade.android.rpc.types.PlanLimitWindow
import app.marmalade.android.rpc.types.PlanLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which plan windows the session Overview panel shows (ADR 0013: the 5-hour
 * window and the weekly one). The daemon reports windows harness-generically,
 * so the picker has to survive ids it has never seen.
 */
class PlanWindowUtilsTest {

    private fun window(id: String, label: String = id, util: Double? = 10.0) =
        PlanLimitWindow(id = id, label = label, utilization = util)

    private fun limits(vararg windows: PlanLimitWindow) =
        listOf(PlanLimits(harness = "claude-code", windows = windows.toList()))

    @Test
    fun `picks the five-hour and weekly windows, five-hour first`() {
        val picked = PlanWindowUtils.forOverview(
            limits(
                window("seven_day", "Weekly"),
                window("model:Fable", "Weekly (Fable)"),
                window("five_hour", "5-hour"),
            ),
        )
        assertEquals(listOf("five_hour", "seven_day"), picked.map { it.id })
    }

    @Test
    fun `id matching tolerates separators and casing`() {
        val picked = PlanWindowUtils.forOverview(limits(window("FIVE-HOUR"), window("7d")))
        assertEquals(2, picked.size)
    }

    @Test
    fun `an unrecognised harness still gets bars, minus per-model windows`() {
        // A future adapter naming its windows differently must not render an
        // empty card — but per-model windows would crowd out everything else.
        val picked = PlanWindowUtils.forOverview(
            limits(
                window("model:a"),
                window("burst"),
                window("sustained"),
                window("model:b"),
            ),
        )
        assertEquals(listOf("burst", "sustained"), picked.map { it.id })
    }

    @Test
    fun `never shows more than two windows in the fallback`() {
        val many = (1..6).map { window("w$it") }
        assertTrue(PlanWindowUtils.forOverview(limits(*many.toTypedArray())).size <= PlanWindowUtils.MAX_WINDOWS)
    }

    @Test
    fun `no plan limits means no bars`() {
        assertTrue(PlanWindowUtils.forOverview(emptyList()).isEmpty())
        assertTrue(PlanWindowUtils.forOverview(limits()).isEmpty())
    }

    @Test
    fun `windows from several harnesses are considered together`() {
        val picked = PlanWindowUtils.forOverview(
            listOf(
                PlanLimits(harness = "codex", windows = listOf(window("five_hour"))),
                PlanLimits(harness = "claude-code", windows = listOf(window("seven_day"))),
            ),
        )
        assertEquals(listOf("five_hour", "seven_day"), picked.map { it.id })
    }
}
