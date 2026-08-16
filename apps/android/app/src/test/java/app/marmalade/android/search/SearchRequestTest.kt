package app.marmalade.android.search

import app.marmalade.android.rpc.types.SearchCorpus
import app.marmalade.android.rpc.types.SearchRoles
import app.marmalade.android.rpc.types.SearchSorts
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exactly what goes on the wire for `search.messages`.
 *
 * These assertions are against
 * marmalade/packages/protocol/src/methods.ts SearchMessagesParams. The daemon
 * validates with zod, so a field this client invents is an InvalidParams on
 * device — cheaper to catch here.
 */
class SearchRequestTest {

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.content?.toInt()
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.content?.toBoolean()

    @Test
    fun `a default request is query only`() {
        val params = buildSearchMessagesParams(query = "seen_at monotonic")
        // Every other field is at its wire default, so restating it would be
        // noise the daemon has to parse.
        assertEquals(setOf("query"), params.keys)
        assertEquals("seen_at monotonic", params.str("query"))
    }

    @Test
    fun `query is sent raw — no FTS escaping client-side`() {
        // The daemon builds the MATCH expression. A client that pre-escaped would
        // double-escape, and a lone double quote is an FTS syntax error, not a
        // no-op — which is exactly why this is the daemon's job.
        val nasty = """compact* "exact phrase" seq_high_water AND -foo"""
        assertEquals(nasty, buildSearchMessagesParams(query = nasty).str("query"))
    }

    @Test
    fun `everywhere scope omits the scope object entirely`() {
        val params = buildSearchMessagesParams(
            query = "unread",
            scope = SearchScopeSelection.EVERYWHERE,
        )
        assertFalse("scope" in params)
    }

