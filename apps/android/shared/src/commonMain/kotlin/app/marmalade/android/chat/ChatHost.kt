package app.marmalade.android.chat

import app.marmalade.android.voice.MarmaladeAction

/**
 * The handful of host-owned facts [ChatController] and [PromptCenter] used to
 * reach for through an `android.content.Context`.
 *
 * Same shape as [app.marmalade.android.rpc.DevicePairingHost]: a *narrow* port
 * carrying only the platform edges the chat core genuinely cannot own, never a
 * mirror of the runtime. On Android these are an Intent fire, two prefs
 * accessors, and one notification cancel — each of which needs a `Context` the
 * KMP core doesn't have.
 *
 * `host` is nullable at both call sites and **null must behave exactly like the
 * old null-`appContext` path**: the voice action is dropped, usage caching is a
 * no-op, cached usage reads as absent, and no notification is cancelled. Tests
 * construct without a host and rely on that.
 */
interface ChatHost {

    /**
     * Fire a parsed `marmalade_action` envelope at the platform (Android:
     * `voice.dispatchAction`). Failures are the host's to surface; the caller
     * ignores the outcome, matching the pre-port behavior.
     */
    fun dispatchVoiceAction(action: MarmaladeAction)

    /**
     * Cache the bound session's usage snapshot (already JSON-encoded) so the
     * composer context donut can render on a cold start.
     */
    fun saveSessionUsage(key: String, encodedJson: String)

    /** The cached usage JSON for [key], or null if nothing is cached. */
    fun loadSessionUsageJson(key: String): String?

    /** Dismiss the OS notification raised for an interactive prompt. */
    fun cancelPromptNotification(sessionKey: String)
}
