package app.marmalade.android.ui.chat

import app.marmalade.android.chat.ChatAnchor
import app.marmalade.android.chat.ChatAnchorRequests
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.chat.messages.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Digital twin for the case the anchor exists to survive: **the target row is
 * not there yet.**
 *
 * Jumping into a session this device has never opened finds an empty Room
 * table. `session.resume` + `session.subscribe(since_seq)` then replay the
 * missing events over the socket and rows land in batches. A one-shot scroll
 * fired at mount would hit an empty list and be lost forever; the anchor is a
 * retained intent that resolves on whichever hydration batch contains the row.
 *
 * This test drives the same decision the chat screen's effect makes
 * (resolve → index → consume → scroll), once per simulated batch.
 */
class ChatAnchorPendingTest {

    private fun msg(id: String, seq: Long) = ChatMessage(
        id = id,
        role = ChatRole.Assistant,
        parts = listOf(ChatMessagePart.Text("body $id")),
        seq = seq,
        timestamp = seq * 1_000L,
    )

    /** One pass of ChatScreen's anchor effect. Returns the index it scrolled
     *  to, or null when the anchor stayed pending. */
    private fun applyOnce(
        requests: ChatAnchorRequests,
        messages: List<ChatMessage>,
        sessionKey: String = "s1",
    ): Int? {
        val pending = requests.anchor.value ?: return null
        if (pending.sessionKey != sessionKey) return null
        val target = resolveAnchorTarget(messages, pending) ?: return null
        val index = anchorListIndex(messages, showActivityIndicator = false, target.id)
            ?: return null
        requests.consume(pending)
        return index
    }

    @Test
    fun `an anchor stays pending until hydration delivers the row`() {
        val requests = ChatAnchorRequests()
        requests.request(ChatAnchor(sessionKey = "s1", seq = 30, messageId = "m3"))

        // Batch 0: Room is empty (never opened on this device).
        assertNull(applyOnce(requests, emptyList()))
        assertNotNull("anchor must survive an empty transcript", requests.anchor.value)

        // Batch 1: the replay starts landing, but not far enough yet.
        val batch1 = listOf(msg("m1", 10), msg("m2", 20))
        assertNull(applyOnce(requests, batch1))
        assertNotNull(requests.anchor.value)

        // Batch 2: the target arrives → jump, and the anchor is consumed.
        val batch2 = batch1 + listOf(msg("m3", 30), msg("m4", 40))
        assertEquals(2, applyOnce(requests, batch2))
        assertNull("anchor is one-shot", requests.anchor.value)

        // Batch 3: more replay arrives; nothing re-fires.
        assertNull(applyOnce(requests, batch2 + msg("m5", 50)))
    }

    @Test
    fun `re-requesting the same anchor jumps again`() {
        val requests = ChatAnchorRequests()
        val msgs = (1..5).map { msg("m$it", it * 10L) }
        val anchor = ChatAnchor(sessionKey = "s1", seq = 20, messageId = "m2")

        requests.request(anchor)
        assertEquals(4, applyOnce(requests, msgs))
        assertNull(requests.anchor.value)

        // Tapping the same search result a second time must work.
        requests.request(anchor)
        assertEquals(4, applyOnce(requests, msgs))
    }

    @Test
    fun `a newer request replaces a pending one`() {
        val requests = ChatAnchorRequests()
        requests.request(ChatAnchor("s1", seq = 10, messageId = "m1"))
        requests.request(ChatAnchor("s1", seq = 30, messageId = "m3"))
        val msgs = (1..3).map { msg("m$it", it * 10L) }
        // m3 is the newest message → nearest the bottom → index 1.
        assertEquals(1, applyOnce(requests, msgs))
    }

    @Test
    fun `consuming a stale anchor does not drop the one that replaced it`() {
        val requests = ChatAnchorRequests()
        val stale = ChatAnchor("s1", seq = 10, messageId = "m1")
        val fresh = ChatAnchor("s1", seq = 30, messageId = "m3")
        requests.request(stale)
        requests.request(fresh)
        requests.consume(stale)
        assertEquals(fresh, requests.anchor.value)
    }

    @Test
    fun `an anchor for another session never moves this transcript`() {
        val requests = ChatAnchorRequests()
        requests.request(ChatAnchor(sessionKey = "other", seq = 10, messageId = "m1"))
        val msgs = (1..3).map { msg("m$it", it * 10L) }
        assertNull(applyOnce(requests, msgs, sessionKey = "s1"))
        assertNotNull("it stays pending for the session it belongs to", requests.anchor.value)
    }

    @Test
    fun `a seq-only anchor resolves on the first message at or after it`() {
        val requests = ChatAnchorRequests()
        requests.request(ChatAnchor(sessionKey = "s1", seq = 25))
        val msgs = (1..4).map { msg("m$it", it * 10L) }
        // Resolves to m3 (seq 30): 4 messages, m4 at index 1, m3 at index 2.
        assertEquals(2, applyOnce(requests, msgs))
    }

    @Test
    fun `the query rides along untouched for the navigator`() {
        val requests = ChatAnchorRequests()
        requests.request(ChatAnchor("s1", seq = 10, messageId = "m1", query = "seen_at monotonic"))
        assertEquals("seen_at monotonic", requests.anchor.value?.query)
    }
}
