package app.marmalade.android.node

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppHandler(
  private val context: Context,
  private val json: Json,
  private val invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
) {
  fun handleAppLaunch(paramsJson: String?): InvokeResult {
    return try {
      val root = paramsJson?.let { json.parseToJsonElement(it).jsonObject }
        ?: return InvokeResult.error("INVALID_ARGUMENT", "Missing parameters")

      val packageName = root["packageName"]?.jsonPrimitive?.content
        ?: return InvokeResult.error("INVALID_ARGUMENT", "packageName is required")

      val pm = context.packageManager
      val intent = pm.getLaunchIntentForPackage(packageName)
      if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        InvokeResult.ok("""{"success":true}""")
      } else {
        InvokeResult.error("NOT_FOUND", "App not found or cannot be launched: $packageName")
      }
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }

  fun handleAppList(): InvokeResult {
    return try {
      val pm = context.packageManager
      // Note: Querying all packages requires QUERY_ALL_PACKAGES permission in Android 11+
      // For now, get installed packages, though it might be limited by package visibility rules.
      val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
      val apps = packages.filter {
          (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("com.google.android")
      }.joinToString(",") { appInfo ->
        val label = pm.getApplicationLabel(appInfo).toString().replace("\"", "\\\"")
        val pkg = appInfo.packageName
        val category = categorizeApp(appInfo)
        """{"packageName":"$pkg","name":"$label","category":"$category"}"""
      }

      InvokeResult.ok("""{"apps":[$apps]}""")
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }

  /**
   * Map [ApplicationInfo.category] constants (API 26+, minSdk 31) to human-readable labels.
   */
  private fun categorizeApp(info: ApplicationInfo): String =
    when (info.category) {
      ApplicationInfo.CATEGORY_GAME -> "game"
      ApplicationInfo.CATEGORY_AUDIO -> "audio"
      ApplicationInfo.CATEGORY_VIDEO -> "video"
      ApplicationInfo.CATEGORY_IMAGE -> "image"
      ApplicationInfo.CATEGORY_SOCIAL -> "social"
      ApplicationInfo.CATEGORY_NEWS -> "news"
      ApplicationInfo.CATEGORY_MAPS -> "maps"
      ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
      ApplicationInfo.CATEGORY_ACCESSIBILITY -> "accessibility"
      else -> "other"
    }
}
