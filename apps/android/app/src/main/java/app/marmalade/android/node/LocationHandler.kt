package app.marmalade.android.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.marmalade.android.LocationMode
import app.marmalade.android.rpc.InvokeResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull

class LocationHandler(
  private val appContext: Context,
  private val location: LocationCaptureManager,
  private val json: Json,
  private val isForeground: () -> Boolean,
  private val locationMode: () -> LocationMode,
  private val locationPreciseEnabled: () -> Boolean,
) {
  fun hasFineLocationPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  fun hasCoarseLocationPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  fun hasBackgroundLocationPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  suspend fun handleLocationGet(paramsJson: String?): InvokeResult {
    val mode = locationMode()
    if (!isForeground() && mode != LocationMode.Always) {
      return InvokeResult.error(
        code = "LOCATION_BACKGROUND_UNAVAILABLE",
        message = "LOCATION_BACKGROUND_UNAVAILABLE: background location requires Always",
      )
    }
    if (!hasFineLocationPermission() && !hasCoarseLocationPermission()) {
      return InvokeResult.error(
        code = "LOCATION_PERMISSION_REQUIRED",
        message = "LOCATION_PERMISSION_REQUIRED: grant Location permission",
      )
    }
    if (!isForeground() && mode == LocationMode.Always && !hasBackgroundLocationPermission()) {
      return InvokeResult.error(
        code = "LOCATION_PERMISSION_REQUIRED",
        message = "LOCATION_PERMISSION_REQUIRED: enable Always in system Settings",
      )
    }
    val (maxAgeMs, timeoutMs, desiredAccuracy) = parseLocationParams(paramsJson)
    val preciseEnabled = locationPreciseEnabled()
    val accuracy =
      when (desiredAccuracy) {
        "precise" -> if (preciseEnabled && hasFineLocationPermission()) "precise" else "balanced"
        "coarse" -> "coarse"
        else -> if (preciseEnabled && hasFineLocationPermission()) "precise" else "balanced"
      }
    val providers =
      when (accuracy) {
        "precise" -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        "coarse" -> listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        else -> listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
      }
    try {
      val payload =
        location.getLocation(
          desiredProviders = providers,
          maxAgeMs = maxAgeMs,
          timeoutMs = timeoutMs,
          isPrecise = accuracy == "precise",
        )
      return InvokeResult.ok(payload.payloadJson)
    } catch (err: TimeoutCancellationException) {
      return InvokeResult.error(
        code = "LOCATION_TIMEOUT",
        message = "LOCATION_TIMEOUT: no fix in time",
      )
    } catch (err: Throwable) {
      val message = err.message ?: "LOCATION_UNAVAILABLE: no fix"
      return InvokeResult.error(code = "LOCATION_UNAVAILABLE", message = message)
    }
  }

  suspend fun handleLocationHistory(paramsJson: String?): InvokeResult {
    // Requires implementation of background location tracking and local storage
    return InvokeResult.error(
        code = "UNIMPLEMENTED",
        message = "UNIMPLEMENTED: Location history tracking is not fully implemented yet."
    )
  }

  suspend fun handleLocationLastKnown(paramsJson: String?): InvokeResult {
    if (!hasFineLocationPermission() && !hasCoarseLocationPermission()) {
      return InvokeResult.error(
        code = "LOCATION_PERMISSION_REQUIRED",
        message = "LOCATION_PERMISSION_REQUIRED: grant Location permission",
      )
    }
    
    try {
        val payload = location.getLastKnownLocation()
        if (payload != null) {
            return InvokeResult.ok(payload.payloadJson)
        } else {
             return InvokeResult.error(
                code = "LOCATION_UNAVAILABLE",
                message = "LOCATION_UNAVAILABLE: No last known location found.",
             )
        }
    } catch (err: Throwable) {
        return InvokeResult.error(
            code = "LOCATION_ERROR",
            message = err.message ?: "Unknown error retrieving last known location."
        )
    }
  }

  suspend fun handleLocationSetTracking(paramsJson: String?): InvokeResult {
    val enabled = try {
        paramsJson?.let { json.parseToJsonElement(it).jsonObject["enabled"]?.let { el -> (el as? JsonPrimitive)?.booleanOrNull } }
    } catch (_: Exception) { null }
    
    if (enabled == null) {
        return InvokeResult.error("INVALID_ARGUMENT", "Missing 'enabled' boolean parameter.")
    }
    
    // In a real implementation, this would start/stop a Foreground Service for location updates
    return InvokeResult.ok("""{"enabled":$enabled,"note":"This feature is simulated in the current version."}""")
  }

  private fun parseLocationParams(paramsJson: String?): Triple<Long?, Long, String?> {
    if (paramsJson.isNullOrBlank()) {
      return Triple(null, 10_000L, null)
    }
    val root =
      try {
        json.parseToJsonElement(paramsJson).jsonObject
      } catch (_: Throwable) {
        null
      }
    val maxAgeMs = (root?.get("maxAgeMs") as? JsonPrimitive)?.content?.toLongOrNull()
    val timeoutMs =
      (root?.get("timeoutMs") as? JsonPrimitive)?.content?.toLongOrNull()?.coerceIn(1_000L, 60_000L)
        ?: 10_000L
    val desiredAccuracy =
      (root?.get("desiredAccuracy") as? JsonPrimitive)?.content?.trim()?.lowercase()
    return Triple(maxAgeMs, timeoutMs, desiredAccuracy)
  }
}
