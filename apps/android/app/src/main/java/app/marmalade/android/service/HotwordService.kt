package app.marmalade.android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.marmalade.android.MainActivity
import app.marmalade.android.MarmaladeApplication
import app.marmalade.android.R
import app.marmalade.android.VoiceWakeMode
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.mic.MicOwner
import app.marmalade.android.mic.MicOwnershipManager
import app.marmalade.android.notification.NotificationChannelManager
import app.marmalade.android.speech.wake.WakeModel
import app.marmalade.android.speech.wake.WakeWordPipeline
import app.marmalade.android.voice.SoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data Flow: HotwordService (in-repo wake-word pipeline)
 *
 * WakeWordPipeline (manages its own AudioRecord, 16kHz mono, VOICE_RECOGNITION source)
 *         |
 *     Audio → Silero VAD gate → Melspectrogram → Speech Embedding → Wake Word Classifier
 *         |  (2-of-3 hop confirmation + cooldown)
 *     onWakeWordDetected() → startService ACTION_SHOW_ASSISTANT
 *         |
 *     MarmaladeAssistantService → shows voice popup overlay
 *
 * WakeWordPipeline handles audio recording, VAD gating, preprocessing, and
 * inference internally (see app.marmalade.android.speech.wake, which
 * replaced xyz.rementia:openwakeword — see docs/decisions/0010, superseding
 * 0003). Mic handoff for voice sessions uses stop()/start() which fully
 * releases and recreates the AudioRecord.
 */
class HotwordService : Service() {

    companion object {
        private const val TAG = "HotwordService"
        // Share the same notification ID as NodeForegroundService so Android coalesces them
        private val NOTIFICATION_ID = NodeForegroundService.NOTIFICATION_ID

        // Detection cooldown (engine also has its own, this is defense-in-depth)
        const val COOLDOWN_MS = 3000L

        fun start(context: Context) {
            val intent = Intent(context, HotwordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HotwordService::class.java))
        }

        /**
         * Pure function to decide whether a wake-word detection should trigger.
         * Returns true if enough time has elapsed since the last detection.
         */
        fun shouldTrigger(
            lastDetectionTime: Long,
            now: Long,
            cooldownMs: Long = COOLDOWN_MS,
        ): Boolean {
            if (lastDetectionTime == 0L) return true
            return (now - lastDetectionTime) >= cooldownMs
        }

