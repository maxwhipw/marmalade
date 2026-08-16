package app.marmalade.android.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.service.HotwordService
import app.marmalade.android.service.NodeForegroundService

/**
 * Start hotword service on boot
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed")
            
            val settings = SettingsRepository.getInstance(context)
            
            if (settings.isConfigured()) {
                // Start the persistent foreground service (combined status notification).
                // NodeForegroundService will auto-start HotwordService when voice wake is enabled.
                Log.d(TAG, "Starting NodeForegroundService on boot")
                NodeForegroundService.start(context)
            }
        }
    }
}
