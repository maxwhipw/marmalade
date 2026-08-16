package app.marmalade.android.utils

import app.marmalade.android.SessionUiModel

/**
 * Collapse rules for the drawer's top-level sections (design lab
 * `drawer-sections`, option C — see ADR 0014).
 *
 * The maintainer asked for a segmented Workspaces | Chats | Terminals switcher; the lab's
 * objection was that a segment hides a running shell behind a dot, which is the
 * exact failure ADR 0013 made terminals top-level to prevent. Collapsing keeps
 * the bounded list without the mode: a collapsed header still carries its count
 * and its status, and expanding happens in place.
 *
 * Pure because the two rules worth pinning are defaults, not drawing: a section
 * the user has never touched follows the app's state, and a section they HAVE
 * touched follows them — including back to a state the default disagrees with.
 */
object DrawerSectionUtils {

    /**
     * Quick sessions default to expanded: they are ungrouped by definition, so
     * there is no workspace context that makes hiding them the obvious default.
     */
    fun quickSessionsExpanded(toggled: Boolean?): Boolean = toggled ?: true

    /**
     * Terminals default to expanded **while any shell is open** and collapsed
     * otherwise. Deliberately "open", not "working": since 2026-07-26 a
     * terminal row distinguishes the two, and you want the section unfolded to
     * see the shells you have — not only the ones mid-command.
     *
     * Trackability of a live shell is the section's whole reason for being
     * top-level (ADR 0013 decision 4); an empty Terminals section is just a
     * header and a "none running" hint, which is exactly what is worth folding
     * away.
     *
     * The default is re-evaluated as terminals come and go, so starting a shell
     * re-opens the section — unless the user has toggled it, in which case
     * their choice stands.
     */
    fun terminalsExpanded(toggled: Boolean?, openCount: Int): Boolean =
        toggled ?: (openCount > 0)

    /**
     * Aggregate status of a hidden set of sessions.
     *
     * Delegates to [SessionStatusUtils] rather than ranking again: a header and
     * the rows it hides disagreeing about which state matters most is exactly
     * the drift a second copy of the ranking invites. Its own `SectionStatus`
     * enum was deleted for the same reason (2026-07-26).
     */
    fun sessionsStatus(sessions: List<SessionUiModel>): SessionStatus =
        SessionStatusUtils.aggregate(sessions)
}
