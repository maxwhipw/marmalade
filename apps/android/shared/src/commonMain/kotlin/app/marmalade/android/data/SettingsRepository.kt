package app.marmalade.android.data

/**
 * Identifying facts about a wake-word preset: the persisted key, the
 * display name shown in UI / logs, the phrase the user is taught to say,
 * and the .onnx asset that openWakeWord loads. Adding a preset means adding
 * a row to [SettingsRepository.BUILTIN_WAKE_WORD_PRESETS].
 */
data class WakeWordPresetInfo(
    val key: String,
    val displayName: String,
    val phrase: String,
    val assetFilename: String,
)

/**
 * Data Flow: SettingsRepository
 *
 * SettingsStore (platform-backed local storage)
 *         |
 * SettingsRepository (singleton accessor)
 *         |
 * UI / Services (read/write settings)
 *
 * Gateway-only settings store. HTTP/ElevenLabs/OpenAI/VoiceVox settings
 * have been removed — this app only uses the gateway WebSocket connection
 * and Android's built-in TTS.
 *
 * ADR 0011 (KMP move, increment 3c): the pure logic + persisted-state surface
 * live in the shared KMP library's `commonMain` so the desktop client reuses
 * them. Storage is abstracted behind [SettingsStore] ([store] = the settings
 * prefs, [credentials] = a read-only view of the SecurePrefs-owned dashboard
 * pair). The `getInstance(Context)` accessor (androidMain) wires
 * EncryptedSharedPreferences into both; desktop supplies file-backed stores.
 */
