package app.marmalade.android.service

import app.marmalade.android.VoiceWakeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for HotwordService.shouldRunHotwordDetection() eligibility logic.
 * Tests the pure companion predicate directly — no Android context needed.
 *
 * Regression guard: previously HotwordService.onTaskRemoved unconditionally
 * scheduled a doze-piercing AlarmManager restart, which could drain battery
 * by waking the device every ~3s when the OS killed the revived service and
 * the user had wake word disabled / voice mode Off / mic revoked. The
 * predicate gates both the restart alarm and the start-time mic allocation.
 */
class HotwordEligibilityTest {

    @Test
    fun `all three preconditions true returns true`() {
        assertTrue(
            "enabled + non-Off mode + mic permission should run hotword",
            HotwordService.shouldRunHotwordDetection(
                enabled = true,
                mode = VoiceWakeMode.Always,
                hasMicPermission = true,
            ),
        )
    }

    @Test
    fun `foreground mode with enabled and mic returns true`() {
        assertTrue(
            "Foreground mode is non-Off, so detection should run when enabled + mic",
            HotwordService.shouldRunHotwordDetection(
                enabled = true,
                mode = VoiceWakeMode.Foreground,
                hasMicPermission = true,
            ),
        )
    }

    @Test
    fun `hotwordEnabled false short-circuits to false`() {
        assertFalse(
            "User opted out of hotword via settings — should not run",
            HotwordService.shouldRunHotwordDetection(
                enabled = false,
                mode = VoiceWakeMode.Always,
                hasMicPermission = true,
            ),
        )
    }

    @Test
    fun `voiceMode Off short-circuits to false`() {
        assertFalse(
            "VoiceWakeMode.Off must suppress hotword regardless of other flags",
            HotwordService.shouldRunHotwordDetection(
                enabled = true,
                mode = VoiceWakeMode.Off,
                hasMicPermission = true,
            ),
        )
    }

    @Test
    fun `missing mic permission short-circuits to false`() {
        assertFalse(
            "No RECORD_AUDIO permission — must not arm mic or restart alarm",
            HotwordService.shouldRunHotwordDetection(
                enabled = true,
                mode = VoiceWakeMode.Always,
                hasMicPermission = false,
            ),
        )
    }

    @Test
    fun `all three preconditions false returns false`() {
        assertFalse(
            "All preconditions false → no hotword",
            HotwordService.shouldRunHotwordDetection(
                enabled = false,
                mode = VoiceWakeMode.Off,
                hasMicPermission = false,
            ),
        )
    }

    @Test
    fun `enabled false with foreground mode still returns false`() {
        assertFalse(
            "hotwordEnabled is an independent kill-switch even if mode is non-Off",
            HotwordService.shouldRunHotwordDetection(
                enabled = false,
                mode = VoiceWakeMode.Foreground,
                hasMicPermission = true,
            ),
        )
    }
}
