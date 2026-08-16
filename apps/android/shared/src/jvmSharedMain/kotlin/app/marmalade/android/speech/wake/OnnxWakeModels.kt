package app.marmalade.android.speech.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Thin ONNX session holders for the openWakeWord mel-spectrogram and
 * embedding stages, plus one holder per classifier model. Each session is
 * created once in the constructor and closed in [close] — this is the fix
 * for the retired rementia AAR's root battery cause, which recreated an
 * `OrtSession` from asset bytes on every 80ms chunk for these two stages
 * specifically (wake-word audit, kept internally).
 *
 * All orchestration (windowing, mel scaling, hop bookkeeping) lives in
 * [OpenWakeWordChain] / [MelWindowBuffer] as constructor-injected functions;
 * these classes only run inference against a fixed tensor shape.
 */
class MelSpectrogramModel(assetBytes: ByteArray) : AutoCloseable {
    companion object {
        /** Confirmed via onnxruntime JVM check: melspectrogram.onnx output mel-bin count. */
        const val MEL_BINS = 32
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(assetBytes, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()

    /**
     * Runs one hop of raw PCM samples (as floats) through the mel model.
     * Confirmed input shape `[batch, samples]`, output shape
     * `[time, 1, T, 32]`; returns the T output rows as individual
     * 32-float mel frames, oldest first.
     */
    fun infer(pcmHop: FloatArray): List<FloatArray> {
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(pcmHop), longArrayOf(1, pcmHop.size.toLong()),
        ).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = result.get(0).value as Array<Array<Array<FloatArray>>>
                // shape [time, 1, T, 32] -> iterate time, then T, collecting 32-float rows.
                val frames = mutableListOf<FloatArray>()
                for (timeSlice in raw) {
                    for (row in timeSlice[0]) {
                        frames.add(row)
                    }
                }
                return frames
            }
        }
    }

    override fun close() = session.close()
}

class EmbeddingModel(assetBytes: ByteArray) : AutoCloseable {
    companion object {
        /** Confirmed via onnxruntime JVM check: embedding_model.onnx output dim. */
        const val EMBEDDING_DIM = 96
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(assetBytes, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()

    /**
     * Runs one flattened 76x32 mel window through the embedding model.
     * Confirmed input shape `[batch, 76, 32, 1]`, output `[batch, 1, 1, 96]`.
     */
    fun infer(flattenedWindow: FloatArray): FloatArray {
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(flattenedWindow),
            longArrayOf(1, MelWindowBuffer.EMBEDDING_WINDOW_SIZE.toLong(), MelWindowBuffer.MEL_FRAME_SIZE.toLong(), 1),
        ).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = result.get(0).value as Array<Array<Array<FloatArray>>>
                return raw[0][0][0]
            }
        }
    }

    override fun close() = session.close()
}

/** One classifier session for one wake-word model (e.g. marmalade.onnx / openclaw.onnx). */
class ClassifierModel(assetBytes: ByteArray) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(assetBytes, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()

    /**
     * Runs one flattened 16x96 embedding window through the classifier.
     * Confirmed input shape `[1, 16, 96]`, output `[1, 1]` sigmoid score.
     */
    fun infer(flattenedWindow: FloatArray): Float {
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(flattenedWindow),
            longArrayOf(1, MelWindowBuffer.CLASSIFIER_WINDOW_SIZE.toLong(), EmbeddingModel.EMBEDDING_DIM.toLong()),
        ).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = result.get(0).value as Array<FloatArray>
                return raw[0][0]
            }
        }
    }

    override fun close() = session.close()
}
