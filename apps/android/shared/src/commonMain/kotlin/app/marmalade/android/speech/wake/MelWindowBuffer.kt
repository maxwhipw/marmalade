package app.marmalade.android.speech.wake

/**
 * Ring buffer + windowing math for the openWakeWord mel -> embedding stage,
 * ported (architecture, not code) from `dscripka/openWakeWord`'s
 * `AudioFeatures._streaming_features` / `_get_embeddings` (Apache-2.0;
 * see CREDITS.md). Kept separate from ONNX plumbing so hop-alignment and
 * boundary behavior are unit-testable with plain float arrays.
 *
 * Verified against the bundled `.onnx` files (not trusted from the spec —
 * see the pipeline replacement commit message for the JVM onnxruntime check
 * used to confirm these):
 * - `melspectrogram.onnx`: input `[batch, samples]`, output `[time, 1, T, 32]`
 *   (32 mel bins/frame).
 * - `embedding_model.onnx`: input `[batch, 76, 32, 1]`, output `[batch, 1, 1, 96]`
 *   (96-dim embedding).
 * - classifier `.onnx` (marmalade/openclaw): input `[1, 16, 96]`, output `[1, 1]`.
 *
 * Per upstream openWakeWord, one 80ms/1280-sample audio hop yields exactly
 * 8 new mel frames (~97 mel-frames/sec model rate), so in steady state each
 * hop advances the 76-frame embedding window by 8 frames and produces
 * exactly one new 96-dim embedding vector; the classifier then runs on the
 * most recent 16 accumulated embeddings, once per hop.
 */
