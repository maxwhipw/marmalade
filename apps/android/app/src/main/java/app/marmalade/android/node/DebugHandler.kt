package app.marmalade.android.node

import android.content.Context
import android.os.Build
import app.marmalade.android.BuildConfig
import app.marmalade.android.identity.DeviceIdentity
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.JsonPrimitive

class DebugHandler(
  private val appContext: Context,
  private val deviceIdentity: DeviceIdentity,
) {

  /**
   * Returns the local device identity for diagnostics. Debug builds only.
   */
  fun handleIdentity(): InvokeResult {
    if (!BuildConfig.DEBUG) {
      return InvokeResult.error(code = "UNAVAILABLE", message = "debug commands are disabled in release builds")
    }
    val info = buildString {
      append("deviceId=").append(deviceIdentity.deviceId).append('\n')
      append("sdk=").append(Build.VERSION.SDK_INT).append('\n')
      append("device=").append(Build.MODEL)
    }
    return InvokeResult.ok("""{"diagnostics":${JsonPrimitive(info)}}""")
  }

  fun handleLogs(): InvokeResult {
    if (!BuildConfig.DEBUG) {
      return InvokeResult.error(code = "UNAVAILABLE", message = "debug commands are disabled in release builds")
    }
    val pid = android.os.Process.myPid()
    val rt = Runtime.getRuntime()
    val info = "v6 pid=$pid thread=${Thread.currentThread().name} free=${rt.freeMemory()/1024}K total=${rt.totalMemory()/1024}K max=${rt.maxMemory()/1024}K uptime=${android.os.SystemClock.elapsedRealtime()/1000}s sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MODEL}\n"
    // Run logcat on current dispatcher thread (no withContext) with file redirect
    val logResult = try {
      val tmpFile = java.io.File(appContext.cacheDir, "debug_logs.txt")
      if (tmpFile.exists()) tmpFile.delete()
      val pb = ProcessBuilder("logcat", "-d", "-t", "200", "--pid=$pid")
      pb.redirectOutput(tmpFile)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val finished = proc.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)
      if (!finished) proc.destroyForcibly()
      val raw = if (tmpFile.exists() && tmpFile.length() > 0) {
        tmpFile.readText().take(128000)
      } else {
        "(no output, finished=$finished, exists=${tmpFile.exists()})"
      }
      tmpFile.delete()
      val spamPatterns = listOf("setRequestedFrameRate", "I View    :", "BLASTBufferQueue", "VRI[Pop-Up",
        "InsetsController:", "VRI[MainActivity", "InsetsSource:", "handleResized", "ProfileInstaller",
        "I VRI[", "onStateChanged: host=", "D StrictMode:", "E StrictMode:", "ImeFocusController",
        "InputTransport", "IncorrectContextUseViolation")
      val sb = StringBuilder()
      for (line in raw.lineSequence()) {
        if (line.isBlank()) continue
        if (spamPatterns.any { line.contains(it) }) continue
        if (sb.length + line.length > 16000) { sb.append("\n(truncated)"); break }
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(line)
      }
      sb.toString().ifEmpty { "(all ${raw.lines().size} lines filtered as spam)" }
    } catch (e: Throwable) {
      "(logcat error: ${e::class.java.simpleName}: ${e.message})"
    }
    // Also include camera debug log if it exists
    val camLogFile = java.io.File(appContext.cacheDir, "camera_debug.log")
    val camLog = if (camLogFile.exists() && camLogFile.length() > 0) {
      "\n--- camera_debug.log ---\n" + camLogFile.readText().take(4000)
    } else ""
    return InvokeResult.ok("""{"logs":${JsonPrimitive(info + logResult + camLog)}}""")
  }
}
