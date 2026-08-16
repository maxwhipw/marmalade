package app.marmalade.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.marmalade.android.chat.OutgoingAttachment
import app.marmalade.android.node.CameraCaptureManager
import app.marmalade.android.node.MarmaladeRuntime
import app.marmalade.android.node.ScreenRecordManager
import app.marmalade.android.node.SmsManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level Compose binding to [MarmaladeRuntime]. The surface is
 * deliberately trimmed to endpoint config + assistant-state — there is no
 * discovery list, TLS pin/TOFU, agent list, or update/pairing banner here;
 * connection UX lives in ConnectScreen.
 *
 * STAYS in `:app` — deliberately (desktop-client plan Phase 1, option (b),
 * 2026-07-25). This class is a pure passthrough: all 46 of its members
 * delegate straight to [MarmaladeRuntime], and it holds no logic, no state
 * and no derivation of its own. Three of those members ([camera],
 * [screenRecorder], [sms]) are re-exported Android manager objects, so the
 * type signature alone is Android-bound.
 *
 * There is therefore nothing here to share: moving it would mean moving
 * `node/` — explicitly out of Phase 1 scope ("resist extracting Android-only
 * subsystems for completeness") — and would buy a desktop client a facade
 * over a runtime it does not have. A desktop shell will bind its own runtime
 * directly. Revisit only if `node/` itself is ever ported.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val runtime: MarmaladeRuntime = (app as MarmaladeApplication).marmaladeRuntime

  val camera: CameraCaptureManager = runtime.camera
  val screenRecorder: ScreenRecordManager = runtime.screenRecorder
  val sms: SmsManager = runtime.sms

  val isConnected: StateFlow<Boolean> = runtime.isConnected
  val statusText: StateFlow<String> = runtime.statusText
  val serverName: StateFlow<String?> = runtime.serverName
  val isForeground: StateFlow<Boolean> = runtime.isForeground
  val mainSessionKey: StateFlow<String> = runtime.mainSessionKey

  val cameraHud: StateFlow<CameraHudState?> = runtime.cameraHud
  val cameraFlashToken: StateFlow<Long> = runtime.cameraFlashToken
  val screenRecordActive: StateFlow<Boolean> = runtime.screenRecordActive

  val instanceId: StateFlow<String> = runtime.instanceId
  val displayName: StateFlow<String> = runtime.displayName
  val cameraEnabled: StateFlow<Boolean> = runtime.cameraEnabled
  val locationMode: StateFlow<LocationMode> = runtime.locationMode
  val locationPreciseEnabled: StateFlow<Boolean> = runtime.locationPreciseEnabled
  val preventSleep: StateFlow<Boolean> = runtime.preventSleep
  val wakeWords: StateFlow<List<String>> = runtime.wakeWords
  val voiceWakeMode: StateFlow<VoiceWakeMode> = runtime.voiceWakeMode
  val voiceWakeStatusText: StateFlow<String> = runtime.voiceWakeStatusText

  val chatSessionKey: StateFlow<String> = runtime.chatSessionKey
  val chatSessionId: StateFlow<String?> = runtime.chatSessionId
  val chatMessages = runtime.chat.messages
  val chatError: StateFlow<String?> = runtime.chatError
  val chatHealthOk: StateFlow<Boolean> = runtime.chatHealthOk
  val chatThinkingLevel: StateFlow<String> = runtime.chatThinkingLevel
  val chatSessions = runtime.chatSessions
  val pendingRunCount: StateFlow<Int> = runtime.pendingRunCount
  val attachmentsSupported: StateFlow<Boolean> = runtime.attachmentsSupported

  fun setForeground(value: Boolean) {
    runtime.setForeground(value)
  }

  fun setDisplayName(value: String) {
    runtime.setDisplayName(value)
  }

  fun setCameraEnabled(value: Boolean) {
    runtime.setCameraEnabled(value)
  }

  fun setLocationMode(mode: LocationMode) {
    runtime.setLocationMode(mode)
  }

  fun setLocationPreciseEnabled(value: Boolean) {
    runtime.setLocationPreciseEnabled(value)
  }

  fun setPreventSleep(value: Boolean) {
    runtime.setPreventSleep(value)
  }

  fun setWakeWords(words: List<String>) {
    runtime.setWakeWords(words)
  }

  fun resetWakeWordsDefaults() {
    runtime.resetWakeWordsDefaults()
  }

  fun setVoiceWakeMode(mode: VoiceWakeMode) {
    runtime.setVoiceWakeMode(mode)
  }

  fun disconnect() {
    runtime.disconnect()
  }

  fun loadChat(sessionKey: String) {
    runtime.loadChat(sessionKey)
  }

  fun refreshChat() {
    runtime.refreshChat()
  }

  fun refreshChatSessions(limit: Int? = null) {
    runtime.refreshChatSessions(limit = limit)
  }

  fun setChatThinkingLevel(level: String) {
    runtime.setChatThinkingLevel(level)
  }

  fun switchChatSession(sessionKey: String) {
    runtime.switchChatSession(sessionKey)
  }

  fun abortChat() {
    runtime.abortChat()
  }

  fun sendChat(message: String, thinking: String, attachments: List<OutgoingAttachment>) {
    runtime.sendChat(message = message, thinking = thinking, attachments = attachments)
  }
}
