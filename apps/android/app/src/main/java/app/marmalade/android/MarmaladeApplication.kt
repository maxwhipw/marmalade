package app.marmalade.android

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.util.Log
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.node.MarmaladeRuntime
import app.marmalade.android.notification.NotificationChannelManager
import app.marmalade.android.speech.STTEngineProvider
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MarmaladeApplication : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val marmaladeRuntime: MarmaladeRuntime by lazy {
        MarmaladeRuntime(this)
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        applySavedAppLocale()
        // Create persistent notification channel (idempotent, safe to call every launch)
        NotificationChannelManager.ensurePersistentChannel(this)
        // Preload STT engine if user has opted in
        if (SettingsRepository.getInstance(this).keepSTTLoaded) {
            appScope.launch {
                try {
                    STTEngineProvider.getInstance(this@MarmaladeApplication).warmup()
                    Log.i("MarmaladeApp", "STT engine preloaded (keepSTTLoaded=true)")
                } catch (e: Exception) {
                    Log.w("MarmaladeApp", "STT preload failed", e)
                }
            }
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("Thread: ${thread.name}")
                pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                pw.println()
                throwable.printStackTrace(pw)
                crashFile().writeText(sw.toString())
            } catch (_: Throwable) {
                // last resort — don't crash the crash handler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun crashFile(): File = File(filesDir, "last_crash.txt")

    /**
     * Wire Coil with an OkHttp client that injects the dashboard bearer
     * token on requests to the configured dashboard host. Without this,
     * inline images served from the gateway's `/api/files/...` surface
     * 401 against the auth middleware — Android would just show a broken
     * placeholder. Matches desktop's `gatewayMediaDataUrl()` flow
     * (markdown-text.tsx:113-128) where image fetches go through the
     * authenticated REST bridge.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val authClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val runtime = marmaladeRuntime
                val dashUrl = runtime.dashboardUrl.value.trim()
                val token = runtime.dashboardToken.value.trim()
                if (dashUrl.isNotBlank() && token.isNotBlank() &&
                    requestHostMatchesDashboard(original.url.host, dashUrl)
                ) {
                    val authed = original.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(authed)
                } else {
                    chain.proceed(original)
                }
            }
            .build()
        return ImageLoader.Builder(this)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { authClient }))
            }
            .build()
    }

    private fun requestHostMatchesDashboard(requestHost: String, dashboardUrl: String): Boolean {
        val dashHost = dashboardUrl
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        return dashHost.isNotBlank() && requestHost.equals(dashHost, ignoreCase = true)
    }

    private fun applySavedAppLocale() {
        val tag = SettingsRepository.getInstance(this).appLanguage.trim()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            val locales = if (tag.isBlank()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
            try {
                localeManager.applicationLocales = locales
            } catch (e: Exception) {
                Log.w("MarmaladeApp", "Failed to set app locale", e)
            }
        } else if (tag.isNotBlank()) {
            // Pre-Android 13: update default locale as best-effort
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
        }
    }
}
