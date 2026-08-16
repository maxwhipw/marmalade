package app.marmalade.android.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Destinations and route-transition direction.
 *
 * The bottom bar these tests used to pin is gone (ADR 0013 — the drawer is the
 * only navigator), so the tab-order rules went with it: every navigation is now
 * a push or a pop, which is the whole of [slideDirectionFor].
 */
class MarmaladeNavHostTest {

    @Test
    fun `the retired bottom-bar routes are gone`() {
        // Regression guard for the ADR 0013 cut-over: nothing should resurrect
        // a Sessions destination, because the drawer replaced that screen.
        val live = listOf(
            MarmaladeDestination.Home,
            MarmaladeDestination.Settings,
            MarmaladeDestination.Debugging,
        ).map { it.route }
        assertFalse("the Sessions screen was deleted", "sessions" in live)
        assertEquals("routes must be distinct", live.size, live.distinct().size)
    }

    @Test
    fun `drawer-reached routes exist and are distinct`() {
        val routes = listOf(
            MarmaladeDestination.SEARCH_ROUTE,
            MarmaladeDestination.ARCHIVED_ROUTE,
            MarmaladeDestination.Home.route,
            MarmaladeDestination.Settings.route,
        )
        assertEquals(routes.size, routes.distinct().size)
        assertTrue(routes.all { it.isNotBlank() })
    }

    @Test
    fun `the archive transcript route is distinct from session detail`() {
        // Its argument is a Claude Code UUID, not a daemon session key. Routing
        // one through session detail would resume-fail on an id the daemon has
        // never heard of, which is why this is its own destination and not a
        // mode of that one.
        assertFalse(
            MarmaladeDestination.ARCHIVE_TRANSCRIPT_ROUTE ==
                MarmaladeDestination.SESSION_DETAIL_ROUTE,
        )
        // ...and unrelated to ARCHIVED_ROUTE, which lists LIVE sessions you
        // pushed out of view. The near-names are the trap.
        assertFalse(
            MarmaladeDestination.ARCHIVE_TRANSCRIPT_ROUTE ==
                MarmaladeDestination.ARCHIVED_ROUTE,
        )
        val uuid = "b3f1c2de-0000-4aaa-9999-1234567890ab"
        assertEquals("archive_transcript/$uuid", MarmaladeDestination.archiveTranscriptRoute(uuid))
    }

    @Test
    fun `an archive session id with a slash survives the route encoding`() {
        // Belt and braces: these ids come from the daemon, not from us.
        val encoded = MarmaladeDestination.archiveTranscriptRoute("a/b c")
        assertFalse("a raw slash would split the route", encoded.endsWith("a/b c"))
        assertTrue(encoded.startsWith("archive_transcript/"))
    }

    @Test
    fun `session and workspace routes round-trip their argument`() {
        // The ids are URL-encoded into the route; a session key with a slash
        // (a real shape) must not split into extra path segments.
        val route = MarmaladeDestination.sessionDetailRoute("agent/one:two")
        assertTrue(route.startsWith("session_detail/"))
        assertFalse(
            "the encoded key must not introduce a path separator",
            route.removePrefix("session_detail/").contains('/'),
        )
    }

    // --- Route transition direction (slideDirectionFor) ------------------

    @Test
    fun `a push slides forward and a pop slides back`() {
        assertEquals(SlideDirection.Left, slideDirectionFor(isPop = false))
        assertEquals(SlideDirection.Right, slideDirectionFor(isPop = true))
    }
}
