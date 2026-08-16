package app.marmalade.android.chat.messages

import app.marmalade.android.chat.OutgoingAttachment
import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.rpc.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

/**
 * Background outbox drainer. Wakes on:
 *  - transport ConnectionState → Open transitions
 *  - explicit [poke] calls from ChatController.sendMessage when a new row is
 *    inserted
 *  - periodic ticks for outbox rows whose nextAttemptAtMs has passed
 *
 * Each wake reads getDueOutbox(now), filters by inFlight set and by row
 * having a known serverSessionId, and tries to send each via
 * MarmaladeRpc.promptSubmit. On RPC success: ackOutboxAsMessage (outbox →
 * messages, single transaction). On failure: backoff per ratified-plan.md
 * §4 — 1s / 4s / 16s / 64s / 5min / 5min / 5min, ±25% jitter, attempt 7
 * transitions status to 'failed' for user-driven retry only.
 *
 * In-process idempotency: outboxId is added to [inFlight] BEFORE the RPC and
 * removed on RPC return. Survives only the current process; cross-process
 * dedup is handled by the plugin's idempotency_key cache (Phase 9 plugin
 * patch) and by hydrateFromServer content dedup (ChatDao.reconcileHistory).
 */
class OutboxDrainer(
    private val chatDao: ChatDao,
    private val transport: PromptTransport,
    private val scope: CoroutineScope,
    private val persistence: PersistenceCoordinator,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Reads a staged attachment file. Injectable so unit tests can feed
     *  bytes without touching the filesystem. */
    private val readFileBytes: (String) -> ByteArray = { java.io.File(it).readBytes() },
    /** Logging seam — this class lives in :shared/jvmSharedMain, which has no
     *  Android SDK. The Android call site (MarmaladeRuntime) wires this to
     *  `Log.w(TAG, …)`; the default no-op keeps tests silent. */
    private val logWarn: (String) -> Unit = {},
) {
    private val triggers = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val drainMutex = Mutex()

    /** Resolves a server session_id for an outbox row whose serverSessionId
     *  is null — a send from a fresh local-only chat (create-on-open is dead;
     *  the session is materialized HERE, at first send). Wired by
     *  ChatController to its ensureServerSessionId (which owns session.create
     *  and the K1 key promotion). Returns null when it can't resolve yet
     *  (transport down) — the row stays queued for the next drain. */
    var resolveSessionId: (suspend (sessionKey: String) -> String?)? = null

    /** Fired after a row's prompt.submit ack lands (post ackOutboxAsMessage)
     *  with the outbox id and the server-minted seq of the submitted user
     *  message (0 against a legacy gateway whose prompt.submit returns
     *  nothing). ChatController fans this into its promptAcks flow; the voice
     *  popup correlates its reply harvest to THIS turn's ack seq. */
    var onAck: ((outboxId: String, ackSeq: Long) -> Unit)? = null

    @OptIn(FlowPreview::class)
    fun start() {
        // Wake on transport-open edges. StateFlow conflates duplicates so
        // we don't need distinctUntilChanged.
        transport.connectionState
            .filter { it == ConnectionState.Open }
            .onEach { triggers.tryEmit(Unit) }
            .launchIn(scope)

        // Drain on debounced triggers + a slow tick for backoff-due rows.
        scope.launch {
            triggers.debounce(50L).collect { drainOnce() }
        }
        scope.launch {
            while (true) {
                delay(BACKOFF_TICK_MS)
                triggers.tryEmit(Unit)
            }
        }
    }

    /** Signal a new row was enqueued. Cheap; multiple pokes coalesce via debounce. */
    fun poke() {
        triggers.tryEmit(Unit)
    }

    private suspend fun drainOnce() = drainMutex.withLock {
        val due = try {
            chatDao.getDueOutbox(now())
        } catch (t: Throwable) {
            logWarn("drain getDueOutbox failed: ${t.message}")
            return@withLock
        }
        for (row in due) {
            var serverSid = row.serverSessionId
            if (serverSid.isNullOrEmpty()) {
                // First send in a fresh chat: mint the session now (deferred
                // create). Resolution failure is not an attempt — the row
                // stays pending and the next drain retries.
                serverSid = resolveSessionId
                    ?.let { resolve -> runCatching { resolve(row.sessionKey) }.getOrNull() }
                if (serverSid.isNullOrEmpty()) continue
                runCatching { chatDao.updateOutboxServerSessionId(row.id, serverSid) }
            }
            if (!inFlight.add(row.id)) continue
            try {
                val text = extractText(row.contentJson)
                val attachments = decodeAttachments(row.attachmentsJson)
                if (text.isBlank() && attachments.isEmpty()) {
                    persistence.lockFor(row.sessionKey).withLock {
                        chatDao.updateOutboxAttempt(
                            id = row.id, status = "failed",
                            errorMsg = "empty content",
                            attempts = row.attemptCount + 1, nextAttempt = 0L,
                        )
                    }
                    continue
                }
                // markOutboxSending under the lock so concurrent MessageStream
                // flushes / reconcileHistory see the status transition atomically.
                persistence.lockFor(row.sessionKey).withLock {
                    chatDao.markOutboxSending(row.id)
                }
                // RPC OUTSIDE the lock — promptSubmit can take up to 20s
                // (JsonRpcClient.Options.requestTimeout). Holding the per-
                // session lock through that window would freeze MessageStream's
                // streaming flushes for the same session (Reviewer Checkpoint
                // 2 finding E2E-#3). inFlight set already protects against
                // re-entry within the process.
                val submitText = if (attachments.isEmpty()) {
                    text
                } else {
                    syncAttachmentsAndBuildText(row, serverSid, text, attachments)
                }
                val ack = transport.submitPrompt(
                    sessionId = serverSid,
                    text = submitText,
                    truncateBeforeUserOrdinal = row.truncateBeforeUserOrdinal,
                    idempotencyKey = row.id,
                    // OutboxEntity.voiceOrigin gets set when ChatController /
                    // MarmaladeRuntime is called with voiceOrigin=true (the
                    // voice popup + wake-word paths). "voice" is the only
                    // origin the gateway acts on today — the daemon stamps it
                    // into the message origin + turn shaping.
                    source = if (row.voiceOrigin) "voice" else null,
                )
                // Reacquire the lock for the ack transaction so a concurrent
                // stream flush can't race the outbox→messages move (invariant
                // I4). The ack binds the promoted row to the SERVER-minted
                // message identity (id/seq/ts) — ids are names, minted once by
                // the daemon; the outbox id was only the local queue handle.
                persistence.lockFor(row.sessionKey).withLock {
                    chatDao.ackOutboxAsMessage(
                        outboxId = row.id,
                        serverMessageId = ack?.message_id,
                        serverSeq = ack?.seq ?: 0L,
                        serverTimestampMs = ack?.ts,
                    )
                }
                onAck?.invoke(row.id, ack?.seq ?: 0L)
            } catch (t: Throwable) {
                // Gateway-4009 "session busy" is a transient race when the
                // user submits while a turn is still streaming. Desktop
                // tight-retries within ~6 s before surfacing the error
                // (use-prompt-actions.ts:121-153). Treat the same here:
                // don't bump attemptCount, schedule a 200 ms re-drain so
                // a real double-tap rides through without burning the
                // outbox budget (~1 s first backoff → 5 min final).
                val rpcEx = t as? app.marmalade.android.rpc.JsonRpcException
                val isSessionBusy = rpcEx?.code == 4009
                // "session not found" — gateway restart between original
                // send and now invalidated our stored session_id. Desktop
                // catches the error, re-resumes to get a fresh id, and
                // retries without burning attempts (use-prompt-actions
                // .ts:710-721). Match here: clear the row's
                // serverSessionId so the next drain re-resolves via the
                // chat's resume path before re-submitting.
                val isSessionNotFound = rpcEx != null &&
                    Regex("session not found", RegexOption.IGNORE_CASE)
                        .containsMatchIn(rpcEx.message ?: "")
                if (isSessionBusy) {
                    val resetAt = now() + 200L
                    runCatching {
                        chatDao.updateOutboxAttempt(
                            id = row.id, status = "pending",
                            errorMsg = null,
                            attempts = row.attemptCount, nextAttempt = resetAt,
                        )
                    }
                } else if (isSessionNotFound) {
                    // Gateway restarted out from under us; the row's
                    // cached server_session_id is stale. Re-resume via
                    // PromptTransport.resumeSession to mint a fresh one,
                    // then write it back to the row so the next drain
                    // tick (50ms via poke) sends to the live id.
                    // Matches desktop's recovery at use-prompt-actions
                    // .ts:710-721.
                    val recovered = runCatching {
                        transport.resumeSession(serverSid)
                    }.getOrNull()
                    if (recovered != null) {
                        runCatching {
                            chatDao.updateOutboxServerSessionId(row.id, recovered)
                            chatDao.updateOutboxAttempt(
                                id = row.id, status = "pending",
                                errorMsg = null,
                                attempts = row.attemptCount, nextAttempt = now() + 200L,
                            )
                        }
                        poke()
                    } else {
                        // Resume itself failed — fall through to normal
                        // backoff so we don't spin tightly on a dead WS.
                        val attempts = row.attemptCount + 1
                        val (status, nextAttemptMs) = computeBackoff(attempts)
                        runCatching {
                            chatDao.updateOutboxAttempt(
                                id = row.id, status = status,
                                errorMsg = "session not found; resume failed",
                                attempts = attempts, nextAttempt = nextAttemptMs,
                            )
                        }
                    }
                } else {
                    val attempts = row.attemptCount + 1
                    val (status, nextAttemptMs) = computeBackoff(attempts)
                    runCatching {
                        chatDao.updateOutboxAttempt(
                            id = row.id, status = status,
                            errorMsg = t.message ?: t.javaClass.simpleName,
                            attempts = attempts, nextAttempt = nextAttemptMs,
                        )
                    }
                }
            } finally {
                inFlight.remove(row.id)
            }
        }
    }

    /**
     * Upload every not-yet-attached attachment for [row], persist upload
     * state incrementally, and return the final prompt text (file refs
     * prepended, image-only fallback applied). The row's contentJson text
     * part is rewritten to the same final text BEFORE the submit so the
     * acked row content-matches the server's history text (server stores the
     * submitted text verbatim — `_start_inflight_turn(session, text)` runs
     * before image enrichment) and reconcileHistory doesn't re-insert the
     * bubble as a duplicate on the next hydrate. Mirrors desktop's
     * submit-time `syncAttachmentsForSubmit` + `rewriteOptimistic`
     * (`use-prompt-actions.ts:436-489,626-634`).
     *
     * Upload dedup on retry (a failed submit re-drains the row):
     * - images queue in the LIVE session's in-memory dict, so skip only when
     *   [OutgoingAttachment.attachedSessionId] matches the current live sid —
     *   a gateway restart rotates the sid and empties the queue, forcing a
     *   correct re-upload.
     * - file refs point into the STORED session's workspace on disk, so an
     *   earned [OutgoingAttachment.refText] stays valid across restarts and
     *   is never re-uploaded.
     *
     * Throws on any upload failure — the caller's catch owns backoff, and
     * already-persisted upload state makes the retry cheap.
     */
    private suspend fun syncAttachmentsAndBuildText(
        row: OutboxEntity,
        serverSid: String,
        text: String,
        attachments: List<OutgoingAttachment>,
    ): String {
        val synced = attachments.toMutableList()
        for ((index, att) in attachments.withIndex()) {
            val alreadyUploaded = when (att.kind) {
                OutgoingAttachment.KIND_IMAGE -> att.attachedSessionId == serverSid
                else -> att.refText != null
            }
            if (alreadyUploaded) continue

            val bytes = readFileBytes(att.path)
            val uploaded = when (att.kind) {
                OutgoingAttachment.KIND_IMAGE -> {
                    transport.attachImageBytes(
                        sessionId = serverSid,
                        contentBase64 = java.util.Base64.getEncoder().encodeToString(bytes),
                        filename = att.name,
                    )
                    att.copy(attachedSessionId = serverSid)
                }
                else -> {
                    val refText = transport.attachFile(
                        sessionId = serverSid,
                        name = att.name,
                        dataUrl = "data:${att.mimeType};base64," +
                            java.util.Base64.getEncoder().encodeToString(bytes),
                    )
                    att.copy(attachedSessionId = serverSid, refText = refText)
                }
            }
            synced[index] = uploaded
            // Persist after EACH upload so a later failure in this row
            // doesn't force re-uploading what already landed.
            persistence.lockFor(row.sessionKey).withLock {
                chatDao.updateOutboxPayload(
                    id = row.id,
                    contentJson = row.contentJson,
                    attachmentsJson = encodeAttachments(synced),
                )
            }
        }

        val submitText = buildSubmitText(text, synced)
        if (submitText != text) {
            val rewritten = rewriteTextPart(row.contentJson, submitText)
            persistence.lockFor(row.sessionKey).withLock {
                chatDao.updateOutboxPayload(
                    id = row.id,
                    contentJson = rewritten,
                    attachmentsJson = encodeAttachments(synced),
                )
            }
        }
        return submitText
    }

    /**
     * Extract the user-visible text from contentJson. Concatenates all Text
     * parts; ignores anything else (Image/File/ToolCall/Reasoning would only
     * be in an assistant message anyway, never an outbox row).
     */
    private fun extractText(contentJson: String): String {
        return runCatching {
            Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(
                    kotlinx.serialization.json.JsonElement.serializer(),
                ),
                contentJson,
            ).asSequence()
                .filterIsInstance<JsonObject>()
                .filter { (it["type"] as? JsonPrimitive)?.content == "text" }
                .mapNotNull { (it["text"] as? JsonPrimitive)?.content }
                .joinToString(separator = "")
        }.getOrDefault("")
    }

    /**
     * Returns (status, nextAttemptAtMs) for a given attempt count.
     * 1s, 4s, 16s, 64s, 5min, 5min, 5min cap; attempt 7+ -> failed.
     */
    private fun computeBackoff(attempts: Int): Pair<String, Long> {
        if (attempts >= MAX_ATTEMPTS) return "failed" to 0L
        val baseSeconds = when (attempts) {
            1 -> 1L
            2 -> 4L
            3 -> 16L
            4 -> 64L
            else -> 300L
        }
        val jitter = Random.nextDouble(-0.25, 0.25)
        val delayMs = (baseSeconds * 1000L * (1.0 + jitter)).toLong().coerceAtLeast(0L)
        return "pending" to (now() + min(delayMs, 5 * 60 * 1000L))
    }

    companion object {
        private const val MAX_ATTEMPTS = 7
        private const val BACKOFF_TICK_MS = 5_000L
    }
}

