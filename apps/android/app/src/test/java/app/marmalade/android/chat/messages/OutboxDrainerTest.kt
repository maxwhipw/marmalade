package app.marmalade.android.chat.messages

import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario tests for [OutboxDrainer] wired against [FakeChatDao] and
 * [FakePromptTransport]. Asserts on observable state — outbox + messages
 * + recorded transport calls — not on internal coroutine timing.
 *
 * Pattern borrowed from the OLD marmalade-android (commit eb73674):
 *   1. Seed the DAO with sessions + outbox rows.
 *   2. Construct OutboxDrainer with fakes + start().
 *   3. Drive transport state transitions / advance time.
 *   4. Assert on dao.snapshot() + transport.submitCalls.
 */
class OutboxDrainerTest {

    private fun TestSetup() = object {
        val dao = FakeChatDao()
        val transport = FakePromptTransport()
        val now = AtomicNow(initial = 1_000L)
    }

    private class AtomicNow(initial: Long) {
        @Volatile var value: Long = initial
        operator fun invoke(): Long = value
    }

    private suspend fun seedSession(dao: FakeChatDao) {
        dao.insertSession(SessionEntity(key = "main", thinkingLevel = "off"))
    }

    private fun outbox(
        id: String,
        sessionKey: String = "main",
        serverSessionId: String? = "main",
        createdAtMs: Long = 1_000L,
        ordinal: Long = 1L,
        text: String = "hi",
    ) = OutboxEntity(
        id = id,
        sessionKey = sessionKey,
        serverSessionId = serverSessionId,
        contentJson = """[{"type":"text","text":"$text"}]""",
        createdAtMs = createdAtMs,
        clientOrdinal = ordinal,
    )

