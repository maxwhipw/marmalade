package app.marmalade.android.search

import app.marmalade.android.rpc.types.SearchCorpus
import app.marmalade.android.rpc.types.SearchSorts
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the user has narrowed the search to.
 *
 * The wire's three scope fields **OR** together, and an absent/empty scope
 * means "everywhere the principal can see" — so [EVERYWHERE] is literally the
 * absence of a scope object, not a magic value. That is why [isEverywhere]
 * exists rather than an `Everywhere` sentinel in the set.
 *
 * [workspaceIds] is deepest-wins on the daemon side: scoping to an umbrella
 * folder EXCLUDES sessions claimed by a workspace nested inside it, exactly as
 * the session list groups them. The scope sheet says so out loud
 * ([DEEPEST_WINS_NOTE]) — it is consistent but surprising the first time.
 */
data class SearchScopeSelection(
    val workspaceIds: Set<String> = emptySet(),
    /** Sessions matching NO workspace. */
    val quickChats: Boolean = false,
    /** Explicit sessions. Find-in-conversation is scope-of-one through here;
     *  the daemon intersects it with what the principal may see, so it is a
     *  narrowing, never a bypass. */
    val sessionIds: List<String> = emptyList(),
    /** WHICH CORPUS, not a fourth narrowing — see [app.marmalade.android.rpc.types.SearchCorpus].
     *  One corpus per query: the wire field is a single enum. The other three
     *  fields still apply INSIDE the archive (the daemon runs archive cwds
     *  through the same workspace matcher), which is why this is a field here
     *  rather than a separate selection type. */
    val corpus: String = SearchCorpus.LIVE,
) {
    /** No NARROWING is applied. Deliberately independent of [corpus]: "search
     *  everywhere" and "search the archive" are orthogonal statements, and the
     *  Everywhere chip stays lit while browsing the archive unscoped. */
    val isEverywhere: Boolean
        get() = workspaceIds.isEmpty() && !quickChats && sessionIds.isEmpty()

    val isArchive: Boolean get() = corpus == SearchCorpus.ARCHIVE

    /** How many user-visible scope chips are lit (quick chats counts as one). */
    val chipCount: Int get() = workspaceIds.size + if (quickChats) 1 else 0

    fun toggleWorkspace(id: String): SearchScopeSelection =
        copy(workspaceIds = if (id in workspaceIds) workspaceIds - id else workspaceIds + id)

    fun toggleQuickChats(): SearchScopeSelection = copy(quickChats = !quickChats)

    fun withCorpus(corpus: String): SearchScopeSelection = copy(corpus = corpus)

    /** Drop every narrowing but STAY in the current corpus — the "search
     *  everywhere instead" widen. Resetting to [EVERYWHERE] would silently
     *  kick the user back to the live corpus, which is a different query, not
     *  a wider one. */
    fun clearedNarrowing(): SearchScopeSelection =
        copy(workspaceIds = emptySet(), quickChats = false, sessionIds = emptyList())

    companion object {
        val EVERYWHERE = SearchScopeSelection()

        /** The sentence the scope sheet must show — the maintainer's locked decision is
         *  keep deepest-wins, but LABEL it. */
        const val DEEPEST_WINS_NOTE =
            "Sessions belong to a workspace by folder, and the deepest folder wins. " +
                "Scoping to a folder that contains another workspace won't include " +
                "that workspace's sessions."

        fun ofSession(sessionId: String) = SearchScopeSelection(sessionIds = listOf(sessionId))
    }
}

/** The wire defaults for `search.messages` — a field equal to its default is
 *  omitted from the request rather than restated. Mirrors
 *  marmalade/packages/protocol/src/methods.ts SearchMessagesParams. */
object SearchDefaults {
    const val MIN_QUERY_LENGTH = 2
    const val INCLUDE_ARCHIVED = false
    const val SORT = SearchSorts.RANK
    const val LIMIT = 20
    const val MAX_LIMIT = 50
    const val OFFSET = 0
}

/** The wire defaults for `search.archive`. Mirrors
 *  marmalade/packages/protocol/src/methods.ts SearchArchiveParams. Note the
 *  page is much larger than [SearchDefaults.LIMIT]: this is a transcript read,
 *  not a ranked result list. */
object SearchArchiveDefaults {
    const val LIMIT = 100
    const val MAX_LIMIT = 200
    const val OFFSET = 0
}

/**
 * Build the `search.messages` params object.
 *
 * Pure and separate from [app.marmalade.android.rpc.MarmaladeRpc] so the exact
 * JSON is unit-testable without a socket. Rules:
 *  - `query` is RAW user text. The daemon builds the FTS MATCH expression;
 *    clients must never send FTS syntax or pre-escape anything.
 *  - `scope` is omitted entirely when everywhere AND live, and each scope field
 *    is omitted when empty — an empty array is not the same statement as
 *    absence to a reader, even though the daemon treats them alike.
 *  - `scope.corpus` is omitted for the live corpus, so a request that does not
 *    use the archive is byte-identical to what this client sent before the
 *    archive existed. That is the point of the omission, not tidiness: an older
 *    daemon must keep seeing exactly the frame it already validates.
 *  - every field at its wire default ([SearchDefaults]) is omitted.
 */
fun buildSearchMessagesParams(
    query: String,
    scope: SearchScopeSelection = SearchScopeSelection.EVERYWHERE,
    role: String? = null,
    since: Long? = null,
    includeArchived: Boolean = SearchDefaults.INCLUDE_ARCHIVED,
    sort: String = SearchDefaults.SORT,
    limit: Int = SearchDefaults.LIMIT,
    offset: Int = SearchDefaults.OFFSET,
): JsonObject = buildJsonObject {
    put("query", query)
    // The archive corpus lives inside `scope`, so an unscoped archive search
    // still needs the object — it is the only thing in it.
    if (!scope.isEverywhere || scope.isArchive) {
        putJsonObject("scope") {
            if (scope.workspaceIds.isNotEmpty()) {
                putJsonArray("workspace_ids") { scope.workspaceIds.forEach { add(it) } }
            }
            if (scope.quickChats) put("quick_chats", true)
            if (scope.sessionIds.isNotEmpty()) {
                putJsonArray("session_ids") { scope.sessionIds.forEach { add(it) } }
            }
            if (scope.isArchive) put("corpus", scope.corpus)
        }
    }
    if (role != null) put("role", role)
    if (since != null) put("since", since)
    if (includeArchived != SearchDefaults.INCLUDE_ARCHIVED) put("include_archived", includeArchived)
    if (sort != SearchDefaults.SORT) put("sort", sort)
    if (limit != SearchDefaults.LIMIT) put("limit", limit)
    if (offset != SearchDefaults.OFFSET) put("offset", offset)
}

/**
 * Build the `search.archive` params object — one page of ONE archive session's
 * transcript.
 *
 * [sessionId] is an ARCHIVE session id (a Claude Code UUID), as carried by an
 * archive hit's `session_id`. Passing a live daemon session id here is a bug,
 * not a fallback: the daemon answers InvalidParams because the archive index has
 * never heard of it.
 *
 * Same omit-at-default rule as [buildSearchMessagesParams].
 */
fun buildSearchArchiveParams(
    sessionId: String,
    limit: Int = SearchArchiveDefaults.LIMIT,
    offset: Int = SearchArchiveDefaults.OFFSET,
): JsonObject = buildJsonObject {
    put("session_id", sessionId)
    if (limit != SearchArchiveDefaults.LIMIT) put("limit", limit)
    if (offset != SearchArchiveDefaults.OFFSET) put("offset", offset)
}
