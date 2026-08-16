package app.marmalade.android.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clarify.request payload parser + wire-answer builder (daemon clarify
 * round-trip, 2026-07-18). Payload truth: daemon router.ts
 * makeSessionClarifies; answer truth: methods.ts ClarifyRespondParams
 * (answers = question text → chosen answer, multi-select comma-joined).
 */
class ClarifyPromptTest {

    private fun payload(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `parses the daemon's exact questions shape`() {
        val questions = parseClarifyQuestions(
            payload(
                """{"request_id":"rid-1","questions":[{
                    "question":"Which library should we use?","header":"Library",
                    "multi_select":false,
                    "options":[
                        {"label":"Ktor","description":"Kotlin-native HTTP"},
                        {"label":"OkHttp","description":"Battle-tested"}
                    ]}]}""",
            ),
        )
        assertEquals(1, questions.size)
        val q = questions.single()
        assertEquals("Which library should we use?", q.question)
        assertEquals("Library", q.header)
        assertFalse(q.multiSelect)
        assertEquals(listOf(ClarifyOption("Ktor", "Kotlin-native HTTP"), ClarifyOption("OkHttp", "Battle-tested")), q.options)
    }

    @Test
    fun `multi_select true is honored`() {
        val questions = parseClarifyQuestions(
            payload("""{"questions":[{"question":"Which features?","header":"Features","multi_select":true,"options":[]}]}"""),
        )
        assertTrue(questions.single().multiSelect)
    }

    @Test
    fun `malformed payloads degrade to empties - never throw`() {
        assertEquals(emptyList<ClarifyQuestion>(), parseClarifyQuestions(payload("""{"request_id":"rid-1"}""")))
        assertEquals(emptyList<ClarifyQuestion>(), parseClarifyQuestions(payload("""{"questions":"not-an-array"}""")))
        val partial = parseClarifyQuestions(payload("""{"questions":[{"options":null}]}""")).single()
        assertEquals("", partial.question)
        assertEquals(emptyList<ClarifyOption>(), partial.options)
        assertFalse(partial.multiSelect)
    }

    @Test
    fun `answers builder joins multi-select picks and omits empty questions`() {
        val answers = buildClarifyAnswers(
            mapOf(
                "Which features?" to listOf("Voice", "Widgets"),
                "Which library?" to listOf("Ktor"),
                "Unanswered?" to emptyList(),
            ),
        )
        assertEquals(
            mapOf("Which features?" to "Voice, Widgets", "Which library?" to "Ktor"),
            answers,
        )
    }
}
