package app.marmalade.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The docked clarify card's state machine (maintainer, on-device 2026-08-01: a
 * two-question ask ran off the top of the screen with the answer button
 * unreachable, so the card became a one-question-at-a-time wizard).
 *
 * Two things are being pinned here. The navigation rules — advance on a
 * single-select pick, stay put on multi-select, back restores what was chosen
 * — and, more importantly, that whatever the wizard collects still folds into
 * exactly the wire shape ClarifyRespondParams specifies (answers = question
 * text → chosen answer, multi-select comma-joined; response only for an ask
 * with no structured questions). The wire contract is frozen; the UI on top of
 * it is not.
 */
class ClarifyWizardTest {

    private fun option(label: String) = ClarifyOption(label, "because $label")

    private fun question(
        text: String,
        multi: Boolean = false,
        options: List<String> = listOf("A", "B"),
    ) = ClarifyQuestion(
        question = text,
        header = text.take(4),
        options = options.map(::option),
        multiSelect = multi,
    )

    private fun wizard(vararg qs: ClarifyQuestion) = ClarifyWizard(qs.toList())

    @Test
    fun `a single question has no step chip and is immediately the last step`() {
        val w = wizard(question("Only?"))
        assertNull(w.stepLabel)
        assertTrue(w.isLastStep)
        assertFalse(w.canGoBack)
        assertFalse(w.canSubmit)
    }

    @Test
    fun `the step chip counts from one`() {
        val w = wizard(question("First?"), question("Second?"), question("Third?"))
        assertEquals("1/3", w.stepLabel)
        assertEquals("2/3", w.next().stepLabel)
        assertEquals("3/3", w.next().next().stepLabel)
    }

    @Test
    fun `a single-select pick advances — but never off the last step`() {
        val w = wizard(question("First?"), question("Second?"))
        assertTrue("mid-wizard single-select carries you forward", w.advancesOnPick)
        // Last step submits via the button, so a mis-tap stays recoverable.
        assertFalse(w.next().advancesOnPick)
    }

    @Test
    fun `a multi-select question never advances on a pick`() {
        val w = wizard(question("Which?", multi = true), question("And?"))
        assertFalse(w.advancesOnPick)
        val picked = w.pick("A").pick("B")
        assertTrue(picked.isPicked("A"))
        assertTrue(picked.isPicked("B"))
        assertEquals(0, picked.index)
    }

    @Test
    fun `single-select replaces the pick, multi-select toggles`() {
        val single = wizard(question("Which?")).pick("A").pick("B")
        assertFalse(single.isPicked("A"))
        assertTrue(single.isPicked("B"))
        // Re-picking the same option clears it (the escape hatch for a mis-tap
        // on the last step, where nothing auto-advances).
        assertFalse(single.pick("B").isPicked("B"))

        val multi = wizard(question("Which?", multi = true)).pick("A").pick("B").pick("A")
        assertFalse(multi.isPicked("A"))
        assertTrue(multi.isPicked("B"))
    }

    @Test
    fun `going back restores the earlier answer`() {
        val w = wizard(question("First?"), question("Second?"))
            .pick("A")
            .next()
            .pick("B")
        assertTrue(w.isPicked("B"))
        val back = w.back()
        assertEquals(0, back.index)
        assertTrue("the first question's pick is still there", back.isPicked("A"))
        assertFalse(back.isPicked("B"))
        // And forward again, unchanged.
        assertTrue(back.next().isPicked("B"))
    }

    @Test
    fun `back at the first step and next at the last are no-ops`() {
        val w = wizard(question("Only?"))
        assertEquals(w, w.back())
        assertEquals(w, w.next())
    }

    @Test
    fun `on a single-select question typing and picking are mutually exclusive`() {
        val typedThenPicked = wizard(question("Which?")).type("something else").pick("A")
        assertEquals("", typedThenPicked.currentText)
        assertTrue(typedThenPicked.isPicked("A"))

        val pickedThenTyped = wizard(question("Which?")).pick("A").type("something else")
        assertFalse(pickedThenTyped.isPicked("A"))
        assertEquals(mapOf("Which?" to "something else"), pickedThenTyped.answers())
    }

    @Test
    fun `on a multi-select question typed text is an extra answer`() {
        val w = wizard(question("Which?", multi = true)).pick("A").pick("B").type("and C")
        assertTrue(w.isPicked("A"))
        assertEquals(mapOf("Which?" to "A, B, and C"), w.answers())
    }

    @Test
    fun `free text is per question, not global`() {
        val w = wizard(question("First?"), question("Second?"))
            .type("one")
            .next()
            .type("two")
        assertEquals("two", w.currentText)
        assertEquals("one", w.back().currentText)
        assertEquals(mapOf("First?" to "one", "Second?" to "two"), w.answers())
    }

    @Test
    fun `unanswered questions are omitted, and submit needs at least one answer`() {
        val w = wizard(question("First?"), question("Second?"))
        assertFalse(w.canSubmit)
        assertEquals(emptyMap<String, String>(), w.answers())

        val partial = w.next().pick("B")
        assertTrue(partial.canSubmit)
        assertEquals(mapOf("Second?" to "B"), partial.answers())
        assertNull(partial.response())
    }

    @Test
    fun `whitespace-only text is not an answer`() {
        val w = wizard(question("Which?")).type("   ")
        assertFalse(w.canSubmit)
        assertEquals(emptyMap<String, String>(), w.answers())
    }

    @Test
    fun `an ask with no parsed questions falls back to the freeform response`() {
        // Malformed or unparseable payload: the card degrades to one text box,
        // and its content rides `response`, not `answers`.
        val w = ClarifyWizard(emptyList())
        assertNull(w.current)
        assertTrue(w.isLastStep)
        assertNull(w.stepLabel)
        assertFalse(w.canSubmit)

        val typed = w.type("just do the sensible thing")
        assertTrue(typed.canSubmit)
        assertEquals("just do the sensible thing", typed.response())
        assertEquals(emptyMap<String, String>(), typed.answers())
    }

    @Test
    fun `the submitted payload is exactly what buildClarifyAnswers produces`() {
        val w = wizard(question("Which?", multi = true), question("Then?"))
            .pick("A")
            .pick("B")
            .next()
            .pick("A")
        assertEquals(
            buildClarifyAnswers(mapOf("Which?" to listOf("A", "B"), "Then?" to listOf("A"))),
            w.answers(),
        )
    }

    @Test
    fun `the title says how many were asked`() {
        assertEquals("The agent has a question", clarifyTitle(1))
        assertEquals("The agent has a question", clarifyTitle(0))
        assertEquals("The agent has 3 questions", clarifyTitle(3))
    }
}
