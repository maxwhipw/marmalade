package app.marmalade.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display-math segmentation (`\$\$…\$\$` + ```math fences) and the HTML
 * template's TeX escaping. Streaming safety hinges on UNTERMINATED math
 * staying plain markdown.
 */
class MathSegmentationTest {

    @Test
    fun `closed dollar block splits into markdown-math-markdown`() {
        val segs = splitAssistantText("Euler:\n\$\$e^{i\\pi} + 1 = 0\$\$\ndone")
        assertEquals(3, segs.size)
        assertEquals("Euler:\n", (segs[0] as TextSegment.Markdown).text)
        assertEquals("e^{i\\pi} + 1 = 0", (segs[1] as TextSegment.Math).tex)
        assertTrue((segs[2] as TextSegment.Markdown).text.contains("done"))
    }

    @Test
    fun `math fence splits, other fences stay markdown`() {
        val segs = splitAssistantText("```math\n\\frac{a}{b}\n```")
        assertEquals(listOf("\\frac{a}{b}"), segs.filterIsInstance<TextSegment.Math>().map { it.tex })

        val code = splitAssistantText("```python\nprint(1)\n```")
        assertTrue(code.single() is TextSegment.Markdown)
    }

    @Test
    fun `dollar-dollar inside a code fence is protected`() {
        val text = "```bash\necho \"cost: \$\$\" && echo \"\$\$ again\"\n```"
        val segs = splitAssistantText(text)
        assertTrue("code fence must not be carved up", segs.single() is TextSegment.Markdown)
    }

    @Test
    fun `unterminated math stays markdown while streaming`() {
        val segs = splitAssistantText("The identity is\n\$\$e^{i\\pi} + 1")
        assertTrue(segs.all { it is TextSegment.Markdown })
    }

    @Test
    fun `multiline display math and multiple equations`() {
        val segs = splitAssistantText("\$\$\na = b\n\$\$\ntext\n\$\$c\$\$")
        val math = segs.filterIsInstance<TextSegment.Math>()
        assertEquals(listOf("a = b", "c"), math.map { it.tex })
    }

    @Test
    fun `empty math renders literally, marmalade blocks still split`() {
        assertTrue(splitAssistantText("\$\$\$\$").single() is TextSegment.Markdown)

        val mixed = splitAssistantText(
            "intro\n```marmalade\n{\"a\":1}\n```\n\$\$x^2\$\$",
        )
        assertTrue(mixed.any { it is TextSegment.Block })
        assertTrue(mixed.any { it is TextSegment.Math })
    }

    @Test
    fun `html template escapes TeX and blocks script breakout`() {
        val html = buildMathHtml(
            tex = "a\\\"b </script><script>alert(1)</script> \\frac{1}{2}",
            colorCss = "#FFFFFF",
            fontSizePx = 14f,
        )
        assertFalse(
            "raw </script> must never appear inside the injected literal",
            html.contains("</script><script>alert"),
        )
        assertTrue("JSON-escaped content present", html.contains("\\\\frac{1}{2}"))
        assertTrue(html.contains("#FFFFFF"))
        assertTrue(html.contains("14.0px"))
    }
}
