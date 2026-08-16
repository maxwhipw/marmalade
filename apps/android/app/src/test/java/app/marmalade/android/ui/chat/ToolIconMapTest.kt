package app.marmalade.android.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import app.marmalade.android.ui.icons.MarmaladeIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The icon map (design-lab `icon-map`, signed off 2026-08-01).
 *
 * Two things are being defended. First [toolBucket]: it used to match a fixed
 * list that only half-covered Claude Code's real tool names, so `Glob`,
 * `WebSearch`, `Task`, `Skill`, `AskUserQuestion`, `TodoWrite`, `NotebookEdit`
 * and every `mcp__server__tool` fell to [ToolBucket.Other] and drew a wrench —
 * a run of eight distinct tools rendered as eight identical icons. Second, the
 * distinctness of the glyphs themselves: `Write` and `Edit` were literally the
 * same `ImageVector`, and `Think` and the subagent mark were both PsychologyAlt.
 */
class ToolIconMapTest {

    @Test
    fun `wire names map to their buckets`() {
        val expected = mapOf(
            // file tools
            "Read" to ToolBucket.Read,
            "read_file" to ToolBucket.Read,
            "view" to ToolBucket.Read,
            "Write" to ToolBucket.Write,
            "write_file" to ToolBucket.Write,
            "Edit" to ToolBucket.Edit,
            "MultiEdit" to ToolBucket.Edit,
            "NotebookEdit" to ToolBucket.Edit,
            "Glob" to ToolBucket.ListDir,
            "ls" to ToolBucket.ListDir,
            "list_dir" to ToolBucket.ListDir,
            // search / shell
            "Grep" to ToolBucket.Search,
            "find" to ToolBucket.Search,
            "Bash" to ToolBucket.Terminal,
            "shell" to ToolBucket.Terminal,
            // web — fetch and search are DIFFERENT buckets: one takes a url,
            // the other a query, and the collapsed summary reads the wrong
            // field if they share one.
            "WebFetch" to ToolBucket.WebFetch,
            "browse" to ToolBucket.WebFetch,
            "WebSearch" to ToolBucket.WebSearch,
            "web_search" to ToolBucket.WebSearch,
            // agent surfaces
            "generate_image" to ToolBucket.Image,
            "Skill" to ToolBucket.Skill,
            "Task" to ToolBucket.Subagent,
            "Agent" to ToolBucket.Subagent,
            "AskUserQuestion" to ToolBucket.Question,
            "TodoWrite" to ToolBucket.Todo,
            "think" to ToolBucket.Think,
            "docs" to ToolBucket.Doc,
        )
        expected.forEach { (name, bucket) ->
            assertEquals("bucket for $name", bucket, toolBucket(name))
        }
    }

    @Test
    fun `matching is case tolerant`() {
        assertEquals(ToolBucket.Todo, toolBucket("todowrite"))
        assertEquals(ToolBucket.Todo, toolBucket("TODOWRITE"))
        assertEquals(ToolBucket.Question, toolBucket("askuserquestion"))
        assertEquals(ToolBucket.Subagent, toolBucket("TASK"))
    }

    @Test
    fun `every mcp namespaced tool buckets on its prefix`() {
        // There is no fixed list of MCP tools — the namespace IS the match.
        assertEquals(ToolBucket.Mcp, toolBucket("mcp__marmalade__update_session_summary"))
        assertEquals(ToolBucket.Mcp, toolBucket("mcp__wiki-helpers__daily_note_append"))
        assertEquals(ToolBucket.Mcp, toolBucket("MCP__Venice__generate_image"))
        // …but the prefix has to be the prefix, not a substring.
        assertEquals(ToolBucket.Other, toolBucket("not_mcp__thing"))
    }

    @Test
    fun `unrecognised names fall to Other`() {
        assertEquals(ToolBucket.Other, toolBucket("frobnicate"))
        assertEquals(ToolBucket.Other, toolBucket(""))
        assertEquals(ToolBucket.Other, toolBucket("tool"))
    }

    @Test
    fun `an mcp call is titled by its own tool name, not the namespace`() {
        assertEquals(
            "Update session summary",
            humanizeToolName("mcp__marmalade__update_session_summary"),
        )
    }

    @Test
    fun `every bucket resolves to a distinct glyph`() {
        val byBucket = ToolBucket.entries.associateWith { iconForTool(sampleNameFor(it)).geometry() }
        assertEquals(ToolBucket.entries.size, byBucket.size)
        // The whole point of the map: no two buckets draw the same shape.
        val collisions = byBucket.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue(
            "buckets sharing a glyph: ${collisions.values.map { g -> g.map { it.key } }}",
            collisions.isEmpty(),
        )
    }