class MelWindowBuffer(
    private val melFrameSize: Int = MEL_FRAME_SIZE,
    private val embeddingWindowSize: Int = EMBEDDING_WINDOW_SIZE,
    private val embeddingStep: Int = EMBEDDING_STEP,
    private val classifierWindowSize: Int = CLASSIFIER_WINDOW_SIZE,
) {
    companion object {
        const val MEL_FRAME_SIZE = 32
        const val EMBEDDING_WINDOW_SIZE = 76
        const val EMBEDDING_STEP = 8
        const val CLASSIFIER_WINDOW_SIZE = 16
        const val HOP_SAMPLES = 1280

        /** Mel-frame buffer cap; matches upstream's 10s rolling window (10 * 97 frames). */
        const val MEL_BUFFER_MAX_FRAMES = 970

        /** Embedding-vector buffer cap; matches upstream's feature_buffer_max_len. */
        const val EMBEDDING_BUFFER_MAX_FRAMES = 120
    }

    // Rows of melFrameSize floats each, oldest first.
    private val melFrames = ArrayDeque<FloatArray>()

    // Rows of embedding-dim floats each, oldest first.
    private val embeddings = ArrayDeque<FloatArray>()

    /** Appends one hop's worth of new mel frames (each of length [melFrameSize]). */
    fun pushMelFrames(frames: List<FloatArray>) {
        for (frame in frames) {
            require(frame.size == melFrameSize) {
                "mel frame size ${frame.size} != expected $melFrameSize"
            }
            melFrames.addLast(frame)
        }
        while (melFrames.size > MEL_BUFFER_MAX_FRAMES) melFrames.removeFirst()
    }

    /**
     * Returns the sliding 76-frame embedding-input windows that became
     * available since the last call for [newFrameCount] newly pushed mel
     * frames, oldest first. Each window is flattened row-major
     * (76 * 32 floats) ready for the `[1, 76, 32, 1]` embedding model input.
     *
     * Mirrors upstream: for a hop that added N mel frames, windows end at
     * offsets stepped by [embeddingStep] counting back from the buffer's
     * current end, skipping any window that doesn't yet have a full
     * [embeddingWindowSize] of history (buffer warm-up).
     */
    fun pendingEmbeddingWindows(newFrameCount: Int): List<FloatArray> {
        if (newFrameCount <= 0) return emptyList()
        val totalFrames = melFrames.size
        val hopWindowCount = newFrameCount / embeddingStep
        if (hopWindowCount == 0) return emptyList()

        val windows = mutableListOf<FloatArray>()
        // Oldest-to-newest window end offsets, mirroring upstream's
        // `for i in arange(hopWindowCount-1, -1, -1): ndx = -8*i` (reversed for
        // oldest-first emission).
        for (i in (hopWindowCount - 1) downTo 0) {
            val endExclusive = totalFrames - embeddingStep * i
            val startInclusive = endExclusive - embeddingWindowSize
            if (startInclusive < 0) continue // not enough history yet (warm-up)
            windows.add(flattenWindow(startInclusive, endExclusive))
        }
        return windows
    }

    private fun flattenWindow(startInclusive: Int, endExclusive: Int): FloatArray {
        val out = FloatArray((endExclusive - startInclusive) * melFrameSize)
        var idx = 0
        for (row in startInclusive until endExclusive) {
            val frame = melFrames.elementAt(row)
            frame.copyInto(out, destinationOffset = idx, startIndex = 0, endIndex = melFrameSize)
            idx += melFrameSize
        }
        return out
    }

    /** Appends one new embedding vector (length = embedding model's output dim). */
    fun pushEmbedding(embedding: FloatArray) {
        embeddings.addLast(embedding)
        while (embeddings.size > EMBEDDING_BUFFER_MAX_FRAMES) embeddings.removeFirst()
    }

    /** Drops all buffered embeddings without touching the mel-frame history. */
    fun clearEmbeddings() {
        embeddings.clear()
    }

    /**
     * Computes the last [classifierWindowSize] embedding-input windows
     * directly from the (always-warm) mel buffer, for backfilling embeddings
     * on a VAD closed->open edge — see `WakeWordPipeline`/`OpenWakeWordChain`
     * doc comments for why. Oldest first, so callers can push them straight
     * into [pushEmbedding] in order and end up with the same buffer state as
     * if the incremental [pendingEmbeddingWindows] path had produced them.
     *
     * Window ends are stepped back by [embeddingStep] from the current mel
     * buffer end, for [classifierWindowSize] windows total: the newest window
     * ends at the buffer end, the next-newest [embeddingStep] frames earlier,
     * and so on. Needs
     * `embeddingWindowSize + (classifierWindowSize - 1) * embeddingStep`
     * (76 + 15*8 = 196) mel frames of history for a full backfill; during
     * cold warm-up (fewer frames buffered) windows that don't yet have a full
     * [embeddingWindowSize] of history are skipped, same as
     * [pendingEmbeddingWindows].
     */
    fun backfillEmbeddingWindows(): List<FloatArray> {
        val totalFrames = melFrames.size
        val windows = mutableListOf<FloatArray>()
        for (i in (classifierWindowSize - 1) downTo 0) {
            val endExclusive = totalFrames - embeddingStep * i
            val startInclusive = endExclusive - embeddingWindowSize
            if (startInclusive < 0) continue // not enough history yet (warm-up)
            windows.add(flattenWindow(startInclusive, endExclusive))
        }
        return windows
    }

    /**
     * Returns the most recent [classifierWindowSize] embedding vectors,
     * flattened row-major for the `[1, 16, 96]` classifier input, or null if
     * fewer than [classifierWindowSize] have been accumulated yet (warm-up).
     */
    fun classifierWindow(): FloatArray? {
        if (embeddings.size < classifierWindowSize) return null
        val embeddingDim = embeddings.last().size
        val out = FloatArray(classifierWindowSize * embeddingDim)
        var idx = 0
        for (row in (embeddings.size - classifierWindowSize) until embeddings.size) {
            val vec = embeddings.elementAt(row)
            vec.copyInto(out, destinationOffset = idx, startIndex = 0, endIndex = embeddingDim)
            idx += embeddingDim
        }
        return out
    }

    fun reset() {
        melFrames.clear()
        embeddings.clear()
    }
}
