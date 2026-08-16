package app.marmalade.android.chat.messages

import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.GatewayEventEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.JsonRpcClient
import app.marmalade.android.ui.effortClampedLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Maintains per-session [ChatMessage] state by consuming a
 * [JsonRpcClient.events] stream. Port of the dispatch core in
 * `hermes-agent upstream: apps/desktop/src/app/session/hooks/use-message-stream.ts`.
 *
 * ## Construction order matters
 *
 * Construct MessageStream BEFORE calling [JsonRpcClient.connect] so the
 * consumer coroutine is hot by the time the server's first event (typically
 * `gateway.ready`) is delivered. The underlying [Flow] has replay = 0 — late
 * subscribers don't see past events. (Same constraint the desktop and web
 * clients rely on: "register listeners before awaiting open".)
 *
 * ## Scope of this slice
 *
 * Handles the events PR1 needs to render a single chat turn end-to-end +
 * a small set of failure modes that would otherwise hang the bubble:
 *
 *   message.start · message.delta · message.complete · message.user
 *   reasoning.delta · reasoning.available · thinking.delta (ignored)
 *   tool.start · tool.progress · tool.generating · tool.complete
 *   error
 *
 * Events carry the daemon's stamped identity (message_id/seq/ts, identity
 * plan P1): the bubble id IS the server message id, a per-session seq
 * watermark drops replay overlap, and session.subscribe replay flows
 * through this same dispatch — live and replayed frames are identical.
 *
 * Defers to follow-up commits (each ties into a distinct UI surface):
 *
 *   clarify.request · approval.request · sudo.request · secret.request
 *   subagent.spawn_requested · subagent.start
 *   status.update · session.info · skin.changed · gateway.ready ·
 *   background.complete
 *
 * Unknown event types are debug-logged and silently dropped — the server
 * can introduce events anytime and the chat shouldn't break.
 *
 * ## Delta batching
 *
 * Streaming text/reasoning deltas are coalesced over a [flushInterval]
 * window (default 33ms ≈ 30 fps) so the chat doesn't re-render on every
 * token. Non-streaming events (tool.*, message.complete, message.start)
 * force-flush the buffered deltas before mutating so events apply in stream
 * order. `reasoning.available` finalizes immediately, matching desktop.
 *
 * ## Threading
 *
 * Pass a [scope] tied to your component lifecycle. The constructor launches
 * one consumer that drains `events`; cancel the scope (or call [close]) to
 * stop. Per-session state lives in a [ConcurrentHashMap] of
 * [MutableStateFlow]s so two sessions can stream concurrently without
 * contention.
 */
