package app.marmalade.android.chat.messages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase of a tool-call lifecycle event from marmalade-agent.
 *
 * - [Running] — `tool.start` / `tool.progress` / `tool.generating` events
 *   that update a live tool row but leave [ChatMessagePart.ToolCall.result]
 *   null.
 * - [Complete] — `tool.complete` event. Fills in `result` + `isError` on the
 *   matched part.
 */
enum class ToolPhase { Running, Complete }

/** Placeholder name for a `tool.*` payload that carries none — it exists only
 *  to key the upsert and must never reach the UI as if it were a real tool
 *  name (the UI maps it to "Tool call"). */
internal const val UNNAMED_TOOL = "tool"

/**
 * Upsert a tool-call into a parts list from a marmalade-agent tool lifecycle
 * event. Faithful port of desktop's `upsertToolPart` at
 * `hermes-agent upstream: apps/desktop/src/lib/chat-messages.ts:441`.
 *
 * Matching priority for picking which existing tool-call to update (full
 * algorithm in [findToolPartIndex]):
 *
 * 1. **Stable id** (payload.tool_id / tool_call_id / id). If we find a part
 *    with that [ChatMessagePart.ToolCall.toolCallId], that's the match.
 * 2. Otherwise we look at *pending* same-name parts (no `result` yet) and
 *    match by **context overlap** — same search_term/query/context/preview
 *    string. This lets parallel `web_search` calls without explicit ids stay
 *    distinct (e.g. tokyo vs reykjavik weather queries).
 * 3. If exactly one pending same-name part exists and there's no overlap
 *    signal: use it. For `Running` events without a stable id + no overlap
 *    we'd open a new part instead (the single-pending row is ambiguous).
 * 4. Completion events without a stable id resolve **oldest-first** (parallel
 *    bursts of the same tool — each completion ticks off the next one in
 *    order). Running events without a stable id resolve **most-recent**
 *    (sparse progress updates after a fresh start).
 *
 * When no existing part matches, a new tool-call part is appended. Either
 * way, the returned list is a new instance — the input is not mutated.
 *
 * @param parts current parts list for the bubble.
 * @param payload the event's `payload` field (as a JsonObject). Pass an
 *   empty object if the event arrived without payload.
 * @param phase whether this event opens/updates a running tool, or completes it.
 */
fun upsertToolPart(
    parts: List<ChatMessagePart>,
    payload: JsonObject,
    phase: ToolPhase,
): List<ChatMessagePart> {
    val stableId = toolIdFromPayload(payload)
    val name = payload.stringOrEmpty("name").ifEmpty { UNNAMED_TOOL }
    val next = parts.toMutableList()

    val index = findToolPartIndex(next, name, stableId, payload, phase)

    val prev = index.takeIf { it >= 0 }?.let { next[it] as? ChatMessagePart.ToolCall }
    val prevArgs = prev?.args ?: JsonObject(emptyMap())
    val prevResult = prev?.result as? JsonObject

    val args = mergeToolArgs(payload, prevArgs)
    val id = stableId.ifEmpty { prev?.toolCallId?.takeIf { it.isNotEmpty() } ?: nextLiveToolId(name) }

    val base = ChatMessagePart.ToolCall(
        toolCallId = id,
        // A completion carries no `name`, so [name] is the UNNAMED_TOOL
        // placeholder here. Writing that over an established name renamed
        // every finished tool to "Tool call" the moment it settled — the
        // second half of the tool_use_id pairing bug (see toolIdFromPayload).
        // A real name always wins over the placeholder, in either direction.
        toolName = name.takeIf { it != UNNAMED_TOOL } ?: prev?.toolName?.takeIf { it.isNotEmpty() } ?: name,
        args = args,
        // Mirror desktop's unconditional JSON.stringify(args) — empty args
        // becomes "{}" not "" so tooltip / title surfaces stay consistent.
        argsText = args.toString(),
        result = if (phase == ToolPhase.Complete) mergeToolResult(payload, prevResult, prevArgs) else prev?.result,
        isError = if (phase == ToolPhase.Complete) booleanFromError(payload["error"]) else prev?.isError ?: false,
        // Subagent attribution (daemon marmalade `ec6ea8e`). Only tool.start
        // carries it reliably, so a completion must NOT clear what the start
        // established — hence the prev fallback.
        parentToolUseId = payload.stringOrEmpty("parent_tool_use_id").ifEmpty { null }
            ?: prev?.parentToolUseId,
        // Only tool.complete carries the approval decision (the choice isn't
        // known when the call starts), so — unlike attribution — there is
        // nothing on the start to preserve; keep the prev fallback anyway so a
        // later partial update can't erase a recorded decision.
        approvalChoice = (payload["approval"] as? JsonObject)
            ?.stringOrEmpty("choice")?.ifEmpty { null }
            ?: prev?.approvalChoice,
    )

    return if (index < 0) next + base else next.also { it[index] = base }
}

// ── matching ────────────────────────────────────────────────────────────────

