package app.marmalade.android.chat

fun normalizeMainKey(raw: String?): String {
    val trimmed = raw?.trim()
    return if (!trimmed.isNullOrEmpty()) trimmed else "main"
}
