package app.marmalade.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for SettingsRepository.matchTerminationWord() — the pure
 * matcher behind patient-listening submit. Pins the contract:
 *
 *   - trailing STT punctuation ("Over.") must not defeat the match
 *   - matching is case-insensitive
 *   - multi-word terminators ("send it") work
 *   - word boundary required: "hangover" must NOT match "over"
 *   - bare terminator returns an empty remaining string
 */
class TerminationWordTest {

    private val words = listOf("over", "send it", "that's all", "i'm done")

    private fun match(text: String) = SettingsRepository.matchTerminationWord(text, words)

    @Test
    fun plainTerminatorAtEndIsExtracted() {
        assertEquals(Pair("Can you hear me", "over"), match("Can you hear me over"))
    }

    @Test
    fun trailingPunctuationIsIgnored() {
        assertEquals(Pair("Can you hear me?", "over"), match("Can you hear me? Over."))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(Pair("hello", "send it"), match("hello SEND IT"))
    }

    @Test
    fun bareTerminatorReturnsEmptyRemaining() {
        assertEquals(Pair("", "over"), match("Over."))
    }

    @Test
    fun embeddedWordDoesNotTrigger() {
        assertNull(match("I have a hangover"))
        assertNull(match("we walked through the clover"))
    }

    @Test
    fun noTerminatorReturnsNull() {
        assertNull(match("just a normal sentence"))
    }

    @Test
    fun textShorterThanTerminatorDoesNotCrash() {
        // Regression: "hello" (5) vs "send it" (7) produced a negative
        // boundary index and crashed STT mid-stream (on-device 2026-07-04).
        assertNull(match("hello"))
        assertNull(match(""))
        assertNull(match("it"))
    }
}
