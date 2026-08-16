package app.marmalade.android.ui.chat

import app.marmalade.android.chat.messages.ChatMessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row grouping for the collapsed tool run (design-lab `tool-calls-in-chat`,
 * option E + D + the subagent card).
 *
 * The behaviour being pinned: a turn's tool COUNT must stop driving its
 * vertical cost. Before this, every call was its own full-width card, so an
 * ordinary turn buried its own prose under a stack of grey slabs. The two
 * escalation rules are the load-bearing exceptions — an error or a question
 * outranks tidiness, because those are the rows the maintainer has to act on.
 */
class ToolRunGroupingTest {

    private fun call(
        id: String,
        name: String = "read_file",
        parent: String? = null,
        done: Boolean = true,
        error: Boolean = false,
        args: JsonObject = JsonObject(emptyMap()),
    ) = ChatMessagePart.ToolCall(
        toolCallId = id,
        toolName = name,
        args = args,
        argsText = args.toString(),
        result = if (done) JsonObject(mapOf("duration_s" to JsonPrimitive(1.0))) else null,
        isError = error,
        parentToolUseId = parent,
    )

    @Test
    fun `several plain calls collapse into one run row`() {
        val rows = groupToolRun(listOf(call("a"), call("b"), call("c")))
        assertEquals(1, rows.size)
        val run = rows.single() as MessageRow.ToolRun
        assertEquals(3, run.entries.size)
    }

    @Test
    fun `a lone call stays a full card — collapsing one buys nothing`() {
        val rows = groupToolRun(listOf(call("a")))
        assertTrue(rows.single() is MessageRow.Tool)
    }

    @Test
    fun `an error escalates out of the run and splits it`() {
        val rows = groupToolRun(
            listOf(call("a"), call("b"), call("boom", error = true), call("c"), call("d")),
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0] is MessageRow.ToolRun)
        assertEquals("boom", (rows[1] as MessageRow.Tool).part.toolCallId)
        assertTrue(rows[2] is MessageRow.ToolRun)
    }

    @Test
    fun `a question escalates out of the run — the agent is blocked on the maintainer`() {
        val rows = groupToolRun(listOf(call("a"), call("ask", name = "AskUserQuestion"), call("b")))
        // It gets the prompt frame rather than the generic tool card (the
        // asked → answered → recorded lifecycle), but the invariant that
        // matters is unchanged: it is NEVER folded into a collapsed run.
        assertEquals("ask", (rows[1] as MessageRow.Prompt).part.toolCallId)
        assertTrue(rows.none { it is MessageRow.ToolRun && it.entries.any { e -> e.call.toolCallId == "ask" } })
    }

    @Test
    fun `a subagent spawn gets its own card with its children nested`() {
        val rows = groupToolRun(
            listOf(
                call("t1"),
                call("spawn", name = "Task"),
                call("c1", parent = "spawn"),
                call("c2", parent = "spawn"),
                call("t2"),
            ),
        )
        // read → [Subagent] → read, with the two children pulled off top level.
        assertEquals(3, rows.size)
        assertTrue(rows[0] is MessageRow.Tool)
        val sub = rows[1] as MessageRow.Subagent
        assertEquals("spawn", sub.entry.call.toolCallId)
        assertEquals(listOf("c1", "c2"), sub.entry.children.map { it.toolCallId })
        assertTrue(rows[2] is MessageRow.Tool)
    }

    @Test
    fun `an orphaned child stays top-level rather than vanishing`() {
        // Its spawn isn't in this run — split across a prose break, or a cold
        // load that dropped it. Losing the row entirely would be worse than
        // showing it unattributed.
        val rows = groupToolRun(listOf(call("a"), call("orphan", parent = "elsewhere")))
        val run = rows.single() as MessageRow.ToolRun
        assertEquals(listOf("a", "orphan"), run.entries.map { it.call.toolCallId })
    }

    @Test
    fun `a run is running while any call or nested child is unfinished`() {
        val settled = ToolRunEntry(call("a"))
        assertTrue(!settled.running)
        assertTrue(ToolRunEntry(call("a", done = false)).running)
        assertTrue(ToolRunEntry(call("spawn", name = "Task"), listOf(call("c", done = false))).running)
    }

    @Test
    fun `an entry is errored when its child failed, even if the spawn did not`() {
        val entry = ToolRunEntry(call("spawn", name = "Task"), listOf(call("c", error = true)))
        assertTrue(entry.errored)
    }

    @Test
    fun `both Task and Agent spellings count as a spawn`() {
        assertTrue(call("x", name = "Task").isSubagentSpawn)
        assertTrue(call("x", name = "Agent").isSubagentSpawn)
        assertTrue(!call("x", name = "read_file").isSubagentSpawn)
    }

    @Test
    fun `ordering is preserved across escalation and nesting`() {
        val rows = groupToolRun(
            listOf(
                call("a"),
                call("spawn", name = "Task"),
                call("c1", parent = "spawn"),
                call("boom", error = true),
                call("b"),
                call("c"),
            ),
        )
        assertEquals(4, rows.size)
        assertTrue(rows[0] is MessageRow.Tool)                     // a
        assertEquals("spawn", (rows[1] as MessageRow.Subagent).entry.call.toolCallId)
        assertEquals("boom", (rows[2] as MessageRow.Tool).part.toolCallId)
        assertEquals(listOf("b", "c"), (rows[3] as MessageRow.ToolRun).entries.map { it.call.toolCallId })
    }
}
