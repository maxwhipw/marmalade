package app.marmalade.android.ui.blocks

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The Kai-adapted JSON repair layer (JsonRepair.kt, Apache-2.0 — see
 * CREDITS.md): every case here is a damage pattern LLMs actually produce.
 * The bar: after repair, kotlinx parseToJsonElement succeeds.
 */
class JsonRepairTest {

    private val json = Json { isLenient = true }

    private fun parses(s: String): Boolean = runCatching { json.parseToJsonElement(s) }.isSuccess

    @Test
    fun `broken key syntax key=bracket becomes key colon bracket`() {
        assertEquals(
            """{"items":["a"],"opts":{"x":1}}""",
            JsonRepair.fixJsonSyntax("""{"items=["a"],"opts={"x":1}}"""),
        )
    }

    @Test
    fun `truncated mid-string is trimmed and closed`() {
        val repaired = JsonRepair.sanitizeJson("""{"type":"text","text":"hello wor""")
        assertEquals(true, parses(repaired))
        assertEquals("""{"type":"text"}""", repaired.replace(" ", ""))
    }

    @Test
    fun `truncated after a comma closes cleanly`() {
        val repaired = JsonRepair.sanitizeJson("""{"type":"list","items":["a","b"],""")
        assertEquals(true, parses(repaired))
    }

    @Test
    fun `orphaned trailing key is dropped`() {
        val repaired = JsonRepair.sanitizeJson("""{"type":"card","title":"x","children"""")
        assertEquals(true, parses(repaired))
        assertEquals("""{"type":"card","title":"x"}""", repaired.replace(" ", ""))
    }

    @Test
    fun `extra closing brace is skipped`() {
        val repaired = JsonRepair.sanitizeJson("""{"type":"divider"}}""")
        assertEquals("""{"type":"divider"}""", repaired)
    }

    @Test
    fun `missing brace between array objects is inserted`() {
        // `,{` inside an object whose parent is an array = forgotten `}`.
        val repaired = JsonRepair.sanitizeJson("""{"children":[{"type":"text","text":"a",{"type":"text","text":"b"}]}""")
        assertEquals(true, parses(repaired))
    }

    @Test
    fun `unclosed nested tree gets all closers appended`() {
        val repaired = JsonRepair.sanitizeJson("""{"type":"column","children":[{"type":"card","children":[{"type":"text","text":"hi"}""")
        assertEquals(true, parses(repaired))
        assertNotNull(UiTreeParser.parse(repaired))
    }

    @Test
    fun `braces inside strings are not treated as structure`() {
        val input = """{"type":"code","code":"if (x) { return; }"}"""
        assertEquals(input, JsonRepair.sanitizeJson(input))
    }
}
