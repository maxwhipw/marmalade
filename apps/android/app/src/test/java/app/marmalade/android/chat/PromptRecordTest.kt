package app.marmalade.android.chat

import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.chat.messages.toChatMessage
import app.marmalade.android.chat.messages.toMessageEntity
import app.marmalade.android.chat.messages.ToolPhase
import app.marmalade.android.chat.messages.upsertToolPart
import app.marmalade.android.ui.chat.DISMISSED
import app.marmalade.android.ui.chat.approvalRecordChip
import app.marmalade.android.ui.chat.clarifyRecordSummary
import app.marmalade.android.ui.chat.parkedQuestionRowKey
import app.marmalade.android.ui.chat.approvalRecordLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asked → answered → **recorded** prompt lifecycle (design-lab
 * `agent-session-ui`, recommendation 2).
 *
 * What is being pinned: a question the maintainer answered must still be in the
 * transcript tomorrow. Before this, every docked prompt card was ephemeral —
 * `PromptCenter.remove()` dropped it on submit and nothing was written to the
 * message history, so a decision that steered a whole session left no trace in
 * scrollback, cold load, or search.
 *
 * The record rides the `AskUserQuestion` tool pair rather than a new persisted
 * message part, because that pair is ALREADY in the daemon's transcript and
 * already replayed on subscribe (verified on the wire 2026-07-27, probe session
 * `s_d914489f`: `tool.start` seq 8 carries the questions, `tool.complete` seq
 * 13 carries `result.answers`; the `clarify.*` events that drive the docked
 * card are transient and are not persisted at all). So the cold-load round trip
 * below is the test that actually matters.
 */
class PromptRecordTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The real `AskUserQuestion` tool input, in the harness's camelCase
     *  spelling — deliberately NOT the daemon's `multi_select` clarify shape,
     *  since one parser now has to read both. */
    private val askInput = buildJsonObject {
        putJsonArray("questions") {
            add(
                buildJsonObject {
                    put("question", "Which direction for the tool cards?")
                    put("header", "Approach")
                    put("multiSelect", false)
                    putJsonArray("options") {
                        add(
                            buildJsonObject {
                                put("label", "Collapse the run")
                                put("description", "One line per turn, expands on tap")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("label", "One line per tool")
                                put("description", "Dense log rows, always visible")
                            },
                        )
                    }
                },
            )
        }
    }

    private val askResult = buildJsonObject {
        putJsonObject("answers") {
            put("Which direction for the tool cards?", "Collapse the run")
        }
    }

    private fun askPart(result: JsonObject? = askResult) = ChatMessagePart.ToolCall(
        toolCallId = "toolu_01Aec",
        toolName = "AskUserQuestion",
        args = askInput,
        argsText = askInput.toString(),
        result = result,
    )

    @Test
    fun `the questions parse out of the tool input, camelCase spelling and all`() {
        val questions = parseClarifyQuestions(askInput)
        assertEquals(1, questions.size)
        assertEquals("Which direction for the tool cards?", questions[0].question)
        assertEquals("Approach", questions[0].header)
        assertEquals(2, questions[0].options.size)
        assertEquals("Collapse the run", questions[0].options[0].label)
    }

    @Test
    fun `the daemon's snake_case multi_select still parses — one parser, both shapes`() {
        val clarifyShape = buildJsonObject {
            putJsonArray("questions") {
                add(
                    buildJsonObject {
                        put("question", "Pick some")
                        put("header", "Multi")
                        put("multi_select", true)
                        putJsonArray("options") { }
                    },
                )
            }
        }
        assertTrue(parseClarifyQuestions(clarifyShape).single().multiSelect)
        assertTrue(parseClarifyQuestions(askInput).single().multiSelect.not())
    }

    @Test
    fun `the answer parses out of the tool result`() {
        assertEquals(
            mapOf("Which direction for the tool cards?" to "Collapse the run"),
            parseClarifyAnswers(askResult),
        )
    }

    @Test
    fun `a dismissal is recorded as a dismissal, not as an answer`() {
        // Sending neither answers nor response IS the dismissal contract — the
        // daemon settles the parked AskUserQuestion with a proceed-on-your-own
        // message. The record must not render that as a choice the maintainer made.
        assertTrue(parseClarifyAnswers(buildJsonObject { }).isEmpty())
        assertTrue(parseClarifyAnswers(null).isEmpty())
    }

    @Test
    fun `a non-string answer degrades instead of throwing`() {
        val weird = buildJsonObject {
            putJsonObject("answers") { put("q", 7) }
        }
        assertTrue(parseClarifyAnswers(weird).isEmpty())
    }

    @Test
    fun `the question AND the answer survive a cold-load round trip through Room`() {
        // The whole point of recommendation 2. A row that renders correctly
        // live but loses the decision on rebuild is exactly the bug this
        // replaces, so assert against the Room encoding, not the live part.
        val message = ChatMessage(
            id = "m1",
            role = ChatRole.Assistant,
            parts = listOf(askPart()),
            timestamp = 1L,
        )
        val entity = message.toMessageEntity(sessionKey = "s1", json = json)
        val rebuilt = entity.toChatMessage(json)

        val part = rebuilt.parts.filterIsInstance<ChatMessagePart.ToolCall>().single()
        assertTrue(part.isAgentQuestion)
        // Cold load reconstructs `args` empty and keeps argsText, so the
        // questions have to come back through displayArgs()' reparse — the
        // path a real scrollback read takes.
        assertEquals(
            "Which direction for the tool cards?",
            parseClarifyQuestions(json.parseToJsonElement(part.argsText) as JsonObject).single().question,
        )
        assertEquals(
            mapOf("Which direction for the tool cards?" to "Collapse the run"),
            parseClarifyAnswers(part.result as JsonObject),
        )
    }

    @Test
    fun `an unanswered ask is still a row — asked without answered`() {
        val part = askPart(result = null)
        assertTrue(part.isAgentQuestion)
        assertTrue(parseClarifyAnswers(part.result as? JsonObject).isEmpty())
        assertEquals(1, parseClarifyQuestions(part.args).size)
    }

    @Test
    fun `an ordinary tool call is not mistaken for a question`() {
        val read = ChatMessagePart.ToolCall(
            toolCallId = "t1",
            toolName = "Read",
            args = JsonObject(emptyMap()),
            argsText = "{}",
            result = JsonPrimitive("ok"),
        )
        assertTrue(read.isAgentQuestion.not())
    }

    // ── the approval record ────────────────────────────────────────────────
    // approval.request/resolved are transient on the wire, so before the
    // daemon stamped the choice onto tool.complete there was no durable
    // evidence that the maintainer personally authorised a command. Null must keep
    // meaning "nobody was asked" — not "denied", and not "approved quietly".

    @Test
    fun `the approval choice rides tool_complete onto the part`() {
        val part = upsertToolPart(
            parts = listOf(
                ChatMessagePart.ToolCall(
                    toolCallId = "t1",
                    toolName = "Bash",
                    args = JsonObject(emptyMap()),
                    argsText = "{}",
                ),
            ),
            payload = buildJsonObject {
                put("tool_use_id", "t1")
                putJsonObject("approval") { put("choice", "once") }
            },
            phase = ToolPhase.Complete,
        ).filterIsInstance<ChatMessagePart.ToolCall>().single()
        assertEquals("once", part.approvalChoice)
    }

    @Test
    fun `no approval block leaves the choice null — nobody was asked`() {
        val part = upsertToolPart(
            parts = listOf(
                ChatMessagePart.ToolCall(
                    toolCallId = "t1",
                    toolName = "Bash",
                    args = JsonObject(emptyMap()),
                    argsText = "{}",
                ),
            ),
            payload = buildJsonObject { put("tool_use_id", "t1") },
            phase = ToolPhase.Complete,
        ).filterIsInstance<ChatMessagePart.ToolCall>().single()
        assertEquals(null, part.approvalChoice)
    }

    @Test
    fun `the decision survives a cold-load round trip through Room`() {
        val message = ChatMessage(
            id = "m2",
            role = ChatRole.Assistant,
            parts = listOf(
                ChatMessagePart.ToolCall(
                    toolCallId = "t1",
                    toolName = "Bash",
                    args = JsonObject(emptyMap()),
                    argsText = "{}",
                    result = JsonObject(emptyMap()),
                    approvalChoice = "deny",
                ),
            ),
            timestamp = 1L,
        )
        val rebuilt = message.toMessageEntity(sessionKey = "s1", json = json).toChatMessage(json)
        assertEquals(
            "deny",
            rebuilt.parts.filterIsInstance<ChatMessagePart.ToolCall>().single().approvalChoice,
        )
    }

    @Test
    fun `every server choice reads as something the maintainer did, and an unknown one is still recorded`() {
        assertTrue(approvalRecordLabel("once").startsWith("You allowed"))
        assertTrue(approvalRecordLabel("session").startsWith("You allowed"))
        assertTrue(approvalRecordLabel("always").startsWith("You allowed"))
        assertTrue(approvalRecordLabel("deny").startsWith("You denied"))
        assertTrue(approvalRecordLabel("weird-new-choice").contains("weird-new-choice"))
        assertEquals("denied", approvalRecordChip("deny"))
        assertEquals("allowed", approvalRecordChip("once"))
    }

    // ── the settled record collapses to one line ───────────────────────────
    // A finished question is scrollback, not an interface — but the line it
    // collapses to must still say what was DECIDED, or the card stops doing
    // the one job it exists for.

    @Test
    fun `a settled ask collapses to the answers themselves`() {
        val questions = parseClarifyQuestions(askInput)
        assertEquals(
            "Collapse the run",
            clarifyRecordSummary(questions, parseClarifyAnswers(askResult)),
        )
    }

    @Test
    fun `several answers collapse in the order they were asked`() {
        val questions = listOf(
            ClarifyQuestion("First?", "One", emptyList(), false),
            ClarifyQuestion("Second?", "Two", emptyList(), false),
        )
        assertEquals(
            "yes · no",
            clarifyRecordSummary(questions, mapOf("Second?" to "no", "First?" to "yes")),
        )
    }

    @Test
    fun `a partly answered ask summarizes what WAS answered`() {
        val questions = listOf(
            ClarifyQuestion("First?", "One", emptyList(), false),
            ClarifyQuestion("Second?", "Two", emptyList(), false),
        )
        assertEquals("yes", clarifyRecordSummary(questions, mapOf("First?" to "yes")))
    }

    @Test
    fun `a dismissal collapses to the dismissal, not to an empty line`() {
        assertEquals(
            DISMISSED,
            clarifyRecordSummary(parseClarifyQuestions(askInput), emptyMap()),
        )
    }

    @Test
    fun `answers that match no question still read as answered`() {
        // A rekeyed or truncated result must not collapse to a blank row —
        // silence would read as "nothing happened here".
        assertEquals(
            "Answered",
            clarifyRecordSummary(parseClarifyQuestions(askInput), mapOf("other" to "x")),
        )
    }

    // ── the dock as a pointer (rec 3) ──────────────────────────────────────
    // parkedQuestionRowKey answers "is there an inline card to point at?".
    // Returning null is the SAFE answer: no row means the docked card is the
    // only way to answer, so it must keep rendering in full.

    private fun msg(vararg parts: ChatMessagePart) =
        ChatMessage(id = "m", role = ChatRole.Assistant, parts = parts.toList(), timestamp = 1L)

    @Test
    fun `an unsettled question yields its row key`() {
        assertEquals(
            "prompt:toolu_01Aec",
            parkedQuestionRowKey(listOf(msg(askPart(result = null)))),
        )
    }

    @Test
    fun `an ANSWERED question is not something to point at`() {
        // It has settled; the agent is no longer blocked, so there is nothing
        // to jump to and the dock has no job.
        assertEquals(null, parkedQuestionRowKey(listOf(msg(askPart()))))
    }

    @Test
    fun `no question at all yields null — the dock keeps the full card`() {
        val read = ChatMessagePart.ToolCall(
            toolCallId = "t1",
            toolName = "Read",
            args = JsonObject(emptyMap()),
            argsText = "{}",
            result = null,
        )
        assertEquals(null, parkedQuestionRowKey(listOf(msg(read))))
        assertEquals(null, parkedQuestionRowKey(emptyList()))
    }

    @Test
    fun `the LAST unsettled question wins — clarifies are serialized per session`() {
        val older = ChatMessagePart.ToolCall(
            toolCallId = "older",
            toolName = "AskUserQuestion",
            args = askInput,
            argsText = askInput.toString(),
            result = askResult,
        )
        val parked = ChatMessagePart.ToolCall(
            toolCallId = "newer",
            toolName = "AskUserQuestion",
            args = askInput,
            argsText = askInput.toString(),
            result = null,
        )
        assertEquals("prompt:newer", parkedQuestionRowKey(listOf(msg(older), msg(parked))))
    }
}
