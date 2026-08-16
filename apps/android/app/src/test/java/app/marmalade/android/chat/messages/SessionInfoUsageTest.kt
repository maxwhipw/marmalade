package app.marmalade.android.chat.messages

import app.marmalade.android.chat.ChatController
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for session.info → _sessionUsage hydration (parity row M2).
 *
 * Verifies that ChatController merges usage from session.info into
 * _sessionUsage with spread-merge semantics (incoming fields win;
 * missing fields preserve the prior value), and correctly ignores
 * foreign-session events or events that carry no usage key.
 *
 * Covers:
 *  - Full usage block → all four fields land on sessionUsage.
 *  - Partial update (output_tokens only) → outputTokens updated, prior inputTokens preserved.
 *  - Foreign session_id → no update.
 *  - session.info with no usage key → sessionUsage unchanged.
 *
 * Uses [FakeChatDao] and [FakeMarmaladeRpc] — no Room/Robolectric needed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionInfoUsageTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val dao: FakeChatDao,
        val rpc: FakeMarmaladeRpc,
        val controller: ChatController,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    /**
     * Builds a minimal ChatController harness backed by fakes.
     * Mirrors [SessionInfoModelTest]'s buildHarness exactly.
     */
    private suspend fun buildHarness(): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "main", thinkingLevel = "off", gatewaySessionId = "server-session-1"))

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
        val controller = ChatController(
            scope = scope,
            rpc = rpc,
            messageStream = stream,
            outboxDrainer = drainer,
            json = testJson,
            chatDao = dao,
        )
        return Harness(dao, rpc, controller, scope)
    }

    // ── Test cases ──────────────────────────────────────────────────────────

    @Test
    fun `session_info full usage block populates all four fields`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("usage", buildJsonObject {
                            put("input_tokens", JsonPrimitive(100L))
                            put("output_tokens", JsonPrimitive(200L))
                            put("cache_read_tokens", JsonPrimitive(50L))
                            put("total_tokens", JsonPrimitive(350L))
                        })
                    },
                    sessionId = null, // unstamped → matches null _sessionId
                ),
            )
            val usage = h.controller.sessionUsage.value
            assertEquals("inputTokens", 100L, usage?.inputTokens)
            assertEquals("outputTokens", 200L, usage?.outputTokens)
            assertEquals("cacheReadTokens", 50L, usage?.cacheReadTokens)
            assertEquals("totalTokens", 350L, usage?.totalTokens)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `second session_info with partial usage merges without clobbering prior fields`() = runTest {
        val h = buildHarness()
        try {
            // First event: set full usage
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("usage", buildJsonObject {
                            put("input_tokens", JsonPrimitive(100L))
                            put("output_tokens", JsonPrimitive(200L))
                        })
                    },
                    sessionId = null,
                ),
            )
            // Second event: only output_tokens present
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("usage", buildJsonObject {
                            put("output_tokens", JsonPrimitive(300L))
                        })
                    },
                    sessionId = null,
                ),
            )
            val usage = h.controller.sessionUsage.value
            assertEquals("outputTokens should update", 300L, usage?.outputTokens)
            assertEquals("inputTokens must be preserved from first event", 100L, usage?.inputTokens)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `session_info stamped with different session_id leaves sessionUsage unchanged`() = runTest {
        val h = buildHarness()
        try {
            // _sessionId is null at this point (no load() called).
            // A non-null foreign session_id must not match.
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("usage", buildJsonObject {
                            put("input_tokens", JsonPrimitive(999L))
                            put("output_tokens", JsonPrimitive(999L))
                        })
                    },
                    sessionId = "foreign-session-999",
                ),
            )
            assertNull("sessionUsage must not change for a foreign session", h.controller.sessionUsage.value)
        } finally {
            h.tearDown()
        }
    }

    @Test
    fun `UsageDelta survives a JSON encode-decode round-trip`() {
        // Locks the @Serializable contract used by the cold-open donut cache
        // (SettingsRepository.saveSessionUsage / ChatController.persistBoundUsage).
        // Every field — including the context/cost fields — must round-trip.
        val original = MessageStream.UsageDelta(
            inputTokens = 1234L,
            outputTokens = 567L,
            cacheReadTokens = 89L,
            cacheWriteTokens = 10L,
            totalTokens = 1900L,
            contextUsed = 61_440L,
            contextMax = 128_000L,
            contextPercent = 48,
            costUsd = 0.0123,
            compressions = 2,
        )
        val encoded = testJson.encodeToString(MessageStream.UsageDelta.serializer(), original)
        val decoded = testJson.decodeFromString(MessageStream.UsageDelta.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `UsageDelta round-trips with null fields`() {
        val original = MessageStream.UsageDelta(contextPercent = 12)
        val encoded = testJson.encodeToString(MessageStream.UsageDelta.serializer(), original)
        val decoded = testJson.decodeFromString(MessageStream.UsageDelta.serializer(), encoded)
        assertEquals(original, decoded)
        assertNull(decoded.inputTokens)
    }

    @Test
    fun `session_info with no usage key leaves sessionUsage unchanged`() = runTest {
        val h = buildHarness()
        try {
            h.rpc.emit(
                GatewayEvent(
                    type = "session.info",
                    payload = buildJsonObject {
                        put("title", JsonPrimitive("Renamed session"))
                    },
                    sessionId = null,
                ),
            )
            assertNull(
                "sessionUsage must not be clobbered when payload has no usage key",
                h.controller.sessionUsage.value,
            )
        } finally {
            h.tearDown()
        }
    }
}
