package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.types.SessionListRow
import app.marmalade.android.rpc.types.SessionListResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ChatController.refreshSessions] stale-row pruning (Bug 1)
 * and [ChatController.deleteSession] (Bug 2).
 *
 * Pruning contract:
 *  - Local rows not in the server's returned set are deleted.
 *  - The currently-bound session is NEVER pruned (user is looking at it).
 *  - Sessions with outbox rows are NEVER pruned (un-acked messages would be lost).
 *
 * Delete contract:
 *  - RPC fires for the session (FakeMarmaladeRpc records the call).
 *  - Local Room row + messages are removed.
 *  - If the deleted session was the currently-bound one, the controller
 *    switches focus to "main" (or the "home key" fallback).
 *
 * Uses [FakeChatDao] (in-memory) and [FakeMarmaladeRpc] with scripted responses.
 * No Room or Robolectric needed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionListPruneTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    // ── Harness ─────────────────────────────────────────────────────────────

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    /**
     * Minimal ChatController harness matching SessionListRefreshTest.buildHarness:
     * UnconfinedTestDispatcher so coroutines launched inside the controller
     * start eagerly and IO operations complete before assertions.
     */
    private suspend fun buildHarness(): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        val rpc = FakeMarmaladeRpc()
        val stream = MessageStream(
            events = rpc.rpcClient.events,
            scope = scope,
            chatDao = dao,
            json = testJson,
        )
        val drainer = OutboxDrainer(
            chatDao = dao,
            transport = marmaladeRpcAdapter(rpc),
            scope = scope,
            persistence = stream.persistence,
        )
        val controller = ChatController(
            scope = scope,
            rpc = rpc,
            messageStream = stream,
            outboxDrainer = drainer,
            json = testJson,
            chatDao = dao,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return Harness(dao, rpc, controller, scope)
    }

    /** Open the transport and run refreshSessions with the given server rows. */
    private suspend fun Harness.refreshWith(vararg rows: SessionListRow, prune: Boolean = true) {
        rpc.sessionListResponse = SessionListResponse(sessions = rows.toList())
        rpc.openTransport()
        controller.refreshSessions(prune = prune)
    }

    /** Insert a local session row (simulates a previously-known session). */
    private suspend fun Harness.seedLocal(key: String, displayName: String = key) {
        dao.insertSession(SessionEntity(key = key, displayName = displayName, thinkingLevel = "off"))
    }

    /** Insert a pending outbox row for [sessionKey] (simulates an un-acked send). */
    private suspend fun Harness.seedOutbox(sessionKey: String) {
        dao.insertOutbox(
            OutboxEntity(
                id = "outbox-$sessionKey-1",
                sessionKey = sessionKey,
                serverSessionId = sessionKey,
                contentJson = """[{"type":"text","text":"hello"}]""",
                thinkingLevel = "off",
                status = "pending",
                createdAtMs = System.currentTimeMillis(),
                clientOrdinal = 1L,
            ),
        )
    }

    // ── Prune: stale rows are deleted ────────────────────────────────────────

    @Test
    fun `refreshSessions prunes local row absent from server response`() = runTest {
        val h = buildHarness()
        try {
            // Seed a local session "old-session" that the server no longer returns.
            h.seedLocal("old-session")
            // Server returns only "current-session".
            h.refreshWith(SessionListRow(session_id = "current-session", topic = "Current"))

            assertNull(
                "old-session should have been pruned after server omitted it",
                h.dao.getSessionByKey("old-session"),
            )
            assertNotNull(
                "current-session should still exist",
                h.dao.getSessionByKey("current-session"),
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `refreshSessions prune=false leaves stale rows intact`() = runTest {
        val h = buildHarness()
        try {
            h.seedLocal("old-session")
            // Soft-merge: server returns only "current-session" but prune is false.
            h.refreshWith(SessionListRow(session_id = "current-session", topic = "Current"), prune = false)

            assertNotNull(
                "old-session should be preserved when prune=false",
                h.dao.getSessionByKey("old-session"),
            )
        } finally {
            h.tearDown()
        }
    }

    // ── Prune: bound session is excluded ─────────────────────────────────────

    @Test
    fun `refreshSessions does NOT prune the currently-bound session`() = runTest {
        val h = buildHarness()
        try {
            // Bind to "active-session" — controller._sessionKey becomes "active-session".
            h.seedLocal("active-session")
            // We need the session row in Room so load() doesn't try to resume via RPC.
            // Keep transport closed during load so it stops at the cache-read path.
            h.controller.load("active-session")

            // Now open transport and refresh — server returns only "other-session".
            h.refreshWith(SessionListRow(session_id = "other-session", topic = "Other"))

            // Deferred create (gap triage 2026-07-11): binding a local-only
            // row no longer materializes a server session, so the bound row
            // keeps its client-coined key. It must survive the prune under
            // that key, and no session.create may have fired.
            assertNotNull(
                "the bound session must NOT be pruned",
                h.dao.getSessionByKey("active-session"),
            )
            assertTrue(
                "binding a local-only row must not create a server session",
                h.rpc.sessionCreateCalls.isEmpty(),
            )
        } finally {
            h.tearDown()
        }
    }

    // ── Prune: sessions with outbox rows are excluded ─────────────────────────

    @Test
    fun `refreshSessions does NOT prune a session with outbox rows`() = runTest {
        val h = buildHarness()
        try {
            // Seed "queued-session" locally with a pending outbox entry.
            h.seedLocal("queued-session")
            h.seedOutbox("queued-session")

            // Server response omits "queued-session".
            h.refreshWith(SessionListRow(session_id = "other-session", topic = "Other"))

            assertNotNull(
                "queued-session must NOT be pruned because it has un-acked outbox rows",
                h.dao.getSessionByKey("queued-session"),
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `refreshSessions prunes session with empty outbox but not on server`() = runTest {
        val h = buildHarness()
        try {
            // Session with NO outbox rows and absent from server → should be pruned.
            h.seedLocal("gone-session")

            h.refreshWith(SessionListRow(session_id = "kept-session", topic = "Kept"))

            assertNull(
                "gone-session (no outbox, not on server) must be pruned",
                h.dao.getSessionByKey("gone-session"),
            )
        } finally {
            h.tearDown()
        }
    }

    // ── deleteSession: RPC + local cleanup ────────────────────────────────────

    @Test
    fun `deleteSession fires sessionDelete RPC and removes local row`() = runTest {
        val h = buildHarness()
        try {
            // Seed local row and populate keyToServerId via a refresh.
            h.seedLocal("sess-to-delete")
            h.refreshWith(SessionListRow(session_id = "sess-to-delete", topic = "Deletable"))

            // Verify the row exists before deletion.
            assertNotNull(h.dao.getSessionByKey("sess-to-delete"))

            // Delete it.
            h.controller.deleteSession("sess-to-delete")

            assertNull(
                "local Room row must be removed after deleteSession",
                h.dao.getSessionByKey("sess-to-delete"),
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `deleteSession on bound session switches focus to main`() = runTest {
        val h = buildHarness()
        try {
            // Seed and bind to "bound-session".
            h.seedLocal("bound-session")
            h.controller.load("bound-session")
            assertEquals("bound-session", h.controller.sessionKey.value)

            // Delete the bound session.
            h.controller.deleteSession("bound-session")

            assertEquals(
                "controller must fall back to 'main' after deleting the bound session",
                "main",
                h.controller.sessionKey.value,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `deleteSession on non-bound session does not change focus`() = runTest {
        val h = buildHarness()
        try {
            // Bind to "current-session".
            h.seedLocal("current-session")
            h.controller.load("current-session")

            // Seed and delete a different session.
            h.seedLocal("other-session")
            h.refreshWith(
                SessionListRow(session_id = "current-session", topic = "Current"),
                SessionListRow(session_id = "other-session", topic = "Other"),
            )
            h.controller.deleteSession("other-session")

            assertEquals(
                "focus must remain on the bound session (deferred create: the local" +
                    " key survives the bind — no K1 promotion until first send)",
                "current-session",
                h.controller.sessionKey.value,
            )
            assertNull(
                "other-session must be removed from Room",
                h.dao.getSessionByKey("other-session"),
            )
        } finally {
            h.tearDown()
        }
    }
}
