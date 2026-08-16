package app.marmalade.android.rpc

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tiny inline replacement for kotlin.test.assertFailsWith — kotlin-test isn't
 * pulled in as a dep yet and we don't want to add a transitive dependency
 * just for one helper. Inlined so suspend bodies inside the lambda compile
 * under the calling coroutine context.
 */
private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("expected ${T::class.simpleName} but got ${e::class.simpleName}: $e")
    }
    throw AssertionError("expected ${T::class.simpleName} but block completed normally")
}

@kotlinx.coroutines.ExperimentalCoroutinesApi
class JsonRpcClientTest {

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    fun `request returns the result field`() = runTest {
        val (client, socket) = openedClient()

        val deferredResult = async {
            client.request("session.create", buildJsonObject { put("foo", "bar") })
        }
        runCurrent()

        val sentFrame = socket.sent.last().asJson()
        val id = sentFrame["id"]!!.jsonPrimitive.content
        assertEquals("session.create", sentFrame["method"]!!.jsonPrimitive.content)

        socket.simulateMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", buildJsonObject { put("session_id", "abc") })
            }.toString()
        )

        val resolved = deferredResult.await()
        assertEquals("abc", resolved!!.jsonObject["session_id"]!!.jsonPrimitive.content)
    }

    // ── error path ──────────────────────────────────────────────────────────

    @Test
    fun `error response throws JsonRpcException with code + method`() = runTest {
        val (client, socket) = openedClient()

        // runCatching inside the async — see also `close rejects pending`
        // below. TestScope's job is NOT a supervisor, so an exception thrown
        // inside `async { ... }` propagates to the test job and fails the
        // test BEFORE the `call.await()` line is reached. runCatching keeps
        // the throwable inside the Result.
        val call = async { runCatching { client.request("model.options") } }
        runCurrent()
        val id = socket.sent.last().asJson()["id"]!!.jsonPrimitive.content
        socket.simulateMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject {
                    put("code", -32601)
                    put("message", "method not found")
                })
            }.toString()
        )

        val exc = call.await().exceptionOrNull()
        assertTrue("expected JsonRpcException, got $exc", exc is JsonRpcException)
        exc as JsonRpcException
        assertEquals(-32601, exc.code)
        assertEquals("model.options", exc.method)
        assertTrue(exc.message!!.contains("model.options"))
        assertTrue(exc.message!!.contains("method not found"))
        // Compile-time check (lets a future change to the class hierarchy
        // break this test) that JsonRpcException IS an IOException — that's
        // the contract callers rely on when they catch one arm.
        val _ioExceptionView: IOException = exc
    }

    // ── timeout ─────────────────────────────────────────────────────────────

    @Test
    fun `request times out and removes pending entry`() = runTest {
        val (client, socket) = openedClient(
            options = JsonRpcClient.Options(requestTimeout = 50.milliseconds),
        )

        val call = async { runCatching { client.request("session.list") } }
        runCurrent()
        advanceTimeBy(60.milliseconds)
        val exc = call.await().exceptionOrNull()
        assertTrue(
            "expected TimeoutCancellationException, got $exc",
            exc is kotlinx.coroutines.TimeoutCancellationException,
        )
        assertEquals(1, socket.sent.size)
    }

    // ── events flow ─────────────────────────────────────────────────────────

    @Test
    fun `server events arrive on the events flow in order`() = runTest {
        val (client, socket) = newClient()

        // Start collecting BEFORE connect to catch gateway.ready (the same
        // ordering desktop + web rely on).
        val collector = async { client.events.take(2).toList() }
        runCurrent()  // ensure the subscriber is attached before any emits

        openConnection(client, socket)
        socket.simulateMessage(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"skin":"default"}}}"""
        )
        socket.simulateMessage(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s1","payload":{"text":"hi"}}}"""
        )

        val collected = collector.await()
        assertEquals(2, collected.size)
        assertEquals("gateway.ready", collected[0].type)
        assertEquals("message.delta", collected[1].type)
        assertEquals("s1", collected[1].sessionId)
    }

    @Test
    fun `eventsOfType filters by event type`() = runTest {
        val (client, socket) = newClient()

        val collector = async { client.eventsOfType("message.delta").first() }
        runCurrent()

        openConnection(client, socket)
        socket.simulateMessage(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}"""
        )
        socket.simulateMessage(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s2"}}"""
        )

        val event = collector.await()
        assertEquals("message.delta", event.type)
        assertEquals("s2", event.sessionId)
    }

    // ── close cleanup ───────────────────────────────────────────────────────

    @Test
    fun `close rejects pending requests and transitions to Closed`() = runTest {
        val (client, socket) = openedClient()
        val call = async { runCatching { client.request("session.list") } }
        runCurrent()

        client.close()

        val exc = call.await().exceptionOrNull()
        assertTrue("expected IOException, got $exc", exc is IOException)
        assertTrue(exc!!.message!!.contains("closed"))
        assertEquals(ConnectionState.Closed, client.connectionState.value)
    }

    // ── coroutine cancellation cleanup ──────────────────────────────────────

    @Test
    fun `coroutine cancellation removes pending entry`() = runTest {
        val (client, socket) = openedClient(
            options = JsonRpcClient.Options(requestTimeout = 60.seconds),
        )

        val call = async { client.request("session.list") }
        runCurrent()
        call.cancelAndJoin()

        // The late response should be harmlessly ignored — no crash, no leak.
        val id = socket.sent.last().asJson()["id"]!!.jsonPrimitive.content
        socket.simulateMessage("""{"jsonrpc":"2.0","id":"$id","result":{"foo":1}}""")
    }

    // ── malformed frames ────────────────────────────────────────────────────

    @Test
    fun `malformed frame is silently dropped`() = runTest {
        val (client, socket) = openedClient()

        socket.simulateMessage("not json")
        socket.simulateMessage("""{"jsonrpc":"2.0"}""")  // no id, no method
        socket.simulateMessage("""{"jsonrpc":"2.0","method":"event","params":"not an object"}""")
        assertEquals(ConnectionState.Open, client.connectionState.value)
    }

    // ── notify (one-way) ────────────────────────────────────────────────────

    @Test
    fun `notify sends a frame with no id`() = runTest {
        val (client, socket) = openedClient()

        client.notify("custom.cancel", buildJsonObject { put("session_id", "s1") })

        val frame = socket.sent.last().asJson()
        assertNull(frame["id"])
        assertEquals("custom.cancel", frame["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `notify before connect throws IOException`() = runTest {
        val (client, _) = newClient()
        assertFailsWith<IOException> { client.notify("foo") }
    }

    // ── request before connect ──────────────────────────────────────────────

    @Test
    fun `request before connect throws IOException`() = runTest {
        val (client, _) = newClient()
        val exc = assertFailsWith<IOException> { client.request("session.list") }
        assertTrue(exc.message!!.contains("not connected"))
    }

    // ── idempotent connect ──────────────────────────────────────────────────

    @Test
    fun `connect while already Open returns without error`() = runTest {
        val (client, socket) = openedClient()
        assertEquals(ConnectionState.Open, client.connectionState.value)
        // Second connect with the socket already Open: short-circuits inside
        // lifecycleMutex without dispatching to the listener. No simulateOpen
        // needed; no deadlock risk.
        client.connect("ws://test/api/ws")
        assertEquals(ConnectionState.Open, client.connectionState.value)
    }

    // ── close during in-flight connect ─────────────────────────────────────

    @Test
    fun `close during in-flight connect fails fast instead of hanging until connectTimeout`() = runTest {
        val (client, socket) = newClient(
            options = JsonRpcClient.Options(connectTimeout = 60.seconds),
        )

        // Start connect() and let it park on openDeferred.await() — no
        // simulateOpen, so without the fix this would sit until
        // connectTimeout (60s virtual time here) before failing.
        val connectJob = async { runCatching { client.connect("ws://test/api/ws") } }
        runCurrent()

        client.close()

        // The fix: close() completes the in-flight openDeferred exceptionally,
        // so connect() fails immediately — no advanceTimeBy needed at all.
        val exc = connectJob.await().exceptionOrNull()
        assertTrue("expected an exception from connect(), got success", exc != null)
        assertEquals(ConnectionState.Closed, client.connectionState.value)
        assertTrue("socket.close() should have been requested", socket.closeRequested)
    }

    @Test
    fun `connect after close-during-connect is not blocked by the stuck lifecycleMutex`() = runTest {
        val (client, socket) = newClient(
            options = JsonRpcClient.Options(connectTimeout = 60.seconds),
        )

        val firstConnect = async { runCatching { client.connect("ws://test/api/ws") } }
        runCurrent()
        client.close()
        firstConnect.await()

        // Mirrors reconnectDashboard(): close() immediately followed by a
        // fresh connect(). Before the fix, the first connect() would still
        // be holding lifecycleMutex (stuck until connectTimeout), so this
        // second connect() would never even get to run within this test's
        // bounded virtual time — it would just sit queued on the mutex.
        val secondConnect = async { client.connect("ws://test/api/ws") }
        runCurrent()
        assertTrue(
            "second connect() should have proceeded past the lifecycle mutex " +
                "and parked on its own openDeferred.await()",
            secondConnect.isActive,
        )

        // FakeWebSocket.attach() re-targets the same fake instance at the
        // second connect()'s new Listener, so simulateOpen() here completes
        // the SECOND connect's openDeferred, not a stale one from the first.
        socket.simulateOpen()
        secondConnect.await()
        assertEquals(ConnectionState.Open, client.connectionState.value)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Build a [JsonRpcClient] wired to a [FakeWebSocket] and the test
     * dispatcher so every internal `launch` runs deterministically under
     * [runCurrent] / [advanceTimeBy].
     *
     * Parented to `backgroundScope` — NOT `coroutineContext` — because
     * [JsonRpcClient]'s `init { scope.launch { for (event in eventChannel) }}`
     * never terminates on its own (the channel is closed only by [shutdown],
     * which most tests don't call). Under `coroutineContext`, the pump is a
     * child of the test job, so `runTest` waits for it after the test body
     * returns — manifesting as `UncompletedCoroutinesError` after 60s.
     * `backgroundScope` is the test-framework's escape hatch for exactly this
     * pattern: long-running coroutines that the test framework should
     * silently cancel when the test body finishes.
     */
    private fun TestScope.newClient(
        options: JsonRpcClient.Options = JsonRpcClient.Options(),
    ): Pair<JsonRpcClient, FakeWebSocket> {
        val socket = FakeWebSocket()
        val client = JsonRpcClient(
            webSocketFactory = { _, listener -> socket.attach(listener) },
            options = options,
            logger = JsonRpcClient.Logger.NoOp,
            parentContext = backgroundScope.coroutineContext,
        )
        return client to socket
    }

    /**
     * Open the WebSocket handshake without deadlocking the test body.
     *
     * Why this helper exists: `client.connect()` suspends inside
     * `openDeferred.await()` waiting for the listener's `onOpen` callback.
     * In production OkHttp fires `onOpen` from a real dispatcher thread.
     * In tests we fire it via [FakeWebSocket.simulateOpen]. With
     * [StandardTestDispatcher] (the default in `runTest` since
     * kotlinx-coroutines-test 1.6), if the test body calls `connect()`
     * directly, the test coroutine suspends and CANNOT advance to the next
     * statement — so `simulateOpen` never fires, the deferred never
     * completes, and after 15s virtual time the connect's `withTimeout`
     * cancels with "connect timed out". That's the entire flake class.
     *
     * Fix: launch connect as a child job, [runCurrent] to let it park on
     * `await()`, fire `simulateOpen`, then await the connect job. Fully
     * deterministic, no virtual-time progression needed.
     */
    private suspend fun TestScope.openConnection(
        client: JsonRpcClient,
        socket: FakeWebSocket,
    ) {
        val job = async { client.connect("ws://test/api/ws") }
        runCurrent()  // let connect() run to openDeferred.await()
        socket.simulateOpen()
        job.await()
    }

    /** Convenience: build a client AND open the connection in one call. */
    private suspend fun TestScope.openedClient(
        options: JsonRpcClient.Options = JsonRpcClient.Options(),
    ): Pair<JsonRpcClient, FakeWebSocket> {
        val pair = newClient(options)
        openConnection(pair.first, pair.second)
        return pair
    }
}

// ── test scaffolding ────────────────────────────────────────────────────────

/**
 * A minimal in-memory [WebSocket] that lets a test drive open/message/close
 * deterministically. Captures every `send()` payload in [sent] for outbound-
 * frame assertions.
 */
private class FakeWebSocket : WebSocket {
    private var listener: WebSocketListener? = null
    val sent = mutableListOf<String>()
    var closeRequested = false
        private set

    fun attach(l: WebSocketListener): WebSocket {
        listener = l
        return this
    }

    fun simulateOpen() {
        listener!!.onOpen(this, fakeResponse())
    }

    fun simulateMessage(text: String) {
        listener!!.onMessage(this, text)
    }

    fun simulateClose(code: Int = 1000, reason: String = "test") {
        listener!!.onClosed(this, code, reason)
    }

    // ── WebSocket impl ──
    override fun send(text: String): Boolean { sent += text; return true }
    override fun send(bytes: ByteString): Boolean { sent += bytes.utf8(); return true }
    override fun close(code: Int, reason: String?): Boolean {
        closeRequested = true
        // Real OkHttp routes close through onClosing → ack → onClosed; the test
        // fake collapses that into a single onClosed callback.
        listener?.onClosed(this, code, reason ?: "")
        return true
    }
    override fun cancel() { listener?.onFailure(this, IOException("cancelled"), null) }
    override fun queueSize(): Long = 0L
    override fun request(): Request = Request.Builder().url("ws://fake/").build()

    companion object {
        fun fakeResponse(): Response =
            Response.Builder()
                .request(Request.Builder().url("ws://fake/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build()
    }
}

private fun String.asJson(): JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(this).jsonObject
