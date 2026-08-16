package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.chat.PendingPrompt
import app.marmalade.android.chat.PromptKind
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [ChatController.pendingPrompts] StateFlow (parity row E1).
 *
 * Verifies:
 *  1. capturePrompt adds a PendingPrompt to pendingPrompts.
 *  2. A prompt arriving in a background session (sessionId != bound session)
 *     fires onPromptNotification with the correct sessionKey and prompt.
 *  3. A prompt for the bound session (null sessionId) does NOT fire
 *     onPromptNotification (user sees the inline card).
 *  4. needsInput derivation: pendingPrompts.any { it.sessionId == sessionId }
 *     is true for sessions that have a pending prompt.
 *
 * Uses [FakeChatDao] + [FakeMarmaladeRpc] — no Room / Robolectric needed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PendingPromptsTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
        val notified: MutableList<Pair<String, PendingPrompt>>,
    ) {
        fun tearDown() = scope.cancel()
    }

    private suspend fun buildHarness(): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "server-session-main"))
        // A known background session: prompts stamped with its LIVE id must
        // resolve to its stable local key at capture time.
        dao.insertSession(SessionEntity(key = "bg-key", thinkingLevel = "off", gatewaySessionId = "live-bg"))

        val rpc = FakeMarmaladeRpc()
        val stream = MessageStream(
            events = rpc.rpcClient.events,
            scope = scope,
            chatDao = dao,
            json = testJson,
        )
        val drainer = OutboxDrainer(
            chatDao = dao,
            transport = marmaladeRpcAdapter(rpc),
            scope = scope,
            persistence = stream.persistence,
        )
        val notified = mutableListOf<Pair<String, PendingPrompt>>()
        val controller = ChatController(
            scope = scope,
            rpc = rpc,
            messageStream = stream,
            outboxDrainer = drainer,
            json = testJson,
            chatDao = dao,
            ioDispatcher = UnconfinedTestDispatcher(),
            onPromptNotification = { sessionKey, prompt -> notified.add(sessionKey to prompt) },
        )
        return Harness(dao, rpc, controller, scope, notified)
    }

    // ── Test cases ──────────────────────────────────────────────────────────

    @Test
    fun `clarify request adds prompt to pendingPrompts`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "clarify.request",
                    payload = buildJsonObject {
                        put("request_id", JsonPrimitive("req-1"))
                        put("title", JsonPrimitive("Confirm action"))
                        put("detail", JsonPrimitive("Are you sure?"))
                    },
                    sessionId = "live-bg",
                ),
            )

            val prompts = h.controller.pendingPrompts.first()
            assertEquals("one prompt captured", 1, prompts.size)
            assertEquals("req-1", prompts[0].requestId)
            assertEquals(PromptKind.Clarify, prompts[0].kind)
            assertEquals("Confirm action", prompts[0].title)
            assertEquals("raw live id preserved", "live-bg", prompts[0].sessionId)
            assertEquals(
                "the LIVE id must resolve to the stable local key at capture",
                "bg-key",
                prompts[0].sessionKey,
            )
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `background session prompt fires onPromptNotification`() = runTest {
        val h = buildHarness()
        try {
            // Bound session is "main" (default). Prompt arrives for a different session.
            // Real approval.request payload shape (tools/approval.py
            // approval_data): command/description/pattern_key/allow_permanent
            // — NO request_id. Pre-fix, capturePrompt dropped these events
            // entirely and approval cards never rendered.
            h.rpc.emit(
                GatewayEvent(
                    type = "approval.request",
                    payload = buildJsonObject {
                        put("command", JsonPrimitive("rm -rf build/"))
                        put("description", JsonPrimitive("recursive delete"))
                        put("pattern_key", JsonPrimitive("rm_rf"))
                        put("allow_permanent", JsonPrimitive(true))
                    },
                    sessionId = "live-bg",
                ),
            )

            assertEquals("notification fired for background session", 1, h.notified.size)
            assertEquals(
                "the notification handler receives the LOCAL key (mute lookup, " +
                    "title resolution, and tap-through are keyed by it)",
                "bg-key",
                h.notified[0].first,
            )
            val captured = h.notified[0].second
            assertTrue(
                "approvals have no request_id on the wire — a local card id is synthesized",
                captured.requestId.startsWith("approval-"),
            )
            assertEquals("Approval required", captured.title)
            assertEquals("description maps to the card detail line", "recursive delete", captured.detail)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `respondApproval sends the server choice routed to the prompt's own session`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "approval.request",
                    payload = buildJsonObject {
                        put("command", JsonPrimitive("sudo systemctl restart foo"))
                        put("description", JsonPrimitive("service restart"))
                        put("pattern_key", JsonPrimitive("systemctl_restart"))
                        put("allow_permanent", JsonPrimitive(true))
                    },
                    sessionId = "live-bg",
                ),
            )
            val prompt = h.controller.pendingPrompts.first().single()

            h.controller.respondApproval(prompt.requestId, "session")

            val call = h.rpc.approvalRespondCalls.single()
            assertEquals("choice passes through untranslated", "session", call.choice)
            assertEquals(
                "session_id must be the prompt's own live id, not the bound session's",
                "live-bg",
                call.sessionId,
            )
            assertFalse(call.all)
            assertTrue("card removed locally", h.controller.pendingPrompts.first().isEmpty())
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `bound session prompt does NOT fire onPromptNotification`() = runTest {
        val h = buildHarness()
        try {
            // Prompt with null sessionId = targets the currently bound session.
            // ChatController treats null sessionId as the bound session — user
            // is looking at it, so no OS notification needed.
            h.rpc.emit(
                GatewayEvent(
                    type = "secret.request",
                    payload = buildJsonObject {
                        put("request_id", JsonPrimitive("req-3"))
                        put("title", JsonPrimitive("Enter password"))
                    },
                    sessionId = null,
                ),
            )

            assertTrue("no notification for bound-session prompt", h.notified.isEmpty())
            // The prompt is still captured in pendingPrompts for the inline card
            assertEquals(1, h.controller.pendingPrompts.first().size)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `needsInput derivation — pendingPrompts any by stable session key`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "clarify.request",
                    payload = buildJsonObject {
                        put("request_id", JsonPrimitive("req-4"))
                        put("title", JsonPrimitive("Clarify?"))
                    },
                    sessionId = "live-bg",
                ),
            )

            val prompts = h.controller.pendingPrompts.first()

            // The exact derivation used in SessionListViewModel:
            //   needsInput = pendingPrompts.any { it.sessionKey == entry.key }
            // Session-list entries are keyed by STORED ids, so comparing the
            // event's rotating LIVE id (the pre-fix code) was always false.
            assertTrue(
                "bg-key has needsInput=true",
                prompts.any { it.sessionKey == "bg-key" },
            )
            assertFalse(
                "other-session has needsInput=false",
                prompts.any { it.sessionKey == "other-session" },
            )
        } finally {
            h.tearDown()
        }
    }

    // ── 2026-07-03 on-device fixes (misplaced + undismissable clarify) ─────

    @Test
    fun `background prompt is hidden from the bound card stack and shows after switching in`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.openTransport()
            h.controller.load("main")
            h.rpc.emit(
                GatewayEvent(
                    type = "clarify.request",
                    payload = buildJsonObject {
                        put("request_id", JsonPrimitive("req-5"))
                        put("title", JsonPrimitive("Which file?"))
                    },
                    sessionId = "live-bg",
                ),
            )

            assertTrue(
                "a background session's clarify must NOT render in the bound chat " +
                    "(the misplaced card, on-device 2026-07-03)",
                h.controller.boundPendingPrompts.value.isEmpty(),
            )

            // Navigate into the prompt's session: the parked card must appear
            // (pre-fix load() wiped ALL prompts on switch, so it never could).
            h.controller.load("bg-key")
            val bound = h.controller.boundPendingPrompts.first()
            assertEquals("parked prompt visible in its own session", 1, bound.size)
            assertEquals("req-5", bound[0].requestId)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `dismissPrompt removes the card locally`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "clarify.request",
                    payload = buildJsonObject {
                        put("request_id", JsonPrimitive("req-6"))
                        put("title", JsonPrimitive("Dismiss me"))
                    },
                    sessionId = null,
                ),
            )
            assertEquals(1, h.controller.pendingPrompts.first().size)

            // Pre-fix the card's X was wired to a no-op and the clarify could
            // not be closed at all (on-device 2026-07-03).
            h.controller.dismissPrompt("req-6")
            assertTrue(h.controller.pendingPrompts.value.isEmpty())
        } finally {
            h.tearDown()
        }
    }
}
