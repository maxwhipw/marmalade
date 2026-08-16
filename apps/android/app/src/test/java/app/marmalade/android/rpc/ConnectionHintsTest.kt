package app.marmalade.android.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Localhost-on-a-phone guidance (hardening plan #3). */
class ConnectionHintsTest {

    @Test
    fun `loopback hosts get guidance`() {
        assertNotNull(ConnectionHints.localhostGuidance("http://127.0.0.1:9130"))
        assertNotNull(ConnectionHints.localhostGuidance("http://localhost:9119"))
        assertNotNull(ConnectionHints.localhostGuidance("https://LOCALHOST"))
        assertNotNull(ConnectionHints.localhostGuidance("ws://127.0.0.1:9130/api/ws"))
        assertNotNull(ConnectionHints.localhostGuidance("http://[::1]:9130"))
    }

    @Test
    fun `real hosts do not`() {
        assertNull(ConnectionHints.localhostGuidance("http://192.0.2.10:9130"))
        assertNull(ConnectionHints.localhostGuidance("https://host.example.ts.net:8443"))
        assertNull(ConnectionHints.localhostGuidance("http://192.168.0.10:9119"))
        // Emulator host alias is CORRECT on an emulator — no nagging.
        assertNull(ConnectionHints.localhostGuidance("http://10.0.2.2:9130"))
        assertNull(ConnectionHints.localhostGuidance(""))
        assertNull(ConnectionHints.localhostGuidance("   "))
    }

    @Test
    fun `host extraction handles ports paths and missing schemes`() {
        assertEquals("127.0.0.1", ConnectionHints.host("http://127.0.0.1:9130/api/ws?x=1"))
        assertEquals("localhost", ConnectionHints.host("localhost:9119"))
        assertEquals("[::1]", ConnectionHints.host("http://[::1]:9130"))
    }
}
