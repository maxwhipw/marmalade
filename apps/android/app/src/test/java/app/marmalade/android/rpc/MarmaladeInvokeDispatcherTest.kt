package app.marmalade.android.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Most tests here exercise [MarmaladeInvokeDispatcher.dispatch] directly —
 * it's intentionally side-effect-free (no events, no scope, no RPC) so it
 * can be tested without standing up a JsonRpcClient or fake server.
 *
 * The concurrency test below additionally drives [MarmaladeInvokeDispatcher.start]
 * end-to-end against a real [JsonRpcClient] wired to an in-memory
 * [FakeWebSocket], proving a slow handler doesn't head-of-line-block a fast
 * one — the scenario that matters once the gateway plugin (live at
 * `~/.marmalade/plugins/marmalade-android/adapter.py`) emits
 * `node.invoke.request` events under real concurrency.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class MarmaladeInvokeDispatcherTest {

  @Test
  fun `unknown tool returns UNKNOWN_TOOL error`() = runTest {
    val dispatcher = dispatcherWithHandlers(emptyMap())

    val result = dispatcher.dispatch(tool = "nonexistent.tool", paramsJson = null)

    assertFalse(result.ok)
    assertNotNull(result.error)
    assertEquals("UNKNOWN_TOOL", result.error?.code)
    assertTrue(
      "error message should name the missing tool",
      result.error?.message.orEmpty().contains("nonexistent.tool"),
    )
  }

  @Test
  fun `registered handler routes through with params`() = runTest {
    var capturedParams: String? = "untouched"
    val dispatcher = dispatcherWithHandlers(
      mapOf(
        "app.launch" to { paramsJson ->
          capturedParams = paramsJson
          InvokeResult.ok("""{"launched":true}""")
        },
      ),
    )

    val result = dispatcher.dispatch(
      tool = "app.launch",
      paramsJson = """{"package":"com.example"}""",
    )

    assertTrue(result.ok)
    assertEquals("""{"launched":true}""", result.payloadJson)
    assertNull(result.error)
    assertEquals("""{"package":"com.example"}""", capturedParams)
  }

  @Test
  fun `registered handler can return error`() = runTest {
    val dispatcher = dispatcherWithHandlers(
      mapOf(
        "sms.send" to { _ ->
          InvokeResult.error(code = "PERMISSION_DENIED", message = "SMS permission not granted")
        },
      ),
    )

    val result = dispatcher.dispatch(tool = "sms.send", paramsJson = "{}")

    assertFalse(result.ok)
    assertEquals("PERMISSION_DENIED", result.error?.code)
    assertEquals("SMS permission not granted", result.error?.message)
    assertNull(result.payloadJson)
  }

  @Test
  fun `null params are passed through as null`() = runTest {
    var sawNull = false
    val dispatcher = dispatcherWithHandlers(
      mapOf(
        "device.status" to { paramsJson ->
          sawNull = (paramsJson == null)
          InvokeResult.ok("{}")
        },
      ),
    )

    dispatcher.dispatch(tool = "device.status", paramsJson = null)

    assertTrue("handler should observe null when no params sent", sawNull)
  }

  // ── concurrency: slow handler must not block a fast one ──────────────────

  @Test
  fun `start dispatches events concurrently so a slow handler does not block a fast one`() = runTest {
    val slowHandlerGate = CompletableDeferred<Unit>()
    val fastHandlerRan = CompletableDeferred<Unit>()

    val handlers = mapOf<String, suspend (String?) -> InvokeResult>(
      "camera.snap" to {
        // Simulate a slow hardware handler (camera, screen record) that
        // doesn't resolve until the test explicitly releases it.
        slowHandlerGate.await()
        InvokeResult.ok("""{"slow":true}""")
      },
      "device.status" to {
        fastHandlerRan.complete(Unit)
        InvokeResult.ok("""{"fast":true}""")
      },
    )

    // A real JsonRpcClient wired to a recording FakeWebSocket (same fake
    // used by JsonRpcClientTest) so node.invoke.respond calls actually
    // flow through the production request() path — captured via sent[].
    val socket = FakeWebSocket()
    val client = JsonRpcClient(
      webSocketFactory = { _, listener -> socket.attach(listener) },
      logger = JsonRpcClient.Logger.NoOp,
      parentContext = backgroundScope.coroutineContext,
    )
    val connectJob = async { client.connect("ws://test/api/ws") }
    runCurrent()
    socket.simulateOpen()
    connectJob.await()

    val eventChannel = Channel<GatewayEvent>(capacity = Channel.UNLIMITED)
    val dispatcher = MarmaladeInvokeDispatcher(
      rpcClient = client,
      events = eventChannel.receiveAsFlow(),
      scope = backgroundScope,
      handlers = handlers,
    )
    dispatcher.start()

    // Slow event first, fast event second — reproduces the head-of-line
    // scenario: before the fix, `collect` awaited dispatch() inline, so the
    // fast event's handler wouldn't even run until the slow one completed.
    eventChannel.send(invokeRequestEvent(requestId = "req-slow", name = "camera.snap"))
    eventChannel.send(invokeRequestEvent(requestId = "req-fast", name = "device.status"))
    runCurrent()

    // With the fix, the fast handler runs (and its node.invoke.respond frame
    // is sent) while the slow one is still parked on its gate.
    assertTrue(
      "fast handler should have run without waiting for the slow handler",
      fastHandlerRan.isCompleted,
    )
    assertTrue(
      "fast request should already have a sent node.invoke.respond frame",
      socket.sent.any { it.asRequestId() == "req-fast" },
    )
    assertFalse(
      "slow request should NOT have responded yet — still gated",
      socket.sent.any { it.asRequestId() == "req-slow" },
    )

    // Release the slow handler; it should now complete and respond too.
    slowHandlerGate.complete(Unit)
    runCurrent()
    assertTrue(socket.sent.any { it.asRequestId() == "req-slow" })
  }

  private fun invokeRequestEvent(
    requestId: String,
    name: String,
    params: JsonObject = buildJsonObject {},
  ): GatewayEvent =
    GatewayEvent(
      type = "node.invoke.request",
      sessionId = "s1",
      payload = buildJsonObject {
        put("name", name)
        put("request_id", requestId)
        put("params", params)
      },
    )

  /** Reads the `request_id` out of a node.invoke.respond frame's params. */
  private fun String.asRequestId(): String? =
    runCatching {
      kotlinx.serialization.json.Json.parseToJsonElement(this)
        .jsonObject["params"]!!.jsonObject["request_id"]!!.jsonPrimitive.content
    }.getOrNull()

  // ── helpers ─────────────────────────────────────────────────────────────

  private fun dispatcherWithHandlers(
    handlers: Map<String, suspend (String?) -> InvokeResult>,
  ): MarmaladeInvokeDispatcher {
    // dispatch() never touches rpc / events / scope. Build a JsonRpcClient
    // with a no-op WebSocketFactory so MarmaladeRpc has a real wire under
    // it — start() would actually connect on its first request, but no
    // test here calls start().
    val client = JsonRpcClient(
      webSocketFactory = { _, listener -> NoOpWebSocket(listener) },
      logger = JsonRpcClient.Logger.NoOp,
    )
    return MarmaladeInvokeDispatcher(
      rpcClient = client,
      events = emptyFlow(),
      scope = CoroutineScope(SupervisorJob()),
      handlers = handlers,
    )
  }

  /** A WebSocket that swallows every operation — used only to give
   *  [MarmaladeRpc] a non-null backing client in tests that don't actually
   *  exercise the wire. */
  private class NoOpWebSocket(@Suppress("UNUSED_PARAMETER") listener: WebSocketListener) : WebSocket {
    override fun request(): Request = throw UnsupportedOperationException()
    override fun queueSize(): Long = 0L
    override fun send(text: String): Boolean = true
    override fun send(bytes: ByteString): Boolean = true
    override fun close(code: Int, reason: String?): Boolean = true
    override fun cancel() {}
  }

  /**
   * Minimal in-memory [WebSocket] fake for driving a real [JsonRpcClient]
   * through open/message/close deterministically — same shape as
   * `JsonRpcClientTest`'s private fake, duplicated here (file-private
   * there) so this test can exercise [MarmaladeInvokeDispatcher.start]
   * against production `request()` plumbing instead of a hand-rolled
   * `JsonRpcClient` stand-in.
   */
  private class FakeWebSocket : WebSocket {
    private var listener: WebSocketListener? = null
    val sent = mutableListOf<String>()

    fun attach(l: WebSocketListener): WebSocket {
      listener = l
      return this
    }

    fun simulateOpen() {
      listener!!.onOpen(this, fakeResponse())
    }

    override fun send(text: String): Boolean { sent += text; return true }
    override fun send(bytes: ByteString): Boolean { sent += bytes.utf8(); return true }
    override fun close(code: Int, reason: String?): Boolean {
      listener?.onClosed(this, code, reason ?: "")
      return true
    }
    override fun cancel() { listener?.onFailure(this, java.io.IOException("cancelled"), null) }
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
}
