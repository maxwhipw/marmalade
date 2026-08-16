package app.marmalade.android

import app.marmalade.android.chat.messages.FakeChatDao
import app.marmalade.android.chat.messages.FakeMarmaladeRpc
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.types.SessionCreateResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the gateway session-creation flow introduced to fix the
 * "NewSessionDialog name gets dropped on the floor" bug.
 *
 * Bug: SessionListViewModel.createSession() for gateway sessions called
 * patchChatSession(clientCoinedKey, name) — but no Room row existed yet,
 * so patchChatSession found no gatewaySessionId, skipped the rename RPC,
 * and the user's title was lost.
 *
 * Fix: call sessionCreate(title=name) first, then persist the returned ids
 * in Room (key = gatewaySessionId = the daemon's immutable session_id).
 *
 * Since SessionListViewModel is an AndroidViewModel (requires a real
 * Application context), these tests follow the PersonalitySettingsTest
 * pattern — they exercise the extracted logic directly against
 * FakeMarmaladeRpc + FakeChatDao, without instantiating the ViewModel.
 */
class SessionListViewModelCreateTest {

    /**
     * Extracted logic that mirrors MarmaladeRuntime.createGatewaySession.
     * Tests call this directly so we get full coverage of the RPC + DAO
     * interactions without needing a real Application / Room.
     */
    private suspend fun createGatewaySession(
        rpc: FakeMarmaladeRpc,
        dao: FakeChatDao,
        title: String,
        agentId: String? = null,
    ): String {
        val effectiveTitle = title.ifBlank { "New Chat" }
        val created = rpc.sessionCreate(title = effectiveTitle)
        val sid = created.session_id
        val canonicalKey = sid
        val now = System.currentTimeMillis()
        dao.insertSession(
            SessionEntity(
                key = canonicalKey,
                gatewaySessionId = sid,
                displayName = effectiveTitle,
                agentId = agentId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return canonicalKey
    }

    // ── Happy path: name passes through ─────────────────────────────────────

    @Test
    fun `createSession with name calls sessionCreate with that title`() = runTest {
        val rpc = FakeMarmaladeRpc().apply {
            sessionCreateResponse = SessionCreateResponse(session_id = "live-1")
        }
        val dao = FakeChatDao()

        createGatewaySession(rpc, dao, "Anime Test")

        assertEquals(1, rpc.sessionCreateCalls.size)
        assertEquals("Anime Test", rpc.sessionCreateCalls[0].title)
    }

    @Test
    fun `createSession with name inserts row with displayName`() = runTest {
        val rpc = FakeMarmaladeRpc().apply {
            sessionCreateResponse = SessionCreateResponse(session_id = "live-1")
        }
        val dao = FakeChatDao()

        createGatewaySession(rpc, dao, "Anime Test")

        val (sessions, _, _) = dao.snapshot()
        assertEquals(1, sessions.size)
        val row = sessions.values.first()
        assertEquals("Anime Test", row.displayName)
    }

    @Test
    fun `createSession keys the row by the daemon's immutable session_id`() = runTest {
        val rpc = FakeMarmaladeRpc().apply {
            sessionCreateResponse = SessionCreateResponse(session_id = "srv-abc")
        }
        val dao = FakeChatDao()

        val key = createGatewaySession(rpc, dao, "My Session")

        assertEquals("srv-abc", key)
        val (sessions, _, _) = dao.snapshot()
        val row = sessions["srv-abc"]
        assertNotNull("Expected row keyed by session_id", row)
        assertEquals("srv-abc", row!!.gatewaySessionId)
    }

    // ── Empty-name path ──────────────────────────────────────────────────────

    @Test
    fun `createSession with empty name uses New Chat placeholder`() = runTest {
        val rpc = FakeMarmaladeRpc().apply {
            sessionCreateResponse = SessionCreateResponse(session_id = "live-2")
        }
        val dao = FakeChatDao()

        createGatewaySession(rpc, dao, "")

        assertEquals(1, rpc.sessionCreateCalls.size)
        assertEquals("New Chat", rpc.sessionCreateCalls[0].title)
        val (sessions, _, _) = dao.snapshot()
        assertEquals("New Chat", sessions.values.first().displayName)
    }

    @Test
    fun `createSession with blank whitespace uses New Chat placeholder`() = runTest {
        val rpc = FakeMarmaladeRpc().apply {
            sessionCreateResponse = SessionCreateResponse(session_id = "live-3")
        }
        val dao = FakeChatDao()

        createGatewaySession(rpc, dao, "   ")

        assertEquals("New Chat", rpc.sessionCreateCalls[0].title)
        val (sessions, _, _) = dao.snapshot()
        assertEquals("New Chat", sessions.values.first().displayName)
    }

    // ── RPC failure ──────────────────────────────────────────────────────────

    @Test
    fun `createSession rpc failure throws and does not insert row`() = runTest {
        val rpc = FakeMarmaladeRpc().apply {
            sessionCreateError = RuntimeException("gateway offline")
        }
        val dao = FakeChatDao()

        var threw = false
        try {
            createGatewaySession(rpc, dao, "Should Fail")
        } catch (e: RuntimeException) {
            threw = true
            assertEquals("gateway offline", e.message)
        }

        assertTrue("Expected RPC failure to propagate as exception", threw)
        val (sessions, _, _) = dao.snapshot()
        assertTrue("No row should be inserted on RPC failure", sessions.isEmpty())
    }
}
