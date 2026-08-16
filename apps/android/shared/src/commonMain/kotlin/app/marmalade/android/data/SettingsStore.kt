package app.marmalade.android.data

/**
 * Platform-agnostic key-value store behind [SettingsRepository] (ADR 0011,
 * increment 3c). The EncryptedSharedPreferences layer is Android-only, so the
 * repository's persisted state hides behind this interface: `androidMain` backs
 * it with EncryptedSharedPreferences (unchanged file names, migration-compat),
 * `desktopMain` with a plain file-backed prefs store.
 *
 * Method shapes mirror `android.content.SharedPreferences` so the Android impl
 * is a thin pass-through and the repository's read-migration logic (default
 * fallbacks, legacy-value coercion) is carried over unchanged. Values are
 * primitives + string sets only — no Android types cross this seam.
 *
 * Callers must not mutate a set returned by [getStringSet] (the SharedPreferences
 * contract forbids it); the repository always copies before editing.
 */
interface SettingsStore {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun getStringSet(key: String, default: Set<String>): Set<String>
    fun putStringSet(key: String, value: Set<String>)
    fun contains(key: String): Boolean
}
