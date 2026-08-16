package app.marmalade.android.ui.chat

import app.marmalade.android.chat.ChatAnchor
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.chat.messages.ChatRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Index math for "open the transcript at this message".
 *
 * The invariant under test: [anchorListIndex] must return the LazyColumn index
 * that [ChatMessageList] actually emits for the target row. Two things make
 * that non-obvious — the list is REVERSE-layout (index 0 is the visual bottom)
 * and one message can flatten into several rows (prose, tool cards, prompts).
 * Get it wrong and the jump lands on a neighbouring bubble with nothing
 * failing loudly, which is exactly why this is pinned here.
 *
 * The tests assert against the row model the list itself uses ([chatRows]),
 * since both now come from one function.
 */
class ChatAnchorIndexTest {

    private fun text(id: String, seq: Long, body: String = "body $id") = ChatMessage(
        id = id,
        role = ChatRole.Assistant,
        parts = listOf(ChatMessagePart.Text(body)),
        seq = seq,
        timestamp = seq * 1_000L,
    )

    private fun call(id: String) = ChatMessagePart.ToolCall(
        toolCallId = id,
        toolName = "read_file",
        args = JsonObject(emptyMap()),
        argsText = "{}",
        result = JsonObject(mapOf("ok" to JsonPrimitive(true))),
    )

    /** Prose → tool call → prose: three rows out of one message. */
    private fun multiRow(id: String, seq: Long) = ChatMessage(
        id = id,
        role = ChatRole.Assistant,
        parts = listOf(
            ChatMessagePart.Text("before"),
            call("$id-tool"),
            ChatMessagePart.Text("after"),
        ),
        seq = seq,
        timestamp = seq * 1_000L,
    )

    /**
     * The mapping the list performs, re-derived independently from the row
     * model: row i (forward order) is emitted at base + (rows.size - 1 - i).
     */
    private fun expectedIndexOfRow(
        messages: List<ChatMessage>,
        showActivityIndicator: Boolean,
        rowIndex: Int,
    ): Int {
        val rows = chatRows(messages)
        return (if (showActivityIndicator) 2 else 1) + (rows.size - 1 - rowIndex)
    }

    @Test
    fun `single-row messages map to reversed indices with the tail spacer offset`() {
        val msgs = (1..4).map { text("m$it", it.toLong()) }
        // Newest message (m4) is the last row → nearest the bottom → index 1
        // (0 is the tail spacer).
        assertEquals(1, anchorListIndex(msgs, showActivityIndicator = false, "m4"))
        assertEquals(2, anchorListIndex(msgs, showActivityIndicator = false, "m3"))
        assertEquals(4, anchorListIndex(msgs, showActivityIndicator = false, "m1"))
    }

    @Test
    fun `the activity indicator shifts every row by one`() {
        val msgs = (1..4).map { text("m$it", it.toLong()) }
        assertEquals(2, anchorListIndex(msgs, showActivityIndicator = true, "m4"))
        assertEquals(5, anchorListIndex(msgs, showActivityIndicator = true, "m1"))
    }

    @Test
    fun `a multi-row message anchors on its FIRST bubble, not its tool rows`() {
        val msgs = listOf(text("m1", 1), multiRow("m2", 2), text("m3", 3))
        val rows = chatRows(msgs)
        val firstBubbleRow = rows.indexOfFirst {
            it.message.id == "m2" && it.row is MessageRow.Bubble
        }
        assertTrue("precondition: m2 flattens into several rows", rows.count { it.message.id == "m2" } > 1)
        assertEquals(
            expectedIndexOfRow(msgs, showActivityIndicator = false, firstBubbleRow),
            anchorListIndex(msgs, showActivityIndicator = false, "m2"),
        )
    }

    @Test
    fun `a tool-only message still anchors — on its first row`() {
        val toolOnly = ChatMessage(
            id = "m2",
            role = ChatRole.Assistant,
            parts = listOf(call("t1"), call("t2"), call("t3")),
            seq = 2,
            timestamp = 2_000L,
        )
        val msgs = listOf(text("m1", 1), toolOnly, text("m3", 3))
        val rows = chatRows(msgs)
        val first = rows.indexOfFirst { it.message.id == "m2" }
        assertTrue("precondition: the tool-only message has no bubble row",
            rows.none { it.message.id == "m2" && it.row is MessageRow.Bubble })
        assertEquals(
            expectedIndexOfRow(msgs, showActivityIndicator = false, first),
            anchorListIndex(msgs, showActivityIndicator = false, "m2"),
        )
    }

