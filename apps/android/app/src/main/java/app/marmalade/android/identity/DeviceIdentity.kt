package app.marmalade.android.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Stable local device identifier.
 *
 * 32 random bytes hex-encoded (64 chars), generated on first read and
 * persisted in a dedicated EncryptedSharedPreferences file so identity
 * has its own lifecycle independent of [SecurePrefs] / [SettingsRepository]
 * (which it predates and outlives — clearing user settings or credentials
 * must NOT churn the device identity).
 *
 * Survives cold launches and OS-managed backups; resets only on app
 * uninstall or "clear data".
 *
 * Ed25519 signing infrastructure was removed when the OpenClaw
 * `node.pair` challenge flow was stripped — marmalade-agent's WS uses
 * bearer-token auth, not signed challenges. If the planned upstream
 * `mint_long_lived_device_token` feature ever ships and needs a signed
 * proof, generate an Ed25519 keypair here alongside [deviceId]; the
 * stable identifier itself doesn't need to change.
 */
class DeviceIdentity private constructor(val deviceId: String) {

  companion object {
    private const val PREFS_FILE = "marmalade_device_identity_prefs"
    private const val MASTER_KEY_ALIAS = "marmalade_device_identity_master_key"
    private const val KEY_DEVICE_ID = "device_id"
    private const val DEVICE_ID_BYTES = 32

    @Volatile
    private var cached: DeviceIdentity? = null

    /**
     * Returns the cached identity, loading from encrypted storage on
     * first call. Thread-safe; the deviceId is generated exactly once
     * even under concurrent first reads.
     */
    @Synchronized
    fun loadOrCreate(context: Context): DeviceIdentity {
      cached?.let { return it }
      val prefs = openPrefs(context.applicationContext)
      val existing = prefs.getString(KEY_DEVICE_ID, null)
      val deviceId = if (existing.isNullOrBlank()) {
        generateDeviceId().also { id ->
          prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
      } else {
        existing
      }
      return DeviceIdentity(deviceId).also { cached = it }
    }

    private fun openPrefs(context: Context): SharedPreferences {
      val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
      return EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
    }

    private fun generateDeviceId(): String {
      val bytes = ByteArray(DEVICE_ID_BYTES)
      SecureRandom().nextBytes(bytes)
      return bytes.joinToString("") { "%02x".format(it) }
    }
  }
}
