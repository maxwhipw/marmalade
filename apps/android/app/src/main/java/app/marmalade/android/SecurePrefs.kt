@file:Suppress("DEPRECATION")

package app.marmalade.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.marmalade.android.utils.DeviceNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

class SecurePrefs(context: Context) {
  companion object {
    val defaultWakeWords: List<String> = listOf("marmalade", "hey marmalade")
    private const val displayNameKey = "node.displayName"
    private const val voiceWakeModeKey = "voiceWake.mode"
  }

  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = true }

  private val masterKey =
    MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()

  private val prefs: SharedPreferences by lazy {
    createPrefs(appContext, "openclaw.node.secure")
  }

  private val _instanceId = MutableStateFlow(loadOrCreateInstanceId())
  val instanceId: StateFlow<String> = _instanceId

  private val _displayName =
    MutableStateFlow(loadOrMigrateDisplayName(context = context))
  val displayName: StateFlow<String> = _displayName

  private val _cameraEnabled = MutableStateFlow(prefs.getBoolean("camera.enabled", false))
  val cameraEnabled: StateFlow<Boolean> = _cameraEnabled

  private val _locationMode =
    MutableStateFlow(LocationMode.fromRawValue(prefs.getString("location.enabledMode", "off")))
  val locationMode: StateFlow<LocationMode> = _locationMode

  private val _locationPreciseEnabled =
    MutableStateFlow(prefs.getBoolean("location.preciseEnabled", false))
  val locationPreciseEnabled: StateFlow<Boolean> = _locationPreciseEnabled

  private val _preventSleep = MutableStateFlow(prefs.getBoolean("screen.preventSleep", false))
  val preventSleep: StateFlow<Boolean> = _preventSleep

  private val _manualEnabled =
    MutableStateFlow(prefs.getBoolean("gateway.manual.enabled", false))
  val manualEnabled: StateFlow<Boolean> = _manualEnabled

  private val _manualHost =
    MutableStateFlow(prefs.getString("gateway.manual.host", "") ?: "")
  val manualHost: StateFlow<String> = _manualHost

  private val _manualPort =
    MutableStateFlow(prefs.getInt("gateway.manual.port", 18789))
  val manualPort: StateFlow<Int> = _manualPort

  private val _manualTls =
    MutableStateFlow(prefs.getBoolean("gateway.manual.tls", true))
  val manualTls: StateFlow<Boolean> = _manualTls

  private val _gatewayToken =
    MutableStateFlow(prefs.getString("gateway.manual.token", "") ?: "")
  val gatewayToken: StateFlow<String> = _gatewayToken

  // Marmalade dashboard — primary chat connection. Same JSON-RPC dialect
  // the desktop + web clients use against the gateway's /api/ws endpoint
  // (port 9119 by default). Configure with the dashboard's URL and the
  // token printed in index.html as window.__MARMALADE_SESSION_TOKEN__.
  private val _dashboardEnabled =
    MutableStateFlow(prefs.getBoolean("marmalade.dashboard.enabled", false))
  val dashboardEnabled: StateFlow<Boolean> = _dashboardEnabled

  private val _dashboardUrl =
    MutableStateFlow(prefs.getString("marmalade.dashboard.url", "") ?: "")
  val dashboardUrl: StateFlow<String> = _dashboardUrl

  private val _dashboardToken =
    MutableStateFlow(prefs.getString("marmalade.dashboard.token", "") ?: "")
  val dashboardToken: StateFlow<String> = _dashboardToken

  // Marmalade Agent Android plugin — secondary connection for node.invoke.*
  // device-tool callbacks ONLY (alarms, intents, camera, etc.). Chat protocol
  // does NOT flow through this connection. URL is the pasted Tailscale Serve
  // address of the marmalade-android platform plugin; token is the opaque
  // pairing secret from android-devices.yaml.
  private val _marmaladeEnabled =
    MutableStateFlow(prefs.getBoolean("marmalade.plugin.enabled", false))
  val marmaladeEnabled: StateFlow<Boolean> = _marmaladeEnabled

  private val _marmaladeUrl =
    MutableStateFlow(prefs.getString("marmalade.plugin.url", "") ?: "")
  val marmaladeUrl: StateFlow<String> = _marmaladeUrl

  private val _marmaladeToken =
    MutableStateFlow(prefs.getString("marmalade.plugin.token", "") ?: "")
  val marmaladeToken: StateFlow<String> = _marmaladeToken

  private val _lastDiscoveredStableId =
    MutableStateFlow(
      prefs.getString("gateway.lastDiscoveredStableID", "") ?: "",
    )
  val lastDiscoveredStableId: StateFlow<String> = _lastDiscoveredStableId

  private val _wakeWords = MutableStateFlow(loadWakeWords())
  val wakeWords: StateFlow<List<String>> = _wakeWords

  private val _voiceWakeMode = MutableStateFlow(loadVoiceWakeMode())
  val voiceWakeMode: StateFlow<VoiceWakeMode> = _voiceWakeMode

  private val _talkEnabled = MutableStateFlow(prefs.getBoolean("talk.enabled", false))
  val talkEnabled: StateFlow<Boolean> = _talkEnabled

  private val _smsEnabled = MutableStateFlow(prefs.getBoolean("sms.enabled", false))
  val smsEnabled: StateFlow<Boolean> = _smsEnabled

  fun setLastDiscoveredStableId(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("gateway.lastDiscoveredStableID", trimmed) }
    _lastDiscoveredStableId.value = trimmed
  }

  fun setDisplayName(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString(displayNameKey, trimmed) }
    _displayName.value = trimmed
  }

  fun setCameraEnabled(value: Boolean) {
    prefs.edit { putBoolean("camera.enabled", value) }
    _cameraEnabled.value = value
  }

  fun setLocationMode(mode: LocationMode) {
    prefs.edit { putString("location.enabledMode", mode.rawValue) }
    _locationMode.value = mode
  }

  fun setLocationPreciseEnabled(value: Boolean) {
    prefs.edit { putBoolean("location.preciseEnabled", value) }
    _locationPreciseEnabled.value = value
  }

  fun setPreventSleep(value: Boolean) {
    prefs.edit { putBoolean("screen.preventSleep", value) }
    _preventSleep.value = value
  }

  fun setManualEnabled(value: Boolean) {
    prefs.edit { putBoolean("gateway.manual.enabled", value) }
    _manualEnabled.value = value
  }

  fun setManualHost(value: String) {
    val trimmed = value.trim()
    prefs.edit { putString("gateway.manual.host", trimmed) }
    _manualHost.value = trimmed
  }

  fun setManualPort(value: Int) {
    prefs.edit { putInt("gateway.manual.port", value) }
    _manualPort.value = value
  }

  fun setManualTls(value: Boolean) {
    prefs.edit { putBoolean("gateway.manual.tls", value) }
    _manualTls.value = value
  }

  fun setGatewayToken(value: String) {
    prefs.edit { putString("gateway.manual.token", value) }
    _gatewayToken.value = value
  }

  // Credential setters use commit=true so the disk write is synchronous
  // before the call returns. ConnectionSettingsScreen calls these and then
  // immediately triggers a reconnect; on Android process death between the
  // apply() and the disk flush, the new value would be silently dropped on
  // next launch. The ~5–50ms latency is acceptable for a Save tap; it would
  // not be for high-frequency setters like toggles.

  fun setDashboardEnabled(value: Boolean) {
    prefs.edit(commit = true) { putBoolean("marmalade.dashboard.enabled", value) }
    _dashboardEnabled.value = value
  }

  fun setDashboardUrl(value: String) {
    val trimmed = value.trim()
    prefs.edit(commit = true) { putString("marmalade.dashboard.url", trimmed) }
    _dashboardUrl.value = trimmed
  }

  fun setDashboardToken(value: String) {
    val trimmed = value.trim()
    prefs.edit(commit = true) { putString("marmalade.dashboard.token", trimmed) }
    _dashboardToken.value = trimmed
  }

  fun setMarmaladeEnabled(value: Boolean) {
    prefs.edit(commit = true) { putBoolean("marmalade.plugin.enabled", value) }
    _marmaladeEnabled.value = value
  }

  fun setMarmaladeUrl(value: String) {
    val trimmed = value.trim()
    prefs.edit(commit = true) { putString("marmalade.plugin.url", trimmed) }
    _marmaladeUrl.value = trimmed
  }

  fun setMarmaladeToken(value: String) {
    val trimmed = value.trim()
    prefs.edit(commit = true) { putString("marmalade.plugin.token", trimmed) }
    _marmaladeToken.value = trimmed
  }

  fun loadGatewayToken(): String? {
    val manual = _gatewayToken.value.trim()
    if (manual.isNotEmpty()) return manual
    val key = "gateway.token.${_instanceId.value}"
    val stored = prefs.getString(key, null)?.trim()
    return stored?.takeIf { it.isNotEmpty() }
  }

  fun saveGatewayToken(token: String) {
    val key = "gateway.token.${_instanceId.value}"
    prefs.edit { putString(key, token.trim()) }
  }

  fun loadGatewayPassword(): String? {
    val key = "gateway.password.${_instanceId.value}"
    val stored = prefs.getString(key, null)?.trim()
    return stored?.takeIf { it.isNotEmpty() }
  }

  fun saveGatewayPassword(password: String) {
    val key = "gateway.password.${_instanceId.value}"
    prefs.edit { putString(key, password.trim()) }
  }

  fun loadGatewayTlsFingerprint(stableId: String): String? {
    val key = "gateway.tls.$stableId"
    return prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
  }

  fun saveGatewayTlsFingerprint(stableId: String, fingerprint: String) {
    val key = "gateway.tls.$stableId"
    prefs.edit { putString(key, fingerprint.trim()) }
  }

  fun getString(key: String): String? {
    return prefs.getString(key, null)
  }

  fun putString(key: String, value: String) {
    prefs.edit { putString(key, value) }
  }

  fun remove(key: String) {
    prefs.edit { remove(key) }
  }

  private fun createPrefs(context: Context, name: String): SharedPreferences {
    return EncryptedSharedPreferences.create(
      context,
      name,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  private fun loadOrCreateInstanceId(): String {
    val existing = prefs.getString("node.instanceId", null)?.trim()
    if (!existing.isNullOrBlank()) return existing
    val fresh = UUID.randomUUID().toString()
    prefs.edit { putString("node.instanceId", fresh) }
    return fresh
  }

  private fun loadOrMigrateDisplayName(context: Context): String {
    val existing = prefs.getString(displayNameKey, null)?.trim().orEmpty()
    if (existing.isNotEmpty() && existing != "Android Node") return existing

    val candidate = DeviceNames.bestDefaultNodeName(context).trim()
    val resolved = candidate.ifEmpty { "Android Node" }

    prefs.edit { putString(displayNameKey, resolved) }
    return resolved
  }

  fun setWakeWords(words: List<String>) {
    val sanitized = WakeWords.sanitize(words, defaultWakeWords)
    val encoded =
      JsonArray(sanitized.map { JsonPrimitive(it) }).toString()
    prefs.edit { putString("voiceWake.triggerWords", encoded) }
    _wakeWords.value = sanitized
  }

  fun setVoiceWakeMode(mode: VoiceWakeMode) {
    prefs.edit { putString(voiceWakeModeKey, mode.rawValue) }
    _voiceWakeMode.value = mode
  }

  fun setTalkEnabled(value: Boolean) {
    prefs.edit { putBoolean("talk.enabled", value) }
    _talkEnabled.value = value
  }

  fun setSmsEnabled(value: Boolean) {
    prefs.edit { putBoolean("sms.enabled", value) }
    _smsEnabled.value = value
  }

  private fun loadVoiceWakeMode(): VoiceWakeMode {
    val raw = prefs.getString(voiceWakeModeKey, null)
    val resolved = VoiceWakeMode.fromRawValue(raw)

    // Default ON (foreground) when unset.
    if (raw.isNullOrBlank()) {
      prefs.edit { putString(voiceWakeModeKey, resolved.rawValue) }
    }

    return resolved
  }

  private fun loadWakeWords(): List<String> {
    val raw = prefs.getString("voiceWake.triggerWords", null)?.trim()
    if (raw.isNullOrEmpty()) return defaultWakeWords
    return try {
      val element = json.parseToJsonElement(raw)
      val array = element as? JsonArray ?: return defaultWakeWords
      val decoded =
        array.mapNotNull { item ->
          when (item) {
            is JsonNull -> null
            is JsonPrimitive -> item.content.trim().takeIf { it.isNotEmpty() }
            else -> null
          }
        }
      WakeWords.sanitize(decoded, defaultWakeWords)
    } catch (_: Throwable) {
      defaultWakeWords
    }
  }

}
