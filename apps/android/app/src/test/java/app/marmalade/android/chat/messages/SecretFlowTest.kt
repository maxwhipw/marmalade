package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.PromptKind
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.types.SecretRespondResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daemon's secret-entry flow, client half (protocol events.ts
 * SecretRequest/ResolvedPayload + methods.ts SecretRespondParams).
 *
 * What these tests exist to pin, in order of how badly each would hurt:
 *
 *  1. **The value goes to the right session.** `secret.respond` REQUIRES
 *     session_id and the daemon settles that session's request — routing a
 *     credential to the bound session instead of the asking one would hand it
 *     to the wrong agent.
 *  2. **`value` and `deny` are exclusive.** The params schema is `.strict()`
 *     with a refine demanding exactly one; sending both (or an empty value on
 *     a deny) is an InvalidParams, i.e. a silently unanswered prompt.
 *  3. **Dismiss denies rather than closing.** The agent is parked for ten
 *     minutes on the tool call.
 *  4. **Any device's resolution clears the card**, including the daemon's own
 *     unprompted denial — and the user is told why the card vanished.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecretFlowTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
        val toasts: MutableList<String>,
    ) {
        fun tearDown() = scope.cancel()
    }

    private suspend fun buildHarness(): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "live-main"))
        val rpc = FakeMarmaladeRpc()
        val stream = MessageStream(events = rpc.rpcClient.events, scope = scope, chatDao = dao, json = testJson)
        val drainer = OutboxDrainer(chatDao = dao, transport = marmaladeRpcAdapter(rpc), scope = scope, persistence = stream.persistence)
        val controller = ChatController(
            scope = scope, rpc = rpc, messageStream = stream, outboxDrainer = drainer,
            json = testJson, chatDao = dao, ioDispatcher = UnconfinedTestDispatcher(),
        )
        val toasts = mutableListOf<String>()
        scope.launch { controller.toastMessage.collect { toasts += it } }
        return Harness(dao, rpc, controller, scope, toasts)
    }

    /** The daemon's exact secret.request payload (events.ts SecretRequestPayload). */
    private fun daemonSecretRequest(rid: String, sessionId: String) = GatewayEvent(
        type = "secret.request",
        payload = buildJsonObject {
            put("session_id", JsonPrimitive(sessionId))
            put("request_id", JsonPrimitive(rid))
            put("entry", JsonPrimitive("marmalade/email/imap-password"))
            put("description", JsonPrimitive("IMAP password for user@example.com"))
            put("created_at", JsonPrimitive(1_700_000_000_000L))
        },
        sessionId = sessionId,
    )

    private fun secretResolved(rid: String, outcome: String, sessionId: String, error: String? = null) =
        GatewayEvent(
            type = "secret.resolved",
            payload = buildJsonObject {
                put("request_id", JsonPrimitive(rid))
                put("outcome", JsonPrimitive(outcome))
                if (error != null) put("error", JsonPrimitive(error))
            },
            sessionId = sessionId,
        )

    @Test
    fun `secret_request renders a card carrying the entry path and the description`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonSecretRequest("sec-1", "live-main"))
            val prompt = h.controller.pendingPrompts.first().single()
            assertEquals(PromptKind.Secret, prompt.kind)
            assertEquals("daemon request_id adopted, not synthesized", "sec-1", prompt.requestId)
            assertEquals(
                "the keyring path is the card's focal claim",
                "marmalade/email/imap-password",
                (prompt.payload["entry"] as JsonPrimitive).content,
            )
            assertEquals(
                "model-authored description carried as the card's detail",
                "IMAP password for user@example.com",
                prompt.detail,
            )
        } finally { h.tearDown() }
    }

    @Test
    fun `respondSecret sends the value with the PROMPT'S session and request id`() = runTest {
        val h = buildHarness()
        try {
            // A secret asked in a session that is NOT the bound one (unknown
            // gateway id → the card parks under the bound local key, but keeps
            // its own server session id). Routing this to boundSessionId would
            // settle the wrong session's request — the pre-fix bug.
            h.rpc.emit(daemonSecretRequest("sec-1", "live-other"))
            h.controller.respondSecret("sec-1", "hunter2")
            val call = h.rpc.secretRespondCalls.single()
            assertEquals("routed to the asking session, not the bound one", "live-other", call.sessionId)
            assertEquals("sec-1", call.requestId)
            assertEquals("hunter2", call.value)
            assertFalse("value and deny are mutually exclusive", call.deny)
            assertTrue("card closes on send", h.controller.pendingPrompts.first().isEmpty())
        } finally { h.tearDown() }
    }

    @Test
    fun `deny sends deny true and NO value`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonSecretRequest("sec-1", "live-main"))
            h.controller.denySecret("sec-1")
            val call = h.rpc.secretRespondCalls.single()
            assertTrue(call.deny)
            assertNull("a deny must not carry a value (strict XOR schema)", call.value)
            assertEquals("live-main", call.sessionId)
            assertEquals("sec-1", call.requestId)
            assertTrue(h.controller.pendingPrompts.first().isEmpty())
        } finally { h.tearDown() }
    }

    @Test
    fun `a successful store toasts the entry path`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.secretRespondResult = SecretRespondResult(resolved = true, stored = true)
            h.rpc.emit(daemonSecretRequest("sec-1", "live-main"))
            h.controller.respondSecret("sec-1", "hunter2")
            assertEquals(listOf("Stored at marmalade/email/imap-password"), h.toasts)
        } finally { h.tearDown() }
    }

    @Test
    fun `a keyring failure surfaces the redacted error`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.secretRespondResult =
                SecretRespondResult(resolved = true, stored = false, error = "gopass exited 1")
            h.rpc.emit(daemonSecretRequest("sec-1", "live-main"))
            h.controller.respondSecret("sec-1", "hunter2")
            assertEquals(listOf("Keyring store failed: gopass exited 1"), h.toasts)
        } finally { h.tearDown() }
    }

    @Test
    fun `secret_resolved clears the matching card - answered on another device`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonSecretRequest("sec-1", "live-main"))
            assertEquals(1, h.controller.pendingPrompts.first().size)
            h.rpc.emit(secretResolved("sec-1", "stored", "live-main"))
            assertTrue("card cleared without a local respond", h.controller.pendingPrompts.first().isEmpty())
            assertTrue("no respond sent from this device", h.rpc.secretRespondCalls.isEmpty())
            assertTrue("another device storing it needs no words here", h.toasts.isEmpty())
        } finally { h.tearDown() }
    }

    @Test
    fun `secret_resolved with an unknown id falls back to the session's secret card`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonSecretRequest("sec-1", "live-main"))
            h.rpc.emit(secretResolved("rid-unknown", "stored", "live-main"))
            assertTrue(h.controller.pendingPrompts.first().isEmpty())
        } finally { h.tearDown() }
    }

    @Test
    fun `an unprompted denial (daemon timeout) clears the card and says why`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonSecretRequest("sec-1", "live-main"))
            // Nobody answered: the daemon's 10-minute timer fired (or the last
            // secrets-capable client dropped) and it denied on its own.
            h.rpc.emit(secretResolved("sec-1", "denied", "live-main"))
            assertTrue(h.controller.pendingPrompts.first().isEmpty())
            assertTrue("no RPC — this device never answered", h.rpc.secretRespondCalls.isEmpty())
            assertEquals(listOf("Secret request expired or was denied"), h.toasts)
        } finally { h.tearDown() }
    }

    @Test
    fun `an unprompted failure surfaces the keyring error`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(daemonSecretRequest("sec-1", "live-main"))
            h.rpc.emit(secretResolved("sec-1", "failed", "live-main", error = "keyring locked"))
            assertTrue(h.controller.pendingPrompts.first().isEmpty())
            assertEquals(listOf("Keyring store failed: keyring locked"), h.toasts)
        } finally { h.tearDown() }
    }

    @Test
    fun `a secret_resolved for a session with no card is a no-op`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(secretResolved("sec-ghost", "denied", "live-main"))
            assertTrue(h.controller.pendingPrompts.first().isEmpty())
            assertTrue("no toast for a card that was never shown", h.toasts.isEmpty())
        } finally { h.tearDown() }
    }
}
