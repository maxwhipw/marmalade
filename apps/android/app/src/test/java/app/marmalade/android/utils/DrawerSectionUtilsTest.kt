package app.marmalade.android.utils

import app.marmalade.android.SessionUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's section-collapse defaults (design lab `drawer-sections` option C
 * / ADR 0014).
 *
 * What is worth pinning is the interaction between the default and the user's
 * toggle: a section nobody has touched follows app state, and a section that
 * HAS been touched keeps the user's choice even when the default would now
 * disagree — which is the case a "expanded = toggled || running" shortcut gets
 * wrong.
 */
class DrawerSectionUtilsTest {

    private fun session(
        id: String,
        running: Boolean = false,
        awaitingInput: Boolean = false,
        serverUnread: Boolean = false,
    ) = SessionUiModel(
        id = id,
        title = id,
        createdAt = 0,
        isGateway = true,
        running = running,
        awaitingInput = awaitingInput,
        serverUnread = serverUnread,
    )

    @Test
    fun `quick sessions are expanded until the user says otherwise`() {
        assertTrue(DrawerSectionUtils.quickSessionsExpanded(null))
        assertFalse(DrawerSectionUtils.quickSessionsExpanded(false))
        assertTrue(DrawerSectionUtils.quickSessionsExpanded(true))
    }

    @Test
    fun `terminals open themselves when a shell exists`() {
        assertFalse(DrawerSectionUtils.terminalsExpanded(toggled = null, openCount = 0))
        assertTrue(DrawerSectionUtils.terminalsExpanded(toggled = null, openCount = 1))
    }

    @Test
    fun `a user collapse of terminals survives a running shell`() {
        // The section exists for trackability, but the user closing it is a
        // stronger signal than the default that opened it.
        assertFalse(DrawerSectionUtils.terminalsExpanded(toggled = false, openCount = 3))
        assertTrue(DrawerSectionUtils.terminalsExpanded(toggled = true, openCount = 0))
    }

    @Test
    fun `a collapsed header defers to the shared status ranking`() {
        // The ranking itself is pinned by SessionStatusUtilsTest; what matters
        // here is that the header does not re-implement it. Its own
        // SectionStatus enum was deleted on 2026-07-26 for exactly that reason.
        assertEquals(
            SessionStatus.RUNNING,
            DrawerSectionUtils.sessionsStatus(
                listOf(
                    session("a", serverUnread = true),
                    session("b", awaitingInput = true),
                    session("c", running = true),
                )
            ),
        )
        assertEquals(
            SessionStatus.UNREAD,
            DrawerSectionUtils.sessionsStatus(listOf(session("a", serverUnread = true))),
        )
    }

    @Test
    fun `a quiet section shows no dot`() {
        assertEquals(
            SessionStatus.IDLE,
            DrawerSectionUtils.sessionsStatus(listOf(session("a"), session("b"))),
        )
        assertEquals(
            SessionStatus.IDLE,
            DrawerSectionUtils.sessionsStatus(emptyList()),
        )
    }
}
