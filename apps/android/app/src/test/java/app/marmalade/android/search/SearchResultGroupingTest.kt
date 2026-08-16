package app.marmalade.android.search

import app.marmalade.android.rpc.types.SearchHit
import app.marmalade.android.rpc.types.SearchRoles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping is VISUAL only (maintainer, 2026-07-27): the query stays flat and ranked, so
 * the folding must never reorder. These tests exist mostly to pin that "same
 * session" means *consecutive*, not "gather them all".
 */
class SearchResultGroupingTest {

    private fun hit(session: String, messageId: String, seq: Long = 1L) = SearchHit(
        session_id = session,
        message_id = messageId,
        seq = seq,
        role = SearchRoles.USER,
        ts = 1_753_000_000_000L + seq,
        snippet = "snippet $messageId",
        text = "text $messageId",
    )

    @Test
    fun `an empty page groups to nothing`() {
        assertTrue(SearchResultGrouping.group(emptyList()).isEmpty())
    }

    @Test
    fun `a single hit gets one header`() {
        val rows = SearchResultGrouping.group(listOf(hit("s1", "m1")))
        assertEquals(2, rows.size)
        assertEquals(SearchResultGrouping.Row.SessionHeader("s1"), rows[0])
        assertEquals("m1", (rows[1] as SearchResultGrouping.Row.HitRow).hit.message_id)
    }

    @Test
    fun `consecutive hits from one session share a single header`() {
        val rows = SearchResultGrouping.group(
            listOf(hit("s1", "m1"), hit("s1", "m2"), hit("s1", "m3")),
        )
        assertEquals(1, rows.count { it is SearchResultGrouping.Row.SessionHeader })
        assertEquals(4, rows.size)
    }

    @Test
    fun `a session change opens a new header`() {
        val rows = SearchResultGrouping.group(
            listOf(hit("s1", "m1"), hit("s2", "m2"), hit("s2", "m3")),
        )
        assertEquals(
            listOf("header:s1", "hit:m1", "header:s2", "hit:m2", "hit:m3"),
            rows.map(::describe),
        )
    }

    @Test
    fun `a session reappearing later gets a SECOND header`() {
        // The load-bearing case. Ranking put s1 back at position 3; gathering it
        // under the first s1 header would silently destroy the daemon's ranking.
        val rows = SearchResultGrouping.group(
            listOf(hit("s1", "m1"), hit("s2", "m2"), hit("s1", "m3")),
        )
        assertEquals(
            listOf("header:s1", "hit:m1", "header:s2", "hit:m2", "header:s1", "hit:m3"),
            rows.map(::describe),
        )
    }

    @Test
    fun `grouping preserves hit order exactly and drops nothing`() {
        val hits = listOf(
            hit("s2", "m1"), hit("s2", "m2"), hit("s1", "m3"),
            hit("s3", "m4"), hit("s1", "m5"), hit("s1", "m6"),
        )
        val emitted = SearchResultGrouping.group(hits)
            .filterIsInstance<SearchResultGrouping.Row.HitRow>()
            .map { it.hit }
        assertEquals(hits, emitted)
    }

    @Test
    fun `every row reports the session it belongs to`() {
        val rows = SearchResultGrouping.group(listOf(hit("s1", "m1"), hit("s2", "m2")))
        assertEquals(listOf("s1", "s1", "s2", "s2"), rows.map { it.sessionId })
    }

    @Test
    fun `keys are unique across a page where a session repeats`() {
        val rows = SearchResultGrouping.group(
            listOf(hit("s1", "m1"), hit("s2", "m2"), hit("s1", "m3")),
        )
        val keys = rows.mapIndexed { index, row -> SearchResultGrouping.keyOf(row, index) }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `header and hit keys are namespaced apart`() {
        val header = SearchResultGrouping.Row.SessionHeader("s1")
        val hitRow = SearchResultGrouping.Row.HitRow(hit("s1", "s1"))
        assertTrue(SearchResultGrouping.keyOf(header, 0).startsWith("search-session:"))
        assertTrue(SearchResultGrouping.keyOf(hitRow, 1).startsWith("search-hit:"))
    }

    private fun describe(row: SearchResultGrouping.Row): String = when (row) {
        is SearchResultGrouping.Row.SessionHeader -> "header:${row.sessionId}"
        is SearchResultGrouping.Row.HitRow -> "hit:${row.hit.message_id}"
    }
}