private fun findToolPartIndex(
    parts: List<ChatMessagePart>,
    name: String,
    stableId: String,
    payload: JsonObject,
    phase: ToolPhase,
): Int {
    val payloadMatches = toolPayloadMatchValues(payload)
    fun overlapsAt(index: Int): Boolean =
        hasToolMatchOverlap(payloadMatches, toolPartMatchValues(parts[index]))

    if (stableId.isNotEmpty()) {
        val stableIndex = parts.indexOfFirst { it is ChatMessagePart.ToolCall && it.toolCallId == stableId }
        if (stableIndex >= 0) return stableIndex
        // Some live streams start without an id, then complete with one. Fall
        // through to pending same-name/context matching so the completion
        // updates the synthetic live row instead of appending a duplicate.
        if (phase == ToolPhase.Running && payloadMatches.isEmpty()) return -1
    }

    val pendingIndices = parts.mapIndexedNotNull { i, p ->
        if (p is ChatMessagePart.ToolCall && p.toolName == name && p.result == null) i else null
    }
    if (pendingIndices.isEmpty()) return -1

    if (payloadMatches.isNotEmpty()) {
        val contextualIndex = pendingIndices.firstOrNull(::overlapsAt)
        if (contextualIndex != null) return contextualIndex
    }

    if (pendingIndices.size == 1) {
        val single = pendingIndices.single()
        return if (phase == ToolPhase.Running && payloadMatches.isNotEmpty() && !overlapsAt(single)) {
            // A running event with context that doesn't overlap the single
            // pending part isn't an update — only fall back to the pending
            // part when we have a stable id to anchor the match.
            if (stableId.isNotEmpty()) single else -1
        } else single
    }

    // Completion events without stable IDs frequently arrive after multiple
    // same-name starts (parallel tool calls). Resolve them oldest-first so we
    // don't collapse an entire burst into a single row.
    if (phase == ToolPhase.Complete) return pendingIndices.first()
    if (stableId.isNotEmpty()) return pendingIndices.first()

    // For progress/running events with no stable id, update the most-recent
    // pending same-name tool instead of creating a phantom extra row.
    return pendingIndices.last()
}

private fun toolPayloadMatchValues(payload: JsonObject): List<String> {
    val args = liveToolArgs(payload)
    val query = args.firstStringField("search_term", "query")
    val context = payload.stringOrEmpty("context")
    val preview = payload.stringOrEmpty("preview")
    return collectToolMatchValues(query, context, preview)
}

private fun toolPartMatchValues(part: ChatMessagePart): List<String> {
    if (part !is ChatMessagePart.ToolCall) return emptyList()
    val query = part.args.firstStringField("search_term", "query")
    val context = part.args.stringOrEmpty("context")
    val preview = part.args.stringOrEmpty("preview")
    return collectToolMatchValues(query, context, preview)
}

private fun collectToolMatchValues(query: String, context: String, preview: String): List<String> =
    listOf(query, context, preview)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()

private fun hasToolMatchOverlap(left: List<String>, right: List<String>): Boolean {
    if (left.isEmpty() || right.isEmpty()) return false
    val rightSet = right.toSet()
    return left.any { it in rightSet }
}

// ── arg / result merging ────────────────────────────────────────────────────

private fun mergeToolArgs(payload: JsonObject, prevArgs: JsonObject): JsonObject = buildJsonObject {
    // prev wins overrides only when there's no event-side override
    prevArgs.forEach { (k, v) -> put(k, v) }
    liveToolArgs(payload).forEach { (k, v) -> put(k, v) }
    payload.stringOrNull("context")?.let { put("context", JsonPrimitive(it)) }
    payload.stringOrNull("preview")?.let { put("preview", JsonPrimitive(it)) }
    carryTodos(payload, prevArgs)?.let { put("todos", it) }
}

private fun mergeToolResult(
    payload: JsonObject,
    prevResult: JsonObject?,
    prevArgs: JsonObject,
): JsonObject = buildJsonObject {
    parseMaybeJsonObject(payload["result"]).forEach { (k, v) -> put(k, v) }
    payload.stringOrNull("inline_diff")?.let { put("inline_diff", JsonPrimitive(it)) }
    payload.stringOrNull("summary")?.let { put("summary", JsonPrimitive(it)) }
    payload.stringOrNull("message")?.let { put("message", JsonPrimitive(it)) }
    payload.stringOrNull("preview")?.let { put("preview", JsonPrimitive(it)) }
    payload["duration_s"]?.takeIf { it !is JsonNull }?.let { put("duration_s", it) }
    // `content` is the tool_result's own body — what the tool actually printed.
    // The daemon forwards it on tool.complete; without carrying it here the
    // detail sheet has an args section and nothing to show for output.
    payload["content"]?.takeIf { it !is JsonNull }?.let { put("content", it) }
    carryTodos(payload, prevResult, prevArgs)?.let { put("todos", it) }
    payload["error"]?.takeIf { it !is JsonNull && !it.isFalsy() }?.let { put("error", it) }
}

