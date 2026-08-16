package app.marmalade.android.node

import android.content.Context
import app.marmalade.android.rpc.InvokeResult
import app.marmalade.android.voice.DispatchResult
import app.marmalade.android.voice.MarmaladeAction
import app.marmalade.android.voice.dispatchAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Invoke handler for `android_action` commands from the gateway.
 *
 * This is the RPC path: gateway sends `node.invoke` with command `android_action`
 * and JSON params describing the action to perform. The handler parses the params,
 * constructs a [MarmaladeAction], dispatches it via [dispatchAction], and returns
 * an [InvokeResult] so the gateway agent knows whether the action succeeded.
 *
 * Follows the same pattern as [AppHandler], [DeviceHandler], etc.
 */
class AndroidActionHandler(
    private val context: Context,
    private val json: Json,
    private val invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
) {

    /**
     * Handle an `android_action` invoke command.
     *
     * Expected params JSON:
     * ```json
     * {
     *   "action": "device.call",         // required: action type
     *   "package": "com.spotify.music",   // optional: target package
     *   "params": { "number": "555..." }, // optional: action-specific params
     *   "displayText": "Calling...",      // required: user-facing description
     *   "intentAction": "...",            // optional: for intent.generic
     *   "intentData": "...",              // optional: for intent.generic
     *   "intentExtras": { "k": "v" },    // optional: for intent.generic
     *   "intentCategory": "..."           // optional: for intent.generic
     * }
     * ```
     */
    fun handleAndroidAction(paramsJson: String?): InvokeResult {
        return try {
            val root = paramsJson?.let { json.parseToJsonElement(it).jsonObject }
                ?: return InvokeResult.error(
                    "INVALID_ARGUMENT", "Missing parameters"
                )

            val actionType = root["action"]?.jsonPrimitive?.content
                ?: return InvokeResult.error(
                    "INVALID_ARGUMENT", "action field is required"
                )

            val displayText = root["displayText"]?.jsonPrimitive?.content ?: actionType

            val packageName = root["package"]?.jsonPrimitive?.content

            // Parse params map
            val paramsMap = root["params"]?.jsonObject?.let { obj ->
                obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
            } ?: emptyMap()

            // Parse generic intent fields
            val intentAction = root["intentAction"]?.jsonPrimitive?.content
            val intentData = root["intentData"]?.jsonPrimitive?.content
            val intentCategory = root["intentCategory"]?.jsonPrimitive?.content
            val intentExtras = root["intentExtras"]?.jsonObject?.let { obj ->
                obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
            }

            val marmaladeAction = MarmaladeAction(
                action = actionType,
                packageName = packageName,
                params = paramsMap,
                displayText = displayText,
                intentAction = intentAction,
                intentData = intentData,
                intentExtras = intentExtras,
                intentCategory = intentCategory,
            )

            when (val result = dispatchAction(context, marmaladeAction)) {
                is DispatchResult.Success ->
                    InvokeResult.ok(
                        """{"success":true,"displayText":"${escapeJson(result.displayText)}"}"""
                    )
                is DispatchResult.Error ->
                    InvokeResult.error(
                        "ACTION_FAILED", result.message
                    )
            }
        } catch (e: Throwable) {
            val (code, msg) = invokeErrorFromThrowable(e)
            InvokeResult.error(code, msg)
        }
    }

    /** Simple JSON string escape for embedding in raw JSON. */
    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
