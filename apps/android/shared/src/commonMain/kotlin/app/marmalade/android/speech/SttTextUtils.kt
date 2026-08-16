package app.marmalade.android.speech

/**
 * Trailing punctuation regex for stripping eager punctuation from partial results.
 * Whisper tends to add `.?!,;:` at VAD segment boundaries even mid-sentence.
 * Only final results should have punctuation.
 */
private val TRAILING_PUNCT = Regex("[.?!,;:]+$")

/**
 * Strip trailing punctuation characters from text.
 * Preserves mid-sentence punctuation (e.g., "Dr. Smith said" stays unchanged).
 * Used for partial STT results where Whisper eagerly punctuates.
 */
fun stripTrailingPunctuation(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.replace(TRAILING_PUNCT, "").trimEnd()
}

/**
 * Map VAD sensitivity slider value (0.0-1.0) to minSilenceDuration in seconds.
 * Linear interpolation: 0.0 -> 0.3s (quick), 0.5 -> 0.9s (medium), 1.0 -> 1.5s (forgiving).
 */
fun vadSliderToSilenceDuration(sliderValue: Float): Float {
    return 0.3f + (sliderValue * 1.2f)
}