/** Desktop's empty-prompt fallback when only images ride the submit
 *  (`use-prompt-actions.ts:573`); the server uses the same string
 *  (`tui_gateway/server.py:6046`). */
// Public, not `internal`: exercised by an `:app` unit test, and `:app`'s test
// compilation is a friend of `:app`'s main compilation only — never of
// `:shared`'s. See f142ad9 ("the internal trap").
const val IMAGE_ONLY_FALLBACK_PROMPT = "What do you see in this image?"

private val ATTACHMENTS_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(OutgoingAttachment.serializer())

// Public, not `internal`: exercised by an `:app` unit test, and `:app`'s test
// compilation is a friend of `:app`'s main compilation only — never of
// `:shared`'s. See f142ad9 ("the internal trap").
fun decodeAttachments(attachmentsJson: String?): List<OutgoingAttachment> {
    if (attachmentsJson.isNullOrBlank()) return emptyList()
    return runCatching {
        Json.decodeFromString(ATTACHMENTS_SERIALIZER, attachmentsJson)
    }.getOrDefault(emptyList())
}

// Public, not `internal`: exercised by an `:app` unit test, and `:app`'s test
// compilation is a friend of `:app`'s main compilation only — never of
// `:shared`'s. See f142ad9 ("the internal trap").
fun encodeAttachments(attachments: List<OutgoingAttachment>): String =
    Json.encodeToString(ATTACHMENTS_SERIALIZER, attachments)

