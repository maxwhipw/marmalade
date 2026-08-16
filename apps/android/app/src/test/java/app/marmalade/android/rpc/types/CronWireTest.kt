package app.marmalade.android.rpc.types

import app.marmalade.android.rpc.JsonRpcClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-conformance twin for the cron types: decodes daemon-shaped JSON
 * (mirroring marmalade/packages/protocol/src/methods.ts CronJobWire) and pins
 * the encode side (nulls OMITTED — the daemon's zod schemas reject explicit
 * nulls on optional fields).
 */
class CronWireTest {

    private val json = JsonRpcClient.DefaultJson

    @Test fun decodesDaemonJobShape() {
        val wire = """
            {"jobs":[{
              "job_id":"cj_abc","name":null,"session_id":"s_1","prompt":"do it",
              "schedule":{"kind":"cron","expr":"0 9 * * 1-5","tz":"Australia/Brisbane","stagger_ms":0},
              "enabled":true,"created_at":1800000000000,"updated_at":1800000000000,
              "next_run_at":1800000360000,"last_run_at":null,"last_status":null,"last_error":null
            }]}
        """.trimIndent()
        val jobs = json.decodeFromString(CronListResponse.serializer(), wire).jobs
        assertEquals(1, jobs.size)
        val j = jobs[0]
        assertEquals("cj_abc", j.jobId)
        assertNull(j.name)
        assertEquals("s_1", j.sessionId)
        assertEquals("cron", j.schedule.kind)
        assertEquals("0 9 * * 1-5", j.schedule.expr)
        assertEquals("Australia/Brisbane", j.schedule.tz)
        assertEquals(1_800_000_360_000L, j.nextRunAt)
        assertNull(j.lastStatus)
    }

    @Test fun toleratesUnknownFieldsAndScheduleKinds() {
        // Daemon additions must never break an old client (ignoreUnknownKeys +
        // pass-through kind, per .claude/rules/protocol.md).
        val wire = """
            {"jobs":[{
              "job_id":"cj_x","session_id":"s","prompt":"p","future_field":42,
              "schedule":{"kind":"lunar","phase":"full"},
              "enabled":false,"created_at":1,"updated_at":2,
              "next_run_at":null,"last_run_at":null,"last_status":null,
              "last_error":"unresolvable schedule"
            }]}
        """.trimIndent()
        val j = json.decodeFromString(CronListResponse.serializer(), wire).jobs[0]
        assertEquals("lunar", j.schedule.kind)
        assertFalse(j.enabled)
        assertEquals("unresolvable schedule", j.lastError)
    }

    @Test fun encodeOmitsNullScheduleFields() {
        val el = json.encodeToJsonElement(
            CronSchedule.serializer(),
            CronSchedule(kind = "every", everyMs = 900_000, anchorMs = 1_800_000_000_000L),
        ) as JsonObject
        assertEquals("every", el["kind"]!!.jsonPrimitive.content)
        assertEquals("900000", el["every_ms"]!!.jsonPrimitive.content)
        // expr/tz/at_ms must be ABSENT, not null (zod optional() rejects null).
        assertFalse(el.containsKey("expr"))
        assertFalse(el.containsKey("tz"))
        assertFalse(el.containsKey("at_ms"))
        assertTrue(el.containsKey("anchor_ms"))
    }
}
