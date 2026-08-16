package app.marmalade.android.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Status-string → enabled-flag mapping for the Plugins row Switch.
 *
 * The gateway's `_plugin_status` (marmalade_cli/plugins_cmd.py:928) emits
 * exactly THREE status strings: `"enabled"`, `"disabled"`, `"not enabled"`.
 * Cover each, plus a forward-compat case for a hypothetical qualifier
 * like `"enabled (override)"` so the soft `startsWith` rule is asserted.
 */
class PluginEnabledTest {

    // ── Real server-emitted values ────────────────────────────────────────

    @Test
    fun status_enabled_is_on() {
        assertTrue(pluginEnabled("enabled"))
    }

    @Test
    fun status_disabled_is_off() {
        assertFalse(pluginEnabled("disabled"))
    }

    @Test
    fun status_not_enabled_is_off() {
        // "not enabled" = discovered on disk but absent from both the
        // enabled-set and the disabled-set. Renders as off.
        assertFalse(pluginEnabled("not enabled"))
    }

    // ── Forward-compat ────────────────────────────────────────────────────

    @Test
    fun future_enabled_qualifier_stays_on() {
        // Soft `startsWith` rule: a future server that adds context
        // ("enabled (override)") must NOT flip the toggle to off behind
        // the user's back.
        assertTrue(pluginEnabled("enabled (override)"))
    }

    @Test
    fun case_insensitive() {
        assertTrue(pluginEnabled("Enabled"))
        assertTrue(pluginEnabled("ENABLED"))
    }

    // ── Defensive ─────────────────────────────────────────────────────────

    @Test
    fun empty_status_is_off() {
        assertFalse(pluginEnabled(""))
    }

    @Test
    fun unrelated_strings_are_off() {
        assertFalse(pluginEnabled("bundled"))
        assertFalse(pluginEnabled("user"))
    }
}
