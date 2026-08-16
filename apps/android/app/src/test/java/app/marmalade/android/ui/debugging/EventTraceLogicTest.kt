package app.marmalade.android.ui.debugging

import app.marmalade.android.data.local.entity.GatewayEventEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind the Event Trace screen: type filter + payload pretty-print. */
class EventTraceLogicTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun row(type: String, rowid: Long = 1L) = GatewayEventEntity(
        rowid = rowid,
        sessionKey = null,
        type = type,
        payloadJson = "null",
        receivedAtMs = 0L,
    )

    @Test
    fun `blank query passes everything, substring match is case-insensitive`() {
        val rows = listOf(row("message.delta", 1), row("tool.complete", 2), row("Tool.Start", 3))
        assertEquals(rows, filterEventsByType(rows, ""))
        assertEquals(rows, filterEventsByType(rows, "   "))
        assertEquals(listOf(2L, 3L), filterEventsByType(rows, "TOOL").map { it.rowid })
        assertTrue(filterEventsByType(rows, "nope").isEmpty())
    }

    @Test
    fun `valid JSON pretty-prints, garbage and null render as-is`() {
        val pretty = prettyPayload("""{"a":1,"b":{"c":true}}""", json)
        assertTrue("indented output", pretty.contains("\n") && pretty.contains("    \"c\": true"))
        assertEquals("null", prettyPayload("null", json))
        assertEquals("not json {", prettyPayload("not json {", json))
    }

    @Test
    fun `oversized payloads truncate with a marker`() {
        val big = "\"" + "x".repeat(EVENT_TRACE_DISPLAY_CAP * 2) + "\""
        val out = prettyPayload(big, json)
        assertTrue(out.length < big.length)
        assertTrue(out.contains("truncated"))
    }
}
