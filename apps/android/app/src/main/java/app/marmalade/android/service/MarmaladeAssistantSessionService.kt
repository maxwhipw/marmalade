package app.marmalade.android.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Service that hosts the VoiceInteractionSession.
 */
class MarmaladeAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return MarmaladeVoiceSession(this)
    }
}
