package app.marmalade.android.ui.blocks

import kotlinx.serialization.json.Json

/**
 * Parses a ```marmalade-ui fence body into a [UiNode] tree and synthesizes
 * the plain-text interaction responses (Marmalade UI v1 — spec:
 * marmalade repo `docs/dynamic-ui/marmalade-ui-v1.md`).
 *
 * Pipeline (Kai's three-stage model): syntax repair ([JsonRepair]) →
 * parseToJsonElement → tolerant field-by-field build ([buildUiNode]).
 * Supports a single JSON object or NDJSON (one object per line, wrapped in
 * an implicit column). Returns null when nothing parses — the caller
 * degrades to a styled code block.
 */
object UiTreeParser {

    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(rawBlock: String): UiNode? {
        if (rawBlock.isBlank()) return null
        val repaired = JsonRepair.fixJsonSyntax(rawBlock.trim())
        val lines = repaired.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // NDJSON: one node per line → implicit column.
        if (lines.size > 1 && lines.all { it.startsWith("{") }) {
            val children = lines.mapNotNull { tryParse(it) }
            return if (children.isNotEmpty()) UiNode.ColumnNode(children) else null
        }
        return tryParse(JsonRepair.sanitizeJson(repaired))
    }

    private fun tryParse(json: String): UiNode? =
        runCatching { buildUiNode(lenientJson.parseToJsonElement(json)) }.getOrNull()
            ?: runCatching { buildUiNode(lenientJson.parseToJsonElement(JsonRepair.sanitizeJson(json))) }.getOrNull()

    // ── Interaction response grammar (spec §Interaction contract) ────────────

    /** `Pressed: <event or label>` — callback button with no collect_from. */
    fun pressedMessage(button: UiNode.ButtonNode): String =
        "Pressed: ${button.event ?: button.label}"

    /**
     * `Responded with: <event>: <id>=<value>; …` — callback button that
     * collects local input state. [values] maps input node id → value string
     * (checkbox: "true"/"false"; multi chip_group: comma-joined ids). A
     * collected id absent from [values] contributes `<id>=`.
     */
    fun respondedMessage(button: UiNode.ButtonNode, values: Map<String, String>): String {
        val event = button.event ?: button.label
        val fields = button.collectFrom.joinToString("; ") { id -> "$id=${values[id] ?: ""}" }
        return "Responded with: $event: $fields"
    }

    /** Route a callback press to the right grammar line. */
    fun callbackMessage(button: UiNode.ButtonNode, values: Map<String, String>): String =
        if (button.collectFrom.isEmpty()) pressedMessage(button) else respondedMessage(button, values)
}
