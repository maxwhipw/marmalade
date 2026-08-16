package app.marmalade.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownPreprocessorTest {

    // -- normalizeBlockquoteLists ----------------------------------------

    @Test
    fun `inserts blank quote line before ordered list inside blockquote`() {
        val input = """
            > Read foo.kt and answer:
            > 1. one
            > 2. two
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.normalizeBlockquoteLists(input)

        assertEquals(
            """
            > Read foo.kt and answer:
            >
            > 1. one
            > 2. two
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `inserts blank quote line before unordered list inside blockquote`() {
        val input = """
            > intro:
            > - alpha
            > - beta
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.normalizeBlockquoteLists(input)

        assertEquals(
            """
            > intro:
            >
            > - alpha
            > - beta
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `no-op when blockquote already has blank separator`() {
        val input = """
            > intro:
            >
            > 1. one
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.normalizeBlockquoteLists(input)

        assertEquals(input, out)
    }

    @Test
    fun `no-op on consecutive list items inside blockquote`() {
        val input = """
            > 1. one
            > 2. two
            > 3. three
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.normalizeBlockquoteLists(input)

        assertEquals(input, out)
    }

    @Test
    fun `no-op on plain ordered list outside blockquote`() {
        val input = """
            Intro text.
            1. one
            2. two
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.normalizeBlockquoteLists(input)

        assertEquals(input, out)
    }

    @Test
    fun `handles real-world chat-controller-question pattern from gateway`() {
        // The exact shape that triggered the bug the maintainer reported on-device:
        // a `>` blockquote intro line ending in `:`, followed immediately
        // by `> 1. ...` numbered items with no blank separator. Pre-pivot
        // these items disappeared from the rendered bubble.
        val input = """
            Give the agent this:

            > Read `app/src/main/java/app/marmalade/android/chat/ChatController.kt` in the Marmalade Android repo and answer:
            > 1. What timeout is used for `chat.send`
            > 2. what timeout is used for waiting on a pending reply
            > 3. what user-facing error text is shown on pending-reply timeout
            > 4. cite the relevant code lines
            > 5. do not edit anything

            ## Why this is a good test
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.normalizeBlockquoteLists(input)

        // After normalization: a blank `>` line is inserted between the
        // intro paragraph and the ordered list inside the blockquote.
        val expectedFragment = """
            > Read `app/src/main/java/app/marmalade/android/chat/ChatController.kt` in the Marmalade Android repo and answer:
            >
            > 1. What timeout is used for `chat.send`
        """.trimIndent()
        assertTrue(
            "expected normalized output to contain the blank `>` separator",
            out.contains(expectedFragment),
        )
    }

    // -- preprocess() integration -----------------------------------------

    @Test
    fun `preprocess applies normalizeBlockquoteLists`() {
        val input = """
            > intro:
            > 1. one
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.preprocess(input)

        assertTrue(
            "preprocess should run normalizeBlockquoteLists",
            out.contains(">\n> 1. one"),
        )
    }

    @Test
    fun `preprocess strips inbound metadata blocks unchanged`() {
        // Sanity: existing behaviour preserved alongside new normalization.
        val input = """
            Sender (untrusted metadata):
            ```json
            {"name":"x"}
            ```

            Hello world.
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.preprocess(input)

        assertEquals("Hello world.", out.trim())
    }

    // -- renderTaskListMarkers --------------------------------------------

    @Test
    fun `renders empty checkbox as ballot box glyph`() {
        val out = ChatMarkdownPreprocessor.renderTaskListMarkers("- [ ] todo item")
        assertEquals("- ☐ todo item", out)
    }

    @Test
    fun `renders checked checkbox as ballot-box-with-check glyph`() {
        val out = ChatMarkdownPreprocessor.renderTaskListMarkers("- [x] done item")
        assertEquals("- ☑ done item", out)
    }

    @Test
    fun `accepts uppercase X for checked`() {
        val out = ChatMarkdownPreprocessor.renderTaskListMarkers("- [X] done item")
        assertEquals("- ☑ done item", out)
    }

    @Test
    fun `handles asterisk and plus list markers`() {
        assertEquals(
            "* ☐ alpha",
            ChatMarkdownPreprocessor.renderTaskListMarkers("* [ ] alpha"),
        )
        assertEquals(
            "+ ☑ beta",
            ChatMarkdownPreprocessor.renderTaskListMarkers("+ [x] beta"),
        )
    }

    @Test
    fun `handles ordered list markers`() {
        val out = ChatMarkdownPreprocessor.renderTaskListMarkers("1. [ ] first\n2. [x] second")
        assertEquals("1. ☐ first\n2. ☑ second", out)
    }

    @Test
    fun `preserves nested-list indent`() {
        val input = "  - [x] nested item"
        val out = ChatMarkdownPreprocessor.renderTaskListMarkers(input)
        assertEquals("  - ☑ nested item", out)
    }

    @Test
    fun `does not touch bracket text outside list-item position`() {
        val input = "Body text mentioning [x] in passing."
        val out = ChatMarkdownPreprocessor.renderTaskListMarkers(input)
        assertEquals(input, out)
    }

    @Test
    fun `does not touch task syntax inside fenced code block`() {
        val input = """
            Outside the fence:
            - [x] real task

            ```markdown
            - [x] sample syntax shown literally
            - [ ] another sample
            ```

            - [ ] back outside the fence
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.renderTaskListMarkers(input)

        assertTrue("real task converted", out.contains("- ☑ real task"))
        assertTrue("sample literal preserved", out.contains("- [x] sample syntax shown literally"))
        assertTrue("sample literal preserved", out.contains("- [ ] another sample"))
        assertTrue("post-fence task converted", out.contains("- ☐ back outside the fence"))
    }

    @Test
    fun `tilde fence boundary also recognized`() {
        // Tilde fences are valid CommonMark — agents using `~~~` for fences
        // shouldn't see their literal task-syntax samples mangled.
        val input = """
            ~~~
            - [x] inside tildes
            ~~~
            - [x] outside
        """.trimIndent()

        val out = ChatMarkdownPreprocessor.renderTaskListMarkers(input)

        assertTrue("tilde-fenced literal preserved", out.contains("- [x] inside tildes"))
        assertTrue("outside converted", out.contains("- ☑ outside"))
    }

    @Test
    fun `idempotent on already-rendered glyphs`() {
        val input = "- ☐ todo\n- ☑ done"
        val out = ChatMarkdownPreprocessor.renderTaskListMarkers(input)
        assertEquals(input, out)
    }

    @Test
    fun `preprocess applies task list rendering`() {
        val input = "- [ ] one\n- [x] two"
        val out = ChatMarkdownPreprocessor.preprocess(input)
        assertEquals("- ☐ one\n- ☑ two", out)
    }
}
