package app.marmalade.android.voice

/**
 * Local exit phrase matching for voice commands.
 * Uses exact match (case-insensitive, trimmed) for safety.
 * Exact match (rather than contains) prevents accidental exits when
 * a phrase is used in the middle of a longer sentence.
 */
val EXIT_PHRASES = listOf(
    "exit talk mode",
    "stop listening",
    "end conversation",
    "goodbye",
    "stop talking",
    "that's all",
    "never mind",
    "dismiss",
)

fun isExitPhrase(text: String): Boolean {
    val normalized = text.trim().lowercase()
    return EXIT_PHRASES.any { it == normalized }
}
