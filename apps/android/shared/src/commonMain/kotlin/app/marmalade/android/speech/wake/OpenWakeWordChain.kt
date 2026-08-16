package app.marmalade.android.speech.wake

/**
 * Orchestrates the openWakeWord mel -> embedding -> classifier chain for one
 * audio hop, given raw PCM16 samples. The three inference stages are
 * constructor-injected as plain functions so this class is unit-testable
 * with fake tensors — no ONNX runtime, no Android — while the real
 * `OrtSession` wiring ([SileroVad] neighbor: see [WakeWordPipeline]) stays a
 * thin adapter that satisfies these function types.
 *
 * Architecture (window sizes, hop-to-mel-frame ratio, mel scaling) ported
 * from `dscripka/openWakeWord` (Apache-2.0) — see CREDITS.md. Tensor shapes
 * verified against the bundled `.onnx` files, not assumed; see
 * [MelWindowBuffer]'s doc comment for the confirmed shapes.
 *
 * @param models the configured wake-word classifiers to score each hop.
 * @param melInfer raw PCM16 hop samples (as floats, unnormalized) ->
 *   newly produced mel frames for that hop (each length 32), oldest first.
 * @param embedInfer a flattened 76x32 mel window -> a 96-dim embedding vector.
 * @param classifierInfer model asset filename + a flattened 16x96 embedding
 *   window -> raw sigmoid score for that model.
 */
class OpenWakeWordChain(
    private val models: List<WakeModel>,
    private val melInfer: (FloatArray) -> List<FloatArray>,
    private val embedInfer: (FloatArray) -> FloatArray,
    private val classifierInfer: (assetFilename: String, window: FloatArray) -> Float,
    private val windowBuffer: MelWindowBuffer = MelWindowBuffer(),
) {
    /**
     * openWakeWord's published mel-spectrogram scaling: the raw ONNX
     * melspectrogram output is transformed to better match the reference
     * TensorFlow implementation (`x / 10 + 2`). Applied per mel value before
     * the frame is buffered.
     */
    private fun scaleMelFrame(raw: FloatArray): FloatArray =
        FloatArray(raw.size) { raw[it] / 10f + 2f }

    /**
     * Runs one hop: mel -> (buffer) -> embedding windows -> (buffer) ->
     * classifier window -> per-model scores. Returns a score per model keyed
     * by asset filename; a model is absent from the map if the classifier
     * window isn't warmed up yet (fewer than 16 embeddings accumulated).
     *
     * Always runs mel + embedding + classifier — callers that gate the
     * expensive stages on VAD state should use [pushMelOnly] /
     * [backfillOnGateOpen] instead; see those methods' doc comments.
     */
    fun processHop(pcmHop: FloatArray): Map<String, Float> {
        val scaledFrames = pushMelOnly(pcmHop)

        val embeddingWindows = windowBuffer.pendingEmbeddingWindows(scaledFrames.size)
        for (window in embeddingWindows) {
            windowBuffer.pushEmbedding(embedInfer(window))
        }

        return classifyCurrentWindow()
    }

    /**
     * Runs only the mel stage for one hop and buffers the resulting frames.
     * This is the cheap stage that must run on every hop, gate-open or
     * closed, so [MelWindowBuffer]'s mel history stays continuous — see
     * [backfillOnGateOpen] for why. Returns the scaled mel frames produced,
     * for callers that need the frame count (e.g. to feed
     * [MelWindowBuffer.pendingEmbeddingWindows] on the open path).
     */
    fun pushMelOnly(pcmHop: FloatArray): List<FloatArray> {
        val rawMelFrames = melInfer(pcmHop)
        val scaledFrames = rawMelFrames.map(::scaleMelFrame)
        windowBuffer.pushMelFrames(scaledFrames)
        return scaledFrames
    }

    /** Steady-state incremental step for a hop while the VAD gate is open (mel already pushed via [pushMelOnly]). */
    fun advanceEmbeddingsAndClassify(newMelFrameCount: Int): Map<String, Float> {
        val embeddingWindows = windowBuffer.pendingEmbeddingWindows(newMelFrameCount)
        for (window in embeddingWindows) {
            windowBuffer.pushEmbedding(embedInfer(window))
        }
        return classifyCurrentWindow()
    }

    /**
     * Called once on the VAD closed->open edge (mel already pushed via
     * [pushMelOnly] for this hop). While the gate was closed, embeddings were
     * never advanced (and were cleared on the open->closed edge — see
     * [clearEmbeddingsOnGateClose]), but the mel buffer stayed warm. Rather
     * than wait ~1.2s for the incremental path to refill 16 embeddings from
     * scratch (added detection latency right at wake-word onset), backfill
     * all 16 classifier-window embeddings directly from the warm mel history
     * in one burst and classify immediately — zero added latency, at the
     * price of a 16-inference burst once per speech onset instead of spread
     * across silence.
     */
    fun backfillOnGateOpen(): Map<String, Float> {
        val windows = windowBuffer.backfillEmbeddingWindows()
        for (window in windows) {
            windowBuffer.pushEmbedding(embedInfer(window))
        }
        return classifyCurrentWindow()
    }

    /**
     * Called once on the VAD open->closed edge. Embeddings must not persist
     * across a silence gap: the next open edge backfills fresh ones from mel
     * history, so stale embeddings left in the buffer would otherwise get
     * mixed with them by [MelWindowBuffer.classifierWindow]'s "most recent 16"
     * slice.
     */
    fun clearEmbeddingsOnGateClose() = windowBuffer.clearEmbeddings()

    private fun classifyCurrentWindow(): Map<String, Float> {
        val classifierWindow = windowBuffer.classifierWindow() ?: return emptyMap()
        return models.associate { model ->
            model.assetFilename to classifierInfer(model.assetFilename, classifierWindow)
        }
    }

    fun reset() = windowBuffer.reset()
}
