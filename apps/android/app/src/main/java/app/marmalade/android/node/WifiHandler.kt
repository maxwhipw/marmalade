package app.marmalade.android.node

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@SuppressLint("MissingPermission")
class WifiHandler(
  private val context: Context,
  private val json: Json,
  private val invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
) {
  private val wifiManager: WifiManager? by lazy {
    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
  }

  fun handleWifiStatus(): InvokeResult {
    return try {
      val wm = wifiManager ?: return InvokeResult.error("UNAVAILABLE", "WifiManager not available")
      val isEnabled = wm.isWifiEnabled
      val info = wm.connectionInfo

      val payload = if (isEnabled && info != null && info.networkId != -1) {
        // Connected
        val ssid = info.ssid?.replace("\"", "") ?: ""
        val bssid = info.bssid ?: ""
        val rssi = info.rssi
        val linkSpeed = info.linkSpeed
        val ipAddress = info.ipAddress
        val ipString = String.format(
          "%d.%d.%d.%d",
          ipAddress and 0xff,
          ipAddress shr 8 and 0xff,
          ipAddress shr 16 and 0xff,
          ipAddress shr 24 and 0xff
        )

        """{"enabled":true,"connected":true,"ssid":"$ssid","bssid":"$bssid","rssi":$rssi,"linkSpeedMbps":$linkSpeed,"ipAddress":"$ipString"}"""
      } else {
        // Disconnected or disabled
        """{"enabled":$isEnabled,"connected":false}"""
      }
      InvokeResult.ok(payload)
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }

  fun handleWifiList(): InvokeResult {
    return try {
      val wm = wifiManager ?: return InvokeResult.error("UNAVAILABLE", "WifiManager not available")
      if (!wm.isWifiEnabled) {
        return InvokeResult.error("FAILED_PRECONDITION", "Wi-Fi is disabled")
      }

      // Note: Starting with Android 10, retrieving scan results requires location permission
      // and location services to be enabled. Assuming permissions are mostly granted or handled.
      // Simply retrieving last scan results.
      val results = wm.scanResults
      val networks = results.map { result ->
        val ssid = result.SSID.replace("\"", "")
        val bssid = result.BSSID
        val rssi = result.level
        """{"ssid":"$ssid","bssid":"$bssid","rssi":$rssi}"""
      }.joinToString(",")

      InvokeResult.ok("""{"networks":[$networks]}""")
    } catch (e: SecurityException) {
      InvokeResult.error("PERMISSION_DENIED", "Location permission is required to list Wi-Fi networks.")
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }

  fun handleWifiConnect(paramsJson: String?): InvokeResult {
    return try {
      // connecting programmatically to a specific SSID is complex and restricted on modern Android.
      // Usually requires Q network suggestion API or companion device manager.
      // Setting Wi-Fi enabled/disabled state is deprecated but might still work on older devices, 
      // or we can just mock the basic behavior/intent.
      // For this command, we'll try to just parse the 'enabled' flag if provided, to toggle Wi-Fi.
      
      val root = paramsJson?.let { json.parseToJsonElement(it).jsonObject } ?: return InvokeResult.error("INVALID_ARGUMENT", "Missing parameters")
      
      // If action is to toggle wifi
      val enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull
      if (enabled != null) {
          @Suppress("DEPRECATION")
          wifiManager?.setWifiEnabled(enabled)
          return InvokeResult.ok("""{"enabled":$enabled}""")
      }
      
      InvokeResult.error("UNIMPLEMENTED", "Directly connecting to a specific SSID is currently not fully supported via this node capability.")
    } catch (e: Throwable) {
      val (code, msg) = invokeErrorFromThrowable(e)
      InvokeResult.error(code, msg)
    }
  }
}
