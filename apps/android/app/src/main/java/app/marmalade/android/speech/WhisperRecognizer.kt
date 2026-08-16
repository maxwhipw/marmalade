package app.marmalade.android.speech

import android.content.Context
import android.util.Log
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Android shell for simulated streaming STT: owns the sherpa-onnx
 * `OfflineRecognizer` (Whisper) + Silero `Vad` native lifecycle and model
 * selection (bundled assets vs downloaded filesDir models via
 * [STTModelManager]), and delegates the capture/segmentation loop to the
 * shared [WhisperStreamingEngine] over the platform mic seam.
 *
 * The sherpa objects are wrapped in the [OfflineTranscriber]/[SpeechVad]
 * facades because the Android AAR's Kotlin API and the desktop release jars'
 * Java builder API differ — shared code never touches sherpa directly
 * (proven by a desktop spike; notes kept internally).
 *
 * Mic-input preprocessing (both VAD and Whisper see the cleaned signal):
 * - `VOICE_RECOGNITION` source + hardware NoiseSuppressor/AGC live in the
 *   androidMain mic actual (`openMicCapture(hardwareEffects = true)`).
 * - [AudioPreprocessor] high-pass (software, always on) runs in the engine.
 *
 * API:
 * - [warmup] pre-loads the model
 * - [startStreaming] returns a Flow<StreamingResult>
 * - [stopStreaming] terminates recording and emits final result
 * - [release] frees all native resources
 *
 * @see StreamingResult
 */
class WhisperRecognizer private constructor(private val context: Context) : StreamingRecognizer {

