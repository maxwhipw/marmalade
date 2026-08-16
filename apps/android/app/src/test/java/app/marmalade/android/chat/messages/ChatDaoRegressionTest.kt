package app.marmalade.android.chat.messages

import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests pinned to specific production bugs. Each test names the
 * commit it's pinning. Run these before any release.
 *
 * The harness for these tests is [FakeChatDao] — the in-memory digital
 * twin of the production [app.marmalade.android.data.local.dao.ChatDao],
 * mirroring the Room semantics that matter (FK CASCADE on session DELETE,
 * @Update preserves children, ackOutboxAsMessage atomicity,
 * reconcileHistory content alignment).
 */
class ChatDaoRegressionTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private fun mainSession(thinkingLevel: String = "off") = SessionEntity(
        key = "main",
        thinkingLevel = thinkingLevel,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun userMessage(
        id: String,
        sessionKey: String = "main",
        ts: Long = 100L,
        ordinal: Long = 1L,
        text: String = id,
    ) = MessageEntity(
        id = id, sessionKey = sessionKey, role = "user",
        contentJson = """[{"type":"text","text":"$text"}]""",
        timestampMs = ts, clientOrdinal = ordinal,
    )

    private fun pendingOutbox(id: String, sessionKey: String = "main", ts: Long = 100L, ordinal: Long = 1L) =
        OutboxEntity(
            id = id, sessionKey = sessionKey, serverSessionId = "main",
            contentJson = """[{"type":"text","text":"queued"}]""",
            createdAtMs = ts, clientOrdinal = ordinal,
        )

    // ──────────────────────────────────────────────────────────────────────
    // 5e23893 — CASCADE wipe on every sendMessage
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Verifies the bug class that produced commit 5e23893. The production
     * ChatDao.insertSession is @Insert(onConflict = REPLACE), which Room
     * compiles to `INSERT OR REPLACE INTO sessions ...`. SQLite executes
     * that as DELETE-then-INSERT, and the DELETE fires the FK CASCADE on
     * messages.sessionKey + outbox.sessionKey. So every time ChatController
     * called insertSession(currentSession.copy(thinkingLevel = X)) to
     * persist a thinking-level change, the entire chat history disappeared.
     *
     * The Phase 7.1 Room-as-canonical refactor surfaced the bug — pre-7.1
     * the UI bound to ChatController._messages (an in-memory StateFlow)
     * which didn't notice the Room CASCADE.
     */
    @Test
    fun `insertSession on existing key CASCADE-wipes messages and outbox (regression 5e23893)`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())
        dao.insertMessage(userMessage("m1"))
        dao.insertOutbox(pendingOutbox("o1", ordinal = 2L))
        assertEquals(1, dao.getMessageCount("main"))
        assertEquals(1, dao.getOutboxForSessionOnce("main").size)

        // The bug — production code did this every send.
        dao.insertSession(mainSession(thinkingLevel = "high"))

        assertEquals("messages.sessionKey FK CASCADE fired", 0, dao.getMessageCount("main"))
        assertEquals("outbox.sessionKey FK CASCADE fired", 0, dao.getOutboxForSessionOnce("main").size)
    }

    /**
     * Verifies the fix in 5e23893: ChatDao.updateSessionRow (@Update) does
     * NOT trigger CASCADE, so chat history survives a thinking-level update.
     */
    @Test
    fun `updateSessionRow preserves messages and outbox (verifies fix 5e23893)`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())
        dao.insertMessage(userMessage("m1"))
        dao.insertOutbox(pendingOutbox("o1", ordinal = 2L))

        dao.updateSessionRow(mainSession(thinkingLevel = "high"))

        assertEquals("UPDATE preserves messages", 1, dao.getMessageCount("main"))
        assertEquals("UPDATE preserves outbox", 1, dao.getOutboxForSessionOnce("main").size)
        assertEquals("UPDATE persists the new field", "high", dao.getSessionByKey("main")?.thinkingLevel)
    }

    @Test
    fun `updateSessionRow on missing key is a no-op (mirrors UPDATE semantics)`() = runTest {
        val dao = FakeChatDao()
        dao.updateSessionRow(mainSession())  // no insert first

        assertNull("nothing inserted", dao.getSessionByKey("main"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // ChatRepository.renameSession CASCADE wipe (same bug class as 5e23893)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Pins the same bug class as 5e23893, found independently in
     * ChatRepository.renameSession: it read the existing session row and
     * called insertSession(existing.copy(displayName = newName)) instead of
     * updateSessionRow. This test reproduces the pre-fix rename step
     * directly against the DAO to prove the hazard is real at the DAO
     * layer — insertSession on an existing key wipes history regardless of
     * which caller triggers it.
     */
    @Test
    fun `insertSession-based rename wipes messages and outbox (renameSession bug class)`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())
        dao.insertMessage(userMessage("m1"))
        dao.insertOutbox(pendingOutbox("o1", ordinal = 2L))
        val existing = dao.getSessionByKey("main")!!

        // Pre-fix ChatRepository.renameSession did exactly this.
        dao.insertSession(existing.copy(displayName = "renamed", updatedAt = 1L))

        assertEquals("messages.sessionKey FK CASCADE fired", 0, dao.getMessageCount("main"))
        assertEquals("outbox.sessionKey FK CASCADE fired", 0, dao.getOutboxForSessionOnce("main").size)
    }

    /**
     * Verifies the fix: renaming via updateSessionRow (what
     * ChatRepository.renameSession now calls) preserves messages and
     * outbox while still persisting the new display name.
     */
    @Test
    fun `updateSessionRow-based rename preserves messages and outbox (verifies renameSession fix)`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())
        dao.insertMessage(userMessage("m1"))
        dao.insertOutbox(pendingOutbox("o1", ordinal = 2L))
        val existing = dao.getSessionByKey("main")!!

        dao.updateSessionRow(existing.copy(displayName = "renamed", updatedAt = 1L))

        assertEquals("rename preserves messages", 1, dao.getMessageCount("main"))
        assertEquals("rename preserves outbox", 1, dao.getOutboxForSessionOnce("main").size)
        assertEquals("rename persists the new display name", "renamed", dao.getSessionByKey("main")?.displayName)
    }

    // ──────────────────────────────────────────────────────────────────────
    // ackOutboxAsMessage atomicity
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ackOutboxAsMessage moves outbox to messages with outbox id preserved`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())
        dao.insertOutbox(pendingOutbox("outbox-X"))

        dao.ackOutboxAsMessage("outbox-X")

        assertEquals(0, dao.getOutboxForSessionOnce("main").size)
        assertEquals(1, dao.getMessageCount("main"))
        assertEquals("messages.id = outbox.id (plugin issues no server-side id for user msgs)",
            "outbox-X", dao.getMessagesForSessionOnce("main").first().id)
    }

    @Test
    fun `ackOutboxAsMessage is a no-op if outbox row already removed`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())

        // reconcileHistory's content-match path can remove the outbox row
        // before the drainer's ack lands. Ack must tolerate this.
        dao.ackOutboxAsMessage("outbox-X-never-existed")

        assertEquals(0, dao.getMessageCount("main"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // History reconciliation — covered in ReconcileHistoryTest. The old
    // mergeHistory fingerprint (role + content + ±5s window) tests were
    // removed with mergeHistory itself: server history rows carry no
    // timestamps, so the window could never match and every hydrate
    // duplicated the transcript (2026-07-01 regression).
    // ──────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────
    // Boot recovery
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `demoteStaleSendingOutbox flips sending rows back to pending`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())
        dao.insertOutbox(pendingOutbox("o1"))
        dao.markOutboxSending("o1")
        assertEquals("sending", dao.getOutboxByIdOnce("o1")?.status)

        dao.demoteStaleSendingOutbox()
        assertEquals("pending", dao.getOutboxByIdOnce("o1")?.status)
    }

    @Test
    fun `demoteStaleStreamingMessages flips isStreaming rows to false`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())
        dao.insertMessage(MessageEntity(
            id = "asst-1", sessionKey = "main", role = "assistant",
            contentJson = "[]", timestampMs = 100L, clientOrdinal = 0L, isStreaming = true,
        ))

        dao.demoteStaleStreamingMessages()

        assertFalse("orphaned streaming row demoted",
            dao.getMessagesForSessionOnce("main").first().isStreaming)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Observe flow re-emission
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `observeOutboxForSession re-emits after insertOutbox`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())

        val initial = dao.observeOutboxForSession("main").first()
        assertTrue("starts empty", initial.isEmpty())

        dao.insertOutbox(pendingOutbox("o1"))
        val after = dao.observeOutboxForSession("main").first()
        assertEquals(1, after.size)
        assertEquals("o1", after.first().id)
    }

    @Test
    fun `getMessagesForSession Flow filters by sessionKey`() = runTest {
        val dao = FakeChatDao()
        dao.insertSession(mainSession())
        dao.insertSession(SessionEntity(key = "agent:other", thinkingLevel = "off"))
        dao.insertMessage(userMessage("m-main", sessionKey = "main"))
        dao.insertMessage(userMessage("m-other", sessionKey = "agent:other"))

        val mainView = dao.getMessagesForSession("main").first()
        val otherView = dao.getMessagesForSession("agent:other").first()

        assertEquals(listOf("m-main"), mainView.map { it.id })
        assertEquals("cross-session leakage check", listOf("m-other"), otherView.map { it.id })
    }
}
