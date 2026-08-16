package app.marmalade.android.voice

import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.chat.messages.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming voice speech ([VoiceSpeechChunker] + [collectSpeakableChunks]).
 *
 * The voice popup speaks the reply while it streams (upstream
 * use-voice-conversation parity). The regressions these guard: splitting
 * mid-number ("3.5"), speaking a still-growing trailing sentence too early,
 * double-speaking text across the pending→finalized transition, and speaking
 * a pending bubble carried over from a previous run.
 */
class VoiceSpeechChunkerTest {

    // ── takeChunks ───────────────────────────────────────────────────────────

    @Test
    fun `no boundary yet emits nothing and keeps the offset`() {
        val (chunks, offset) = VoiceSpeechChunker.takeChunks("The weather today is", 0, finalize = false)
        assertEquals(emptyList<String>(), chunks)
        assertEquals(0, offset)
    }

    @Test
    fun `a completed sentence is emitted once the next char proves the boundary`() {
        val (chunks, offset) = VoiceSpeechChunker.takeChunks("Sunny and warm. The wind", 0, finalize = false)
        assertEquals(listOf("Sunny and warm."), chunks)
        assertEquals(15, offset) // consumed through the boundary; the space stays for the next chunk
    }

    @Test
    fun `a trailing period on streaming text is NOT a boundary`() {
        // The next delta could extend it ("...v1.2" → "...v1.2.3").
        val (chunks, _) = VoiceSpeechChunker.takeChunks("It is version 1.2.", 0, finalize = false)
        assertEquals(emptyList<String>(), chunks)
    }

    @Test
    fun `decimals do not split`() {
        val (chunks, _) = VoiceSpeechChunker.takeChunks("Pi is about 3.14159 which is handy. More", 0, finalize = false)
        assertEquals(listOf("Pi is about 3.14159 which is handy."), chunks)
    }

    @Test
    fun `finalize flushes the remainder`() {
        // "Done." is under MIN_CHUNK_CHARS, so the flush is one merged chunk.
        val (chunks, offset) = VoiceSpeechChunker.takeChunks("Done. And one more thing", 0, finalize = true)
        assertEquals(listOf("Done. And one more thing"), chunks)
        assertEquals(24, offset)
    }

    @Test
    fun `a too-short sentence rides along with the next one`() {
        val text = "Hi. It is sunny outside today. And"
        val (chunks, _) = VoiceSpeechChunker.takeChunks(text, 0, finalize = false)
        // "Hi." is under MIN_CHUNK_CHARS — merged into the next sentence.
        assertEquals(listOf("Hi. It is sunny outside today."), chunks)
    }

    @Test
    fun `newline is an unconditional boundary`() {
        val (chunks, _) = VoiceSpeechChunker.takeChunks("First line of the reply\nsecond", 0, finalize = false)
        assertEquals(listOf("First line of the reply"), chunks)
    }