class SettingsRepository(
    private val store: SettingsStore,
    private val credentials: SettingsStore,
) {

    // Hotword enabled (ON by default -- user opts out via settings)
    var hotwordEnabled: Boolean
        get() = store.getBoolean(KEY_HOTWORD_ENABLED, true)
        set(value) = store.putBoolean(KEY_HOTWORD_ENABLED, value)

    // Wake word sensitivity (low / medium / high) — legacy, kept for migration
    var wakeWordSensitivity: String
        get() = store.getString(KEY_WAKE_WORD_SENSITIVITY, "medium") ?: "medium"
        set(value) = store.putString(KEY_WAKE_WORD_SENSITIVITY, value)

    // Get the KWS threshold — prefers direct float, falls back to legacy string.
    // Floats persisted before the slider remap lived in 0.03–0.20, all of which
    // are now far too eager (false-positive prone). Any stored value below the
    // current slider minimum is treated as a pre-remap leftover and migrated to
    // the default rather than fed to the engine.
    fun getWakeWordThreshold(): Float {
        if (!store.contains(KEY_WAKE_WORD_THRESHOLD)) {
            return mapSensitivityToThreshold(wakeWordSensitivity)
        }
        val stored = store.getFloat(KEY_WAKE_WORD_THRESHOLD, DEFAULT_WAKE_WORD_THRESHOLD)
        return if (stored < MIN_WAKE_WORD_THRESHOLD) DEFAULT_WAKE_WORD_THRESHOLD else stored
    }

    fun setWakeWordThreshold(value: Float) =
        store.putFloat(KEY_WAKE_WORD_THRESHOLD, value)

    // Wake word selection (preset key, or WAKE_WORD_CUSTOM for user-supplied).
    // Read-side migrates legacy and unknown values to the default built-in
    // preset, so a saved-pref written by a previous version (hey_marmalade,
    // jarvis, etc.) silently lands back in a valid state.
    var wakeWordPreset: String
        get() {
            val stored = store.getString(KEY_WAKE_WORD_PRESET, DEFAULT_WAKE_WORD_PRESET.key)
                ?: DEFAULT_WAKE_WORD_PRESET.key
            val knownKeys = BUILTIN_WAKE_WORD_PRESETS.map { it.key } + WAKE_WORD_CUSTOM
            return if (stored in knownKeys) stored else DEFAULT_WAKE_WORD_PRESET.key
        }
        set(value) = store.putString(KEY_WAKE_WORD_PRESET, value)

    // Custom wake word (when preset is "custom")
    var customWakeWord: String
        get() = store.getString(KEY_CUSTOM_WAKE_WORD, "") ?: ""
        set(value) = store.putString(KEY_CUSTOM_WAKE_WORD, value)

    /**
     * Resolve current settings to the WakeWordPresetInfo that should actually
     * load. Built-in presets resolve to their static row. Custom resolves to
     * a derived row from the user's typed name; blank-custom falls back to
     * the default built-in so logs and UI agree with what the engine loads.
     */
    private fun resolveWakeWordPreset(): WakeWordPresetInfo {
        BUILTIN_WAKE_WORD_PRESETS.firstOrNull { it.key == wakeWordPreset }?.let { return it }
        if (wakeWordPreset == WAKE_WORD_CUSTOM) {
            val trimmed = customWakeWord.trim()
            if (trimmed.isEmpty()) return DEFAULT_WAKE_WORD_PRESET
            val phrase = trimmed.lowercase()
            return WakeWordPresetInfo(
                key = WAKE_WORD_CUSTOM,
                displayName = trimmed,
                phrase = phrase,
                assetFilename = "${phrase.replace(" ", "_")}.onnx",
            )
        }
        return DEFAULT_WAKE_WORD_PRESET
    }

    fun getWakeWords(): List<String> = listOf(resolveWakeWordPreset().phrase)

    fun getWakeWordDisplayName(): String = resolveWakeWordPreset().displayName

    /**
     * Asset filename of the per-keyword openWakeWord classifier model.
     * Loaded by [HotwordService] when constructing the engine. Custom-preset
     * filenames are derived from the user-typed name (lowercased, spaces →
     * underscores, `.onnx` appended); a matching asset must be added to
     * `app/src/main/assets/` at build time (developer workflow only).
     */
    fun getWakeWordAssetFilename(): String = resolveWakeWordPreset().assetFilename

    // Chat-tab auto-speak toggle (top-bar speaker icon). Deliberately separate
    // from the voice assistant, which ALWAYS speaks — muting chat must never
    // silence the wake-word popup (maintainer, 2026-07-08).
    var chatTtsEnabled: Boolean
        get() = store.getBoolean(KEY_CHAT_TTS_ENABLED, false)
        set(value) = store.putBoolean(KEY_CHAT_TTS_ENABLED, value)

    // Resume Latest Session
    var resumeLatestSession: Boolean
        get() = store.getBoolean(KEY_RESUME_LATEST_SESSION, false)
        set(value) = store.putBoolean(KEY_RESUME_LATEST_SESSION, value)

    // TTS Speed
    var ttsSpeed: Float
        get() = store.getFloat(KEY_TTS_SPEED, 1.2f)
        set(value) = store.putFloat(KEY_TTS_SPEED, value)

    // TTS Engine
    var ttsEngine: String
        get() = store.getString(KEY_TTS_ENGINE, "") ?: ""
        set(value) = store.putString(KEY_TTS_ENGINE, value)

    // Gateway Port for WebSocket agent list connection (default 18789)
    var gatewayPort: Int
        get() = store.getInt(KEY_GATEWAY_PORT, 18789)
        set(value) = store.putInt(KEY_GATEWAY_PORT, value)

    // Speech recognition silence timeout in ms (default 5000ms)
    var speechSilenceTimeout: Long
        get() = store.getLong(KEY_SPEECH_SILENCE_TIMEOUT, 5000L)
        set(value) = store.putLong(KEY_SPEECH_SILENCE_TIMEOUT, value)

    // Speech recognition language (BCP-47 tag, empty = system default)
    var speechLanguage: String
        get() = store.getString(KEY_SPEECH_LANGUAGE, "") ?: ""
        set(value) = store.putString(KEY_SPEECH_LANGUAGE, value)

    // Conversation mode: after the assistant finishes speaking, automatically
    // re-open the mic for the next turn (the voice popup's auto-listen
    // toggle). Persisted so the choice survives across voice sessions.
    var conversationModeEnabled: Boolean
        get() = store.getBoolean(KEY_CONVERSATION_MODE, false)
        set(value) = store.putBoolean(KEY_CONVERSATION_MODE, value)

    // Patient listening: auto-restart recognizer on silence timeout and accumulate text
    // until user says a termination word. Default ON.
    var patientListeningEnabled: Boolean
        get() = store.getBoolean(KEY_PATIENT_LISTENING, true)
        set(value) = store.putBoolean(KEY_PATIENT_LISTENING, value)

    // Termination words that end patient listening and send the message.
    // Stored as comma-separated string. Default: "over,send it,that's all,I'm done"
    var terminationWords: String
        get() = store.getString(KEY_TERMINATION_WORDS, DEFAULT_TERMINATION_WORDS) ?: DEFAULT_TERMINATION_WORDS
        set(value) = store.putString(KEY_TERMINATION_WORDS, value)

    fun getTerminationWordsList(): List<String> {
        return terminationWords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    /**
     * Check if the given text ends with a termination word.
     * Returns the text with the termination word stripped, or null if no match.
     */
    fun extractTerminationWord(text: String): Pair<String, String>? =
        matchTerminationWord(text, getTerminationWordsList())

    // App UI language (BCP-47 tag, empty = system default)
    var appLanguage: String
        get() = store.getString(KEY_APP_LANGUAGE, "") ?: ""
        set(value) = store.putString(KEY_APP_LANGUAGE, value)

    // Thinking sound enabled
    var thinkingSoundEnabled: Boolean
        get() = store.getBoolean(KEY_THINKING_SOUND_ENABLED, true)
        set(value) = store.putBoolean(KEY_THINKING_SOUND_ENABLED, value)

    // Show assistant reasoning/thinking blocks in the chat transcript.
    var showThinkingBlocks: Boolean
        get() = store.getBoolean(KEY_SHOW_THINKING_BLOCKS, true)
        set(value) = store.putBoolean(KEY_SHOW_THINKING_BLOCKS, value)

    // Show tool-use cards in the chat transcript.
    var showToolUse: Boolean
        get() = store.getBoolean(KEY_SHOW_TOOL_USE, true)
        set(value) = store.putBoolean(KEY_SHOW_TOOL_USE, value)

    // Has completed initial setup guide
    var hasCompletedSetup: Boolean
        get() = store.getBoolean(KEY_HAS_COMPLETED_SETUP, false)
        set(value) = store.putBoolean(KEY_HAS_COMPLETED_SETUP, value)

    // Cached id of THE daemon-managed singleton main session (session.main).
    // Home + voice ALWAYS bind to this session — there is no user-selectable
    // "assistant session" any more (assistant plan 2026-07-19: main is
    // daemon-owned). This is written every time the runtime resolves
    // session.main on connect, and read on the next OFFLINE cold start so Home
    // shows the main conversation immediately instead of an empty phantom.
    // Self-healing: a stale value is overwritten by the authoritative
    // session.main the moment the socket opens.
    var cachedMainSessionId: String?
        get() = store.getString(KEY_MAIN_SESSION_ID, null)
        set(value) = store.putString(KEY_MAIN_SESSION_ID, value)

    // Cold-open cache of the last `session.info` usage snapshot for the bound
    // session, so the composer's context donut can render immediately on app
    // restart instead of blank until the first turn. Single-entry, keyed by
    // session: reopening the same session restores it; a different session
    // shows nothing until its first turn (same as before). The gateway can't
    // supply this on bind — it only computes context after a turn runs in the
    // live session — so the client caches the last-known value.
    fun saveSessionUsage(sessionKey: String, usageJson: String) {
        // JSON first: a crash between the two writes then leaves the OLD key
        // with NEW json (read as null — fail-safe) rather than the new key
        // serving the previous session's usage.
        store.putString(KEY_LAST_USAGE_JSON, usageJson)
        store.putString(KEY_LAST_USAGE_SESSION, sessionKey)
    }

    /** Returns the cached usage JSON only when it belongs to [sessionKey]. */
    fun getSessionUsageJson(sessionKey: String): String? =
        if (store.getString(KEY_LAST_USAGE_SESSION, null) == sessionKey) {
            store.getString(KEY_LAST_USAGE_JSON, null)
        } else {
            null
        }

    // Theme mode: "system", "light", or "dark"
    var themeMode: String
        get() = store.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) = store.putString(KEY_THEME_MODE, value)

    // Theme preset name (e.g., "SYSTEM", "MARMALADE", "MIDNIGHT", "FOREST", "BERRY").
    // Defaults to MARMALADE so the app is on-brand out of the box; Material You
    // (SYSTEM, wallpaper-derived) remains a selectable preset.
    var themePreset: String
        get() = store.getString(KEY_THEME_PRESET, "MARMALADE") ?: "MARMALADE"
        set(value) = store.putString(KEY_THEME_PRESET, value)

    // Has seen the session organization tutorial popup
    var hasSeenSessionTutorial: Boolean
        get() = store.getBoolean(KEY_HAS_SEEN_SESSION_TUTORIAL, false)
        set(value) = store.putBoolean(KEY_HAS_SEEN_SESSION_TUTORIAL, value)

    // Default Agent ID
    var defaultAgentId: String
        get() = store.getString(KEY_DEFAULT_AGENT_ID, "main") ?: "main"
        set(value) = store.putString(KEY_DEFAULT_AGENT_ID, value)

    // Active STT model ID. Default = the bundled distil-small.en (ADR 0012).
    var activeSTTModel: String
        get() = store.getString(KEY_ACTIVE_STT_MODEL, "distil_small_en") ?: "distil_small_en"
        set(value) = store.putString(KEY_ACTIVE_STT_MODEL, value)

    // VAD sensitivity slider value (0.0 = quick response, 1.0 = forgiving of pauses)
    var vadSensitivity: Float
        get() = store.getFloat(KEY_VAD_SENSITIVITY, 0.5f)
        set(value) = store.putFloat(KEY_VAD_SENSITIVITY, value)

    // Keep STT model loaded in memory (loads on app start, survives background)
    var keepSTTLoaded: Boolean
        get() = store.getBoolean(KEY_KEEP_STT_LOADED, false)
        set(value) = store.putBoolean(KEY_KEEP_STT_LOADED, value)

    // Developer option: show the Debug tab in the bottom navigation bar.
    // OFF by default — the firehose log explorer is a developer tool, not a
    // user-facing feature. Toggled from the Developer settings screen, which
    // ships in release builds (unlike the old compile-time BuildConfig.DEBUG
    // gate, which stripped the tab from release entirely).
    var debugTabEnabled: Boolean
        get() = store.getBoolean(KEY_DEBUG_TAB_ENABLED, false)
        set(value) = store.putBoolean(KEY_DEBUG_TAB_ENABLED, value)

    // Developer option: render unknown / unhandled gateway frames as
    // `role="unknown"` cards in the chat. OFF by default — an unrecognised
    // frame still produces a WARN log line either way; this only controls
    // whether it also surfaces visibly in the conversation.
    var showUnknownFramesInChat: Boolean
        get() = store.getBoolean(KEY_SHOW_UNKNOWN_FRAMES, false)
        set(value) = store.putBoolean(KEY_SHOW_UNKNOWN_FRAMES, value)

    // Whether the terminal's soft keyboard may compose and autocorrect ("Abc"
    // in the extra-keys row). OFF by default: a terminal echoes per keystroke,
    // and letting the IME compose means the echo arrives a word at a time.
    // Global rather than per-terminal — it is a keyboard preference, and it
    // outlives any one shell.
    var terminalSuggestionsEnabled: Boolean
        get() = store.getBoolean(KEY_TERMINAL_SUGGESTIONS, false)
        set(value) = store.putBoolean(KEY_TERMINAL_SUGGESTIONS, value)

    // Set of `latestVersion` strings the user has dismissed on the
    // update-available banner. When the gateway re-emits the same version,
    // the dispatch site suppresses surfacing the banner. A newer
    // `latestVersion` is treated as a fresh notification regardless of any
    // previously dismissed entries.
    fun isUpdateVersionDismissed(version: String): Boolean {
        if (version.isBlank()) return false
        return store.getStringSet(KEY_DISMISSED_UPDATE_VERSIONS, emptySet())
            .contains(version)
    }

    fun markUpdateVersionDismissed(version: String) {
        if (version.isBlank()) return
        val existing = store.getStringSet(KEY_DISMISSED_UPDATE_VERSIONS, emptySet())
        if (version in existing) return
        // Copy: SharedPreferences contract forbids mutating the returned set.
        val updated = existing.toMutableSet().apply { add(version) }
        store.putStringSet(KEY_DISMISSED_UPDATE_VERSIONS, updated)
    }

    // Set of pending-pair `requestId` strings the user has dismissed on the
    // node-pairing banner. Mirrors the dismissed-update-versions pattern:
    // the reconciler treats a dismissed requestId as Hidden until the gateway
    // either resolves it or issues a fresh requestId (supersession). The set
    // grows unbounded in principle; in practice gateway TTL is 5 minutes and
    // requestIds rarely accumulate beyond a handful per device. If pruning
    // ever becomes necessary, GC against the latest `node.pair.list`'s
    // pending set during reconcile.
    fun isPairRequestDismissed(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        return store.getStringSet(KEY_DISMISSED_PAIR_REQUESTS, emptySet())
            .contains(requestId)
    }

    fun markPairRequestDismissed(requestId: String) {
        if (requestId.isBlank()) return
        val existing = store.getStringSet(KEY_DISMISSED_PAIR_REQUESTS, emptySet())
        if (requestId in existing) return
        val updated = existing.toMutableSet().apply { add(requestId) }
        store.putStringSet(KEY_DISMISSED_PAIR_REQUESTS, updated)
    }

    /**
     * Drop any dismissed requestIds that aren't in [keepIds]. Called from
     * the pairing reconciler after a successful `node.pair.list` response —
     * the gateway has either resolved or expired any requestId not in the
     * pending array, so the persisted dismissal has nothing left to
     * suppress and would only grow the set unbounded over a developer's
     * many-test-gateway lifetime.
     *
     * No-op when nothing changes — avoids a SettingsStore write on
     * every poll-loop tick when the dismissed set is already empty or
     * already fully covered.
     */
    fun pruneDismissedPairRequests(keepIds: Set<String>) {
        val existing = store.getStringSet(KEY_DISMISSED_PAIR_REQUESTS, emptySet())
        if (existing.isEmpty()) return
        val pruned = existing.intersect(keepIds)
        if (pruned.size == existing.size) return
        store.putStringSet(KEY_DISMISSED_PAIR_REQUESTS, pruned)
    }

    /**
     * True when the dashboard URL + token are both set. Used by callers
     * outside the Compose layer (BootReceiver, MarmaladeVoiceSession, ...) that
     * don't hold a [MarmaladeRuntime] reference. Reads the keys
     * SecurePrefs writes for the dashboard pair (the [credentials] store).
     */
    fun isConfigured(): Boolean {
        val url = credentials.getString("marmalade.dashboard.url", null)?.trim().orEmpty()
        val token = credentials.getString("marmalade.dashboard.token", null)?.trim().orEmpty()
        return url.isNotEmpty() && token.isNotEmpty()
    }

    companion object {
        /**
         * Pure matcher behind [extractTerminationWord] (static for unit tests).
         * Strips trailing punctuation (Whisper often appends "." to short
         * utterances) and requires a word boundary before the match so
         * "hangover" never triggers "over".
         */
        fun matchTerminationWord(text: String, words: List<String>): Pair<String, String>? {
            val stripped = text.trim().replace(Regex("[.?!,;:]+$"), "").trim()
            val normalized = stripped.lowercase()
            for (word in words) {
                // endsWith first: it also guarantees normalized.length >= word.length,
                // so the boundary index below can't go negative.
                if (!normalized.endsWith(word)) continue
                val boundaryOk = normalized.length == word.length ||
                    !normalized[normalized.length - word.length - 1].isLetter()
                if (boundaryOk) {
                    return Pair(stripped.substring(0, stripped.length - word.length).trim(), word)
                }
            }
            return null
        }

        private const val KEY_HOTWORD_ENABLED = "hotword_enabled"
        private const val KEY_WAKE_WORD_PRESET = "wake_word_preset"
        private const val KEY_CUSTOM_WAKE_WORD = "custom_wake_word"
        private const val KEY_CHAT_TTS_ENABLED = "chat_tts_enabled"
        @Suppress("unused") private const val KEY_CONTINUOUS_MODE = "continuous_mode" // removed, kept for migration
        private const val KEY_CONVERSATION_MODE = "conversation_mode_enabled"
        private const val KEY_RESUME_LATEST_SESSION = "resume_latest_session"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_GATEWAY_PORT = "gateway_port"
        private const val KEY_DEFAULT_AGENT_ID = "default_agent_id"
        private const val KEY_SPEECH_SILENCE_TIMEOUT = "speech_silence_timeout"
        private const val KEY_THINKING_SOUND_ENABLED = "thinking_sound_enabled"
        private const val KEY_SHOW_THINKING_BLOCKS = "show_thinking_blocks"
        private const val KEY_SHOW_TOOL_USE = "show_tool_use"
        private const val KEY_SPEECH_LANGUAGE = "speech_language"
        private const val KEY_PATIENT_LISTENING = "patient_listening"
        private const val KEY_TERMINATION_WORDS = "termination_words"
        private const val DEFAULT_TERMINATION_WORDS = "over,send it,that's all,I'm done"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_MAIN_SESSION_ID = "main_session_id"
        private const val KEY_LAST_USAGE_SESSION = "last_usage_session"
        private const val KEY_LAST_USAGE_JSON = "last_usage_json"
        private const val KEY_HAS_COMPLETED_SETUP = "has_completed_setup"
        private const val KEY_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
        private const val KEY_WAKE_WORD_THRESHOLD = "wake_word_threshold"
        private const val KEY_HAS_SEEN_SESSION_TUTORIAL = "has_seen_session_tutorial"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_PRESET = "theme_preset"
        private const val KEY_ACTIVE_STT_MODEL = "active_stt_model"
        private const val KEY_VAD_SENSITIVITY = "vad_sensitivity"
        private const val KEY_KEEP_STT_LOADED = "keep_stt_loaded"
        private const val KEY_DEBUG_TAB_ENABLED = "debug_tab_enabled"
        private const val KEY_SHOW_UNKNOWN_FRAMES = "show_unknown_frames_in_chat"
        private const val KEY_TERMINAL_SUGGESTIONS = "terminal_suggestions_enabled"
        private const val KEY_DISMISSED_UPDATE_VERSIONS = "dismissed_update_versions"
        private const val KEY_DISMISSED_PAIR_REQUESTS = "dismissed_pair_requests"
        private const val KEY_SESSION_SORT_MODE = "session_sort_mode"

        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

        // Wake-word detection threshold bounds. openWakeWord scores are sigmoid
        // probabilities in [0,1]; a detection fires when score > threshold, so a
        // lower threshold is more eager to trigger. The library's own default is
        // 0.5. The slider spans MIN..MAX; values below MIN are pre-remap leftovers
        // (the old 0.03–0.20 range) and migrate to DEFAULT on read.
        const val MIN_WAKE_WORD_THRESHOLD = 0.3f
        const val MAX_WAKE_WORD_THRESHOLD = 0.9f
        const val DEFAULT_WAKE_WORD_THRESHOLD = 0.5f

        /**
         * Map sensitivity label to KWS threshold.
         * Lower threshold = more sensitive (more detections, more false positives).
         * Higher threshold = less sensitive (fewer detections, fewer false positives).
         */
        fun mapSensitivityToThreshold(sensitivity: String): Float {
            return when (sensitivity.lowercase()) {
                "low" -> 0.7f                     // strict — fewest false positives
                "medium" -> DEFAULT_WAKE_WORD_THRESHOLD // 0.5 — openWakeWord default
                "high" -> MIN_WAKE_WORD_THRESHOLD       // 0.3 — most sensitive
                else -> DEFAULT_WAKE_WORD_THRESHOLD     // unknown defaults to medium
            }
        }

        // Wake word presets. Custom-trained openWakeWord models ship in
        // app/src/main/assets/, all built on license-clean data so they're
        // safe to redistribute. Legacy preset strings (hey_marmalade,
        // hey_assistant, jarvis, computer) referenced CC-BY-NC-SA pre-trained
        // models that we no longer ship — any saved-pref with one of those
        // values migrates to DEFAULT_WAKE_WORD_PRESET on read.
        const val WAKE_WORD_MARMALADE = "marmalade"
        const val WAKE_WORD_OPENCLAW = "openclaw"
        const val WAKE_WORD_CUSTOM = "custom"

        /**
         * Single source of truth for the wake-word presets that ship as
         * bundled .onnx assets. To add a preset:
         *   1. Drop the .onnx into `app/src/main/assets/`.
         *   2. Add a key constant above.
         *   3. Add a row here.
         * The picker, model loader, display copy, and legacy-value migration
         * all read from this list — no other site needs touching.
         */
        val BUILTIN_WAKE_WORD_PRESETS: List<WakeWordPresetInfo> = listOf(
            WakeWordPresetInfo(
                key = WAKE_WORD_MARMALADE,
                displayName = "Marmalade",
                phrase = "marmalade",
                assetFilename = "marmalade.onnx",
            ),
            WakeWordPresetInfo(
                key = WAKE_WORD_OPENCLAW,
                displayName = "OpenClaw",
                phrase = "open claw",
                assetFilename = "openclaw.onnx",
            ),
        )

        /** Fallback when prefs are blank, legacy, or Custom-with-empty-name. */
        val DEFAULT_WAKE_WORD_PRESET: WakeWordPresetInfo get() = BUILTIN_WAKE_WORD_PRESETS.first()

        /**
         * Resolve app language setting to a BCP-47 locale tag.
         * Returns null for system default (empty or "system").
         * Returns the tag as-is for known or future languages.
         */
        fun resolveLanguageTag(appLanguage: String): String? {
            return when {
                appLanguage.isEmpty() || appLanguage == "system" -> null
                else -> appLanguage
            }
        }
    }
}
