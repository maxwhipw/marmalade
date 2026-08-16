package app.marmalade.android.speech.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Thin wrapper over `silero_vad.onnx` (already bundled in assets, already
 * used for STT VAD in [app.marmalade.android.speech.WhisperRecognizer] via
 * sherpa-onnx's `Vad` class). This pipeline talks to the same asset directly
 * through onnxruntime because it needs per-hop probabilities decoupled from
 * sherpa's streaming segment abstraction.
 *
 * The bundled model is the stateful dual-tensor (`h`/`c`) Silero VAD variant
 * — confirmed by inspecting the model's input/output signature with a
 * throwaway onnxruntime JVM check (not the newer combined-`state`-tensor
 * variant). Input `x` is `[1, 512]` (512 samples = 32ms at 16kHz, matching
 * `WhisperRecognizer.VAD_WINDOW_SIZE`); `h`/`c` are `[2, 1, 64]` recurrent
 * state, zero-initialized and fed back from `new_h`/`new_c` each call.
 * Output `prob` is `[1, 1]` and is already a probability (no sigmoid needed).
 *
 * Session is created once in the constructor and closed in [close] — unlike
 * the retired rementia AAR's mel/embedding stages, which recreated an
 * `OrtSession` from the asset bytes on every 80ms chunk
 * (wake-word audit, kept internally).
 */
class SileroVad(assetBytes: ByteArray) : AutoCloseable {

    companion object {
        const val WINDOW_SIZE_SAMPLES = 512
        private const val STATE_DIM = 64
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(assetBytes, OrtSession.SessionOptions())

    private var h = FloatArray(2 * 1 * STATE_DIM)
    private var c = FloatArray(2 * 1 * STATE_DIM)

    /**
     * Runs one 512-sample window through the VAD and returns the speech
     * probability, updating recurrent state for the next call.
     */
    @Suppress("UNCHECKED_CAST")
    fun speechProbability(window: FloatArray): Float {
        require(window.size == WINDOW_SIZE_SAMPLES) {
            "Silero VAD window size ${window.size} != expected $WINDOW_SIZE_SAMPLES"
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, WINDOW_SIZE_SAMPLES.toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, STATE_DIM.toLong())).use { hTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, STATE_DIM.toLong())).use { cTensor ->
                    val inputs = mapOf("x" to x, "h" to hTensor, "c" to cTensor)
                    session.run(inputs).use { result ->
                        val prob = (result.get(0).value as Array<FloatArray>)[0][0]
                        h = flattenState(result.get(1).value)
                        c = flattenState(result.get(2).value)
                        return prob
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenState(raw: Any): FloatArray {
        val nested = raw as Array<Array<FloatArray>>
        val out = FloatArray(2 * 1 * STATE_DIM)
        var idx = 0
        for (a in nested) for (b in a) for (v in b) out[idx++] = v
        return out
    }

    /** Resets recurrent state — call when starting a fresh listening session. */
    fun resetState() {
        h = FloatArray(2 * 1 * STATE_DIM)
        c = FloatArray(2 * 1 * STATE_DIM)
    }

    override fun close() {
        session.close()
    }
}
