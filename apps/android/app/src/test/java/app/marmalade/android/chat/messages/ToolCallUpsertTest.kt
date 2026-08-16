package app.marmalade.android.chat.messages

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assert.assertNotNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Mirrors `hermes-agent upstream: apps/desktop/src/lib/chat-messages.test.ts`
 * "upsertToolPart" describe-block at line 392.
 *
 * Covers the tool-call matching heuristics, args/result merging, parallel
 * same-name tool resolution, and id-less reconciliation paths.
 */
class ToolCallUpsertTest {

    @Before fun resetCounter() = resetLiveToolCounterForTest()

    @Test
    fun `inline_diff carried from completion payload`() {
        val parts = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("inline_diff", "--- a/foo.ts\n+++ b/foo.ts\n@@\n-old\n+new")
                put("name", "patch")
                put("tool_id", "tool-1")
            },
            ToolPhase.Complete,
        )
        val tool = parts.single() as ChatMessagePart.ToolCall
        assertEquals(
            "--- a/foo.ts\n+++ b/foo.ts\n@@\n-old\n+new",
            (tool.result as JsonObject)["inline_diff"]!!.toString().trim('"').replace("\\n", "\n"),
        )
    }

    @Test
    fun `live todo rows survive sparse progress payloads`() {
        val first = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("name", "todo")
                put("todos", buildJsonArray {
                    add(buildJsonObject {
                        put("content", "Boil water"); put("id", "boil"); put("status", "in_progress")
                    })
                })
                put("tool_id", "todo-1")
            },
            ToolPhase.Running,
        )
        val progressed = upsertToolPart(
            first,
            buildJsonObject {
                put("name", "todo"); put("preview", "updating plan"); put("tool_id", "todo-1")
            },
            ToolPhase.Running,
        )
        val tool = progressed.single() as ChatMessagePart.ToolCall
        val todos = tool.args["todos"]
        assertTrue(todos != null && todos.toString().contains("Boil water"))
    }

    @Test
    fun `todo state archives on completion + explicit empty clears`() {
        val started = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("name", "todo")
                put("todos", buildJsonArray {
                    add(buildJsonObject { put("content", "Boil"); put("id", "b"); put("status", "in_progress") })
                })
                put("tool_id", "todo-1")
            },
            ToolPhase.Running,
        )
        val completed = upsertToolPart(
            started,
            buildJsonObject { put("name", "todo"); put("tool_id", "todo-1") },
            ToolPhase.Complete,
        )
        val cleared = upsertToolPart(
            completed,
            buildJsonObject {
                put("name", "todo"); put("todos", buildJsonArray { }); put("tool_id", "todo-1")
            },
            ToolPhase.Complete,
        )

        val completedTool = completed.single() as ChatMessagePart.ToolCall
        val clearedTool = cleared.single() as ChatMessagePart.ToolCall
        val completedTodos = (completedTool.result as JsonObject)["todos"]
        val clearedTodos = (clearedTool.result as JsonObject)["todos"]
        assertTrue(completedTodos != null && completedTodos.toString().contains("Boil"))
        assertEquals("[]", clearedTodos!!.toString())
    }

    @Test
    fun `parallel same-name tools stay distinct without explicit ids`() {
        val startedTokyo = upsertToolPart(
            emptyList(),
            buildJsonObject { put("context", "tokyo weather"); put("name", "web_search") },
            ToolPhase.Running,
        )
        val startedReykjavik = upsertToolPart(
            startedTokyo,
            buildJsonObject { put("context", "reykjavik weather"); put("name", "web_search") },
            ToolPhase.Running,
        )
        val completedTokyo = upsertToolPart(
            startedReykjavik,
            buildJsonObject {
                put("context", "tokyo weather"); put("message", "tokyo done")
                put("name", "web_search"); put("summary", "Did 5 searches")
            },
            ToolPhase.Complete,
        )
        val completedBoth = upsertToolPart(
            completedTokyo,
            buildJsonObject {
                put("context", "reykjavik weather"); put("message", "reykjavik done")
                put("name", "web_search"); put("summary", "Did 5 searches")
            },
            ToolPhase.Complete,
        )

        val webParts = completedBoth.filterIsInstance<ChatMessagePart.ToolCall>()
            .filter { it.toolName == "web_search" }
        assertEquals(2, webParts.size)
        assertEquals(
            listOf("tokyo weather", "reykjavik weather"),
            webParts.map { (it.args["context"] as JsonPrimitive).content },
        )
    }

    @Test
    fun `query args preserved when completion payload omits context`() {
        val started = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("context", "auckland weather today and tomorrow forecast")
                put("name", "web_search"); put("tool_id", "search-1")
            },
            ToolPhase.Running,
        )
        val completed = upsertToolPart(
            started,
            buildJsonObject {
                put("duration_s", 1.1); put("name", "web_search")
                put("summary", "Did 5 searches in 1.1s"); put("tool_id", "search-1")
            },
            ToolPhase.Complete,
        )
        val tool = completed.single() as ChatMessagePart.ToolCall
        assertEquals(
            "auckland weather today and tomorrow forecast",
            (tool.args["context"] as JsonPrimitive).content,
        )
        assertEquals(
            "Did 5 searches in 1.1s",
            ((tool.result as JsonObject)["summary"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `id-less progress updates do not phantom-append same-name rows`() {
        val startedA = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("context", "reykjavik weather today and tomorrow forecast")
                put("name", "web_search")
            },
            ToolPhase.Running,
        )
        val startedB = upsertToolPart(
            startedA,
            buildJsonObject {
                put("context", "kathmandu weather today and tomorrow forecast")
                put("name", "web_search")
            },
            ToolPhase.Running,
        )
        val progressed = upsertToolPart(
            startedB,
            buildJsonObject { put("name", "web_search") },
            ToolPhase.Running,
        )
        val webParts = progressed.filterIsInstance<ChatMessagePart.ToolCall>()
            .filter { it.toolName == "web_search" }
        assertEquals(2, webParts.size)
    }

    @Test
    fun `id-less live starts match later identified completions`() {
        val started = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("context", "asuncion paraguay weather today and tomorrow forecast")
                put("name", "web_search")
            },
            ToolPhase.Running,
        )
        val completed = upsertToolPart(
            started,
            buildJsonObject {
                put("context", "asuncion paraguay weather today and tomorrow forecast")
                put("duration_s", 1.1); put("name", "web_search")
                put("summary", "Did 5 searches in 1.1s"); put("tool_id", "search-asuncion")
            },
            ToolPhase.Complete,
        )
        val webParts = completed.filterIsInstance<ChatMessagePart.ToolCall>()
            .filter { it.toolName == "web_search" }
        assertEquals(1, webParts.size)
        assertEquals("search-asuncion", webParts[0].toolCallId)
        assertEquals(
            "Did 5 searches in 1.1s",
            ((webParts[0].result as JsonObject)["summary"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `id-less live starts match later identified progress updates`() {
        val started = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("context", "reykjavik tashkent uzbekistan weather today and tomorrow forecast")
                put("name", "web_search")
            },
            ToolPhase.Running,
        )
        val progressed = upsertToolPart(
            started,
            buildJsonObject {
                put("context", "reykjavik tashkent uzbekistan weather today and tomorrow forecast")
                put("name", "web_search"); put("tool_id", "search-reykjavik")
            },
            ToolPhase.Running,
        )
        val webParts = progressed.filterIsInstance<ChatMessagePart.ToolCall>()
            .filter { it.toolName == "web_search" }
        assertEquals(1, webParts.size)
        assertEquals("search-reykjavik", webParts[0].toolCallId)
    }

    @Test
    fun `completion without stable id resolves oldest pending first`() {
        // Two starts with distinct tool_ids → two pending rows. Without
        // distinct ids, identical Running events collapse into one row
        // (intentional design — see findToolPartIndex's pendingIndices.size
        // == 1 branch returning `single`). Distinct tool_ids on the starts
        // set up the scenario the COMPLETION half of this test cares about:
        // when the completion event arrives with NO stable id, it resolves
        // to the OLDEST pending (`pendingIndices.first()` at line 132 of
        // ToolCallUpsert.kt), not the most recent or both.
        var parts: List<ChatMessagePart> = emptyList()
        parts = upsertToolPart(
            parts,
            buildJsonObject { put("name", "noop"); put("tool_id", "t1") },
            ToolPhase.Running,
        )
        parts = upsertToolPart(
            parts,
            buildJsonObject { put("name", "noop"); put("tool_id", "t2") },
            ToolPhase.Running,
        )
        parts = upsertToolPart(
            parts,
            buildJsonObject { put("name", "noop"); put("summary", "first done") },
            ToolPhase.Complete,
        )
        val tools = parts.filterIsInstance<ChatMessagePart.ToolCall>()
        assertEquals(2, tools.size)
        // Oldest pending (t1) got the completion; t2 still pending.
        assertEquals("t1", tools[0].toolCallId)
        assertEquals("first done", ((tools[0].result as JsonObject)["summary"] as JsonPrimitive).content)
        assertEquals("t2", tools[1].toolCallId)
        assertNull(tools[1].result)
    }

    @Test
    fun `synthetic live-tool id is assigned when payload has none`() {
        val parts = upsertToolPart(
            emptyList(),
            buildJsonObject { put("name", "search") },
            ToolPhase.Running,
        )
        val tool = parts.single() as ChatMessagePart.ToolCall
        assertTrue("expected synthetic id, got ${tool.toolCallId}", tool.toolCallId.startsWith("live-tool:search:"))
    }

    // ── the daemon's real wire shape (the maintainer's 2026-07-26 transcript) ─────────
    // tool.start  -> {id, name, input}
    // tool.complete -> {tool_use_id, content, result, ...}   <- NO name
    //
    // `tool_use_id` was missing from toolIdFromPayload, so completions
    // resolved to an empty stable id, fell through to name matching with the
    // "tool" placeholder, matched nothing, and appended a second part. Every
    // finished tool rendered TWICE: the real row stuck on "running" forever,
    // plus a phantom "Tool call" row holding the result.

    @Test
    fun `a completion keyed by tool_use_id updates its start, not a new row`() {
        val started = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("id", "toolu_01")
                put("name", "terminal")
                put("input", buildJsonObject { put("command", "find /home/user") })
            },
            ToolPhase.Running,
        )
        assertEquals(1, started.size)

        val completed = upsertToolPart(
            started,
            buildJsonObject {
                put("tool_use_id", "toolu_01")
                put("content", "3 results")
            },
            ToolPhase.Complete,
        )
        assertEquals("one row, not two", 1, completed.size)
        val tool = completed.single() as ChatMessagePart.ToolCall
        assertEquals("terminal", tool.toolName)
        assertNotNull("the start row must receive the result", tool.result)
    }

    @Test
    fun `parallel completions by tool_use_id each settle their own start`() {
        var parts = upsertToolPart(
            emptyList(),
            buildJsonObject { put("id", "t1"); put("name", "terminal") },
            ToolPhase.Running,
        )
        parts = upsertToolPart(
            parts,
            buildJsonObject { put("id", "t2"); put("name", "terminal") },
            ToolPhase.Running,
        )
        // Complete the SECOND one first — id matching must not resolve
        // oldest-first when a stable id is present.
        parts = upsertToolPart(
            parts,
            buildJsonObject { put("tool_use_id", "t2"); put("content", "b") },
            ToolPhase.Complete,
        )
        assertEquals(2, parts.size)
        val byId = parts.filterIsInstance<ChatMessagePart.ToolCall>().associateBy { it.toolCallId }
        assertNotNull(byId["t2"]!!.result)
        assertEquals("t1 must still be running", null, byId["t1"]!!.result)
    }

    @Test
    fun `tool_result content is carried onto the merged result`() {
        val parts = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("tool_use_id", "t1")
                put("content", "260 anime files")
            },
            ToolPhase.Complete,
        )
        val tool = parts.single() as ChatMessagePart.ToolCall
        assertEquals("260 anime files", (tool.result as JsonObject)["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subagent attribution rides tool_start and survives the completion`() {
        var parts = upsertToolPart(
            emptyList(),
            buildJsonObject {
                put("id", "child1")
                put("name", "terminal")
                put("parent_tool_use_id", "toolu_spawn")
            },
            ToolPhase.Running,
        )
        parts = upsertToolPart(
            parts,
            // The completion carries no attribution — it must not clear it.
            buildJsonObject { put("tool_use_id", "child1"); put("content", "ok") },
            ToolPhase.Complete,
        )
        val tool = parts.single() as ChatMessagePart.ToolCall
        assertEquals("toolu_spawn", tool.parentToolUseId)
    }
}
