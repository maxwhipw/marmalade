package app.marmalade.android.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Builds the device's installed-app catalog as a JSON payload so the
 * agent can route voice commands (`"open YouTube"`, `"play music on
 * Spotify"`) to specific packages.
 *
 * - [buildAppListPayload] returns the catalog JSON. The catalog is
 *   surfaced to the agent through the `app.list` tool, called on demand
 *   (see [app.marmalade.android.node.AppHandler.handleAppList]); there is
 *   no periodic auto-push. The class survives because the package-changed
 *   receiver still warrants programmatic registration — the catalog
 *   refresh hook may grow back when the Python plugin emits a
 *   fresh-catalog request.
 * - [registerPackageReceiver] listens for PACKAGE_ADDED / PACKAGE_REMOVED.
 *
 * The receiver is registered **programmatically** (not in
 * AndroidManifest.xml) because manifest-declared receivers for implicit
 * package broadcasts are silently ignored on Android 8+ (Research
 * Pitfall 4).
 */
class AppListSyncer(private val context: Context) {

    companion object {
        private const val TAG = "AppListSyncer"
    }

    private var packageReceiver: BroadcastReceiver? = null

    /**
     * Build the JSON catalog of installed user apps for the caller to
     * dispatch (e.g. as an agent-tool result or an `app.list` push).
     *
     * Call from `Dispatchers.IO` — PackageManager queries can be slow
     * on devices with many installed apps. Returns `{"apps":[...]}` or
     * an empty array on error (logged at WARN).
     */
    fun buildAppListPayload(): String {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val apps = packages.filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    it.packageName.contains("com.google.android")
            }.joinToString(",") { appInfo ->
                val label = pm.getApplicationLabel(appInfo).toString()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                val pkg = appInfo.packageName
                val category = categorizeApp(appInfo)
                """{"name":"$label","package":"$pkg","category":"$category"}"""
            }

            """{"apps":[$apps]}"""
        } catch (e: Throwable) {
            Log.w(TAG, "App list build failed: ${e.message ?: e::class.java.simpleName}")
            """{"apps":[]}"""
        }
    }

    /**
     * Register a [BroadcastReceiver] for [Intent.ACTION_PACKAGE_ADDED] and
     * [Intent.ACTION_PACKAGE_REMOVED]. When a package change is detected,
     * [onPackageChanged] is invoked so the caller can trigger [syncAppList].
     *
     * Must be balanced by a call to [unregisterPackageReceiver] on disconnect or destroy.
     */
    fun registerPackageReceiver(context: Context, onPackageChanged: () -> Unit) {
        if (packageReceiver != null) return // already registered

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REMOVED) {
                    Log.d(TAG, "Package changed ($action), scheduling re-sync")
                    onPackageChanged()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }

        context.registerReceiver(receiver, filter)
        packageReceiver = receiver
        Log.d(TAG, "Package change receiver registered")
    }

    /**
     * Unregister the package change receiver. Safe to call even if never registered.
     */
    fun unregisterPackageReceiver(context: Context) {
        packageReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
                Log.d(TAG, "Package change receiver unregistered")
            } catch (e: IllegalArgumentException) {
                // Already unregistered -- safe to ignore
                Log.d(TAG, "Package receiver already unregistered")
            }
        }
        packageReceiver = null
    }

    /**
     * Map [ApplicationInfo.category] constants (API 26+, minSdk 31) to string labels.
     * Mirrors [AppHandler.categorizeApp] for consistency.
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
