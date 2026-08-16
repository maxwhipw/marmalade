package app.marmalade.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * [SettingsStore] backed by an [EncryptedSharedPreferences]. A thin pass-through
 * — the repository's read-migration logic is unchanged; only the backing store
 * is now abstracted (ADR 0011, increment 3c). Each mutator opens its own
 * `edit()`/`apply()`, exactly as the pre-move `SettingsRepository` did, so the
 * on-disk behavior is identical.
 */
private class SharedPreferencesStore(
    private val prefs: SharedPreferences,
) : SettingsStore {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        prefs.getStringSet(key, default) ?: default

    override fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)
}

// Android EncryptedSharedPreferences file names. Kept verbatim for migration
// compatibility — an existing install's settings live in these files.
private const val PREFS_NAME = "openclaw_secure_prefs"
// Mirrors SecurePrefs.createPrefs(..., "openclaw.node.secure"). Two
// EncryptedSharedPreferences files exist for historical reasons; any change to
// that file name in SecurePrefs needs a matching change here. Read-only from
// here (the dashboard URL/token the SecurePrefs pair owns) so isConfigured()
// can answer without coupling to SecurePrefs.
private const val SECURE_PREFS_NAME = "openclaw.node.secure"

@Volatile
private var INSTANCE: SettingsRepository? = null

/**
 * Process-wide singleton accessor. Attached as an extension on the
 * [SettingsRepository] companion (declared in `commonMain`) so every existing
 * `SettingsRepository.getInstance(context)` call site keeps the same syntax
 * after the KMP move — the Android EncryptedSharedPreferences wiring lives here
 * (ADR 0011, increment 3c); desktop supplies its own file-backed stores.
 */
fun SettingsRepository.Companion.getInstance(context: Context): SettingsRepository {
    return INSTANCE ?: synchronized(this) {
        INSTANCE ?: run {
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val securePrefs = EncryptedSharedPreferences.create(
                appContext,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            SettingsRepository(
                store = SharedPreferencesStore(prefs),
                credentials = SharedPreferencesStore(securePrefs),
            ).also { INSTANCE = it }
        }
    }
}
