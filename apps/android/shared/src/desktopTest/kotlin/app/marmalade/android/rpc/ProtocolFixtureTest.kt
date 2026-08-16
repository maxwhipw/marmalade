package app.marmalade.android.rpc

import app.marmalade.android.chat.messages.MessageStream
import app.marmalade.android.chat.parseClarifyQuestions
import app.marmalade.android.rpc.types.AudioTranscribeResponse
import app.marmalade.android.rpc.types.CronDeleteResponse
import app.marmalade.android.rpc.types.CronJobResponse
import app.marmalade.android.rpc.types.CronListResponse
import app.marmalade.android.rpc.types.CronRunNowResponse
import app.marmalade.android.rpc.types.DaemonFsListResponse
import app.marmalade.android.rpc.types.DaemonMcpListResponse
import app.marmalade.android.rpc.types.DaemonPluginsListResponse
import app.marmalade.android.rpc.types.DaemonSettings
import app.marmalade.android.rpc.types.DaemonToggleResponse
import app.marmalade.android.rpc.types.DeviceListResponse
import app.marmalade.android.rpc.types.DeviceRevokeResponse
import app.marmalade.android.rpc.types.FileAttachResponse
import app.marmalade.android.rpc.types.FsDefaultsResponse
import app.marmalade.android.rpc.types.GatewayReadyPayload
import app.marmalade.android.rpc.types.HelloResponse
import app.marmalade.android.rpc.types.ImageAttachResponse
import app.marmalade.android.rpc.types.ModelListResponse
import app.marmalade.android.rpc.types.PairingClaimResponse
import app.marmalade.android.rpc.types.PromptSubmitAck
import app.marmalade.android.rpc.types.SearchArchiveResponse
import app.marmalade.android.rpc.types.SearchMessagesResponse
import app.marmalade.android.rpc.types.SecretRespondResult
import app.marmalade.android.rpc.types.SessionArchiveResponse
import app.marmalade.android.rpc.types.SessionClearResponse
import app.marmalade.android.rpc.types.SessionCreateResponse
import app.marmalade.android.rpc.types.SessionEffortResponse
import app.marmalade.android.rpc.types.SessionForkResponse
import app.marmalade.android.rpc.types.SessionListResponse
import app.marmalade.android.rpc.types.SessionMainResponse
import app.marmalade.android.rpc.types.SessionModelResponse
import app.marmalade.android.rpc.types.SessionResumeResponse
import app.marmalade.android.rpc.types.SessionSeenResponse
import app.marmalade.android.rpc.types.SessionSubscribeResponse
import app.marmalade.android.rpc.types.SessionUndoResponse
import app.marmalade.android.rpc.types.SkillsListResponse
import app.marmalade.android.rpc.types.SkillsToggleResponse
import app.marmalade.android.rpc.types.TerminalAttachResponse
import app.marmalade.android.rpc.types.TerminalCloseResponse
import app.marmalade.android.rpc.types.TerminalCreateResponse
import app.marmalade.android.rpc.types.TerminalDataPayload
import app.marmalade.android.rpc.types.TerminalExitPayload
import app.marmalade.android.rpc.types.TerminalListResponse
import app.marmalade.android.rpc.types.UsageSummaryResponse
import app.marmalade.android.rpc.types.WorkspaceContextResponse
import app.marmalade.android.rpc.types.WorkspaceDeleteResponse
import app.marmalade.android.rpc.types.WorkspaceListResponse
import app.marmalade.android.rpc.types.WorkspaceMutateResponse
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The client half of the protocol-fixture contract.
 *
 * `packages/protocol/fixtures/` holds one checked-in corpus of real wire
 * frames. The daemon side validates every file against its zod schemas
 * (packages/protocol/test/fixtures.test.ts); this test round-trips the SAME
 * files through the shapes this client actually uses. A host change that
 * renames a field, drops one, or adds a method therefore fails on both sides
 * of the wire in one CI run, instead of surfacing on a phone as a blank screen
 * or a MethodNotFound toast.
 *
 * WHY :shared/desktopTest and not :app — this suite must run in CI, and
 * `:app:testDebugUnitTest` cannot: :app links the prebuilt sherpa-onnx .aar and
 * .so binaries that are deliberately not in git (assets-manifest.json /
 * scripts/fetch-assets.sh). :shared has no binary assets, so `:shared:desktopTest`
 * runs from a bare checkout. Everything the wire layer needs already lives here
 * (rpc/types, MessageStream, ClarifyPrompt).
 *
 * It complements [WireConformanceTest], which scans client SOURCE for method
 * and event NAMES. That test asks "does the client speak a word the daemon
 * knows"; this one asks "does the client understand the sentence" — payload
 * fields, types and nesting.
 */
