package app.marmalade.android.speech

import android.content.Context
import android.util.Log
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Type of recognizer backend a model uses.
 */
enum class ModelType {
    /** Offline (batch) Whisper model -- uses OfflineRecognizer with Silero VAD simulated streaming. */
    WHISPER_OFFLINE,
}

/**
 * A file that is part of an STT model.
 */
data class ModelFile(
    val filename: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * An STT model definition with metadata and file inventory.
 */
data class STTModel(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeBytes: Long,
    val isBundled: Boolean,
    val modelType: ModelType,
    val files: List<ModelFile>,
)

/**
 * Status of an STT model on this device.
 */
sealed class ModelStatus {
    /** Model is the currently active STT engine. */
    object Active : ModelStatus()
    /** Model files are downloaded but not currently active. */
    object Downloaded : ModelStatus()
    /** Model is not downloaded (needs download before use). */
    object NotDownloaded : ModelStatus()
    /** Model is currently being downloaded. */
    data class Downloading(val progress: Float) : ModelStatus()
    /** Download or activation failed. */
    data class Error(val message: String) : ModelStatus()
}

/**
 * Manages the STT model inventory, status tracking, and download/activate/delete operations.
 *
 * One model ships today: **Distil-Whisper Small** (distil-small.en), bundled in
 * APK assets as the default engine (ADR 0012, superseding 0005). It is smaller
 * than the old Whisper Small download (~298MB vs ~374MB), more accurate than the
 * previous Whisper Tiny default, and distilled on documented open data (ethics
 * "clear"). Bundled models use asset paths and cannot be deleted.
 *
 * The download machinery (files/progress/delete below) is retained for future
 * downloadable tiers, though none ship now — distil-medium is server-side only
 * (too big for mobile). Retired engines whose files may linger in app storage
 * are reclaimed on first init: Nemotron 0.6B (removed 2026-07-04, poor accuracy)
 * and the Whisper Small download (superseded 2026-07-23 by the bundled distil).
 */
class STTModelManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "STTModelManager"
        private const val MODELS_DIR = "models"

        /** The bundled default STT model id (ADR 0012, superseded Whisper tiny). */
        const val DEFAULT_MODEL_ID = "distil_small_en"

        /**
         * Static model definitions accessible without Context for pure-logic tests.
         */
        val MODEL_DEFINITIONS: List<STTModel> = listOf(
            STTModel(
                id = DEFAULT_MODEL_ID,
                displayName = "Distil-Whisper Small",
                description = "Bundled — distilled English Whisper, open training data",
                sizeBytes = 298_040_528L, // actual assets/stt/ total (encoder+decoder+tokens)
                isBundled = true,
                modelType = ModelType.WHISPER_OFFLINE,
                files = emptyList(), // Bundled in assets/stt/, no download needed
            ),
        )

        /**
         * Find a model by its ID from the static definitions.
         */
        fun findModelById(id: String): STTModel? {
            return MODEL_DEFINITIONS.firstOrNull { it.id == id }
        }

        @Volatile
        private var instance: STTModelManager? = null

        fun getInstance(context: Context): STTModelManager {
            return instance ?: synchronized(this) {
                instance ?: STTModelManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val settings = SettingsRepository.getInstance(context)

    init {
        // One-time cleanup: reclaim downloaded files of retired engines no longer
        // in MODEL_DEFINITIONS (no UI would otherwise remove them). Nemotron
        // (removed 2026-07-04, ~663MB) and the Whisper Small download (superseded
        // 2026-07-23 by the bundled distil model, ~374MB).
        for (retiredId in listOf("nemotron", "whisper_small")) {
            val dir = File(context.filesDir, "$MODELS_DIR/$retiredId")
            if (dir.exists() && dir.deleteRecursively()) {
                Log.i(TAG, "Removed orphaned model files for retired engine '$retiredId'")
            }
        }
        // A saved active-model id from a retired/renamed engine self-heals via
        // getActiveModel's fallback; stamp it now so the setting reflects reality.
        if (findModelById(settings.activeSTTModel) == null) {
            settings.activeSTTModel = DEFAULT_MODEL_ID
        }
    }

    /** All available STT models. */
    val models: List<STTModel> get() = MODEL_DEFINITIONS

    /** Download progress per model ID (0.0 to 1.0). Empty when no downloads active. */
    private val _downloadProgressFlow = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgressFlow: StateFlow<Map<String, Float>> = _downloadProgressFlow.asStateFlow()

    /** Error messages per model ID. Cleared when download starts or model is deleted. */
    private val _downloadErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloadErrors: StateFlow<Map<String, String>> = _downloadErrors.asStateFlow()

    /**
     * Get the status of a model on this device.
     */
    fun getModelStatus(modelId: String): ModelStatus {
        val model = findModelById(modelId) ?: return ModelStatus.Error("Unknown model: $modelId")

        // Check if download error
        val error = _downloadErrors.value[modelId]
        if (error != null) return ModelStatus.Error(error)

        // Check if download in progress
        val progress = _downloadProgressFlow.value[modelId]
        if (progress != null) return ModelStatus.Downloading(progress)

        val activeModel = settings.activeSTTModel

        return when {
            model.isBundled && modelId == activeModel -> ModelStatus.Active
            model.isBundled -> ModelStatus.Downloaded // Bundled is always available
            isModelDownloaded(modelId) && modelId == activeModel -> ModelStatus.Active
            isModelDownloaded(modelId) -> ModelStatus.Downloaded
            else -> ModelStatus.NotDownloaded
        }
    }

    /**
     * Get the filesDir path for a downloaded model, or null for bundled models.
     */
    fun getModelPath(modelId: String): String? {
        val model = findModelById(modelId) ?: return null
        if (model.isBundled) return null
        val dir = File(context.filesDir, "$MODELS_DIR/$modelId")
        return if (dir.exists()) dir.absolutePath else null
    }

    /**
     * Check if all files for a downloadable model exist in filesDir/models/{modelId}/.
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val model = findModelById(modelId) ?: return false
        if (model.isBundled) return true // Always available

        val dir = File(context.filesDir, "$MODELS_DIR/$modelId")
        if (!dir.exists()) return false

        return model.files.all { modelFile ->
            File(dir, modelFile.filename).exists()
        }
    }

    /**
     * Delete a downloaded model's files. Cannot delete bundled model.
     * If the deleted model was active, reverts to the bundled default.
     */
    fun deleteModel(modelId: String) {
        val model = findModelById(modelId) ?: return
        if (model.isBundled) {
            Log.w(TAG, "Cannot delete bundled model: $modelId")
            return
        }

        val dir = File(context.filesDir, "$MODELS_DIR/$modelId")
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "Deleted model files: $modelId")
        }

        // If this was the active model, revert to bundled default
        if (settings.activeSTTModel == modelId) {
            settings.activeSTTModel = DEFAULT_MODEL_ID
            Log.i(TAG, "Active model reverted to $DEFAULT_MODEL_ID")
        }
    }

    /**
     * Activate a model (set as the active STT engine).
     * Model must be bundled or downloaded.
     *
     * @throws IllegalArgumentException if model doesn't exist or isn't downloaded
     */
    fun activateModel(modelId: String) {
        val model = findModelById(modelId)
            ?: throw IllegalArgumentException("Unknown model: $modelId")

        if (!model.isBundled && !isModelDownloaded(modelId)) {
            throw IllegalArgumentException("Model $modelId is not downloaded")
        }

        settings.activeSTTModel = modelId
        Log.i(TAG, "Activated model: $modelId (${model.displayName})")
    }

    /**
     * Get the currently active STT model.
     * Falls back to the bundled default if the active model is no longer available.
     */
    fun getActiveModel(): STTModel {
        val activeId = settings.activeSTTModel
        val model = findModelById(activeId)

        // Fallback if active model is unknown or not available
        if (model == null || (!model.isBundled && !isModelDownloaded(activeId))) {
            settings.activeSTTModel = DEFAULT_MODEL_ID
            return MODEL_DEFINITIONS.first { it.id == DEFAULT_MODEL_ID }
        }

        return model
    }

    /**
     * Update download progress for a model (called by download service).
     */
    fun updateDownloadProgress(modelId: String, progress: Float) {
        // Clear any previous error when download starts/progresses
        if (progress < 1.0f) {
            val errors = _downloadErrors.value.toMutableMap()
            errors.remove(modelId)
            _downloadErrors.value = errors
        }

        val current = _downloadProgressFlow.value.toMutableMap()
        if (progress >= 1.0f) {
            current.remove(modelId)
        } else {
            current[modelId] = progress
        }
        _downloadProgressFlow.value = current
    }

    /**
     * Report a download error for a model (called by download service on failure).
     */
    fun reportDownloadError(modelId: String, message: String) {
        // Remove progress entry
        val progress = _downloadProgressFlow.value.toMutableMap()
        progress.remove(modelId)
        _downloadProgressFlow.value = progress

        // Set error
        val errors = _downloadErrors.value.toMutableMap()
        errors[modelId] = message
        _downloadErrors.value = errors

        Log.e(TAG, "Download error for $modelId: $message")
    }

    /**
     * Clear a download error (called when retrying).
     */
    fun clearDownloadError(modelId: String) {
        val errors = _downloadErrors.value.toMutableMap()
        errors.remove(modelId)
        _downloadErrors.value = errors
    }
}
