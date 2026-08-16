package app.marmalade.android.speech

/**
 * Results emitted by STT recognizers (WhisperRecognizer).
 *
 * Shared sealed class consumed by voice popup (MarmaladeVoiceSession), inline STT (InlineSTTState),
 * and any future STT entry point.
 */
sealed class StreamingResult {
    /** Recognizer is ready, mic is active. */
    object Ready : StreamingResult()

    /** Partial text updated -- display in UI. Changes with each audio chunk. */
    data class PartialText(val text: String) : StreamingResult()

    /** Final text from endpoint detection or manual stop. */
    data class FinalText(val text: String) : StreamingResult()

    /** Error occurred during recognition. */
    data class Error(val message: String) : StreamingResult()
}
