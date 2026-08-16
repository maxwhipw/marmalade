package app.marmalade.desktop

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Non-UI proof that [DesktopRuntime] drives the shared KMP stack against a
 * REAL local `marmaladed`: open the socket, complete the v1 `hello`, read the
 * session list.
 *
 * Read-only by construction — `hello`, `session.main`, `session.list`. It never
 * submits a prompt, because the sessions on this box are the maintainer's live ones.
 *
 * Skipped (not failed) when nothing is listening on the daemon port: the spike
 * shouldn't turn a stopped daemon into a red build. It runs against a temp DB
 * so it never touches `~/.marmalade-desktop/`.
 */
class DesktopDaemonSmokeTest {

    @Test
    fun connectsHandshakesAndListsSessions() {
        if (!daemonReachable()) {
            println("SKIP: no marmaladed listening on $DAEMON_HOST:$DAEMON_PORT")
            return
        }

        val dbFile = File.createTempFile("marmalade-desktop-smoke", ".db").apply { delete() }
        val runtime = DesktopRuntime(dbFilePath = dbFile.absolutePath)
        try {
            runBlocking {
                withTimeout(30_000) {
                    runtime.connect()

                    // hello returned — the daemon negotiated protocol v1 and
                    // advertised its feature set.
                    val features = runtime.serverFeatures.value
                    println("features: $features")
                    assertTrue(features.isNotEmpty(), "hello returned no features")

                    // session.list over the same socket, read-only.
                    val sessions = runtime.rpc.sessionList(limit = 20)
                    println("session.list → ${sessions.sessions.size} sessions")
                    sessions.sessions.take(5).forEach {
                        println("  ${it.session_id}  ${it.title ?: "(untitled)"}")
                    }

                    // The daemon-managed main session resolved.
                    println("main session: ${runtime.mainSessionKey.value}")
                    assertTrue(
                        runtime.mainSessionKey.value.isNotBlank(),
                        "session.main did not resolve",
                    )
                }
            }
        } finally {
            runtime.close()
            dbFile.delete()
        }
    }

    private fun daemonReachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(DAEMON_HOST, DAEMON_PORT), 1_000) }
        true
    }.getOrDefault(false)

    private companion object {
        const val DAEMON_HOST = "127.0.0.1"
        const val DAEMON_PORT = 9130
    }
}
