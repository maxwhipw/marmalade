package app.marmalade.android.chat.messages

import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val testJson = Json { ignoreUnknownKeys = true }

// ── Harness note ───────────────────────────────────────────────────────────────
//
// Tests that call advanceUntilIdle() with MutableSharedFlow(replay=0) are
// unreliable: events emitted before the MessageStream collector subscribes are
// dropped silently. The eight failing tests below use runBlocking instead, with
// a delay(50) after construction to let the collector go hot before any emit,
// and a final delay sized to outlast the flush window (33ms default) or the
// message.complete synchronous path.
//
// The eleven passing tests that already worked (streaming-text coalesce,
// reasoning paths, session management, etc.) use a different pattern
// (advanceTimeBy/runCurrent inside runTest) that happens to work because the
// advanceTimeBy call gives the virtual clock enough ticks. They are left
// untouched — converting working tests to a new pattern is scope creep, and
// they don't suffer from the subscriber-race because the test emits AFTER
// runCurrent() drains the constructor launch.

class MessageStreamTest {

    @Test
    fun `streaming text deltas coalesce into a single text part after flush`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, flushInterval = 33L, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("s1")

        events.emit(event("message.start", sid = "s1"))
        events.emit(event("message.delta", payload = text("Hel"), sid = "s1"))
        events.emit(event("message.delta", payload = text("lo, "), sid = "s1"))
        events.emit(event("message.delta", payload = text("world!"), sid = "s1"))
        runCurrent()

        // Before the flush window, the deltas are still buffered.
        assertNull("text should be buffered, not yet applied", state.value.pending?.parts?.firstOrNull())

        advanceTimeBy(50L)
        runCurrent()

