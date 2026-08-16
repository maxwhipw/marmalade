package app.marmalade.android.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitPhrasesTest {

    @Test
    fun exactMatchReturnsTrue() {
        assertTrue(isExitPhrase("exit talk mode"))
    }

    @Test
    fun caseInsensitiveMatch() {
        assertTrue(isExitPhrase("Exit Talk Mode"))
        assertTrue(isExitPhrase("GOODBYE"))
        assertTrue(isExitPhrase("Stop Listening"))
    }

    @Test
    fun leadingAndTrailingWhitespaceIsTrimmed() {
        assertTrue(isExitPhrase("  goodbye  "))
        assertTrue(isExitPhrase("\tstop listening\n"))
    }

    @Test
    fun phraseContainedInLongerSentenceDoesNotMatch() {
        // Exact match only — "tell them goodbye" should NOT match
        assertFalse(isExitPhrase("tell them goodbye"))
        assertFalse(isExitPhrase("please stop listening to me"))
        assertFalse(isExitPhrase("never mind about that"))
    }

    @Test
    fun emptyStringDoesNotMatch() {
        assertFalse(isExitPhrase(""))
        assertFalse(isExitPhrase("   "))
    }

    @Test
    fun unrelatedPhrasesDoNotMatch() {
        assertFalse(isExitPhrase("hello"))
        assertFalse(isExitPhrase("open youtube"))
        assertFalse(isExitPhrase("what is the weather"))
    }

    @Test
    fun allDefinedPhrasesMatch() {
        for (phrase in EXIT_PHRASES) {
            assertTrue("Expected '$phrase' to match isExitPhrase", isExitPhrase(phrase))
        }
    }
}
