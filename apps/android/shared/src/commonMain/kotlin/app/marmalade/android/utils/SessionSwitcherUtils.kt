package app.marmalade.android.utils

import app.marmalade.android.SessionUiModel
import app.marmalade.android.rpc.types.TerminalInfo

/**
 * Content assembly for the title-bar session switcher (ADR 0013, step 1).
 *
 * The sheet answers one question — "what else can I switch to from here?" —
 * scoped to the workspace the current session belongs to. Sessions and
 * terminals are peers in that list: a terminal is never *owned* by a session
 * (ADR 0013 decision 4), it just shares the workspace.
 *
 * Pure logic on purpose: scope resolution, the two membership rules, and the
 * search-threshold rule are the parts worth testing, and none of them need
 * Compose or Android.
 */
object SessionSwitcherUtils {

    /**
     * Show the search field once the *unfiltered* row count exceeds this.
     * The maintainer routinely keeps 20+ unarchived sessions in one workspace, so a long
     * list is the normal case, not the edge case — but a 3-row sheet with a
     * search box on top reads as clutter.
     */
    const val SEARCH_THRESHOLD = 10

    data class SessionRow(val session: SessionUiModel, val isCurrent: Boolean)

    data class TerminalRow(val terminal: TerminalInfo, val isCurrent: Boolean)

    /**
     * @param workspaceId null when the current session belongs to no workspace
     *   (Quick sessions, and the daemon-managed main session — which the
     *   session list excludes entirely, so it never appears as a row).
     * @param workspacePath the workspace root, used as the "new session /
     *   new terminal here" cwd. Null in quick scope — the daemon then applies
     *   its own default.
     * @param showSearch computed from the UNFILTERED counts, so typing a query
     *   that narrows the list below the threshold can't yank the field the
     *   user is typing into out from under them.
     */
    data class SwitcherContent(
        val workspaceId: String?,
        val workspaceName: String,
        val workspacePath: String?,
        val sessions: List<SessionRow>,
        val terminals: List<TerminalRow>,
        val showSearch: Boolean,
        val isFiltered: Boolean,
    ) {
        val isEmpty: Boolean get() = sessions.isEmpty() && terminals.isEmpty()
    }

    /**
     * The workspace the user is currently "in", or null for quick scope.
     *
     * Shared with the drawer (ADR 0013: only the CURRENT workspace is expanded
     * by default), so both surfaces agree on where you are.
     */
    fun currentWorkspaceId(
        layout: WorkspaceGroupUtils.WorkspaceLayout,
        terminals: List<TerminalInfo>,
        currentSessionKey: String?,
        currentTerminalId: String? = null,
    ): String? = currentCard(layout, terminals, currentSessionKey, currentTerminalId)?.id

    private fun currentCard(
        layout: WorkspaceGroupUtils.WorkspaceLayout,
        terminals: List<TerminalInfo>,
        currentSessionKey: String?,
        currentTerminalId: String?,
    ): WorkspaceGroupUtils.WorkspaceCard? =
        layout.cards.firstOrNull { c -> c.sessions.any { it.id == currentSessionKey } }
            ?: layout.cards.firstOrNull { c ->
                // A terminal is in no card's session list, so fall back to the
                // terminal's own stamp when we're looking at one.
                currentTerminalId != null &&
                    terminals.firstOrNull { it.terminal_id == currentTerminalId }
                        ?.workspace_id == c.id
            }

    /**
     * Build the sheet's content for whatever the user is currently looking at.
     *
     * Scope resolution: the workspace card holding [currentSessionKey] wins; if
     * no card holds it (a quick session, the main session, or a terminal opened
     * from the Terminals list) the scope is Quick sessions.
     *
     * Terminal membership mirrors [WorkspaceGroupUtils]' session rule exactly —
     * the server-derived `workspace_id` stamp, with a stamp pointing at an
     * unknown workspace falling back to quick scope rather than vanishing.
     */
    fun build(
        layout: WorkspaceGroupUtils.WorkspaceLayout,
        terminals: List<TerminalInfo>,
        currentSessionKey: String?,
        currentTerminalId: String? = null,
        query: String = "",
        quickScopeLabel: String = "Quick sessions",
    ): SwitcherContent {
        val card = currentCard(layout, terminals, currentSessionKey, currentTerminalId)

        val knownIds = layout.cards.map { it.id }.toSet()
        val scopedSessions = card?.sessions ?: layout.quickSessions
        val scopedTerminals = terminals
            .filter { t ->
                if (card == null) t.workspace_id == null || t.workspace_id !in knownIds
                else t.workspace_id == card.id
            }
            .sortedByDescending { it.last_active }

        val needle = query.trim().lowercase()
        val filtered = needle.isNotEmpty()

        val sessionRows = scopedSessions
            .filter { !filtered || it.title.lowercase().contains(needle) }
            .map { SessionRow(it, isCurrent = it.id == currentSessionKey) }
        val terminalRows = scopedTerminals
            .filter {
                !filtered ||
                    it.shell.lowercase().contains(needle) ||
                    it.cwd.lowercase().contains(needle)
            }
            .map { TerminalRow(it, isCurrent = it.terminal_id == currentTerminalId) }

        return SwitcherContent(
            workspaceId = card?.id,
            workspaceName = card?.workspace?.name ?: quickScopeLabel,
            workspacePath = card?.workspace?.path,
            sessions = sessionRows,
            terminals = terminalRows,
            showSearch = scopedSessions.size + scopedTerminals.size > SEARCH_THRESHOLD,
            isFiltered = filtered,
        )
    }
}
