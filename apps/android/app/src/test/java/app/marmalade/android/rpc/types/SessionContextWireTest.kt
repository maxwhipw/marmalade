package app.marmalade.android.rpc.types

import app.marmalade.android.rpc.JsonRpcClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-conformance twin for the persisted context-occupancy fields on
 * session.list rows (mirroring the protocol comment in
 * marmalade/packages/protocol/src/methods.ts, after SessionArchiveResult, and
 * the daemon's router.ts session.list handler).
 *
 * The whole contract is "unknown must stay unknown": an OLD daemon that omits
 * the fields and a daemon that reports them as null (never ran / no window /
 * just cleared) both decode to null, and the client draws no donut rather than
 * a fabricated number.
 */
class SessionContextWireTest {

    private val json = JsonRpcClient.DefaultJson

    private fun row(wire: String): SessionListRow =
        json.decodeFromString(SessionListResponse.serializer(), wire).sessions.single()

    @Test fun `absent context fields decode as null (old daemon)`() {
        val r = row("""{"sessions":[{"session_id":"s-old","topic":"chat"}]}""")
        assertNull("absent context_used must decode as null, not crash", r.context_used)
        assertNull("absent context_max must decode as null, not crash", r.context_max)
        assertNull(r.context_percent)
    }

    @Test fun `explicit nulls decode as null (never ran, or just cleared)`() {
        val r = row(
            """{"sessions":[{"session_id":"s","context_used":null,"context_max":null,"context_percent":null}]}""",
        )
        assertNull(r.context_used)
        assertNull(r.context_max)
        assertNull(r.context_percent)
    }

    @Test fun `a stamped row carries both halves`() {
        val r = row(
            """{"sessions":[{"session_id":"s","context_used":32900,"context_max":200000,"context_percent":16}]}""",
        )
        assertEquals(32_900L, r.context_used)
        assertEquals(200_000L, r.context_max)
    }

    @Test fun `a window-less harness reports used with a null max`() {
        // ACP/OpenCode: tokens are known, the window is not. The daemon stores
        // used and derives a null percent; the client must treat it as unknown
        // (asserted at the ContextOccupancy layer — here we just pin the decode).
        val r = row("""{"sessions":[{"session_id":"s","context_used":9000,"context_max":null}]}""")
        assertEquals(9_000L, r.context_used)
        assertNull(r.context_max)
    }

    @Test fun `a million-token window survives the decode`() {
        // context_max exceeds Int range on the big-window models — the field is
        // Long for exactly this reason.
        val r = row("""{"sessions":[{"session_id":"s","context_used":40542,"context_max":1000000}]}""")
        assertEquals(40_542L, r.context_used)
        assertEquals(1_000_000L, r.context_max)
    }

    @Test fun `context fields do not disturb the rest of the row`() {
        val r = row(
            """{"sessions":[{"session_id":"s","archived":true,"is_main":true,"context_used":10,"context_max":100}]}""",
        )
        assertEquals("s", r.session_id)
        assertEquals(true, r.archived)
        assertEquals(true, r.is_main)
    }
}
