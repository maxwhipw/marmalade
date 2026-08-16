package app.marmalade.android.chat.messages

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `hermes-agent upstream: apps/desktop/src/lib/chat-messages.test.ts`
 * "interleaved reasoning/text coalescing" describe-block at line 179.
 *
 * The point of these tests is that the segment-aware coalescer behaves
 * identically to the desktop implementation — same accepted shapes for the
 * cases that matter most (models that interleave reasoning + text deltas
 * within a single tool-bounded segment).
 */
class StreamCoalescerTest {

    @Test
    fun `narration stays contiguous when reasoning interrupts mid-sentence`() {
        // text → reasoning → text within one tool-bounded segment.
        // Both text fragments must coalesce into ONE text part — the
        // reasoning burst between them is transparent to text coalescing.
        var parts: List<ChatMessagePart> = emptyList()
        parts = appendTextPart(parts, "Let me ")
        parts = appendReasoningPart(parts, "checking the file...")
        parts = appendTextPart(parts, "verify the full file is correct:")

        assertEquals(listOf("Text", "Reasoning"), parts.map { it::class.simpleName })
        assertEquals("Let me verify the full file is correct:", (parts[0] as ChatMessagePart.Text).text)
        assertEquals("checking the file...", (parts[1] as ChatMessagePart.Reasoning).text)
    }

    @Test
    fun `reasoning bursts straddling a text fragment merge into one`() {
        var parts: List<ChatMessagePart> = emptyList()
        parts = appendReasoningPart(parts, "first thought ")
        parts = appendTextPart(parts, "Working on it.")
        parts = appendReasoningPart(parts, "second thought")

        assertEquals(listOf("Reasoning", "Text"), parts.map { it::class.simpleName })
        assertEquals("first thought second thought", (parts[0] as ChatMessagePart.Reasoning).text)
        assertEquals("Working on it.", (parts[1] as ChatMessagePart.Text).text)
    }

    @Test
    fun `a tool call opens a fresh text segment`() {
        var parts: List<ChatMessagePart> = listOf(ChatMessagePart.Text("Let me check."))
        parts = parts + emptyToolCall("read_file", "tc-1")
        parts = appendTextPart(parts, "Now editing.")

        assertEquals(listOf("Text", "ToolCall", "Text"), parts.map { it::class.simpleName })
        assertEquals("Let me check.", (parts[0] as ChatMessagePart.Text).text)
        assertEquals("Now editing.", (parts[2] as ChatMessagePart.Text).text)
    }

    @Test
    fun `reasoning does not merge across a tool call`() {
        var parts: List<ChatMessagePart> = listOf(ChatMessagePart.Reasoning("before tool"))
        parts = parts + emptyToolCall("read_file", "tc-1")
        parts = appendReasoningPart(parts, "after tool")

        assertEquals(listOf("Reasoning", "ToolCall", "Reasoning"), parts.map { it::class.simpleName })
        assertEquals("before tool", (parts[0] as ChatMessagePart.Reasoning).text)
        assertEquals("after tool", (parts[2] as ChatMessagePart.Reasoning).text)
    }

    @Test
    fun `image and file parts are also segment boundaries`() {
        var parts: List<ChatMessagePart> = listOf(ChatMessagePart.Text("Look:"))
        parts = parts + ChatMessagePart.Image(image = "https://x/cat.png")
        parts = appendTextPart(parts, "Cute.")
        parts = parts + ChatMessagePart.File(name = "log.txt", source = "@file:log.txt")
        parts = appendReasoningPart(parts, "Reviewing log.")

        assertEquals(
            listOf("Text", "Image", "Text", "File", "Reasoning"),
            parts.map { it::class.simpleName },
        )
    }

    @Test
    fun `empty initial delta creates a new part`() {
        val result = appendStreamPart(emptyList(), StreamKind.Text, "")
        assertEquals(0, result.index)
        assertEquals(1, result.parts.size)
        assertTrue(result.parts[0] is ChatMessagePart.Text)
        assertEquals("", (result.parts[0] as ChatMessagePart.Text).text)
    }

    @Test
    fun `result index points to the part that received the delta`() {
        val first = appendStreamPart(emptyList(), StreamKind.Text, "hello ")
        assertEquals(0, first.index)

        val second = appendStreamPart(first.parts, StreamKind.Text, "world")
        assertEquals(0, second.index)
        assertEquals("hello world", (second.parts[0] as ChatMessagePart.Text).text)

        val third = appendStreamPart(second.parts + emptyToolCall("tool", "t1"), StreamKind.Text, "next")
        assertEquals(2, third.index)
    }

    @Test
    fun `chatMessageText concatenates only Text parts in order`() {
        val msg = ChatMessage(
            id = "m1",
            role = ChatRole.Assistant,
            parts = listOf(
                ChatMessagePart.Text("hello "),
                ChatMessagePart.Reasoning("thinking..."),
                emptyToolCall("tool", "t1"),
                ChatMessagePart.Text("world"),
            ),
        )
        assertEquals("hello world", msg.text())
        assertTrue(msg.hasToolPart())
    }

    private fun emptyToolCall(name: String, id: String) = ChatMessagePart.ToolCall(
        toolCallId = id,
        toolName = name,
        args = JsonObject(emptyMap()),
        argsText = "",
    )
}
