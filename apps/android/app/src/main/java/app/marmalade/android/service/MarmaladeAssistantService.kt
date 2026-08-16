package app.marmalade.android.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Voice Interaction Service
 * Launched as a system assistant with a long press of the home button
 */
class MarmaladeAssistantService : VoiceInteractionService() {

    companion object {
        private const val TAG = "MarmaladeAssistantSvc"
        private const val PENDING_SESSION_TIMEOUT_MS = 30_000L
        const val ACTION_SHOW_ASSISTANT = "app.marmalade.android.ACTION_SHOW_ASSISTANT"
    }

    private var isServiceReady = false
    private var pendingShowSession = false
    // Guards against double-unregister: VoiceInteractionService.onShutdown is
    // not guaranteed to fire on every Service teardown (e.g. system kill),
    // so we also clean up in onDestroy. Either path is fine, but registering
    // twice would throw IntentReceiverLeaked.
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private val pendingSessionTimeoutRunnable = Runnable {
        if (pendingShowSession) {
            Log.w(TAG, "Pending showSession timed out. Clearing.")
            pendingShowSession = false
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Assistant trigger receiver fired: ${intent?.action}")
            if (intent?.action == ACTION_SHOW_ASSISTANT) {
                triggerShowSession()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VoiceInteractionService onCreate")
        val filter = IntentFilter(ACTION_SHOW_ASSISTANT)
        // NOT_EXPORTED: only same-app broadcasts (InlineSTTState.triggerVoicePopup,
        // package-scoped) may trigger a show — an exported receiver would let any
        // app barge in and cancel an in-flight voice turn. The wake path
        // (HotwordService) uses startService, not this receiver.
        ContextCompat.registerReceiver(this, debugReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterDebugReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(debugReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered (e.g. by an earlier onShutdown). Idempotent.
            Log.d(TAG, "debugReceiver already unregistered: ${e.message}")
        }
        receiverRegistered = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            Log.d(TAG, "onStartCommand received: null (system restart)")
            return START_STICKY
        }

        val action = intent.action
        Log.i(TAG, "onStartCommand received: $action")
        if (action == ACTION_SHOW_ASSISTANT) {
            triggerShowSession()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        Log.d(TAG, "onBind received: ${intent?.action}")
        return super.onBind(intent)
    }

    private fun triggerShowSession() {
        val compName = ComponentName(this, MarmaladeAssistantService::class.java)
        val isActive = isActiveService(this, compName)
        Log.d(TAG, "triggerShowSession: isServiceReady=$isServiceReady, isActiveService=$isActive")

        if (isServiceReady) {
            try {
                val args = Bundle()
                showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                Log.d(TAG, "showSession() called immediately")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call showSession immediately", e)
            }
        } else {
            Log.d(TAG, "Service not ready. Queuing showSession request.")
            pendingShowSession = true
            handler.removeCallbacks(pendingSessionTimeoutRunnable)
            handler.postDelayed(pendingSessionTimeoutRunnable, PENDING_SESSION_TIMEOUT_MS)
        }
    }

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "VoiceInteractionService onReady")
        isServiceReady = true
        if (pendingShowSession) {
            pendingShowSession = false
            handler.removeCallbacks(pendingSessionTimeoutRunnable)
            try {
                val args = Bundle()
                showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                Log.d(TAG, "showSession() called from onReady (pending)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call pending showSession", e)
            }
        }
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "VoiceInteractionService onShutdown")
        isServiceReady = false
        pendingShowSession = false
        handler.removeCallbacks(pendingSessionTimeoutRunnable)
        unregisterDebugReceiver()
    }

    override fun onDestroy() {
        // VoiceInteractionService.onShutdown is not guaranteed on every
        // Service teardown (the system may kill the process directly); the
        // base Service.onDestroy is. Mirror the cleanup here so the
        // BroadcastReceiver never leaks (IntentReceiverLeaked).
        unregisterDebugReceiver()
        handler.removeCallbacks(pendingSessionTimeoutRunnable)
        super.onDestroy()
    }
}
