package app.marmalade.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import app.marmalade.android.R

/**
 * Singleton SoundPool wrapper for voice interaction audio feedback.
 *
 * Plays a short pop/bubble sound for wake word activation and ready-for-input
 * cues. Uses SoundPool for low-latency playback from a preloaded buffer.
 *
 * Thread-safe via @Volatile + synchronized double-check locking, following
 * the project's manual singleton pattern (see SettingsRepository, AppDatabase).
 */
class SoundManager private constructor(context: Context) {

    companion object {
        private const val TAG = "SoundManager"
        private const val VOLUME = 0.7f
        private const val READY_VOLUME = 0.3f

        @Volatile
        private var instance: SoundManager? = null

        fun getInstance(context: Context): SoundManager {
            return instance ?: synchronized(this) {
                instance ?: SoundManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val soundPool: SoundPool
    private val activationSoundId: Int

    @Volatile
    private var soundLoaded = false

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(attrs)
            .build()

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                soundLoaded = true
                Log.d(TAG, "Sound loaded: sampleId=$sampleId")
            } else {
                Log.w(TAG, "Sound load failed: sampleId=$sampleId status=$status")
            }
        }

        // Load the activation sound -- same sound reused for both activation and ready cues
        activationSoundId = soundPool.load(context, R.raw.activation, 1)
    }

    /**
     * Play the activation sound (wake word detected).
     * No-op if the sound has not finished loading yet.
     */
    fun playActivation() {
        if (!soundLoaded) {
            Log.d(TAG, "playActivation: sound not yet loaded, skipping")
            return
        }
        soundPool.play(activationSoundId, VOLUME, VOLUME, 1, 0, 1.0f)
    }

    /**
     * Play the ready-for-input sound (response complete, ready for next turn).
     * Uses the same activation sound per CONTEXT.md design decision.
     */
    fun playReady() {
        if (!soundLoaded) {
            Log.d(TAG, "playReady: sound not yet loaded, skipping")
            return
        }
        soundPool.play(activationSoundId, READY_VOLUME, READY_VOLUME, 1, 0, 1.2f)
    }
}
