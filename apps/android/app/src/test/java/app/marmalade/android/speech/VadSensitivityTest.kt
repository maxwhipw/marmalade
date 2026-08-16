package app.marmalade.android.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class VadSensitivityTest {

    @Test
    fun `slider at 0 maps to 0_3s`() {
        assertEquals(0.3f, vadSliderToSilenceDuration(0.0f), 0.001f)
    }

    @Test
    fun `slider at 0_25 maps to about 0_6s`() {
        assertEquals(0.6f, vadSliderToSilenceDuration(0.25f), 0.001f)
    }

    @Test
    fun `slider at 0_5 maps to about 0_9s`() {
        assertEquals(0.9f, vadSliderToSilenceDuration(0.5f), 0.001f)
    }

    @Test
    fun `slider at 0_75 maps to about 1_2s`() {
        assertEquals(1.2f, vadSliderToSilenceDuration(0.75f), 0.001f)
    }

    @Test
    fun `slider at 1 maps to 1_5s`() {
        assertEquals(1.5f, vadSliderToSilenceDuration(1.0f), 0.001f)
    }
}
