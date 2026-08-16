package app.marmalade.desktop

import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

/**
 * Offline proof that [DesktopRuntime.start] keeps retrying: a connect that
 * throws (daemon not running / restarting) must land the loop back in backoff
 * instead of killing it, which is the failure mode the one-shot `connect()`
 * LaunchedEffect it replaced actually had.
 *
 * Points at a closed loopback port, so it never touches a daemon or the maintainer's live
 * sessions — connection-refused is the same Error transition a daemon restart
 * produces, arriving faster.
 */
class DesktopReconnectLoopTest {

    @Test
    fun retriesAfterAFailedConnect() {
        val dbFile = File.createTempFile("marmalade-desktop-reconnect", ".db").apply { delete() }
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val runtime = DesktopRuntime(
            daemonHttpUrl = "http://127.0.0.1:${closedLoopbackPort()}",
            dbFilePath = dbFile.absolutePath,
            log = { lines.add(it) },
        )
        try {
            runtime.start()
            runBlocking {
                // First attempt is immediate; the retry lands one backoff step
                // (1 s) after the refusal. 10 s is slack for a loaded CI box,
                // not the expected duration.
                withTimeout(10_000) {
                    while (attempts(lines) < 2) delay(50)
                }
            }
            assertTrue(
                lines.any { it.startsWith("reconnecting in") },
                "no backoff was scheduled: $lines",
            )
        } finally {
            runtime.close()
            dbFile.delete()
        }
    }

    private fun attempts(lines: List<String>): Int =
        synchronized(lines) { lines.count { it.startsWith("connecting to") } }

    /** A port nothing is listening on: bind one, learn its number, release it. */
    private fun closedLoopbackPort(): Int = ServerSocket(0).use { it.localPort }
}
