package app.marmalade.android.node

import app.marmalade.android.VoiceWakeMode
import app.marmalade.android.rpc.InvokeResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VoiceWakeHandler(
  private val json: Json,
  private val voiceWakeMode: () -> VoiceWakeMode,
  private val setVoiceWakeMode: (VoiceWakeMode) -> Unit,
  private val voiceWakeStatusText: () -> String,
  private val invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
) {

  fun handleVoiceWakeGetMode(): InvokeResult {
    return try {
      val mode = voiceWakeMode().name.lowercase()
      InvokeResult.ok("""{"mode":"$mode"}""")
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }

  fun handleVoiceWakeSetMode(paramsJson: String?): InvokeResult {
    return try {
      val root = paramsJson?.let { json.parseToJsonElement(it).jsonObject }
        ?: return InvokeResult.error("INVALID_ARGUMENT", "Missing parameters")

      val modeStr = root["mode"]?.jsonPrimitive?.content?.lowercase()
        ?: return InvokeResult.error("INVALID_ARGUMENT", "Mode must be provided (off, foreground, always)")

      val newMode = when (modeStr) {
        "off" -> VoiceWakeMode.Off
        "foreground" -> VoiceWakeMode.Foreground
        "always" -> VoiceWakeMode.Always
        else -> return InvokeResult.error("INVALID_ARGUMENT", "Invalid mode: $modeStr")
      }

      setVoiceWakeMode(newMode)
      InvokeResult.ok("""{"success":true,"mode":"$modeStr"}""")
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }

  fun handleVoiceWakeStatus(): InvokeResult {
    return try {
      val status = voiceWakeStatusText()
      InvokeResult.ok("""{"status":"$status"}""")
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }
}
