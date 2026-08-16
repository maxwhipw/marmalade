package app.marmalade.android.ui.blocks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Parser for Marmalade interactive blocks.
 *
 * Parses JSON from ```marmalade code blocks into typed data classes.
 * Returns null for invalid JSON (graceful degradation to styled code block).
 *
 * Uses kotlinx.serialization with isLenient=true and ignoreUnknownKeys=true
 * per project decision in CONTEXT.md.
 */
object MarmaladeBlockParser {

    private val lenientJson = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * Parse a raw JSON string from a ```marmalade code block into a MarmaladeBlock.
     *
     * Returns null if the JSON is invalid or cannot be parsed at all
     * (graceful degradation -- the caller renders it as a styled code block).
     */
    fun parseMarmaladeBlock(json: String): MarmaladeBlock? {
        if (json.isBlank()) return null
        return try {
            lenientJson.decodeFromString<MarmaladeBlock>(json)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parse the block's [data] JsonObject into the appropriate typed data class
     * based on [MarmaladeBlock.type].
     *
     * Returns ConfirmData, SelectData, MultiselectData, ActionData, or StatusData.
     * Returns null for unknown types (renders as raw JSON display).
     */
    fun parseBlockData(block: MarmaladeBlock): Any? {
        return try {
            when (block.type) {
                "confirm" -> parseConfirmData(block.data)
                "select" -> parseSelectData(block.data)
                "multiselect" -> parseMultiselectData(block.data)
                "action" -> parseActionData(block.data)
                "status" -> parseStatusData(block.data)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Format a block interaction response as a ```marmalade-response code block string
     * ready to be sent via chat.send.
     *
     * Response payload varies by type:
     * - confirm: "confirmed" or "cancelled"
     * - select: selected option id string
     * - multiselect: list of selected option id strings
     * - action: action id string
     */
    fun formatBlockResponse(blockId: String?, type: String, response: Any): String {
        val responseValue = when (response) {
            is List<*> -> JsonArray(response.map { JsonPrimitive(it.toString()) })
            else -> JsonPrimitive(response.toString())
        }

        val jsonObj = buildJsonObject {
            if (blockId != null) {
                put("blockId", blockId)
            }
            put("type", type)
            put("response", responseValue)
        }

        return "```marmalade-response\n${jsonObj}\n```"
    }

    // -- Private parsing helpers --

    private fun parseConfirmData(data: JsonObject): ConfirmData {
        return ConfirmData(
            message = data["message"]!!.jsonPrimitive.content,
            confirmLabel = data["confirmLabel"]!!.jsonPrimitive.content,
            cancelLabel = data["cancelLabel"]!!.jsonPrimitive.content,
        )
    }

    private fun parseSelectData(data: JsonObject): SelectData {
        return SelectData(
            message = data["message"]!!.jsonPrimitive.content,
            options = parseOptions(data["options"]!!.jsonArray),
        )
    }

    private fun parseMultiselectData(data: JsonObject): MultiselectData {
        return MultiselectData(
            message = data["message"]!!.jsonPrimitive.content,
            options = parseOptions(data["options"]!!.jsonArray),
            submitLabel = data["submitLabel"]!!.jsonPrimitive.content,
        )
    }

    private fun parseActionData(data: JsonObject): ActionData {
        val actions = data["actions"]!!.jsonArray.map { el ->
            val obj = el.jsonObject
            ActionItem(
                id = obj["id"]!!.jsonPrimitive.content,
                label = obj["label"]!!.jsonPrimitive.content,
                icon = obj["icon"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return ActionData(actions = actions)
    }

    private fun parseStatusData(data: JsonObject): StatusData {
        return StatusData(
            message = data["message"]!!.jsonPrimitive.content,
            progress = data["progress"]?.jsonPrimitive?.floatOrNull,
            state = data["state"]!!.jsonPrimitive.content,
        )
    }

    private fun parseOptions(array: JsonArray): List<SelectOption> {
        return array.map { el ->
            val obj = el.jsonObject
            SelectOption(
                id = obj["id"]!!.jsonPrimitive.content,
                label = obj["label"]!!.jsonPrimitive.content,
            )
        }
    }
}
