package app.marmalade.android.chat.messages

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.GatewayEventEntity
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room regression suite for the session-keying FK bug fixed in
 * commit ca8bddd.
 *
 * The pre-existing [FakeChatDao] is an in-memory digital twin that does
 * NOT enforce SQLite foreign keys, so the FK-violation class of bug
 * was invisible to its tests. This suite swaps in
 * [Room.inMemoryDatabaseBuilder] so the production FK constraints
 * actually fire, and pins the bug class with both a raw-DAO test and
 * an end-to-end test that drives [MessageStream] against the real DB.
 *
 * Mirrors the bug-reproduction shape: the gateway stamps event payloads
 * with its own `session_id` (e.g. `d09b6359`), while local sessions are
 * keyed `chat-yyyymmdd-HHmmss` (or `main`). Pre-fix code wrote
 * `MessageEntity(sessionKey = <gateway_session_id>)`, which has no
 * matching row in `sessions.key` — every insert hit
 * `FOREIGN KEY constraint failed (code 787)` and silently rolled back,
 * so the assistant bubble never landed in Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatDaoRoomFkTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        // FK enforcement is what catches the bug class. Room 2.6 enables
        // foreign keys by default on the SupportSQLiteOpenHelper it
        // installs; the negative-case test below would silently pass if
        // that ever changed.
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(ctx)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Raw DAO layer ───────────────────────────────────────────────────────

    /**
     * Pre-fix scenario: insert a message keyed by the gateway session_id
     * before any session row with that key exists. Production Room raised
     * `FOREIGN KEY constraint failed`; FakeChatDao would silently accept
     * the row, hiding the bug.
     */
    @Test
    fun `inserting a message with no matching session key triggers FK violation`() = runBlocking {
        try {
            dao.insertMessage(
                MessageEntity(
                    id = "asst-1",
                    sessionKey = "d09b6359",
                    role = "assistant",
                    contentJson = """[{"type":"text","text":"hi"}]""",
                    timestampMs = 100L,
                ),
            )
            fail("expected FK violation, got silent success")
        } catch (t: Throwable) {
            assertTrue(
                "expected FOREIGN KEY constraint message; got: ${t.message}",
                t.message?.contains("FOREIGN KEY", ignoreCase = true) == true,
            )
        }
    }

    /**
     * Post-fix scenario: store the session with its local key, then
     * resolve the gateway id back to the local key via the DAO query
     * the fix added, and use THAT to insert messages.
     */
    @Test
    fun `resolveLocalKeyForGatewayId maps gateway id to the local key the messages table FKs to`() = runBlocking {
        dao.insertSession(
            SessionEntity(
                key = "chat-20260628-214739",
                thinkingLevel = "off",
                gatewaySessionId = "d09b6359",
            ),
        )

        val local = dao.resolveLocalKeyForGatewayId("d09b6359")
        assertEquals("chat-20260628-214739", local)

        // Use the resolved key — the insert succeeds.
        dao.insertMessage(
            MessageEntity(
                id = "asst-1",
                sessionKey = local!!,
                role = "assistant",
                contentJson = """[{"type":"text","text":"hi"}]""",
                timestampMs = 100L,
            ),
        )

        assertEquals(1, dao.getMessagesForSessionOnce("chat-20260628-214739").size)
    }

    @Test
    fun `resolveLocalKeyForGatewayId returns null when no session has that gateway id`() = runBlocking {
        // The MessageStream fix's "no local key for gateway id" warning
        // branch — we want the lookup to fail cleanly, not crash.
        val local = dao.resolveLocalKeyForGatewayId("never-seen")
        assertNull(local)
    }

    // ── K1: renameSessionKey + FK ON UPDATE CASCADE ─────────────────────────

    /**
     * Happy path: a session row is renamed and the FK CASCADE relabels
     * every child messages + outbox row in a single transaction. After
     * the rename, lookups by the old key return empty and lookups by
     * the new key see the original children.
     *
     * This is the load-bearing property K1 (parity audit Option B) is
     * built on — without ON UPDATE CASCADE the rename would either
     * abort with a FK violation or orphan the children.
     */
    @Test
    fun `renameSessionKey cascades to messages and outbox via FK ON UPDATE CASCADE`() = runBlocking {
        dao.insertSession(
            SessionEntity(
                key = "chat-20260628-214739",
                thinkingLevel = "off",
                gatewaySessionId = "live-d09b6359",
            ),
        )
        dao.insertMessage(
            MessageEntity(
                id = "msg-1",
                sessionKey = "chat-20260628-214739",
                role = "user",
                contentJson = """[{"type":"text","text":"hi"}]""",
                timestampMs = 100L,
            ),
        )
        dao.insertMessage(
            MessageEntity(
                id = "msg-2",
                sessionKey = "chat-20260628-214739",
                role = "assistant",
                contentJson = """[{"type":"text","text":"hello back"}]""",
                timestampMs = 200L,
            ),
        )
        dao.insertOutbox(
            OutboxEntity(
                id = "outbox-1",
                sessionKey = "chat-20260628-214739",
                contentJson = """[{"type":"text","text":"queued"}]""",
                createdAtMs = 300L,
                clientOrdinal = 1L,
            ),
        )

        val updated = dao.renameSessionKey(
            oldKey = "chat-20260628-214739",
            newKey = "stored-abc123",
        )
        assertEquals("renameSessionKey reports one row updated", 1, updated)

        // Session row is reachable under the new key only.
        assertNotNull(dao.getSessionByKey("stored-abc123"))
        assertNull(dao.getSessionByKey("chat-20260628-214739"))

        // Messages followed the CASCADE.
        val msgsNew = dao.getMessagesForSessionOnce("stored-abc123")
        assertEquals(2, msgsNew.size)
        assertEquals(0, dao.getMessagesForSessionOnce("chat-20260628-214739").size)
        assertTrue(msgsNew.all { it.sessionKey == "stored-abc123" })

        // Outbox row followed too.
        val outboxNew = dao.getOutboxForSessionOnce("stored-abc123")
        assertEquals(1, outboxNew.size)
        assertEquals("stored-abc123", outboxNew.single().sessionKey)
        assertEquals(0, dao.getOutboxForSessionOnce("chat-20260628-214739").size)

        // The gatewaySessionId column is unaffected by the key rename —
        // K1 keeps it as the LIVE id slot (orthogonal to the canonical
        // key). Confirm we didn't accidentally clobber it.
        assertEquals(
            "live-d09b6359",
            dao.getSessionByKey("stored-abc123")?.gatewaySessionId,
        )
    }

    @Test
    fun `renameSessionKey is a no-op when no row matches`() = runBlocking {
        // Nothing inserted — rename should report 0 rows updated and
        // raise no FK violation.
        val updated = dao.renameSessionKey(oldKey = "ghost", newKey = "phantom")
        assertEquals(0, updated)
        assertNull(dao.getSessionByKey("ghost"))
        assertNull(dao.getSessionByKey("phantom"))
    }

    /**
     * The pre-K1 FK CASCADE on DELETE still has to work post-K1 — we
     * only added ON UPDATE CASCADE, didn't change the delete semantics.
     * Without this guard a refactor that swapped CASCADE for RESTRICT
     * would slip through tests.
     */
    @Test
    fun `renameSessionKey preserves FK CASCADE on DELETE`() = runBlocking {
        dao.insertSession(
            SessionEntity(key = "chat-old", thinkingLevel = "off"),
        )
        dao.insertMessage(
            MessageEntity(
                id = "msg-1",
                sessionKey = "chat-old",
                role = "user",
                contentJson = """[{"type":"text","text":"hi"}]""",
                timestampMs = 100L,
            ),
        )

        dao.renameSessionKey(oldKey = "chat-old", newKey = "stored-xyz")
        assertEquals(1, dao.getMessagesForSessionOnce("stored-xyz").size)

        // Now delete the (renamed) session — children must CASCADE away.
        dao.deleteSession("stored-xyz")
        assertNull(dao.getSessionByKey("stored-xyz"))
        assertEquals(0, dao.getMessagesForSessionOnce("stored-xyz").size)
    }

    // ── End-to-end against MessageStream ────────────────────────────────────

    /**
     * The regression test that would have caught ca8bddd's bug. Drives
     * MessageStream against real Room with FK enforcement, replaying the
     * exact event sequence the maintainer's phone produced when the bug surfaced:
     *
     *   1. local session keyed `chat-20260628-214739`, gateway id `d09b6359`
     *   2. gateway streams `message.start` / `message.delta` / `message.complete`
     *      all stamped `session_id = "d09b6359"`
     *   3. MessageStream's persistence coordinator flushes — must land
     *      a row in `messages` with sessionKey = `chat-20260628-214739`,
     *      NOT `d09b6359`
     *
     * Pre-fix this test crashed the test JVM via an SQLite FK exception
     * on the first flush. Post-fix it persists with the correct local key.
     */
    @Test
    fun `MessageStream flushes assistant message with the local session key resolved from gateway id`() = runBlocking {
        // SessionEntity persisted under the LOCAL key; gateway id is
        // stored as a column for the DAO to map back from.
        dao.insertSession(
            SessionEntity(
                key = "chat-20260628-214739",
                thinkingLevel = "off",
                gatewaySessionId = "d09b6359",
            ),
        )

        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(
            events = events.asSharedFlow(),
            scope = streamScope,
            chatDao = dao,
            json = json,
            flushInterval = 1L,
        )
        stream.setActiveSession("d09b6359")

        // MutableSharedFlow has replay = 0; the collector launched in
        // MessageStream's constructor needs a tick to subscribe before
        // we emit (otherwise the first events are dropped silently —
        // exactly the constructor-order constraint MessageStream.kt:32
        // warns about).
        delay(50)

        events.emit(GatewayEvent(type = "message.start", payload = buildJsonObject {}, sessionId = "d09b6359"))
        events.emit(GatewayEvent(type = "message.delta", payload = buildJsonObject {
            put("text", JsonPrimitive("the answer is "))
        }, sessionId = "d09b6359"))
        events.emit(GatewayEvent(type = "message.delta", payload = buildJsonObject {
            put("text", JsonPrimitive("42"))
        }, sessionId = "d09b6359"))
        events.emit(GatewayEvent(type = "message.complete", payload = buildJsonObject {
            put("text", JsonPrimitive("the answer is 42"))
        }, sessionId = "d09b6359"))

        // PersistenceCoordinator debounces at 200ms; message.complete
        // calls flushNow but it still hops through scope.launch + Room's
        // internal executor. 500ms is comfortably past both.
        delay(500)

        val rows = dao.getMessagesForSessionOnce("chat-20260628-214739")
        assertEquals(
            "expected exactly one assistant row under the LOCAL session key",
            1,
            rows.size,
        )
        val row = rows.single()
        assertEquals("assistant", row.role)
        assertTrue(
            "row content should contain the canonical text; was: ${row.contentJson}",
            row.contentJson.contains("the answer is 42"),
        )
        // Belt-and-braces: nothing got written under the gateway id.
        assertEquals(0, dao.getMessagesForSessionOnce("d09b6359").size)

        stream.close()
        streamScope.cancel()
    }

    /**
     * Negative case — when no session row carries the incoming gateway
     * id yet (race: stream lands before refreshSessions has reconciled),
     * the fix's "skipping" path must NOT crash and must NOT silently
     * write a row under the wrong key.
     */
    @Test
    fun `MessageStream skips flush when no local key maps to the gateway id`() = runBlocking {
        // No insertSession — the gateway id is unknown to Room.
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(
            events = events.asSharedFlow(),
            scope = streamScope,
            chatDao = dao,
            json = json,
            flushInterval = 1L,
        )
        stream.setActiveSession("orphan-gateway-id")
        delay(50)

        events.emit(GatewayEvent(type = "message.start", payload = buildJsonObject {}, sessionId = "orphan-gateway-id"))
        events.emit(GatewayEvent(type = "message.delta", payload = buildJsonObject {
            put("text", JsonPrimitive("dropped"))
        }, sessionId = "orphan-gateway-id"))
        events.emit(GatewayEvent(type = "message.complete", payload = buildJsonObject {
            put("text", JsonPrimitive("dropped"))
        }, sessionId = "orphan-gateway-id"))

        delay(500)

        // No FK crash, no orphan rows under either key.
        assertEquals(0, dao.getMessagesForSessionOnce("orphan-gateway-id").size)

        stream.close()
        streamScope.cancel()
    }

    // ── G1: gateway_events FK to sessions ──────────────────────────────────

    /**
     * ON UPDATE CASCADE: renaming the session propagates to gateway_events.
     * After the rename the ring-buffer row is visible under the new key and
     * absent under the old one.
     */
    @Test
    fun `gateway_events row's sessionKey CASCADE-updates on session rename`() = runBlocking {
        dao.insertSession(SessionEntity(key = "chat-old", thinkingLevel = "off"))
        dao.insertGatewayEvent(
            GatewayEventEntity(
                sessionKey = "chat-old",
                type = "message.start",
                payloadJson = "{}",
                receivedAtMs = 100L,
            ),
        )

        dao.renameSessionKey(oldKey = "chat-old", newKey = "stored-xyz")

        val eventsNew = dao.getGatewayEventsForSessionOnce("stored-xyz")
        assertEquals(1, eventsNew.size)
        assertEquals("stored-xyz", eventsNew.single().sessionKey)
        assertEquals(0, dao.getGatewayEventsForSessionOnce("chat-old").size)
    }

    /**
     * ON DELETE CASCADE: deleting the session removes its ring-buffer rows.
     */
    @Test
    fun `gateway_events rows are CASCADE-deleted with the session`() = runBlocking {
        dao.insertSession(SessionEntity(key = "chat-del", thinkingLevel = "off"))
        dao.insertGatewayEvent(
            GatewayEventEntity(
                sessionKey = "chat-del",
                type = "message.delta",
                payloadJson = """{"text":"hi"}""",
                receivedAtMs = 200L,
            ),
        )

        dao.deleteSession("chat-del")

        assertEquals(0, dao.getGatewayEventsForSessionOnce("chat-del").size)
    }

    /**
     * Null sessionKey inserts succeed — no parent row required (null FKs
     * are valid SQLite). Diagnostic events for unscoped/pre-session events
     * must still be recorded.
     */
    @Test
    fun `gateway_events insert with null sessionKey succeeds`() = runBlocking {
        dao.insertGatewayEvent(
            GatewayEventEntity(
                sessionKey = null,
                type = "gateway.ready",
                payloadJson = "{}",
                receivedAtMs = 50L,
            ),
        )

        val rows = dao.getGatewayEventsForSessionOnce(null)
        assertEquals(1, rows.size)
        assertNull(rows.single().sessionKey)
    }

    /**
     * Non-null sessionKey with no matching sessions row must trigger a FK
     * violation when inserted without conflict-ignore. This proves FK
     * enforcement is on (Room 2.6 default) and that the null-FK loophole
     * above doesn't accidentally suppress enforcement for non-null values.
     *
     * Uses a raw INSERT (no OR IGNORE) because [ChatDao.insertGatewayEvent]
     * uses OnConflictStrategy.IGNORE to swallow violations in production —
     * the production catch-site comment in MessageStream.recordToRingBuffer
     * explains why that's intentional. We bypass IGNORE here so the test
     * can verify the FK constraint itself fires at the SQLite layer.
     */
    @Test
    fun `gateway_events insert with non-existent sessionKey fails the FK`() {
        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO gateway_events (sessionKey, type, payloadJson, receivedAtMs) " +
                    "VALUES ('does-not-exist', 'message.start', '{}', 300)"
            )
            fail("expected FK violation, got silent success")
        } catch (t: Throwable) {
            assertTrue(
                "expected FOREIGN KEY constraint message; got: ${t.message}",
                t.message?.contains("FOREIGN KEY", ignoreCase = true) == true,
            )
        }
    }

    // ── K1 end-to-end: rename then stream against the new key ───────────────

    /**
     * The K1 (parity audit Option B) shape: a session is seeded with a
     * client-coined `chat-yyyymmdd-HHmmss` key. After `session.create`
     * returns a `stored_session_id`, the row is renamed in place (FK
     * CASCADE relabels children). Subsequent gateway events stamped
     * with the LIVE session_id (still resolved via gatewaySessionId)
     * land on the row keyed by the new stored id.
     *
     * Mirrors the FK-fix e2e shape above but with a rename in the
     * middle. Without ON UPDATE CASCADE on messages.sessionKey, the
     * rename would orphan or abort.
     */
    @Test
    fun `MessageStream flushes assistant message after K1 rename to stored_session_id`() = runBlocking {
        // Seed the session under a client-coined key, with the LIVE
        // gateway id stamped into gatewaySessionId.
        dao.insertSession(
            SessionEntity(
                key = "chat-20260628-214739",
                thinkingLevel = "off",
                gatewaySessionId = "live-d09b6359",
            ),
        )

        // Simulate K1: session.create returned stored_session_id =
        // "stored-abc123" → rename the local key to match. FK CASCADE
        // moves any existing children (none here yet) along with it.
        val renamed = dao.renameSessionKey(
            oldKey = "chat-20260628-214739",
            newKey = "stored-abc123",
        )
        assertEquals(1, renamed)

        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(
            events = events.asSharedFlow(),
            scope = streamScope,
            chatDao = dao,
            json = json,
            flushInterval = 1L,
        )
        stream.setActiveSession("live-d09b6359")
        delay(50)

        // Gateway events still carry the LIVE id — that's the orthogonal
        // mapping gatewaySessionId preserves. The FK fix's
        // resolveLocalKeyForGatewayId still resolves live → key, and the
        // key is now the stored id.
        events.emit(GatewayEvent(type = "message.start", payload = buildJsonObject {}, sessionId = "live-d09b6359"))
        events.emit(GatewayEvent(type = "message.delta", payload = buildJsonObject {
            put("text", JsonPrimitive("the answer is "))
        }, sessionId = "live-d09b6359"))
        events.emit(GatewayEvent(type = "message.delta", payload = buildJsonObject {
            put("text", JsonPrimitive("42"))
        }, sessionId = "live-d09b6359"))
        events.emit(GatewayEvent(type = "message.complete", payload = buildJsonObject {
            put("text", JsonPrimitive("the answer is 42"))
        }, sessionId = "live-d09b6359"))

        delay(500)

        val rows = dao.getMessagesForSessionOnce("stored-abc123")
        assertEquals(
            "assistant row lands under the post-rename (stored) key, not the original client-coined key",
            1,
            rows.size,
        )
        assertTrue(
            "row content carries the streamed text; was: ${rows.single().contentJson}",
            rows.single().contentJson.contains("the answer is 42"),
        )
        // The original client-coined key is gone.
        assertEquals(0, dao.getMessagesForSessionOnce("chat-20260628-214739").size)
        // Nothing got written under the live gateway id directly.
        assertEquals(0, dao.getMessagesForSessionOnce("live-d09b6359").size)

        stream.close()
        streamScope.cancel()
    }
}
