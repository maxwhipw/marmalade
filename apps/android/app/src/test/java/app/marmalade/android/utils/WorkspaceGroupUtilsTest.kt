package app.marmalade.android.utils

import app.marmalade.android.SessionUiModel
import app.marmalade.android.rpc.types.WorkspaceDetection
import app.marmalade.android.rpc.types.WorkspaceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless tests for workspace grouping (sort mode "workspace"). Membership is
 * the server-derived [SessionUiModel.workspaceId] stamp — NEVER re-derived from
 * cwd. Covers: stamp-based bucketing, unread rollups, ordering, Quick-sessions
 * fallout (null + stale stamps), and empty-card placement.
 */
class WorkspaceGroupUtilsTest {

    private fun session(
        id: String,
        workspaceId: String?,
        lastMessageAt: Long,
        serverUnread: Boolean = false,
        running: Boolean = false,
        archived: Boolean = false,
    ) = SessionUiModel(
        id = id,
        title = id,
        createdAt = lastMessageAt,
        isGateway = true,
        lastMessageAt = lastMessageAt,
        workspaceId = workspaceId,
        serverUnread = serverUnread,
        running = running,
        archived = archived,
    )

    private fun workspace(
        id: String,
        name: String = id,
        emoji: String? = null,
        branch: String? = null,
    ) = WorkspaceInfo(
        workspace_id = id,
        path = "/home/user/$id",
        name = name,
        emoji = emoji,
        detection = WorkspaceDetection(git_branch = branch),
    )

    @Test
    fun `sessions bucket by the workspace_id stamp, not cwd`() {
        val layout = WorkspaceGroupUtils.groupByWorkspace(
            sessions = listOf(
                session("a", "ws1", 100),
                session("b", "ws1", 200),
                session("c", "ws2", 150),
            ),
            workspaces = listOf(workspace("ws1"), workspace("ws2")),
        )
        val ws1 = layout.cards.first { it.id == "ws1" }
        // Within a card: most-recent first.
        assertEquals(listOf("b", "a"), ws1.sessions.map { it.id })
        assertEquals(listOf("c"), layout.cards.first { it.id == "ws2" }.sessions.map { it.id })
        assertTrue(layout.quickSessions.isEmpty())
    }

    @Test
    fun `cards ordered by most recent activity, newest first`() {
        val layout = WorkspaceGroupUtils.groupByWorkspace(
            sessions = listOf(
                session("old", "alpha", 100),
                session("new", "beta", 500),
                session("mid", "alpha", 300),
            ),
            workspaces = listOf(workspace("alpha"), workspace("beta")),
        )
        // beta's newest (500) beats alpha's newest (300).
        assertEquals(listOf("beta", "alpha"), layout.cards.map { it.id })
    }

    @Test
    fun `unread rolls up per workspace, running sessions excluded`() {
        val layout = WorkspaceGroupUtils.groupByWorkspace(
            sessions = listOf(
                session("a", "ws1", 100, serverUnread = true),
                session("b", "ws1", 200, serverUnread = true, running = true), // excluded
                session("c", "ws1", 150, serverUnread = false),
            ),
            workspaces = listOf(workspace("ws1")),
        )
        // Two unread but the running one is suppressed (mirrors the row chip).
        assertEquals(1, layout.cards.first().unreadCount)
    }

    @Test
    fun `null workspace_id falls into Quick sessions, most recent first`() {
        val layout = WorkspaceGroupUtils.groupByWorkspace(
            sessions = listOf(
                session("x", null, 900),
                session("y", null, 800),
                session("z", "ws1", 100),
            ),
            workspaces = listOf(workspace("ws1")),
        )
        assertEquals(listOf("x", "y"), layout.quickSessions.map { it.id })
        assertEquals(listOf("z"), layout.cards.first().sessions.map { it.id })
    }

    @Test
    fun `stale stamp pointing at an unknown workspace falls into Quick sessions`() {
        // Session claims "gone" but no such workspace was returned — never drop
        // it into a phantom card.
        val layout = WorkspaceGroupUtils.groupByWorkspace(
            sessions = listOf(
                session("a", "gone", 100),
                session("b", "ws1", 200),
            ),
            workspaces = listOf(workspace("ws1")),
        )
        assertEquals(listOf("a"), layout.quickSessions.map { it.id })
        assertEquals(listOf("b"), layout.cards.first().sessions.map { it.id })
    }