    companion object {
        private const val TAG = "WhisperSTT"
        private const val SAMPLE_RATE = MIC_SAMPLE_RATE
        const val VAD_WINDOW_SIZE = WhisperStreamingEngine.VAD_WINDOW_SIZE

        // Bundled distil-small.en int8 model paths (relative to assets/).
        // The bundled default per ADR 0012 (superseded Whisper tiny 2026-07-23).
        private const val WHISPER_ENCODER = "stt/distil-small.en-encoder.int8.onnx"
        private const val WHISPER_DECODER = "stt/distil-small.en-decoder.int8.onnx"
        private const val WHISPER_TOKENS = "stt/distil-small.en-tokens.txt"

        // Silero VAD model (in assets/ root)
        private const val VAD_MODEL = "silero_vad.onnx"

        @Volatile
        private var instance: WhisperRecognizer? = null

        fun getInstance(context: Context): WhisperRecognizer {
            return instance ?: synchronized(this) {
                instance ?: WhisperRecognizer(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val settings = SettingsRepository.getInstance(context)
    private val modelManager = STTModelManager.getInstance(context)

    // Keep recognizer + VAD loaded in memory -- model loading is the expensive part (~2-3s).
    private var recognizer: OfflineRecognizer? = null
    private var loadedModelId: String? = null
    private var vad: Vad? = null

    private val engine = WhisperStreamingEngine(
        transcriberProvider = { SherpaTranscriber(getOrCreateRecognizer()) },
        vadProvider = { SherpaVad(getOrCreateVad()) },
        logError = { msg, e -> Log.e(TAG, msg, e) },
    )

    /** [OfflineTranscriber] over the AAR's Kotlin `OfflineRecognizer` API. */
    private class SherpaTranscriber(private val rec: OfflineRecognizer) : OfflineTranscriber {
        override fun transcribe(samples: FloatArray, sampleRate: Int): String {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            val text = rec.getResult(stream).text
            stream.release()
            return text
        }
    }

    /** [SpeechVad] over the AAR's Kotlin `Vad` API. */
    private class SherpaVad(private val vad: Vad) : SpeechVad {
        override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)
        override fun isSpeechDetected(): Boolean = vad.isSpeechDetected()
        override fun empty(): Boolean = vad.empty()
        override fun frontSamples(): FloatArray = vad.front().samples
        override fun pop() = vad.pop()
        override fun reset() = vad.reset()
    }

    @Synchronized
    private fun getOrCreateRecognizer(): OfflineRecognizer {
        val activeModel = modelManager.getActiveModel()
        recognizer?.let {
            if (loadedModelId == activeModel.id) return it
            // Active model changed since load (e.g. user activated Whisper Small).
            Log.i(TAG, "Active STT model changed $loadedModelId -> ${activeModel.id}, reloading")
            try { it.release() } catch (_: Exception) {}
            recognizer = null
        }

        Log.i(TAG, "Loading Whisper model '${activeModel.id}'...")
        val whisperConfig = if (activeModel.isBundled) {
            OfflineWhisperModelConfig(
                encoder = WHISPER_ENCODER,
                decoder = WHISPER_DECODER,
                language = "en",
                task = "transcribe",
                tailPaddings = 1000,
            )
        } else {
            // Downloaded model: absolute filesDir paths, loaded without assetManager.
            val dir = modelManager.getModelPath(activeModel.id)
                ?: throw IllegalStateException("Model ${activeModel.id} reported active but has no download dir")
            fun modelFile(kind: String): String {
                val name = activeModel.files.firstOrNull { it.filename.contains(kind) }?.filename
                    ?: throw IllegalStateException("Model ${activeModel.id} has no '$kind' file")
                return File(dir, name).absolutePath
            }
            OfflineWhisperModelConfig(
                encoder = modelFile("encoder"),
                decoder = modelFile("decoder"),
                language = "en",
                task = "transcribe",
                tailPaddings = 1000,
            )
        }
        val tokens = if (activeModel.isBundled) {
            WHISPER_TOKENS
        } else {
            val dir = modelManager.getModelPath(activeModel.id)!!
            val name = activeModel.files.first { it.filename.contains("tokens") }.filename
            File(dir, name).absolutePath
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = whisperConfig,
                tokens = tokens,
                numThreads = 2,
                provider = "cpu",
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        )

        val rec = if (activeModel.isBundled) {
            OfflineRecognizer(assetManager = context.assets, config = config)
        } else {
            OfflineRecognizer(config = config)
        }
        recognizer = rec
        loadedModelId = activeModel.id
        Log.i(TAG, "Whisper model '${activeModel.id}' loaded")
        return rec
    }

    @Synchronized
    private fun getOrCreateVad(): Vad {
        vad?.let { return it }

        val silenceDuration = vadSliderToSilenceDuration(settings.vadSensitivity)
        Log.i(TAG, "Creating Silero VAD (minSilenceDuration=${silenceDuration}s)")

        val vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = VAD_MODEL,
                threshold = 0.5f,
                minSilenceDuration = silenceDuration,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW_SIZE,
                maxSpeechDuration = 60.0f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        )

        val v = Vad(assetManager = context.assets, config = vadConfig)
        vad = v
        Log.i(TAG, "VAD initialized")
        return v
    }

    /**
     * Pre-load the model so first streaming session starts instantly.
     * Call from a background thread (e.g., during service onCreate).
     */
    override fun warmup() {
        getOrCreateRecognizer()
        getOrCreateVad()
    }

    /**
     * Start streaming recognition. Returns a Flow that emits the current
     * transcription as the user speaks. Cancel the Flow to stop recognition
     * and release the mic.
     *
     * Mic ownership is the caller's responsibility (MarmaladeVoiceSession /
     * InlineSTTStateHolder hold a MicOwnershipManager token); [onFlowClosed]
     * is the precise "mic released" signal the caller uses to release it.
     *
     * @param enableEndpoint If true, VAD speech-end triggers FinalText emission.
     *                       If false (patient listening), speech accumulates until
     *                       [stopStreaming] is called.
     * @param onFlowClosed Optional callback invoked when the Flow closes
     *                     (for mic ownership release coordination).
     */
    override fun startStreaming(
        enableEndpoint: Boolean,
        onFlowClosed: (() -> Unit)?,
    ): Flow<StreamingResult> = engine.startStreaming(enableEndpoint, onFlowClosed)

    /**
     * Request the streaming loop to stop and emit a final transcription.
     * The flow will complete after processing the final result.
     */
    override fun stopStreaming() {
        engine.stopStreaming()
    }

    /**
     * Release all native resources (OfflineRecognizer and Vad).
     * After calling this, [warmup] must be called again before next use.
     */
    override fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        loadedModelId = null
        try { vad?.release() } catch (_: Exception) {}
        vad = null
    }
}
