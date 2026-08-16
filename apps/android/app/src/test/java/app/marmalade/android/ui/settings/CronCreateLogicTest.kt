package app.marmalade.android.ui.settings

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure create-dialog logic: buildSchedule validation + date/time combining. */
class CronCreateLogicTest {

    private val now = 1_800_000_000_000L

    @Test fun buildSchedule_every_anchorsAtNow() {
        val s = CronViewModel.buildSchedule("every", "", "", "15m", null, now).getOrThrow()
        assertEquals("every", s.kind)
        assertEquals(900_000L, s.everyMs)
        assertEquals(now, s.anchorMs)
    }

    @Test fun buildSchedule_cron_requiresExprAndOmitsBlankTz() {
        assertTrue(CronViewModel.buildSchedule("cron", " ", "", "", null, now).isFailure)
        val s = CronViewModel.buildSchedule("cron", "0 9 * * *", " ", "", null, now).getOrThrow()
        assertEquals("0 9 * * *", s.expr)
        assertEquals(null, s.tz)
    }

    @Test fun buildSchedule_at_rejectsMissingAndPast() {
        assertTrue(CronViewModel.buildSchedule("at", "", "", "", null, now).isFailure)
        assertTrue(CronViewModel.buildSchedule("at", "", "", "", now - 1000, now).isFailure)
        val s = CronViewModel.buildSchedule("at", "", "", "", now + 60_000, now).getOrThrow()
        assertEquals(now + 60_000, s.atMs)
    }

    @Test fun buildSchedule_badInterval_fails() {
        assertTrue(CronViewModel.buildSchedule("every", "", "", "soon", null, now).isFailure)
    }

    @Test fun combineDateAndTime_readsDateInUtcAndTimeInLocal() {
        // 2026-07-20 UTC midnight (what DatePicker hands back)…
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(2026, Calendar.JULY, 20, 0, 0, 0)
        // …combined with 09:30 must land on 2026-07-20 09:30 LOCAL time.
        val combined = combineDateAndTime(utc.timeInMillis, 9, 30)
        val local = Calendar.getInstance()
        local.timeInMillis = combined
        assertEquals(2026, local.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, local.get(Calendar.MONTH))
        assertEquals(20, local.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, local.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, local.get(Calendar.MINUTE))
    }
}
