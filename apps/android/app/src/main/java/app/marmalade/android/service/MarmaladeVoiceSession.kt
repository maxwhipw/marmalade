package app.marmalade.android.service

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Image
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import app.marmalade.android.R
import app.marmalade.android.MarmaladeApplication
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.mic.MicOwner
import app.marmalade.android.mic.MicOwnershipManager
import app.marmalade.android.speech.STTEngineProvider
import app.marmalade.android.speech.ServerRecognizer
import app.marmalade.android.speech.StreamingRecognizer
import app.marmalade.android.speech.StreamingResult
import app.marmalade.android.speech.vadSliderToSilenceDuration
import app.marmalade.android.speech.TTSManager
import app.marmalade.android.speech.TTSState
import app.marmalade.android.speech.TTSUtils
import app.marmalade.android.voice.SoundManager
import app.marmalade.android.voice.collectSpeakableChunks
import app.marmalade.android.voice.isExitPhrase
import app.marmalade.android.chat.PromptAck
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.chat.messages.text
import app.marmalade.android.ui.home.MascotExpression
import app.marmalade.android.ui.home.MascotImage
import app.marmalade.android.ui.voice.VoicePopupUI
import app.marmalade.android.ui.theme.MarmaladeAssistantTheme

// ── Voice message model for mini chat ────────────────────────────────────────

data class VoiceMessage(
    val text: String,
    val isUser: Boolean,
    val isPartial: Boolean = false,
)

// ── Assistant state ──────────────────────────────────────────────────────────

enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    THINKING,
    PREPARING_SPEECH,
    SPEAKING,
    ERROR
}

/** What a wake-word re-trigger should do when the popup is already open. */
internal enum class WakeReentryAction { BARGE_IN, IGNORE, START }

/**
 * Pure decision for a wake word (or assist gesture) fired while the voice popup
 * is ALREADY open — pulled out of [MarmaladeVoiceSession.handleWakeReentry] so
 * it's unit-testable without the VoiceInteractionSession stack (same pattern as
 * [harvestVoiceReply] and [app.marmalade.android.service.HotwordService.shouldTrigger]).
 *
 * A wake word always means "listen to me now", never "submit what I've said"
 * (that is the mic button's LISTENING tap). So a mid-reply turn barges in
 * (stop TTS / abort the run / re-listen); an already-listening popup ignores
 * the redundant trigger rather than tearing down a live mic; an idle or errored
 * popup simply starts a fresh listen.
 */
internal fun wakeReentryAction(state: AssistantState): WakeReentryAction = when (state) {
    AssistantState.SPEAKING,
    AssistantState.PREPARING_SPEECH,
    AssistantState.THINKING,
    AssistantState.PROCESSING -> WakeReentryAction.BARGE_IN
    AssistantState.LISTENING -> WakeReentryAction.IGNORE
    AssistantState.IDLE,
    AssistantState.ERROR -> WakeReentryAction.START
}

/**
 * Pure reply-harvest predicate for the voice turn, pulled out of
 * [MarmaladeVoiceSession.sendToGateway] so it's unit-testable without the
 * Android VoiceInteractionSession / TTS / STT stack (same pattern as
 * `computeAttachmentsSupported`).
 *
 * Correlates the reply to the SUBMITTED turn by seq: a valid reply is a
 * finalized, non-errored assistant bubble WITH TEXT whose `seq` is strictly
 * greater than [seqFloor] — the seq the prompt.submit ack minted for OUR user
 * message (see [awaitVoiceReply]). The daemon assigns seq monotonically, so
 * the reply to this turn is the only assistant text that can land above it.
 *
 * Why the ack seq, not the pre-submit local max: the daemon replays
 * `seq > MAX(local serverSeq)` on a reconnect subscribe — so when Room is
 * BEHIND the server at popup-open (the reconnect case), a replayed OLD reply
 * carries a seq above the local max and a local-max floor would speak it.
 * The ack seq is server-truth: everything at or below it predates our turn,
 * whatever the local cache had. (This also closes the cross-device hole —
 * another device's earlier turn can't land above OUR submit's seq.)
 *
 * The error guard: a server-error turn finalizes with its truncated partial
 * text still attached; speaking half a sentence as if it were the answer is
 * worse than the error state. Requires `error` to survive the Room round-trip
 * (MessageEntity.error, DB v21) — the chat view derives entirely from Room.
 *
 * Returns the LATEST qualifying reply's text (a tool-heavy agent turn finalizes
 * several assistant messages within the turn; the spoken answer is the last
 * one), or null when no reply for this turn has finalized yet.
 */
internal fun harvestVoiceReply(messages: List<ChatMessage>, seqFloor: Long): String? =
    messages.lastOrNull {
        it.role == ChatRole.Assistant && !it.pending && it.error == null &&
            it.seq > seqFloor && it.text().isNotBlank()
    }?.text()

/** Result of [awaitVoiceReply]: the reply to speak (null = none arrived) and
 *  whether the turn still showed life when the wait ended (drives the
 *  "reply will land in the chat tab" message vs. the generic error). */
internal data class VoiceReplyWait(val replyText: String?, val turnStillRunning: Boolean)

/**
 * The voice popup's reply wait, extracted from [MarmaladeVoiceSession] so the
 * whole loop — ack-floor resolution + harvest + liveness/grace/hard-cap — is
 * drivable by digital-twin tests on a virtual clock.
 *
 * Floor resolution: wait on [acks] for the [PromptAck] whose outboxId matches
 * OUR submit ([outboxId]); its seq is the harvest floor. Until the ack lands
 * nothing is harvested — before the server has numbered our user message,
 * NO assistant text can be proven to answer it (replayed history could land
 * above any locally-derived floor; see [harvestVoiceReply]). A seq-0 ack
 * (legacy gateway, no server ids) falls back to [localSeqFloor], the
 * pre-submit local max — the old behavior, best available without an ack.
 *
 * Liveness: not a fixed timeout — tool-heavy turns legitimately run for
 * minutes. Each poll refreshes a [graceMs] window while [isTurnAlive] reports
 * life (streaming flag, server-reported running, a pending assistant bubble);
 * the wait gives up when the grace window drains with no life, or at the
 * absolute [hardCapMs]. An ack that never arrives (offline submit) rides the
 * same grace window — no reply can exist for an unsent prompt.
 */