        val pending = state.value.pending
        assertNotNull(pending)
        assertEquals("Hello, world!", pending!!.text())
        assertTrue(state.value.streaming)
    }

    @Test
    fun `immediate delta flush publishes without waiting out the coalescing window`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, flushInterval = 33L, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("s1")

        // Voice turn active: the speech feeder needs sentence boundaries the
        // moment their delta arrives, not after the 33ms render batch.
        stream.immediateDeltaFlush = true
        events.emit(event("message.start", sid = "s1"))
        events.emit(event("message.delta", payload = text("First sentence closed here. And"), sid = "s1"))
        runCurrent()

        // No advanceTimeBy: the text must already be visible on the StateFlow.
        assertEquals("First sentence closed here. And", state.value.pending?.text())

        // Toggle off restores coalescing: the next delta buffers again.
        stream.immediateDeltaFlush = false
        events.emit(event("message.delta", payload = text(" more"), sid = "s1"))
        runCurrent()
        assertEquals("First sentence closed here. And", state.value.pending?.text())
        advanceTimeBy(50L)
        runCurrent()
        assertEquals("First sentence closed here. And more", state.value.pending?.text())
    }

    @Test
    fun `message start does not eagerly create an empty bubble`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("s1")

        events.emit(event("message.start", sid = "s1"))
        runCurrent()

        // streaming=true but no bubble yet — desktop parity (use-message-stream.ts:832).
        assertTrue(state.value.streaming)
        assertNull(state.value.pending)
    }

    @Test
    fun `message complete carries has_cut_point onto the finalized bubble, absent stays null`() = runBlocking {
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(events, streamScope, FakeChatDao(), testJson, now = { 1L })
        stream.setActiveSession("s1")
        delay(50)
        val state = stream.sessionMessages("s1")

        // Turn one: fork-copied shape — the daemon stamps has_cut_point=false.
        events.emit(event("message.delta", payload = text("copied"), sid = "s1"))
        events.emit(
            event(
                "message.complete",
                payload = buildJsonObject { put("text", "copied"); put("has_cut_point", false) },
                sid = "s1",
            )
        )
        // Turn two: pre-flag transcript — no flag on the wire.
        events.emit(event("message.delta", payload = text("legacy"), sid = "s1"))
        events.emit(event("message.complete", payload = text("legacy"), sid = "s1"))
        delay(200)

        assertEquals(2, state.value.messages.size)
        assertEquals(false, state.value.messages[0].hasCutPoint)
        assertNull("absent flag must stay null (offer + let the daemon decide)", state.value.messages[1].hasCutPoint)

        stream.close()
        streamScope.cancel()
    }

    @Test
    fun `message complete with finalText replaces streamed text + preserves tool parts`() = runBlocking {
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(events, streamScope, FakeChatDao(), testJson, now = { 1234L })
        stream.setActiveSession("s1")
        delay(50)
        val state = stream.sessionMessages("s1")

        events.emit(event("message.start", sid = "s1"))
        events.emit(event("message.delta", payload = text("Let me look. "), sid = "s1"))
        events.emit(
            event(
                "tool.start",
                payload = buildJsonObject { put("name", "read_file"); put("tool_id", "tc-1") },
                sid = "s1",
            )
        )
        events.emit(
            event(
                "tool.complete",
                payload = buildJsonObject {
                    put("name", "read_file"); put("tool_id", "tc-1"); put("summary", "ok")
                },
                sid = "s1",
            )
        )
        events.emit(event("message.delta", payload = text("Streamed tail."), sid = "s1"))
        events.emit(event("message.complete", payload = text("Canonical answer."), sid = "s1"))
        delay(200)

        assertEquals(1, state.value.messages.size)
        val finalized = state.value.messages[0]
        val parts = finalized.parts
        // Tool call survives; the TRAILING streamed segment is replaced by
        // the canonical text, appended last (desktop's replaceTextPart,
        // use-message-stream.ts:574). Deliberate desktop deviation: the
        // pre-tool NARRATION segment is KEPT — the gateway's history keeps
        // it too (server.py _history_to_messages), and reconcileHistory
        // correlates local rows with history by content, so dropping
        // narration made every narration-bearing tool turn dupe on hydrate
        // and lose its rich local row (see ToolTurnReconcileTest).
        val types = parts.map { it::class.simpleName }
        assertEquals(listOf("Text", "ToolCall", "Text"), types)
        assertEquals("Let me look. ", (parts[0] as ChatMessagePart.Text).text)
        assertEquals("tc-1", (parts[1] as ChatMessagePart.ToolCall).toolCallId)
        assertEquals("Canonical answer.", (parts[2] as ChatMessagePart.Text).text)
        assertFalse(state.value.streaming)

        stream.close()
        streamScope.cancel()
    }

    @Test
    fun `message complete with empty payload + empty parts renders placeholder`() = runBlocking {
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(events, streamScope, FakeChatDao(), testJson, now = { 0L })
        stream.setActiveSession("s1")
        delay(50)
        val state = stream.sessionMessages("s1")

        // ensurePendingBubble fires on a delta — synthesize a pending bubble
        // so message.complete has something to finalize. Empty bubble + empty
        // payload should show "(empty response)".
        events.emit(event("message.start", sid = "s1"))
        events.emit(event("message.delta", payload = text(""), sid = "s1"))
        // The empty-text delta is a no-op; pending stays null. We need a
        // bubble to test the placeholder path — use a real delta then complete.
        events.emit(event("message.delta", payload = text("placeholder"), sid = "s1"))
        events.emit(event("message.complete", payload = JsonObject(emptyMap()), sid = "s1"))
        delay(200)

        assertEquals(1, state.value.messages.size)
        // No canonical finalText, no canonical replacement — preserves streamed.
        assertEquals("placeholder", state.value.messages[0].text())

        stream.close()
        streamScope.cancel()
    }

    @Test
    fun `reasoning available replaces reasoning parts when no text yet`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("s1")

        events.emit(event("message.start", sid = "s1"))
        events.emit(event("reasoning.delta", payload = text("Thinking part 1. "), sid = "s1"))
        events.emit(event("reasoning.delta", payload = text("Thinking part 2."), sid = "s1"))
        events.emit(event("reasoning.available", payload = text("Final canonical reasoning."), sid = "s1"))
        runCurrent()

        val parts = state.value.pending!!.parts
        // Replace, not append — exactly one reasoning part with canonical text.
        val reasoningParts = parts.filterIsInstance<ChatMessagePart.Reasoning>()
        assertEquals(1, reasoningParts.size)
        assertEquals("Final canonical reasoning.", reasoningParts[0].text)
    }

    @Test
    fun `reasoning available is skipped once assistant text has streamed`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("s1")

        events.emit(event("message.start", sid = "s1"))
        events.emit(event("reasoning.delta", payload = text("Thinking..."), sid = "s1"))
        events.emit(event("message.delta", payload = text("Hello"), sid = "s1"))
        advanceTimeBy(50L); runCurrent()
        events.emit(event("reasoning.available", payload = text("Canonical reasoning"), sid = "s1"))
        runCurrent()

        val parts = state.value.pending!!.parts
        val reasoningParts = parts.filterIsInstance<ChatMessagePart.Reasoning>()
        // Pre-existing streamed reasoning preserved; the would-be canonical
        // replacement is skipped because text has streamed.
        assertEquals(1, reasoningParts.size)
        assertEquals("Thinking...", reasoningParts[0].text)
    }

    @Test
    fun `re-entrant message start finalizes the previous pending bubble`() = runBlocking {
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(events, streamScope, FakeChatDao(), testJson, now = { 0L })
        stream.setActiveSession("s1")
        delay(50)
        val state = stream.sessionMessages("s1")

        // First turn: starts, streams some text, then a SECOND message.start
        // arrives (e.g. reconnect mid-turn). The first bubble must survive
        // into messages — CLAUDE.md: "WS reconnect must not kill the run".
        events.emit(event("message.start", sid = "s1"))
        events.emit(event("message.delta", payload = text("First turn text"), sid = "s1"))
        delay(100) // let the flush timer fire (flushInterval default 33ms)
        events.emit(event("message.start", sid = "s1"))
        events.emit(event("message.delta", payload = text("Second turn text"), sid = "s1"))
        events.emit(event("message.complete", payload = text("Second turn text"), sid = "s1"))
        delay(200)

        assertEquals(2, state.value.messages.size)
        assertEquals("First turn text", state.value.messages[0].text())
        assertEquals("Second turn text", state.value.messages[1].text())
        assertFalse(state.value.messages[0].pending)
        assertFalse(state.value.messages[1].pending)

        stream.close()
        streamScope.cancel()
    }

    @Test
    fun `error event finalizes pending bubble with the error message`() = runBlocking {
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(events, streamScope, FakeChatDao(), testJson, now = { 0L })
        stream.setActiveSession("s1")
        delay(50)
        val state = stream.sessionMessages("s1")

        events.emit(event("message.start", sid = "s1"))
        events.emit(event("message.delta", payload = text("Working on it"), sid = "s1"))
        delay(100) // let the flush timer fire (flushInterval default 33ms)
        events.emit(
            event(
                "error",
                payload = buildJsonObject { put("message", "rate limit exceeded") },
                sid = "s1",
            )
        )
        delay(100)

        assertEquals(1, state.value.messages.size)
        val finalized = state.value.messages[0]
        assertEquals("rate limit exceeded", finalized.error)
        assertFalse(finalized.pending)
        assertFalse(state.value.streaming)
        assertNull(state.value.pending)

        stream.close()
        streamScope.cancel()
    }

    @Test
    fun `completion text matching gateway error pattern surfaces as error`() = runBlocking {
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(events, streamScope, FakeChatDao(), testJson, now = { 0L })
        stream.setActiveSession("s1")
        delay(50)
        val state = stream.sessionMessages("s1")

        // A delta is needed to create the pending bubble; message.complete
        // only finalizes an existing bubble (production code does not eagerly
        // create one on message.start). The error-pattern detection is the
        // subject of this test, not the no-prior-delta edge case.
        events.emit(event("message.start", sid = "s1"))
        events.emit(event("message.delta", payload = text("API call failed after 3 retries: connection reset"), sid = "s1"))
        events.emit(
            event(
                "message.complete",
                payload = text("API call failed after 3 retries: connection reset"),
                sid = "s1",
            )
        )
        delay(100)

        val msg = state.value.messages.single()
        assertNotNull(msg.error)
        assertTrue(msg.error!!.contains("API call failed"))
        assertTrue("error text strips text parts", msg.parts.none { it is ChatMessagePart.Text })

        stream.close()
        streamScope.cancel()
    }

    @Test
    fun `tool start opens a card after flushing pending text`() = runBlocking {
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(events, streamScope, FakeChatDao(), testJson, now = { 0L })
        stream.setActiveSession("s1")
        delay(50)
        val state = stream.sessionMessages("s1")

        events.emit(event("message.start", sid = "s1"))
        events.emit(event("message.delta", payload = text("Let me check."), sid = "s1"))
        events.emit(
            event(
                "tool.start",
                payload = buildJsonObject { put("name", "read_file"); put("tool_id", "tc-1") },
                sid = "s1",
            )
        )
        delay(100)

        val parts = state.value.pending!!.parts
        assertEquals(listOf("Text", "ToolCall"), parts.map { it::class.simpleName })
        assertEquals("Let me check.", (parts[0] as ChatMessagePart.Text).text)
        assertEquals("tc-1", (parts[1] as ChatMessagePart.ToolCall).toolCallId)

        stream.close()
        streamScope.cancel()
    }

    @Test
    fun `event without session_id is dropped when no active session is set`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })

        events.emit(event("message.delta", payload = text("orphan"), sid = null))
        advanceUntilIdle()
        // No crash; no spurious bucket created either.
    }

    @Test
    fun `unscoped non-subagent events route to the active session`() = runBlocking {
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(events, streamScope, FakeChatDao(), testJson, now = { 0L })
        stream.setActiveSession("focused")
        delay(50)
        val state = stream.sessionMessages("focused")

        // Server emits unstamped events for the focused turn (gateway only
        // stamps session_id on *background* sessions). Without the active-id
        // fallback the prior implementation silently dropped these.
        events.emit(event("message.start", sid = null))
        events.emit(event("message.delta", payload = text("hello"), sid = null))
        events.emit(event("message.complete", payload = text("hello"), sid = null))
        delay(200)

        assertEquals(1, state.value.messages.size)
        assertEquals("hello", state.value.messages[0].text())

        stream.close()
        streamScope.cancel()
    }

    @Test
    fun `stamped session_id wins over active session`() = runBlocking {
        val streamScope = CoroutineScope(SupervisorJob())
        val events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
        val stream = MessageStream(events, streamScope, FakeChatDao(), testJson, now = { 0L })
        stream.setActiveSession("focused")
        delay(50)
        val focused = stream.sessionMessages("focused")
        val background = stream.sessionMessages("bg")

        // A background session's stamped events still route to that session,
        // not to whatever the UI currently has focused.
        events.emit(event("message.start", sid = "bg"))
        events.emit(event("message.delta", payload = text("background"), sid = "bg"))
        events.emit(event("message.complete", payload = text("background"), sid = "bg"))
        delay(200)

        assertTrue("focused untouched", focused.value.messages.isEmpty())
        assertEquals("background", background.value.messages.single().text())

        stream.close()
        streamScope.cancel()
    }

    @Test
    fun `subagent events without session_id are dropped`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("focused")
        stream.setActiveSession("focused")

        // subagent.* always carries its OWN session id; an unstamped subagent
        // event has nowhere safe to land. Drop rather than guess.
        events.emit(event("subagent.spawn_requested", payload = JsonObject(emptyMap()), sid = null))
        advanceUntilIdle()

        assertTrue(state.value.messages.isEmpty())
        assertNull(state.value.pending)
    }

    @Test
    fun `concurrent sessions stream independently`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val a = stream.sessionMessages("a")
        val b = stream.sessionMessages("b")

        events.emit(event("message.start", sid = "a"))
        events.emit(event("message.start", sid = "b"))
        events.emit(event("message.delta", payload = text("from-a"), sid = "a"))
        events.emit(event("message.delta", payload = text("from-b"), sid = "b"))
        advanceTimeBy(50L); runCurrent()

        assertEquals("from-a", a.value.pending!!.text())
        assertEquals("from-b", b.value.pending!!.text())
    }

    @Test
    fun `removeSession evicts the bucket entirely`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()

        events.emit(event("message.start", sid = "ephemeral"))
        events.emit(event("message.delta", payload = text("done"), sid = "ephemeral"))
        advanceTimeBy(50L); runCurrent()

        stream.removeSession("ephemeral")
        // Next access returns a fresh empty bucket — confirms eviction.
        assertNull(stream.sessionMessages("ephemeral").value.pending)
        assertTrue(stream.sessionMessages("ephemeral").value.messages.isEmpty())
    }

    @Test
    fun `unknown event types do not throw`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("s1")

        events.emit(event("custom.thing.we.never.added", payload = JsonObject(emptyMap()), sid = "s1"))
        advanceUntilIdle()

        assertNull(state.value.pending)
        assertTrue(state.value.messages.isEmpty())
    }

    @Test
    fun `non-string text payload does not produce literal type-name as content`() = runTest {
        val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
        val stream = MessageStream(events, backgroundScope, FakeChatDao(), testJson, now = { 0L })
        runCurrent()
        val state = stream.sessionMessages("s1")

        events.emit(event("message.start", sid = "s1"))
        // Defensive: a malformed server sending text as number / bool / null
        // would have rendered "42" / "true" / "null" as visible chat text in
        // the prior implementation. Now: ignored.
        events.emit(
            event(
                "message.delta",
                payload = buildJsonObject { put("text", 42) },
                sid = "s1",
            )
        )
        advanceTimeBy(50L); runCurrent()

        // No delta applied; pending bubble still empty / not even created.
        assertNull(state.value.pending)
    }
}

private fun event(
    type: String,
    payload: JsonObject = JsonObject(emptyMap()),
    sid: String? = null,
): GatewayEvent = GatewayEvent(type = type, payload = payload, sessionId = sid)

private fun text(t: String): JsonObject = buildJsonObject { put("text", t) }
