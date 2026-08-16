package app.marmalade.android.utils

import app.marmalade.android.rpc.types.WorkspaceContextFile
import app.marmalade.android.rpc.types.WorkspaceContextResponse
import app.marmalade.android.utils.WorkspaceContextUtils.PeekTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless tests for the workspace-detail pure logic: building the context
 * strip / peek tabs from a workspace.context response.
 */
class WorkspaceContextUtilsTest {

    private fun ctx(
        claudeMd: WorkspaceContextFile? = null,
        agentsMd: WorkspaceContextFile? = null,
        memory: List<String> = emptyList(),
        branch: String? = null,
    ) = WorkspaceContextResponse(
        workspace_id = "ws1",
        claude_md = claudeMd,
        agents_md = agentsMd,
        memory = memory,
        git_branch = branch,
    )

    private val file = WorkspaceContextFile(content = "hello", truncated = false)

    // ── chips ───────────────────────────────────────────────────────────────

    @Test
    fun `chips omit everything when nothing present`() {
        assertTrue(WorkspaceContextUtils.chips(ctx()).isEmpty())
    }

    @Test
    fun `chips include git branch as an outlined display-only chip`() {
        val chips = WorkspaceContextUtils.chips(ctx(branch = "main"))
        assertEquals(1, chips.size)
        assertEquals("git · main", chips[0].label)
        assertTrue(chips[0].outlined)
        assertEquals(null, chips[0].peek)
    }

    @Test
    fun `chips order is git, CLAUDE, AGENTS, memory`() {
        val chips = WorkspaceContextUtils.chips(
            ctx(claudeMd = file, agentsMd = file, memory = listOf("a.md", "b.md"), branch = "dev"),
        )
        assertEquals(
            listOf("git · dev", "CLAUDE.md", "AGENTS.md", "memory · 2"),
            chips.map { it.label },
        )
        assertEquals(
            listOf(null, PeekTarget.CLAUDE_MD, PeekTarget.AGENTS_MD, PeekTarget.MEMORY),
            chips.map { it.peek },
        )
    }

    @Test
    fun `memory chip omitted when zero notes`() {
        val chips = WorkspaceContextUtils.chips(ctx(claudeMd = file, memory = emptyList()))
        assertEquals(listOf("CLAUDE.md"), chips.map { it.label })
    }

    @Test
    fun `git chip omitted when branch blank`() {
        val chips = WorkspaceContextUtils.chips(ctx(branch = "", claudeMd = file))
        assertEquals(listOf("CLAUDE.md"), chips.map { it.label })
    }

    // ── peek tabs ─────────────────────────────────────────────────────────────

    @Test
    fun `peek tabs mirror present files and never include git`() {
        val tabs = WorkspaceContextUtils.peekTabs(
            ctx(claudeMd = file, memory = listOf("x.md"), branch = "main"),
        )
        assertEquals(listOf(PeekTarget.CLAUDE_MD, PeekTarget.MEMORY), tabs)
    }

    @Test
    fun `peek tabs empty when only git present`() {
        assertTrue(WorkspaceContextUtils.peekTabs(ctx(branch = "main")).isEmpty())
    }
}
