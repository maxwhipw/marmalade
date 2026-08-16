package app.marmalade.android.rpc

import app.marmalade.android.chat.messages.MessageStream
import app.marmalade.android.chat.messages.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * PR1 smoke test — round-trip a `prompt.submit` against a live marmalade-agent.
 *
 * Per task #6, this is the proof point that JsonRpcClient + MarmaladeRpc +
 * MessageStream actually integrate end-to-end against a real server. The
 * test:
 *
 *  1. Connects to `MARMALADE_WS_URL` via the new JsonRpcClient (auth lives in
 *     the URL's `?token=...` query param — same as desktop / web).
 *  2. Calls `session.create` via MarmaladeRpc.
 *  3. Submits a deterministic prompt ("Reply with the word PONG, …").
 *  4. Drains MessageStream's SessionMessages until `streaming == false`.
 *  5. Asserts the assistant bubble's text contains "PONG".
 *
 * ## How to run
 *
 * Mint a WS URL (token mode) from a running marmalade-agent instance —
 * scrape `__HERMES_SESSION_TOKEN__` from the dashboard's HTML root, or grab
 * the value from `~/.marmalade/.env` (HERMES_SESSION_TOKEN), then:
 *
 *     export MARMALADE_WS_URL="ws://127.0.0.1:9119/api/ws?token=<token>"
 *     ./gradlew :app:testDebugUnitTest --tests "*MarmaladeIntegrationTest*"
 *
 * If `MARMALADE_WS_URL` is unset, the test is skipped via JUnit's `Assume`
 * — green build on CI even when no marmalade-agent is reachable.
 *
 * ## Caveat: not runnable until compile is restored
 *
 * The repo doesn't compile until task #7 (production wiring) rewrites the
 * orphan ChatController/RunStateMachine references. Until then this test
 * serves as live spec — run it once `:app:assembleDebug` is green.
 */
class MarmaladeIntegrationTest {

    @Test
    fun `connects and round-trips a prompt`() = runBlocking {
        val wsUrl = System.getenv("MARMALADE_WS_URL")
        assumeNotNull("MARMALADE_WS_URL not set — skipping integration test", wsUrl)

        // Long ws read timeout so streaming responses don't time out mid-turn.
        val httpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = JsonRpcClient(
            httpClient = httpClient,
            options = JsonRpcClient.Options(
                connectTimeout = 15.seconds,
                requestTimeout = 60.seconds,
            ),
            logger = JsonRpcClient.Logger.NoOp,
            parentContext = scope.coroutineContext,
        )
        val rpc = MarmaladeRpc(client)
        val stream = MessageStream(
            client = client,
            scope = scope,
            chatDao = app.marmalade.android.chat.messages.FakeChatDao(),
            json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )

        try {
            client.connect(wsUrl) // suspends until open handshake completes

            // Spin up a fresh session and submit a deterministic prompt.
            val created = rpc.sessionCreate()
            val sessionId = created.session_id
            assertTrue("session_id should be non-empty", sessionId.isNotEmpty())

            // Single-session connections receive unscoped events for the
            // focused turn (the gateway only stamps session_id on background
            // sessions). Without setActiveSession the live answer would be
            // dropped — see MessageStream.resolveSessionId.
            stream.setActiveSession(sessionId)

            rpc.promptSubmit(
                sessionId = sessionId,
                text = "Reply with the single word PONG, in one line, and nothing else.",
            )

            // Drain the message stream until the turn finishes (or 60s elapses).
            val sessionState = stream.sessionMessages(sessionId)
            val finalized = withTimeout(60.seconds) {
                sessionState
                    .filter { !it.streaming && it.messages.isNotEmpty() }
                    .first()
            }

            val messages = finalized.messages
            assertTrue("assistant produced at least one message", messages.isNotEmpty())
            val assistantText = messages.last().text()
            assertTrue(
                "expected PONG in assistant reply, got: $assistantText",
                assistantText.uppercase().contains("PONG"),
            )

            rpc.sessionInterrupt(sessionId)
        } finally {
            client.shutdown()
            scope.cancel()
            httpClient.dispatcher.executorService.shutdown()
        }
    }
}
