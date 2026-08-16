package app.marmalade.android.speech

import kotlinx.coroutines.flow.Flow

/**
 * Common interface for STT engines that produce a Flow of [StreamingResult].
 *
 * Implementations:
 * - [WhisperRecognizer] — Offline Whisper + Silero VAD simulated streaming
 *   (the only backend since the Nemotron transducer was retired 2026-07-04)
 */
interface StreamingRecognizer {

    /**
     * Pre-load model so the first streaming session starts instantly.
     * Call from a background thread.
     */
    fun warmup()

    /**
     * Start streaming recognition.
     *
     * @param enableEndpoint If true, endpoint detection (VAD or model-native)
     *                       triggers [StreamingResult.FinalText] emission.
     *                       If false (patient listening), text accumulates
     *                       until [stopStreaming].
     * @param onFlowClosed   Optional callback when the Flow closes
     *                       (for mic ownership release coordination).
     */
    fun startStreaming(
        enableEndpoint: Boolean = true,
        onFlowClosed: (() -> Unit)? = null,
    ): Flow<StreamingResult>

    /** Request the streaming loop to stop and emit a final result. */
    fun stopStreaming()

    /** Release all native resources. After this, [warmup] must be called again. */
    fun release()
}
