package app.marmalade.android.utils

import app.marmalade.android.SessionUiModel
import app.marmalade.android.rpc.types.TerminalInfo
import app.marmalade.android.rpc.types.WorkspaceDetection
import app.marmalade.android.rpc.types.WorkspaceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless tests for the title-bar switcher's content assembly (ADR 0013).
 *
 * The three rules worth pinning: scope follows the CURRENT session's workspace,
 * terminal membership uses the server stamp with a quick-scope fallback (never
 * a phantom workspace), and the search field's visibility is decided on the
 * unfiltered counts so it can't disappear mid-typing.
 */
class SessionSwitcherUtilsTest {

    private fun session(
        id: String,
        workspaceId: String?,
        lastMessageAt: Long = 100,
        title: String = id,
    ) = SessionUiModel(
        id = id,
        title = title,
        createdAt = lastMessageAt,
        isGateway = true,
        lastMessageAt = lastMessageAt,
        workspaceId = workspaceId,
    )

    private fun workspace(id: String) = WorkspaceInfo(
        workspace_id = id,
        path = "/home/user/$id",
        name = id,
        emoji = null,
        detection = WorkspaceDetection(),
    )

    private fun terminal(
        id: String,
        workspaceId: String?,
        lastActive: Long = 0,
        cwd: String = "/home/user",
    ) = TerminalInfo(
        terminal_id = id,
        shell = "bash",
        cwd = cwd,
        workspace_id = workspaceId,
        last_active = lastActive,
    )

    private fun layout(
        sessions: List<SessionUiModel>,
        workspaces: List<WorkspaceInfo>,
    ) = WorkspaceGroupUtils.groupByWorkspace(sessions, workspaces)

    @Test
    fun `scope is the workspace holding the current session`() {
        val content = SessionSwitcherUtils.build(
            layout = layout(
                listOf(session("a", "ws1"), session("b", "ws1"), session("c", "ws2")),
                listOf(workspace("ws1"), workspace("ws2")),
            ),
            terminals = emptyList(),
            currentSessionKey = "a",
        )
        assertEquals("ws1", content.workspaceId)
        assertEquals("/home/user/ws1", content.workspacePath)
        assertEquals(listOf("a", "b"), content.sessions.map { it.session.id })
        assertTrue(content.sessions.first { it.session.id == "a" }.isCurrent)
        assertFalse(content.sessions.first { it.session.id == "b" }.isCurrent)
    }

    @Test
    fun `a session in no workspace opens the sheet in quick scope`() {
        val content = SessionSwitcherUtils.build(
            layout = layout(
                listOf(session("a", "ws1"), session("q", null), session("q2", null)),
                listOf(workspace("ws1")),
            ),
            terminals = emptyList(),
            currentSessionKey = "q",
        )
        assertNull(content.workspaceId)
        assertNull(content.workspacePath)
        assertEquals("Quick sessions", content.workspaceName)
        assertEquals(setOf("q", "q2"), content.sessions.map { it.session.id }.toSet())
    }

    @Test
    fun `the main session is not in the layout, so it falls back to quick scope`() {
        // The ViewModel filters isMain out of workspaceLayout entirely, so the
        // switcher must not blow up (or show a phantom scope) when Home opens it.
        val content = SessionSwitcherUtils.build(
            layout = layout(listOf(session("q", null)), listOf(workspace("ws1"))),
            terminals = emptyList(),
            currentSessionKey = "main-session-id",
        )
        assertNull(content.workspaceId)
        assertEquals(listOf("q"), content.sessions.map { it.session.id })
        assertTrue(content.sessions.none { it.isCurrent })
    }

    @Test
    fun `terminals are scoped by the server stamp, newest activity first`() {
        val content = SessionSwitcherUtils.build(
            layout = layout(listOf(session("a", "ws1")), listOf(workspace("ws1"))),
            terminals = listOf(
                terminal("t-old", "ws1", lastActive = 10),
                terminal("t-new", "ws1", lastActive = 99),
                terminal("t-other", "ws2"),
                terminal("t-quick", null),
            ),
            currentSessionKey = "a",
        )
        assertEquals(listOf("t-new", "t-old"), content.terminals.map { it.terminal.terminal_id })
    }

    @Test
    fun `a stamp pointing at an unknown workspace falls into quick scope`() {
        val content = SessionSwitcherUtils.build(
            layout = layout(listOf(session("q", null)), listOf(workspace("ws1"))),
            terminals = listOf(terminal("t-stale", "ws-deleted"), terminal("t-ws1", "ws1")),
            currentSessionKey = "q",
        )
        assertEquals(listOf("t-stale"), content.terminals.map { it.terminal.terminal_id })
    }

    @Test
    fun `the current terminal decides the scope when no session matches`() {
        val content = SessionSwitcherUtils.build(
            layout = layout(listOf(session("a", "ws1")), listOf(workspace("ws1"))),
            terminals = listOf(terminal("t1", "ws1")),
            currentSessionKey = null,
            currentTerminalId = "t1",
        )
        assertEquals("ws1", content.workspaceId)
        assertTrue(content.terminals.single().isCurrent)
    }

    @Test
    fun `search appears only past the threshold`() {
        val ten = (1..10).map { session("s$it", "ws1") }
        val below = SessionSwitcherUtils.build(
            layout = layout(ten, listOf(workspace("ws1"))),
            terminals = emptyList(),
            currentSessionKey = "s1",
        )
        assertFalse(below.showSearch)

        val above = SessionSwitcherUtils.build(
            layout = layout(ten, listOf(workspace("ws1"))),
            terminals = listOf(terminal("t1", "ws1")),
            currentSessionKey = "s1",
        )
        assertTrue(above.showSearch)
    }

    @Test
    fun `a query that narrows below the threshold keeps the search field`() {
        // Otherwise the field yanks itself out from under the user mid-typing.
        val many = (1..12).map { session("s$it", "ws1", title = "session $it") }
        val content = SessionSwitcherUtils.build(
            layout = layout(many, listOf(workspace("ws1"))),
            terminals = emptyList(),
            currentSessionKey = "s1",
            query = "session 1",
        )
        assertTrue(content.showSearch)
        assertTrue(content.isFiltered)
        // "session 1", "session 10", "session 11", "session 12"
        assertEquals(4, content.sessions.size)
    }

    @Test
    fun `the query matches session titles and terminal cwd, case-insensitively`() {
        val content = SessionSwitcherUtils.build(
            layout = layout(
                listOf(session("a", "ws1", title = "Refactor Router"), session("b", "ws1", title = "Docs")),
                listOf(workspace("ws1")),
            ),
            terminals = listOf(terminal("t1", "ws1", cwd = "/home/user/router")),
            currentSessionKey = "a",
            query = "ROUTER",
        )
        assertEquals(listOf("a"), content.sessions.map { it.session.id })
        assertEquals(listOf("t1"), content.terminals.map { it.terminal.terminal_id })
    }

    @Test
    fun `an empty scope reports empty rather than throwing`() {
        val content = SessionSwitcherUtils.build(
            layout = layout(emptyList(), listOf(workspace("ws1"))),
            terminals = emptyList(),
            currentSessionKey = null,
        )
        assertTrue(content.isEmpty)
        assertFalse(content.isFiltered)
    }
}
