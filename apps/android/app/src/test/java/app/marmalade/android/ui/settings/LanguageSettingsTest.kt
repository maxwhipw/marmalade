package app.marmalade.android.ui.settings

import app.marmalade.android.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-function tests for [SettingsRepository.resolveLanguageTag].
 * No Android context needed.
 */
class LanguageSettingsTest {

    @Test
    fun `empty string returns null for system default`() {
        assertNull(SettingsRepository.resolveLanguageTag(""))
    }

    @Test
    fun `system keyword returns null for system default`() {
        assertNull(SettingsRepository.resolveLanguageTag("system"))
    }

    @Test
    fun `en returns en`() {
        assertEquals("en", SettingsRepository.resolveLanguageTag("en"))
    }

    @Test
    fun `ja returns ja`() {
        assertEquals("ja", SettingsRepository.resolveLanguageTag("ja"))
    }

    @Test
    fun `unknown language passes through for future languages`() {
        assertEquals("fr", SettingsRepository.resolveLanguageTag("fr"))
        assertEquals("de", SettingsRepository.resolveLanguageTag("de"))
        assertEquals("zh-CN", SettingsRepository.resolveLanguageTag("zh-CN"))
    }
}
