package app.marmalade.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SettingsRepository.mapSensitivityToThreshold().
 *
 * These assert invariants, not tuning snapshots. The exact float values
 * have been retuned several times (sherpa-onnx KWS → openWakeWord) and
 * pinning them ties tests to whichever classifier ships today. The
 * contract that matters:
 *
 *   - low ≥ medium ≥ high (lower threshold = more sensitive)
 *   - all values are valid probabilities in (0, 1)
 *   - "unknown" / "" / unrecognized strings fall back to medium
 *   - matching is case-insensitive (production calls .lowercase())
 */
class WakeWordSensitivityTest {

    private fun map(s: String) = SettingsRepository.mapSensitivityToThreshold(s)

    @Test
    fun thresholdsAreOrdered_lowAboveMediumAboveHigh() {
        val low = map("low")
        val medium = map("medium")
        val high = map("high")
        assertTrue("low ($low) must exceed medium ($medium)", low > medium)
        assertTrue("medium ($medium) must exceed high ($high)", medium > high)
    }

    @Test
    fun thresholdsAreValidProbabilities() {
        for (level in listOf("low", "medium", "high")) {
            val v = map(level)
            assertTrue("$level threshold $v must be > 0", v > 0f)
            assertTrue("$level threshold $v must be < 1", v < 1f)
        }
    }

    @Test
    fun unknownDefaultsToMedium() {
        assertEquals(map("medium"), map("unknown"), 0.0001f)
    }

    @Test
    fun emptyStringDefaultsToMedium() {
        assertEquals(map("medium"), map(""), 0.0001f)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(map("low"), map("LOW"), 0.0001f)
        assertEquals(map("medium"), map("Medium"), 0.0001f)
        assertEquals(map("high"), map("HIGH"), 0.0001f)
    }

    @Test
    fun whitespaceIsNotTrimmed_fallsBackToMedium() {
        // Current behavior: "low " (trailing space) is not "low" after .lowercase(),
        // so it goes through the else branch. Documenting, not advocating.
        assertEquals(map("medium"), map("low "), 0.0001f)
    }
}
