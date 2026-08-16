package app.marmalade.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class ScreenCaptureRequester(private val activity: ComponentActivity) {
  data class CaptureResult(val resultCode: Int, val data: Intent)

  private val mutex = Mutex()
  // AtomicReference because the launcher callback runs on Main outside the
  // suspend mutex; a request that timed out leaves the system consent sheet
  // alive, and a late tap must not resolve the *next* request's deferred.
  private val pending = AtomicReference<CompletableDeferred<CaptureResult?>?>(null)

  private val launcher: ActivityResultLauncher<Intent> =
    activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      // Atomically detach whatever is in the slot, then resolve only that
      // deferred. If the slot is already null (we timed out and cleared it)
      // the late callback is dropped instead of leaking into another request.
      val p = pending.getAndSet(null)
      val data = result.data
      if (result.resultCode == Activity.RESULT_OK && data != null) {
        p?.complete(CaptureResult(result.resultCode, data))
      } else {
        p?.complete(null)
      }
    }

  suspend fun requestCapture(timeoutMs: Long = 20_000): CaptureResult? =
    mutex.withLock {
      val proceed = showRationaleDialog()
      if (!proceed) return null

      val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      val intent = mgr.createScreenCaptureIntent()

      val deferred = CompletableDeferred<CaptureResult?>()
      pending.set(deferred)
      withContext(Dispatchers.Main) { launcher.launch(intent) }

      try {
        withContext(Dispatchers.Default) { withTimeout(timeoutMs) { deferred.await() } }
      } finally {
        // Compare-and-set: only clear if we still own the slot. Prevents a
        // request that timed out from wiping a newer request's deferred.
        pending.compareAndSet(deferred, null)
      }
    }

  private suspend fun showRationaleDialog(): Boolean =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { cont ->
        val dialog =
          AlertDialog.Builder(activity)
            .setTitle("Screen recording required")
            .setMessage("Marmalade needs to record the screen for this command.")
            .setPositiveButton("Continue") { _, _ -> cont.resume(true) }
            .setNegativeButton("Not now") { _, _ -> cont.resume(false) }
            .setOnCancelListener { cont.resume(false) }
            .show()
        // Dismiss the dialog if the coroutine is cancelled (e.g. activity
        // destroyed) so its window can't leak (WindowLeaked).
        cont.invokeOnCancellation { dialog.dismiss() }
      }
    }
}
