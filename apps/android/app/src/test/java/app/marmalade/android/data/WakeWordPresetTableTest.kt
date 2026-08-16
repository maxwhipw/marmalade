package app.marmalade.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the bundled wake-word preset table. The whole point of
 * BUILTIN_WAKE_WORD_PRESETS is that adding a row is the only edit needed
 * to add a preset — these tests catch the typos that would silently break
 * the picker, loader, or migration if a future row is malformed.
 */
class WakeWordPresetTableTest {

    private val presets = SettingsRepository.BUILTIN_WAKE_WORD_PRESETS

    @Test
    fun tableIsNonEmpty() {
        assertTrue("at least one bundled preset must ship", presets.isNotEmpty())
    }

    @Test
    fun keysAreUniqueAndDoNotCollideWithCustomSentinel() {
        val keys = presets.map { it.key }
        assertEquals("preset keys must be unique", keys.size, keys.toSet().size)
        assertFalse(
            "no built-in may use the WAKE_WORD_CUSTOM sentinel",
            keys.contains(SettingsRepository.WAKE_WORD_CUSTOM),
        )
    }

    @Test
    fun assetFilenamesEndInOnnx() {
        for (p in presets) {
            assertTrue("${p.key}: asset filename '${p.assetFilename}' must end in .onnx",
                p.assetFilename.endsWith(".onnx"))
        }
    }

    @Test
    fun rowsHaveNoBlankFields() {
        for (p in presets) {
            assertTrue("${p.key}: blank field in row $p",
                p.key.isNotBlank() && p.displayName.isNotBlank() &&
                    p.phrase.isNotBlank() && p.assetFilename.isNotBlank())
        }
    }

    @Test
    fun phrasesAreLowercase() {
        // The engine matches the spoken phrase to the trained `.onnx` label.
        // If a copy-paste error introduces "Marmalade" instead of "marmalade",
        // getWakeWords() returns the wrong-cased token and detection silently
        // fails. Custom presets are lowercased in resolveWakeWordPreset();
        // built-in rows are hand-written, so pin the invariant here.
        for (p in presets) {
            assertEquals("${p.key}: phrase must be lowercase",
                p.phrase.lowercase(), p.phrase)
        }
    }
}
