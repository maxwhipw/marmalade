package app.marmalade.android.speech

/**
 * Facade over an offline batch transcriber (sherpa-onnx `OfflineRecognizer`'s
 * createStream → acceptWaveform → decode → getResult dance).
 *
 * Why a facade instead of using sherpa directly in shared code: sherpa's two
 * JVM distributions expose DIFFERENT APIs under the same class names — the
 * Android AAR ships Kotlin named-arg constructors (+ assetManager loading),
 * while the desktop linux-x64 release jars ship a Java builder API (proven in
 * a desktop spike; notes kept internally). Shared streaming logic can't compile
 * against either uniformly, so each target wraps its own sherpa behind this
 * seam ([WhisperStreamingEngine] is the consumer).
 */
interface OfflineTranscriber {
    /** Transcribe a complete utterance of float PCM in [-1, 1]. */
    fun transcribe(samples: FloatArray, sampleRate: Int): String
}

/**
 * Facade over a streaming VAD with segment assembly — shaped 1:1 after the
 * sherpa-onnx `Vad` surface [WhisperStreamingEngine] consumes. Same rationale
 * as [OfflineTranscriber]: per-target sherpa APIs differ, logic is shared.
 */
interface SpeechVad {
    /** Feed one analysis window ([WhisperStreamingEngine.VAD_WINDOW_SIZE] samples). */
    fun acceptWaveform(samples: FloatArray)

    /** True while the VAD currently believes speech is in progress. */
    fun isSpeechDetected(): Boolean

    /** True when no completed speech segment is queued. */
    fun empty(): Boolean

    /** Samples of the oldest completed segment. Only valid when ![empty]. */
    fun frontSamples(): FloatArray

    /** Drop the oldest completed segment. */
    fun pop()

    /** Reset all VAD state for a fresh listening session. */
    fun reset()
}
