package app.marmalade.android.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classifying attach failures. The distinction matters because "gone" prunes
 * the roster row and a transport failure must NOT — the shell survives a
 * dropped socket, so pruning on a network blip would delete a live terminal
 * from the UI.
 */
class TerminalErrorsTest {

    @Test
    fun `the daemon's real unknown-terminal error is gone`() {
        // Verbatim from the device, 2026-07-25.
        assertTrue(
            TerminalErrors.isGone(
                "terminal.attach failed (rpc -32602): unknown terminal " +
                    "t_3ef58cae-0d2f-4438-aae7-d9769a9c3e17",
            ),
        )
    }

    @Test
    fun `wording and casing variants still classify`() {
        assertTrue(TerminalErrors.isGone("Unknown Terminal t_1"))
        assertTrue(TerminalErrors.isGone("no such terminal t_1"))
        // Reworded but still the invalid-params code about a terminal.
        assertTrue(TerminalErrors.isGone("terminal.attach failed (rpc -32602): bad id"))
    }

    @Test
    fun `transport failures are NOT gone`() {
        // The shell outlives a dropped socket — pruning here would remove a
        // live terminal from the roster.
        assertFalse(TerminalErrors.isGone("Failed to connect to 127.0.0.1:9130"))
        assertFalse(TerminalErrors.isGone("timeout waiting for response"))
        assertFalse(TerminalErrors.isGone("SocketTimeoutException"))
        assertFalse(TerminalErrors.isGone(null))
        assertFalse(TerminalErrors.isGone(""))
    }

    @Test
    fun `an unrelated invalid-params error is not a gone terminal`() {
        assertFalse(TerminalErrors.isGone("session.create failed (rpc -32602): bad cwd"))
    }

    @Test
    fun `the user-facing message names the cause and the next step`() {
        val msg = TerminalErrors.goneMessage()
        assertTrue(msg.contains("daemon restarted"))
        assertTrue(msg.contains("Start a new terminal"))
    }
}
