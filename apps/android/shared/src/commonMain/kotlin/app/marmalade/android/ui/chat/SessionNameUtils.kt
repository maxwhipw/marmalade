package app.marmalade.android.ui.chat

/**
 * Derive a human-friendly label from a raw session key.
 * Examples:
 *   "telegram:g-agent-main-main" -> "Main"
 *   "agent:main:main" -> "Main"
 *   "discord:g-server-channel" -> "Server Channel"
 *   "my-custom-session" -> "My Custom Session"
 *
 * Lives in `:shared/commonMain` (package unchanged) because the chat state
 * slice — `SessionListSync` in particular — needs it, and the rest of
 * `SessionFilters.kt` is Android-side session-picker policy that stays in
 * `:app`.
 */
fun friendlySessionName(key: String): String {
  // Strip common prefixes like "telegram:", "agent:", "discord:" etc.
  val stripped = key.substringAfterLast(":")

  // Remove leading "g-" prefix (gateway artifact)
  val cleaned = if (stripped.startsWith("g-")) stripped.removePrefix("g-") else stripped

  // Split on hyphens/underscores, title-case each word, collapse "main main" -> "Main"
  val words = cleaned.split('-', '_').filter { it.isNotBlank() }.map { word ->
    word.replaceFirstChar { it.uppercaseChar() }
  }.distinct()

  val result = words.joinToString(" ")
  return result.ifBlank { key }
}
