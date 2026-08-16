package app.marmalade.android.chat.messages

/**
 * Result of [appendStreamPart]: the updated parts list AND the index of the
 * part that received (or now holds) the appended delta. Some callers — like
 * the assistant-text MEDIA-link renderer in a future commit — need the index
 * to do a follow-up replacement.
 */
data class StreamAppendResult(val index: Int, val parts: List<ChatMessagePart>)

/**
 * Streaming-channel kinds that coalesce. The only two streaming part types
 * today; if a third ever shows up, extend this enum and the lookup in
 * [makeStreamPart].
 */
enum class StreamKind { Text, Reasoning }

/**
 * Append a streaming delta to the most-recent same-type part within the
 * current *segment*. Faithful port of desktop's `appendStreamPart` at
 * `hermes-agent upstream: apps/desktop/src/lib/chat-messages.ts:193`.
 *
 * A segment is bounded by any non-streaming part — [ChatMessagePart.ToolCall],
 * [ChatMessagePart.Image], [ChatMessagePart.File]. Within a segment, the
 * opposite streaming channel ([StreamKind.Text] ↔ [StreamKind.Reasoning]) is
 * *transparent*: a reasoning burst between two text deltas does NOT split
 * the sentence — both text fragments still coalesce into one text part.
 * (Models that interleave `reasoning_content` + `content` would otherwise
 * shred narration into text / Thinking / text fragments.)
 *
 * Tool calls (and other non-streaming parts) end the segment and force the
 * next streaming delta to open a fresh part — preserving narration order
 * across tool steps. See the segment-boundary tests for the matrix of
 * accepted behaviours.
 *
 * Returns a [StreamAppendResult] containing the new parts list and the
 * index of the part that received the delta. The input list is not
 * mutated.
 */
fun appendStreamPart(
    parts: List<ChatMessagePart>,
    kind: StreamKind,
    delta: String,
): StreamAppendResult {
    val next = parts.toMutableList()

    for (i in next.indices.reversed()) {
        when (val part = next[i]) {
            is ChatMessagePart.Text -> if (kind == StreamKind.Text) {
                next[i] = part.copy(text = part.text + delta)
                return StreamAppendResult(i, next)
            }
            is ChatMessagePart.Reasoning -> if (kind == StreamKind.Reasoning) {
                next[i] = part.copy(text = part.text + delta)
                return StreamAppendResult(i, next)
            }
            // Any non-streaming part is a segment boundary: stop walking back.
            is ChatMessagePart.ToolCall,
            is ChatMessagePart.Image,
            is ChatMessagePart.File -> return appendNewStreamPart(next, kind, delta)
        }
    }

    return appendNewStreamPart(next, kind, delta)
}

/** Convenience wrapper: append a text delta, return only the parts list. */
fun appendTextPart(parts: List<ChatMessagePart>, delta: String): List<ChatMessagePart> =
    appendStreamPart(parts, StreamKind.Text, delta).parts

/** Convenience wrapper: append a reasoning delta, return only the parts list. */
fun appendReasoningPart(parts: List<ChatMessagePart>, delta: String): List<ChatMessagePart> =
    appendStreamPart(parts, StreamKind.Reasoning, delta).parts

private fun appendNewStreamPart(
    list: MutableList<ChatMessagePart>,
    kind: StreamKind,
    delta: String,
): StreamAppendResult {
    list += makeStreamPart(kind, delta)
    return StreamAppendResult(list.size - 1, list)
}

private fun makeStreamPart(kind: StreamKind, text: String): ChatMessagePart = when (kind) {
    StreamKind.Text -> ChatMessagePart.Text(text)
    StreamKind.Reasoning -> ChatMessagePart.Reasoning(text)
}
