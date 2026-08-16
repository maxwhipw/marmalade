package app.marmalade.android

import android.content.pm.PackageManager
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.provider.Settings
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class PermissionRequester(private val activity: ComponentActivity) {
  private val mutex = Mutex()
  // AtomicReference because the launcher callback runs on Main outside the
  // suspend mutex; a request that timed out leaves the system dialog alive,
  // and a late user tap must not resolve the *next* request's deferred.
  private val pending = AtomicReference<CompletableDeferred<Map<String, Boolean>>?>(null)

  private val launcher: ActivityResultLauncher<Array<String>> =
    activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      // Atomically detach whatever is in the slot, then resolve only that
      // deferred. If the slot is already null (we timed out and cleared it)
      // the late callback is dropped instead of leaking into another request.
      pending.getAndSet(null)?.complete(result)
    }

  suspend fun requestIfMissing(
    permissions: List<String>,
    timeoutMs: Long = 20_000,
  ): Map<String, Boolean> =
    mutex.withLock {
      val missing =
        permissions.filter { perm ->
          ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED
        }
      if (missing.isEmpty()) {
        return permissions.associateWith { true }
      }

      val needsRationale =
        missing.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
      if (needsRationale) {
        val proceed = showRationaleDialog(missing)
        if (!proceed) {
          return permissions.associateWith { perm ->
            ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
          }
        }
      }

      val deferred = CompletableDeferred<Map<String, Boolean>>()
      pending.set(deferred)
      withContext(Dispatchers.Main) {
        launcher.launch(missing.toTypedArray())
      }

      val result =
        try {
          withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(timeoutMs) { deferred.await() }
          }
        } finally {
          // Compare-and-set: only clear if we still own the slot. Prevents a
          // request that timed out from wiping a newer request's deferred.
          pending.compareAndSet(deferred, null)
        }

      // Merge: if something was already granted, treat it as granted even if launcher omitted it.
      val merged =
        permissions.associateWith { perm ->
        val nowGranted =
          ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
        result[perm] == true || nowGranted
      }

      val denied =
        merged.filterValues { !it }.keys.filter {
          !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
      if (denied.isNotEmpty()) {
        showSettingsDialog(denied)
      }

      return merged
    }

  private suspend fun showRationaleDialog(permissions: List<String>): Boolean =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { cont ->
        val dialog =
          AlertDialog.Builder(activity)
            .setTitle("Permission required")
            .setMessage(buildRationaleMessage(permissions))
            .setPositiveButton("Continue") { _, _ -> cont.resume(true) }
            .setNegativeButton("Not now") { _, _ -> cont.resume(false) }
            .setOnCancelListener { cont.resume(false) }
            .show()
        // Dismiss the dialog if the coroutine is cancelled (e.g. activity
        // destroyed) so its window can't leak (WindowLeaked).
        cont.invokeOnCancellation { dialog.dismiss() }
      }
    }

  suspend fun requestNotificationAccess() =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine<Unit> { cont ->
        val dialog =
          AlertDialog.Builder(activity)
            .setTitle("Notification Access Required")
            .setMessage(
              "Marmalade needs notification access to read and manage your notifications. " +
                "Tap \"Open Settings\", enable Marmalade, then return to the app."
            )
            .setPositiveButton("Open Settings") { _, _ ->
              activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
              cont.resume(Unit)
            }
            .setNegativeButton("Not now") { _, _ -> cont.resume(Unit) }
            .setOnCancelListener { cont.resume(Unit) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
      }
    }

  private fun showSettingsDialog(permissions: List<String>) {
    AlertDialog.Builder(activity)
      .setTitle("Enable permission in Settings")
      .setMessage(buildSettingsMessage(permissions))
      .setPositiveButton("Open Settings") { _, _ ->
        val intent =
          Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null),
          )
        activity.startActivity(intent)
      }
      .setNegativeButton("Cancel", null)
      .show()
  }

  private fun buildRationaleMessage(permissions: List<String>): String {
    val labels = permissions.map { permissionLabel(it) }
    return "Marmalade needs ${labels.joinToString(", ")} permissions to continue."
  }

  private fun buildSettingsMessage(permissions: List<String>): String {
    val labels = permissions.map { permissionLabel(it) }
    return "Please enable ${labels.joinToString(", ")} in Android Settings to continue."
  }

  private fun permissionLabel(permission: String): String =
    when (permission) {
      Manifest.permission.CAMERA -> "Camera"
      Manifest.permission.RECORD_AUDIO -> "Microphone"
      Manifest.permission.SEND_SMS -> "SMS"
      Manifest.permission.READ_CONTACTS -> "Contacts (read)"
      Manifest.permission.WRITE_CONTACTS -> "Contacts (write)"
      Manifest.permission.READ_CALENDAR -> "Calendar (read)"
      Manifest.permission.WRITE_CALENDAR -> "Calendar (write)"
      Manifest.permission.READ_MEDIA_IMAGES -> "Photos"
      Manifest.permission.READ_EXTERNAL_STORAGE -> "Storage"
      Manifest.permission.ACTIVITY_RECOGNITION -> "Activity Recognition"
      else -> permission
    }
}
