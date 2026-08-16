package app.marmalade.android.node

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import app.marmalade.android.BuildConfig
import app.marmalade.android.SecurePrefs
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class DeviceHandler(
  private val appContext: Context,
  private val prefs: SecurePrefs,
) {

  fun handleStatus(): InvokeResult {
    val batteryIntent: Intent? = appContext.registerReceiver(
      null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    )
    val rawLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val level = if (rawLevel != -1 && scale > 0) (rawLevel * 100 / scale.toFloat()).toInt() else -1
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1

    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    val payload = buildJsonObject {
      put("batteryLevel", JsonPrimitive(level))
      put("charging", JsonPrimitive(isCharging))
      put("plugged", JsonPrimitive(plugged))
      put("status", JsonPrimitive(status))
      put("temperature", JsonPrimitive(temperature / 10.0)) // Celsius
      put("voltage", JsonPrimitive(voltage))
      put("screenInteractive", JsonPrimitive(powerManager.isInteractive))
      put("voiceWakeMode", JsonPrimitive(prefs.voiceWakeMode.value.rawValue))
      put("locationMode", JsonPrimitive(prefs.locationMode.value.rawValue))
      put("screenPreventSleep", JsonPrimitive(prefs.preventSleep.value))
    }
    return InvokeResult.ok(payload.toString())
  }

  fun handleInfo(): InvokeResult {
    val payload = buildJsonObject {
      put("deviceId", JsonPrimitive(prefs.instanceId.value))
      put("name", JsonPrimitive(prefs.displayName.value))
      put("appVersion", JsonPrimitive(BuildConfig.VERSION_NAME))
      put("appBuild", JsonPrimitive(BuildConfig.VERSION_CODE))
      put("androidSdk", JsonPrimitive(Build.VERSION.SDK_INT))
      put("androidVersion", JsonPrimitive(Build.VERSION.RELEASE))
      put("model", JsonPrimitive(Build.MODEL))
      put("manufacturer", JsonPrimitive(Build.MANUFACTURER))
      put("brand", JsonPrimitive(Build.BRAND))
    }
    return InvokeResult.ok(payload.toString())
  }

  fun handlePermissions(): InvokeResult {
    val fineLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val coarseLocation = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
      fineLocation || coarseLocation
    }

    val payload = buildJsonObject {
      put("camera", JsonPrimitive(hasPermission(Manifest.permission.CAMERA)))
      put("microphone", JsonPrimitive(hasPermission(Manifest.permission.RECORD_AUDIO)))
      put("location", buildJsonObject {
        put("fine", JsonPrimitive(fineLocation))
        put("coarse", JsonPrimitive(coarseLocation))
        put("background", JsonPrimitive(backgroundLocation))
      })
      put("sms", JsonPrimitive(hasPermission(Manifest.permission.SEND_SMS)))
    }
    return InvokeResult.ok(payload.toString())
  }

  fun handleHealth(): InvokeResult {
    val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)

    val stat = StatFs(Environment.getDataDirectory().path)

    val payload = buildJsonObject {
      put("status", JsonPrimitive("ok"))
      put("memory", buildJsonObject {
        put("total", JsonPrimitive(memInfo.totalMem))
        put("available", JsonPrimitive(memInfo.availMem))
        put("lowMemory", JsonPrimitive(memInfo.lowMemory))
      })
      put("storage", buildJsonObject {
        put("total", JsonPrimitive(stat.totalBytes))
        put("available", JsonPrimitive(stat.availableBytes))
      })
    }
    return InvokeResult.ok(payload.toString())
  }

  private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
