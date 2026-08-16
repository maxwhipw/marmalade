package app.marmalade.android.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [wakeReentryAction] — the wake-word barge-in decision
 * used when the voice popup is already open (see MarmaladeVoiceSession.onShow →
 * handleWakeReentry). No Android / VoiceInteractionSession stack needed.
 */
class WakeReentryActionTest {

    @Test
    fun `mid-reply states barge in`() {
        listOf(
            AssistantState.SPEAKING,
            AssistantState.PREPARING_SPEECH,
            AssistantState.THINKING,
            AssistantState.PROCESSING,
        ).forEach { state ->
            assertEquals(
                "state $state should barge in",
                WakeReentryAction.BARGE_IN,
                wakeReentryAction(state),
            )
        }
    }

    @Test
    fun `already listening ignores the redundant trigger`() {
        assertEquals(WakeReentryAction.IGNORE, wakeReentryAction(AssistantState.LISTENING))
    }

    @Test
    fun `idle and error start a fresh listen`() {
        assertEquals(WakeReentryAction.START, wakeReentryAction(AssistantState.IDLE))
        assertEquals(WakeReentryAction.START, wakeReentryAction(AssistantState.ERROR))
    }

    @Test
    fun `every AssistantState maps to an action`() {
        // Guards against a new state slipping past the when without a decision.
        AssistantState.entries.forEach { wakeReentryAction(it) }
    }
}