        /**
         * Pure predicate: should hotword detection actually run?
         *
         * All three preconditions must be satisfied. Falsifying any one of them
         * means the service should not allocate the mic — and `onTaskRemoved`
         * must not arm a doze-piercing restart alarm. Otherwise a swipe-away
         * with hotword disabled / voice mode Off / mic revoked would wake the
         * device every ~3s indefinitely.
         */
        @JvmStatic
        fun shouldRunHotwordDetection(
            enabled: Boolean,
            mode: VoiceWakeMode,
            hasMicPermission: Boolean,
        ): Boolean {
            return enabled && mode != VoiceWakeMode.Off && hasMicPermission
        }
    }

    private lateinit var settings: SettingsRepository
    private lateinit var soundManager: SoundManager

    private var engine: WakeWordPipeline? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastDetectionTime = 0L

    private val micOwnership: MicOwnershipManager by lazy {
        MicOwnershipManager.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.getInstance(this)
        soundManager = SoundManager.getInstance(this)
        // NodeForegroundService already creates the persistent channel;
        // skip redundant createNotificationChannel() here.

        // Mic handoff is coordinated through MicOwnershipManager (no broadcasts).
        // When an STT consumer releases the mic, this callback restarts KWS.
        // It carries the manager's 50ms settle delay, which the raw currentOwner
        // -> NONE transition does not — so the restart edge lives here, and only
        // the stop edge lives in the currentOwner collector below.
        micOwnership.setOnMicReleasedToKws { restartKws() }

        // The currentOwner collector is the single mechanism for the *stop*
        // edge: the instant a non-KWS consumer acquires the mic, KWS must drop
        // its AudioRecord so the two never coexist (voice.md: AudioRecord is
        // single-owner). Engine calls are marshalled onto Main — WakeWordEngine
        // was previously only ever driven from a Main-thread BroadcastReceiver.
        serviceScope.launch {
            micOwnership.currentOwner.collect { owner ->
                when (owner) {
                    MicOwner.VOICE_SESSION, MicOwner.INLINE_STT -> {
                        Log.i(TAG, "Mic owner is $owner — stopping engine (releasing mic)")
                        withContext(Dispatchers.Main) { engine?.stop() }
                    }
                    MicOwner.KWS, MicOwner.NONE -> {
                        // Restart on the NONE edge is handled by the
                        // setOnMicReleasedToKws callback, not here — restarting
                        // from both would double-start the engine.
                    }
                }
            }
        }
    }

    /**
     * Re-acquire the mic for KWS and restart wake-word detection. Invoked from
     * [MicOwnershipManager]'s release callback (on the manager scope, after a
     * 50ms settle delay) once an STT consumer has handed the mic back.
     */
    private fun restartKws() {
        if (!shouldRunHotwordDetection()) {
            Log.i(TAG, "Mic released but hotword detection no longer eligible; not restarting KWS")
            return
        }
        // KWS is always granted by the manager; the check is defensive.
        if (!micOwnership.requestMic(MicOwner.KWS)) {
            Log.w(TAG, "requestMic(KWS) denied unexpectedly; not restarting engine")
            return
        }
        val e = engine
        if (e != null) {
            Log.i(TAG, "Restarting wake-word engine (re-acquiring mic)")
            e.start()
        } else {
            Log.i(TAG, "Engine was released; rebuilding via startDetection()")
            startDetection()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted.")
            showPermissionNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!shouldRunHotwordDetection()) {
            Log.i(
                TAG,
                "Hotword detection disabled (enabled=${settings.hotwordEnabled}, " +
                    "mode=${currentVoiceWakeMode()}); stopping without allocating mic.",
            )
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = getCurrentOrFallbackNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        startDetection()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!shouldRunHotwordDetection()) {
            Log.i(
                TAG,
                "Task removed but hotword detection is not eligible to run " +
                    "(enabled=${settings.hotwordEnabled}, mode=${currentVoiceWakeMode()}, " +
                    "mic=${hasRecordAudioPermission()}); skipping restart alarm.",
            )
            return
        }
        Log.w(TAG, "Task removed. Scheduling restart.")
        val restartIntent = Intent(applicationContext, HotwordService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.setAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 3000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.release()
        engine = null
        serviceScope.cancel()
        // Unregister so the manager does not call into a destroyed service,
        // and release the KWS token so it does not believe a now-gone service
        // still owns the mic. releaseMic is a guarded no-op if an STT consumer
        // currently owns it instead.
        micOwnership.setOnMicReleasedToKws(null)
        micOwnership.releaseMic(MicOwner.KWS)
        Log.i(TAG, "Wake word detection stopped")
    }

    // --- Eligibility predicate ---

    /**
     * Read the current voice wake mode from the application's MarmaladeRuntime.
     * Falls back to Off if the application class isn't the expected type
     * (defensive; shouldn't happen at runtime).
     */
    private fun currentVoiceWakeMode(): VoiceWakeMode {
        val app = application as? MarmaladeApplication ?: return VoiceWakeMode.Off
        return app.marmaladeRuntime.voiceWakeMode.value
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Instance wrapper around the pure companion predicate. Used by both
     * onStartCommand (before mic allocation) and onTaskRemoved (before
     * arming the doze-piercing restart alarm) so the two call-sites share
     * one definition.
     */
    private fun shouldRunHotwordDetection(): Boolean =
        shouldRunHotwordDetection(
            enabled = settings.hotwordEnabled,
            mode = currentVoiceWakeMode(),
            hasMicPermission = hasRecordAudioPermission(),
        )

    // --- Wake word detection engine ---

    private fun startDetection() {
        if (engine != null) {
            Log.d(TAG, "Detection already running, skipping start")
            return
        }

        val threshold = settings.getWakeWordThreshold()
        val assetName = settings.getWakeWordAssetFilename()
        val displayName = settings.getWakeWordDisplayName()
        Log.d(TAG, "Starting wake-word pipeline (model=$assetName threshold=$threshold)")

        val models = listOf(
            WakeModel(displayName, assetName, threshold),
        )

        try {
            engine = WakeWordPipeline(
                context = this,
                models = models,
                cooldownMs = COOLDOWN_MS,
                scope = serviceScope,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WakeWordPipeline", e)
            stopSelf()
            return
        }

        // Collect detections (already SINGLE_BEST-only: the pipeline's
        // ConfirmationTracker picks the highest-scoring confirmed model
        // within a hop -- see app.marmalade.android.speech.wake.ConfirmationTracker).
        serviceScope.launch {
            engine!!.detections.collect { detection ->
                Log.i(TAG, "Wake word detected: '${detection.modelName}' (score=${detection.score})")
                onWakeWordDetected()
            }
        }

        // Claim the mic token, then start the engine only if granted.
        // KWS is granted unless an STT consumer already owns the mic — which
        // happens if voice wake is toggled on while STT is running. In that
        // case the engine is built but left stopped; restartKws() (fired by
        // the manager's release callback) starts it once STT hands the mic
        // back. Starting the AudioRecord here would collide with the STT one.
        if (micOwnership.requestMic(MicOwner.KWS)) {
            engine!!.start()
            Log.i(TAG, "Wake word detection started (wake-word pipeline)")
        } else {
            Log.i(
                TAG,
                "Engine built but not started — an STT consumer holds the mic; " +
                    "will start when it is released",
            )
        }
    }

    private fun onWakeWordDetected() {
        Log.i(TAG, "Wake word detected, triggering assistant")
        soundManager.playActivation()

        val intent = Intent(this, MarmaladeAssistantService::class.java).apply {
            action = MarmaladeAssistantService.ACTION_SHOW_ASSISTANT
        }
        startService(intent)
    }

    // --- Notification helpers ---

    /**
     * Retrieves the existing active notification (posted by NodeForegroundService)
     * instead of building a standalone one. This prevents HotwordService from
     * overwriting the combined gateway+wake-word status notification.
     * Falls back to a minimal "Voice Wake Active" notification if NodeForegroundService
     * hasn't posted yet (e.g., HotwordService restarted independently).
     * NodeForegroundService's Flow.combine will overwrite this with the full
     * combined notification when it starts.
     */
    private fun getCurrentOrFallbackNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val existing = nm.activeNotifications.find { it.id == NOTIFICATION_ID }?.notification
        if (existing != null) return existing
        return createFallbackNotification()
    }

    /**
     * Builds a minimal notification for when NodeForegroundService hasn't posted yet.
     * Shows "Voice Wake Active" instead of the old standalone "Say X to activate",
     * so the user sees correct status even during service restart races.
     * NodeForegroundService will overwrite this with the full combined notification.
     */
    private fun createFallbackNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannelManager.PERSISTENT_CHANNEL_ID)
            .setContentTitle("Marmalade")
            .setContentText("Voice Wake Active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun showPermissionNotification() {
        NotificationChannelManager.ensurePersistentChannel(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NotificationChannelManager.PERSISTENT_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_mic_permission_title))
            .setContentText(getString(R.string.notification_mic_permission_content))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID + 1, notification)
    }
}
