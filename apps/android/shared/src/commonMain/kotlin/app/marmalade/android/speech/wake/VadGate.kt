package app.marmalade.android.speech.wake

/**
 * Pure speech/silence gate with hangover, decoupled from the Silero ONNX
 * session so it is unit-testable with synthetic probability sequences.
 *
 * This is the battery fix: the retired rementia AAR ran the full
 * melspectrogram -> embedding -> classifier chain on every 80ms hop,
 * silence included (wake-word audit, kept internally). [SileroVad] is a
 * tiny model by comparison; running it every hop and only running the
 * expensive openWakeWord chain while [isOpen] is true is the whole saving.
 *
 * Hangover exists so the chain doesn't drop mid-utterance the instant speech
 * probability dips below threshold for one hop (VAD probability is noisy
 * frame-to-frame) — without it, "hey mar-<dip>-malade" could gate closed
 * before the wake word finishes.
 */
class VadGate(
    private val speechThreshold: Float = DEFAULT_SPEECH_THRESHOLD,
    private val hangoverMs: Long = DEFAULT_HANGOVER_MS,
) {
    companion object {
        const val DEFAULT_SPEECH_THRESHOLD = 0.5f
        const val DEFAULT_HANGOVER_MS = 1500L
    }

    private var open = false
    private var lastSpeechTimeMs: Long = 0L

    /** True while the openWakeWord chain should run for the current hop. */
    val isOpen: Boolean get() = open

    /**
     * Feed one hop's VAD speech probability. Updates and returns [isOpen].
     *
     * @param nowMs caller-supplied clock reading (testability).
     */
    fun offer(speechProbability: Float, nowMs: Long): Boolean {
        if (speechProbability > speechThreshold) {
            lastSpeechTimeMs = nowMs
            open = true
        } else if (open && (nowMs - lastSpeechTimeMs) >= hangoverMs) {
            open = false
        }
        return open
    }

    fun reset() {
        open = false
        lastSpeechTimeMs = 0L
    }
}