    @Test
    fun `a message filtered out of the display list has no index`() {
        // What the show-thinking / show-tools toggles do: the target never
        // reaches ChatMessageList, so there is nothing to scroll to.
        val msgs = listOf(text("m1", 1), text("m3", 3))
        assertNull(anchorListIndex(msgs, showActivityIndicator = false, "m2"))
    }

    @Test
    fun `indices track the filtered list, not the unfiltered one`() {
        val all = (1..5).map { text("m$it", it.toLong()) }
        val filtered = all.filterNot { it.id == "m4" }
        assertEquals(1, anchorListIndex(filtered, showActivityIndicator = false, "m5"))
        // m3 is now adjacent to m5 in the filtered list.
        assertEquals(2, anchorListIndex(filtered, showActivityIndicator = false, "m3"))
    }

    @Test
    fun `an empty transcript has no index`() {
        assertNull(anchorListIndex(emptyList(), showActivityIndicator = false, "m1"))
    }

    // ── resolution ──────────────────────────────────────────────────────────

    @Test
    fun `messageId wins over seq when the row is present`() {
        val msgs = listOf(text("m1", 10), text("m2", 20), text("m3", 30))
        val target = resolveAnchorTarget(msgs, ChatAnchor("s", seq = 30, messageId = "m1"))
        assertEquals("m1", target?.id)
    }

    @Test
    fun `an unknown messageId falls back to the first message at or after seq`() {
        val msgs = listOf(text("m1", 10), text("m3", 30))
        val target = resolveAnchorTarget(msgs, ChatAnchor("s", seq = 20, messageId = "gone"))
        assertEquals("m3", target?.id)
    }

    @Test
    fun `a seq between two messages resolves to the later one`() {
        val msgs = listOf(text("m1", 10), text("m2", 20), text("m3", 30))
        assertEquals("m2", resolveAnchorTarget(msgs, ChatAnchor("s", seq = 15))?.id)
        assertEquals("m2", resolveAnchorTarget(msgs, ChatAnchor("s", seq = 20))?.id)
    }

    @Test
    fun `local-only rows never satisfy an anchor`() {
        // seq == 0 means "the server has not numbered this yet" (un-acked
        // outbox bubble, client chrome) — it carries no position to jump to.
        val msgs = listOf(text("outbox-1", 0))
        assertNull(resolveAnchorTarget(msgs, ChatAnchor("s", seq = 5)))
    }

    @Test
    fun `an anchor past the end of the transcript stays unresolved`() {
        val msgs = listOf(text("m1", 10), text("m2", 20))
        assertNull(resolveAnchorTarget(msgs, ChatAnchor("s", seq = 99)))
    }

    // ── the pill's distance ─────────────────────────────────────────────────

    @Test
    fun `messagesBackFrom counts messages to the end of the transcript`() {
        val msgs = (1..10).map { text("m$it", it.toLong()) }
        assertEquals(9, messagesBackFrom(msgs, "m1"))
        assertEquals(0, messagesBackFrom(msgs, "m10"))
        assertEquals(0, messagesBackFrom(msgs, "absent"))
    }

    @Test
    fun `pill copy is singular for one message`() {
        assertEquals("jumped 1 message back", anchorJumpPillText(1))
        assertEquals("jumped 74 messages back", anchorJumpPillText(74))
    }

    // ── the activity-indicator gate ─────────────────────────────────────────

    @Test
    fun `activity indicator gate matches the list's condition`() {
        val idle = listOf(text("m1", 1))
        val pending = listOf(text("m1", 1), text("m2", 2).copy(pending = true))
        assertTrue(showChatActivityIndicator(pending, isChatConnected = true, isStreaming = false))
        assertTrue(showChatActivityIndicator(idle, isChatConnected = true, isStreaming = true))
        // Disconnected drops the indicator even mid-turn — no spinner left
        // hanging after the socket dies.
        assertTrue(!showChatActivityIndicator(pending, isChatConnected = false, isStreaming = true))
        assertTrue(!showChatActivityIndicator(idle, isChatConnected = true, isStreaming = false))
    }
}