    @Test
    fun `an ender-free run soft-breaks at the last comma past the limit`() {
        val head = "a".repeat(100) + ", " + "b".repeat(100)
        val text = head + ", " + "c".repeat(100)
        val (chunks, offset) = VoiceSpeechChunker.takeChunks(text, 0, finalize = false)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks[0].length <= VoiceSpeechChunker.SOFT_BREAK_CHARS)
        assertTrue(chunks[0].endsWith(","))
        assertTrue(offset > 0)
    }

    @Test
    fun `offsets accumulate across calls without re-emitting`() {
        val partial = "One done. Two done. Three is still strea"
        val (first, offset1) = VoiceSpeechChunker.takeChunks(partial, 0, finalize = false)
        assertEquals(listOf("One done.", "Two done."), first)
        val full = "One done. Two done. Three is still streaming done."
        val (second, offset2) = VoiceSpeechChunker.takeChunks(full, offset1, finalize = true)
        assertEquals(listOf("Three is still streaming done."), second)
        assertEquals(full.length, offset2)
    }

    // ── collectSpeakableChunks ───────────────────────────────────────────────

    private fun msg(
        id: String,
        text: String,
        pending: Boolean = false,
        seq: Long = 0L,
        role: ChatRole = ChatRole.Assistant,
        error: String? = null,
    ) = ChatMessage(
        id = id,
        role = role,
        parts = if (text.isEmpty()) emptyList() else listOf(ChatMessagePart.Text(text)),
        pending = pending,
        error = error,
        seq = seq,
    )

    @Test
    fun `streams the pending bubble sentence by sentence then flushes the finalized tail once`() {
        val consumed = mutableMapOf<String, Int>()
        val none = emptySet<String>()

        // Delta 1: no boundary yet.
        assertEquals(
            emptyList<String>(),
            collectSpeakableChunks(listOf(msg("m1", "The answer is", pending = true)), 10L, none, consumed),
        )
        // Delta 2: first sentence closes.
        assertEquals(
            listOf("The answer is forty-two."),
            collectSpeakableChunks(
                listOf(msg("m1", "The answer is forty-two. Because the", pending = true)),
                10L, none, consumed,
            ),
        )
        // Finalized (same id, canonical text, seq minted above floor): only the tail speaks.
        assertEquals(
            listOf("Because the question was mice."),
            collectSpeakableChunks(
                listOf(msg("m1", "The answer is forty-two. Because the question was mice.", seq = 12L)),
                10L, none, consumed,
            ),
        )
        // Idempotent: nothing new on a re-collect.
        assertEquals(
            emptyList<String>(),
            collectSpeakableChunks(
                listOf(msg("m1", "The answer is forty-two. Because the question was mice.", seq = 12L)),
                10L, none, consumed,
            ),
        )
    }

    @Test
    fun `messages at or below the seq floor are never spoken`() {
        val consumed = mutableMapOf<String, Int>()
        val history = listOf(
            msg("old1", "An answer from two turns ago.", seq = 5L),
            msg("old2", "The previous reply.", seq = 9L),
        )
        assertEquals(emptyList<String>(), collectSpeakableChunks(history, 9L, emptySet(), consumed))
    }

    @Test
    fun `a pending bubble carried over from a previous run is excluded by id`() {
        val consumed = mutableMapOf<String, Int>()
        val carried = msg("stale", "A long-running background answer. Still going.", pending = true)
        assertEquals(
            emptyList<String>(),
            collectSpeakableChunks(listOf(carried), 10L, setOf("stale"), consumed),
        )
    }

    @Test
    fun `errored and user messages are skipped`() {
        val consumed = mutableMapOf<String, Int>()
        val msgs = listOf(
            msg("u1", "the question. asked out loud.", seq = 11L, role = ChatRole.User),
            msg("e1", "a truncated partial before the error.", seq = 12L, error = "boom"),
        )
        assertEquals(emptyList<String>(), collectSpeakableChunks(msgs, 10L, emptySet(), consumed))
    }

    @Test
    fun `a tool-heavy turn speaks each finalized message in order`() {
        val consumed = mutableMapOf<String, Int>()
        val msgs = listOf(
            msg("n1", "Checking the calendar now.", seq = 12L),
            msg("n2", "You are free at three o'clock.", seq = 14L),
        )
        assertEquals(
            listOf("Checking the calendar now.", "You are free at three o'clock."),
            collectSpeakableChunks(msgs, 10L, emptySet(), consumed),
        )
    }

    // ── optimistic pre-ack feeding (floor = Long.MAX_VALUE until the ack) ────

    @Test
    fun `pre-ack floor speaks the live pending bubble but blocks every finalized row`() {
        val consumed = mutableMapOf<String, Int>()
        val msgs = listOf(
            // Reconnect replay: finalized, above any local max, NOT preexisting
            // — the exact row the ack floor exists to keep silent.
            msg("replayed", "An old reply replayed from the server. Stale.", seq = 99L),
            msg("m1", "The first sentence closed here. And the", pending = true),
        )
        assertEquals(
            listOf("The first sentence closed here."),
            collectSpeakableChunks(msgs, Long.MAX_VALUE, emptySet(), consumed),
        )
    }

    @Test
    fun `ack re-anchor speaks the finalized tail without re-speaking pre-ack chunks`() {
        val consumed = mutableMapOf<String, Int>()
        // Pre-ack: streaming bubble speaks its first sentence under MAX_VALUE.
        collectSpeakableChunks(
            listOf(msg("m1", "The answer is forty-two. Because the", pending = true)),
            Long.MAX_VALUE, emptySet(), consumed,
        )
        // Ack lands (floor 10); the row finalizes above it. Same consumed map:
        // only the unspoken tail comes out.
        assertEquals(
            listOf("Because the question was mice."),
            collectSpeakableChunks(
                listOf(msg("m1", "The answer is forty-two. Because the question was mice.", seq = 12L)),
                10L, emptySet(), consumed,
            ),
        )
    }

    @Test
    fun `a replayed row stays silent through the re-anchor when it predates the floor`() {
        val consumed = mutableMapOf<String, Int>()
        val replayed = msg("replayed", "An old reply replayed from the server. Stale.", seq = 8L)
        // Blocked pre-ack by MAX_VALUE...
        assertEquals(
            emptyList<String>(),
            collectSpeakableChunks(listOf(replayed), Long.MAX_VALUE, emptySet(), consumed),
        )
        // ...and still blocked once the real floor (10) arrives.
        assertEquals(
            emptyList<String>(),
            collectSpeakableChunks(listOf(replayed), 10L, emptySet(), consumed),
        )
    }

    @Test
    fun `canonical final text shorter than the streamed offset does not crash or re-speak`() {
        val consumed = mutableMapOf<String, Int>()
        collectSpeakableChunks(
            listOf(msg("m1", "A sentence that streamed fully. And more trailing text", pending = true)),
            10L, emptySet(), consumed,
        )
        // Server canonical text dropped the tail bytes.
        val chunks = collectSpeakableChunks(
            listOf(msg("m1", "A sentence that streamed fully.", seq = 12L)),
            10L, emptySet(), consumed,
        )
        assertEquals(emptyList<String>(), chunks)
    }
}
