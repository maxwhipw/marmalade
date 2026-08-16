package app.marmalade.android.utils

import app.marmalade.android.rpc.types.CronJob
import app.marmalade.android.rpc.types.CronSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display rules for the scheduled-prompts screen. The daemon semantics they
 * reflect are pinned in the daemon repo's test/cron-router.test.ts; here we
 * pin the client rendering: disabled jobs surface their reason, fired
 * one-shots read as done, unknown schedule kinds don't crash.
 */
class CronFormatUtilsTest {

    private val now = 1_800_000_000_000L

    private fun job(
        enabled: Boolean = true,
        schedule: CronSchedule = CronSchedule(kind = "every", everyMs = 3_600_000),
        nextRunAt: Long? = now + 3_600_000,
        lastRunAt: Long? = null,
        lastStatus: String? = null,
        lastError: String? = null,
    ) = CronJob(
        jobId = "cj_1", name = "test", sessionId = "s1", prompt = "p",
        schedule = schedule, enabled = enabled, createdAt = now, updatedAt = now,
        nextRunAt = nextRunAt, lastRunAt = lastRunAt, lastStatus = lastStatus, lastError = lastError,
    )

    @Test fun formatMs_picksLargestExactUnit() {
        assertEquals("1s", CronFormatUtils.formatMs(1000))
        assertEquals("90s", CronFormatUtils.formatMs(90_000))
        assertEquals("15m", CronFormatUtils.formatMs(900_000))
        assertEquals("2h", CronFormatUtils.formatMs(7_200_000))
        assertEquals("1d", CronFormatUtils.formatMs(86_400_000))
    }

    @Test fun countdown_coarsensAndClampsPast() {
        assertEquals("now", CronFormatUtils.formatCountdown(-5))
        assertEquals("in 45s", CronFormatUtils.formatCountdown(45_000))
        assertEquals("in 5m", CronFormatUtils.formatCountdown(5 * 60_000L))
        assertEquals("in 3h 20m", CronFormatUtils.formatCountdown(3 * 3_600_000L + 20 * 60_000L))
        assertEquals("in 2d 3h", CronFormatUtils.formatCountdown(2 * 86_400_000L + 3 * 3_600_000L))
    }

    @Test fun describeSchedule_allKindsPlusUnknown() {
        assertEquals("cron \"0 9 * * *\" (UTC)", CronFormatUtils.describeSchedule(CronSchedule("cron", expr = "0 9 * * *", tz = "UTC")))
        assertEquals("cron \"* * * * *\"", CronFormatUtils.describeSchedule(CronSchedule("cron", expr = "* * * * *")))
        assertEquals("every 15m", CronFormatUtils.describeSchedule(CronSchedule("every", everyMs = 900_000)))
        assertTrue(CronFormatUtils.describeSchedule(CronSchedule("at", atMs = now)).startsWith("once at "))
        // Forward compat: an unknown kind renders opaquely, never throws.
        assertEquals("lunar", CronFormatUtils.describeSchedule(CronSchedule("lunar")))
    }

    @Test fun stateLabel_enabledShowsCountdown() {
        assertTrue(CronFormatUtils.stateLabel(job(), now).startsWith("next in 1h"))
    }

    @Test fun stateLabel_nullNextRunIsUnarmed() {
        assertEquals("never (unarmed)", CronFormatUtils.stateLabel(job(nextRunAt = null), now))
    }

    @Test fun stateLabel_schedulerDisabledSurfacesReason() {
        val j = job(enabled = false, lastError = "target session was deleted", lastStatus = "ok")
        assertEquals("disabled — target session was deleted", CronFormatUtils.stateLabel(j, now))
    }

    @Test fun stateLabel_firedOneShotReadsDone() {
        val j = job(
            enabled = false,
            schedule = CronSchedule("at", atMs = now - 1000),
            lastRunAt = now - 1000,
            lastStatus = "ok",
        )
        assertEquals("done (one-shot fired)", CronFormatUtils.stateLabel(j, now))
    }

    @Test fun errorDisabled_isPlainDisabled_errorRidesLastRunLabel() {
        val j = job(enabled = false, lastStatus = "error", lastError = "boom", lastRunAt = now)
        assertEquals("disabled", CronFormatUtils.stateLabel(j, now))
        assertTrue(CronFormatUtils.lastRunLabel(j).contains("failed"))
        assertTrue(CronFormatUtils.lastRunLabel(j).contains("boom"))
    }

    @Test fun parseDuration_unitsAndBareMinutes() {
        assertEquals(30_000L, CronFormatUtils.parseDuration("30s"))
        assertEquals(900_000L, CronFormatUtils.parseDuration("15m"))
        assertEquals(7_200_000L, CronFormatUtils.parseDuration("2h"))
        assertEquals(86_400_000L, CronFormatUtils.parseDuration("1d"))
        assertEquals(90 * 60_000L, CronFormatUtils.parseDuration("90"))
        assertNull(CronFormatUtils.parseDuration("soon"))
        assertNull(CronFormatUtils.parseDuration("0.5s"))
    }
}
