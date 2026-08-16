package app.marmalade.android.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.marmalade.android.R
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

private const val TAG = "AndroidTTSProvider"

/**
 * How long [speakWithProgress] waits for the engine's async init to finish
 * before giving up. The Android TTS engine usually initialises in 50–300 ms;
 * 3 s is a generous ceiling that still surfaces a stuck engine instead of
 * hanging the caller forever.
 */
private const val INIT_WAIT_TIMEOUT_MS = 3_000L

/**
 * Android native TTS provider (wrapper around TextToSpeech)
 */
class AndroidTTSProvider(private val context: Context) : TTSProvider {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val settings = SettingsRepository.getInstance(context)

    /**
     * Completes when the engine finishes its async init (success path) so a
     * cold-start `speak`/`speakWithProgress` can `await` it instead of
     * silently emitting `Error("not initialized")`. Pre-fix the very first
     * voice reply after process start often missed because the user hit Send
     * before TTS's init callback had fired.
     *
     * Completes exceptionally with the init-failure case so callers wait at
     * most [INIT_WAIT_TIMEOUT_MS] before reporting the error.
     */
    private val initDeferred = CompletableDeferred<Boolean>()

    init {
        initialize()
    }

    private fun initialize() {
        val preferredEngine = settings.ttsEngine

        if (preferredEngine.isNotEmpty()) {
            Log.d(TAG, "Initializing with preferred engine: $preferredEngine")
            tts = TextToSpeech(context.applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Initialized with preferred engine")
                    onInitSuccess()
                } else {
                    Log.w(TAG, "Preferred engine failed, falling back to default")
                    tryDefaultEngine()
                }
            }, preferredEngine)
        } else {
            tryDefaultEngine()
        }
    }

    private fun tryDefaultEngine() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d(TAG, "Initialized with default engine")
                onInitSuccess()
            } else {
                Log.e(TAG, "Failed to initialize TTS")
                // Resolve the wait so callers awaiting init don't hang for
                // the full timeout when the engine is genuinely unavailable.
                initDeferred.complete(false)
            }
        }
    }

    private fun onInitSuccess() {
        isInitialized = true
        setupVoice()
        initDeferred.complete(true)
    }

    /**
     * Settings snapshot the last [setupVoice] applied. Voice setup costs
     * 60–200 ms (setLanguage does an engine data lookup; `tts.voices`
     * enumerates + filters the catalog) and used to run on EVERY
     * utterance — a per-chunk tax and a chunk of the first-reply speech
     * delay. Now it re-runs only when the settings it reads change.
     */
    private var appliedLanguage: String? = null
    private var appliedSpeed: Float? = null

    private fun setupVoiceIfChanged() {
        if (settings.speechLanguage == appliedLanguage && settings.ttsSpeed == appliedSpeed) return
        setupVoice()
    }

    private fun setupVoice() {
        val tts = this.tts ?: return

        appliedLanguage = settings.speechLanguage
        appliedSpeed = settings.ttsSpeed

        val languageTag = settings.speechLanguage
        val locale = if (languageTag.isNotEmpty()) {
            Locale.forLanguageTag(languageTag)
        } else {
            Locale.getDefault()
        }

        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }

        // Set speed
        tts.setSpeechRate(settings.ttsSpeed)
        tts.setPitch(1.0f)

        // Try to select high-quality voice
        try {
            val targetLang = tts.language?.language
            val voices = tts.voices
            val bestVoice = voices?.filter { it.locale.language == targetLang }
                ?.firstOrNull { !it.isNetworkConnectionRequired }
                ?: voices?.firstOrNull { it.locale.language == targetLang }

            bestVoice?.let { tts.voice = it }
        } catch (e: Exception) {
            Log.w(TAG, "Error selecting voice: ${e.message}")
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.shutdown()
        isInitialized = false
    }

    override fun isAvailable(): Boolean = isInitialized

    override fun getType(): String = TTSProviderType.LOCAL

    override fun getDisplayName(): String = context.getString(R.string.tts_provider_local_name)

    override fun isConfigured(): Boolean = true // Local TTS is always configured

    override fun getConfigurationError(): String? = null

    override fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        val utteranceId = UUID.randomUUID().toString()

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                trySend(TTSState.Speaking)
            }
            override fun onDone(utteranceId: String?) {
                trySend(TTSState.Done)
                close()
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                trySend(TTSState.Error(context.getString(R.string.tts_error_stopped)))
                close()
            }
            override fun onError(utteranceId: String?) {
                trySend(TTSState.Error(context.getString(R.string.tts_error_generic)))
                close()
            }
        }

        // Cold-start path: if the engine hasn't fired its init callback yet
        // (very first message after process start), wait briefly for it to
        // resolve. Pre-fix we'd flunk straight into the Error branch and the
        // assistant's first reply went silent. The wait is bounded so a
        // genuinely broken engine still surfaces an error promptly.
        val ready = if (isInitialized) {
            true
        } else {
            try {
                withTimeoutOrNull(INIT_WAIT_TIMEOUT_MS) { initDeferred.await() } ?: false
            } catch (_: TimeoutCancellationException) {
                false
            }
        }

        if (ready && isInitialized) {
            setupVoiceIfChanged()
            trySend(TTSState.Preparing)
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            trySend(TTSState.Error(context.getString(R.string.tts_error_not_initialized)))
            close()
        }

        awaitClose { stop() }
    }
}
