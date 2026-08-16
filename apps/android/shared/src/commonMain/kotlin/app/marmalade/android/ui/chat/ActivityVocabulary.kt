package app.marmalade.android.ui.chat

/**
 * Data Flow: ActivityVocabulary
 *
 * `ChatMessage.streamingActivity` (set by ChatController) → string is
 * passed into `verbsFor(activity)` / `pickVerb(activity, index)` → returns
 * a list of evocative verbs (or a single verb + optional subtitle). The
 * UI consumer (ActivityBubble in Phase 2) picks an index per phase
 * transition; this file is pure-Kotlin and has no Compose / Android deps.
 *
 * Activity strings the controller produces:
 *   - "starting"  — gap between chat.send ack and first agent stream event
 *   - "thinking"  — agent is in a thinking phase
 *   - "writing"   — agent is producing assistant text
 *   - "tool:NAME" — agent is invoking tool NAME (e.g. "tool:exec")
 *   - null / unknown — falls back to the default working-verb list
 *
 * The "tool:NAME" branch splits on whether NAME is a recognised
 * built-in OpenClaw tool or an unknown / custom tool:
 *   - Built-in with a curated verb table → curated verbs, no subtitle
 *   - Built-in without a curated entry  → default fallback verbs, no subtitle
 *   - Non-built-in / custom tool         → generic non-OpenClaw verbs +
 *                                           the raw tool name as subtitle
 */
object ActivityVocabulary {

    /**
     * Canonical OpenClaw built-in tool names.
     *
     * Sources:
     *  - Upstream registry: `src/agents/openclaw-tools.ts` in the OpenClaw repo
     *    (createOpenClawTools enumerates every shipped tool factory; the
     *    `name:` literal in each factory is what ends up on the wire).
     *  - Tool factories grepped: canvas, nodes, cron, message, tts,
     *    image_generate, music_generate, video_generate, gateway,
     *    agents_list, update_plan, sessions_*, subagents, session_status,
     *    web_search, web_fetch, image, pdf.
     *  - Common shell / fs / web tool names that appear via OpenClaw's
     *    bundled MCP / plugin tools and the `nodes` tool's command
     *    surface (exec, bash, shell, read_file, read, cat, edit,
     *    write_file, write, apply_patch, fetch_url, search, grep, find,
     *    search_files, glob, list_files, ls). These aren't in the core
     *    registry but they're what the user sees in `tool:NAME` for
     *    routine command-style invocations and are listed explicitly in
     *    §4 of the activity-bubble plan (kept internally).
     *
     * Decision rule for membership: a tool name belongs here if its
     * activity rendering should NOT show the raw tool name as a
     * subtitle (i.e. users recognise the tool, so the verb alone is
     * meaningful). Custom MCP / plugin tools are intentionally excluded
     * so they fall through to the generic verb set + subtitle.
     */
    val BUILT_IN_OPENCLAW_TOOLS: Set<String> = setOf(
        // Core OpenClaw tool registry (createOpenClawTools)
        "canvas",
        "nodes",
        "cron",
        "message",
        "tts",
        "image",
        "image_generate",
        "music_generate",
        "video_generate",
        "pdf",
        "gateway",
        "agents_list",
        "update_plan",
        "sessions_list",
        "sessions_history",
        "sessions_send",
        "sessions_yield",
        "sessions_spawn",
        "subagents",
        "session_status",
        "web_search",
        "web_fetch",
        // Common shell / fs / web command names (bundled MCP + nodes commands)
        "exec",
        "bash",
        "shell",
        "read_file",
        "read",
        "cat",
        "edit",
        "write_file",
        "write",
        "apply_patch",
        "fetch_url",
        "search",
        "grep",
        "find",
        "search_files",
        "glob",
        "list_files",
        "ls",
    )

    // -- Verb tables (per-activity). Each list ordered "most common /
    //    recognisable" first. All lists are non-empty.

    private val STARTING_VERBS = listOf(
        "Warming up", "Booting", "Stretching", "Limbering up", "Spinning up",
    )

    private val THINKING_VERBS = listOf(
        "Thinking", "Pondering", "Contemplating", "Musing",
        "Reflecting", "Noodling", "Mulling",
    )

    /** Compaction is a distinct, longer-running activity the gateway
     *  emits via `status.update {kind: "compacting"}`. Showing the
     *  generic "Thinking…" verb during a 30+ second compress is
     *  misleading — a dedicated label makes the long pause legible.
     *  Matches desktop's `thread.tsx:418` swap on `$compactionActive`. */
    private val COMPACTING_VERBS = listOf(
        "Compacting", "Compressing", "Tidying", "Distilling",
    )

    private val WRITING_VERBS = listOf(
        "Writing", "Generating", "Drafting", "Sculpting", "Composing", "Penning",
    )

