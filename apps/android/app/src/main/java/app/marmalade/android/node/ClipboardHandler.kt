package app.marmalade.android.node

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClipboardHandler(
  private val context: Context,
  private val json: Json,
  private val invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
) {
  private val clipboardManager: ClipboardManager? by lazy {
    context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
  }

  fun handleClipboardRead(): InvokeResult {
    return try {
      val clipMgr = clipboardManager ?: return InvokeResult.error(
        "UNAVAILABLE",
        "ClipboardManager not available"
      )
      
      val clipData = clipMgr.primaryClip
      val text = if (clipData != null && clipData.itemCount > 0) {
        clipData.getItemAt(0).text?.toString() ?: ""
      } else {
        ""
      }

      val escapedText = text.replace("\"", "\\\"").replace("\n", "\\n")
      InvokeResult.ok("""{"text":"$escapedText"}""")
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }

  fun handleClipboardWrite(paramsJson: String?): InvokeResult {
    return try {
      val clipMgr = clipboardManager ?: return InvokeResult.error(
        "UNAVAILABLE",
        "ClipboardManager not available"
      )

      val root = paramsJson?.let { json.parseToJsonElement(it).jsonObject }
        ?: return InvokeResult.error("INVALID_ARGUMENT", "Missing parameters")

      val text = root["text"]?.jsonPrimitive?.content ?: ""
      val clip = ClipData.newPlainText("MarmaladeAssistant", text)
      clipMgr.setPrimaryClip(clip)

      InvokeResult.ok("""{"success":true}""")
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }
}
