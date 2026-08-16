package app.marmalade.android.utils

import android.content.Context
import android.os.Build
import app.marmalade.android.BuildConfig
import app.marmalade.android.data.SettingsRepository

object SystemInfoProvider {

    fun getSystemInfoReport(context: Context, settings: SettingsRepository, gatewayVersion: String? = null): String {
        val appVersion = getAppVersion(context)
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        val ttsEngine = settings.ttsEngine.ifEmpty { "System Default" }
        val wakeWord = settings.getWakeWordDisplayName()
        val language = settings.speechLanguage.ifEmpty { "System Default" }

        return buildString {
            appendLine("**Device Information**")
            appendLine("- App Version: $appVersion")
            if (!gatewayVersion.isNullOrBlank()) {
                appendLine("- Gateway Version: $gatewayVersion")
            }
            appendLine("- Device: $deviceModel")
            appendLine("- Android Version: $androidVersion")
            appendLine("- Language: $language")
            appendLine("- TTS Engine: $ttsEngine")
            appendLine("- Wake Word: $wakeWord")
        }.trim()
    }

    private fun getAppVersion(context: Context): String {
        return try {
            // Use BuildConfig for accurate version info from build.gradle
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        } catch (e: Exception) {
            // Fallback to PackageManager if BuildConfig fails
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                val versionCode = packageInfo.versionCode
                "${packageInfo.versionName} ($versionCode)"
            } catch (e2: Exception) {
                "Unknown"
            }
        }
    }
}