    // ──────────────────────────────────────────────────────────────────────
    // Wake on Open + drain happy path
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `drainer fires submitPrompt and acks when connection opens with pending row`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outbox("o-1", text = "hello"))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()

        // No drain until Open.
        runCurrent()
        assertEquals(0, t.transport.submitCalls.size)

        t.transport.open()
        advanceTimeBy(100)  // wait past the 50ms debounce
        runCurrent()

        assertEquals("drainer fired the RPC", 1, t.transport.submitCalls.size)
        assertEquals("submitted text matches", "hello", t.transport.submitCalls.first().text)
        assertEquals("idempotency_key = outbox.id", "o-1", t.transport.submitCalls.first().idempotencyKey)

        val outboxAfter = t.dao.getOutboxForSessionOnce("main")
        val messagesAfter = t.dao.getMessagesForSessionOnce("main")
        assertTrue("outbox row acked + removed", outboxAfter.isEmpty())
        assertEquals("messages row inserted with outbox id", listOf("o-1"), messagesAfter.map { it.id })
    }

    @Test
    fun `drainer skips rows with null serverSessionId`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outbox("o-1", serverSessionId = null))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()
        runCurrent()  // let launchIn / scope.launch register their collectors
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("drainer skipped the bootstrap-incomplete row", 0, t.transport.submitCalls.size)
        assertEquals("row stays in outbox", 1, t.dao.getOutboxForSessionOnce("main").size)
    }

    @Test
    fun `drainer drains rows in createdAtMs order`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outbox("o-second", createdAtMs = 2_000L, ordinal = 2L, text = "second"))
        t.dao.insertOutbox(outbox("o-first", createdAtMs = 1_000L, ordinal = 1L, text = "first"))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()
        runCurrent()  // let launchIn / scope.launch register their collectors
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        val callTexts = t.transport.submitCalls.map { it.text }
        assertEquals(listOf("first", "second"), callTexts)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Backoff on failure
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `drainer backs off after RPC failure and does not retry immediately`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outbox("o-1"))
        t.transport.queueFailure("network")

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()
        runCurrent()  // let launchIn / scope.launch register their collectors
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("attempted once", 1, t.transport.submitCalls.size)
        val row = t.dao.getOutboxByIdOnce("o-1")
        assertEquals("row demoted back to pending for retry", "pending", row?.status)
        assertEquals("attemptCount incremented", 1, row?.attemptCount)
        assertTrue("nextAttemptAtMs is in the future", (row?.nextAttemptAtMs ?: 0L) > t.now())
    }

    @Test
    fun `drainer marks failed after seven consecutive failures`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outbox("o-1"))
        // Pre-fail 6 attempts via direct DAO + then drain the 7th.
        t.dao.updateOutboxAttempt(
            id = "o-1", status = "pending",
            errorMsg = "network",
            attempts = 6, nextAttempt = 0L,
        )
        t.transport.queueFailure("network")

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()
        runCurrent()  // let launchIn / scope.launch register their collectors
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        val row = t.dao.getOutboxByIdOnce("o-1")
        assertEquals("attempt 7+ transitions to failed", "failed", row?.status)
        assertEquals("attemptCount at MAX_ATTEMPTS", 7, row?.attemptCount)
    }

    @Test
    fun `drainer marks row failed when content is blank`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outbox("o-1", text = "").copy(
            contentJson = "[]",
        ))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()
        runCurrent()  // let launchIn / scope.launch register their collectors
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("no RPC fired for blank content", 0, t.transport.submitCalls.size)
        assertEquals("failed", t.dao.getOutboxByIdOnce("o-1")?.status)
    }

    // ──────────────────────────────────────────────────────────────────────
    // poke + in-flight set
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `poke wakes the drainer between Open edges`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()
        runCurrent()
        t.transport.open()
        // Drain the initial Open-driven trigger so it doesn't race the poke
        // we're trying to test.
        advanceTimeBy(100)
        runCurrent()
        assertEquals("nothing to drain initially", 0, t.transport.submitCalls.size)

        // ChatController.sendMessage path: insert row + poke.
        t.dao.insertOutbox(outbox("o-1", text = "fresh"))
        drainer.poke()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("drainer picked up the freshly enqueued row", 1, t.transport.submitCalls.size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Boot recovery integration
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `boot recovery + drainer re-sends a 'sending' row from the previous process`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        // Previous process left this row in 'sending' (was mid-RPC when killed).
        val staleSending = outbox("o-stale")
        t.dao.insertOutbox(staleSending)
        t.dao.markOutboxSending("o-stale")
        assertEquals("sending", t.dao.getOutboxByIdOnce("o-stale")?.status)

        // Boot recovery (production: AppDatabase.onOpen).
        t.dao.demoteStaleSendingOutbox()
        assertEquals("pending", t.dao.getOutboxByIdOnce("o-stale")?.status)

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()
        runCurrent()  // let launchIn / scope.launch register their collectors
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("stale row re-sent after boot recovery", 1, t.transport.submitCalls.size)
        assertNull("ack moved it out of outbox", t.dao.getOutboxByIdOnce("o-stale"))
        assertEquals(listOf("o-stale"), t.dao.getMessagesForSessionOnce("main").map { it.id })
    }

    // ──────────────────────────────────────────────────────────────────────
    // Voice-origin propagation (source="voice" wire param)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `drainer sends source=voice for voice-origin outbox row`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outbox("o-voice").copy(voiceOrigin = true))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()
        runCurrent()
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, t.transport.submitCalls.size)
        assertEquals(
            "voice-origin row must send source=voice so the gateway prepends VOICE_TURN_PREFIX",
            "voice",
            t.transport.submitCalls.first().source,
        )
    }

    @Test
    fun `drainer omits source for regular (text) outbox row`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outbox("o-text"))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
        )
        drainer.start()
        runCurrent()
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, t.transport.submitCalls.size)
        assertNull(
            "text-origin rows must NOT send source= so the gateway takes the default text path",
            t.transport.submitCalls.first().source,
        )
    }
}
