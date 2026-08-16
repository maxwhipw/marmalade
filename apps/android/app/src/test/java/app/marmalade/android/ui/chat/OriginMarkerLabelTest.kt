package app.marmalade.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The user-bubble marker for daemon-minted cross-session/scheduled origins.
 * "cron" → "Scheduled"; "agent" → "from session X" off origin.deviceId
 * ("session:<sender>"); everything else (text/voice/null) is unmarked here —
 * voice has its own mic affordance.
 */
class OriginMarkerLabelTest {

    @Test
    fun `cron renders Scheduled`() {
        assertEquals("Scheduled", originMarkerLabel("cron", "cron"))
        // deviceId is irrelevant for cron.
        assertEquals("Scheduled", originMarkerLabel("cron", null))
    }

    @Test
    fun `agent renders the sending session, shortened`() {
        assertEquals("From session abc12345", originMarkerLabel("agent", "session:abc12345def"))
    }

    @Test
    fun `agent without a resolvable sender falls back`() {
        assertEquals("From another session", originMarkerLabel("agent", null))
        assertEquals("From another session", originMarkerLabel("agent", "session:"))
        assertEquals("From another session", originMarkerLabel("agent", "   "))
    }

    @Test
    fun `text voice and unknown render no marker`() {
        assertNull(originMarkerLabel("text", null))
        assertNull(originMarkerLabel("voice", "pixel-8a"))
        assertNull(originMarkerLabel(null, null))
        assertNull(originMarkerLabel("telegram", "tg:123"))
    }
}
