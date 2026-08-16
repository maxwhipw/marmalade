package app.marmalade.android.chat.messages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digital-twin repro for the tool-result persistence gap (maintainer, on-device
 * 2026-07-02): a completed tool call renders its quiet-success check +
 * duration only for the live window — after a cold load every tool card
 * reverts to "running" forever, because
 * [toMessageEntity] never serialized [ChatMessagePart.ToolCall.result]
 * and [toChatMessage] never restored it. The UI derives BOTH the
 * completed-vs-running state (result == null → running) AND the duration
 * label (`result["duration_s"]`) from that field, so dropping it on the
 * Room round-trip breaks the whole completed-state rendering.
 */
class ToolResultRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(message: ChatMessage): ChatMessage =
        message.toMessageEntity(sessionKey = "main", json = json).toChatMessage(json)

    private fun toolCall(
        result: kotlinx.serialization.json.JsonElement? = null,
        isError: Boolean = false,
    ) = ChatMessagePart.ToolCall(
        toolCallId = "tc-1",
        toolName = "terminal",
        args = buildJsonObject { put("command", "ls /tmp") },
        argsText = """{"command":"ls /tmp"}""",
        result = result,
        isError = isError,
    )

    @Test
    fun `completed tool result survives the Room round-trip`() {
        val result = buildJsonObject {
            put("message", "3 files")
            put("duration_s", 1.42)
        }
        val original = ChatMessage(
            id = "m-1",
            role = ChatRole.Assistant,
            parts = listOf(toolCall(result = result), ChatMessagePart.Text("done")),
            timestamp = 1_000L,
        )

        val restored = roundTrip(original)

        val part = restored.parts.filterIsInstance<ChatMessagePart.ToolCall>().single()
        assertNotNull(
            "tool result must survive persistence — a null result renders as 'running' forever",
            part.result,
        )
        val resultObj = part.result as JsonObject
        assertEquals(JsonPrimitive("3 files"), resultObj["message"])
        assertEquals(
            "duration_s must survive so the quiet-success duration label renders after cold load",
            "1.42",
            (resultObj["duration_s"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `hasCutPoint tri-state survives the Room round-trip`() {
        // false must NOT collapse to null (null re-offers the branch
        // affordance the daemon would reject) and null must NOT become false
        // (legacy transcripts keep the offer-and-let-the-daemon-decide path).
        for (flag in listOf(true, false, null)) {
            val original = ChatMessage(
                id = "m-cut-" + flag,
                role = ChatRole.Assistant,
                parts = listOf(ChatMessagePart.Text("hi")),
                timestamp = 1_000L,
                hasCutPoint = flag,
            )
            assertEquals(flag, roundTrip(original).hasCutPoint)
        }
    }

    @Test
    fun `still-running tool call round-trips with null result`() {
        val original = ChatMessage(
            id = "m-2",
            role = ChatRole.Assistant,
            parts = listOf(toolCall(result = null)),
            timestamp = 1_000L,
        )
        val part = roundTrip(original).parts.filterIsInstance<ChatMessagePart.ToolCall>().single()
        assertNull("running tool must stay result-less after round-trip", part.result)
    }

    @Test
    fun `errored tool result round-trips with isError`() {
        val result = buildJsonObject { put("error", "command not found") }
        val original = ChatMessage(
            id = "m-3",
            role = ChatRole.Assistant,
            parts = listOf(toolCall(result = result, isError = true)),
            timestamp = 1_000L,
        )
        val part = roundTrip(original).parts.filterIsInstance<ChatMessagePart.ToolCall>().single()
        assertTrue("isError must survive", part.isError)
        assertEquals(JsonPrimitive("command not found"), (part.result as JsonObject)["error"])
    }

    @Test
    fun `legacy rows without a result field hydrate as running`() {
        // Rows written before the fix carry no "result" key at all — they
        // must keep deserializing (null result), not throw.
        val legacyContentJson =
            """[{"type":"tool_call","toolCallId":"tc-9","toolName":"terminal","argsText":"{}"}]"""
        val entity = app.marmalade.android.data.local.entity.MessageEntity(
            id = "m-legacy",
            sessionKey = "main",
            role = "assistant",
            contentJson = legacyContentJson,
            timestampMs = 1_000L,
            isStreaming = false,
            clientOrdinal = 0L,
        )
        val part = entity.toChatMessage(json).parts.filterIsInstance<ChatMessagePart.ToolCall>().single()
        assertEquals("tc-9", part.toolCallId)
        assertNull(part.result)
    }
}
