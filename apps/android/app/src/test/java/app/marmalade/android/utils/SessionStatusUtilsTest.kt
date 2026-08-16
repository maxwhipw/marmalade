package app.marmalade.android.utils

import app.marmalade.android.SessionUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status legend the maintainer signed off on 2026-07-26 (design lab `session-status`).
 *
 * These pin the two things that are decisions rather than mechanics — the
 * precedence order, and the fact that `hung` draws at all — because both are
 * invisible in the drawing code and both were got wrong before: `hung` had been
 * on the wire since P2 and rendered as idle, i.e. as "nothing is wrong".
 */
class SessionStatusUtilsTest {

    private fun session(
        id: String = "s",
        running: Boolean = false,
        hung: Boolean = false,
        awaitingInput: Boolean = false,
        serverUnread: Boolean = false,
    ) = SessionUiModel(
        id = id,
        title = id,
        createdAt = 0,
        isGateway = true,
        running = running,
        hung = hung,
        awaitingInput = awaitingInput,
        serverUnread = serverUnread,
    )

    @Test
    fun `a quiet session draws nothing`() {
        assertEquals(SessionStatus.IDLE, SessionStatusUtils.forSession(session()))
    }

    @Test
    fun `each state maps to its own indicator`() {
        assertEquals(SessionStatus.RUNNING, SessionStatusUtils.forSession(session(running = true)))
        assertEquals(SessionStatus.ERROR, SessionStatusUtils.forSession(session(hung = true)))
        assertEquals(
            SessionStatus.AWAITING_INPUT,
            SessionStatusUtils.forSession(session(awaitingInput = true)),
        )
        assertEquals(
            SessionStatus.UNREAD,
            SessionStatusUtils.forSession(session(serverUnread = true)),
        )
    }

    @Test
    fun `running outranks unread, because the reply is being written right now`() {
        // Not mutually exclusive: a long turn can stream while earlier messages
        // sit unread. Unread surfaces the moment the turn lands.
        assertEquals(
            SessionStatus.RUNNING,
            SessionStatusUtils.forSession(session(running = true, serverUnread = true)),
        )
    }

    @Test
    fun `a live running push outranks a hung flag from the last list refresh`() {
        // `running` comes from the status.update push; `hung` can only arrive on
        // a session.list poll. When they disagree the push is the fresher fact.
        assertEquals(
            SessionStatus.RUNNING,
            SessionStatusUtils.forSession(session(running = true, hung = true)),
        )
    }

    @Test
    fun `an error outranks both waiting and unread`() {
        assertEquals(
            SessionStatus.ERROR,
            SessionStatusUtils.forSession(
                session(hung = true, awaitingInput = true, serverUnread = true)
            ),
        )
    }

    @Test
    fun `a collapsed section reports the most urgent row it is hiding`() {
        assertEquals(
            SessionStatus.RUNNING,
            SessionStatusUtils.aggregate(
                listOf(
                    session("a", serverUnread = true),
                    session("b", awaitingInput = true),
                    session("c", running = true),
                )
            ),
        )
        assertEquals(
            SessionStatus.ERROR,
            SessionStatusUtils.aggregate(
                listOf(session("a", serverUnread = true), session("b", hung = true))
            ),
        )
        assertEquals(
            SessionStatus.AWAITING_INPUT,
            SessionStatusUtils.aggregate(
                listOf(session("a", serverUnread = true), session("b", awaitingInput = true))
            ),
        )
    }

    @Test
    fun `a terminal spins only while it is moving bytes`() {
        val now = 1_000_000L
        val window = SessionStatusUtils.TERMINAL_ACTIVE_WINDOW_MS
        // Every row in the roster is a live shell, so "alive" is not the
        // question — "busy" is. A shell parked at a prompt draws nothing.
        assertEquals(
            SessionStatus.RUNNING,
            SessionStatusUtils.forTerminal(lastActive = now - 1, now = now),
        )
        assertEquals(
            SessionStatus.RUNNING,
            SessionStatusUtils.forTerminal(lastActive = now - (window - 1), now = now),
        )
        assertEquals(
            SessionStatus.IDLE,
            SessionStatusUtils.forTerminal(lastActive = now - window, now = now),
        )
        assertEquals(
            SessionStatus.IDLE,
            SessionStatusUtils.forTerminal(lastActive = now - 60_000, now = now),
        )
    }

    @Test
    fun `a terminal with no activity stamp is idle, not busy`() {
        // An old daemon omitting last_active decodes to 0. Defaulting that to
        // "working" would spin every row forever; idle degrades honestly.
        assertEquals(
            SessionStatus.IDLE,
            SessionStatusUtils.forTerminal(lastActive = 0L, now = 1_000_000L),
        )
    }

    @Test
    fun `a clock that has gone backwards does not spin forever`() {
        // A future stamp (device clock behind the daemon's) must not read as a
        // permanently-busy shell.
        assertEquals(
            SessionStatus.IDLE,
            SessionStatusUtils.forTerminal(lastActive = 2_000_000L, now = 1_000_000L),
        )
    }

    @Test
    fun `a section of quiet sessions reports idle`() {
        assertEquals(SessionStatus.IDLE, SessionStatusUtils.aggregate(listOf(session("a"))))
        assertEquals(SessionStatus.IDLE, SessionStatusUtils.aggregate(emptyList()))
    }
}
