package app.marmalade.android.ui.chat

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.mic.MicOwner
import app.marmalade.android.mic.MicOwnershipManager
import app.marmalade.android.service.MarmaladeAssistantService
import app.marmalade.android.speech.STTEngineProvider
import app.marmalade.android.speech.StreamingResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "InlineSTTState"

/**
 * Interval for re-requesting the mic token while inline STT is active. Must be
 * shorter than [MicOwnershipManager.SAFETY_NET_TIMEOUT_MS] so a healthy long
 * session keeps refreshing the safety net before it can fire.
 */
private const val MIC_KEEPALIVE_INTERVAL_MS = 60_000L

/**
 * Delay between acquiring the mic token and the recognizer building its
 * AudioRecord. Gives HotwordService's currentOwner collector time to run
 * engine.stop() so the two AudioRecords never coexist (voice.md: AudioRecord
 * is single-owner). MarmaladeVoiceSession has an equivalent delay(150) in onShow;
 * the inline path had none, so it is added here. See ADR 0008.
 */
private const val MIC_HANDOFF_DELAY_MS = 150L

/**
 * Composable-level inline STT state holder.
 *
 * Uses the active STT engine via [STTEngineProvider] with endpoint detection.
 * Provides toggle/stop/triggerVoicePopup actions and exposes
 * isActive + partialText state for the ChatScreen to wire into the Composer.
 *
 * Mic ownership is coordinated through [MicOwnershipManager]: [start] acquires
 * the [MicOwner.INLINE_STT] token (or invokes [onMicBusy] and bails if the
 * voice popup currently holds the mic), and the recognizer's onFlowClosed
 * callback plus [stop]/[release] release it.
 */
class InlineSTTStateHolder(
    private val sttProvider: STTEngineProvider,
    private val settings: SettingsRepository,
    private val micOwnership: MicOwnershipManager,
    private val sendBroadcast: (Intent) -> Unit,
    private val packageName: String,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onMicBusy: () -> Unit = {},
) {
    var isActive by mutableStateOf(false)
        private set

    var partialText by mutableStateOf("")
        private set

    private var sttJob: Job? = null

    /** Toggle inline STT on/off. */
    fun toggle() {
        if (isActive) {
            stop()
        } else {
            start()
        }
    }

    fun start() {
        if (isActive) return

        // Acquire the mic token. KWS is preemptable so this is granted unless
        // the voice popup currently holds the mic — in which case surface a
        // transient "mic busy" message and leave the button disengaged.
        if (!micOwnership.requestMic(MicOwner.INLINE_STT)) {
            Log.w(TAG, "Mic busy — voice session holds it; not starting inline STT")
            onMicBusy()
            return
        }

        isActive = true
        partialText = ""

        sttJob?.cancel()
        sttJob = scope.launch {
            // Let HotwordService's currentOwner collector run engine.stop()
            // before the recognizer builds its own AudioRecord. requestMic
            // above already flipped currentOwner; this delay is the settle
            // window (MarmaladeVoiceSession has the equivalent delay(150) in onShow).
            delay(MIC_HANDOFF_DELAY_MS)

            // Keep-alive heartbeat: re-request the mic every 60s while the STT
            // flow is alive, refreshing the manager's 90s safety net. Scoped
            // inside sttJob so it dies with the flow — a stuck session stops
            // pinging and the net reclaims the mic ~90s after the stall.
            launch {
                while (currentCoroutineContext().isActive) {
                    delay(MIC_KEEPALIVE_INTERVAL_MS)
                    micOwnership.requestMic(MicOwner.INLINE_STT)
                }
            }

            var accumulated = ""
            val recognizer = sttProvider.getRecognizer()
            try {
                recognizer.startStreaming(
                    enableEndpoint = true, // inline STT uses endpoint detection
                    onFlowClosed = { micOwnership.releaseMic(MicOwner.INLINE_STT) },
                ).collect { result ->
                    when (result) {
                        is StreamingResult.Ready -> {
                            this@InlineSTTStateHolder.isActive = true
                        }
                        is StreamingResult.PartialText -> {
                            partialText = if (accumulated.isNotEmpty()) {
                                "$accumulated ${result.text}"
                            } else {
                                result.text
                            }
                        }
                        is StreamingResult.FinalText -> {
                            accumulated += (if (accumulated.isNotEmpty()) " " else "") + result.text
                            partialText = accumulated
                            // Don't auto-stop — keep listening for more input
                        }
                        is StreamingResult.Error -> {
                            Log.w(TAG, "Inline STT error: ${result.message}")
                            this@InlineSTTStateHolder.isActive = false
                            partialText = ""
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Inline STT flow failed", e)
                }
                this@InlineSTTStateHolder.isActive = false
                // onFlowClosed (recognizer finally block) also releases the
                // mic; this guard covers the case where the flow never built
                // its AudioRecord (e.g. cancelled during the handoff delay).
                micOwnership.releaseMic(MicOwner.INLINE_STT)
            }
        }
    }

    fun stop() {
        sttJob?.cancel()
        sttJob = null
        isActive = false
        // Keep partial text for user to use.
        // Guard release — sttJob cancellation also triggers onFlowClosed;
        // releaseMic is idempotent.
        micOwnership.releaseMic(MicOwner.INLINE_STT)
    }

    /** Trigger the voice popup overlay. */
    fun triggerVoicePopup() {
        sendBroadcast(
            Intent(MarmaladeAssistantService.ACTION_SHOW_ASSISTANT)
                .setPackage(packageName)
        )
    }

    fun release() {
        sttJob?.cancel()
        // Guard release for Composable disposal mid-listen.
        micOwnership.releaseMic(MicOwner.INLINE_STT)
        // Don't release recognizer — it's a singleton
    }
}

/**
 * Remember an [InlineSTTStateHolder] scoped to the current composition.
 *
 * @param onMicBusy invoked when [InlineSTTStateHolder.start] is denied the mic
 *   because the voice popup holds it — wire this to a snackbar.
 */
@Composable
fun rememberInlineSTTState(onMicBusy: () -> Unit = {}): InlineSTTStateHolder {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository.getInstance(context) }
    val sttProvider = remember { STTEngineProvider.getInstance(context) }
    val micOwnership = remember { MicOwnershipManager.getInstance(context) }

    val state = remember {
        InlineSTTStateHolder(
            sttProvider = sttProvider,
            settings = settings,
            micOwnership = micOwnership,
            sendBroadcast = { intent -> context.sendBroadcast(intent) },
            packageName = context.packageName,
            scope = scope,
            onMicBusy = onMicBusy,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            state.release()
        }
    }

    return state
}
