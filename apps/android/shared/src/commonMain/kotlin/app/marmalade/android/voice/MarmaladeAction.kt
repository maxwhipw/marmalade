/**
 * Data Flow: MarmaladeAction (parse half of the marmalade_action envelope)
 *
 * Assistant response text (message.complete)
 *     |
 * parseMarmaladeAction(responseText)   <- this file, pure Kotlin + kotlinx
 *     | (MarmaladeAction? or null)
 * ChatHost.dispatchVoiceAction(action)
 *     |
 * :app ActionDispatcher.dispatchAction(context, action) -> Android Intent
 *     |
 * DispatchResult (Success or Error)
 *
 * The assistant embeds a JSON envelope in its response text like:
 *   { "marmalade_action": { "action": "app.launch", "package": "...", "displayText": "..." } }
 *
 * [parseMarmaladeAction] locates and extracts the JSON object from the raw text,
 * which may be mixed with prose or markdown code blocks. It is split out of
 * `:app`'s ActionDispatcher.kt so `:shared` (ChatController) can parse without
 * pulling in `android.content.Intent`; the dispatch half stays in `:app`.
 */
package app.marmalade.android.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
data class MarmaladeAction(
    val action: String,
    @SerialName("package")
    val packageName: String? = null,
    val params: Map<String, String> = emptyMap(),
    val displayText: String,
    // Generic intent fields -- enables gateway to dispatch arbitrary Android intents
    val intentAction: String? = null,
    val intentData: String? = null,
    val intentExtras: Map<String, String>? = null,
    val intentCategory: String? = null,
)

/**
 * Parse a marmalade_action JSON from assistant message text.
 * The action may be embedded in markdown code blocks or mixed with text.
 * Returns null if no action found or parsing fails.
 */
fun parseMarmaladeAction(text: String): MarmaladeAction? {
    // Try to find "marmalade_action" key in the text
    val idx = text.indexOf("\"marmalade_action\"")
    if (idx < 0) return null

    // Find the enclosing JSON object -- look backwards for { and forwards for matching }
    val jsonStart = text.lastIndexOf('{', idx)
    if (jsonStart < 0) return null

    // Find the matching closing brace
    var depth = 0
    var jsonEnd = -1
    for (i in jsonStart until text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    jsonEnd = i + 1
                    break
                }
            }
        }
    }
    if (jsonEnd < 0) return null

    val jsonStr = text.substring(jsonStart, jsonEnd)
    return try {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val actionObj = root["marmalade_action"]?.jsonObject ?: return null
        json.decodeFromJsonElement(MarmaladeAction.serializer(), actionObj)
    } catch (e: Exception) {
        null
    }
}
