package app.marmalade.android.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuationStripTest {

    @Test
    fun `strips trailing period`() {
        assertEquals("Hello world", stripTrailingPunctuation("Hello world."))
    }

    @Test
    fun `strips trailing question mark`() {
        assertEquals("What", stripTrailingPunctuation("What?"))
    }

    @Test
    fun `no change when no trailing punctuation`() {
        assertEquals("Hello world", stripTrailingPunctuation("Hello world"))
    }

    @Test
    fun `strips trailing ellipsis`() {
        assertEquals("Hello", stripTrailingPunctuation("Hello..."))
    }

    @Test
    fun `preserves mid-sentence punctuation`() {
        assertEquals("Dr. Smith said", stripTrailingPunctuation("Dr. Smith said"))
    }

    @Test
    fun `handles empty string`() {
        assertEquals("", stripTrailingPunctuation(""))
    }

    @Test
    fun `strips trailing exclamation`() {
        assertEquals("Wow", stripTrailingPunctuation("Wow!"))
    }

    @Test
    fun `strips trailing comma`() {
        assertEquals("Hello", stripTrailingPunctuation("Hello,"))
    }

    @Test
    fun `strips trailing semicolon`() {
        assertEquals("Hello", stripTrailingPunctuation("Hello;"))
    }

    @Test
    fun `strips trailing colon`() {
        assertEquals("Hello", stripTrailingPunctuation("Hello:"))
    }

    @Test
    fun `strips multiple trailing punctuation chars`() {
        assertEquals("Really", stripTrailingPunctuation("Really?!"))
    }

    @Test
    fun `handles whitespace-only string`() {
        assertEquals("", stripTrailingPunctuation("   "))
    }
}
