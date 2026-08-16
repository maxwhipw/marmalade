package app.marmalade.android.search

import app.marmalade.android.rpc.types.SearchHit

/**
 * Client-side VISUAL grouping of a flat ranked hit page (maintainer, 2026-07-27).
 *
 * The results list is flat and ranked — that is what paginates, and it is the
 * only shape where a Best/Newest toggle means something unambiguous. Grouping
 * by session was rejected as a *query* shape for exactly that reason. But two
 * consecutive hits from the same conversation repeating the same header row
 * reads badly, so the client folds *consecutive* runs under one header.
 *
 * The word consecutive is load-bearing: a session that reappears further down
 * the ranked page gets a SECOND header. Reordering hits to gather a session's
 * matches together would silently destroy the ranking the daemon computed.
 */
object SearchResultGrouping {

    /** A row in the rendered list: a session header, or a hit under it. */
    sealed interface Row {
        /** Which session this row belongs to — the key into the response's
         *  `sessions` map, and the id to open. */
        val sessionId: String

        data class SessionHeader(override val sessionId: String) : Row

        data class HitRow(val hit: SearchHit) : Row {
            override val sessionId: String get() = hit.session_id
        }
    }

    /**
     * Interleave [hits] with a header row wherever the session changes.
     *
     * Order is preserved exactly; nothing is sorted, dropped or de-duplicated.
     */
    fun group(hits: List<SearchHit>): List<Row> {
        if (hits.isEmpty()) return emptyList()
        val rows = ArrayList<Row>(hits.size + hits.size / 2 + 1)
        var current: String? = null
        for (hit in hits) {
            if (hit.session_id != current) {
                rows += Row.SessionHeader(hit.session_id)
                current = hit.session_id
            }
            rows += Row.HitRow(hit)
        }
        return rows
    }

    /**
     * Stable LazyColumn key for [row].
     *
     * Namespaced like the chat list's keys (`.claude/rules/chat-ui.md`) so a
     * header can never collide with a hit. A header repeats when a session
     * reappears, so its key carries the row index of the run it opens —
     * `message_id` is unique per hit and carries the rest.
     */
    fun keyOf(row: Row, index: Int): String = when (row) {
        is Row.SessionHeader -> "search-session:${row.sessionId}:$index"
        is Row.HitRow -> "search-hit:${row.hit.message_id}"
    }
}
