package app.marmalade.android.service

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

/**
 * Minimal RecognitionService required for VoiceInteractionService to function correctly.
 */
class MarmaladeAssistantRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "MarmaladeAssistantRec"
    }

    override fun onStartListening(intent: Intent?, listener: RecognitionService.Callback?) {
        Log.d(TAG, "onStartListening")
        // No-op: Actual recognition is handled in MarmaladeVoiceSession
    }

    override fun onCancel(listener: RecognitionService.Callback?) {
        Log.d(TAG, "onCancel")
    }

    override fun onStopListening(listener: RecognitionService.Callback?) {
        Log.d(TAG, "onStopListening")
    }
}
