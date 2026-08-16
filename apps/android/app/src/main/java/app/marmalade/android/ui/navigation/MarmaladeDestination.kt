package app.marmalade.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Named navigation destinations.
 *
 * The bottom navigation bar these were built for is gone (ADR 0013 — the
 * drawer is the only navigator), so the icon pairs survive only where a
 * destination still renders an icon somewhere.
 */
sealed class MarmaladeDestination(
    val route: String,
    val label: String,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
) {
    data object Home : MarmaladeDestination(
        route = "home",
        label = "Home",
        outlinedIcon = Icons.Outlined.Home,
        filledIcon = Icons.Filled.Home,
    )

    data object Settings : MarmaladeDestination(
        route = "settings",
        label = "Settings",
        outlinedIcon = Icons.Outlined.Settings,
        filledIcon = Icons.Filled.Settings,
    )

    /**
     * Deep-dive log explorer. Renders the full firehose of gateway frames
     * with toggleable per-kind filters and consecutive-message grouping.
     *
     * Reached from the drawer's pinned bottom row (beside Settings) and from
     * Settings → Developer → Frame explorer. It used to be a bottom-bar tab
     * gated on a preference; ADR 0013 deleted the bar, so it is simply a
     * destination now.
     */
    data object Debugging : MarmaladeDestination(
        route = "debugging",
        label = "Debug",
        outlinedIcon = Icons.Outlined.BugReport,
        filledIcon = Icons.Filled.BugReport,
    )

    companion object {
        /** Cross-session full-text search, opened from the drawer's bottom row. */
        const val SEARCH_ROUTE = "search"

        /** Archived sessions, opened from the drawer. Archived rows are hidden
         *  from the drawer's own lists, so this is their only home. */
        const val ARCHIVED_ROUTE = "archived"

        /**
         * Route pattern for the read-only PRE-DAEMON archive transcript viewer.
         *
         * Distinct from [SESSION_DETAIL_ROUTE] on purpose, and not a mode of it:
         * the argument is a Claude Code UUID, not a daemon session key, so
         * routing one to session detail would resume-fail on an id the daemon
         * has never heard of. Its only entry point is an archive search hit.
         *
         * Unrelated to [ARCHIVED_ROUTE] despite the near-name — that lists LIVE
         * sessions you pushed out of view, this reads history that predates the
         * daemon.
         */
        const val ARCHIVE_TRANSCRIPT_ROUTE = "archive_transcript/{archiveSessionId}"

        /** Build a navigation route for a specific archive session uuid. */
        fun archiveTranscriptRoute(archiveSessionId: String): String =
            "archive_transcript/${java.net.URLEncoder.encode(archiveSessionId, "UTF-8")}"

        /** Route pattern for full-screen session detail. */
        const val SESSION_DETAIL_ROUTE = "session_detail/{sessionKey}"

        /** Build a navigation route for a specific session key. */
        fun sessionDetailRoute(sessionKey: String): String =
            "session_detail/${java.net.URLEncoder.encode(sessionKey, "UTF-8")}"

        /** Route pattern for the full-screen workspace detail (not in bottom
         *  bar). Opened by tapping a workspace card's header. */
        const val WORKSPACE_DETAIL_ROUTE = "workspace_detail/{workspaceId}"

        /** Build a navigation route for a specific workspace id. */
        fun workspaceDetailRoute(workspaceId: String): String =
            "workspace_detail/${java.net.URLEncoder.encode(workspaceId, "UTF-8")}"

        /** Route pattern for the full-screen open-terminal (WebView + keys).
         *  Reached from the Sessions Terminals tab / workspace detail. */
        const val TERMINAL_DETAIL_ROUTE = "terminal_detail/{terminalId}"

        /** Build a navigation route for a specific terminal id. */
        fun terminalDetailRoute(terminalId: String): String =
            "terminal_detail/${java.net.URLEncoder.encode(terminalId, "UTF-8")}"
    }
}
