package app.marmalade.android.chat.messages

import app.marmalade.android.chat.OutgoingAttachment
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attachment upload behavior of [OutboxDrainer]: images route through
 * `image.attach_bytes`, files through `file.attach` with their `@file:` ref
 * prepended to the prompt, uploads dedupe across retries, and the outbox
 * row's contentJson is rewritten to the final submitted text so the acked
 * message content-matches server history (reconcileHistory invariant).
 */
class OutboxAttachmentTest {

    private class AtomicNow(initial: Long) {
        @Volatile var value: Long = initial
        operator fun invoke(): Long = value
    }

    private fun TestSetup() = object {
        val dao = FakeChatDao()
        val transport = FakePromptTransport()
        val now = AtomicNow(initial = 1_000L)
        /** path → deterministic fake bytes, so tests can assert the exact base64. */
        val fileBytes: (String) -> ByteArray = { path -> "bytes-of:$path".toByteArray() }
    }

    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString("bytes-of:$s".toByteArray())

    private suspend fun seedSession(dao: FakeChatDao) {
        dao.insertSession(SessionEntity(key = "main", thinkingLevel = "off"))
    }

    private fun image(path: String, name: String = "photo.jpg") = OutgoingAttachment(
        kind = OutgoingAttachment.KIND_IMAGE,
        name = name,
        mimeType = "image/jpeg",
        path = path,
        sizeBytes = 10L,
    )

    private fun file(path: String, name: String = "doc.txt") = OutgoingAttachment(
        kind = OutgoingAttachment.KIND_FILE,
        name = name,
        mimeType = "text/plain",
        path = path,
        sizeBytes = 10L,
    )

    private fun outboxRow(
        id: String,
        text: String,
        attachments: List<OutgoingAttachment>,
    ) = OutboxEntity(
        id = id,
        sessionKey = "main",
        serverSessionId = "main",
        contentJson = if (text.isEmpty()) "[]" else """[{"type":"text","text":"$text"}]""",
        attachmentsJson = encodeAttachments(attachments),
        createdAtMs = 1_000L,
        clientOrdinal = 1L,
    )

