package app.marmalade.android.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marmalade UI v1 tree parsing (spec: marmalade repo
 * docs/dynamic-ui/marmalade-ui-v1.md). Fixtures cover the full v1
 * vocabulary, the tolerant-defaults contract, NDJSON composition, unknown
 * node degradation, and the collect_from response grammar.
 *
 * TWIN SUITE: the daemon repo's `docs/dynamic-ui/fixtures/` holds these
 * same payloads as files, consumed by the webui + CLI renderer tests
 * (packages/ui-tree, packages/cli). Separate git repos — if you change a
 * payload here, mirror it there and vice versa.
 */
class UiTreeParserTest {

    // The whole v1 vocabulary in one tree — the shared drift-proof fixture.
    private val fullVocabulary = """
        {"type":"card","title":"Trip planner","children":[
          {"type":"text","text":"Where to?","style":"title","bold":true,"color":"primary"},
          {"type":"divider"},
          {"type":"row","children":[
            {"type":"text_input","id":"dest","label":"Destination","placeholder":"Kyoto"},
            {"type":"checkbox","id":"flex","label":"Flexible dates","checked":true}
          ]},
          {"type":"select","id":"season","label":"Season","options":[{"id":"spring","label":"Spring"},"summer"]},
          {"type":"chip_group","id":"vibe","multi":true,"options":["onsen","food","temples"]},
          {"type":"list","items":["pack light","bring cash"],"ordered":true},
          {"type":"table","columns":["City","Days"],"rows":[["Kyoto","3"],["Nara","1"]]},
          {"type":"code","code":"echo hi","language":"bash"},
          {"type":"alert","text":"Peak season","level":"warning","title":"Heads up"},
          {"type":"progress","value":0.5,"label":"Planning"},
          {"type":"status","text":"Searching","state":"active"},
          {"type":"countdown","seconds":90,"label":"Offer expires"},
          {"type":"button","label":"Plan it","action":"callback","event":"plan","collect_from":["dest","season","vibe","flex"]},
          {"type":"button","label":"Open map","action":"open_url","url":"https://maps.example"},
          {"type":"button","label":"Copy","action":"copy_to_clipboard","text":"itinerary"}
        ]}
    """.trimIndent()

    @Test
    fun `full v1 vocabulary parses into the sealed hierarchy`() {
        val root = UiTreeParser.parse(fullVocabulary)
        val card = root as UiNode.CardNode
        assertEquals("Trip planner", card.title)
        assertEquals(15, card.children.size)
        val text = card.children[0] as UiNode.TextNode
        assertEquals("title", text.style)
        assertTrue(text.bold)
        val row = card.children[2] as UiNode.RowNode
        assertEquals("dest", (row.children[0] as UiNode.TextInputNode).id)
        assertTrue((row.children[1] as UiNode.CheckboxNode).checked)
        val select = card.children[3] as UiNode.SelectNode
        assertEquals(listOf(UiNode.UiOption("spring", "Spring"), UiNode.UiOption("summer", "summer")), select.options)
        val chips = card.children[4] as UiNode.ChipGroupNode
        assertTrue(chips.multi)
        val table = card.children[6] as UiNode.TableNode
        assertEquals(listOf("Kyoto", "3"), table.rows[0])
        val button = card.children[12] as UiNode.ButtonNode
        assertEquals(listOf("dest", "season", "vibe", "flex"), button.collectFrom)
        assertEquals("open_url", (card.children[13] as UiNode.ButtonNode).action)
    }

    @Test
    fun `partial node renders with field defaults - tolerant contract`() {
        val root = UiTreeParser.parse("""{"type":"text"}""") as UiNode.TextNode
        assertEquals("", root.text)
        assertEquals("body", root.style)
        assertEquals("default", root.color)
    }

    @Test
    fun `NDJSON lines compose into an implicit column`() {
        val root = UiTreeParser.parse(
            """
            {"type":"text","text":"one"}
            {"type":"text","text":"two"}
            """.trimIndent(),
        ) as UiNode.ColumnNode
        assertEquals(2, root.children.size)
        assertEquals("two", (root.children[1] as UiNode.TextNode).text)
    }

    @Test
    fun `truncated tree repairs and renders the surviving prefix`() {
        val truncated = """{"type":"column","children":[{"type":"text","text":"kept"},{"type":"text","text":"lost mid-str"""
        val root = UiTreeParser.parse(truncated) as UiNode.ColumnNode
        assertTrue(root.children.isNotEmpty())
        assertEquals("kept", (root.children[0] as UiNode.TextNode).text)
    }

    @Test
    fun `unknown node type degrades to Unknown with its text - never an error`() {
        val root = UiTreeParser.parse("""{"type":"hologram","text":"future thing"}""") as UiNode.Unknown
        assertEquals("hologram", root.type)
        assertEquals("future thing", root.text)
    }

    @Test
    fun `garbage returns null - caller degrades to a code block`() {
        assertNull(UiTreeParser.parse("not json at all"))
        assertNull(UiTreeParser.parse(""))
    }

    @Test
    fun `input without an id is dropped rather than rendered uncollectable`() {
        val root = UiTreeParser.parse("""{"type":"column","children":[{"type":"text_input"},{"type":"text","text":"x"}]}""") as UiNode.ColumnNode
        assertEquals(1, root.children.size)
        assertNotNull(root.children[0] as UiNode.TextNode)
    }

    // ── Interaction response grammar (spec §Interaction contract) ────────────

    @Test
    fun `callback without collect_from synthesizes Pressed line`() {
        val b = UiNode.ButtonNode(label = "Confirm", action = "callback", event = "confirm")
        assertEquals("Pressed: confirm", UiTreeParser.callbackMessage(b, emptyMap()))
        val noEvent = UiNode.ButtonNode(label = "OK", action = "callback")
        assertEquals("Pressed: OK", UiTreeParser.callbackMessage(noEvent, emptyMap()))
    }

    @Test
    fun `callback with collect_from synthesizes Responded with line in collect order`() {
        val b = UiNode.ButtonNode(
            label = "Plan it", action = "callback", event = "plan",
            collectFrom = listOf("dest", "vibe", "flex", "missing"),
        )
        val msg = UiTreeParser.callbackMessage(b, mapOf("dest" to "kyoto", "vibe" to "onsen,food", "flex" to "true"))
        assertEquals("Responded with: plan: dest=kyoto; vibe=onsen,food; flex=true; missing=", msg)
    }
}
