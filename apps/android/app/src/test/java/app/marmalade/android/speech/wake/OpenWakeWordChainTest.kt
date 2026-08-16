package app.marmalade.android.speech.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWakeWordChainTest {

    private val model = WakeModel("Marmalade", "marmalade.onnx", threshold = 0.5f)

    /** Fake mel inference: returns [framesPerHop] frames, each filled with [melValue]. */
    private fun fakeMelInfer(framesPerHop: Int, melValue: Float): (FloatArray) -> List<FloatArray> =
        { _ -> List(framesPerHop) { FloatArray(32) { melValue } } }

    @Test
    fun `no classifier score until warm-up completes`() {
        val embedCalls = mutableListOf<FloatArray>()
        val chain = OpenWakeWordChain(
            models = listOf(model),
            melInfer = fakeMelInfer(framesPerHop = 8, melValue = 1f),
            embedInfer = { window -> embedCalls.add(window); FloatArray(96) { 0.5f } },
            classifierInfer = { _, _ -> 0.9f },
        )

        // Fewer than 76 mel frames after 1 hop (8 < 76) -> no embedding window yet -> empty scores.
        val scores = chain.processHop(FloatArray(1280))
        assertTrue(scores.isEmpty())
        assertTrue(embedCalls.isEmpty())
    }

    @Test
    fun `classifier runs once warm-up crosses 76 mel frames and 16 embeddings`() {
        val chain = OpenWakeWordChain(
            models = listOf(model),
            melInfer = fakeMelInfer(framesPerHop = 8, melValue = 1f),
            embedInfer = { FloatArray(96) { 0.5f } },
            classifierInfer = { _, _ -> 0.77f },
        )

        // 10 hops * 8 frames/hop = 80 mel frames (crosses 76 on hop 10), producing embedding windows
        // starting hop 10. Need 16 embeddings for the classifier -> hops 10..25 (16 more windows).
        var lastScores: Map<String, Float> = emptyMap()
        repeat(25) {
            lastScores = chain.processHop(FloatArray(1280))
        }

        assertEquals(0.77f, lastScores.getValue("marmalade.onnx"), 0.0001f)
    }

    @Test
    fun `mel scaling divides by 10 and adds 2 before windowing`() {
        // Verify the openWakeWord mel_transform (x/10 + 2) by checking the embedding
        // function receives the scaled values, not raw mel output.
        var capturedWindow: FloatArray? = null
        val chain = OpenWakeWordChain(
            models = listOf(model),
            melInfer = fakeMelInfer(framesPerHop = 76, melValue = 100f), // raw mel value 100
            embedInfer = { window -> capturedWindow = window; FloatArray(96) },
            classifierInfer = { _, _ -> 0f },
        )

        chain.processHop(FloatArray(1280))

        assertTrue(capturedWindow != null)
        // 100 / 10 + 2 = 12
        assertEquals(12f, capturedWindow!![0], 0.0001f)
    }

    @Test
    fun `each configured model gets its own classifier score`() {
        val modelA = WakeModel("Marmalade", "marmalade.onnx", threshold = 0.5f)
        val modelB = WakeModel("OpenClaw", "openclaw.onnx", threshold = 0.5f)
        val chain = OpenWakeWordChain(
            models = listOf(modelA, modelB),
            melInfer = fakeMelInfer(framesPerHop = 8, melValue = 1f),
            embedInfer = { FloatArray(96) },
            classifierInfer = { assetFilename, _ ->
                if (assetFilename == "marmalade.onnx") 0.3f else 0.8f
            },
        )

        var lastScores: Map<String, Float> = emptyMap()
        repeat(25) { lastScores = chain.processHop(FloatArray(1280)) }

        assertEquals(0.3f, lastScores.getValue("marmalade.onnx"), 0.0001f)
        assertEquals(0.8f, lastScores.getValue("openclaw.onnx"), 0.0001f)
    }

    @Test
    fun `reset clears warm-up state so classifier goes quiet again`() {
        val chain = OpenWakeWordChain(
            models = listOf(model),
            melInfer = fakeMelInfer(framesPerHop = 8, melValue = 1f),
            embedInfer = { FloatArray(96) },
            classifierInfer = { _, _ -> 0.9f },
        )

        repeat(25) { chain.processHop(FloatArray(1280)) }
        chain.reset()

        val scoresAfterReset = chain.processHop(FloatArray(1280))
        assertTrue(scoresAfterReset.isEmpty())
    }

    // --- Gate-edge API (VAD gate integration; see WakeWordPipeline.runHopLoop) ---

    @Test
    fun `open-edge backfill classifies on the very first open hop given a warm mel buffer`() {
        var embedCalls = 0
        val chain = OpenWakeWordChain(
            models = listOf(model),
            melInfer = fakeMelInfer(framesPerHop = 8, melValue = 1f),
            embedInfer = { embedCalls++; FloatArray(96) { 0.5f } },
            classifierInfer = { _, _ -> 0.88f },
        )

        // Warm the mel buffer to 196 frames (76 + 15*8) purely via pushMelOnly,
        // as the pipeline does while the gate is closed -- no embeddings yet.
        repeat(25) { chain.pushMelOnly(FloatArray(1280)) } // 25*8 = 200 >= 196 frames

        // Closed -> open edge: backfill should classify immediately, in one call.
        val scores = chain.backfillOnGateOpen()

        assertEquals(16, embedCalls) // one backfill burst of 16 embedding inferences
        assertEquals(0.88f, scores.getValue("marmalade.onnx"), 0.0001f)
    }

    @Test
    fun `embeddings cleared on close edge so a later open edge backfills from scratch`() {
        var embedCalls = 0
        val chain = OpenWakeWordChain(
            models = listOf(model),
            melInfer = fakeMelInfer(framesPerHop = 8, melValue = 1f),
            embedInfer = { embedCalls++; FloatArray(96) { 0.5f } },
            classifierInfer = { _, _ -> 0.5f },
        )

        repeat(25) { chain.pushMelOnly(FloatArray(1280)) }
        chain.backfillOnGateOpen()
        assertEquals(16, embedCalls)

        // Gate closes: embeddings are dropped (mel history is untouched).
        chain.clearEmbeddingsOnGateClose()

        // Gate re-opens without any new mel frames pushed in between (buffer
        // is still 200 frames >= 196 needed) -- backfill runs again from mel
        // history, producing a fresh burst rather than reusing stale embeddings.
        val scores = chain.backfillOnGateOpen()

        assertEquals(32, embedCalls) // second full 16-window backfill burst
        assertEquals(0.5f, scores.getValue("marmalade.onnx"), 0.0001f)
    }

    @Test
    fun `steady-state open path after backfill uses the incremental one-window-per-hop step`() {
        var embedCalls = 0
        val chain = OpenWakeWordChain(
            models = listOf(model),
            melInfer = fakeMelInfer(framesPerHop = 8, melValue = 1f),
            embedInfer = { embedCalls++; FloatArray(96) { 0.5f } },
            classifierInfer = { _, _ -> 0.5f },
        )

        repeat(25) { chain.pushMelOnly(FloatArray(1280)) }
        chain.backfillOnGateOpen()
        assertEquals(16, embedCalls)

        // Next hop, gate still open: mel pushed, then the incremental step.
        val newFrames = chain.pushMelOnly(FloatArray(1280))
        chain.advanceEmbeddingsAndClassify(newFrames.size)

        assertEquals(17, embedCalls) // exactly one more embedding inference
    }
}