// SerialDescriptor introspection (getElementIndex / element descriptors) is the
// whole mechanism here, and it is @ExperimentalSerializationApi.
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class ProtocolFixtureTest {

    // ── responses: method → the deserializer MarmaladeRpc actually uses ──────
    // Transcribed from MarmaladeRpc.kt's call() sites. A method missing here
    // must be in [RESULTS_NOT_DECODED] with the reason.

    private val responseTypes: Map<String, DeserializationStrategy<*>> = mapOf(
        "hello" to HelloResponse.serializer(),
        "session.create" to SessionCreateResponse.serializer(),
        "session.resume" to SessionResumeResponse.serializer(),
        "session.subscribe" to SessionSubscribeResponse.serializer(),
        "session.seen" to SessionSeenResponse.serializer(),
        "session.list" to SessionListResponse.serializer(),
        "session.main" to SessionMainResponse.serializer(),
        "session.clear" to SessionClearResponse.serializer(),
        "session.model" to SessionModelResponse.serializer(),
        "session.effort" to SessionEffortResponse.serializer(),
        "session.archive" to SessionArchiveResponse.serializer(),
        "session.undo" to SessionUndoResponse.serializer(),
        "session.fork" to SessionForkResponse.serializer(),
        "prompt.submit" to PromptSubmitAck.serializer(),
        "session.steer" to PromptSubmitAck.serializer(),
        "model.list" to ModelListResponse.serializer(),
        "settings.get" to DaemonSettings.serializer(),
        "settings.update" to DaemonSettings.serializer(),
        "pairing.claim" to PairingClaimResponse.serializer(),
        "device.list" to DeviceListResponse.serializer(),
        "device.revoke" to DeviceRevokeResponse.serializer(),
        "skills.list" to SkillsListResponse.serializer(),
        "skills.toggle" to SkillsToggleResponse.serializer(),
        "fs.defaults" to FsDefaultsResponse.serializer(),
        "fs.list" to DaemonFsListResponse.serializer(),
        "workspace.create" to WorkspaceMutateResponse.serializer(),
        "workspace.update" to WorkspaceMutateResponse.serializer(),
        "workspace.list" to WorkspaceListResponse.serializer(),
        "workspace.delete" to WorkspaceDeleteResponse.serializer(),
        "workspace.context" to WorkspaceContextResponse.serializer(),
        "mcp.list" to DaemonMcpListResponse.serializer(),
        "mcp.toggle" to DaemonToggleResponse.serializer(),
        "plugins.list" to DaemonPluginsListResponse.serializer(),
        "plugins.toggle" to DaemonToggleResponse.serializer(),
        "secret.respond" to SecretRespondResult.serializer(),
        "cron.create" to CronJobResponse.serializer(),
        "cron.update" to CronJobResponse.serializer(),
        "cron.list" to CronListResponse.serializer(),
        "cron.delete" to CronDeleteResponse.serializer(),
        "cron.run_now" to CronRunNowResponse.serializer(),
        "image.attach_bytes" to ImageAttachResponse.serializer(),
        "file.attach" to FileAttachResponse.serializer(),
        "audio.transcribe" to AudioTranscribeResponse.serializer(),
        "usage.summary" to UsageSummaryResponse.serializer(),
        "search.messages" to SearchMessagesResponse.serializer(),
        "search.archive" to SearchArchiveResponse.serializer(),
        "terminal.create" to TerminalCreateResponse.serializer(),
        "terminal.attach" to TerminalAttachResponse.serializer(),
        "terminal.close" to TerminalCloseResponse.serializer(),
        "terminal.list" to TerminalListResponse.serializer(),
    )

    /**
     * Results this client deliberately does not decode. Each entry says why —
     * an unexplained entry is how a real gap hides. Removing an entry (and
     * adding a type) is the fix when a screen starts needing the body.
     */
    private val resultsNotDecoded = mapOf(
        "session.unsubscribe" to "ack-shaped `{}`; the call is fire-and-forget",
        "session.delete" to "ack-shaped `{}`; the list refreshes off session.deleted",
        "session.interrupt" to "ack-shaped `{}`; the UI reacts to status.update",
        "session.compact" to "ack-shaped `{}`; progress arrives as session.compaction events",
        "session.title" to "the client already holds the title it just set",
        "image.detach" to "the composer recounts its own staged attachments",
        "approval.respond" to "`{resolved}` — the card clears on approval.resolved, not on the ack",
        "clarify.respond" to "`{resolved}` — same as approval.respond",
        "terminal.detach" to "ack-shaped `{}`",
        "terminal.input" to "ack-shaped `{}`",
        "terminal.resize" to "`{cols, rows}` — the emulator already applied the geometry it asked for",
        "session.stop" to "the client never calls session.stop (see [methodsNotSent])",
        "session.summary" to "the client never calls session.summary (see [methodsNotSent])",
        "session.approvals" to "the client never calls session.approvals (see [methodsNotSent])",
        "pairing.start" to "the client never calls pairing.start (see [methodsNotSent])",
    )

    /**
     * Methods the daemon routes that this client never SENDS. Fixtures for
     * them still exist (they pin the host surface); this list records that the
     * client is not a consumer, so a missing send is a decision and not a bug
     * that slipped through.
     */
    private val paramsNotSent = mapOf(
        "session.create#approvals" to
            "the per-session approvals override has no composer UI; sessions inherit the daemon default",
    )

    private val methodsNotSent = mapOf(
        "session.stop" to "the client stops a turn with session.interrupt; ending a session is the daemon's reaper",
        "session.summary" to "topic/summary already ride every session.list row",
        "session.approvals" to "per-session approvals mode has no client UI yet",
        "pairing.start" to "this client CLAIMS a pairing (pairing.claim); the code is minted on the host",
    )

    /**
     * Response fields the client legitimately ignores — present on the wire,
     * absent from the client type. Keyed `<method>#<json path>`.
     */
    private val ignoredResponseFields = mapOf(
        "session.model#reasoning_effort" to
            "additive 2026-07-27: the daemon re-clamps effort on a model switch and returns it. " +
                "The client re-reads the row instead of adopting it — harmless, but it means a " +
                "model switch can briefly show a stale effort. SessionModelResponse should grow the field.",
        "session.list#sessions[].purpose" to "daemon-internal session classification; no client surface",
        "session.list#sessions[].status" to
            "the legacy derived status view; this client reads lifecycle/run_state instead",
        "session.list#sessions[].approvals" to
            "the effective approvals mode per row; the client has no per-session approvals UI " +
                "(see paramsNotSent[\"session.create#approvals\"])",
        "session.list#sessions[].reasoning_effort" to
            "GAP, not a decision: the daemon stamps every row with its effort (router.ts session.list) " +
                "and SessionListRow has no field for it, so a cold-opened session shows no effort until " +
                "session.info arrives. SessionListRow should grow `reasoning_effort: String?`.",
        "search.messages#sessions[].corpus" to "read via SearchSessionInfo.corpus", // present in the type
    )

    /** Event payload fields the client does not read (envelope-open events). */
    private val consumedEventFields: Map<String, List<String>> = mapOf(
        // MessageStream.onMessageStart / onUserMessage / onMessageComplete and
        // the tool upsert read exactly these; a rename here is a silent
        // regression on device, which is the whole point of pinning them.
        "message.start" to listOf("message_id", "seq", "ts"),
        "message.user" to listOf("message_id", "text", "seq", "ts", "origin"),
        "message.delta" to listOf("text", "message_id"),
        "message.complete" to listOf("seq", "usage"),
        "tool.start" to listOf("id", "name", "input", "message_id", "seq"),
        "tool.complete" to listOf("tool_use_id", "seq"),
        "status.update" to listOf("session_id", "lifecycle", "run_state"),
        "error" to listOf("kind", "message", "session_id"),
        "effort.clamped" to listOf("requested", "effective", "model", "bound", "limit"),
        "session.undone" to listOf("session_id", "last_message_id", "popped_message_ids"),
        "session.cleared" to listOf("session_id"),
        "session.deleted" to listOf("session_id"),
        "session.compaction" to listOf("status"),
        "approval.request" to listOf("request_id", "tool_name", "command", "description"),
        "approval.resolved" to listOf("request_id"),
        "clarify.resolved" to listOf("request_id"),
        "secret.request" to listOf("session_id", "request_id", "entry", "description"),
        "secret.resolved" to listOf("request_id", "outcome"),
    )

    /** Events whose payloads this client decodes with a generated serializer. */
    private val eventPayloadTypes: Map<String, DeserializationStrategy<*>> = mapOf(
        "gateway.ready" to GatewayReadyPayload.serializer(),
        "terminal.data" to TerminalDataPayload.serializer(),
        "terminal.exit" to TerminalExitPayload.serializer(),
    )

    /**
     * Events the client does NOT consume, with the reason. `terminal.*` are
     * consumed, but by TerminalController in :app — outside this module — so
     * they are checked here through their payload types instead of the chat
     * dispatch scan.
     */
    private val eventsNotConsumed = mapOf(
        "session.info" to "consumed by ChatEventRouter; listed because the payload is adapter-shaped and open",
        "reasoning.available" to "consumed by MessageStream; no daemon emitter today (adapter vocabulary)",
        "reasoning.delta" to "consumed by MessageStream; no daemon emitter today (adapter vocabulary)",
        // MessageStream routes `subagent.*` by session (it refuses to apply an
        // unscoped one) but has no handler: the subagent card is built from
        // tool.start/tool.complete's parent_tool_use_id instead, so these two
        // events fall through to the `else ->` branch and are dropped. Worth
        // revisiting — subagent.complete carries the report + run totals.
        "subagent.start" to "no dispatch handler; the card is driven off tool.* parent_tool_use_id",
        "subagent.complete" to "no dispatch handler; the report/run-totals payload is currently dropped",
        "thinking.delta" to "handled, but explicitly ignored — the UI shows its own spinner",
    )

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    fun `the fixture corpus is present and non-trivial`() {
        val all = fixtures()
        assertTrue(all.size >= 150, "expected the full corpus, found ${all.size} fixtures in $fixturesDir")
        for (kind in listOf("requests", "responses", "events", "errors")) {
            assertTrue(all.any { it.kind == kind }, "no $kind fixtures under $fixturesDir")
        }
    }

    @Test
    fun `every response fixture decodes into the type this client uses`() {
        for (f in fixtures("responses")) {
            val serializer = responseTypes[f.name]
            if (serializer == null) {
                assertTrue(
                    resultsNotDecoded.containsKey(f.name),
                    "${f.path}: no client type for the `${f.name}` result. Map it in responseTypes, " +
                        "or record it in resultsNotDecoded with the reason.",
                )
                continue
            }
            val result = f.frame["result"] ?: fail("${f.path}: response fixture has no `result`")
            try {
                JsonRpcClient.DefaultJson.decodeFromJsonElement(serializer, result)
            } catch (t: Throwable) {
                fail(
                    "${f.path}: the daemon's `${f.name}` result does not decode into " +
                        "${serializer.descriptor.serialName}: ${t.message}",
                )
            }
            assertNoUnknownFields(f.name, f.path, serializer.descriptor, result, "")
        }
    }

    @Test
    fun `every request fixture names a method this client sends, with params it knows how to build`() {
        val source = rpcSource()
        val sent = RPC_METHOD_PATTERN.findAll(source).map { it.groupValues[1] }.toSet()
        assertTrue(sent.size >= 40, "extracted only ${sent.size} sent methods — the pattern or path drifted")
        val puts = PUT_KEY_PATTERN.findAll(source).map { it.groupValues[1] }.toSet()

        for (f in fixtures("requests")) {
            if (f.name !in sent) {
                assertTrue(
                    methodsNotSent.containsKey(f.name),
                    "${f.path}: the daemon routes `${f.name}` but MarmaladeRpc never sends it, and " +
                        "there is no methodsNotSent entry explaining why.",
                )
                continue
            }
            val params = f.frame["params"] as? JsonObject ?: continue
            for (key in params.keys) {
                if (key in puts) continue
                assertTrue(
                    paramsNotSent.containsKey("${f.name}#$key"),
                    "${f.path}: `${f.name}` carries the param `$key`, but MarmaladeRpc never puts a " +
                        "\"$key\" key — the client cannot send it. Add the send, or record it in " +
                        "paramsNotSent with the reason.",
                )
            }
        }
    }

    @Test
    fun `every event fixture is one this client consumes`() {
        val handled = handledEventNames()
        assertTrue(handled.size >= 25, "extracted only ${handled.size} handled events — pattern or path drifted")
        for (f in fixtures("events")) {
            assertTrue(
                f.name in handled || f.name in eventPayloadTypes || f.name in eventsNotConsumed,
                "${f.path}: the daemon emits `${f.name}` but no client dispatch handles it. Handle it, " +
                    "or record it in eventsNotConsumed with the reason.",
            )
        }
    }

    @Test
    fun `typed event payloads decode into the client's payload classes`() {
        for (f in fixtures("events")) {
            val serializer = eventPayloadTypes[f.name] ?: continue
            val payload = f.payload ?: JsonObject(emptyMap())
            try {
                JsonRpcClient.DefaultJson.decodeFromJsonElement(serializer, payload)
            } catch (t: Throwable) {
                fail("${f.path}: payload does not decode into ${serializer.descriptor.serialName}: ${t.message}")
            }
            assertNoUnknownFields(f.name, f.path, serializer.descriptor, payload, "")
        }
    }

    @Test
    fun `every field the client reads off an event payload is present on the wire`() {
        for (f in fixtures("events")) {
            val expected = consumedEventFields[f.name] ?: continue
            val payload = f.payload as? JsonObject
                ?: fail("${f.path}: expected an object payload for `${f.name}`")
            // A variant fixture pins a NARROWER frame on purpose (an error
            // completion carries no text/usage), so a base fixture is what must
            // carry every consumed field.
            if (f.variant.isNotEmpty()) continue
            for (key in expected) {
                assertTrue(
                    payload.containsKey(key),
                    "${f.path}: the client reads `$key` off a `${f.name}` payload, but the fixture " +
                        "does not carry it — either the daemon stopped sending it or the fixture is stale.",
                )
            }
        }
    }

    @Test
    fun `the message-complete usage block round-trips through the client's parser`() {
        val f = fixture("events", "message.complete")
        val usage = MessageStream.extractUsage(f.payload!!.jsonObject)
        assertNotNull(usage, "the client failed to read the usage block off message.complete")
        assertEquals(4120L, usage.inputTokens)
        assertEquals(318L, usage.outputTokens)
        assertEquals(41880L, usage.cacheReadTokens)
        assertEquals(2100L, usage.cacheWriteTokens)
        assertEquals(48210L, usage.contextUsed)
        assertEquals(200000L, usage.contextMax)
        // Recomputed client-side from used/max, never read off the wire.
        assertEquals(24, usage.contextPercent)
        assertEquals(0.0412, usage.costUsd)
    }

    @Test
    fun `the clarify-request payload round-trips through the client's parser`() {
        val f = fixture("events", "clarify.request")
        val questions = parseClarifyQuestions(f.payload!!.jsonObject)
        assertEquals(1, questions.size, "the client parsed no questions out of clarify.request")
        val q = questions.single()
        assertEquals("Which package should own the parser?", q.question)
        assertEquals("Ownership", q.header)
        assertEquals(false, q.multiSelect)
        assertEquals(listOf("core", "cli"), q.options.map { it.label })
        assertEquals("the shared library", q.options.first().description)
    }

    @Test
    fun `error frames carry the code and the structured fork discriminator`() {
        for (f in fixtures("errors")) {
            val error = f.frame["error"]?.jsonObject ?: fail("${f.path}: no `error` object")
            assertTrue(error.containsKey("code"), "${f.path}: error frames must carry a numeric code")
            assertTrue(error.containsKey("message"), "${f.path}: error frames must carry a message")
        }
        val fork = fixture("errors", "fork-unsupported")
        val reason = fork.frame["error"]!!.jsonObject["data"]!!.jsonObject["reason"]!!.jsonPrimitive.content
        assertEquals(
            app.marmalade.android.rpc.types.FORK_UNSUPPORTED_REASON,
            reason,
            "the client branches to its seed-create fallback on this discriminator, never on the prose",
        )
    }

    @Test
    fun `the skip lists have no stale entries`() {
        val responseNames = fixtures("responses").map { it.name }.toSet()
        val requestNames = fixtures("requests").map { it.name }.toSet()
        val eventNames = fixtures("events").map { it.name }.toSet()
        for ((name, reason) in resultsNotDecoded) {
            assertTrue(name in responseNames, "resultsNotDecoded names `$name`, which has no fixture — drop it")
            assertTrue(responseTypes[name] == null, "`$name` now HAS a client type — drop it from resultsNotDecoded")
            assertTrue(reason.length > 10, "resultsNotDecoded[$name] needs a real reason")
        }
        for ((name, reason) in methodsNotSent) {
            assertTrue(name in requestNames, "methodsNotSent names `$name`, which has no fixture — drop it")
            assertTrue(reason.length > 10, "methodsNotSent[$name] needs a real reason")
        }
        for ((name, reason) in eventsNotConsumed) {
            assertTrue(name in eventNames, "eventsNotConsumed names `$name`, which has no fixture — drop it")
            assertTrue(reason.length > 10, "eventsNotConsumed[$name] needs a real reason")
        }
        for (ref in paramsNotSent.keys) {
            val method = ref.substringBefore('#')
            assertTrue(method in requestNames, "paramsNotSent names `$method`, which has no fixture — drop it")
        }
        for (ref in ignoredResponseFields.keys) {
            val method = ref.substringBefore('#')
            assertTrue(method in responseNames, "ignoredResponseFields names `$method`, which has no fixture")
        }
        for (name in consumedEventFields.keys) {
            assertTrue(name in eventNames, "consumedEventFields names `$name`, which has no fixture — drop it")
        }
    }

    // ── unknown-field scan ──────────────────────────────────────────────────

    /**
     * Walk a wire value against the descriptor of the type the client decodes
     * it into, and fail on any key the type has no element for. `Json`'s
     * `ignoreUnknownKeys = true` (the production setting, and the right one —
     * an unknown key must never reject a whole frame) means an added or renamed
     * field decodes SILENTLY into a default. That is exactly the drift this
     * corpus exists to catch, so the check has to be structural rather than a
     * decode result.
     */
    private fun assertNoUnknownFields(
        method: String,
        path: String,
        descriptor: SerialDescriptor,
        value: kotlinx.serialization.json.JsonElement,
        jsonPath: String,
    ) {
        if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) return // opaque JsonElement
        when (descriptor.kind) {
            is StructureKind.CLASS, is StructureKind.OBJECT -> {
                val obj = value as? JsonObject ?: return
                for ((key, child) in obj) {
                    val index = descriptor.getElementIndex(key)
                    if (index < 0) { // CompositeDecoder.UNKNOWN_NAME
                        val ref = "$method#${(jsonPath + key).trimStart('.')}"
                        assertTrue(
                            ignoredResponseFields.containsKey(ref),
                            "$path: the wire carries `$ref`, which ${descriptor.serialName} has no " +
                                "field for — the client silently drops it. Add the field, or record " +
                                "the omission in ignoredResponseFields with the reason.",
                        )
                        continue
                    }
                    assertNoUnknownFields(
                        method, path, descriptor.getElementDescriptor(index), child, "$jsonPath$key.",
                    )
                }
            }
            is StructureKind.LIST -> {
                val arr = value as? JsonArray ?: return
                val element = descriptor.getElementDescriptor(0)
                val childPath = jsonPath.trimEnd('.') + "[]."
                for (item in arr) assertNoUnknownFields(method, path, element, item, childPath)
            }
            is StructureKind.MAP -> {
                val obj = value as? JsonObject ?: return
                val element = descriptor.getElementDescriptor(1)
                val childPath = jsonPath.trimEnd('.') + "[]."
                for ((_, child) in obj) assertNoUnknownFields(method, path, element, child, childPath)
            }
            is PolymorphicKind, is SerialKind.CONTEXTUAL -> return
            else -> return // primitives + enums: the decode above already checked them
        }
    }

    // ── corpus access ───────────────────────────────────────────────────────

    private data class WireFixture(
        val kind: String,
        val path: String,
        val name: String,
        val variant: String,
        val frame: JsonObject,
    ) {
        val payload: kotlinx.serialization.json.JsonElement?
            get() = (frame["params"] as? JsonObject)?.get("payload")
    }

    private fun fixtures(kind: String? = null): List<WireFixture> =
        corpus.filter { kind == null || it.kind == kind }

    private fun fixture(kind: String, name: String): WireFixture =
        fixtures(kind).firstOrNull { it.name == name && it.variant.isEmpty() }
            ?: fail("no base fixture $kind/$name.json in $fixturesDir")

    /** MarmaladeRpc plus the pure param builders it delegates to. */
    private fun rpcSource(): String = listOf(
        "shared/src/jvmSharedMain/kotlin/app/marmalade/android/rpc/MarmaladeRpc.kt",
        "shared/src/commonMain/kotlin/app/marmalade/android/search/SearchRequest.kt",
    ).joinToString("\n") { stripComments(moduleFile(it).readText()) }

    /** Event names handled by the chat dispatch (the same seams
     *  [WireConformanceTest] scans, read here for payload checking). */
    private fun handledEventNames(): Set<String> {
        val src = listOf(
            "shared/src/jvmSharedMain/kotlin/app/marmalade/android/chat/ChatEventRouter.kt",
            "shared/src/jvmSharedMain/kotlin/app/marmalade/android/chat/messages/MessageStream.kt",
        ).joinToString("\n") { stripComments(moduleFile(it).readText()) }
        return EVENT_CASE_PATTERN.findAll(src)
            .flatMap { m -> BARE_STRING.findAll(m.value).map { it.groupValues[1] } }
            .toSet()
    }

    private fun stripComments(src: String): String =
        src.replace(BLOCK_COMMENT, " ").lineSequence().joinToString("\n") { it.substringBefore("//") }

    private companion object {
        /** `client.request("x", …)` / `call("x", …)` — MarmaladeRpc's two send seams. */
        private val RPC_METHOD_PATTERN =
            Regex("""(?:client\.request|\bcall)\(\s*"([a-z][a-zA-Z._]+)"""", RegexOption.DOT_MATCHES_ALL)

        /** `put("key"` / `putJsonArray("key")` — the param keys the client can build. */
        private val PUT_KEY_PATTERN = Regex("""put(?:Json[A-Za-z]+)?\(\s*"([a-zA-Z_][a-zA-Z0-9_]*)"""")

        private val EVENT_CASE_PATTERN =
            Regex(""""[a-z][a-zA-Z._]+"(?:\s*,\s*"[a-z][a-zA-Z._]+")*\s*->""")
        private val BARE_STRING = Regex(""""([a-z][a-zA-Z._]+)"""")
        private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        /** The android module root (apps/android), found by walking up from the
         *  test's working directory — AGP and plain Gradle invocations disagree
         *  about what that is. */
        private val moduleRoot: File = run {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                if (File(dir, "shared/src/commonMain/kotlin").isDirectory) return@run dir
                dir = dir.parentFile
            }
            error("could not locate the android module root from ${System.getProperty("user.dir")}")
        }

        /** `packages/protocol/fixtures`, resolved by walking up to the repo root. */
        private val fixturesDir: File = run {
            var dir: File? = moduleRoot
            while (dir != null) {
                val candidate = File(dir, "packages/protocol/fixtures")
                if (candidate.isDirectory) return@run candidate
                dir = dir.parentFile
            }
            error("could not locate packages/protocol/fixtures from ${moduleRoot.path}")
        }

        private val lenientJson = Json { ignoreUnknownKeys = true }

        private val corpus: List<WireFixture> =
            listOf("requests", "responses", "events", "errors").flatMap { kind ->
                (File(fixturesDir, kind).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
                    .sortedBy { it.name }
                    .map { file ->
                        val base = file.name.removeSuffix(".json")
                        // requests/responses/events are named for a protocol
                        // method or event (never hyphenated), so the first "-"
                        // starts a free-text variant suffix. errors/ names are
                        // free-form and hyphenated — no split there.
                        val dash = if (kind == "errors") -1 else base.indexOf('-')
                        WireFixture(
                            kind = kind,
                            path = "$kind/${file.name}",
                            name = if (dash == -1) base else base.substring(0, dash),
                            variant = if (dash == -1) "" else base.substring(dash + 1),
                            frame = lenientJson.parseToJsonElement(file.readText()).jsonObject,
                        )
                    }
            }
    }

    private fun moduleFile(relative: String): File = File(moduleRoot, relative)
}