private fun JsonElement.isFalsy(): Boolean = when (this) {
    is JsonPrimitive -> when {
        isString -> content.isEmpty()
        booleanOrNull == false -> true
        else -> false
    }
    else -> false
}

private val JsonPrimitive.booleanOrNull: Boolean? get() = when (content) {
    "true" -> true; "false" -> false; else -> null
}

private fun booleanFromError(element: JsonElement?): Boolean = when {
    element == null || element is JsonNull -> false
    element is JsonPrimitive -> when {
        element.isString -> element.content.isNotEmpty()
        else -> element.booleanOrNull == true
    }
    else -> true
}

/**
 * Carry the `todos` array across sparse progress payloads. If the new
 * payload has a `todos` field, use it (including an explicit empty array
 * which is a meaningful "clear" signal). Otherwise, if this is the `todo`
 * tool, fall back to whatever the prev sources stored.
 */
private fun carryTodos(payload: JsonObject, vararg prev: JsonObject?): JsonElement? {
    if (payload.containsKey("todos")) {
        return payload["todos"]?.takeIf { it is JsonArray || it is JsonNull } ?: payload["todos"]
    }
    if (payload.stringOrEmpty("name") != "todo") return null
    for (source in prev) {
        val carried = source?.get("todos")
        if (carried != null && carried !is JsonNull) return carried
    }
    return null
}

// ── argument extraction ─────────────────────────────────────────────────────

/**
 * Pull the agent's tool arguments out of a payload, handling the half-dozen
 * shapes that live tool events arrive in. Matches desktop's `liveToolArgs`
 * (chat-messages.ts:514).
 */
private fun liveToolArgs(payload: JsonObject): JsonObject {
    val direct = firstNonEmptyObject(payload["args"], payload["arguments"])
    val input = firstNonEmptyObject(payload["input"])
    val fn = (input["function"] as? JsonObject)
    val nested = firstNonEmptyObject(
        input["args"], input["arguments"], input["parameters"], input["input"],
        fn?.get("arguments"), fn?.get("args"), fn?.get("parameters"),
    )
    return buildJsonObject {
        input.forEach { (k, v) -> put(k, v) }
        nested.forEach { (k, v) -> put(k, v) }
        direct.forEach { (k, v) -> put(k, v) }
    }
}

private fun firstNonEmptyObject(vararg candidates: JsonElement?): JsonObject {
    for (c in candidates) {
        val obj = parseMaybeJsonObject(c)
        if (obj.isNotEmpty()) return obj
    }
    return JsonObject(emptyMap())
}

/**
 * Coerce a value to a JsonObject. Accepts an actual JsonObject, OR a JSON
 * string that parses to an object (some servers stringify tool args). Returns
 * empty object on anything else.
 */
private fun parseMaybeJsonObject(value: JsonElement?): JsonObject {
    if (value == null || value is JsonNull) return JsonObject(emptyMap())
    if (value is JsonObject) return value
    if (value is JsonPrimitive && value.isString) {
        val text = value.content.trim()
        if (text.isEmpty()) return JsonObject(emptyMap())
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
    }
    return JsonObject(emptyMap())
}

/**
 * The stable tool id off a `tool.*` payload, under any of the spellings the
 * wire uses.
 *
 * `tool_use_id` is the one the daemon puts on **every `tool.complete`**
 * (normalize.ts `case "user"`), and it was missing from this list. The
 * consequence was systemic and had been live the whole time: a completion
 * resolved to an EMPTY stable id, fell through to name matching with the
 * `"tool"` placeholder (completions carry no `name`), matched nothing, and
 * appended a brand-new part.
 *
 * So every finished tool produced TWO rows — the real one stuck on "running"
 * forever because its result never landed, plus a phantom "Tool call" row
 * carrying the result. The maintainer's 2026-07-26 transcript shows the pair repeated
 * eleven times, and it is also why the subagent card never settled: the Task
 * tool's own completion missed in exactly the same way.
 */
private fun toolIdFromPayload(payload: JsonObject): String =
    payload.stringOrEmpty("tool_use_id").ifEmpty {
        payload.stringOrEmpty("tool_id").ifEmpty {
            payload.stringOrEmpty("tool_call_id").ifEmpty { payload.stringOrEmpty("id") }
        }
    }

// ── small JsonObject helpers (private to this file) ─────────────────────────

private fun JsonObject.stringOrEmpty(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

private fun JsonObject.firstStringField(vararg keys: String): String {
    for (k in keys) {
        val v = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (!v.isNullOrEmpty()) return v
    }
    return ""
}

// ── live tool id ────────────────────────────────────────────────────────────

private val liveToolCounter = AtomicLong(0)

internal fun nextLiveToolId(name: String): String =
    "live-tool:$name:${liveToolCounter.incrementAndGet()}"

/** Reset the live-tool id counter — tests only. Public (not internal)
 *  because ToolCallUpsertTest stays in :app while this moved to :shared
 *  (increment 3d) and `internal` doesn't cross the module boundary. */
fun resetLiveToolCounterForTest() {
    liveToolCounter.set(0)
}
