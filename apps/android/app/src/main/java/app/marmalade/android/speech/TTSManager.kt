package app.marmalade.android.speech

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Minimal interface for per-message read-aloud so callers (ChatScreen, tests)
 * don't depend on the full [TTSManager] concrete class.
 */
interface TTSSpeaker {
    fun speakWithProgress(text: String): Flow<TTSState>
    fun stop()
}

/**
 * Text-to-Speech Manager — local Android TTS only.
 * ElevenLabs, OpenAI, and VoiceVox providers have been removed.
 */
class TTSManager(private val context: Context) : TTSSpeaker {

    // Provider instances
    private val providers = mutableMapOf<String, TTSProvider>()

    init {
        providers[TTSProviderType.LOCAL] = AndroidTTSProvider(context)
    }

    /**
     * Get the currently configured provider (always local)
     */
    private fun getCurrentProvider(): TTSProvider? {
        return providers[TTSProviderType.LOCAL]
    }

    /**
     * Speak with progress updates
     */
    override fun speakWithProgress(text: String): Flow<TTSState> {
        val provider = getCurrentProvider()
        if (provider == null) {
            return callbackFlow {
                trySend(TTSState.Error("No provider found"))
                close()
            }
        }

        if (!provider.isConfigured()) {
            return callbackFlow {
                trySend(TTSState.Error(provider.getConfigurationError() ?: "Not configured"))
                close()
            }
        }

        val processedText = TTSUtils.stripMarkdownForSpeech(text)
        return provider.speakWithProgress(processedText)
    }

    /**
     * Stop current speech
     */
    override fun stop() {
        getCurrentProvider()?.stop()
    }

    /**
     * Stop all providers
     */
    fun stopAll() {
        providers.values.forEach { it.stop() }
    }

    /**
     * Release all resources
     */
    fun shutdown() {
        providers.values.forEach { it.shutdown() }
        providers.clear()
    }

    /**
     * Reinitialize after settings change
     */
    fun reinitialize() {
        shutdown()
        providers[TTSProviderType.LOCAL] = AndroidTTSProvider(context)
    }
}
