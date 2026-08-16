package app.marmalade.android.node

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.marmalade.android.R
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import android.media.AudioManager
import android.provider.Settings

class SystemHandler(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    fun handleNotify(paramsJson: String?): InvokeResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return InvokeResult.error("PERMISSION_REQUIRED", "POST_NOTIFICATIONS permission required")
            }
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val title = (params["title"] as? JsonPrimitive)?.content ?: "Marmalade"
        val message = (params["message"] as? JsonPrimitive)?.content ?: ""

        if (message.isEmpty()) {
            return InvokeResult.error("INVALID_REQUEST", "Message is required")
        }

        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "openclaw_system"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Marmalade System Notifications", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)

        return InvokeResult.ok("""{"ok":true}""")
    }

    fun handleSetAlarm(paramsJson: String?): InvokeResult {
        return try {
            val params = paramsJson?.let {
                try { json.parseToJsonElement(it).jsonObject } catch (e: Exception) { null }
            } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

            val hour = (params["hour"] as? JsonPrimitive)?.intOrNull
                ?: return InvokeResult.error("INVALID_REQUEST", "hour required")
            val minute = (params["minute"] as? JsonPrimitive)?.intOrNull
                ?: return InvokeResult.error("INVALID_REQUEST", "minute required")
            if (hour !in 0..23 || minute !in 0..59) {
                return InvokeResult.error("INVALID_REQUEST", "hour 0-23, minute 0-59")
            }
            val label = (params["label"] as? JsonPrimitive)?.content ?: ""

            // ACTION_SET_ALARM with SKIP_UI creates the alarm in the user's Clock
            // app with no extra permission beyond SET_ALARM (manifest, normal).
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // started from non-Activity context
            }
            try {
                appContext.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                return InvokeResult.error("NO_CLOCK_APP", "No clock app handles set-alarm")
            }
            InvokeResult.ok("""{"set":true,"hour":$hour,"minute":$minute}""")
        } catch (e: Exception) {
            InvokeResult.error("INTERNAL_ERROR", e.message ?: "Unknown error")
        }
    }

    fun handleVolume(paramsJson: String?): InvokeResult {
        return try {
            val params = paramsJson?.let {
                try { json.parseToJsonElement(it).jsonObject } catch (e: Exception) { null }
            } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

            val level = (params["level"] as? JsonPrimitive)?.intOrNull

            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            if (level != null) {
                // Set volume
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val safeLevel = level.coerceIn(0, 100)
                val newVolume = (safeLevel / 100f * maxVolume).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                InvokeResult.ok("""{"level":$safeLevel}""")
            } else {
                // Get volume
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val percentage = if (maxVolume > 0) (currentVolume * 100 / maxVolume) else 0
                InvokeResult.ok("""{"level":$percentage}""")
            }
        } catch (e: Exception) {
            InvokeResult.error("INTERNAL_ERROR", e.message ?: "Unknown error")
        }
    }

    fun handleBrightness(paramsJson: String?): InvokeResult {
        return try {
            val params = paramsJson?.let {
                try { json.parseToJsonElement(it).jsonObject } catch (e: Exception) { null }
            } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

            val level = (params["level"] as? JsonPrimitive)?.intOrNull

            if (level != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(appContext)) {
                    return InvokeResult.error("PERMISSION_DENIED", "WRITE_SETTINGS permission is not granted")
                }
                // Set brightness
                val safeLevel = level.coerceIn(0, 100)
                val newBrightness = (safeLevel / 100f * 255).toInt()
                
                // Disable auto-brightness if setting manually
                Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newBrightness)
                
                InvokeResult.ok("""{"level":$safeLevel}""")
            } else {
                // Get brightness
                val currentBrightness = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                val percentage = (currentBrightness * 100 / 255f).toInt()
                InvokeResult.ok("""{"level":$percentage}""")
            }
        } catch (e: Settings.SettingNotFoundException) {
             InvokeResult.error("INTERNAL_ERROR", "Could not read brightness setting")
        } catch (e: Exception) {
            InvokeResult.error("INTERNAL_ERROR", e.message ?: "Unknown error")
        }
    }
}
