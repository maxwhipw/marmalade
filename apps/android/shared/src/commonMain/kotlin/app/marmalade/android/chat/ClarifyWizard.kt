package app.marmalade.android.chat

/**
 * One question at a time: the state behind the docked clarify card.
 *
 * A clarify request carries up to four questions, each with up to four
 * options. The card used to stack all of them — the maintainer's screenshot of a
 * two-question ask showed it running off the top of the screen with the
 * answer button unreachable. So the card became a wizard: one question per
 * step, a step chip, a back arrow, and a button that says "Next" until the
 * last step, where it says "Answer".
 *
 * Immutable on purpose. Every transition returns a new value, so the card can
 * hold exactly one `mutableStateOf(ClarifyWizard(...))` and the whole state
 * machine is unit-testable with no Compose runtime (ClarifyWizardTest).
 *
 * Answer semantics, matching the wire contract (ClarifyRespondParams):
 *  - single-select — a pick and typed text are mutually exclusive; whichever
 *    came last wins, because they are two ways of answering the same question
 *    and a card that silently sent both would be lying about what was chosen.
 *  - multi-select — typed text is an ADDITIONAL answer, comma-joined with the
 *    picked labels by [buildClarifyAnswers] (the harness's join contract).
 *  - questions with no answer are omitted, never sent empty; an ask with no
 *    parsed questions at all degrades to a single free-text box whose content
 *    rides `response` instead of `answers`.
 */
data class ClarifyWizard(
    val questions: List<ClarifyQuestion>,
    /** Index of the visible question. Clamped to the question list. */
    val index: Int = 0,
    /** question text → picked option labels. */
    val picks: Map<String, List<String>> = emptyMap(),
    /** question text → free text typed for that question ([FREE_TEXT_ONLY] when
     *  the ask carries no questions). */
    val typed: Map<String, String> = emptyMap(),
) {
    val current: ClarifyQuestion? get() = questions.getOrNull(index)

    /** "2/3" — only when there is more than one question to step through. */
    val stepLabel: String? get() =
        if (questions.size > 1) "${index + 1}/${questions.size}" else null

    val isLastStep: Boolean get() = index >= questions.lastIndex
    val canGoBack: Boolean get() = index > 0

    /** The key this question's free text is stored under. */
    private val currentKey: String get() = current?.question ?: FREE_TEXT_ONLY

    fun isPicked(label: String): Boolean =
        picks[currentKey].orEmpty().contains(label)

    val currentText: String get() = typed[currentKey].orEmpty()

    /**
     * True when picking an option should carry the user to the next question
     * on its own. Single-select only — a multi-select answer isn't finished
     * until they say so — and never on the last step, where the next action is
     * SUBMIT: auto-sending on a mis-tap would be unrecoverable, so the button
     * stays the confirmation.
     */
    val advancesOnPick: Boolean get() = current?.multiSelect == false && !isLastStep

    /** Toggle an option on the current question. Single-select replaces the
     *  pick and clears any typed text; multi-select toggles in place. */
    fun pick(label: String): ClarifyWizard {
        val q = current ?: return this
        val currentPicks = picks[q.question].orEmpty()
        val next = when {
            q.multiSelect && currentPicks.contains(label) -> currentPicks - label
            q.multiSelect -> currentPicks + label
            currentPicks.contains(label) -> emptyList()
            else -> listOf(label)
        }
        return copy(
            picks = picks + (q.question to next),
            typed = if (q.multiSelect) typed else typed - q.question,
        )
    }

    /** Type a free-text answer for the current question. On a single-select
     *  question this clears the picked option (see the class doc). */
    fun type(text: String): ClarifyWizard {
        val q = current
        return copy(
            typed = typed + (currentKey to text),
            picks = if (q == null || q.multiSelect || text.isBlank()) picks else picks - q.question,
        )
    }

    fun next(): ClarifyWizard = if (isLastStep) this else copy(index = index + 1)

    fun back(): ClarifyWizard = if (canGoBack) copy(index = index - 1) else this

    /** The wire `answers` map: question text → chosen answer. */
    fun answers(): Map<String, String> = buildClarifyAnswers(
        questions.associate { q ->
            val labels = picks[q.question].orEmpty()
            val text = typed[q.question].orEmpty().trim()
            q.question to when {
                text.isEmpty() -> labels
                q.multiSelect -> labels + text
                else -> listOf(text)
            }
        },
    )

    /** The wire `response` — freeform text for an ask that carried no
     *  structured questions. Null otherwise: with questions present, typed
     *  text belongs to the question it was typed under. */
    fun response(): String? =
        if (questions.isEmpty()) typed[FREE_TEXT_ONLY]?.trim()?.ifBlank { null } else null

    /** Sending neither answers nor response is the DISMISSAL contract, so the
     *  submit button stays disabled until at least one question is answered.
     *  Dismissing is the X, deliberately a different gesture. */
    val canSubmit: Boolean get() = answers().isNotEmpty() || response() != null

    companion object {
        /** Free-text key for an ask with no structured questions. Not a valid
         *  question text (the daemon never sends an empty one), so it cannot
         *  collide with a real answer key. */
        const val FREE_TEXT_ONLY = ""
    }
}