class MessageStream(
    private val events: Flow<GatewayEvent>,
    private val scope: CoroutineScope,
    private val chatDao: ChatDao,
    private val json: Json,
    private val flushInterval: Long = 33L,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Logging seam — this class lives in :shared/jvmSharedMain, which has no
     *  Android SDK. The Android call site (MarmaladeRuntime) wires these to
     *  `Log.w`/`Log.d` under the "MessageStream" tag; the defaults are no-ops
     *  so tests stay silent. */
    private val logWarn: (String) -> Unit = {},
    private val logDebug: (String) -> Unit = {},
) {
    /**
     * Convenience constructor: subscribe to a [JsonRpcClient]'s event flow.
     */
    constructor(
        client: JsonRpcClient,
        scope: CoroutineScope,
        chatDao: ChatDao,
        json: Json,
        flushInterval: Long = 33L,
        now: () -> Long = { System.currentTimeMillis() },
        logWarn: (String) -> Unit = {},
        logDebug: (String) -> Unit = {},
    ) : this(client.events, scope, chatDao, json, flushInterval, now, logWarn, logDebug)

    /**
     * Model id → display label, mirrored from `model.list` by
     * [app.marmalade.android.chat.ChatController.refreshModels].
     *
     * Only the `effort.clamped` transcript line reads it, and only to name the
     * model in human terms. A plain mutable field rather than a constructor
     * arg because ChatController is built AFTER this (it takes the stream), so
     * a lookup lambda would close a construction cycle for one string.
     * Unpopulated (the window before the first model.list) degrades to the raw
     * model id.
     */
    @Volatile
    var modelLabels: Map<String, String> = emptyMap()

    /**
     * Render state for one session — the message list the UI binds to.
     *
     * - [messages] — finalized bubbles, oldest-first.
     * - [pending] — the still-streaming assistant bubble (or null when idle).
     *   Rendered after [messages].
     * - [streaming] — true between `message.start` and `message.complete`
     *   (i.e. an assistant response is in flight). UI can use this for a
     *   composer-disabled / spinner indicator. Renamed from `busy` to avoid
     *   confusion with the broader `session.info.running` flag that a future
     *   commit may surface separately.
     */
    data class SessionMessages(
        val messages: List<ChatMessage> = emptyList(),
        val pending: ChatMessage? = null,
        val streaming: Boolean = false,
    ) {
        /** The list as the UI sees it, [pending] appended last. */
        fun allMessages(): List<ChatMessage> =
            if (pending != null) messages + pending else messages
    }

    /**
     * Emitted by [finalizedAssistants] whenever a turn finalizes — either via
     * `message.complete` (canonical text) or `error` (terminal failure). The
     * [message] is the final ChatMessage with `pending = false` (and `error`
     * set on the failure path).
     */
    data class AssistantFinalized(
        val sessionId: String,
        val message: ChatMessage,
        val usage: UsageDelta? = null,
        val model: String? = null,
        val finishReason: String? = null,
    )

    /**
     * Token-count delta surfaced from `message.complete` payload. Schema
     * follows the gateway's reported shape: input/output/cache_read/
     * cache_write are deltas for THIS turn; total is the running
     * session-level total when the gateway provides it.
     */
    @Serializable
    data class UsageDelta(
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val cacheReadTokens: Long? = null,
        val cacheWriteTokens: Long? = null,
        val totalTokens: Long? = null,
        /** Context-window occupancy. On marmaladed these ride the
         *  `message.complete` usage block (daemon normalize.ts wireUsage —
         *  last API call's input+cache+output vs the model's contextWindow).
         *  Drives the composer's context donut. Absent when the harness
         *  doesn't report a window. */
        val contextUsed: Long? = null,
        val contextMax: Long? = null,
        val contextPercent: Int? = null,
        /** Estimated turn/session cost in USD, when the gateway can price it. */
        val costUsd: Double? = null,
        /** Number of context compressions the session has undergone. */
        val compressions: Int? = null,
    ) {
        /**
         * Spread-merge: fields [delta] carries overwrite this snapshot's;
         * fields it omits are preserved. Shared by the `session.info` and
         * `message.complete` usage paths (mirrors desktop's setCurrentUsage
         * at use-message-stream.ts:817-819) so per-provider field-availability
         * variations never null out prior counts.
         */
        fun mergedWith(delta: UsageDelta): UsageDelta = copy(
            inputTokens = delta.inputTokens ?: inputTokens,
            outputTokens = delta.outputTokens ?: outputTokens,
            cacheReadTokens = delta.cacheReadTokens ?: cacheReadTokens,
            cacheWriteTokens = delta.cacheWriteTokens ?: cacheWriteTokens,
            totalTokens = delta.totalTokens ?: totalTokens,
            contextUsed = delta.contextUsed ?: contextUsed,
            contextMax = delta.contextMax ?: contextMax,
            contextPercent = delta.contextPercent ?: contextPercent,
            costUsd = delta.costUsd ?: costUsd,
            compressions = delta.compressions ?: compressions,
        )
    }

    private val sessions = ConcurrentHashMap<String, MutableStateFlow<SessionMessages>>()
    private val pendingDeltas = ConcurrentHashMap<String, StringBuilder>()
    private val pendingReasoning = ConcurrentHashMap<String, StringBuilder>()
    private val flushJobs = ConcurrentHashMap<String, Job>()
    private val consumerJob: Job

    /**
     * Highest server-minted seq applied per session THIS process (identity
     * plan P1: seq is the ordering key + dedup guard). A stamped event whose
     * seq is <= this is a replay overlap (session.subscribe replays from the
     * Room cursor, which can trail what memory already applied) and is
     * dropped. Unstamped events (seq absent — legacy gateway) bypass the
     * guard entirely.
     */
    private val lastAppliedSeq = ConcurrentHashMap<String, Long>()

    /** Highest seq applied for [sessionId] this process (0 = none). */
    fun lastSeq(sessionId: String): Long = lastAppliedSeq[sessionId] ?: 0L

    /** Per-session counter for the gateway_events ring-buffer prune cadence. */
    private val eventsSinceLastPrune = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Emits whenever an assistant turn finalizes (message.complete or
     * onErrorEvent). Consumers (ChatController) use this for side-effect
     * dispatch (marmalade_action, session.lastMessageAt bump, cross-session
     * notification). Decoupled from the per-session StateFlow so the public
     * API doesn't need to expose internal scratch state.
     */
    private val _finalizedAssistants = MutableSharedFlow<AssistantFinalized>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val finalizedAssistants: SharedFlow<AssistantFinalized> = _finalizedAssistants.asSharedFlow()

    /**
     * Per-session debounced Room writer for the streaming bubble + just-
     * finalized messages. 200ms cadence matches the OLD client; force-flushed
     * on terminal events. Survives crash-mid-stream up to the last debounce
     * window, which is the same guarantee desktop and OLD-Android make.
     */
    // Public (was `internal`) since the chat slice moved to :shared: the
    // Android runtime and the outbox tests construct OutboxDrainer with this
    // coordinator from another module.
    val persistence = PersistenceCoordinator(
        scope = scope,
        flush = ::flushSessionToRoom,
    )

    /**
     * Flush callback: write the session's current pending bubble and
     * just-finalized messages to Room as MessageEntity rows (REPLACE on
     * conflict). Outbox-acked user rows already live in Room and are
     * untouched — we only write what SessionMessages knows about.
     *
     * The bucket key here is the GATEWAY session_id stamped on each
     * event, but messages.sessionKey FKs to sessions.key (the local
     * "chat-yyyymmdd-…" / "main" id). Translate via the DAO before
     * inserting — without this every flush hits FOREIGN KEY constraint
     * failed and the assistant bubble never lands in Room (UI binds
     * to Room → user sees nothing after their send).
     */
    private suspend fun flushSessionToRoom(sessionKey: String) {
        val snapshot = sessions[sessionKey]?.value ?: return
        val localKey = chatDao.resolveLocalKeyForGatewayId(sessionKey) ?: run {
            logWarn("flushSessionToRoom: no local key for gateway id $sessionKey — skipping")
            return
        }
        val rows = buildList {
            snapshot.messages.forEach { add(it.toMessageEntity(localKey, json)) }
            snapshot.pending?.let { add(it.toMessageEntity(localKey, json)) }
        }
        if (rows.isEmpty()) return
        try {
            for (row in rows) {
                chatDao.insertMessage(row)
            }
        } catch (t: Throwable) {
            logWarn("flushSessionToRoom failed for $sessionKey (local=$localKey): ${t.message}")
        }
    }

    @Volatile
    private var activeSessionId: String? = null

    init {
        consumerJob = scope.launch {
            events.collect { event -> handle(event) }
        }
    }

    /**
     * Set the session whose events the user is currently watching. Live events
     * arriving without a `session_id` (the gateway only stamps unscoped events
     * for background sessions — the focused turn's output is unstamped) are
     * routed to this id. Mirrors desktop's `activeSessionIdRef`
     * (`use-message-stream.ts`).
     *
     * Pass `null` when no session is focused (e.g. the session list is open
     * but no chat is selected) — unscoped non-subagent events are dropped
     * with a warning rather than guessed.
     */
    fun setActiveSession(sessionId: String?) {
        activeSessionId = sessionId
    }

    // seedInflight (the resume `inflight` snapshot merge) is GONE: the
    // daemon's session.subscribe(since_seq) replays the actual stamped
    // events from its transcript cache, so a reconnect mid-turn rebuilds the
    // partial bubble from the same code path as live streaming. No snapshot,
    // no content-merge heuristics.

    /** Stop consuming events and cancel any in-flight flush timers. */
    fun close() {
        consumerJob.cancel()
        flushJobs.values.forEach { it.cancel() }
        flushJobs.clear()
        pendingDeltas.clear()
        pendingReasoning.clear()
    }

    /**
     * Internal scratch state per session — the in-memory accumulator that
     * the PersistenceCoordinator flushes to Room. Exposed as `internal` only
     * for test fixtures; production callers should observe Room directly
     * (see ChatController.messages) and react to finalized turns via
     * [finalizedAssistants].
     */
    // Public, not `internal`: exercised by an `:app` unit test, and `:app`'s test
    // compilation is a friend of `:app`'s main compilation only — never of
    // `:shared`'s. See f142ad9 ("the internal trap").
    fun sessionMessages(sessionId: String): StateFlow<SessionMessages> =
        bucketFor(sessionId).asStateFlow()

    /**
     * Fully evict a session — drops its bucket, deltas, and flush timer.
     * Use when the user deletes the session entirely so we don't accumulate
     * dead state over a long-running process. Existing subscribers to the
     * bucket's StateFlow stop seeing updates.
     */
    fun removeSession(sessionId: String) {
        flushJobs.remove(sessionId)?.cancel()
        pendingDeltas.remove(sessionId)
        pendingReasoning.remove(sessionId)
        sessions.remove(sessionId)
        lastAppliedSeq.remove(sessionId)
        announcedStart.remove(sessionId)
    }

    // ── event dispatch ──────────────────────────────────────────────────────

    private suspend fun handle(event: GatewayEvent) {
        // Terminal events (terminal.*) are NOT session events — no session_id,
        // no seq. TerminalController routes them around the chat path; excluding
        // them here keeps terminal output out of the active session's diagnostic
        // ring buffer and the unscoped-event resolution (mirrors the webui
        // dispatchEvent early-return before the session gate).
        if (event.type.startsWith("terminal.")) return
        val sessionId = resolveSessionId(event) ?: return

        recordToRingBuffer(sessionId, event)

        // Seq guard (P1): drop stamped events already applied — replay
        // overlap after session.subscribe, or a duplicate delivery. seq
        // orders; a monotonic per-session watermark is all dedup needs on a
        // single ordered socket. Unstamped events pass through untouched.
        val payload = payloadObject(event)
        val seq = payload.longOrNull("seq")
        if (seq != null) {
            val applied = lastAppliedSeq[sessionId] ?: 0L
            if (seq <= applied) {
                logDebug("dropping replayed event ${event.type} seq=$seq (applied=$applied)")
                return
            }
            lastAppliedSeq[sessionId] = seq
        }

        when (event.type) {
            "message.start" -> onMessageStart(
                sessionId,
                messageId = payload.stringOrNull("message_id"),
                seq = seq ?: 0L,
                ts = payload.longOrNull("ts"),
            )
            "message.user" -> onUserMessage(sessionId, payload)
            "message.delta" -> onTextDelta(sessionId, payloadText(event), payload.stringOrNull("message_id"))
            "thinking.delta" -> { /* spinner status; ignored — UI shows its own */ }
            "reasoning.delta" -> onReasoningDelta(sessionId, payloadText(event), finalize = false)
            "reasoning.available" -> onReasoningAvailable(sessionId, payloadText(event))
            "message.complete" -> onMessageComplete(sessionId, payloadText(event), payload)
            "tool.start", "tool.progress", "tool.generating" ->
                onToolEvent(sessionId, payload, ToolPhase.Running)
            "tool.complete" ->
                onToolEvent(sessionId, payload, ToolPhase.Complete)
            "error" -> onErrorEvent(sessionId, payload)
            "effort.clamped" -> onEffortClamped(sessionId, payload)
            else -> logDebug("unhandled event: ${event.type}")
        }
    }

    /**
     * Replayed / cross-device user message (transcript event `message.user`,
     * P4 replay completeness). Written straight to Room — it is a finalized
     * message, not stream state. Dedup is by the server-minted id: the
     * device that SUBMITTED the prompt already holds this row (the outbox
     * ack bound it to the same message_id), so same id = same message =
     * skip. The Room flow re-renders on insert; no bucket mutation needed.
     */
    private suspend fun onUserMessage(sessionId: String, payload: JsonObject) {
        val messageId = payload.stringOrNull("message_id") ?: return
        val text = payload.stringOrNull("text") ?: ""
        val seq = payload.longOrNull("seq") ?: 0L
        val ts = payload.longOrNull("ts") ?: now()
        try {
            if (chatDao.messageExists(messageId)) return
            val localKey = chatDao.resolveLocalKeyForGatewayId(sessionId) ?: sessionId
            val origin = payload["origin"] as? JsonObject
            // Daemon-minted, never spoofable: "text"|"voice"|"cron"|"agent"|…
            // "voice" also lights the mic affordance; "cron"/"agent" get a
            // distinct scheduled/cross-session label (see UserTextPart). The
            // agent turn's deviceId is "session:<sender>" — the "from session X".
            val originSource = origin?.stringOrNull("source")
            // Wire is snake_case (daemon identity.ts wireOrigin: device_id).
            val originDeviceId = origin?.stringOrNull("device_id")
            val steered = (payload["steered"] as? JsonPrimitive)?.booleanOrNull == true
            chatDao.insertMessage(
                ChatMessage.user(id = messageId, text = text, timestamp = ts)
                    .copy(
                        seq = seq,
                        voiceOrigin = originSource == "voice",
                        steered = steered,
                        originSource = originSource,
                        originDeviceId = originDeviceId,
                    )
                    .toMessageEntity(sessionKey = localKey, json = json),
            )
        } catch (t: Throwable) {
            logWarn("message.user insert failed ($messageId): ${t.message}")
        }
    }

    /**
     * A per-model effort bound moved the requested reasoning effort
     * (`effort.clamped`, daemon 2026-07-27). Written to Room as a System-role
     * message — a DURABLE transcript row, like `message.user`, not stream
     * state: the daemon stamps and caches the event, so it replays in place on
     * cold load and the record has to survive the same way the transcript does.
     *
     * Design-lab option E3 (maintainer, 2026-07-27): a quiet permanent line, not a
     * toast and not a card. The wording lives in :shared ([effortClampedLine])
     * so the settings screen and this can't name a level differently.
     *
     * The id is derived from (session, seq) rather than minted: the seq
     * watermark already drops in-process replay, but a cold load re-subscribes
     * from Room's max seq and a boundary overlap would otherwise insert the
     * same clamp twice. Same-id = same event = skip, exactly as
     * [onUserMessage] dedups.
     */
    private suspend fun onEffortClamped(sessionId: String, payload: JsonObject) {
        val effective = payload.stringOrNull("effective") ?: return
        val bound = payload.stringOrNull("bound") ?: return
        val model = payload.stringOrNull("model") ?: return
        val seq = payload.longOrNull("seq") ?: 0L
        val ts = payload.longOrNull("ts") ?: now()
        val id = "effort-clamped:$sessionId:${if (seq != 0L) seq else ts}"
        val line = effortClampedLine(
            effective = effective,
            bound = bound,
            // The catalog label when we have one; the raw model id otherwise.
            // An id is ugly but never a lie, and this row is written now — it
            // can't wait for model.list to land.
            modelLabel = modelLabels[model] ?: model,
        )
        try {
            if (chatDao.messageExists(id)) return
            val localKey = chatDao.resolveLocalKeyForGatewayId(sessionId) ?: sessionId
            chatDao.insertMessage(
                ChatMessage(
                    id = id,
                    role = ChatRole.System,
                    parts = listOf(ChatMessagePart.Text(line)),
                    timestamp = ts,
                    seq = seq,
                ).toMessageEntity(sessionKey = localKey, json = json),
            )
        } catch (t: Throwable) {
            logWarn("effort.clamped insert failed ($id): ${t.message}")
        }
    }

    /**
     * Resolve which session an event applies to. The gateway only stamps a
     * `session_id` on events for *background* sessions; the focused turn's
     * output arrives unstamped because there's only one active turn at a
     * time. Subagent events are the exception — they always carry their own
     * (subagent) session_id; if an unscoped `subagent.*` event arrives, we
     * have no safe target and drop it.
     *
     * Matches desktop's `gatewayEventRequiresSessionId` (returns true only for
     * `subagent.*`). The prior set-based implementation here had the polarity
     * inverted and silently swallowed every live message/reasoning/tool event
     * on single-session connections.
     */
    private fun resolveSessionId(event: GatewayEvent): String? {
        val stamped = event.sessionId
        if (!stamped.isNullOrEmpty()) return stamped
        if (event.type.startsWith("subagent.")) {
            logWarn("dropping subagent event without session_id: ${event.type}")
            return null
        }
        val active = activeSessionId
        if (active.isNullOrEmpty()) {
            logWarn("no active session for unscoped event: ${event.type}")
            return null
        }
        return active
    }

    // ── diagnostic ring buffer ──────────────────────────────────────────────
    //
    // Best-effort write of every gateway frame to the gateway_events table.
    // Not load-bearing — failures are swallowed so a transient DB issue
    // never breaks the chat. Surfaced through the Settings → Debug → Event
    // Trace screen (Phase 11).

    private suspend fun recordToRingBuffer(sessionId: String, event: GatewayEvent) {
        try {
            val payloadJson = event.payload?.toString() ?: "null"
            // sessionId is the gateway's LIVE session_id (rotates on reconnect).
            // sessions.key is the persistent stored_session_id post-K1. Resolve
            // LIVE → stored so the FK reference lands on the existing row;
            // unresolved (e.g. unscoped diagnostic event, or session not yet
            // session.create'd) writes a null sessionKey — the column is
            // nullable per G1, so the row still records under "no session".
            val storedKey = chatDao.resolveLocalKeyForGatewayId(sessionId)
            chatDao.insertGatewayEvent(
                GatewayEventEntity(
                    sessionKey = storedKey,
                    type = event.type,
                    payloadJson = payloadJson,
                    receivedAtMs = now(),
                ),
            )
            val counter = eventsSinceLastPrune.getOrPut(sessionId) { AtomicInteger(0) }
            if (counter.incrementAndGet() >= PRUNE_EVERY_N_EVENTS) {
                counter.set(0)
                // Prune by the SAME key we wrote under — storedKey, not the
                // live sessionId — otherwise the ring buffer would never trim.
                // The null bucket (unresolved-session events) prunes on the
                // same cadence: nothing else ever trims it, so pre-fix it
                // grew without bound (the DAO query's `IS :sessionKey`
                // matches null).
                chatDao.pruneGatewayEventsKeepingLast(storedKey, RING_BUFFER_CAP)
            }
        } catch (t: Throwable) {
            logWarn("ring buffer write failed for ${event.type}: ${t.message}")
        }
    }

    // ── handlers ────────────────────────────────────────────────────────────

    private suspend fun onMessageStart(
        sessionId: String,
        messageId: String?,
        seq: Long,
        ts: Long?,
    ) {
        flushDeltas(sessionId)
        // Cold-start replay of a turn whose completion we never stored: the
        // partial Room row (boot-demoted from isStreaming) carries the SAME
        // server id this replayed start announces. Delete it — the replay
        // that follows rebuilds the full message. Never happens for finalized
        // turns: their rows store the COMPLETE event's seq, so the replay
        // cursor starts after them.
        if (messageId != null) {
            runCatching {
                if (chatDao.messageExists(messageId)) chatDao.deleteMessage(messageId)
            }
        }
        bucketFor(sessionId).update { state ->
            // Re-entrant message.start (reconnect mid-turn, or server pivots to a
            // fresh message without sending message.complete for the old one):
            // finalize the previous pending bubble into messages rather than
            // silently discarding the user's partial response. The CLAUDE.md
            // run-lifecycle rule (server owns lifecycle; WS reconnect must not
            // kill the run) means we treat the old pending bubble as a real
            // utterance that just didn't get a clean close.
            val carriedMessages = if (state.pending != null) {
                state.messages + state.pending.copy(pending = false)
            } else {
                state.messages
            }
            // Don't eagerly create an empty assistant bubble — desktop creates
            // it lazily on the first delta or tool event (matched here via
            // ensurePendingBubble). If the turn aborts before any payload, the
            // user doesn't see a phantom empty row.
            state.copy(messages = carriedMessages, pending = null, streaming = true)
        }
        // Stash the server-minted identity of the announced message so the
        // lazily-created bubble adopts it (ids are names, minted once by the
        // daemon — never synthesize when the server minted one).
        if (messageId != null) {
            announcedStart[sessionId] = AnnouncedStart(messageId, seq, ts ?: now())
        } else {
            announcedStart.remove(sessionId)
        }
        persistence.schedule(sessionId)
    }

    /** Identity from the last `message.start` (P1) awaiting its lazy bubble. */
    private data class AnnouncedStart(val messageId: String, val seq: Long, val ts: Long)
    private val announcedStart = ConcurrentHashMap<String, AnnouncedStart>()

    private fun onTextDelta(sessionId: String, delta: String, messageId: String? = null) {
        if (delta.isEmpty()) return
        ensurePendingBubble(sessionId, messageId)
        pendingDeltas.getOrPut(sessionId) { StringBuilder() }.append(delta)
        scheduleFlush(sessionId)
    }

    private fun onReasoningDelta(sessionId: String, delta: String, finalize: Boolean) {
        if (delta.isEmpty() && !finalize) return
        ensurePendingBubble(sessionId)
        if (delta.isNotEmpty()) {
            pendingReasoning.getOrPut(sessionId) { StringBuilder() }.append(delta)
        }
        if (finalize) flushDeltas(sessionId) else scheduleFlush(sessionId)
    }

    /**
     * `reasoning.available` is a one-shot finalize: drop any prior streamed
     * reasoning parts and insert this one canonical reasoning part, BUT only
     * if no assistant text has been streamed yet (matching desktop's
     * `appendReasoningDelta(..., replace=true)` semantics). When text has
     * already streamed the canonical reasoning is redundant — desktop skips
     * the replace; we do too.
     */
    private fun onReasoningAvailable(sessionId: String, text: String) {
        flushDeltas(sessionId)
        if (text.isEmpty()) return
        ensurePendingBubble(sessionId)
        bucketFor(sessionId).update { state ->
            val pending = state.pending ?: return@update state
            val hasText = pending.parts.any { it is ChatMessagePart.Text && it.text.isNotEmpty() }
            if (hasText) return@update state
            val withoutReasoning = pending.parts.filterNot { it is ChatMessagePart.Reasoning }
            state.copy(pending = pending.copy(parts = withoutReasoning + ChatMessagePart.Reasoning(text)))
        }
        persistence.schedule(sessionId)
    }

    private fun onToolEvent(sessionId: String, payload: JsonObject, phase: ToolPhase) {
        flushDeltas(sessionId)
        ensurePendingBubble(sessionId, payload.stringOrNull("message_id"))
        bucketFor(sessionId).update { state ->
            val current = state.pending ?: return@update state
            state.copy(pending = current.copy(parts = upsertToolPart(current.parts, payload, phase)))
        }
        persistence.schedule(sessionId)
    }

    private fun onMessageComplete(sessionId: String, finalText: String, payload: JsonObject) {
        flushDeltas(sessionId)
        var finalizedForEmit: ChatMessage? = null
        // The finalized row stores the COMPLETE event's seq (not the start's):
        // MAX(serverSeq) is the session.subscribe replay cursor, and a
        // finalized turn must place the cursor AFTER its whole event range so
        // it is never replayed. A mid-stream partial keeps the start seq and
        // IS replayed (message.start deletes + rebuilds it).
        val completeSeq = payload.longOrNull("seq") ?: 0L
        // has_cut_point (daemon, additive): tri-state — absent on pre-flag
        // transcripts (null = offer branch and let the daemon decide).
        val hasCutPoint = (payload["has_cut_point"] as? JsonPrimitive)?.booleanOrNull
        bucketFor(sessionId).update { state ->
            val pending = state.pending?.let {
                var p = if (completeSeq > 0L) it.copy(seq = completeSeq) else it
                if (hasCutPoint != null) p = p.copy(hasCutPoint = hasCutPoint)
                p
            } ?: return@update state.copy(streaming = false)
            val errored = completionErrorText(finalText, payload)
            val finalized = when {
                errored != null -> pending.copy(
                    parts = pending.parts.filterNot { it is ChatMessagePart.Text },
                    pending = false,
                    error = errored,
                )
                finalText.isNotEmpty() -> pending.copy(
                    parts = replaceStreamedTextWithCanonical(pending.parts, finalText),
                    pending = false,
                )
                pending.parts.isEmpty() -> {
                    // Empty `message.complete` with no streamed parts — render a
                    // placeholder rather than a blank bubble. Surfaces server
                    // bugs / aborted turns more visibly.
                    pending.copy(parts = listOf(ChatMessagePart.Text("(empty response)")), pending = false)
                }
                else -> pending.copy(pending = false)
            }
            finalizedForEmit = finalized
            state.copy(messages = state.messages + finalized, pending = null, streaming = false)
        }
        // Drop the buffers — turn is over.
        pendingDeltas.remove(sessionId)
        pendingReasoning.remove(sessionId)
        flushJobs.remove(sessionId)?.cancel()
        persistence.flushNow(sessionId)
        finalizedForEmit?.let { msg ->
            val usage = extractUsage(payload)
            val model = payload.stringOrNull("model")
            val finishReason = payload.stringOrNull("finish_reason")
                ?: payload.stringOrNull("stop_reason")
            scope.launch {
                _finalizedAssistants.emit(
                    AssistantFinalized(sessionId, msg, usage, model, finishReason),
                )
            }
        }
    }

    /**
     * Server-emitted terminal error (not the same as completion text that
     * starts with "API call failed…"). Marks the pending bubble as errored,
     * appends to messages, and clears streaming flag — otherwise the bubble
     * spins forever on a server crash mid-turn.
     */
    private fun onErrorEvent(sessionId: String, payload: JsonObject) {
        flushDeltas(sessionId)
        val msg = payload.stringOrNull("message") ?: payload.stringOrNull("error") ?: "agent error"
        val errorSeq = payload.longOrNull("seq") ?: 0L
        var finalizedForEmit: ChatMessage? = null
        bucketFor(sessionId).update { state ->
            val pending = state.pending ?: return@update state.copy(streaming = false)
            val finalized = pending.copy(
                pending = false,
                error = msg,
                seq = if (errorSeq > 0L) errorSeq else pending.seq,
            )
            finalizedForEmit = finalized
            state.copy(
                messages = state.messages + finalized,
                pending = null,
                streaming = false,
            )
        }
        pendingDeltas.remove(sessionId)
        pendingReasoning.remove(sessionId)
        flushJobs.remove(sessionId)?.cancel()
        persistence.flushNow(sessionId)
        finalizedForEmit?.let { m ->
            scope.launch { _finalizedAssistants.emit(AssistantFinalized(sessionId, m)) }
        }
    }

    // ── batching ────────────────────────────────────────────────────────────

    /**
     * When true, streaming deltas bypass the [flushInterval] coalescing
     * window and publish to the messages StateFlow immediately. The 33 ms
     * batch exists purely for render smoothness (~30 fps), but the voice
     * popup's speech feeder reads the SAME StateFlow — so with batching on,
     * time-to-first-speech pays a render optimization it gets nothing from.
     * The voice session enables this for the duration of a voice turn
     * (every ms before the first spoken chunk is user-audible) and always
     * resets it after; chat-only streaming keeps the coalesced default.
     */
    @Volatile
    var immediateDeltaFlush: Boolean = false

    private fun scheduleFlush(sessionId: String) {
        if (immediateDeltaFlush) {
            // Same call the event handlers make inline (onMessageStart etc.);
            // safe from the event-collect coroutine.
            flushDeltas(sessionId)
            return
        }
        // computeIfAbsent — atomic check-and-set so a delta-arrival never
        // races a job-completion to drop the flush.
        flushJobs.computeIfAbsent(sessionId) {
            scope.launch {
                delay(flushInterval)
                flushDeltas(sessionId)
            }
        }
    }

    /**
     * Drain the per-session text and reasoning buffers into the pending
     * bubble. Always called from event handlers BEFORE state mutation so
     * subsequent updates apply against an up-to-date parts list.
     *
     * Drains the flush job FIRST, then the buffers, then mutates — that
     * order means a delta arriving between buffer-drain and job-removal
     * still races a fresh scheduleFlush, but `computeIfAbsent` makes the
     * scheduling atomic and the new buffer entry survives.
     */
    private fun flushDeltas(sessionId: String) {
        flushJobs.remove(sessionId)?.cancel()
        val textBuffer = pendingDeltas.remove(sessionId)
        val reasoningBuffer = pendingReasoning.remove(sessionId)
        val textHas = textBuffer != null && textBuffer.isNotEmpty()
        val reasoningHas = reasoningBuffer != null && reasoningBuffer.isNotEmpty()
        if (!textHas && !reasoningHas) return

        ensurePendingBubble(sessionId)
        bucketFor(sessionId).update { state ->
            val pending = state.pending ?: return@update state
            var parts = pending.parts
            if (textHas) parts = appendTextPart(parts, textBuffer!!.toString())
            if (reasoningHas) parts = appendReasoningPart(parts, reasoningBuffer!!.toString())
            state.copy(pending = pending.copy(parts = parts))
        }
        persistence.schedule(sessionId)
    }

    /**
     * Force-flush every pending Room write. Call from lifecycle hooks
     * (onPause, onStop, process-death handlers) so users never lose more
     * than ~debounceMs of streaming content on app kill.
     */
    fun flushPersistenceNow() {
        persistence.flushAll()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun ensurePendingBubble(sessionId: String, eventMessageId: String? = null) {
        bucketFor(sessionId).update { state ->
            if (state.pending != null) state
            else {
                // Bubble identity, best source first: the message.start
                // announcement (P1 server-minted id + seq + ts), then the
                // triggering event's own message_id stamp, then — legacy
                // gateway only — a synthesized local id.
                val announced = announcedStart.remove(sessionId)
                state.copy(
                    pending = ChatMessage.assistantPending(
                        id = announced?.messageId ?: eventMessageId
                            ?: "assistant-${UUID.randomUUID()}",
                        timestamp = announced?.ts ?: now(),
                        seq = announced?.seq ?: 0L,
                    ),
                    streaming = true,
                )
            }
        }
    }

    private fun bucketFor(sessionId: String): MutableStateFlow<SessionMessages> =
        sessions.computeIfAbsent(sessionId) { MutableStateFlow(SessionMessages()) }

    private fun payloadObject(event: GatewayEvent): JsonObject =
        (event.payload as? JsonObject) ?: JsonObject(emptyMap())

    private fun payloadText(event: GatewayEvent): String {
        val obj = (event.payload as? JsonObject) ?: return ""
        return coerceText(obj["text"]) ?: coerceText(obj["rendered"]) ?: ""
    }

    private fun coerceText(element: JsonElement?): String? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> if (element.isString) element.content else null
        else -> null
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    /**
     * Replace the TRAILING streamed text segment with the canonical
     * [finalText] from `message.complete`, preserving reasoning, tool-call
     * parts, and any inter-tool NARRATION text segments in place.
     *
     * Derived from desktop's `replaceTextPart` (use-message-stream.ts:556),
     * with one deliberate deviation: desktop drops ALL streamed text and
     * keeps only the canonical final answer. `finalText` covers only the
     * LAST LLM call's response, so narration streamed before a tool call
     * (an earlier call's response within the same turn) is lost there —
     * desktop can afford that because its resume replaces the whole thread
     * with server history (which keeps narration; server.py
     * `_history_to_messages`). Android's `reconcileHistory` instead
     * correlates local rows with that history BY CONTENT: dropping
     * narration made every narration-bearing tool turn mismatch its server
     * row, so each hydrate inserted a degraded duplicate and a later
     * reconcile pruned the rich local row — the on-device
     * duplicated-turn / vanishing-tool-card bugs (maintainer, 2026-07-02).
     * Keeping narration also makes the live rendering agree with what a
     * reload shows.
     *
     * The trailing segment (all text after the last non-streaming part) IS
     * dropped and replaced — the server's canonical version is
     * authoritative; streamed deltas can miss tail bytes. Reasoning parts
     * whose text is a prefix-of / contained-in the final text are filtered
     * out as redundant (whitespace-normalised compare, matching desktop's
     * `normalize(value).replace(/\s+/g, ' ').trim()`).
     */
    private fun replaceStreamedTextWithCanonical(
        parts: List<ChatMessagePart>,
        finalText: String,
    ): List<ChatMessagePart> {
        val normalizedFinal = normalizeForDedupe(finalText)
        // Segment boundary: text after the last tool/image/file part belongs
        // to the final LLM call; text before it is kept narration.
        val lastBoundary = parts.indexOfLast {
            it !is ChatMessagePart.Text && it !is ChatMessagePart.Reasoning
        }
        val kept = parts.filterIndexed { index, part ->
            when (part) {
                is ChatMessagePart.Text -> index < lastBoundary
                is ChatMessagePart.Reasoning -> finalText.isEmpty() || run {
                    val normalizedPart = normalizeForDedupe(part.text)
                    !(normalizedFinal.startsWith(normalizedPart) ||
                        normalizedPart.startsWith(normalizedFinal))
                }
                else -> true
            }
        }
        return if (finalText.isEmpty()) kept else kept + ChatMessagePart.Text(finalText)
    }

    private fun normalizeForDedupe(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    /**
     * Extract a [UsageDelta] from the message.complete payload. Delegates
     * to the companion so [ChatController] can call the same logic from
     * session.info without duplicating the parsing.
     */
    private fun extractUsage(payload: JsonObject): UsageDelta? =
        Companion.extractUsage(payload)

    /**
     * Detect gateway-error patterns smuggled as completion text (the agent
     * server occasionally returns provider/network failures as the message
     * body rather than as an `error` event). When matched, the bubble shows
     * an error chip + retry affordance instead of rendering the failure as
     * an answer. Mirrors desktop's `completionErrorText` /
     * `COMPLETION_ERROR_PATTERNS`.
     */
    private fun completionErrorText(finalText: String, payload: JsonObject): String? {
        // Explicit payload.error wins.
        payload.stringOrNull("error")?.let { return it }
        val trimmed = finalText.trim()
        if (trimmed.isEmpty()) return null
        for (re in COMPLETION_ERROR_PATTERNS) {
            if (re.containsMatchIn(trimmed)) return trimmed.lineSequence().first().trim()
        }
        return null
    }

    companion object {

        /** Ring-buffer cap and prune cadence (per session). 500 frames ~=
         *  one heavy turn with reasoning + several tool calls; cheap to keep
         *  but bounded. Prune runs every Nth event to amortize the DELETE. */
        private const val RING_BUFFER_CAP = 500
        private const val PRUNE_EVERY_N_EVENTS = 50

        /**
         * Extract a [UsageDelta] from an event payload that carries a
         * nested `usage` object. The gateway nests usage under a `usage`
         * object; fields are optional and not every provider reports them.
         * Returns null when the payload has no usable usage block (so
         * consumers don't accidentally zero out a session counter on an
         * event that didn't carry token info).
         *
         * Exposed as an `internal` companion function so [ChatController]
         * can reuse the same parsing logic from session.info without
         * duplicating the snake_case / short-form fallback handling.
         */
        // Public, not `internal`: exercised by an `:app` unit test, and `:app`'s test
        // compilation is a friend of `:app`'s main compilation only — never of
        // `:shared`'s. See f142ad9 ("the internal trap").
        fun extractUsage(payload: JsonObject): UsageDelta? {
            val usage = (payload["usage"] as? JsonObject) ?: return null
            fun longOf(key: String): Long? =
                (usage[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
            fun doubleOf(key: String): Double? =
                (usage[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            // Context occupancy is taken as a PAIR and its percentage
            // recomputed: the block's own `context_percent` is a second source
            // of truth for a number the daemon derives (router.ts /
            // normalize.ts use this same formula), and both halves are required
            // — used without a window is unknown, never a fabricated bar. Same
            // rule the cold seed goes through, so the two agree.
            val occupancy = contextOccupancy(longOf("context_used"), longOf("context_max"))
            val u = UsageDelta(
                inputTokens = longOf("input_tokens") ?: longOf("input"),
                outputTokens = longOf("output_tokens") ?: longOf("output"),
                cacheReadTokens = longOf("cache_read_tokens") ?: longOf("cache_read"),
                cacheWriteTokens = longOf("cache_creation_tokens") ?: longOf("cache_write"),
                totalTokens = longOf("total_tokens") ?: longOf("total"),
                contextUsed = occupancy?.used,
                contextMax = occupancy?.max,
                contextPercent = occupancy?.percent,
                costUsd = doubleOf("cost_usd"),
                compressions = longOf("compressions")?.toInt(),
            )
            return if (
                u.inputTokens == null && u.outputTokens == null &&
                u.cacheReadTokens == null && u.cacheWriteTokens == null &&
                u.totalTokens == null && u.contextPercent == null
            ) null else u
        }

        /** Completion-text patterns that indicate a gateway / provider error
         *  (not a real assistant answer). Subset of desktop's set; expand as
         *  we encounter more in the wild. */
        private val COMPLETION_ERROR_PATTERNS = listOf(
            Regex("^API call failed after \\d+ retries:", RegexOption.IGNORE_CASE),
            Regex("^HTTP \\d{3}\\b", RegexOption.IGNORE_CASE),
            Regex("^(Provider|Gateway) error:", RegexOption.IGNORE_CASE),
            Regex("^Connection (timed out|refused|reset)", RegexOption.IGNORE_CASE),
        )
    }
}
