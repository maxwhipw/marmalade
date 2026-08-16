package app.marmalade.android.service

import app.marmalade.android.chat.MAIN_SESSION_PLACEHOLDER

/**
 * Pure decision logic for routing voice turns to a chat session.
 *
 * Extracted from [MarmaladeVoiceSession] so the rule "voice lands in the same
 * session the Home tab opens" can be unit-tested without standing up a real
 * `VoiceInteractionSession`.
 *
 * Voice ALWAYS routes into THE daemon-managed main session (`session.main`) —
 * the same session Home binds to (mirrors `resolveAssistantSessionKey`). There
 * is no user-selectable "assistant session" any more (assistant plan
 * 2026-07-19: main is daemon-owned). Returns `null` only before the runtime
 * has resolved a main id (the boot placeholder or an empty key) — the caller
 * opens the popup in a degraded state and surfaces the standard "no session"
 * error on send.
 */
fun resolveVoiceSessionKey(mainSessionKey: String): String? {
    val main = mainSessionKey.trim()
    if (main.isEmpty() || main == MAIN_SESSION_PLACEHOLDER) return null
    return main
}