/**
 * Final prompt text for a row with attachments: file `@file:` refs first,
 * then the visible text, double-newline separated (desktop's
 * `buildContextText`, `use-prompt-actions.ts:565-575`); the image-only
 * fallback when nothing else remains. IDEMPOTENT on retry: a re-drained row
 * whose contentJson was already rewritten carries the refs inside [text], and
 * refs already present are not prepended again.
 */
// Public, not `internal`: exercised by an `:app` unit test, and `:app`'s test
// compilation is a friend of `:app`'s main compilation only — never of
// `:shared`'s. See f142ad9 ("the internal trap").
fun buildSubmitText(text: String, attachments: List<OutgoingAttachment>): String {
    val refs = attachments
        .mapNotNull { it.refText }
        .filter { it.isNotBlank() && !text.contains(it) }
    val combined = (refs + listOfNotNull(text.takeIf { it.isNotBlank() }))
        .joinToString(separator = "\n\n")
    if (combined.isNotBlank()) return combined
    return if (attachments.any { it.kind == OutgoingAttachment.KIND_IMAGE }) {
        IMAGE_ONLY_FALLBACK_PROMPT
    } else {
        combined
    }
}

/**
 * Rewrite the (single) text part inside an outbox row's contentJson to
 * [newText], preserving every other part (Image/File chips). Prepends a text
 * part when the row had none (image-only send gaining the fallback prompt).
 */
// Public, not `internal`: exercised by an `:app` unit test, and `:app`'s test
// compilation is a friend of `:app`'s main compilation only — never of
// `:shared`'s. See f142ad9 ("the internal trap").
fun rewriteTextPart(contentJson: String, newText: String): String {
    val parts = runCatching {
        Json.parseToJsonElement(contentJson) as? kotlinx.serialization.json.JsonArray
    }.getOrNull() ?: return contentJson
    val textPart = kotlinx.serialization.json.buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(newText))
    }
    var replaced = false
    val rewritten = parts.map { element ->
        val obj = element as? JsonObject
        if (!replaced && obj != null && (obj["type"] as? JsonPrimitive)?.content == "text") {
            replaced = true
            textPart
        } else {
            element
        }
    }
    val result = if (replaced) rewritten else listOf(textPart) + rewritten
    return Json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()),
        result,
    )
}
