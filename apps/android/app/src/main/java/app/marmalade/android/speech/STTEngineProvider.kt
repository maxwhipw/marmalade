package app.marmalade.android.speech

import android.content.Context
import android.util.Log

/**
 * Provides the correct [StreamingRecognizer] based on the active STT model.
 *
 * Callers should use [getRecognizer] to get the current engine. The recognizer
 * may change between sessions if the user switches models in settings, so
 * consumers should call [getRecognizer] at each session start rather than
 * caching indefinitely.
 */
class STTEngineProvider private constructor(private val context: Context) {

    companion object {
        private const val TAG = "STTEngineProvider"

        @Volatile
        private var instance: STTEngineProvider? = null

        fun getInstance(context: Context): STTEngineProvider {
            return instance ?: synchronized(this) {
                instance ?: STTEngineProvider(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val modelManager = STTModelManager.getInstance(context)

    /**
     * Get the recognizer for the currently active STT model.
     *
     * Returns [WhisperRecognizer] for Whisper models (simulated streaming via
     * Silero VAD). Whisper is the only backend since the Nemotron streaming
     * transducer was retired 2026-07-04 (poor accuracy).
     */
    fun getRecognizer(): StreamingRecognizer {
        val activeModel = modelManager.getActiveModel()
        Log.d(TAG, "Active model: ${activeModel.id} (${activeModel.modelType})")

        return when (activeModel.modelType) {
            ModelType.WHISPER_OFFLINE -> WhisperRecognizer.getInstance(context)
        }
    }

    /**
     * Warmup the currently active recognizer.
     * Call from a background thread during service/activity init.
     */
    fun warmup() {
        getRecognizer().warmup()
    }

    /**
     * Stop any active streaming session.
     */
    fun stopStreaming() {
        WhisperRecognizer.getInstance(context).stopStreaming()
    }
}
