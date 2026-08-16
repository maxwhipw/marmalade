package app.marmalade.android.utils

import app.marmalade.android.SessionUiModel
import app.marmalade.android.rpc.types.WorkspaceInfo

/**
 * Workspace grouping for the sessions list (workspace mode). Pure logic, kept
 * out of the ViewModel for headless testing.
 *
 * A [WorkspaceCard] wraps one daemon workspace with the sessions the DERIVED
 * server stamp ([SessionUiModel.workspaceId]) assigned to it — membership is
 * NEVER re-derived client-side from cwd. Sessions whose workspaceId is null (or
 * points at a workspace the list didn't return) fall into a flat "Quick
 * sessions" bucket, matching today's look.
 *
 * Rules:
 *  - One card per workspace in [workspaces]; a card is shown even with zero
 *    sessions (the user just added it — "New chat here" gets them in).
 *  - Cards are ordered by most-recent session activity, newest first; empty
 *    cards (no sessions) sort last among cards, by name.
 *  - Within a card, sessions are most-recent first.
 *  - Unread rolls up: the count of sessions whose serverUnread is true (and not
 *    running — the row suppresses the "New" chip while a turn is live, so the
 *    rollup mirrors it).
 *  - Quick sessions (null / unknown workspaceId) are returned separately, most-
 *    recent first, and always render below the cards.
 */
object WorkspaceGroupUtils {

    /** One collapsible workspace card: the daemon workspace plus its resolved
     *  session rows and rolled-up counts. */
    data class WorkspaceCard(
        val workspace: WorkspaceInfo,
        val sessions: List<SessionUiModel>,
        val unreadCount: Int,
    ) {
        val id: String get() = workspace.workspace_id
        val sessionCount: Int get() = sessions.size
    }

    /** The shaped list for workspace mode: the ordered cards plus the flat
     *  Quick-sessions bucket (rendered last, under a plain header). */
    data class WorkspaceLayout(
        val cards: List<WorkspaceCard>,
        val quickSessions: List<SessionUiModel>,
    )

    fun groupByWorkspace(
        sessions: List<SessionUiModel>,
        workspaces: List<WorkspaceInfo>,
    ): WorkspaceLayout {
        fun activity(s: SessionUiModel) = s.lastMessageAt ?: s.createdAt

        val byWorkspace = sessions.groupBy { it.workspaceId }
        val knownIds = workspaces.map { it.workspace_id }.toSet()

        val cards = workspaces.map { ws ->
            val rows = (byWorkspace[ws.workspace_id] ?: emptyList())
                .sortedByDescending(::activity)
            WorkspaceCard(
                workspace = ws,
                sessions = rows,
                // The row suppresses the "New" chip while running, so the
                // rollup counts only sessions the header would badge.
                unreadCount = rows.count { it.serverUnread && !it.running },
            )
        }.sortedWith(
            // Cards with sessions first, newest activity leading; empty cards
            // (nothing to date them by) sort last, alphabetically by name.
            compareByDescending<WorkspaceCard> { it.sessions.isNotEmpty() }
                .thenByDescending { card -> card.sessions.maxOfOrNull(::activity) ?: Long.MIN_VALUE }
                .thenBy { it.workspace.name.lowercase() },
        )

        // Quick sessions: null workspaceId OR a stamp pointing at a workspace
        // the list didn't return (stale stamp / not-yet-listed) — never drop a
        // session into a phantom card.
        val quick = sessions
            .filter { it.workspaceId == null || it.workspaceId !in knownIds }
            .sortedByDescending(::activity)

        return WorkspaceLayout(cards = cards, quickSessions = quick)
    }
}
