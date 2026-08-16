package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.types.SessionListRow
import app.marmalade.android.rpc.types.SessionListResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the `refreshSessions` displayName fallback chain (parity row K3).
 *
 * Desktop chain (`chat-runtime.ts:62`):
 *   session.title?.trim() || session.preview?.trim() || 'Untitled session'
 *
 * Android equivalent (ChatController.kt, post-title-A fix 2026-06-29):
 *   row.title.trim().ifEmpty { null }
 *       ?: row.preview.trim().ifEmpty { null }
 *       ?: existing?.displayName
 *       ?: friendlySessionName(key)
 *
 * The terminal `friendlySessionName(key)` was added after on-device testing
 * showed all untitled sessions rendering as the literal "New Session" string
 * (the hard-coded fallback in SessionCategoryUtils when displayName is null).
 * The top bar already used `friendlySessionName` as its terminal fallback
 * (MarmaladeNavHost.kt:423); applying it here aligns the two display paths.
 *
 * Covers:
 *  - title present → title wins.
 *  - title blank, preview present → preview fills in.
 *  - title whitespace-only, preview present → trim semantics, preview wins.
 *  - both blank, existing displayName present → existing displayName preserved.
 *  - both blank, no existing displayName → friendlySessionName(key).
 *
 * Uses [FakeChatDao] (in-memory) and [FakeMarmaladeRpc] with a scripted
 * sessionList response. No Room/Robolectric needed.
 *
 * Test file: SessionListRefreshTest.kt (parity row K3)
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionListRefreshTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    /**
     * Minimal ChatController harness. Mirrors SessionInfoModelTest.buildHarness
     * exactly — UnconfinedTestDispatcher so coroutines launched inside the
     * controller start eagerly.
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
        // Inject UnconfinedTestDispatcher as ioDispatcher so that
        // refreshSessions' withContext(ioDispatcher) block runs eagerly
        // and the DAO write completes before the test asserts.
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

    /**
     * Script [FakeMarmaladeRpc.sessionList] to return a single row, open the
     * transport, call refreshSessions, and wait for Room writes to complete
     * (UnconfinedTestDispatcher drains eagerly so no explicit advance needed).
     */
    private suspend fun Harness.seedAndRefresh(row: SessionListRow) {
        rpc.sessionListResponse = SessionListResponse(sessions = listOf(row))
        rpc.openTransport()
        controller.refreshSessions()
    }

    // ── Test cases ──────────────────────────────────────────────────────────

    @Test
    fun `title present, preview present — title wins`() = runTest {
        val h = buildHarness()
        try {
            h.seedAndRefresh(
                SessionListRow(session_id = "sess-1", topic = "My Session", summary = "hi"),
            )
            val stored = h.dao.getSessionByKey("sess-1")
            assertEquals("displayName should be the title", "My Session", stored?.displayName)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `title blank, preview present — preview fills in`() = runTest {
        val h = buildHarness()
        try {
            h.seedAndRefresh(
                SessionListRow(session_id = "sess-2", topic = "", summary = "Hey there"),
            )
            val stored = h.dao.getSessionByKey("sess-2")
            assertEquals("displayName should fall back to preview", "Hey there", stored?.displayName)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `title whitespace-only, preview present — trim semantics, preview wins`() = runTest {
        val h = buildHarness()
        try {
            h.seedAndRefresh(
                SessionListRow(session_id = "sess-3", topic = "   ", summary = "real text"),
            )
            val stored = h.dao.getSessionByKey("sess-3")
            assertEquals("whitespace title must be trimmed away; preview wins", "real text", stored?.displayName)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `title blank, preview blank, existing displayName — existing preserved`() = runTest {
        val h = buildHarness()
        try {
            // Pre-seed an existing row with a locally-set display name.
            h.dao.insertSession(
                SessionEntity(key = "sess-4", displayName = "Local Name", thinkingLevel = "off"),
            )
            h.seedAndRefresh(
                SessionListRow(session_id = "sess-4", topic = "", summary = ""),
            )
            val stored = h.dao.getSessionByKey("sess-4")
            assertEquals("existing displayName must be preserved when both title and preview are blank", "Local Name", stored?.displayName)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `model reported by session_list round-trips into the Room row`() = runTest {
        val h = buildHarness()
        try {
            h.seedAndRefresh(
                SessionListRow(session_id = "sess-m1", topic = "t", model = "claude-opus-4-8"),
            )
            val stored = h.dao.getSessionByKey("sess-m1")
            assertEquals("session.list model must persist into the mirror", "claude-opus-4-8", stored?.model)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `model omitted by session_list preserves the existing local value`() = runTest {
        val h = buildHarness()
        try {
            // e.g. an unsent picker choice, or a value from a prior refresh.
            h.dao.insertSession(
                SessionEntity(key = "sess-m2", thinkingLevel = "off", model = "claude-haiku-4"),
            )
            h.seedAndRefresh(
                SessionListRow(session_id = "sess-m2", topic = "t"),
            )
            val stored = h.dao.getSessionByKey("sess-m2")
            assertEquals("a row without model must not clobber the local value", "claude-haiku-4", stored?.model)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `is_main from session_list threads into the Room row`() = runTest {
        val h = buildHarness()
        try {
            h.seedAndRefresh(
                SessionListRow(session_id = "sess-main", topic = "Assistant", is_main = true),
            )
            val stored = h.dao.getSessionByKey("sess-main")
            assertEquals("is_main must mirror into the session row", true, stored?.isMain)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `is_main false by default (ordinary session)`() = runTest {
        val h = buildHarness()
        try {
            h.seedAndRefresh(SessionListRow(session_id = "sess-ordinary", topic = "chat"))
            val stored = h.dao.getSessionByKey("sess-ordinary")
            assertEquals("an ordinary row is never main", false, stored?.isMain)
        } finally {
            h.tearDown()
        }
    }

    // ── archived (session.archive) ──────────────────────────────────────────

    @Test
    fun `archived from session_list threads into the Room row`() = runTest {
        val h = buildHarness()
        try {
            h.seedAndRefresh(
                SessionListRow(session_id = "sess-arch", topic = "chat", archived = true),
            )
            assertEquals(
                "archived must mirror into the session row",
                true, h.dao.getSessionByKey("sess-arch")?.archived,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `archived absent decodes false and is adopted verbatim (old daemon)`() = runTest {
        val h = buildHarness()
        try {
            // A previously-archived local row; the (old-daemon) list row omits
            // the flag → decodes false → adopted verbatim, same as isMain.
            h.dao.insertSession(
                SessionEntity(key = "sess-una", thinkingLevel = "off", archived = true),
            )
            h.seedAndRefresh(SessionListRow(session_id = "sess-una", topic = "chat"))
            assertEquals(
                "archived is server truth, adopted verbatim (never preserved-on-null)",
                false, h.dao.getSessionByKey("sess-una")?.archived,
            )
        } finally {
            h.tearDown()
        }
    }

    // ── persisted context occupancy (the cold-open donut seed) ──────────────

    @Test
    fun `context occupancy from session_list threads into the Room row`() = runTest {
        val h = buildHarness()
        try {
            h.seedAndRefresh(
                SessionListRow(
                    session_id = "sess-ctx", topic = "chat",
                    context_used = 32_900L, context_max = 200_000L, context_percent = 16.0,
                ),
            )
            val stored = h.dao.getSessionByKey("sess-ctx")
            assertEquals("context_used must mirror into the session row", 32_900L, stored?.contextUsed)
            assertEquals("context_max must mirror into the session row", 200_000L, stored?.contextMax)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `context occupancy absent is adopted verbatim, clearing a stale local reading`() = runTest {
        val h = buildHarness()
        try {
            // The daemon nulls both columns on session.clear (and an old daemon
            // omits them entirely). Either way the local mirror must go dark
            // rather than keep showing the pre-clear number.
            h.dao.insertSession(
                SessionEntity(
                    key = "sess-stale", thinkingLevel = "off",
                    contextUsed = 32_900L, contextMax = 200_000L,
                ),
            )
            h.seedAndRefresh(SessionListRow(session_id = "sess-stale", topic = "chat"))
            val stored = h.dao.getSessionByKey("sess-stale")
            assertNull("context is server truth, adopted verbatim (never preserved-on-null)", stored?.contextUsed)
            assertNull(stored?.contextMax)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `refresh wipes a non-bound session whose server last_seq dropped below local (missed clear)`() = runTest {
        val h = buildHarness()
        try {
            // A background session with local messages up to seq 50 …
            h.dao.insertSession(SessionEntity(key = "sess-cleared", thinkingLevel = "off"))
            h.dao.insertMessage(
                MessageEntity(id = "old-1", sessionKey = "sess-cleared", role = "user", contentJson = "[]", timestampMs = 1L, serverSeq = 50L),
            )
            // … the daemon cleared it (last_seq back to 0) while we weren't
            // subscribed; the transient session.cleared was missed.
            h.seedAndRefresh(SessionListRow(session_id = "sess-cleared", topic = "chat", last_seq = 0))
            assertTrue(
                "stale rows for a server-truncated background session must be wiped",
                h.dao.getMessagesForSessionOnce("sess-cleared").isEmpty(),
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `refresh keeps rows when server last_seq is at or above local`() = runTest {
        val h = buildHarness()
        try {
            h.dao.insertSession(SessionEntity(key = "sess-live", thinkingLevel = "off"))
            h.dao.insertMessage(
                MessageEntity(id = "m-1", sessionKey = "sess-live", role = "user", contentJson = "[]", timestampMs = 1L, serverSeq = 10L),
            )
            // Server is caught up (last_seq >= local max) — no truncation.
            h.seedAndRefresh(SessionListRow(session_id = "sess-live", topic = "chat", last_seq = 10))
            assertEquals(
                "a healthy session's rows survive refresh",
                1, h.dao.getMessagesForSessionOnce("sess-live").size,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `title blank, preview blank, no existing displayName — friendlySessionName fallback`() = runTest {
        val h = buildHarness()
        try {
            // Use a realistic post-K1 stored_session_id key format:
            // YYYYMMDD_HHMMSS_RANDOM6 → friendlySessionName splits on _ and
            // title-cases each token, producing a readable but unique string.
            h.seedAndRefresh(
                SessionListRow(session_id = "20260629_180856_779e02", topic = "", summary = ""),
            )
            val stored = h.dao.getSessionByKey("20260629_180856_779e02")
            // friendlySessionName: substringAfterLast(":") → unchanged,
            // strip "g-" → unchanged, split("-", "_") → 3 tokens, distinct,
            // joinToString(" ") → "20260629 180856 779e02".
            assertEquals(
                "untitled session must fall back to friendlySessionName(key), NOT null — null produces the hard-coded \"New Session\" via SessionCategoryUtils",
                "20260629 180856 779e02",
                stored?.displayName,
            )
        } finally {
            h.tearDown()
        }
    }

    // ── Fork lineage merge (T2 #3) ──────────────────────────────────────────

    @Test
    fun `branched_from on the wire is adopted into the row`() = runTest {
        val h = buildHarness()
        try {
            h.seedAndRefresh(
                SessionListRow(
                    session_id = "fork-1",
                    topic = "fork",
                    branched_from = app.marmalade.android.rpc.types.SessionLineageRef(
                        session_id = "src-1", message_id = "m_7",
                    ),
                ),
            )
            assertEquals("src-1", h.dao.getSessionByKey("fork-1")?.branchedFromId)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `a list row without branched_from never regresses a locally-stamped lineage`() = runTest {
        val h = buildHarness()
        try {
            // bindForkedSession stamps branchedFromId locally at fork time;
            // a refresh whose row omits the field must PRESERVE it (the
            // nullable-merge idiom, same as model/lifecycle).
            h.dao.insertSession(
                SessionEntity(
                    key = "fork-2",
                    displayName = "x (fork)",
                    thinkingLevel = "off",
                    branchedFromId = "src-2",
                ),
            )
            h.seedAndRefresh(SessionListRow(session_id = "fork-2", topic = "x (fork)"))
            assertEquals("src-2", h.dao.getSessionByKey("fork-2")?.branchedFromId)
        } finally {
            h.tearDown()
        }
    }
}
