package app.marmalade.android.speech.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmationTrackerTest {

    private val model = WakeModel("Marmalade", "marmalade.onnx", threshold = 0.5f)

    @Test
    fun `single spike above threshold does not fire`() {
        val tracker = ConfirmationTracker(listOf(model), cooldownMs = 3000L)

        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.1f), nowMs = 0L))
        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 80L)) // single hop above threshold
        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.1f), nowMs = 160L))
    }

    @Test
    fun `2 of 3 hops above threshold fires`() {
        val tracker = ConfirmationTracker(listOf(model), cooldownMs = 3000L)

        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 0L))
        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.1f), nowMs = 80L))
        val result = tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 160L)

        assertEquals(WakeDetection("Marmalade", 0.9f), result)
    }

    @Test
    fun `2 consecutive hits fire on the second hop, before the ring is even full`() {
        val tracker = ConfirmationTracker(listOf(model), cooldownMs = 3000L)

        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 0L))
        // 2-of-2 already satisfies "at least 2 of the last 3" -- fires immediately,
        // it does not wait for a 3rd hop to fill the ring.
        val result = tracker.offer(mapOf("marmalade.onnx" to 0.8f), nowMs = 80L)

        assertEquals(WakeDetection("Marmalade", 0.8f), result)
    }

    @Test
    fun `cooldown suppresses repeat detections`() {
        val tracker = ConfirmationTracker(listOf(model), cooldownMs = 3000L)

        // Confirm once.
        tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 0L)
        val first = tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 80L)
        assertEquals(WakeDetection("Marmalade", 0.9f), first)

        // Still within cooldown -- even though the 2-of-3 window re-confirms, no re-fire.
        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 160L))
        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 2000L))

        // Past cooldown -- fires again since the ring still holds 2-of-3.
        val second = tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 3080L)
        assertEquals(WakeDetection("Marmalade", 0.9f), second)
    }

    @Test
    fun `ring only tracks the last 3 hops`() {
        val tracker = ConfirmationTracker(listOf(model), cooldownMs = 3000L)

        // Two early hits fall out of the 3-hop window by the time hop 4 arrives.
        tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 0L)
        tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 80L)
        tracker.offer(mapOf("marmalade.onnx" to 0.1f), nowMs = 160L)
        val result = tracker.offer(mapOf("marmalade.onnx" to 0.1f), nowMs = 240L)

        assertNull(result) // ring is now [0.1, 0.1... wait 3rd] -- only 1 hit in last 3
    }

    @Test
    fun `SINGLE_BEST picks the higher-scoring model within a hop`() {
        val modelA = WakeModel("Marmalade", "marmalade.onnx", threshold = 0.5f)
        val modelB = WakeModel("OpenClaw", "openclaw.onnx", threshold = 0.5f)
        val tracker = ConfirmationTracker(listOf(modelA, modelB), cooldownMs = 3000L)

        // Both models confirm on the same hop (2-of-3 each); B has a higher score this hop.
        tracker.offer(mapOf("marmalade.onnx" to 0.9f, "openclaw.onnx" to 0.6f), nowMs = 0L)
        val result = tracker.offer(
            mapOf("marmalade.onnx" to 0.7f, "openclaw.onnx" to 0.99f),
            nowMs = 80L,
        )

        assertEquals(WakeDetection("OpenClaw", 0.99f), result)
    }

    @Test
    fun `reset clears history and cooldown`() {
        val tracker = ConfirmationTracker(listOf(model), cooldownMs = 3000L)

        tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 0L)
        tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 80L)
        tracker.reset()

        // Immediately after reset, a single hop should not fire (history cleared)
        // and cooldown should not suppress a fresh confirmation later.
        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 160L))
        assertNull(tracker.offer(mapOf("marmalade.onnx" to 0.1f), nowMs = 240L))
        val result = tracker.offer(mapOf("marmalade.onnx" to 0.9f), nowMs = 320L)
        assertEquals(WakeDetection("Marmalade", 0.9f), result)
    }
}
