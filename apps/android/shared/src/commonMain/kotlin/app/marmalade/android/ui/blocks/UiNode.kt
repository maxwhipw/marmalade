package app.marmalade.android.ui.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Marmalade UI v1 — the sealed node hierarchy (dynamic-UI blocks v2).
 * Vocabulary + interaction contract:
 * marmalade repo `docs/dynamic-ui/marmalade-ui-v1.md` (+ the JSON Schema
 * next to it) — the language-neutral truth all three renderers cite.
 *
 * Builders are TOLERANT by design (Kai's model): every field falls back to
 * a default when missing/mis-typed, so a truncated-and-repaired node still
 * renders. An unknown `type` becomes [UiNode.Unknown] and degrades to its
 * best text content — never an error card.
 */
sealed interface UiNode {
    data class ColumnNode(val children: List<UiNode>) : UiNode
    data class RowNode(val children: List<UiNode>) : UiNode
    data class CardNode(val title: String?, val children: List<UiNode>) : UiNode
    data object DividerNode : UiNode

    data class TextNode(
        val text: String,
        val style: String = "body",     // headline | title | body | caption
        val bold: Boolean = false,
        val color: String = "default",  // default | primary | success | warning | error
    ) : UiNode

    data class ListNode(val items: List<String>, val ordered: Boolean = false) : UiNode
    data class TableNode(val columns: List<String>, val rows: List<List<String>>) : UiNode
    data class CodeNode(val code: String, val language: String? = null) : UiNode
    data class AlertNode(val text: String, val level: String = "info", val title: String? = null) : UiNode

    data class ButtonNode(
        val label: String,
        val action: String = "callback", // callback | open_url | copy_to_clipboard
        val event: String? = null,
        val collectFrom: List<String> = emptyList(),
        val url: String? = null,
        val text: String? = null,
        val variant: String = "primary", // primary | secondary | danger
    ) : UiNode

    data class TextInputNode(
        val id: String,
        val label: String? = null,
        val placeholder: String? = null,
        val value: String? = null,
    ) : UiNode

    data class UiOption(val id: String, val label: String)

    data class SelectNode(val id: String, val label: String?, val options: List<UiOption>) : UiNode
    data class CheckboxNode(val id: String, val label: String, val checked: Boolean = false) : UiNode
    data class ChipGroupNode(val id: String, val options: List<UiOption>, val multi: Boolean = false) : UiNode

    data class ProgressNode(val value: Float?, val label: String? = null) : UiNode
    data class StatusNode(val text: String, val state: String = "pending") : UiNode
    data class CountdownNode(val untilMs: Long?, val seconds: Long?, val label: String? = null) : UiNode

    /** Unrecognized type — degrades to readable text, never an error. */
    data class Unknown(val type: String, val text: String?) : UiNode
}

// ── Tolerant field readers ───────────────────────────────────────────────────

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

private fun JsonObject.childNodes(): List<UiNode> =
    (this["children"] as? JsonArray)?.mapNotNull { buildUiNode(it) } ?: emptyList()

private fun JsonObject.options(key: String = "options"): List<UiNode.UiOption> =
    (this[key] as? JsonArray)?.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> UiNode.UiOption(id = el.content, label = el.content)
            is JsonObject -> {
                val id = el.str("id") ?: el.str("label") ?: return@mapNotNull null
                UiNode.UiOption(id = id, label = el.str("label") ?: id)
            }
            else -> null
        }
    } ?: emptyList()

/**
 * Build one [UiNode] from a parsed [JsonElement]. Returns null only when the
 * element is not an object or has no usable `type` — every recognized type
 * builds SOMETHING (per-field defaults for whatever the repair pass lost).
 */
fun buildUiNode(element: JsonElement): UiNode? {
    val obj = element as? JsonObject ?: return null
    return when (obj.str("type")) {
        "column" -> UiNode.ColumnNode(obj.childNodes())
        "row" -> UiNode.RowNode(obj.childNodes())
        "card" -> UiNode.CardNode(title = obj.str("title"), children = obj.childNodes())
        "divider" -> UiNode.DividerNode
        "text" -> UiNode.TextNode(
            text = obj.str("text") ?: "",
            style = obj.str("style") ?: "body",
            bold = obj.bool("bold") ?: false,
            color = obj.str("color") ?: "default",
        )
        "list" -> UiNode.ListNode(items = obj.strings("items"), ordered = obj.bool("ordered") ?: false)
        "table" -> UiNode.TableNode(
            columns = obj.strings("columns"),
            rows = (obj["rows"] as? JsonArray)?.mapNotNull { row ->
                (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            } ?: emptyList(),
        )
        "code" -> UiNode.CodeNode(code = obj.str("code") ?: "", language = obj.str("language"))
        "alert" -> UiNode.AlertNode(
            text = obj.str("text") ?: "",
            level = obj.str("level") ?: "info",
            title = obj.str("title"),
        )
        "button" -> UiNode.ButtonNode(
            label = obj.str("label") ?: "OK",
            action = obj.str("action") ?: "callback",
            event = obj.str("event"),
            collectFrom = obj.strings("collect_from"),
            url = obj.str("url"),
            text = obj.str("text"),
            variant = obj.str("variant") ?: "primary",
        )
        "text_input" -> UiNode.TextInputNode(
            id = obj.str("id") ?: return null, // an input without an id is uncollectable
            label = obj.str("label"),
            placeholder = obj.str("placeholder"),
            value = obj.str("value"),
        )
        "select" -> UiNode.SelectNode(
            id = obj.str("id") ?: return null,
            label = obj.str("label"),
            options = obj.options(),
        )
        "checkbox" -> UiNode.CheckboxNode(
            id = obj.str("id") ?: return null,
            label = obj.str("label") ?: "",
            checked = obj.bool("checked") ?: false,
        )
        "chip_group" -> UiNode.ChipGroupNode(
            id = obj.str("id") ?: return null,
            options = obj.options(),
            multi = obj.bool("multi") ?: false,
        )
        "progress" -> UiNode.ProgressNode(
            value = (obj["value"] as? JsonPrimitive)?.doubleOrNull?.toFloat()?.coerceIn(0f, 1f),
            label = obj.str("label"),
        )
        "status" -> UiNode.StatusNode(text = obj.str("text") ?: "", state = obj.str("state") ?: "pending")
        "countdown" -> UiNode.CountdownNode(
            untilMs = (obj["until"] as? JsonPrimitive)?.longOrNull,
            seconds = (obj["seconds"] as? JsonPrimitive)?.longOrNull,
            label = obj.str("label"),
        )
        null -> null
        else -> UiNode.Unknown(
            type = obj.str("type") ?: "?",
            text = obj.str("text") ?: obj.str("label") ?: obj.str("title"),
        )
    }
}
