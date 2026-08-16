package app.marmalade.android.data

import java.io.File
import java.util.Properties

/**
 * File-backed [SettingsStore] for the desktop client — a plain
 * `java.util.Properties` file. NOT encrypted: desktop credential-at-rest
 * hardening is a Phase 2+ concern (desktop-client plan), so [buildDesktopSettings]
 * keeps the dashboard pair in a separate file to mirror Android's two-store split
 * and leave room for a future secure backend.
 *
 * String sets are stored newline-joined under a single key (the two set-valued
 * settings — dismissed update versions / pair request ids — are version strings
 * and request ids, never multi-line). Every mutator writes through to disk
 * synchronously so the store matches SharedPreferences' apply-and-persist
 * semantics closely enough for the desktop shell.
 *
 * Not yet wired to an app — exists so `desktopMain` compiles the shared
 * [SettingsRepository], proving the KMP move carries settings to the desktop
 * client (ADR 0011, increment 3c). The desktop shell (Phase 2) is its first
 * caller.
 */
private class PropertiesFileStore(private val file: File) : SettingsStore {
    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }

    private fun putRaw(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        persist()
    }

    override fun getString(key: String, default: String?): String? =
        props.getProperty(key) ?: default

    override fun putString(key: String, value: String?) = putRaw(key, value)

    override fun getBoolean(key: String, default: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default

    override fun putBoolean(key: String, value: Boolean) = putRaw(key, value.toString())

    override fun getInt(key: String, default: Int): Int =
        props.getProperty(key)?.toIntOrNull() ?: default

    override fun putInt(key: String, value: Int) = putRaw(key, value.toString())

    override fun getLong(key: String, default: Long): Long =
        props.getProperty(key)?.toLongOrNull() ?: default

    override fun putLong(key: String, value: Long) = putRaw(key, value.toString())

    override fun getFloat(key: String, default: Float): Float =
        props.getProperty(key)?.toFloatOrNull() ?: default

    override fun putFloat(key: String, value: Float) = putRaw(key, value.toString())

    override fun getStringSet(key: String, default: Set<String>): Set<String> {
        val raw = props.getProperty(key) ?: return default
        if (raw.isEmpty()) return emptySet()
        return raw.split('\n').toSet()
    }

    override fun putStringSet(key: String, value: Set<String>) =
        putRaw(key, value.joinToString("\n"))

    override fun contains(key: String): Boolean = props.getProperty(key) != null
}

/**
 * Build a desktop [SettingsRepository] rooted at [configDir]: `settings.properties`
 * for user settings and `credentials.properties` for the dashboard pair. Mirrors
 * `buildDesktopDatabase(path)` — the KMP construction seam the desktop shell uses.
 */
fun SettingsRepository.Companion.buildDesktopSettings(configDir: String): SettingsRepository {
    val dir = File(configDir)
    return SettingsRepository(
        store = PropertiesFileStore(File(dir, "settings.properties")),
        credentials = PropertiesFileStore(File(dir, "credentials.properties")),
    )
}