    private val DEFAULT_VERBS = listOf(
        "Working", "Tinkering", "Brewing", "Cooking",
    )

    private val EXEC_VERBS = listOf(
        "Bashing", "Executing", "Running", "Invoking", "Moseying",
    )

    private val READ_VERBS = listOf(
        "Reading", "Skimming", "Perusing", "Studying",
    )

    private val EDIT_VERBS = listOf(
        "Editing", "Refining", "Tweaking", "Sculpting",
    )

    private val WEB_VERBS = listOf(
        "Fetching", "Browsing", "Spelunking", "Hunting",
    )

    private val SEARCH_VERBS = listOf(
        "Searching", "Hunting", "Sifting", "Combing",
    )

    private val LIST_VERBS = listOf(
        "Surveying", "Scanning", "Mapping",
    )

    private val GENERIC_TOOL_VERBS = listOf(
        "Wielding", "Operating", "Tinkering", "Conjuring",
    )

    /**
     * Map of built-in tool name → curated verb list. Keys MUST also
     * appear in [BUILT_IN_OPENCLAW_TOOLS]. Built-in tools NOT present
     * here fall back to [DEFAULT_VERBS] (no subtitle, since they are
     * still recognised tools).
     */
    private val TOOL_VERB_MAP: Map<String, List<String>> = buildMap {
        listOf("exec", "bash", "shell").forEach { put(it, EXEC_VERBS) }
        listOf("read_file", "read", "cat").forEach { put(it, READ_VERBS) }
        listOf("edit", "write_file", "write", "apply_patch").forEach { put(it, EDIT_VERBS) }
        listOf("web_fetch", "fetch_url", "web_search", "search").forEach { put(it, WEB_VERBS) }
        listOf("grep", "find", "search_files").forEach { put(it, SEARCH_VERBS) }
        listOf("glob", "list_files", "ls").forEach { put(it, LIST_VERBS) }
    }

    /**
     * Returns the verb list associated with [activity].
     *
     * Routing:
     *  - "starting" / "thinking" / "writing" → fixed per-activity list
     *  - "tool:NAME" where NAME has a curated entry → curated list
     *  - "tool:NAME" where NAME is a recognised built-in but not curated
     *    → [DEFAULT_VERBS]
     *  - "tool:NAME" where NAME is unknown / custom → [GENERIC_TOOL_VERBS]
     *  - null / any other string → [DEFAULT_VERBS]
     *
     * The returned list is always non-empty.
     */
    fun verbsFor(activity: String?): List<String> {
        if (activity == null) return DEFAULT_VERBS
        return when (activity) {
            "starting" -> STARTING_VERBS
            "thinking" -> THINKING_VERBS
            "writing" -> WRITING_VERBS
            "compacting" -> COMPACTING_VERBS
            else -> {
                if (activity.startsWith("tool:")) {
                    val name = activity.removePrefix("tool:")
                    TOOL_VERB_MAP[name]
                        ?: if (name in BUILT_IN_OPENCLAW_TOOLS) DEFAULT_VERBS
                           else GENERIC_TOOL_VERBS
                } else {
                    DEFAULT_VERBS
                }
            }
        }
    }

    /**
     * Pick a single verb from the list selected by [activity], using
     * [indexInList] modulo the list length. Negative indices are
     * handled via [Math.floorMod] so callers don't need to guard.
     *
     * The returned [VerbResult.subtitle] is non-null only for unknown
     * / non-built-in tool names — in that case it carries the raw tool
     * name (without the `tool:` prefix) so advanced users can still see
     * which tool is running.
     */
    fun pickVerb(activity: String?, indexInList: Int): VerbResult {
        val verbs = verbsFor(activity)
        // verbsFor never returns empty; floorMod handles negative indices.
        val idx = Math.floorMod(indexInList, verbs.size)
        val verb = verbs[idx]
        val subtitle = subtitleFor(activity)
        return VerbResult(verb = verb, subtitle = subtitle)
    }

    /**
     * Returns the raw tool name as subtitle for unknown / non-built-in
     * tool activities; null for everything else (including built-in
     * tools, where the curated verb already conveys the action).
     */
    private fun subtitleFor(activity: String?): String? {
        if (activity == null || !activity.startsWith("tool:")) return null
        val name = activity.removePrefix("tool:")
        if (name.isEmpty()) return null
        return if (name in BUILT_IN_OPENCLAW_TOOLS) null else name
    }
}

/**
 * Result of a single verb pick. [subtitle] is null for known activities
 * and recognised built-in tools; non-null (the raw tool name) for
 * unknown / custom tools so the user can still tell what's running.
 */
data class VerbResult(
    val verb: String,
    val subtitle: String? = null,
)
