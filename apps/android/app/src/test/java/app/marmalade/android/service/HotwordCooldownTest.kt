package app.marmalade.android.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for HotwordService.shouldTrigger() cooldown logic.
 * Tests the companion method directly — no Android context needed.
 */
class HotwordCooldownTest {

    @Test
    fun `first detection always triggers (lastDetectionTime is 0)`() {
        val now = System.currentTimeMillis()
        assertTrue(
            "First detection (lastDetectionTime=0) should always trigger",
            HotwordService.shouldTrigger(lastDetectionTime = 0L, now = now)
        )
    }

    @Test
    fun `detection within cooldown is suppressed`() {
        val lastDetection = 1000L
        val now = lastDetection + 2999L // 2999ms < 3000ms cooldown
        assertFalse(
            "Detection within cooldown window should be suppressed",
            HotwordService.shouldTrigger(lastDetectionTime = lastDetection, now = now)
        )
    }

    @Test
    fun `detection after cooldown triggers`() {
        val lastDetection = 1000L
        val now = lastDetection + 3001L // 3001ms > 3000ms cooldown
        assertTrue(
            "Detection after cooldown expires should trigger",
            HotwordService.shouldTrigger(lastDetectionTime = lastDetection, now = now)
        )
    }

    @Test
    fun `detection exactly at cooldown boundary triggers`() {
        val lastDetection = 1000L
        val now = lastDetection + HotwordService.COOLDOWN_MS // exactly 3000ms
        assertTrue(
            "Detection exactly at cooldown boundary (3000ms) should trigger",
            HotwordService.shouldTrigger(lastDetectionTime = lastDetection, now = now)
        )
    }

    @Test
    fun `cooldown is 3 seconds`() {
        // Verify the constant value matches the spec
        assertTrue(
            "COOLDOWN_MS should be 3000",
            HotwordService.COOLDOWN_MS == 3000L
        )
    }

    @Test
    fun `custom cooldown value is respected`() {
        val lastDetection = 1000L
        val customCooldown = 5000L
        val now = lastDetection + 4000L // 4000ms < 5000ms custom cooldown
        assertFalse(
            "Custom cooldown should be respected",
            HotwordService.shouldTrigger(lastDetectionTime = lastDetection, now = now, cooldownMs = customCooldown)
        )
    }
}
