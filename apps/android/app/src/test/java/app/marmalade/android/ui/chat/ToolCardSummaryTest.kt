package app.marmalade.android.ui.chat

import app.marmalade.android.chat.messages.ChatMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tool-call card collapsed-summary logic (design proposal TOPIC 2 (b1)).
 *
 * The load-bearing case is [ChatMessagePart.ToolCall.displayArgs]: tool parts
 * hydrated from Room carry an EMPTY `args` object and only a populated
 * `argsText` string (ChatMessageMappers reconstructs args empty on cold-load,
 * and persistence lands ~200ms after a tool starts). The on-device regression
 * that motivated this test: reading `part.args` directly showed raw JSON in
 * the collapsed row and a blank expanded body for every hydrated tool call.
 */
class ToolCardSummaryTest {

    private fun toolCall(
        toolName: String,
        args: JsonObject = JsonObject(emptyMap()),
        argsText: String = args.toString(),
    ) = ChatMessagePart.ToolCall(
        toolCallId = "t1",
        toolName = toolName,
        args = args,
        argsText = argsText,
    )

    @Test
    fun `displayArgs prefers structured args when present`() {
        val args = buildJsonObject { put("command", "ls /tmp") }
        val part = toolCall("terminal", args = args)
        assertEquals(args, part.displayArgs())
    }

    @Test
    fun `displayArgs reparses argsText for hydrated parts with empty args`() {
        // Cold-loaded shape: empty args, populated argsText.
        val part = toolCall(
            "terminal",
            args = JsonObject(emptyMap()),
            argsText = """{"context":"ls /tmp","command":"ls /tmp"}""",
        )
        val parsed = part.displayArgs()
        assertEquals(
            "ls /tmp",
            (parsed["command"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }

    @Test
    fun `displayArgs falls back to empty for non-object argsText`() {
        val part = toolCall("terminal", args = JsonObject(emptyMap()), argsText = "not json")
        assertEquals(JsonObject(emptyMap()), part.displayArgs())
    }

    @Test
    fun `terminal summary shows the command first line for a hydrated part`() {
        val part = toolCall(
            "terminal",
            argsText = """{"context":"ls /tmp","command":"ls /tmp\nsecond line"}""",
        )
        // This is the exact failure seen on-device: it must NOT be raw JSON.
        assertEquals(
            "ls /tmp",
            toolSummary(part.toolName, part.displayArgs(), part.argsText),
        )
    }

    @Test
    fun `file summary shows the basename left of a long path`() {
        val part = toolCall(
            "read_file",
            argsText = """{"path":"/home/user/coding/marmalade/app/src/main/Bar.kt"}""",
        )
        assertEquals(
            "Bar.kt",
            toolSummary(part.toolName, part.displayArgs(), part.argsText),
        )
    }

    @Test
    fun `search summary shows the query`() {
        val part = toolCall("grep", argsText = """{"query":"needle","context":"x"}""")
        assertEquals(
            "needle",
            toolSummary(part.toolName, part.displayArgs(), part.argsText),
        )
    }

    @Test
    fun `unknown tool with no recognisable field summarises to nothing, not raw JSON`() {
        // Was: the raw argsText, which printed `{"foo":"bar"}` into the header.
        // An empty summary renders no chip at all, which reads far better.
        val part = toolCall("mystery_tool", argsText = """{"foo":"bar"}""")
        assertEquals("", toolSummary(part.toolName, part.displayArgs(), part.argsText))
    }

    @Test
    fun `unknown tool still surfaces a recognisable field when it has one`() {
        val part = toolCall("mystery_tool", argsText = """{"query":"needle"}""")
        assertEquals("needle", toolSummary(part.toolName, part.displayArgs(), part.argsText))
    }

    @Test
    fun `humanized titles cover the common buckets and snake-case fallback`() {
        assertEquals("Read file", humanizeToolName("read_file"))
        assertEquals("Terminal", humanizeToolName("bash"))
        assertEquals("Generate image", humanizeToolName("generate_image"))
        assertEquals("Custom tool", humanizeToolName("custom_tool"))
    }

    // ── the four defects from the maintainer's 2026-07-26 screenshot ──────────────────

    @Test
    fun `MCP tool names split on the double underscore, not blank-replaced`() {
        // Was: "Mcp  marmalade  update session summary" — each `__` became two
        // spaces because the Other bucket did a blanket replace('_', ' ').
        assertEquals(
            "Update session summary",
            humanizeToolName("mcp__marmalade__update_session_summary"),
        )
        assertEquals("marmalade" to "update_session_summary",
            parseMcpToolName("mcp__marmalade__update_session_summary"))
    }

    @Test
    fun `an MCP tool summarises to its server, never its argument JSON`() {
        // Was: `{"query":"select:mcp__marm…` printed into the header.
        val part = toolCall(
            "mcp__marmalade__update_session_summary",
            argsText = """{"summary":"a very long summary string that used to be dumped raw"}""",
        )
        assertEquals("marmalade", toolSummary(part.toolName, part.displayArgs(), part.argsText))
    }

    @Test
    fun `an argument-less tool summarises to empty, not a literal braces pair`() {
        // Was: "{}" — the expanded body guarded against it, the header did not.
        val part = toolCall("some_tool", argsText = "{}")
        assertEquals("", toolSummary(part.toolName, part.displayArgs(), part.argsText))
    }

    @Test
    fun `the unnamed-tool placeholder never renders as the tool name`() {
        // ToolCallUpsert defaults a nameless tool.start to "tool" to key the
        // upsert; it used to title-case straight into the header as "Tool".
        assertEquals("Tool call", humanizeToolName("tool"))
    }

    @Test
    fun `a malformed MCP name degrades to snake-case rather than throwing`() {
        assertEquals("Mcp", humanizeToolName("mcp__"))
        assertEquals(null, parseMcpToolName("mcp__onlyserver"))
        assertEquals("Mcp  onlyserver", humanizeToolName("mcp__onlyserver"))
    }
}