    @Test
    fun `image attachment uploads bytes then submits with original text`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outboxRow("o-1", text = "look at this", attachments = listOf(image("/tmp/a.jpg"))))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
            readFileBytes = t.fileBytes,
        )
        drainer.start()
        runCurrent() // subscribe trigger collectors before the Open edge
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, t.transport.imageAttachCalls.size)
        val attach = t.transport.imageAttachCalls.first()
        assertEquals("main", attach.sessionId)
        assertEquals("photo.jpg", attach.filename)
        assertEquals(b64("/tmp/a.jpg"), attach.contentBase64)

        assertEquals(1, t.transport.submitCalls.size)
        assertEquals("image adds no ref text", "look at this", t.transport.submitCalls.first().text)

        assertTrue("row acked", t.dao.getOutboxForSessionOnce("main").isEmpty())
        assertEquals(listOf("o-1"), t.dao.getMessagesForSessionOnce("main").map { it.id })
    }

    @Test
    fun `file attachment prepends ref text and rewrites the acked bubble`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outboxRow("o-1", text = "summarize this", attachments = listOf(file("/tmp/doc.txt"))))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
            readFileBytes = t.fileBytes,
        )
        drainer.start()
        runCurrent() // subscribe trigger collectors before the Open edge
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, t.transport.fileAttachCalls.size)
        val attach = t.transport.fileAttachCalls.first()
        assertEquals("data:text/plain;base64," + b64("/tmp/doc.txt"), attach.dataUrl)

        val expectedText = "@file:doc.txt\n\nsummarize this"
        assertEquals(expectedText, t.transport.submitCalls.single().text)

        // The acked message's text part carries the submitted text so
        // reconcileHistory's content signature matches server history.
        val message = t.dao.getMessagesForSessionOnce("main").single()
        assertTrue(message.contentJson.contains("@file:doc.txt\\n\\nsummarize this"))
    }

    @Test
    fun `image-only send falls back to the desktop vision prompt`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outboxRow("o-1", text = "", attachments = listOf(image("/tmp/a.jpg"))))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
            readFileBytes = t.fileBytes,
        )
        drainer.start()
        runCurrent() // subscribe trigger collectors before the Open edge
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(IMAGE_ONLY_FALLBACK_PROMPT, t.transport.submitCalls.single().text)
        val message = t.dao.getMessagesForSessionOnce("main").single()
        assertTrue(
            "fallback text part inserted into the bubble",
            message.contentJson.contains(IMAGE_ONLY_FALLBACK_PROMPT),
        )
    }

    @Test
    fun `retry after failed submit skips re-upload and does not double-prepend refs`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(
            outboxRow(
                "o-1", text = "hello",
                attachments = listOf(image("/tmp/a.jpg"), file("/tmp/doc.txt")),
            ),
        )

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
            readFileBytes = t.fileBytes,
        )
        drainer.start()
        runCurrent() // subscribe trigger collectors before the Open edge
        t.transport.queueFailure("network blip")
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, t.transport.submitCalls.size)
        assertEquals("row survives the failed submit", 1, t.dao.getOutboxForSessionOnce("main").size)

        // Let the backoff window pass and re-drain.
        t.now.value = 60_000L
        drainer.poke()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("submit retried", 2, t.transport.submitCalls.size)
        assertEquals("image uploaded exactly once", 1, t.transport.imageAttachCalls.size)
        assertEquals("file uploaded exactly once", 1, t.transport.fileAttachCalls.size)
        assertEquals(
            "refs not duplicated on the retried submit",
            t.transport.submitCalls[0].text,
            t.transport.submitCalls[1].text,
        )
        assertTrue("row acked after retry", t.dao.getOutboxForSessionOnce("main").isEmpty())
    }

    @Test
    fun `attach failure backs off without submitting`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        t.dao.insertOutbox(outboxRow("o-1", text = "hi", attachments = listOf(image("/tmp/a.jpg"))))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
            readFileBytes = t.fileBytes,
        )
        drainer.start()
        runCurrent() // subscribe trigger collectors before the Open edge
        t.transport.attachFailure = "gateway down"
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("no submit after failed attach", 0, t.transport.submitCalls.size)
        val row = t.dao.getOutboxForSessionOnce("main").single()
        assertEquals("pending", row.status)
        assertEquals(1, row.attemptCount)
        assertTrue(row.nextAttemptAtMs > 0L)
    }

    @Test
    fun `image re-uploads when the live session id rotated`() = runTest {
        val t = TestSetup()
        seedSession(t.dao)
        // Simulate an attachment already uploaded to a PREVIOUS live sid: the
        // gateway restarted, so its in-memory image queue is gone.
        val stale = image("/tmp/a.jpg").copy(attachedSessionId = "old-sid")
        t.dao.insertOutbox(outboxRow("o-1", text = "hi", attachments = listOf(stale)))

        val drainer = OutboxDrainer(
            chatDao = t.dao,
            transport = t.transport,
            scope = backgroundScope,
            persistence = PersistenceCoordinator(scope = backgroundScope, flush = {}),
            now = { t.now() },
            readFileBytes = t.fileBytes,
        )
        drainer.start()
        runCurrent() // subscribe trigger collectors before the Open edge
        t.transport.open()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("re-uploaded to the current sid", 1, t.transport.imageAttachCalls.size)
        assertEquals("main", t.transport.imageAttachCalls.single().sessionId)
        assertFalse(t.dao.getMessagesForSessionOnce("main").isEmpty())
    }

    // ── Pure helpers ────────────────────────────────────────────────────────

    @Test
    fun `buildSubmitText is idempotent when refs are already present`() {
        val att = file("/tmp/doc.txt").copy(refText = "@file:doc.txt")
        val first = buildSubmitText("hello", listOf(att))
        assertEquals("@file:doc.txt\n\nhello", first)
        assertEquals("second pass adds nothing", first, buildSubmitText(first, listOf(att)))
    }

    @Test
    fun `buildSubmitText handles files without text`() {
        val att = file("/tmp/doc.txt").copy(refText = "@file:doc.txt")
        assertEquals("@file:doc.txt", buildSubmitText("", listOf(att)))
    }

    @Test
    fun `rewriteTextPart preserves image and file parts`() {
        val content = """[{"type":"text","text":"old"},{"type":"image","image":"file:///tmp/a.jpg"}]"""
        val rewritten = rewriteTextPart(content, "new text")
        assertTrue(rewritten.contains(""""text":"new text""""))
        assertTrue(rewritten.contains("file:///tmp/a.jpg"))
        assertFalse(rewritten.contains(""""text":"old""""))
    }

    @Test
    fun `rewriteTextPart prepends a text part when none exists`() {
        val content = """[{"type":"image","image":"file:///tmp/a.jpg"}]"""
        val rewritten = rewriteTextPart(content, IMAGE_ONLY_FALLBACK_PROMPT)
        assertTrue(rewritten.startsWith("""[{"type":"text""""))
        assertTrue(rewritten.contains("file:///tmp/a.jpg"))
    }

    @Test
    fun `attachments json round-trips upload state`() {
        val atts = listOf(
            image("/tmp/a.jpg").copy(attachedSessionId = "sid-1"),
            file("/tmp/doc.txt").copy(refText = "@file:doc.txt", attachedSessionId = "sid-1"),
        )
        assertEquals(atts, decodeAttachments(encodeAttachments(atts)))
    }
}
