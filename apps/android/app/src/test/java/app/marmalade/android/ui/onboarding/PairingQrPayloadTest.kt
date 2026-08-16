package app.marmalade.android.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pairing-QR payload parsing — versioned JSON, reject-with-message on garbage. */
class PairingQrPayloadTest {

    @Test
    fun `full payload parses`() {
        val p = PairingQrPayload.parse(
            """{"v":1,"url":"http://host:9119","token":"tok","pluginUrl":"http://host:9211","pluginToken":"ptok"}""",
        ).getOrThrow()
        assertEquals("http://host:9119", p.url)
        assertEquals("tok", p.token)
        assertEquals("http://host:9211", p.pluginUrl)
        assertEquals("ptok", p.pluginToken)
    }

    @Test
    fun `dashboard-only payload parses, plugin fields null`() {
        val p = PairingQrPayload.parse("""{"v":1,"url":"http://h:9119","token":"t"}""").getOrThrow()
        assertNull(p.pluginUrl)
        assertNull(p.pluginToken)
    }

    @Test
    fun `unknown keys tolerated, future version rejected`() {
        assertTrue(
            PairingQrPayload.parse("""{"v":1,"url":"u","token":"t","extra":42}""").isSuccess,
        )
        val err = PairingQrPayload.parse("""{"v":2,"url":"u","token":"t"}""").exceptionOrNull()
        assertTrue(err!!.message!!.contains("version"))
    }

    @Test
    fun `garbage and missing fields fail with readable messages`() {
        assertEquals(
            "Not a Marmalade pairing QR code",
            PairingQrPayload.parse("https://example.com/some-random-qr").exceptionOrNull()?.message,
        )
        assertTrue(
            PairingQrPayload.parse("""{"v":1,"url":"","token":"t"}""")
                .exceptionOrNull()!!.message!!.contains("dashboard URL"),
        )
        assertTrue(
            PairingQrPayload.parse("""{"v":1,"url":"u","token":""}""")
                .exceptionOrNull()!!.message!!.contains("session token"),
        )
    }
}