    @Test
    fun `workspace-only scope sends just workspace_ids`() {
        val params = buildSearchMessagesParams(
            query = "unread",
            scope = SearchScopeSelection(workspaceIds = setOf("ws-client", "ws-wiki")),
        )
        val scope = params["scope"]!!.jsonObject
        assertEquals(setOf("workspace_ids"), scope.keys)
        assertEquals(
            setOf("ws-client", "ws-wiki"),
            scope["workspace_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    @Test
    fun `quick chats alone is a first-class scope`() {
        val params = buildSearchMessagesParams(
            query = "wake word",
            scope = SearchScopeSelection(quickChats = true),
        )
        val scope = params["scope"]!!.jsonObject
        assertEquals(setOf("quick_chats"), scope.keys)
        assertEquals(true, scope.bool("quick_chats"))
    }

    @Test
    fun `workspaces and quick chats OR together in one scope`() {
        // "workspaces and/or quick chats" was the literal ask — checkboxes, not
        // a radio group, and the wire fields OR.
        val params = buildSearchMessagesParams(
            query = "tithe",
            scope = SearchScopeSelection(workspaceIds = setOf("ws-finance"), quickChats = true),
        )
        val scope = params["scope"]!!.jsonObject
        assertEquals(setOf("workspace_ids", "quick_chats"), scope.keys)
    }

    @Test
    fun `quick chats off is omitted, not sent as false`() {
        val params = buildSearchMessagesParams(
            query = "tithe",
            scope = SearchScopeSelection(workspaceIds = setOf("ws-finance"), quickChats = false),
        )
        assertFalse("quick_chats" in params["scope"]!!.jsonObject)
    }

    @Test
    fun `scope-of-one sends session_ids — the find-in-conversation shape`() {
        val params = buildSearchMessagesParams(
            query = "boundary",
            scope = SearchScopeSelection.ofSession("20260722_091200_abc123"),
        )
        val scope = params["scope"]!!.jsonObject
        assertEquals(setOf("session_ids"), scope.keys)
        assertEquals(
            listOf("20260722_091200_abc123"),
            scope["session_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `role is omitted when anyone and sent when narrowed`() {
        assertNull(buildSearchMessagesParams(query = "x1").str("role"))
        assertEquals(
            SearchRoles.USER,
            buildSearchMessagesParams(query = "x1", role = SearchRoles.USER).str("role"),
        )
    }

    @Test
    fun `since is omitted when absent and sent as a number when set`() {
        assertFalse("since" in buildSearchMessagesParams(query = "x1"))
        assertEquals(
            1_753_000_000_000L,
            buildSearchMessagesParams(query = "x1", since = 1_753_000_000_000L)
                .str("since")!!.toLong(),
        )
    }

    @Test
    fun `include_archived is omitted when false and sent when opted in`() {
        // Archived is out by default — archived is what you pushed out of view.
        assertFalse("include_archived" in buildSearchMessagesParams(query = "x1"))
        assertEquals(
            true,
            buildSearchMessagesParams(query = "x1", includeArchived = true)
                .bool("include_archived"),
        )
    }

    @Test
    fun `sort is omitted for rank and sent for recent`() {
        assertFalse("sort" in buildSearchMessagesParams(query = "x1", sort = SearchSorts.RANK))
        assertEquals(
            SearchSorts.RECENT,
            buildSearchMessagesParams(query = "x1", sort = SearchSorts.RECENT).str("sort"),
        )
    }

    @Test
    fun `limit and offset are omitted at their defaults`() {
        val params = buildSearchMessagesParams(
            query = "x1",
            limit = SearchDefaults.LIMIT,
            offset = SearchDefaults.OFFSET,
        )
        assertFalse("limit" in params)
        assertFalse("offset" in params)
    }

    @Test
    fun `a later page sends offset`() {
        val params = buildSearchMessagesParams(query = "x1", offset = 20)
        assertEquals(20, params.int("offset"))
        // limit is still the default, so it stays off the wire.
        assertFalse("limit" in params)
    }

    @Test
    fun `every field together produces the full frame`() {
        val params = buildSearchMessagesParams(
            query = "compaction boundary",
            scope = SearchScopeSelection(
                workspaceIds = setOf("ws-client"),
                quickChats = true,
                sessionIds = listOf("s1"),
            ),
            role = SearchRoles.ASSISTANT,
            since = 1_750_000_000_000L,
            includeArchived = true,
            sort = SearchSorts.RECENT,
            limit = 50,
            offset = 40,
        )
        assertEquals(
            setOf("query", "scope", "role", "since", "include_archived", "sort", "limit", "offset"),
            params.keys,
        )
        assertEquals(
            setOf("workspace_ids", "quick_chats", "session_ids"),
            params["scope"]!!.jsonObject.keys,
        )
    }

    // ── the archive corpus ──────────────────────────────────────────────────

    @Test
    fun `the live corpus is omitted — an archive-free request is unchanged`() {
        // THE point of omitting it. A client that always sent corpus="live"
        // would change every frame an older daemon already validates, for a
        // field that means exactly what its absence means.
        val withoutCorpus = buildSearchMessagesParams(
            query = "seen_at monotonic",
            scope = SearchScopeSelection(workspaceIds = setOf("ws-client")),
        )
        val explicitlyLive = buildSearchMessagesParams(
            query = "seen_at monotonic",
            scope = SearchScopeSelection(
                workspaceIds = setOf("ws-client"),
                corpus = SearchCorpus.LIVE,
            ),
        )
        assertEquals(withoutCorpus, explicitlyLive)
        assertFalse("corpus" in withoutCorpus["scope"]!!.jsonObject)
    }

    @Test
    fun `an unscoped archive search still sends a scope object`() {
        // The corpus lives INSIDE scope, so "everywhere, in the archive" cannot
        // omit the object the way "everywhere, live" does — it would silently
        // become a live search.
        val params = buildSearchMessagesParams(
            query = "wake word",
            scope = SearchScopeSelection(corpus = SearchCorpus.ARCHIVE),
        )
        val scope = params["scope"]!!.jsonObject
        assertEquals(setOf("corpus"), scope.keys)
        assertEquals(SearchCorpus.ARCHIVE, scope.str("corpus"))
    }

    @Test
    fun `archive narrowing rides alongside the corpus`() {
        // workspace_ids/quick_chats still apply inside the archive — the daemon
        // runs archive cwds through the SAME workspace matcher.
        val params = buildSearchMessagesParams(
            query = "compaction",
            scope = SearchScopeSelection(
                workspaceIds = setOf("ws-client"),
                quickChats = true,
                corpus = SearchCorpus.ARCHIVE,
            ),
        )
        val scope = params["scope"]!!.jsonObject
        assertEquals(setOf("workspace_ids", "quick_chats", "corpus"), scope.keys)
        assertEquals(SearchCorpus.ARCHIVE, scope.str("corpus"))
    }

    @Test
    fun `search_archive params are session_id only at the defaults`() {
        val params = buildSearchArchiveParams(sessionId = "b3f1c2de-0000-4aaa-9999-1234567890ab")
        assertEquals(setOf("session_id"), params.keys)
        assertEquals("b3f1c2de-0000-4aaa-9999-1234567890ab", params.str("session_id"))
    }

    @Test
    fun `a later archive page sends offset`() {
        val params = buildSearchArchiveParams(sessionId = "s-uuid", offset = 100)
        assertEquals(100, params.int("offset"))
        assertFalse("limit" in params)
    }

    @Test
    fun `the archive page size stays inside the daemon's max`() {
        // methods.ts SearchArchiveParams: limit 1..200, default 100.
        assertEquals(100, SearchArchiveDefaults.LIMIT)
        assertEquals(200, SearchArchiveDefaults.MAX_LIMIT)
        assertTrue(SearchArchiveDefaults.LIMIT <= SearchArchiveDefaults.MAX_LIMIT)
    }

    // ── SearchScopeSelection ────────────────────────────────────────────────

    @Test
    fun `the corpus is orthogonal to everywhere`() {
        // "Everywhere" is about NARROWING; the corpus is about WHICH history.
        // Conflating them would dim the Everywhere chip the moment you opened
        // the archive, implying a narrowing that isn't there.
        val archiveEverywhere = SearchScopeSelection(corpus = SearchCorpus.ARCHIVE)
        assertTrue(archiveEverywhere.isEverywhere)
        assertTrue(archiveEverywhere.isArchive)
        assertFalse(SearchScopeSelection.EVERYWHERE.isArchive)
    }

    @Test
    fun `withCorpus replaces rather than accumulates — one corpus per query`() {
        val archive = SearchScopeSelection.EVERYWHERE.withCorpus(SearchCorpus.ARCHIVE)
        assertEquals(SearchCorpus.ARCHIVE, archive.corpus)
        assertEquals(SearchCorpus.LIVE, archive.withCorpus(SearchCorpus.LIVE).corpus)
    }

    @Test
    fun `clearedNarrowing widens the scope but keeps the corpus`() {
        // Widening is not the same as changing which history you're reading —
        // "search everywhere instead" must not kick you back to the live corpus.
        val scoped = SearchScopeSelection(
            workspaceIds = setOf("ws-a"),
            quickChats = true,
            sessionIds = listOf("s1"),
            corpus = SearchCorpus.ARCHIVE,
        )
        val widened = scoped.clearedNarrowing()
        assertTrue(widened.isEverywhere)
        assertTrue(widened.isArchive)
    }

    @Test
    fun `everywhere is the absence of a scope, not a member of it`() {
        assertTrue(SearchScopeSelection.EVERYWHERE.isEverywhere)
        assertFalse(SearchScopeSelection(quickChats = true).isEverywhere)
        assertFalse(SearchScopeSelection(workspaceIds = setOf("w")).isEverywhere)
        assertFalse(SearchScopeSelection.ofSession("s").isEverywhere)
    }

    @Test
    fun `toggling a workspace adds then removes it`() {
        val once = SearchScopeSelection.EVERYWHERE.toggleWorkspace("ws-a")
        assertEquals(setOf("ws-a"), once.workspaceIds)
        assertTrue(once.toggleWorkspace("ws-a").isEverywhere)
    }

    @Test
    fun `chipCount counts quick chats as one scope`() {
        assertEquals(0, SearchScopeSelection.EVERYWHERE.chipCount)
        assertEquals(
            3,
            SearchScopeSelection(workspaceIds = setOf("a", "b"), quickChats = true).chipCount,
        )
    }

    @Test
    fun `the deepest-wins note is stated, because the scope sheet must say it`() {
        // The maintainer's locked decision: keep deepest-wins, but LABEL it. Scoping to his
        // umbrella folder genuinely excludes the nested repo workspaces.
        assertTrue(SearchScopeSelection.DEEPEST_WINS_NOTE.contains("deepest"))
    }

    @Test
    fun `the minimum query length matches the daemon's floor`() {
        // methods.ts: query: z.string().min(2)
        assertEquals(2, SearchDefaults.MIN_QUERY_LENGTH)
    }

    @Test
    fun `the page size stays inside the daemon's max`() {
        assertTrue(SearchDefaults.LIMIT <= SearchDefaults.MAX_LIMIT)
        assertEquals(50, SearchDefaults.MAX_LIMIT)
    }
}
