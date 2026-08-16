package app.marmalade.android.utils

import app.marmalade.android.SessionUiModel

/**
 * The one place that decides what a session row's status indicator says
 * (design lab `session-status`, rounds 1–3; the maintainer's sign-off 2026-07-26).
 *
 * The legend the maintainer locked:
 *
 * | Status | Draws | Means |
 * |---|---|---|
 * | [RUNNING] | green **blocks-shuffle**, animated | a turn is in flight |
 * | [ERROR] | solid red | the run wedged (`run_state=hung`) |
 * | [AWAITING_INPUT] | solid orange | the agent is blocked on you |
 * | [UNREAD] | solid green | there are messages past your read cursor |
 * | [IDLE] | **nothing** | quiet — but the column stays reserved |
 *
 * Two rules are worth stating because they are not derivable from the table:
 *
 * 1. **Running outranks unread.** They are not mutually exclusive — a long turn
 *    can be streaming while earlier messages sit unread. While the turn is in
 *    flight "unread" is barely meaningful (the reply is being written *now*),
 *    and it surfaces the moment the turn lands. Nothing is lost, only deferred.
 * 2. **Running and unread share a colour and differ by form.** Filled = something
 *    happened; moving blocks = something is happening. That is why idle draws
 *    nothing rather than a grey dot: the absence *is* the quiet state, and the
 *    reserved column keeps every title on one left edge.
 */
enum class SessionStatus { RUNNING, ERROR, AWAITING_INPUT, UNREAD, IDLE }

object SessionStatusUtils {

    /**
     * Rank a session. [SessionUiModel.running] wins over [SessionUiModel.hung]
     * because it is fed by the live `status.update` push while `hung` can only
     * arrive on a `session.list` refresh — when they disagree, the push is the
     * fresher fact.
     */
    fun forSession(session: SessionUiModel): SessionStatus = when {
        session.running -> SessionStatus.RUNNING
        session.hung -> SessionStatus.ERROR
        session.awaitingInput -> SessionStatus.AWAITING_INPUT
        session.serverUnread -> SessionStatus.UNREAD
        else -> SessionStatus.IDLE
    }

    /**
     * How recently a terminal must have moved a byte to count as working.
     *
     * Five seconds rather than one: a build with sparse output would otherwise
     * flip between states between one line and the next. Erring long is the
     * cheap direction — a shell shown as working for five seconds after it
     * finished costs nothing, where a working shell shown as idle is a lie.
     */
    const val TERMINAL_ACTIVE_WINDOW_MS = 5_000L

    /**
     * Terminal rows get [SessionStatus.RUNNING] only while the shell is
     * actually moving bytes; a shell sitting at a prompt draws **nothing**
     * (maintainer, 2026-07-26). Every terminal in the roster is alive by definition —
     * a dead one is removed — so "alive" needs no glyph, and spinning three
     * indicators for three idle shells was the drawer's loudest untruth.
     *
     * [lastActive] is the daemon's `TerminalInfo.last_active`, stamped on every
     * byte in BOTH directions (input and output).
     *
     * **Known limit, worth stating because it is invisible from here:** this
     * measures *traffic*, not *a running process*. A command that sleeps
     * silently — `sleep 60`, a compile that buffers — reads as idle. The
     * accurate signal is the PTY's foreground process group (`tpgid` in
     * `/proc/<pid>/stat` differing from the shell's own pid), which only the
     * daemon can see. If terminal state ever needs to be exact, that is the
     * fix; this is the honest approximation available client-side today.
     */
    fun forTerminal(lastActive: Long, now: Long): SessionStatus {
        if (lastActive <= 0L) return SessionStatus.IDLE // old daemon: no stamp
        val age = now - lastActive
        // The lower bound is not paranoia: the stamp is the DAEMON's clock and
        // `now` is the PHONE's. A phone a few seconds behind produces a future
        // stamp, and `age < window` alone would then read as busy forever.
        return if (age in 0 until TERMINAL_ACTIVE_WINDOW_MS) {
            SessionStatus.RUNNING
        } else {
            SessionStatus.IDLE
        }
    }

    /**
     * What a collapsed section header says on behalf of the rows it is hiding.
     * Deliberately the *most urgent* status among them, using the same ranking
     * as [forSession] — a section must never under-report its rows.
     */
    fun aggregate(sessions: List<SessionUiModel>): SessionStatus {
        var best = SessionStatus.IDLE
        for (session in sessions) {
            val status = forSession(session)
            // enum order IS the ranking, so the lowest ordinal wins.
            if (status.ordinal < best.ordinal) best = status
        }
        return best
    }
}
