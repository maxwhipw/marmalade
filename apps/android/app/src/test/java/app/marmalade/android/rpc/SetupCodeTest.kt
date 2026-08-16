package app.marmalade.android.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Setup-code decode against the daemon's encodeSetupCode shape (marmalade
 * repo packages/daemon/src/pairing.ts): base64url(JSON {url, token,
 * expires_at_ms}), unpadded (Node's base64url).
 */
class SetupCodeTest {

    private fun encode(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `decodes the daemon's setup-code payload`() {
        val raw = encode("""{"url":"ws://192.0.2.10:9130/api/ws","token":"boot-abc","expires_at_ms":1752300000000}""")
        val code = SetupCode.parse(raw).getOrThrow()
        assertEquals("ws://192.0.2.10:9130/api/ws", code.url)
        assertEquals("boot-abc", code.token)
        assertEquals(1752300000000L, code.expires_at_ms)
    }

    @Test
    fun `tolerates surrounding whitespace and unknown fields`() {
        val raw = "  " + encode("""{"url":"ws://h:1/api/ws","token":"t","expires_at_ms":1,"future":"x"}""") + "\n"
        assertTrue(SetupCode.parse(raw).isSuccess)
    }

    @Test
    fun `dashboardHttpUrl strips the ws path and maps schemes`() {
        val ws = SetupCode(url = "ws://192.0.2.10:9130/api/ws", token = "t", expires_at_ms = 1)
        assertEquals("http://192.0.2.10:9130", ws.dashboardHttpUrl())
        val wss = SetupCode(url = "wss://m.example.ts.net:8443/api/ws", token = "t", expires_at_ms = 1)
        assertEquals("https://m.example.ts.net:8443", wss.dashboardHttpUrl())
    }

    @Test
    fun `expiry is judged against the payload timestamp`() {
        val code = SetupCode(url = "ws://h:1/api/ws", token = "t", expires_at_ms = 1000)
        assertTrue(code.isExpired(nowMs = 2000))
        assertFalse(code.isExpired(nowMs = 500))
    }

    @Test
    fun `garbage and near-misses fail with a readable message`() {
        assertTrue(SetupCode.parse("").isFailure)
        assertTrue(SetupCode.parse("not base64 at all!!!").isFailure)
        // Valid base64 of non-JSON:
        assertTrue(SetupCode.parse(encode("hello world")).isFailure)
        // JSON but not a setup code (the legacy onboarding QR shape):
        assertTrue(SetupCode.parse(encode("""{"v":1,"url":"http://h:9119","token":"t"}""")).isFailure)
        // Missing token:
        assertTrue(SetupCode.parse(encode("""{"url":"ws://h:1","token":"","expires_at_ms":1}""")).isFailure)
        SetupCode.parse("junk").onFailure { assertTrue(it.message!!.isNotBlank()) }
    }
}
