package app.marmalade.android.service

import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.chat.messages.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Voice reply-harvest correlation ([harvestVoiceReply]).
 *
 * The flagship voice surface waits for the assistant's reply to the turn it
 * just submitted, then speaks it. The regression this guards: a reconnect
 * mid-turn triggers a `session.subscribe` replay that re-emits OLD assistant
 * messages, and the previous bubble-count-delta harvest would fire on one of
 * them and speak a STALE reply. Correlating by seq (reply seq > the floor
 * captured before submit) makes replayed history inert.
 */
class HarvestVoiceReplyTest {

    private fun asst(
        seq: Long,
        text: String,
        pending: Boolean = false,
        error: String? = null,
        id: String = "a$seq",
    ) =
        ChatMessage(
            id = id,
            role = ChatRole.Assistant,
            parts = if (text.isEmpty()) emptyList() else listOf(ChatMessagePart.Text(text)),
            pending = pending,
            error = error,
            seq = seq,
        )

    private fun user(seq: Long, text: String, id: String = "u$seq") =
        ChatMessage(id = id, role = ChatRole.User, parts = listOf(ChatMessagePart.Text(text)), seq = seq)

    @Test
    fun `no reply yet returns null`() {
        assertNull(harvestVoiceReply(emptyList(), seqFloor = 0L))
    }

    @Test
    fun `a finalized reply above the floor is harvested`() {
        val msgs = listOf(user(11, "what's the weather"), asst(12, "Sunny and 20 degrees."))
        assertEquals("Sunny and 20 degrees.", harvestVoiceReply(msgs, seqFloor = 10L))
    }

    @Test
    fun `replayed old assistant messages never satisfy the wait`() {
        // The reconnect case: subscribe replays the whole prior history; every
        // replayed assistant message is at or below the floor captured before
        // this turn's submit. None may be spoken as "the reply".
        val replayedHistory = listOf(
            asst(3, "an answer from two turns ago"),
            user(4, "a later question"),
            asst(5, "the previous turn's answer"),
        )
        assertNull(harvestVoiceReply(replayedHistory, seqFloor = 5L))
    }

    @Test
    fun `the real reply is picked out from replayed history alongside it`() {
        // Replay re-emits old messages (<= floor) AND the genuine new reply
        // (> floor) lands in the same list. Only the new one is harvested.
        val msgs = listOf(
            asst(3, "stale answer A"),
            asst(5, "stale answer B"),
            user(11, "my new voice question"),
            asst(12, "the fresh reply"),
        )
        assertEquals("the fresh reply", harvestVoiceReply(msgs, seqFloor = 5L))
    }

    @Test
    fun `a mid-stream partial is not harvested - no half sentence to TTS`() {
        val msgs = listOf(user(11, "q"), asst(12, "half a sen", pending = true))
        assertNull(harvestVoiceReply(msgs, seqFloor = 10L))
    }

    @Test
    fun `a blank tool-only finalized assistant is skipped`() {
        // Agent turns can finalize an empty assistant message before the real
        // text arrives; harvesting it would end the wait with 0 chars.
        val msgs = listOf(user(11, "q"), asst(12, ""))
        assertNull(harvestVoiceReply(msgs, seqFloor = 10L))
    }

    @Test
    fun `the latest qualifying reply wins for a multi-message tool turn`() {
        // A tool-heavy turn finalizes narration, then the answer; the spoken
        // reply is the last finalized-with-text assistant bubble of the turn.
        val msgs = listOf(
            user(11, "book me a table"),
            asst(12, "Let me check availability."),
            asst(14, "Booked for 7pm."),
        )
        assertEquals("Booked for 7pm.", harvestVoiceReply(msgs, seqFloor = 10L))
    }

    @Test
    fun `a user message above the floor is ignored`() {
        assertNull(harvestVoiceReply(listOf(user(12, "just my own prompt")), seqFloor = 10L))
    }

    @Test
    fun `an errored turn is never spoken - truncated partial text is not the answer`() {
        // A server error mid-turn finalizes the bubble with whatever partial
        // text had streamed. Speaking half a sentence as if it answered the
        // question is worse than the error state.
        val msgs = listOf(
            user(11, "q"),
            asst(12, "I was about to sa", error = "provider crashed"),
        )
        assertNull(harvestVoiceReply(msgs, seqFloor = 10L))
    }

    @Test
    fun `an errored later segment does not shadow the turn's real earlier text`() {
        // lastOrNull semantics: the errored segment is skipped, not a wall —
        // the latest QUALIFYING segment of the turn still wins.
        val msgs = listOf(
            user(11, "q"),
            asst(12, "The answer is 42."),
            asst(14, "and furthermo", error = "stream aborted"),
        )
        assertEquals("The answer is 42.", harvestVoiceReply(msgs, seqFloor = 10L))
    }
}