    /** The two collisions the lab found, named so a regression says which. */
    @Test
    fun `write and edit are different glyphs, as are think and subagent`() {
        assertNotEquals(MarmaladeIcons.Write.geometry(), MarmaladeIcons.Edit.geometry())
        assertNotEquals(MarmaladeIcons.Thinking.geometry(), MarmaladeIcons.Subagent.geometry())
    }

    @Test
    fun `every token in the map is a 24dp stroke glyph and unique`() {
        val map = mapOf(
            "icon.tool.terminal" to MarmaladeIcons.Terminal,
            "icon.tool.read" to MarmaladeIcons.Read,
            "icon.tool.write" to MarmaladeIcons.Write,
            "icon.tool.edit" to MarmaladeIcons.Edit,
            "icon.tool.list" to MarmaladeIcons.ListFiles,
            "icon.tool.search" to MarmaladeIcons.Search,
            "icon.tool.web.fetch" to MarmaladeIcons.WebFetch,
            "icon.tool.web.search" to MarmaladeIcons.WebSearch,
            "icon.tool.image" to MarmaladeIcons.Image,
            "icon.tool.skill" to MarmaladeIcons.Skill,
            "icon.tool.subagent" to MarmaladeIcons.Subagent,
            "icon.tool.question" to MarmaladeIcons.Question,
            "icon.tool.todo" to MarmaladeIcons.Todo,
            "icon.tool.mcp" to MarmaladeIcons.Mcp,
            "icon.tool.doc" to MarmaladeIcons.Doc,
            "icon.tool.unknown" to MarmaladeIcons.Unknown,
            "icon.agent.thinking" to MarmaladeIcons.Thinking,
            "icon.agent.approval" to MarmaladeIcons.Approval,
            "icon.agent.secret" to MarmaladeIcons.Secret,
            "icon.agent.sudo" to MarmaladeIcons.Sudo,
            "icon.agent.voice" to MarmaladeIcons.Voice,
            "icon.agent.attachment" to MarmaladeIcons.Attachment,
            "icon.agent.schedule" to MarmaladeIcons.Schedule,
            "icon.agent.plugin" to MarmaladeIcons.Plugin,
            "icon.agent.error" to MarmaladeIcons.Error,
            "icon.agent.done" to MarmaladeIcons.Done,
        )
        assertEquals("the signed-off map is 26 tokens", 26, map.size)
        map.forEach { (token, icon) ->
            assertEquals("$token viewport", 24f, icon.viewportWidth, 0f)
            assertEquals("$token viewport", 24f, icon.viewportHeight, 0f)
            assertTrue("$token draws nothing", icon.root.iterator().hasNext())
        }
        // A copy/paste slip in the generated path data would show up here as a
        // token silently drawing another token's glyph.
        val collisions = map.entries.groupBy { it.value.geometry() }.filterValues { it.size > 1 }
        assertTrue(
            "tokens sharing a glyph: ${collisions.values.map { g -> g.map { it.key } }}",
            collisions.isEmpty(),
        )
    }

    /**
     * Path data only. Comparing whole [ImageVector]s would be useless here:
     * `equals` includes the vector's NAME, so two tokens carrying byte-identical
     * geometry under different names would compare unequal and the collision
     * this test exists to catch would sail through.
     */
    private fun ImageVector.geometry(): List<List<PathNode>> =
        root.filterIsInstance<VectorPath>().map { it.pathData }

    private fun sampleNameFor(bucket: ToolBucket): String = when (bucket) {
        ToolBucket.Read -> "Read"
        ToolBucket.Write -> "Write"
        ToolBucket.Edit -> "Edit"
        ToolBucket.ListDir -> "Glob"
        ToolBucket.Search -> "Grep"
        ToolBucket.Terminal -> "Bash"
        ToolBucket.WebFetch -> "WebFetch"
        ToolBucket.WebSearch -> "WebSearch"
        ToolBucket.Image -> "generate_image"
        ToolBucket.Skill -> "Skill"
        ToolBucket.Subagent -> "Task"
        ToolBucket.Question -> "AskUserQuestion"
        ToolBucket.Todo -> "TodoWrite"
        ToolBucket.Mcp -> "mcp__marmalade__ping"
        ToolBucket.Think -> "think"
        ToolBucket.Doc -> "docs"
        ToolBucket.Other -> "frobnicate"
    }
}
