package app.marmalade.android.speech.wake

/**
 * A single wake-word model to run in the pipeline: which classifier `.onnx`
 * asset to load, what it should be called in detections, and the score
 * threshold that counts as a hit for one hop.
 *
 * Mirrors the shape of the retired `xyz.rementia.openwakeword.lib.model.WakeWordModel`
 * so [app.marmalade.android.service.HotwordService]'s construction site changes
 * minimally.
 */
data class WakeModel(
    val displayName: String,
    val assetFilename: String,
    val threshold: Float,
)

/**
 * A confirmed wake-word detection, emitted only after multi-hop confirmation
 * (see [ConfirmationTracker]) and cooldown have both passed.
 */
data class WakeDetection(
    val modelName: String,
    val score: Float,
)
