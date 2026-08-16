package app.marmalade.android.voice

import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.chat.messages.text

/**
 * Incremental sentence chunking for streaming voice replies.
 *
 * The voice popup used to wait for the turn's FINAL reply before speaking a
 * word, so a long answer sat in "Thinking…" for its entire generation. The
 * upstream desktop client instead speaks the response as it streams
 * (`use-voice-conversation.ts::takeSpeechChunk`); this is the Kotlin port of
 * that boundary logic, kept pure so digital-twin tests can drive it.
 *
 * Boundary rules (mirroring desktop):
 *  - Break after sentence enders. Western `.` `!` `?` only count when followed
 *    by whitespace — a trailing `.` on still-streaming text may be mid-number
 *    ("3.5") or mid-abbreviation, so it waits for the next delta to prove
 *    itself. CJK enders and newlines break unconditionally.
 *  - A chunk must be at least [MIN_CHUNK_CHARS] long — tiny fragments make the
 *    TTS engine stutter; a too-short sentence rides along with the next one.
 *  - Past [SOFT_BREAK_CHARS] with no sentence ender, break at the last
 *    comma-class pause instead (hard cut if there wasn't one) so an ender-free
 *    run can't grow unboundedly before speech starts.
 */
object VoiceSpeechChunker {
    const val MIN_CHUNK_CHARS = 8
    const val SOFT_BREAK_CHARS = 220

    private val HARD_ENDERS = setOf('。', '！', '？', '\n')
    private val WESTERN_ENDERS = setOf('.', '!', '?')
    private val SOFT_BREAKS = setOf(',', ';', ':', '、', '，', '；', '：')

    /**
     * Extract complete chunks from [text] starting at [offset] (chars already
     * taken by earlier calls). With [finalize] the tail past the last boundary
     * is flushed too — used once the message stops streaming.
     * Returns the chunks plus the new offset.
     */
    fun takeChunks(text: String, offset: Int, finalize: Boolean): Pair<List<String>, Int> {
        var pos = offset.coerceIn(0, text.length)
        val chunks = mutableListOf<String>()
        while (true) {
            val end = findChunkEnd(text, pos) ?: break
            val chunk = text.substring(pos, end).trim()
            if (chunk.isNotEmpty()) chunks.add(chunk)
            pos = end
        }
        if (finalize && pos < text.length) {
            val rest = text.substring(pos).trim()
            if (rest.isNotEmpty()) chunks.add(rest)
            pos = text.length
        }
        return chunks to pos
    }

    /** End index (exclusive) of the next complete chunk at [start], or null when none has closed yet. */
    private fun findChunkEnd(text: String, start: Int): Int? {
        var softBreak = -1
        var i = start
        while (i < text.length) {
            val c = text[i]
            val len = i + 1 - start
            when {
                c in HARD_ENDERS -> if (len >= MIN_CHUNK_CHARS) return i + 1
                c in WESTERN_ENDERS ->
                    if (i + 1 < text.length && text[i + 1].isWhitespace() && len >= MIN_CHUNK_CHARS) {
                        return i + 1
                    }
                c in SOFT_BREAKS -> softBreak = i + 1
            }
            if (len >= SOFT_BREAK_CHARS) {
                return if (softBreak - start >= MIN_CHUNK_CHARS) softBreak else i + 1
            }
            i++
        }
        return null
    }
}

/**
 * Walk THIS voice turn's assistant output and return the chunks that became
 * speakable since the last call, advancing [consumed] (message id → chars fed).
 *
 * Turn membership mirrors [app.marmalade.android.service.harvestVoiceReply]:
 * finalized assistant messages need `seq > seqFloor` (the prompt.submit ack's
 * server-minted seq) and no error. The still-streaming pending bubble carries
 * seq 0 until it completes, so it can't be floor-checked — [preexistingIds]
 * (every message id present at submit time) excludes a bubble carried over
 * from an earlier run instead. A tool-heavy turn finalizes several assistant
 * messages; all of them are spoken, in order, exactly as desktop speaks the
 * whole streamed response.
 *
 * The consumed offset survives the pending→finalized transition because the
 * daemon mints the message id once — the finalized row keeps the bubble's id,
 * so text spoken while streaming is never re-spoken from the canonical text
 * (which may only differ in tail bytes the deltas missed).
 */
internal fun collectSpeakableChunks(
    messages: List<ChatMessage>,
    seqFloor: Long,
    preexistingIds: Set<String>,
    consumed: MutableMap<String, Int>,
): List<String> {
    val out = mutableListOf<String>()
    for (msg in messages) {
        if (msg.role != ChatRole.Assistant) continue
        if (msg.id in preexistingIds) continue
        if (msg.error != null) continue
        if (msg.seq != 0L && msg.seq <= seqFloor) continue
        if (!msg.pending && msg.seq == 0L) continue // unacked local row — not server output
        val text = msg.text()
        val offset = consumed[msg.id] ?: 0
        val (chunks, newOffset) = VoiceSpeechChunker.takeChunks(text, offset, finalize = !msg.pending)
        consumed[msg.id] = newOffset
        out += chunks
    }
    return out
}
