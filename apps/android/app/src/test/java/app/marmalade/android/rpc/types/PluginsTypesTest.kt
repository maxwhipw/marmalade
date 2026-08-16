package app.marmalade.android.rpc.types

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for the Plugins DTOs (Settings pages, 2026-06-30).
 * Mirrors the catch-extra-fields and tolerate-defaults patterns the
 * started_at and SkillInfo.category bugs taught us. (The Toolsets half of
 * the original ToolsetsPluginsTypesTest died with the Toolsets surface —
 * fork-rest-triage verdict resolved 2026-07-18.)
 */
class PluginsTypesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // ── plugins list ──────────────────────────────────────────────────────

    @Test
    fun pluginsList_decodes_full_row() {
        val raw = """
            {
              "plugins": [
                {
                  "name": "marmalade-android",
                  "version": "1.0.0",
                  "description": "Android client bridge.",
                  "source": "bundled",
                  "status": "enabled"
                }
              ],
              "user_count": 0,
              "bundled_count": 1
            }
        """.trimIndent()
        val decoded = json.decodeFromString(PluginsListResponse.serializer(), raw)
        assertEquals(1, decoded.plugins.size)
        assertEquals("marmalade-android", decoded.plugins[0].name)
        assertEquals("bundled", decoded.plugins[0].source)
        assertEquals("enabled", decoded.plugins[0].status)
        assertEquals(0, decoded.userCount)
        assertEquals(1, decoded.bundledCount)
    }

    @Test
    fun pluginsList_tolerates_missing_version_description() {
        // Server returns "" defaults but if a future variant returns null
        // we still want defensible decode.
        val raw = """
            {
              "plugins": [
                {"name": "x", "source": "user", "status": "disabled"}
              ]
            }
        """.trimIndent()
        val decoded = json.decodeFromString(PluginsListResponse.serializer(), raw)
        assertEquals("", decoded.plugins[0].version)
        assertEquals("", decoded.plugins[0].description)
    }

    // ── plugins toggle ────────────────────────────────────────────────────

    @Test
    fun pluginsToggle_decodes_with_plugin_row() {
        val raw = """
            {
              "ok": true,
              "unchanged": false,
              "name": "marmalade-android",
              "plugin": {
                "name": "marmalade-android",
                "version": "1.0.0",
                "description": "Android client bridge.",
                "source": "bundled",
                "status": "disabled"
              }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(PluginsToggleResponse.serializer(), raw)
        assertTrue(decoded.ok)
        assertEquals("marmalade-android", decoded.name)
        assertNotNull(decoded.plugin)
        assertEquals("disabled", decoded.plugin!!.status)
    }

    @Test
    fun pluginsToggle_tolerates_null_plugin_row() {
        // The server may omit the plugin field on a no-op toggle.
        val raw = """
            {"ok": true, "unchanged": true, "name": "marmalade-android"}
        """.trimIndent()
        val decoded = json.decodeFromString(PluginsToggleResponse.serializer(), raw)
        assertTrue(decoded.unchanged)
        assertNull(decoded.plugin)
    }
}