internal suspend fun awaitVoiceReply(
    acks: Flow<PromptAck>,
    outboxId: String,
    messages: StateFlow<List<ChatMessage>>,
    localSeqFloor: Long,
    isTurnAlive: () -> Boolean,
    elapsedMs: () -> Long,
    pollMs: Long,
    graceMs: Long,
    hardCapMs: Long,
): VoiceReplyWait {
    val startedAt = elapsedMs()
    var graceLeftMs = graceMs
    var seqFloor: Long? = null
    var turnStillRunning = false
    while (elapsedMs() - startedAt < hardCapMs) {
        if (seqFloor == null) {
            val ack = withTimeoutOrNull(pollMs) { acks.first { it.outboxId == outboxId } }
            if (ack != null) {
                seqFloor = if (ack.seq > 0L) ack.seq else localSeqFloor
            }
        }
        // Harvest in the SAME iteration the ack resolved — no extra poll lag.
        val floor = seqFloor
        if (floor != null) {
            val finalized = withTimeoutOrNull(pollMs) {
                val ready = messages.first { harvestVoiceReply(it, floor) != null }
                harvestVoiceReply(ready, floor)
            }
            if (finalized != null) {
                return VoiceReplyWait(replyText = finalized, turnStillRunning = true)
            }
        }
        val alive = isTurnAlive()
        turnStillRunning = alive
        if (alive) {
            graceLeftMs = graceMs
        } else {
            graceLeftMs -= pollMs
            if (graceLeftMs <= 0) break
        }
    }
    return VoiceReplyWait(replyText = null, turnStillRunning = turnStillRunning)
}

/**
 * Voice Interaction Session
 *
 * Redesigned bottom-sheet popup with mini chat bubbles, reactive mascot,
 * amber mic button with pulsing glow, and conversation mode toggle.
 * Lifecycle management (LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner)
 * is preserved from the original implementation.
 */
