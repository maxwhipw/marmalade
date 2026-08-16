package app.marmalade.android.ui.settings

import app.marmalade.android.ui.navigation.MarmaladeDestination
import app.marmalade.android.ui.navigation.SettingsRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure string-constant tests for the settings route structure. The
 * Gateway routes were removed alongside the OpenClaw transport gut —
 * marmalade-agent's URL + token live in [SecurePrefs] now, not in a
 * dedicated settings sub-screen.
 */
class SettingsNavGraphTest {

    @Test
    fun `ALL covers every defined route`() {
        // Don't hard-code a count — when a Settings page is added (e.g. W3
        // Skills, W4 MCP, D9 Personality landed 2026-06-29), the contract
        // is "every const String in SettingsRoutes is reachable via ALL",
        // not "ALL has N entries forever". Compare via reflection.
        val constants = SettingsRoutes::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(null) as? String }
            .toSet()
        assertEquals(
            "SettingsRoutes.ALL must reference every const String route in the object",
            constants,
            SettingsRoutes.ALL.toSet(),
        )
    }

    @Test
    fun `new routes from W3 W4 are present`() {
        assertTrue("Skills route missing (W3)", SettingsRoutes.ALL.contains(SettingsRoutes.SKILLS))
        assertTrue("MCP route missing (W4)", SettingsRoutes.ALL.contains(SettingsRoutes.MCP))
        // Personality (D9) was removed with config.set — fork-only surface
        // (gap triage 2026-07-11); marmaladed has no runtime-config RPC.
    }

    @Test
    fun `all routes start with settings prefix`() {
        SettingsRoutes.ALL.forEach { route ->
            assertTrue(
                "Route '$route' should start with 'settings/'",
                route.startsWith("settings/"),
            )
        }
    }

    @Test
    fun `MAIN is the start destination`() {
        assertEquals("settings/main", SettingsRoutes.MAIN)
    }

    @Test
    fun `settings destination route is the nav graph parent`() {
        assertEquals("settings", MarmaladeDestination.Settings.route)
    }

    @Test
    fun `category routes are distinct`() {
        val categoryRoutes = listOf(
            SettingsRoutes.APPEARANCE,
            SettingsRoutes.SPEECH_RECOGNITION,
            SettingsRoutes.ASSISTANT,
            SettingsRoutes.APP_INFO,
        )
        assertEquals(4, categoryRoutes.toSet().size)
    }

    @Test
    fun `credits and licenses sub-routes are present`() {
        assertTrue(SettingsRoutes.ALL.contains(SettingsRoutes.CREDITS))
        assertTrue(SettingsRoutes.ALL.contains(SettingsRoutes.LICENSES))
    }

    @Test
    fun `speech recognition and assistant routes are present`() {
        assertTrue(SettingsRoutes.ALL.contains(SettingsRoutes.SPEECH_RECOGNITION))
        assertTrue(SettingsRoutes.ALL.contains(SettingsRoutes.ASSISTANT))
    }

    @Test
    fun `deprecated voice route is still in ALL`() {
        assertTrue(SettingsRoutes.ALL.contains(SettingsRoutes.VOICE))
    }

    @Test
    fun `route values match expected strings`() {
        assertEquals("settings/appearance", SettingsRoutes.APPEARANCE)
        assertEquals("settings/voice", SettingsRoutes.VOICE)
        assertEquals("settings/speech_recognition", SettingsRoutes.SPEECH_RECOGNITION)
        assertEquals("settings/assistant", SettingsRoutes.ASSISTANT)
        assertEquals("settings/developer", SettingsRoutes.DEVELOPER)
        assertEquals("settings/app_info", SettingsRoutes.APP_INFO)
        assertEquals("settings/credits", SettingsRoutes.CREDITS)
        assertEquals("settings/licenses", SettingsRoutes.LICENSES)
    }
}
