package app.marmalade.android.speech.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadGateTest {

    @Test
    fun `closed while probability stays below threshold`() {
        val gate = VadGate(speechThreshold = 0.5f, hangoverMs = 1500L)

        assertFalse(gate.offer(0.1f, nowMs = 0L))
        assertFalse(gate.offer(0.3f, nowMs = 80L))
        assertFalse(gate.isOpen)
    }

    @Test
    fun `opens immediately when probability crosses threshold`() {
        val gate = VadGate(speechThreshold = 0.5f, hangoverMs = 1500L)

        assertFalse(gate.offer(0.1f, nowMs = 0L))
        assertTrue(gate.offer(0.6f, nowMs = 80L))
        assertTrue(gate.isOpen)
    }

    @Test
    fun `stays open through hangover after speech ends`() {
        val gate = VadGate(speechThreshold = 0.5f, hangoverMs = 1500L)

        gate.offer(0.6f, nowMs = 0L) // speech starts
        // Probability drops below threshold, but within hangover window.
        assertTrue(gate.offer(0.1f, nowMs = 500L))
        assertTrue(gate.offer(0.1f, nowMs = 1400L))
    }

    @Test
    fun `closes after hangover elapses with no further speech`() {
        val gate = VadGate(speechThreshold = 0.5f, hangoverMs = 1500L)

        gate.offer(0.6f, nowMs = 0L) // speech starts, lastSpeechTime = 0
        assertTrue(gate.offer(0.1f, nowMs = 1400L)) // still within 1500ms hangover
        assertFalse(gate.offer(0.1f, nowMs = 1600L)) // 1600ms since last speech > hangover
    }

    @Test
    fun `renewed speech during hangover resets the hangover clock`() {
        val gate = VadGate(speechThreshold = 0.5f, hangoverMs = 1500L)

        gate.offer(0.6f, nowMs = 0L)
        assertTrue(gate.offer(0.1f, nowMs = 1000L)) // within hangover
        assertTrue(gate.offer(0.6f, nowMs = 1400L)) // speech resumes, clock resets to 1400
        assertTrue(gate.offer(0.1f, nowMs = 2800L)) // 1400ms since renewed speech, still within 1500ms
        assertFalse(gate.offer(0.1f, nowMs = 2950L)) // 1550ms since renewed speech, hangover elapsed
    }

    @Test
    fun `reset clears open state and hangover clock`() {
        val gate = VadGate(speechThreshold = 0.5f, hangoverMs = 1500L)

        gate.offer(0.6f, nowMs = 0L)
        assertTrue(gate.isOpen)

        gate.reset()
        assertFalse(gate.isOpen)
        // After reset, silence should read closed rather than riding a stale hangover.
        assertFalse(gate.offer(0.1f, nowMs = 100L))
    }

    @Test
    fun `probability exactly at threshold does not open the gate`() {
        val gate = VadGate(speechThreshold = 0.5f, hangoverMs = 1500L)

        assertFalse(gate.offer(0.5f, nowMs = 0L))
    }
}