class MarmaladeVoiceSession(context: Context) : VoiceInteractionSession(context),
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    companion object {
        private const val TAG = "MarmaladeVoiceSession"

        /**
         * Interval for re-requesting the mic token while STT is active. Must be
         * shorter than [MicOwnershipManager.SAFETY_NET_TIMEOUT_MS] so a healthy
         * long session keeps refreshing the safety net before it can fire.
         */
        private const val MIC_KEEPALIVE_INTERVAL_MS = 60_000L

        /**
         * Pre-listen pause in [onShow] before the recognizer is started. NOT
         * a mic-handoff window — that lives inside [startListening] as
         * [MIC_HANDOFF_DELAY_MS], after the requestMic call. This delay
         * lets the popup-show animation, activation chirp, and haptic
         * settle so the recognizer doesn't capture them as the first STT
         * frames.
         */
        private const val POPUP_SETTLE_DELAY_MS = 150L

        /**
         * Settle window between `requestMic(VOICE_SESSION)` and recognizer
         * construction. Gives `HotwordService`'s `currentOwner` collector
         * time to run `engine.stop()` so the two AudioRecords never coexist
         * (voice.md / ADR 0008: AudioRecord is single-owner).
         */
        private const val MIC_HANDOFF_DELAY_MS = 50L

        /**
         * A mic-token denial is usually transient (inline STT wrapping up, or
         * KWS mid-handoff), so retry briefly before surfacing the error state
         * — a hard fail on the first attempt made conversation-mode restarts
         * flaky right after a fast turn.
         */
        private const val MIC_REQUEST_ATTEMPTS = 3
        private const val MIC_RETRY_DELAY_MS = 150L

        /**
         * Safety-cap for patient-mode listening. After this window the
         * listening job is cancelled even if the user hasn't said a
         * termination word. Defends against a wedged STT engine holding
         * the mic until the manager's 90 s safety net force-reclaims it.
         */
        private const val PATIENT_MODE_SAFETY_TIMEOUT_MS = 300_000L

        /**
         * Pause before auto-restarting STT after the previous turn closed.
         * Briefly relaxes the mic so any tail audio drains and the
         * recognizer doesn't immediately re-pick the assistant's first
         * syllables.
         */
        private const val AUTO_LISTEN_RESTART_DELAY_MS = 200L

        /**
         * Pause between the ready chirp after a spoken turn and the STT
         * restart in conversation mode — lets the TTS/chirp audio tail drain
         * so the recognizer doesn't transcribe the assistant's own output.
         */
        private const val AUTO_LISTEN_TAIL_DRAIN_MS = 300L

        /** Duration (ms) for the activation haptic vibration on popup show. */
        private const val ACTIVATION_VIBRATION_MS = 50L

        /**
         * Seq floor the streaming feeder uses before the prompt.submit ack
         * arrives. MAX_VALUE blocks every finalized row (including reconnect
         * replays, which is the whole reason finalized rows are floor-gated —
         * see [collectSpeakableChunks]) while still letting the live pending
         * bubble speak: pending bubbles carry seq 0 and are validated by
         * preexisting-id exclusion, never by the floor. This is what lets the
         * feeder start on the first delta instead of waiting a network RTT.
         */
        private const val PRE_ACK_SEQ_FLOOR = Long.MAX_VALUE

        /** How often [sendToGateway]'s reply wait re-checks turn liveness. */
        private const val VOICE_TURN_POLL_MS = 1_000L

        /**
         * How long the reply wait tolerates NO liveness signal (no client
         * streaming flag, no server-reported running, no pending assistant
         * bubble) before giving up. Refreshed every time life is observed,
         * so it bounds both "the turn never started" and "the turn died
         * mid-flight" without capping a healthy long turn.
         */
        private const val VOICE_TURN_LIVENESS_GRACE_MS = 30_000L

        /**
         * Absolute ceiling on the reply wait. A tool-heavy agent turn can
         * legitimately run for minutes (the old fixed 60s cap misreported
         * those as "something went wrong" while the run was still going);
         * past this ceiling the popup stops holding THINKING and points
         * the user at the chat tab, where the reply still lands.
         */
        private const val VOICE_TURN_HARD_CAP_MS = 300_000L
    }

    private val settings = SettingsRepository.getInstance(context)
    private val micOwnership = MicOwnershipManager.getInstance(context)
    // Cached MarmaladeRuntime accessor — was repeated five+ times across the
    // class with at least one shadowed re-declaration inside scope.launch.
    // The runtime is a process-wide singleton, so caching it on the session
    // is a pure-readability win with no lifecycle implications.
    private val marmaladeRuntime by lazy {
        (context.applicationContext as MarmaladeApplication).marmaladeRuntime
    }
    private lateinit var sttProvider: STTEngineProvider
    private lateinit var ttsManager: TTSManager
    private lateinit var soundManager: SoundManager

    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI State
    private var assistantState = mutableStateOf(AssistantState.IDLE)
    private var voiceMessages = mutableStateListOf<VoiceMessage>()
    private var autoListenEnabled = mutableStateOf(false)
    private var popupVisible = mutableStateOf(false)
    private var errorMessage = mutableStateOf<String?>(null)

    // Voice session routing
    private var resolvedSessionKey: String? = null

    // WakeLock to keep CPU alive during voice conversation when screen is off
    private var wakeLock: PowerManager.WakeLock? = null

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)

    // AudioFocus management
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // Must implement ViewModelStoreOwner for Compose
    override val viewModelStore: androidx.lifecycle.ViewModelStore = androidx.lifecycle.ViewModelStore()

    private var listeningJob: Job? = null
    private var gatewayJob: Job? = null

    override fun onCreate() {
        Log.d(TAG, "Session onCreate start")
        super.onCreate()

        // Initialize lifecycle and saved state here (once per session lifetime)
        try {
            savedStateRegistryController.performAttach()
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already attached?", e)
        }

        try {
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already restored?", e)
        }

        try {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "Lifecycle ON_CREATE failed", e)
        }

        sttProvider = STTEngineProvider.getInstance(context)
        // Preload STT model in background so first voice session starts instantly
        scope.launch(Dispatchers.IO) { sttProvider.warmup() }
        ttsManager = TTSManager(context)
        soundManager = SoundManager.getInstance(context)
        // Don't log ttsManager.isReady() here: AndroidTTSProvider's engine init
        // is async (50-300ms), so a synchronous readiness probe at session
        // create always races to ready=false + a misleading error string even
        // though speech works (speakWithProgress awaits init internally).
        Log.d(TAG, "Session TTS provider created (engine init is async)")
    }

    // BackHandler inside VoicePopupUI reads LocalOnBackPressedDispatcherOwner.
    // A VoiceInteractionSession's ComposeView doesn't inherit one from the
    // framework the way an Activity does, so pre-fix the popup crashed on
    // first show with `IllegalStateException("No OnBackPressedDispatcherOwner
    // was provided")`. Supplying our own owner via CompositionLocalProvider
    // lets BackHandler register cleanly. Session dismiss still routes
    // through `finish()` — either via the popup's own onDismiss (X, scrim)
    // or via the framework's default back handling on the underlying
    // session dialog — so registered dispatcher callbacks don't need to
    // actually fire for the popup to close.
    private val backPressedOwner: OnBackPressedDispatcherOwner by lazy {
        object : OnBackPressedDispatcherOwner {
            private val dispatcher = OnBackPressedDispatcher(fallbackOnBackPressed = { finish() })
            override val onBackPressedDispatcher: OnBackPressedDispatcher get() = dispatcher
            override val lifecycle: Lifecycle get() = this@MarmaladeVoiceSession.lifecycle
        }
    }

    override fun onCreateContentView(): View {
        Log.d(TAG, "Session onCreateContentView")
        val composeView = ComposeView(context).apply {
            // Set ViewTree owners using extensions
            try {
                setViewTreeLifecycleOwner(this@MarmaladeVoiceSession)
                setViewTreeViewModelStoreOwner(this@MarmaladeVoiceSession)
                setViewTreeSavedStateRegistryOwner(this@MarmaladeVoiceSession)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ViewTree owners", e)
            }

            setContent {
                CompositionLocalProvider(
                    LocalOnBackPressedDispatcherOwner provides backPressedOwner,
                ) {
                    MarmaladeAssistantTheme {
                        // shutdownHint was a gateway-side restart/shutdown reason
                        // OpenClaw could push out-of-band; marmalade has no
                        // equivalent event, so the popup falls back to the
                        // local errorMessage exclusively.
                        val effectiveError = errorMessage.value
                        // Patient mode listens until a termination word — a
                        // word the user previously had to just KNOW. Surface
                        // the first configured one while listening.
                        val listeningHint = if (settings.patientListeningEnabled) {
                            settings.getTerminationWordsList().firstOrNull()?.let {
                                context.getString(R.string.voice_termination_hint, it)
                            }
                        } else null
                        VoicePopupUI(
                            state = assistantState.value,
                            messages = voiceMessages,
                            autoListenEnabled = autoListenEnabled.value,
                            isVisible = popupVisible.value,
                            errorMessage = effectiveError,
                            listeningHint = listeningHint,
                            onMicClick = { onMicButtonClicked() },
                            onAutoListenToggle = {
                                autoListenEnabled.value = it
                                settings.conversationModeEnabled = it
                            },
                            onDismiss = { finish() },
                        )
                    }
                }
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // Recreate scope if it was cancelled by a previous onHide()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        // A re-show while the popup is ALREADY up is a wake-word (or assist
        // gesture) barge-in mid-conversation — the framework calls onShow()
        // again on the live session. Interrupt and listen; do NOT run the
        // fresh-show reset below (which would clear the mini chat and start a
        // second STT turn over the still-playing TTS). Fresh shows have
        // popupVisible == false (cleanupSession clears it) and fall through.
        if (popupVisible.value) {
            handleWakeReentry()
            return
        }

        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

        // Start foreground service to keep process alive during conversation (screen off)
        SessionForegroundService.start(context)

        Log.d(TAG, "Session shown with flags: $showFlags")

        // Reset popup state for new session. Conversation mode (auto-listen)
        // is a persisted preference — resetting it to OFF every session forced
        // the user to re-flip the toggle on each popup open.
        voiceMessages.clear()
        autoListenEnabled.value = settings.conversationModeEnabled
        errorMessage.value = null
        assistantState.value = AssistantState.IDLE

        // Show popup with animation
        popupVisible.value = true

        // Check settings
        if (!settings.isConfigured()) {
            assistantState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_config_required)
            return
        }

        // Fail fast if the gateway is not healthy
        if (!marmaladeRuntime.chatHealthOk.value) {
            assistantState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_gateway_not_connected)
            return
        }

        // Play activation sound + haptic vibration + start listening
        soundManager.playActivation()
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(
                    ACTIVATION_VIBRATION_MS,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ACTIVATION_VIBRATION_MS)
        }

        // Resolve voice session and start STT.
        //
        // Order matters: resolution may suspend on a gateway RPC
        // (sessions.patch) when creating the dedicated "marmalade"
        // session for the first time. We resolve BEFORE starting the
        // recognizer so the session is in place by the time the user
        // starts speaking. If resolution fails we still open the popup;
        // the user will hit the existing "no session" error path if
        // they try to send.
        scope.launch {
            val key = resolveVoiceSession()
            if (key != null) {
                resolvedSessionKey = key
                marmaladeRuntime.switchChatSession(key)
            }
            // Short pre-listen pause. This is NOT the mic-handoff window —
            // that has moved into startListening() (delay(50) AFTER its
            // requestMic(VOICE_SESSION) call). This delay just gives the
            // popup-show animation, activation sound, and haptic vibration
            // (~50 ms above) a moment to land before the recognizer takes
            // over the audio path — keeps the activation chirp from
            // bleeding into the first STT frames.
            delay(POPUP_SETTLE_DELAY_MS)
            Log.d(TAG, "Popup settled, starting STT engine")
            startListening()
        }
    }

    override fun onHide() {
        super.onHide()
        // Dismissing the popup ends the voice conversation: tear down STT,
        // TTS, the gateway wait, audio focus, the wake-lock, and the
        // foreground service. The popup previously kept a voice-active
        // session alive for screen-off use, but a hidden conversation that
        // then stalled (e.g. the gateway died mid-run) leaked those
        // resources with no path back to cleanup.
        cleanupSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)

        // Release the mic token (safety). Guarded no-op if cleanupSession()
        // already released it; the manager then restarts KWS.
        micOwnership.releaseMic(MicOwner.VOICE_SESSION)

        SessionForegroundService.stop(context)
        ttsManager.shutdown()
        releaseWakeLock()
    }

    // ── Voice session routing ────────────────────────────────────────────────

    /**
     * Voice routes into THE daemon-managed main session (`session.main`) — the
     * same session Home binds to (see [resolveVoiceSessionKey]). It is never
     * user-selectable; the daemon owns it. Null before the runtime has
     * resolved a main id (disconnected boot); the popup still opens and the
     * user hits the standard "no session" error on send.
     */
    private fun resolveVoiceSession(): String? {
        val key = resolveVoiceSessionKey(marmaladeRuntime.mainSessionKey.value)
        if (key == null) {
            Log.w(TAG, "Voice session: main session not resolved yet (gateway not connected)")
        } else {
            Log.d(TAG, "Voice session: routing to main session $key")
        }
        return key
    }

    // ── Mic button handler ───────────────────────────────────────────────────

    private fun onMicButtonClicked() {
        when (assistantState.value) {
            AssistantState.IDLE, AssistantState.ERROR -> startListening()
            AssistantState.LISTENING -> {
                // Grab accumulated text before cancelling STT
                val partialMsg = voiceMessages.lastOrNull { it.isUser && it.isPartial }
                errorMessage.value = null
                assistantState.value = AssistantState.PROCESSING
                listeningJob?.cancel()

                if (partialMsg != null && partialMsg.text.isNotBlank()) {
                    // Send the accumulated text
                    val text = settings.extractTerminationWord(partialMsg.text)?.first
                        ?: partialMsg.text
                    finalizeUserMessage(text)
                    sendToGateway(text)
                } else {
                    // Nothing spoken — just dismiss
                    assistantState.value = AssistantState.IDLE
                }
            }
            AssistantState.SPEAKING, AssistantState.PREPARING_SPEECH -> {
                // Stop speech. With streaming TTS the reply may still be
                // generating — cancel the whole turn job (it owns the speaker
                // and feeder) and, if the run is still in flight, abort it
                // scoped to this session so the daemon stops producing an
                // answer the user just cut off.
                gatewayJob?.cancel()
                gatewayJob = null
                ttsManager.stop()
                voiceMessages.removeAll { !it.isUser && it.isPartial }
                if (marmaladeRuntime.chat.isStreaming.value) {
                    resolvedSessionKey?.let { marmaladeRuntime.chat.abort() }
                }
                errorMessage.value = null
                if (autoListenEnabled.value) {
                    // Auto mode: restart listening
                    assistantState.value = AssistantState.IDLE
                    scope.launch { delay(AUTO_LISTEN_RESTART_DELAY_MS); startListening() }
                } else {
                    abandonAudioFocus()
                    assistantState.value = AssistantState.IDLE
                }
            }
            AssistantState.THINKING, AssistantState.PROCESSING -> {
                // Stop everything: TTS (in case of overlap), gateway wait,
                // abort the run — but ONLY runs in this voice session.
                // chat.abort() (no args) aborts every non-terminal run
                // process-wide, which would silently kill background runs
                // the user deliberately started in other chats. The voice
                // popup must stay scoped to its own session.
                ttsManager.stop()
                errorMessage.value = null
                gatewayJob?.cancel()
                gatewayJob = null
                voiceMessages.removeAll { !it.isUser && it.isPartial }
                resolvedSessionKey?.let { marmaladeRuntime.chat.abort() }
                assistantState.value = AssistantState.IDLE
            }
        }
    }

    // ── Wake-word barge-in ───────────────────────────────────────────────────

    /**
     * A wake word (or assist gesture) fired while the popup was already open
     * (routed here from [onShow] on a re-show). Route by current state via
     * [wakeReentryAction]. Unlike [onShow]'s fresh path this never clears the
     * mini chat or re-resolves the session — the conversation continues.
     */
    private fun handleWakeReentry() {
        when (wakeReentryAction(assistantState.value)) {
            WakeReentryAction.BARGE_IN -> {
                Log.i(TAG, "Wake barge-in (state=${assistantState.value}): stopping reply, re-listening")
                // Mirror the mic-button stop path, but ALWAYS re-listen — a wake
                // word is an interrupt-and-talk, not a submit.
                gatewayJob?.cancel()
                gatewayJob = null
                ttsManager.stop()
                voiceMessages.removeAll { !it.isUser && it.isPartial }
                // Abort UNCONDITIONALLY (not isStreaming-guarded): BARGE_IN also
                // covers THINKING/PROCESSING, where the run can be live but
                // pre-stream (isStreaming only flips on message.start /
                // run_state=running). A guarded abort would spare that run, and
                // its reply — minted at a seq above the follow-up's floor — could
                // be spoken as the answer to the follow-up. abort() is scoped to
                // the bound session (session.interrupt on _sessionId) and is a
                // harmless no-op with no active run; matches the mic-button
                // THINKING branch.
                resolvedSessionKey?.let { marmaladeRuntime.chat.abort() }
                errorMessage.value = null
                soundManager.playActivation()
                assistantState.value = AssistantState.IDLE
                // Brief tail-drain so the recognizer doesn't catch the TTS/chirp
                // it just cut off (same delay as the auto-listen restart).
                scope.launch { delay(AUTO_LISTEN_RESTART_DELAY_MS); ensureSessionResolvedThenListen() }
            }
            WakeReentryAction.IGNORE -> {
                Log.d(TAG, "Wake re-trigger ignored — already listening")
            }
            WakeReentryAction.START -> {
                Log.i(TAG, "Wake re-trigger from ${assistantState.value} — starting listen")
                soundManager.playActivation()
                ensureSessionResolvedThenListen()
            }
        }
    }

    /**
     * Re-resolve + bind the main session if the fresh show never did (popup
     * opened while the gateway was down, then recovered) before listening, so a
     * barged-in / re-triggered turn still lands in `session.main` rather than
     * whatever session the ChatController was last bound to. No-op once
     * resolved. [resolveVoiceSession] and [MarmaladeRuntime.switchChatSession]
     * are both non-suspending, so this runs inline before [startListening].
     */
    private fun ensureSessionResolvedThenListen() {
        if (resolvedSessionKey == null) {
            resolveVoiceSession()?.let { key ->
                resolvedSessionKey = key
                marmaladeRuntime.switchChatSession(key)
            }
        }
        startListening()
    }

    // ── STT listening ────────────────────────────────────────────────────────

    /**
     * [useServerFallback] = this listen runs on [ServerRecognizer] (daemon
     * audio.transcribe) because the on-device recognizer just errored. Set
     * only by [maybeFallBackToServerStt]; every normal (re)start uses the
     * default false so on-device STT stays primary.
     */
    private fun startListening(useServerFallback: Boolean = false) {
        Log.d(TAG, "startListening() called, currentState=${assistantState.value}, serverFallback=$useServerFallback")
        listeningJob?.cancel()
        acquireWakeLock()

        assistantState.value = AssistantState.PROCESSING
        errorMessage.value = null

        // Request audio focus
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ).build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }

        val patientMode = settings.patientListeningEnabled
        val enableEndpoint = !patientMode
        val recognizer: StreamingRecognizer = if (useServerFallback) {
            ServerRecognizer(
                silenceMs = (vadSliderToSilenceDuration(settings.vadSensitivity) * 1000).toLong(),
                transcribe = { wav -> marmaladeRuntime.marmaladeRpc.audioTranscribe(wav).transcript },
            )
        } else {
            sttProvider.getRecognizer()
        }
        Log.i(TAG, "Starting STT (${recognizer::class.simpleName}, patientMode=$patientMode, enableEndpoint=$enableEndpoint)")

        listeningJob = scope.launch {
            // Acquire the mic token for every listening turn — not just the
            // first. In auto-listen mode the previous turn's recognizer
            // onFlowClosed already released VOICE_SESSION (KWS reclaimed the
            // mic), so each restart must re-acquire. KWS is preemptable so
            // this is granted unless inline STT holds the mic.
            var micGranted = micOwnership.requestMic(MicOwner.VOICE_SESSION)
            var attempt = 1
            while (!micGranted && attempt < MIC_REQUEST_ATTEMPTS) {
                Log.w(TAG, "Mic busy (attempt $attempt/$MIC_REQUEST_ATTEMPTS), retrying")
                delay(MIC_RETRY_DELAY_MS)
                micGranted = micOwnership.requestMic(MicOwner.VOICE_SESSION)
                attempt++
            }
            if (!micGranted) {
                Log.w(TAG, "Mic busy — another STT consumer holds it; cannot start listening")
                assistantState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_mic_busy)
                return@launch
            }
            // Settle window so HotwordService's currentOwner collector runs
            // engine.stop() before the recognizer builds its AudioRecord
            // (voice.md / ADR 0008: AudioRecord is single-owner).
            delay(MIC_HANDOFF_DELAY_MS)

            val safetyTimeout = if (patientMode) {
                launch {
                    delay(PATIENT_MODE_SAFETY_TIMEOUT_MS)
                    Log.w(TAG, "Patient listening safety timeout (5 min)")
                    listeningJob?.cancel()
                }
            } else null

            // Keep-alive heartbeat: while the STT flow is alive, re-request the
            // mic every 60s. The manager's idempotent branch refreshes its 90s
            // safety net, so a healthy long (patient-mode) session is not
            // force-released. This child coroutine lives inside listeningJob —
            // when listeningJob is cancelled the heartbeat dies with it, so a
            // *stuck* session stops pinging and the net reclaims the mic ~90s
            // after the stall. It must NOT be hoisted to a longer-lived scope.
            launch {
                while (isActive) {
                    delay(MIC_KEEPALIVE_INTERVAL_MS)
                    micOwnership.requestMic(MicOwner.VOICE_SESSION)
                }
            }

            try {
                recognizer.startStreaming(
                    enableEndpoint = enableEndpoint,
                    onFlowClosed = {
                        Log.d(TAG, "STT flow closed, releasing mic token")
                        micOwnership.releaseMic(MicOwner.VOICE_SESSION)
                    },
                ).collect { result ->
                    when (result) {
                        is StreamingResult.Ready -> {
                            Log.d(TAG, "STT engine ready, transitioning to LISTENING")
                            soundManager.playReady()
                            assistantState.value = AssistantState.LISTENING
                        }
                        is StreamingResult.PartialText -> {
                            // Check exit phrases
                            if (isExitPhrase(result.text)) {
                                safetyTimeout?.cancel()
                                finish()
                                listeningJob?.cancel()
                                return@collect
                            }

                            // Check for termination word (patient mode)
                            if (patientMode) {
                                val termMatch = settings.extractTerminationWord(result.text)
                                if (termMatch != null) {
                                    val (remaining, word) = termMatch
                                    Log.i(TAG, "Termination word '$word' detected in streaming text")
                                    recognizer.stopStreaming()
                                    safetyTimeout?.cancel()
                                    if (remaining.isNotBlank()) {
                                        finalizeUserMessage(remaining)
                                        sendToGateway(remaining)
                                    } else {
                                        finish()
                                    }
                                    listeningJob?.cancel()
                                    return@collect
                                }
                            }

                            // Update UI with partial text
                            updatePartialUserMessage(result.text)
                        }
                        is StreamingResult.FinalText -> {
                            // Fires when VAD detects end-of-speech (enableEndpoint=true / normal mode)
                            if (isExitPhrase(result.text)) {
                                finish()
                                listeningJob?.cancel()
                                return@collect
                            }
                            // Stop the mic BEFORE sending to gateway.
                            // The recognizer must fully release the mic before TTS plays,
                            // otherwise it transcribes TTS output (feedback loop).
                            // Auto-listen restarts STT after TTS completes in speakResponse().
                            recognizer.stopStreaming()
                            safetyTimeout?.cancel()
                            finalizeUserMessage(result.text)
                            sendToGateway(result.text)
                            listeningJob?.cancel()
                            return@collect
                        }
                        is StreamingResult.Error -> {
                            if (maybeFallBackToServerStt(useServerFallback, result.message)) {
                                listeningJob?.cancel()
                                return@collect
                            }
                            // Only show error if we're still in LISTENING state
                            // (not if user already stopped via mic tap)
                            if (assistantState.value == AssistantState.LISTENING) {
                                Log.e(TAG, "STT error: ${result.message}")
                                assistantState.value = AssistantState.ERROR
                                errorMessage.value = result.message
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "STT failed", e)
                    if (!maybeFallBackToServerStt(useServerFallback, e.message)) {
                        assistantState.value = AssistantState.ERROR
                        errorMessage.value = e.message
                    }
                }
            }
        }
    }

    /**
     * On-device STT just failed. When the daemon can transcribe server-side
     * ("transcription" hello feature — advertised only when an STT command
     * actually resolves on its host) and this listen wasn't already the
     * fallback, restart on [ServerRecognizer] instead of surfacing the error.
     * Returns true when the fallback was taken.
     */
    private fun maybeFallBackToServerStt(useServerFallback: Boolean, reason: String?): Boolean {
        if (useServerFallback) return false
        if (!marmaladeRuntime.transcriptionSupported.value) return false
        Log.w(TAG, "On-device STT failed ($reason); falling back to server transcription")
        scope.launch { startListening(useServerFallback = true) }
        return true
    }

    // ── Mini chat message management ─────────────────────────────────────────

    private fun updatePartialUserMessage(text: String) {
        if (text.isBlank()) return
        val lastIndex = voiceMessages.indexOfLast { it.isUser && it.isPartial }
        val msg = VoiceMessage(text = text, isUser = true, isPartial = true)
        if (lastIndex >= 0) {
            voiceMessages[lastIndex] = msg
        } else {
            voiceMessages.add(msg)
        }
    }

    /** Live transcript while the reply streams — replaces the "..." thinking
     *  placeholder (both are the trailing partial assistant row). */
    private fun updatePartialAssistantMessage(text: String) {
        if (text.isBlank()) return
        val lastIndex = voiceMessages.indexOfLast { !it.isUser && it.isPartial }
        val msg = VoiceMessage(text = text, isUser = false, isPartial = true)
        if (lastIndex >= 0) {
            voiceMessages[lastIndex] = msg
        } else {
            voiceMessages.add(msg)
        }
    }

    private fun finalizeUserMessage(text: String) {
        val lastIndex = voiceMessages.indexOfLast { it.isUser && it.isPartial }
        val msg = VoiceMessage(text = text, isUser = true, isPartial = false)
        if (lastIndex >= 0) {
            voiceMessages[lastIndex] = msg
        } else {
            voiceMessages.add(msg)
        }
    }

    private fun addAssistantMessage(text: String) {
        // The popup bubble is a plain Text composable (no markdown renderer),
        // so raw **bold**/`code` markers would show literally. Strip them the
        // same way the TTS path does; the full formatted reply lives in the
        // chat tab.
        val display = TTSUtils.stripMarkdownForSpeech(text)
        voiceMessages.add(VoiceMessage(text = display, isUser = false, isPartial = false))
    }

    // ── Gateway send + response collection ───────────────────────────────────

    private fun sendToGateway(message: String) {
        Log.i(TAG, "Sending message via ChatController: '${message.take(80)}'")
        assistantState.value = AssistantState.PROCESSING

        gatewayJob = scope.launch {
            if (!marmaladeRuntime.chatHealthOk.value) {
                assistantState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_gateway_not_connected)
                return@launch
            }

            try {
                // Correlate the reply to THIS turn by seq. The REAL floor is
                // the prompt.submit ack's server-minted seq (awaited inside
                // awaitVoiceReply via promptAcks); this local pre-submit max
                // is only the fallback for a seq-0 legacy ack. A local-max
                // floor alone is NOT safe: on reconnect the daemon replays
                // seq > MAX(local serverSeq), so with Room behind the server
                // a replayed OLD reply lands above the local max and would be
                // spoken. See [harvestVoiceReply] / [awaitVoiceReply].
                val localSeqFloor = marmaladeRuntime.chat.messages.value.maxOfOrNull { it.seq } ?: 0L

                // Route through ChatController.sendMessage — the SAME reliable
                // path the chat tab uses. onShow already bound the controller
                // to resolvedSessionKey via switchChatSession, so this:
                //  - resolves the correct LIVE server session id
                //    (ensureServerSessionId). The old direct
                //    sendChatToSession(storedKey) → promptSubmit(storedKey)
                //    hit a session the gateway's live registry didn't know,
                //    so no reply ever came back and the popup hung on
                //    "Thinking…" forever.
                //  - forwards voiceOrigin=true → source=voice for concise,
                //    TTS-safe replies (sendChatToSession silently dropped it).
                //  - persists + streams the response through chat.messages,
                //    which we await below.
                val handle = marmaladeRuntime.chat.sendMessage(
                    message = message,
                    thinkingLevel = marmaladeRuntime.chat.thinkingLevel.value,
                    voiceOrigin = true,
                )
                if (handle == null) {
                    // Blank send — can't happen from the STT paths (they
                    // guard on non-blank text), but don't hang THINKING on it.
                    assistantState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_no_response)
                    return@launch
                }

                // Show thinking state while waiting for response
                assistantState.value = AssistantState.THINKING
                voiceMessages.add(VoiceMessage(text = "...", isUser = false, isPartial = true))

                // ── Streaming speech (upstream use-voice-conversation parity):
                // speak sentence chunks as the reply streams instead of holding
                // THINKING for the whole generation. The feeder watches
                // chat.messages for THIS turn's assistant output (same
                // seq-floor correlation as the finalize wait below, see
                // collectSpeakableChunks) and queues chunks; the speaker plays
                // them sequentially; the mini chat shows the live transcript.
                // awaitVoiceReply stays the single owner of finalize/timeout
                // semantics. The voice assistant ALWAYS speaks its reply —
                // the chat tab's auto-speak flag (settings.chatTtsEnabled)
                // exists precisely so nothing can mute this path.
                // Voice turns bypass the 33 ms delta render-coalescing so the
                // feeder sees sentence boundaries the moment they arrive off
                // the wire (reset in the finally below — chat-only streaming
                // keeps the batched default).
                marmaladeRuntime.chat.setImmediateDeltaFlush(true)
                val preexistingIds = marmaladeRuntime.chat.messages.value.map { it.id }.toSet()
                val consumed = mutableMapOf<String, Int>()
                var ackFloor: Long? = null
                var anyChunkSpoken = false
                var anyChunkFailed = false
                val speechQueue = Channel<String>(Channel.UNLIMITED)
                val speaker = launch {
                    for (chunk in speechQueue) {
                        if (speakChunk(chunk)) anyChunkSpoken = true else anyChunkFailed = true
                    }
                }
                val feeder = launch {
                    // Optimistic start: collect from the first delta instead of
                    // suspending a full network RTT on the ack. Safe because the
                    // interim PRE_ACK_SEQ_FLOOR blocks every finalized row (the
                    // replay-hazard class) while the live pending bubble — the
                    // source of the first speakable sentence — never consults
                    // the floor at all. The ack re-anchors the floor for
                    // finalized rows; the shared consumed map carries offsets
                    // across the switch, so nothing double-speaks.
                    launch {
                        val ack = marmaladeRuntime.chat.promptAcks.first { it.outboxId == handle.outboxId }
                        ackFloor = if (ack.seq > 0L) ack.seq else localSeqFloor
                    }
                    marmaladeRuntime.chat.messages.collect { msgs ->
                        collectSpeakableChunks(msgs, ackFloor ?: PRE_ACK_SEQ_FLOOR, preexistingIds, consumed)
                            .forEach { speechQueue.trySend(it) }
                        msgs.lastOrNull {
                            it.role == ChatRole.Assistant && it.pending && it.id !in preexistingIds
                        }?.let {
                            updatePartialAssistantMessage(TTSUtils.stripMarkdownForSpeech(it.text()))
                        }
                    }
                }

                // Wait for THIS turn's finalized reply: floor = the
                // prompt.submit ack's seq, correlated by outbox id. Not a
                // fixed timeout — tool-heavy agent turns regularly run past
                // a minute, and the old 60s cap reported "something went
                // wrong" while the run was still in flight (maintainer, on-device
                // 2026-07-04); liveness-refreshed grace under a hard cap.
                val wait = awaitVoiceReply(
                    acks = marmaladeRuntime.chat.promptAcks,
                    outboxId = handle.outboxId,
                    messages = marmaladeRuntime.chat.messages,
                    localSeqFloor = localSeqFloor,
                    isTurnAlive = {
                        // Client-side streaming flag (message.start →
                        // complete), server-reported per-session running flag
                        // (session.info), or a pending assistant bubble in
                        // Room. Any one means the turn is still in flight.
                        val boundSid = marmaladeRuntime.chat.sessionId.value
                        marmaladeRuntime.chat.isStreaming.value ||
                            (boundSid != null && marmaladeRuntime.chat.sessionRunning.value[boundSid] == true) ||
                            marmaladeRuntime.chat.messages.value.any { it.role == ChatRole.Assistant && it.pending }
                    },
                    elapsedMs = { SystemClock.elapsedRealtime() },
                    pollMs = VOICE_TURN_POLL_MS,
                    graceMs = VOICE_TURN_LIVENESS_GRACE_MS,
                    hardCapMs = VOICE_TURN_HARD_CAP_MS,
                )

                feeder.cancel()

                // Remove thinking placeholder / live transcript
                voiceMessages.removeAll { !it.isUser && it.isPartial }

                if (wait.replyText != null) {
                    // Final bubble in the mini chat. (Intent dispatch lives in
                    // ChatController.handleChatEvent's "final" branch — one
                    // source-agnostic site for voice popup and chat tab.)
                    addAssistantMessage(wait.replyText)
                    // Flush the tail the stream didn't cover: finalized rows
                    // are re-walked with finalize=true through the same
                    // consumed map, so already-spoken text is never repeated.
                    val floor = ackFloor
                    if (floor != null) {
                        collectSpeakableChunks(
                            marmaladeRuntime.chat.messages.value, floor, preexistingIds, consumed,
                        ).forEach { speechQueue.trySend(it) }
                    } else {
                        // Feeder never saw the ack (shouldn't happen —
                        // promptAcks replays); don't lose the reply.
                        speechQueue.trySend(wait.replyText)
                    }
                    speechQueue.close()
                    speaker.join()
                    abandonAudioFocus()
                    if (anyChunkFailed && !anyChunkSpoken) {
                        assistantState.value = AssistantState.ERROR
                        errorMessage.value = context.getString(R.string.error_speech_general)
                    } else {
                        finishSpokenTurn()
                    }
                } else {
                    speechQueue.close()
                    speaker.cancel()
                    ttsManager.stop()
                    abandonAudioFocus()
                    assistantState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(
                        if (wait.turnStillRunning) R.string.voice_reply_in_chat else R.string.error_no_response,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Gateway request cancelled (user stopped)")
                } else {
                    Log.e(TAG, "Gateway error", e)
                    // Reap the streaming feeder/speaker children so a failed
                    // turn can't keep collecting or speaking behind the error.
                    coroutineContext.cancelChildren()
                    ttsManager.stop()
                    assistantState.value = AssistantState.ERROR
                    errorMessage.value = e.message ?: context.getString(R.string.error_network)
                }
            } finally {
                // Restore render-coalesced delta batching whatever way the
                // turn ended (finalized, errored, popup dismissed/cancelled).
                marmaladeRuntime.chat.setImmediateDeltaFlush(false)
            }
        }
    }

    // ── Response speech ──────────────────────────────────────────────────────

    /**
     * Speak one streamed chunk; returns false only when the engine errored.
     * Chunks are ≤ ~220 chars by construction (the chunker's soft break), but
     * a finalize flush with no sentence boundaries can exceed the engine
     * limit — split defensively. A failed chunk is logged
     * and skipped so one bad utterance doesn't mute the rest of the reply;
     * total TTS failure (nothing spoke at all) is surfaced by the caller.
     */
    private suspend fun speakChunk(text: String): Boolean {
        val clean = TTSUtils.stripMarkdownForSpeech(text)
        if (clean.isBlank()) return true // markdown-only chunk (e.g. a ``` fence)
        if (assistantState.value == AssistantState.THINKING ||
            assistantState.value == AssistantState.PROCESSING
        ) {
            assistantState.value = AssistantState.PREPARING_SPEECH
        }
        var ok = true
        val maxLen = minOf(TTSUtils.getMaxInputLength(null), 1000)
        for (piece in TTSUtils.splitTextForTTS(clean, maxLen)) {
            var pieceDone = false
            ttsManager.speakWithProgress(piece).collect { state ->
                when (state) {
                    is TTSState.Preparing -> { /* keep current state */ }
                    is TTSState.Speaking -> assistantState.value = AssistantState.SPEAKING
                    is TTSState.Done -> pieceDone = true
                    is TTSState.Error -> Log.e(TAG, "TTS error: ${state.message}")
                }
            }
            if (!pieceDone) ok = false
        }
        return ok
    }

    /**
     * Tail of a fully-spoken turn: conversation mode re-opens the mic for the
     * next turn; tap-to-talk parks the popup at IDLE.
     */
    private suspend fun finishSpokenTurn() {
        if (autoListenEnabled.value) {
            Log.i(TAG, "Turn spoken, conversation mode -> auto-listen")
            soundManager.playReady()
            // Let the TTS audio tail drain so the recognizer doesn't pick up
            // the assistant's last syllables.
            delay(AUTO_LISTEN_TAIL_DRAIN_MS)
            startListening()
        } else {
            Log.i(TAG, "Turn spoken, state -> IDLE (tap-to-talk default)")
            assistantState.value = AssistantState.IDLE
            releaseWakeLock()
            SessionForegroundService.stop(context)
        }
    }

    // ── Session cleanup ──────────────────────────────────────────────────────

    private fun cleanupSession() {
        Log.i(TAG, "Voice popup hiding, cleaning up session resources")
        popupVisible.value = false

        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)

        // Clean up audio resources
        abandonAudioFocus()
        SessionForegroundService.stop(context)
        scope.cancel()
        sttProvider.stopStreaming()
        ttsManager.stop()
        releaseWakeLock()

        // Release the mic token AFTER scope.cancel() + stopStreaming() so the
        // STT AudioRecord is being torn down. The recognizer's onFlowClosed
        // lambda also calls releaseMic(VOICE_SESSION); both are idempotent, so
        // the mic is released once and KWS restarts once.
        Log.i(TAG, "Releasing mic token (STT engine stopped)")
        micOwnership.releaseMic(MicOwner.VOICE_SESSION)
    }

    // ── Audio focus ──────────────────────────────────────────────────────────

    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    // ── WakeLock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MarmaladeAssistant::SessionWakeLock",
        ).apply {
            acquire(10 * 60 * 1000L) // 10 min max to prevent leak
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}
