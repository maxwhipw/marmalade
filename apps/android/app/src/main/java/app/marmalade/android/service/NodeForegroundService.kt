package app.marmalade.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.marmalade.android.MainActivity
import app.marmalade.android.MarmaladeApplication
import app.marmalade.android.R
import app.marmalade.android.VoiceWakeMode
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.notification.NotificationChannelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class NodeForegroundService : Service() {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var notificationJob: Job? = null
  private var lastRequiresMic = false
  private var didStartForeground = false
  private var lastHotwordState = false

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    val initial = buildNotification(title = "Marmalade", text = "Starting…")
    startForegroundWithTypes(notification = initial, requiresMic = false)

    val runtime = (application as MarmaladeApplication).marmaladeRuntime
    notificationJob =
      scope.launch {
        combine(
          runtime.statusText,
          runtime.serverName,
          runtime.isConnected,
          runtime.voiceWakeMode,
          runtime.voiceWakeIsListening,
        ) { args ->
          val status = args[0] as String
          val server = args[1] as? String
          val connected = args[2] as Boolean
          val voiceMode = args[3] as VoiceWakeMode
          val voiceListening = args[4] as Boolean
          Quint(status, server, connected, voiceMode, voiceListening)
        }.collect { (status, server, connected, voiceMode, voiceListening) ->
          val title = if (connected) "Marmalade · Connected" else "Marmalade"
          val voiceSuffix =
            if (voiceMode == VoiceWakeMode.Always) {
              if (voiceListening) " · Voice Wake: Listening" else " · Voice Wake: Paused"
            } else {
              ""
            }
          val text = (server?.let { "$status · $it" } ?: status) + voiceSuffix

          val requiresMic =
            voiceMode == VoiceWakeMode.Always && hasRecordAudioPermission()

          // HotwordService auto-start: runs when wake mode is enabled (Foreground or Always)
          // + mic permission + user hasn't disabled hotword in settings
          val settings = SettingsRepository.getInstance(this@NodeForegroundService)
          val shouldRunHotword = voiceMode != VoiceWakeMode.Off
              && settings.hotwordEnabled
              && hasRecordAudioPermission()

          if (shouldRunHotword && !lastHotwordState) {
              HotwordService.start(this@NodeForegroundService)
              lastHotwordState = true
          } else if (!shouldRunHotword && lastHotwordState) {
              HotwordService.stop(this@NodeForegroundService)
              lastHotwordState = false
          }

          startForegroundWithTypes(
            notification = buildNotification(title = title, text = text),
            requiresMic = requiresMic,
          )
        }
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        (application as MarmaladeApplication).marmaladeRuntime.disconnect()
        stopSelf()
        return START_NOT_STICKY
      }
    }
    // Keep running; connection is managed by MarmaladeRuntime (auto-reconnect + manual).
    return START_STICKY
  }

  override fun onDestroy() {
    notificationJob?.cancel()
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?) = null

  private fun ensureChannel() {
    NotificationChannelManager.ensurePersistentChannel(this)
  }

  private fun buildNotification(title: String, text: String): Notification {
    val launchIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val launchPending =
      PendingIntent.getActivity(
        this,
        1,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent = Intent(this, NodeForegroundService::class.java).setAction(ACTION_STOP)
    val stopPending =
      PendingIntent.getService(
        this,
        2,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, NotificationChannelManager.PERSISTENT_CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(launchPending)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(0, "Disconnect", stopPending)
      .build()
  }

  private fun updateNotification(notification: Notification) {
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.notify(NOTIFICATION_ID, notification)
  }

  private fun startForegroundWithTypes(notification: Notification, requiresMic: Boolean) {
    if (didStartForeground && requiresMic == lastRequiresMic) {
      updateNotification(notification)
      return
    }

    lastRequiresMic = requiresMic

    // Use connectedDevice (no time limit) — dataSync has a 6-hour limit on Android 15+
    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    if (requiresMic) {
        types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }

    try {
      startForeground(NOTIFICATION_ID, notification, types)
      didStartForeground = true
    } catch (e: Exception) {
      android.util.Log.e("NodeForegroundSvc", "Foreground service start failed", e)
      updateNotification(notification)
    }
  }


  private fun hasRecordAudioPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  companion object {
    const val NOTIFICATION_ID = 1

    private const val ACTION_STOP = "app.marmalade.android.action.STOP"

    fun start(context: Context) {
      val intent = Intent(context, NodeForegroundService::class.java)
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, NodeForegroundService::class.java).setAction(ACTION_STOP)
      context.startService(intent)
    }
  }
}

private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
