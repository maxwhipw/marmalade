package app.marmalade.android.rpc.types

import app.marmalade.android.rpc.JsonRpcClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-conformance twin for the session.archive types (mirroring
 * marmalade/packages/protocol/src/methods.ts SessionArchiveResult + the
 * `archived` field on session.list rows). Pins the back-compat contract:
 * an OLD daemon that omits `archived` decodes as false, never a crash.
 */
class SessionArchiveWireTest {

    private val json = JsonRpcClient.DefaultJson

    @Test fun `session_list row archived defaults to false when absent (old daemon)`() {
        // No `archived` key — a pre-flag daemon.
        val wire = """{"sessions":[{"session_id":"s-old","topic":"chat"}]}"""
        val row = json.decodeFromString(SessionListResponse.serializer(), wire).sessions.single()
        assertFalse("absent archived must decode as false, not crash", row.archived)
    }

    @Test fun `session_list row archived true round-trips`() {
        val wire = """{"sessions":[{"session_id":"s-arch","topic":"chat","archived":true}]}"""
        val row = json.decodeFromString(SessionListResponse.serializer(), wire).sessions.single()
        assertTrue(row.archived)
    }

    @Test fun `session_list row archived false round-trips`() {
        val wire = """{"sessions":[{"session_id":"s-act","topic":"chat","archived":false}]}"""
        val row = json.decodeFromString(SessionListResponse.serializer(), wire).sessions.single()
        assertFalse(row.archived)
    }

    @Test fun `session_archive result decodes the stored flag`() {
        assertTrue(
            json.decodeFromString(SessionArchiveResponse.serializer(), """{"archived":true}""").archived,
        )
        assertFalse(
            json.decodeFromString(SessionArchiveResponse.serializer(), """{"archived":false}""").archived,
        )
    }

    @Test fun `session_archive result tolerates a missing flag`() {
        // Defensive: an unexpectedly empty result must not crash the client.
        assertFalse(
            json.decodeFromString(SessionArchiveResponse.serializer(), "{}").archived,
        )
    }

    @Test fun `unknown future fields on a row are ignored`() {
        val wire = """{"sessions":[{"session_id":"s","archived":true,"future_field":42}]}"""
        val row = json.decodeFromString(SessionListResponse.serializer(), wire).sessions.single()
        assertEquals("s", row.session_id)
        assertTrue(row.archived)
    }
}
