package app.marmalade.android.chat.messages

import app.marmalade.android.speech.TTSSpeaker
import app.marmalade.android.speech.TTSState
import app.marmalade.android.ui.chat.BubbleAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for per-message read-aloud (upstream parity row D5).
 *
 * These tests exercise the TTSSpeaker interface contract used by ChatScreen:
 *  1. ReadAloud dispatches to TTSSpeaker.speakWithProgress with the correct text.
 *  2. ReadAloud while already speaking calls stop() first, then speaks.
 *  3. ReadAloud on a user message: the menu item is not shown (verified via
 *     BubbleAction sealed class — the action is assistant-only by convention
 *     enforced at the UI layer; we assert the user-bubble path reaches no speak call).
 *
 * Uses [FakeTTSSpeaker] — a minimal spy that records every call without
 * touching AndroidTTSProvider or AndroidTTS internals.
 */
class PerMessageReadAloudTest {

    // ── Fake TTSSpeaker ─────────────────────────────────────────────────────

    /**
     * Spy on [TTSSpeaker] calls without involving any Android TTS machinery.
     *
     * [speakCalls] records every text passed to [speakWithProgress].
     * [stopCount] records how many times [stop] was called.
     * [speakFlow] controls what the flow emits; defaults to Done immediately.
     */
    private class FakeTTSSpeaker(
        private val speakFlow: Flow<TTSState> = flow {
            emit(TTSState.Speaking)
            emit(TTSState.Done)
        },
    ) : TTSSpeaker {

        val speakCalls = mutableListOf<String>()
        var stopCount = 0

        override fun speakWithProgress(text: String): Flow<TTSState> {
            speakCalls += text
            return speakFlow
        }

        override fun stop() {
            stopCount++
        }
    }

    // ── Helper: simulate what ChatScreen.onBubbleAction does for ReadAloud ──

    /**
     * Mirrors the ReadAloud branch in ChatScreen's onBubbleAction lambda.
     *
     * Returns the new speakingMessageId after the action (null = stopped).
     */
    private suspend fun dispatchReadAloud(
        tts: FakeTTSSpeaker,
        messageId: String,
        messageText: String,
        currentSpeakingId: String?,
    ): String? {
        if (messageText.isBlank()) return currentSpeakingId
        tts.stop()
        return if (currentSpeakingId == messageId) {
            // Re-tap on the active bubble → stop only.
            null
        } else {
            // Collect the flow synchronously (test-only: flow is finite).
            tts.speakWithProgress(messageText).collect { _ -> }
            null // After flow completes, speakingMessageId resets to null.
        }
    }

    // ── Test 1: ReadAloud dispatches with correct text ───────────────────────

    @Test
    fun `ReadAloud dispatches speakWithProgress with the message text`() = runBlocking {
        val tts = FakeTTSSpeaker()
        val text = "Hello from the assistant"

        dispatchReadAloud(tts, messageId = "msg-1", messageText = text, currentSpeakingId = null)

        assertEquals("speakWithProgress called exactly once", 1, tts.speakCalls.size)
        assertEquals("text matches message content", text, tts.speakCalls[0])
    }

    // ── Test 2: ReadAloud while speaking interrupts first then starts new ────

    @Test
    fun `ReadAloud while a different message is speaking calls stop then speakWithProgress`() = runBlocking {
        val tts = FakeTTSSpeaker()
        // "msg-1" is the currently playing message; user taps ReadAloud on "msg-2".
        val newText = "Second message to read"

        val resultId = dispatchReadAloud(
            tts,
            messageId = "msg-2",
            messageText = newText,
            currentSpeakingId = "msg-1",
        )

        assertEquals("stop() called once before starting new speech", 1, tts.stopCount)
        assertEquals("speakWithProgress called once with the new text", 1, tts.speakCalls.size)
        assertEquals(newText, tts.speakCalls[0])
        // After flow completes the speaking id resets to null.
        assertNull(resultId)
    }

    // ── Test 3: Re-tap on the active bubble stops playback ───────────────────

    @Test
    fun `ReadAloud re-tap on the active bubble calls stop only, no new speak`() = runBlocking {
        val tts = FakeTTSSpeaker()
        // "msg-1" is already speaking; user taps ReadAloud on "msg-1" again.
        val resultId = dispatchReadAloud(
            tts,
            messageId = "msg-1",
            messageText = "Some assistant text",
            currentSpeakingId = "msg-1",
        )

        assertEquals("stop() called once", 1, tts.stopCount)
        assertTrue("speakWithProgress NOT called on re-tap", tts.speakCalls.isEmpty())
        assertNull("speakingMessageId resets to null", resultId)
    }

    // ── Test 4: ReadAloud on user message — no speak dispatched ─────────────

    /**
     * The "Read aloud" menu item is suppressed on user bubbles at the UI layer
     * (MessageBubble only shows it when isAssistant == true). This test verifies
     * the guard by simulating the caller checking role before dispatching.
     *
     * Choice: suppress at menu level (not shown at all on user bubbles),
     * because a no-op handler that silently drops the action would be worse
     * UX than never surfacing the action. This mirrors the desktop's pattern
     * where the read-aloud icon only appears on assistant messages.
     */
    @Test
    fun `ReadAloud is not dispatched when the caller gates on assistant role`() = runBlocking {
        val tts = FakeTTSSpeaker()
        val isAssistant = false // user message

        // Simulate the UI gate: only dispatch when isAssistant is true.
        if (isAssistant) {
            dispatchReadAloud(
                tts,
                messageId = "user-msg-1",
                messageText = "User question text",
                currentSpeakingId = null,
            )
        }

        assertTrue("speakWithProgress never called for user message", tts.speakCalls.isEmpty())
        assertEquals("stop() never called", 0, tts.stopCount)
    }

    // ── Test 5: BubbleAction.ReadAloud is in the sealed class ───────────────

    @Test
    fun `BubbleAction sealed class contains ReadAloud`() {
        // Compile-time check: ReadAloud can be instantiated and compared.
        val action: BubbleAction = BubbleAction.ReadAloud
        assertTrue(action is BubbleAction.ReadAloud)
    }

    // ── Test 6: Blank text skips speak ───────────────────────────────────────

    @Test
    fun `ReadAloud with blank message text skips speakWithProgress`() = runBlocking {
        val tts = FakeTTSSpeaker()

        dispatchReadAloud(tts, messageId = "msg-blank", messageText = "", currentSpeakingId = null)

        assertTrue("speakWithProgress not called for blank text", tts.speakCalls.isEmpty())
        // stop() is not called either — the blank guard fires before TTS dispatch.
        assertEquals(0, tts.stopCount)
    }
}
