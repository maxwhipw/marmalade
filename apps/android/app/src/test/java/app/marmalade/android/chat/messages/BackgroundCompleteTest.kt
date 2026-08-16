package app.marmalade.android.chat.messages

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room tests for the `background.complete` event handler (parity row E4).
 *
 * Uses the same Robolectric + inMemoryDatabaseBuilder + runBlocking + delay
 * harness established in [ChatDaoRoomFkTest], because the handler runs on
 * ioDispatcher and persists via ChatDao — only real Room can prove the row
 * lands (FakeChatDao silently accepts rows with no FK enforcement).
 *
 * The handler is in ChatController.handleEvent; these tests drive it by
 * emitting events into FakeMarmaladeRpc and observing the messages table.
 *
 * Design decisions documented here:
 *
 *  - **Empty text:** still inserts the row. Desktop does the same — the sys
 *    line `[bg <id>]` is useful metadata even when the task produced no
 *    text. Stripping the row on empty text would silently drop completions.
 *  - **Empty task_id:** log + skip, no row inserted. Without an id the sys
 *    line is ambiguous and useless; consistent with desktop not emitting
 *    `background.complete` without an id.
 *  - **Unknown session (resolveLocalKeyForGatewayId returns null):** log +
 *    skip, no crash. The race (stream lands before refreshSessions has
 *    reconciled) is the same race MessageStream already tolerates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundCompleteTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao
    private lateinit var scope: CoroutineScope
    private lateinit var rpc: FakeMarmaladeRpc
    private lateinit var controller: ChatController
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(ctx)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()

        scope = CoroutineScope(SupervisorJob())
        rpc = FakeMarmaladeRpc()

        val stream = MessageStream(
            events = rpc.rpcClient.events,
            scope = scope,
            chatDao = dao,
            json = json,
            flushInterval = 1L,
        )
        val drainer = OutboxDrainer(
            chatDao = dao,
            transport = marmaladeRpcAdapter(rpc),
            scope = scope,
            persistence = stream.persistence,
        )
        controller = ChatController(
            scope = scope,
            rpc = rpc,
            messageStream = stream,
            outboxDrainer = drainer,
            json = json,
            chatDao = dao,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    // ── 1. Valid stamped event ──────────────────────────────────────────────

    /**
     * Happy path: event carries a session_id that resolves to a known local
     * session. Verifies the row lands with role="system" and content matches
     * the desktop-parity format `[bg <task_id>] <text>`.
     */
    @Test
    fun `valid stamped event inserts system row with correct content`() = runBlocking {
        dao.insertSession(
            SessionEntity(
                key = "chat-20260629-100000",
                thinkingLevel = "off",
                gatewaySessionId = "gw-session-abc",
            ),
        )

        // delay(50) — collector race: MutableSharedFlow with replay=0 needs the
        // subscriber launched in MessageStream's constructor to be hot before we emit.
        delay(50)

        rpc.emit(
            GatewayEvent(
                type = "background.complete",
                payload = buildJsonObject {
                    put("task_id", JsonPrimitive("task-001"))
                    put("text", JsonPrimitive("image resized"))
                },
                sessionId = "gw-session-abc",
            ),
        )

        // Handler launches on ioDispatcher → give it time to execute + Room to commit.
        delay(200)

        val rows = dao.getMessagesForSessionOnce("chat-20260629-100000")
        assertEquals("expected exactly one system row", 1, rows.size)
        val row = rows.single()
        assertEquals("system", row.role)
        assertTrue(
            "content must contain '[bg task-001] image resized'; was: ${row.contentJson}",
            row.contentJson.contains("[bg task-001] image resized"),
        )
        // Nothing landed under the gateway id directly.
        assertEquals(0, dao.getMessagesForSessionOnce("gw-session-abc").size)
    }

    // ── 2. Unstamped event (null sessionId) ────────────────────────────────

    /**
     * When the event has no session_id, the handler falls back to the
     * controller's bound session (_sessionKey.value, default "main").
     * Verifies the row lands keyed to the bound session.
     */
    @Test
    fun `unstamped event inserts system row keyed to the bound session`() = runBlocking {
        // "main" is _sessionKey's initial value in ChatController.
        dao.insertSession(
            SessionEntity(key = "main", thinkingLevel = "off"),
        )

        delay(50)

        rpc.emit(
            GatewayEvent(
                type = "background.complete",
                payload = buildJsonObject {
                    put("task_id", JsonPrimitive("task-unstamped"))
                    put("text", JsonPrimitive("done"))
                },
                sessionId = null,
            ),
        )

        delay(200)

        val rows = dao.getMessagesForSessionOnce("main")
        assertEquals("expected exactly one system row under 'main'", 1, rows.size)
        assertTrue(
            "content contains the task id; was: ${rows.single().contentJson}",
            rows.single().contentJson.contains("[bg task-unstamped] done"),
        )
    }

    // ── 3. Unknown session (no matching local key) ──────────────────────────

    /**
     * When resolveLocalKeyForGatewayId returns null (gateway id unknown to
     * Room), the handler must log + skip — no row inserted, no crash.
     *
     * This is the same race MessageStream tolerates: the stream lands before
     * refreshSessions has reconciled the new gateway id into Room.
     */
    @Test
    fun `stamped event with unknown session inserts no row and does not crash`() = runBlocking {
        // No insertSession — the gateway id is not in Room.
        delay(50)

        rpc.emit(
            GatewayEvent(
                type = "background.complete",
                payload = buildJsonObject {
                    put("task_id", JsonPrimitive("task-orphan"))
                    put("text", JsonPrimitive("some output"))
                },
                sessionId = "gw-never-seen",
            ),
        )

        delay(200)

        // No FK crash, no row under any key.
        assertEquals(0, dao.getMessagesForSessionOnce("gw-never-seen").size)
    }

    // ── 4. Empty text ───────────────────────────────────────────────────────

    /**
     * An event with empty text still inserts the row. Desktop does the same —
     * `[bg <id>]` with no trailing text is still useful metadata (the task
     * completed, just produced no output). Stripping the row would silently
     * swallow the completion event.
     */
    @Test
    fun `empty text still inserts the system row`() = runBlocking {
        dao.insertSession(
            SessionEntity(
                key = "chat-20260629-110000",
                thinkingLevel = "off",
                gatewaySessionId = "gw-session-empty-text",
            ),
        )

        delay(50)

        rpc.emit(
            GatewayEvent(
                type = "background.complete",
                payload = buildJsonObject {
                    put("task_id", JsonPrimitive("task-no-output"))
                    put("text", JsonPrimitive(""))
                },
                sessionId = "gw-session-empty-text",
            ),
        )

        delay(200)

        val rows = dao.getMessagesForSessionOnce("chat-20260629-110000")
        assertEquals("row inserted even for empty text", 1, rows.size)
        assertEquals("system", rows.single().role)
        // Content is `[bg task-no-output] ` — trailing space from empty text is acceptable.
        assertTrue(
            "content contains the task id; was: ${rows.single().contentJson}",
            rows.single().contentJson.contains("[bg task-no-output]"),
        )
    }

    // ── 5. Empty task_id ───────────────────────────────────────────────────

    /**
     * An event with an empty task_id must log + skip — no row inserted.
     * Without an id the sys line `[bg ] some text` is ambiguous and useless,
     * and there is no surface to display it on.
     */
    @Test
    fun `empty task_id skips insert and does not crash`() = runBlocking {
        dao.insertSession(
            SessionEntity(
                key = "chat-20260629-120000",
                thinkingLevel = "off",
                gatewaySessionId = "gw-session-empty-id",
            ),
        )

        delay(50)

        rpc.emit(
            GatewayEvent(
                type = "background.complete",
                payload = buildJsonObject {
                    put("task_id", JsonPrimitive(""))
                    put("text", JsonPrimitive("some output"))
                },
                sessionId = "gw-session-empty-id",
            ),
        )

        delay(200)

        // No row should be inserted — empty task_id triggers early return.
        assertEquals(0, dao.getMessagesForSessionOnce("chat-20260629-120000").size)
    }
}
