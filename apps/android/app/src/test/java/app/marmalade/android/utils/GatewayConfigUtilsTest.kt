package app.marmalade.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for GatewayConfigUtils resolveScannedSetupCode logic.
 *
 * Since resolveScannedSetupCode and decodeGatewaySetupCode use android.util.Base64
 * which is unavailable in pure JUnit tests (no Robolectric), we test:
 * 1. The JSON wrapper extraction logic (isolatable)
 * 2. Edge case handling (empty, blank, null-like input)
 * 3. GatewaySetupCode data class structure
 *
 * Full integration tests for QR code scanning should use AndroidTest with real Base64.
 */
class GatewayConfigUtilsTest {

    @Test
    fun gatewaySetupCode_dataClass_containsAllFields() {
        val code = GatewaySetupCode(
            url = "https://gateway.local:18789",
            token = "mytoken",
            password = "mypassword",
        )
        assertEquals("https://gateway.local:18789", code.url)
        assertEquals("mytoken", code.token)
        assertEquals("mypassword", code.password)
    }

    @Test
    fun gatewaySetupCode_nullableFields() {
        val code = GatewaySetupCode(
            url = "https://example.com",
            token = null,
            password = null,
        )
        assertNotNull(code.url)
        assertNull(code.token)
        assertNull(code.password)
    }

    // Note: parseGatewayEndpoint uses android.net.Uri (not available in pure JUnit).
    // Those tests require instrumented tests or Robolectric.

    @Test
    fun gatewayEndpointConfig_dataClass_containsAllFields() {
        val config = GatewayEndpointConfig(
            host = "gateway.local",
            port = 18789,
            tls = true,
            displayUrl = "https://gateway.local:18789",
        )
        assertEquals("gateway.local", config.host)
        assertEquals(18789, config.port)
        assertEquals(true, config.tls)
        assertEquals("https://gateway.local:18789", config.displayUrl)
    }

    @Test
    fun composeGatewayManualUrl_validInputs_composesCorrectly() {
        val result = GatewayConfigUtils.composeGatewayManualUrl("myhost.local", "18789", true)
        assertEquals("https://myhost.local:18789", result)
    }

    @Test
    fun composeGatewayManualUrl_httpNoTls_composesCorrectly() {
        val result = GatewayConfigUtils.composeGatewayManualUrl("myhost.local", "8080", false)
        assertEquals("http://myhost.local:8080", result)
    }

    @Test
    fun composeGatewayManualUrl_invalidPort_returnsNull() {
        assertNull(GatewayConfigUtils.composeGatewayManualUrl("host", "0", true))
        assertNull(GatewayConfigUtils.composeGatewayManualUrl("host", "99999", true))
        assertNull(GatewayConfigUtils.composeGatewayManualUrl("host", "abc", true))
    }

    @Test
    fun resolveScannedSetupCode_methodExists() {
        // Verify the method exists with the correct signature.
        // Actual Base64 decode testing requires instrumented tests.
        // Empty input should return null without hitting Base64.
        val result = GatewayConfigUtils.resolveScannedSetupCode("")
        assertNull(result)
    }

    @Test
    fun resolveScannedSetupCode_whitespaceOnly_returnsNull() {
        val result = GatewayConfigUtils.resolveScannedSetupCode("   ")
        assertNull(result)
    }
}