    @Test
    fun `empty workspace still gets a card, sorted last`() {
        val layout = WorkspaceGroupUtils.groupByWorkspace(
            sessions = listOf(session("a", "busy", 100)),
            workspaces = listOf(workspace("empty"), workspace("busy")),
        )
        // The card with sessions leads; the empty one sorts last.
        assertEquals(listOf("busy", "empty"), layout.cards.map { it.id })
        assertTrue(layout.cards.first { it.id == "empty" }.sessions.isEmpty())
    }

    @Test
    fun `empty cards ordered alphabetically by name`() {
        val layout = WorkspaceGroupUtils.groupByWorkspace(
            sessions = emptyList(),
            workspaces = listOf(
                workspace("z", name = "Zebra"),
                workspace("a", name = "Apple"),
            ),
        )
        assertEquals(listOf("Apple", "Zebra"), layout.cards.map { it.workspace.name })
    }

    @Test
    fun `no workspaces means all sessions are Quick, no cards`() {
        val layout = WorkspaceGroupUtils.groupByWorkspace(
            sessions = listOf(
                session("a", null, 100),
                session("b", "orphan", 200),
            ),
            workspaces = emptyList(),
        )
        assertTrue(layout.cards.isEmpty())
        assertEquals(listOf("b", "a"), layout.quickSessions.map { it.id })
    }

    @Test
    fun `card exposes count and rollup fields`() {
        val layout = WorkspaceGroupUtils.groupByWorkspace(
            sessions = listOf(
                session("a", "ws1", 100, serverUnread = true),
                session("b", "ws1", 200),
            ),
            workspaces = listOf(workspace("ws1", branch = "main")),
        )
        val card = layout.cards.first()
        assertEquals(2, card.sessionCount)
        assertEquals(1, card.unreadCount)
        assertEquals("main", card.workspace.detection.git_branch)
    }

    @Test
    fun `empty input yields empty layout`() {
        val layout = WorkspaceGroupUtils.groupByWorkspace(emptyList(), emptyList())
        assertTrue(layout.cards.isEmpty())
        assertTrue(layout.quickSessions.isEmpty())
    }

    // ── Archived filtering (session.archive) ────────────────────────────────
    // groupByWorkspace itself never filters archived — the ViewModel excludes
    // archived rows BEFORE grouping (SessionListViewModel.workspaceLayout:
    // `models.filter { !it.isMain && !it.archived }`). These tests exercise that
    // exact contract plus the "Archived section" selection predicates the
    // screens use, so the pure filtering logic is covered end to end.

    /** The main-list filter the ViewModel applies before grouping. */
    private fun activeOnly(sessions: List<SessionUiModel>) =
        sessions.filter { !it.isMain && !it.archived }

    @Test
    fun `archived sessions are excluded from cards, quick, and unread rollups`() {
        val all = listOf(
            session("live", "ws1", 100, serverUnread = true),
            session("arch-ws", "ws1", 200, serverUnread = true, archived = true), // hidden
            session("quick-live", null, 300),
            session("arch-quick", null, 400, archived = true), // hidden
        )
        val layout = WorkspaceGroupUtils.groupByWorkspace(activeOnly(all), listOf(workspace("ws1")))

        assertEquals("only the active row in the card", listOf("live"), layout.cards.first().sessions.map { it.id })
        assertEquals("archived unread must not roll up", 1, layout.cards.first().unreadCount)
        assertEquals("only the active folderless row is Quick", listOf("quick-live"), layout.quickSessions.map { it.id })
    }

    @Test
    fun `archived quick-section predicate selects folderless and unknown-workspace archived rows`() {
        val archived = listOf(
            session("a", null, 100, archived = true),      // folderless → quick
            session("b", "ws1", 200, archived = true),     // under a shown card → NOT quick
            session("c", "gone", 300, archived = true),    // stale stamp → quick
        )
        val cardIds = setOf("ws1")
        // Mirrors SessionListScreen's archivedQuick predicate.
        val archivedQuick = archived.filter { it.workspaceId == null || it.workspaceId !in cardIds }
        assertEquals(listOf("a", "c"), archivedQuick.map { it.id })
    }

    @Test
    fun `archived workspace-section predicate selects rows for one workspace`() {
        val archived = listOf(
            session("a", "ws1", 100, archived = true),
            session("b", "ws2", 200, archived = true),
            session("c", "ws1", 300, archived = true),
        )
        // Mirrors WorkspaceDetailScreen's archivedForWs filter.
        val forWs1 = archived.filter { it.workspaceId == "ws1" }
        assertEquals(setOf("a", "c"), forWs1.map { it.id }.toSet())
    }
}
