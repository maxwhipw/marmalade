package app.marmalade.android.chat

import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatMessagePart
import kotlinx.serialization.builtins.ListSerializer
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.chat.messages.MessageStream
import app.marmalade.android.chat.messages.contextOccupancy
import app.marmalade.android.chat.messages.seedContext
import app.marmalade.android.chat.messages.text
import app.marmalade.android.ui.snapEffortToBounds
import app.marmalade.android.chat.messages.toChatMessage
import app.marmalade.android.chat.messages.toMessageEntity
import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.QueuedPromptEntity
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.ConnectionState
import app.marmalade.android.rpc.JsonRpcException
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.FORK_UNSUPPORTED_REASON
import app.marmalade.android.rpc.types.SessionForkResponse
import app.marmalade.android.voice.MarmaladeAction
import app.marmalade.android.voice.parseMarmaladeAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Handle returned by [ChatController.sendMessage]: the local outbox id the
 * send was queued under (correlate against [ChatController.promptAcks] to
 * learn the server-minted seq of THIS submit) plus the Job running the
 * outbox insert (the composer-queue drain worker joins it for durability).
 */
data class SendHandle(val outboxId: String, val job: Job)

/**
 * A prompt.submit ack, re-broadcast from the outbox drainer: [outboxId] is
 * the local queue handle the message was sent under; [seq] is the
 * server-minted per-session seq of the submitted USER message (0 against a
 * legacy gateway whose prompt.submit returns nothing). The voice popup uses
 * this as the reply-harvest floor — the reply to this turn is the first
 * finalized assistant message with seq strictly greater.
 */
data class PromptAck(val outboxId: String, val seq: Long)

/**
 * Mediates the chat surface between the marmalade-agent transport
 * ([MarmaladeRpc] + [MessageStream]) and the UI.
 *
 * Responsibilities:
 *
 *  - **Session binding.** Tracks the currently-bound session by stable
 *    local `key` ([sessionKey]) and resolves/creates the server-side
 *    `session_id` ([sessionId]) on demand. Persists the mapping in
 *    [SessionEntity.gatewaySessionId] so cold launches don't re-create.
 *  - **Bridging messages.** Hydrates the bound session's history from
 *    Room (cold start) and from [MarmaladeRpc.sessionResume] (post-connect),
 *    then routes live events through [MessageStream] and emits the
 *    combined feed as [messages].
 *  - **Sending.** Appends a local user bubble, persists it as `sending`,
 *    fires [MarmaladeRpc.promptSubmit], and writes the result to Room.
 *    Failed/queued messages drain on next reconnect.
 *  - **MarmaladeAction dispatch.** On every finalized assistant message,
 *    parses the `{marmalade_action: {...}}` envelope and forwards to
 *    [onDispatchAction] for Android Intent firing (alarm, call, SMS,
 *    app launch, generic intent).
 *  - **Cross-session notification.** When an assistant message
 *    finalizes in a session the user isn't currently viewing, fires
 *    [onOtherSessionMessage] so [ChatNotificationHelper] can surface
 *    a lock-screen notification.
 *  - **Interactive prompts.** Holds the live `clarify.request` /
 *    `approval.request` / `secret.request` payloads in
 *    [pendingPrompts] for the inline prompt-card UI to render
 *    (card UI lands separately; the wire-side wiring is here).
 *
 * What this class deliberately does NOT do:
 *  - Stream-event coalescing (delta batching, tool-part upserts,
 *    history reconstruction) — that lives in [MessageStream] and the
 *    `chat/messages/` helpers.
 *  - Transport lifecycle — [MarmaladeRpc] / [JsonRpcClient] own it.
 *    This class observes [MarmaladeRpc.rpcClient.connectionState] to
 *    decide *when* to issue RPCs, but doesn't decide whether the
 *    socket should be open.
 *  - Multi-session foreground state — only the currently-bound
 *    session has its [messages] collected. Background sessions stream
 *    silently into [MessageStream]'s internal map; their assistant
 *    bubbles are written to Room when the user navigates to them.
 *    (Background → Room writeback for sessions never visited lands in
 *    a follow-up; today, that finalized data lives only in
 *    [MessageStream] until the user opens the session.)
 */
class ChatController(
  private val scope: CoroutineScope,
  private val rpc: MarmaladeRpc,
  private val messageStream: MessageStream,
  private val outboxDrainer: app.marmalade.android.chat.messages.OutboxDrainer,
  private val json: Json,
  private val chatDao: ChatDao,
  /** True while the app is visible. Gates the explicit `session.seen` mark on
   *  bound-session turn completion — a turn finishing while the app is
   *  backgrounded was NOT seen (it gets a notification instead). Defaults to
   *  true for tests. */
  private val isForeground: () -> Boolean = { true },
  /** Platform edges this controller can't own (voice-action dispatch, the
   *  usage-snapshot cache, prompt-notification dismissal). Null in tests and
   *  behaves exactly like the old null-Context path: every use is a no-op. */
  private val host: ChatHost? = null,
  /** Notify the runtime when an assistant bubble finalizes in a session
   *  other than the currently-bound one (drives the chat notification). */
  private val onOtherSessionMessage: ((sessionKey: String, sessionId: String?, lastText: String) -> Unit)? = null,
  /** Notify the runtime when a clarify/approval/secret/sudo prompt arrives in
   *  a session the user is NOT currently viewing, so an OS notification can be
   *  dispatched. Receives (sessionKey, displayName, prompt). The runtime resolves
   *  the display name and calls ChatNotificationHelper; keeping that logic in the
   *  runtime (not here) preserves ChatController's context-free testability. */
  private val onPromptNotification: ((sessionKey: String, prompt: PendingPrompt) -> Unit)? = null,
  /** Dispatch a parsed marmalade_action envelope. Defaults to a real
   *  Android Intent fire via [host]; tests override to a spy. */
  private val onDispatchAction: (action: MarmaladeAction) -> Unit = { action ->
    host?.dispatchVoiceAction(action)
  },
  /** Surface multi-action / malformed-JSON notices into the transport
   *  log buffer (Debugging tab). */
  private val logDispatch: (message: String, runId: String?) -> Unit = { _, _ -> },
  /** Surface unexpected server values (unknown event names, unhandled
   *  states) into the transport log buffer. */
  private val logWarn: (String) -> Unit = {},
  /** Server-advertised max inbound payload, when known. Stubbed to
   *  null today; marmalade doesn't expose a policy hello. */
  @Suppress("unused")
  private val maxPayloadBytes: () -> Long? = { null },
  /** Developer toggle: when true, unrecognized server frames render
   *  as `role="unknown"` debug cards. OFF by default. */
  @Suppress("unused")
  private val showUnknownFramesInChat: () -> Boolean = { false },
  /** Dispatcher for Room IO. Defaults to [Dispatchers.IO]; tests
   *  inject the test scheduler so coroutines stay observable under
   *  `advanceUntilIdle`. */
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  /** Delete a session server-side by its STORED id, returning true on
   *  success (or if it's already gone). Wired by the runtime
   *  (`MarmaladeRuntime.deleteChatSession`) to JSON-RPC `session.delete` —
   *  the daemon is WS-only (fork REST is dead), and the router cascades the
   *  full lineage (index row + message identity rows + every device's seen
   *  cursor + transcript) and subsumes stop for a live session, so there is
   *  no "cannot delete an active session" refusal. Null in unit tests →
   *  local-only delete. */
  private val deleteSessionRemote: (suspend (storedId: String) -> Boolean)? = null,
  /** THE daemon-managed singleton main session's id (session.main), owned by
   *  [MarmaladeRuntime] and updated on connect. Post-K1 the main session's
   *  local key == its server id == this value, so `sessionKey == mainSessionKey`
   *  identifies the bound-is-main case. Defaults to the placeholder flow in
   *  unit tests (nothing is main until the runtime resolves session.main). */
  private val mainSessionKey: StateFlow<String> = MutableStateFlow(MAIN_SESSION_PLACEHOLDER),
) {

  // ── Public StateFlows ─────────────────────────────────────────

  private val _sessionKey = MutableStateFlow("main")
  val sessionKey: StateFlow<String> = _sessionKey.asStateFlow()

  private val _sessionId = MutableStateFlow<String?>(null)
  val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

  // ── Transcript anchoring (open-at-seq) ────────────────────────
  //
  // A pending "scroll the transcript to this message" request. Lives here
  // rather than in the chat screen because the requester (a search result,
  // a notification) usually is NOT the chat screen, and because the request
  // must survive the screen not yet having the row: hydration replays the
  // missing events asynchronously (see hydrateFromServer). See [ChatAnchor].
  private val anchorRequests = ChatAnchorRequests()

  /** The pending transcript anchor, or null. The chat screen applies it when
   *  the target row exists and [ChatAnchor.sessionKey] matches the session it
   *  is bound to, then calls [consumeAnchor]. */
  val anchor: StateFlow<ChatAnchor?> = anchorRequests.anchor

  /** Ask the chat screen to open the transcript at [seq] (or at [messageId]
   *  when known). Safe to call before the session is bound / hydrated — the
   *  request is retained until the row lands. */
  fun requestAnchor(
    sessionKey: String,
    seq: Long,
    messageId: String? = null,
    query: String? = null,
  ) = anchorRequests.request(ChatAnchor(sessionKey, seq, messageId, query))

  fun requestAnchor(anchor: ChatAnchor) = anchorRequests.request(anchor)

  /** Clear [anchor] once it has been applied. One-shot by design: a second
   *  request with the same values re-fires the jump. */
  fun consumeAnchor(anchor: ChatAnchor) = anchorRequests.consume(anchor)

  /** True when the bound session is THE daemon-managed main session (the Home
   *  assistant surface). Drives main-only affordances: Clear runs
   *  `session.clear` (not a new session), delete is hidden (the daemon refuses
   *  it), and the model picker is still `session.model`. Derived off the
   *  runtime's resolved main id — the placeholder is never "main". */
  val isBoundMain: StateFlow<Boolean> =
    combine(_sessionKey, mainSessionKey) { key, main ->
      main != MAIN_SESSION_PLACEHOLDER && key == main
    }.stateIn(scope, SharingStarted.Eagerly, false)

  /**
   * The canonical chat view, derived live from Room: finalized + streaming
   * MessageEntity rows for the bound session, combined with OutboxEntity
   * rows (pending / sending / failed user prompts the drainer hasn't yet
   * promoted). flatMapLatest on _sessionKey switches subscription when the
   * user changes session; combine merges the two table flows into one
   * sorted list.
   *
   * The OLD optimistic _messages MutableStateFlow + localExtras retrofit
   * is gone — the user bubble no longer vanishes on first delta because
   * it lives in the outbox table, not in _messages.
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val messages: StateFlow<List<ChatMessage>> = _sessionKey
    .flatMapLatest { key: String ->
      combine(
        chatDao.getMessagesForSession(key),
        chatDao.observeOutboxForSession(key),
      ) { msgs: List<MessageEntity>, outbox: List<OutboxEntity> ->
        val mapped: List<ChatMessage> = msgs.map { entity ->
          val chatMessage = entity.toChatMessage(json)
          if (entity.isStreaming) {
            chatMessage.copy(streamingActivity = deriveStreamingActivity(chatMessage))
          } else {
            chatMessage
          }
        }
        // ackOutboxAsMessage promotes outbox -> messages REUSING the outbox
        // id, atomically in one transaction — but these are two independent
        // Room flows, so combine can pair a fresh messages emission (already
        // holding the promoted row) with the outbox flow's cached pre-ack
        // value (still holding its twin). Duplicate ids crash the LazyColumn
        // ("Key bubble:outbox-…:0 was already used", on-device 2026-07-05).
        // The messages row wins; the stale outbox emission catches up next.
        val mappedIds = msgs.mapTo(HashSet()) { it.id }
        val outboxBubbles: List<ChatMessage> =
          outbox.filter { it.id !in mappedIds }.map { it.toChatMessage(json) }
        // seq orders (identity plan): server-acknowledged rows sort by their
        // server-minted seq; local-only rows (seq == 0: un-acked outbox
        // bubbles, system chrome) are by definition newer than anything the
        // server has numbered, so they sort after all seq-bearing rows, by
        // local timestamp. Never order by wall clock across devices.
        (mapped + outboxBubbles).sortedWith(
          compareBy(
            { if (it.seq > 0L) 0 else 1 },
            { if (it.seq > 0L) it.seq else (it.timestamp ?: 0L) },
            { it.timestamp ?: 0L },
          )
        )
      }
    }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  /** Sidebar / session-picker list, fed live from Room. */
  val sessions: StateFlow<List<ChatSessionEntry>> = chatDao.getAllSessions()
    .map { rows -> rows.map { it.toEntry() } }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  /** Per-session mute (SessionEntity.isMuted) for the bound session — drives
   *  the settings-sheet Notifications toggle. The notification pipeline already
   *  honours this flag (MarmaladeRuntime.handleOtherSessionMessage /
   *  handlePromptNotification); this just exposes/edits it from the chat. */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val muted: StateFlow<Boolean> = _sessionKey
    .flatMapLatest { key -> chatDao.observeSessionMuted(key).map { it ?: false } }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /** Set the bound session's mute flag (Notifications toggle). */
  fun setMuted(muted: Boolean) {
    val key = _sessionKey.value
    scope.launch {
      withContext(ioDispatcher) {
        runCatching { chatDao.updateSessionMuted(key, muted) }
      }
    }
  }

  private val _errorText = MutableStateFlow<String?>(null)
  val errorText: StateFlow<String?> = _errorText.asStateFlow()

  /** True when the transport is fully connected (mirror of
   *  [JsonRpcClient.connectionState] == Open). */
  val healthOk: StateFlow<Boolean> = rpc.rpcClient.connectionState
    .map { it == ConnectionState.Open }
    .stateIn(scope, SharingStarted.Eagerly, false)

  private val _thinkingLevel = MutableStateFlow("off")
  val thinkingLevel: StateFlow<String> = _thinkingLevel.asStateFlow()

  /**
   * The user's own effort pick for the bound session while the server has not
   * yet confirmed it, else null. `session.info` must not overwrite the flow
   * while it is set — see [setThinkingLevel] / [applyServerEffort]. Cleared on
   * session switch (the new session's own value applies) and when an echo
   * finally carries the picked level.
   *
   * It is the PICK, not a boolean, because "sent" is not the same as "applied":
   * for a fresh session the pick only lands at session.create, and a spawn's
   * session.info re-announces the row's effort. Clearing this on send (the
   * first shape of the fix) is exactly why medium snapped back to high the
   * moment the maintainer hit send.
   *
   * Deliberately a plain var, not a StateFlow: nothing renders it, and the
   * only readers are the pick/echo paths.
   */
  @Volatile
  private var pendingEffortPick: String? = null

  /** Working directory (workspace) the bound session runs in, as reported by
   *  the gateway (`session.info` / `session.resume`.info `cwd`). Rendered as a
   *  subtitle under the chat title so the user can see which project's context
   *  files the agent has loaded. Null = the gateway default cwd (show nothing).
   *  Seeded from the Room row on [load] for an instant cold-open value, then
   *  overwritten by the authoritative server value. Declared here (not next to
   *  [setCurrentModel]) so it's initialized before the constructor-time [load]
   *  runs. */
  private val _currentCwd = MutableStateFlow<String?>(null)
  val currentCwd: StateFlow<String?> = _currentCwd.asStateFlow()

  /** Currently-selected model id (a plain harness model id from
   *  [models], e.g. "claude-opus-4-8"). null = the harness default.
   *  Set on user pick via [setCurrentModel], seeded from the Room row's
   *  `model` mirror on [load], overwritten by session.info. Declared here
   *  (same constraint as [_currentCwd]) so it's initialized before the
   *  constructor-time [load] runs. The fork's "<model> --provider
   *  <provider>" composite is dead — marmaladed has no provider concept. */
  private val _currentModel = MutableStateFlow<String?>(null)
  val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

  /** Count of currently-streaming sessions. Drives the "N runs in
   *  flight" status pill. */
  private val _pendingRunCount = MutableStateFlow(0)
  val pendingRunCount: StateFlow<Int> = _pendingRunCount.asStateFlow()

  /**
   * True between `message.start` and `message.complete` / `error` for the
   * bound session. Distinct from `messages.any { pending }` (which is Room-
   * driven): set IMMEDIATELY on message.start before the pending bubble
   * gets its first delta and is written to Room, so the activity indicator
   * has no "blank window" at the top of a turn. Matches desktop's `busy`
   * flag (apps/desktop/src/app/session/hooks/use-message-stream.ts).
   */
  private val _isStreaming = MutableStateFlow(false)
  val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

  /**
   * True when the gateway is auto-compacting the bound session's history.
   * Set on `status.update {kind: "compacting"}`, cleared on the next
   * `message.start` for the same session. Multi-minute operation —
   * without this signal the activity indicator would say "thinking" and
   * the user wouldn't know why the agent is silent. Matches desktop's
   * `setSessionCompacting` (use-message-stream.ts:1074-1082).
   */
  private val _isCompacting = MutableStateFlow(false)
  val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

  /**
   * Cumulative running total of assistant output tokens, summed across all
   * `message.complete` events for the bound session. Per-turn deltas come
   * from [MessageStream.AssistantFinalized.usage.outputTokens].
   *
   * NOT the server-reported usage snapshot — that's [_sessionUsage]. These
   * are intentionally distinct: this flow is for the Composer's running hint
   * ("Xk out"); [_sessionUsage] reflects whatever the gateway last reported
   * via `session.info`.
   *
   * Reset to null in [load] so the hint tracks the bound session, not the
   * previous one.
   */
  private val _sessionOutputTokensTotal = MutableStateFlow<Long?>(null)
  val sessionOutputTokensTotal: StateFlow<Long?> = _sessionOutputTokensTotal.asStateFlow()

  /**
   * Latest snapshot of `session.info.usage` for the bound session.
   * Spread-merged so per-provider field availability variations don't null
   * out prior counts — only fields present in the incoming payload overwrite
   * stored values.
   *
   * Distinct from [_sessionOutputTokensTotal] (the Composer-facing cumulative
   * accumulator). This flow is a snapshot; consumers who want a running total
   * should read [_sessionOutputTokensTotal] instead.
   *
   * Parity row M2 — mirrors desktop's `setCurrentUsage` spread-merge
   * at use-message-stream.ts:817-819.
   */
  private val _sessionUsage = MutableStateFlow<MessageStream.UsageDelta?>(null)
  val sessionUsage: StateFlow<MessageStream.UsageDelta?> = _sessionUsage.asStateFlow()

  /** Model id reported by the most recent finalized turn — useful as a
   *  cross-check when the picker selection differs from what actually
   *  ran (e.g. provider override on the server). */
  private val _lastTurnModel = MutableStateFlow<String?>(null)
  val lastTurnModel: StateFlow<String?> = _lastTurnModel.asStateFlow()

  /** Cache the bound session's usage snapshot to prefs so the composer context
   *  donut can render on a cold app open (hydrated in [load]). No-op in tests
   *  (no [host]) or when there's nothing to cache. */
  private fun persistBoundUsage() {
    val h = host ?: return
    val usage = _sessionUsage.value ?: return
    val key = _sessionKey.value
    runCatching {
      val encoded = json.encodeToString(MessageStream.UsageDelta.serializer(), usage)
      h.saveSessionUsage(key, encoded)
    }
  }

  /** Restore the cached usage snapshot for [key], or null if none/undecodable. */
  private fun loadPersistedUsage(key: String): MessageStream.UsageDelta? {
    val h = host ?: return null
    return runCatching {
      h.loadSessionUsageJson(key)
        ?.let { json.decodeFromString(MessageStream.UsageDelta.serializer(), it) }
    }.getOrNull()
  }

  /** Interactive prompt cards, extracted to [PromptCenter] (2026-07-17
   *  decomposition). The public API below ([pendingPrompts],
   *  [boundPendingPrompts], the respond/dismiss methods) delegates so
   *  callers and tests are unchanged. */
  private val promptCenter = PromptCenter(
    scope = scope,
    rpc = rpc,
    chatDao = chatDao,
    ioDispatcher = ioDispatcher,
    host = host,
    boundKey = { _sessionKey.value },
    boundSessionId = { _sessionId.value },
    onPromptNotification = onPromptNotification,
    logWarn = logWarn,
    // Lambda, not a direct reference: [_toastMessage] is declared further down
    // this class, so reading it eagerly here would see null.
    notifyUser = { msg -> _toastMessage.tryEmit(msg) },
  )

  /** Live prompt payloads across ALL sessions — see
   *  [PromptCenter.pendingPrompts]. */
  val pendingPrompts: StateFlow<List<PendingPrompt>> get() = promptCenter.pendingPrompts

  /**
   * Prompts scoped to the currently-bound session — what the inline
   * prompt-card stack renders. Pre-fix the UI rendered [pendingPrompts]
   * unfiltered, so a clarify raised in a background session popped its
   * card into whatever chat was open (maintainer, on-device 2026-07-03). The
   * prompt's session is compared by stable LOCAL key, so live-id rotation
   * across reconnects can't orphan or mis-route a card.
   */
  val boundPendingPrompts: StateFlow<List<PendingPrompt>> =
    combine(promptCenter.pendingPrompts, _sessionKey) { prompts, key ->
      prompts.filter { it.sessionKey == key }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

  /**
   * Composer send-queue for the bound session — prompts staged while a turn
   * runs, rendered as editable chips above the composer and drained by the
   * init worker when the session goes idle. Room-persisted (composer_queue),
   * keyed by stable LOCAL key. Desktop analogue: store/composer-queue.ts.
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val boundQueue: StateFlow<List<QueuedPrompt>> = _sessionKey
    .flatMapLatest { key: String -> chatDao.observeQueueForSession(key) }
    .map { rows -> rows.map { it.toQueuedPrompt(json) } }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  /**
   * Per-session `running` flag, keyed by LIVE gateway session_id (the id
   * the gateway stamps on `session.info` events — NOT the stored_session_id
   * / local key). Updated on every `session.info` that carries a `running`
   * boolean, for ANY session (foreground or background).
   *
   * `true`  → a turn is currently in flight in that session.
   * `false` → the session is idle.
   *
   * Entries are kept (never dropped) so downstream consumers can rely on
   * a stable presence of the key once it has been seen at least once.
   * Parity row M3 — mirrors desktop's `setRunning` at
   * use-message-stream.ts:785-814.
   */
  private val _sessionRunning = MutableStateFlow<Map<String, Boolean>>(emptyMap())
  val sessionRunning: StateFlow<Map<String, Boolean>> = _sessionRunning.asStateFlow()

  /**
   * The daemon's model menu (marmaladed `model.list`): the models the
   * harness adapter can run a session on, mapped to picker rows. Empty
   * before the first successful fetch. Refreshed on each transition to
   * [ConnectionState.Open]; callers can force-refresh via [refreshModels].
   */
  private val _models = MutableStateFlow<List<ModelCatalogEntry>>(emptyList())
  val models: StateFlow<List<ModelCatalogEntry>> = _models.asStateFlow()

  /**
   * The daemon's new-session default model (`model.list` `default_model`): the
   * harness model a model-less session.create will be stamped with. Null until
   * the daemon reports one (older daemons never do). Surfaced by the composer's
   * model chip in the "Default" placeholder slot — an adopted session.info
   * model still wins once a session exists.
   */
  private val _defaultModel = MutableStateFlow<String?>(null)
  val defaultModel: StateFlow<String?> = _defaultModel.asStateFlow()

  /**
   * The daemon's new-session default reasoning effort (`model.list`
   * `default_effort`), and the effort vocabulary it validates against
   * (`efforts`). Null / empty on a daemon that advertises neither.
   *
   * The default seeds [thinkingLevel] for sessions that have never been
   * created server-side. Before this, a fresh session showed "Off" — a value
   * the daemon does not even accept — while the daemon quietly stamped its
   * own default on the create. The picker showed a lie (maintainer, 2026-07-25).
   */
  private val _defaultEffort = MutableStateFlow<String?>(null)
  val defaultEffort: StateFlow<String?> = _defaultEffort.asStateFlow()

  private val _efforts = MutableStateFlow<List<String>>(emptyList())
  val efforts: StateFlow<List<String>> = _efforts.asStateFlow()

  // ── Internal state ────────────────────────────────────────────

  /** Stable local key → server-side session_id. Hydrated on Room read
   *  in [load] and updated on every [MarmaladeRpc.sessionCreate] /
   *  [MarmaladeRpc.sessionResume]. */
  private val keyToServerId = ConcurrentHashMap<String, String>()

  /** Hydration job (Room → state, then sessionResume → state). One per
   *  bound session; cancelled by re-load. */
  private var activeHydration: Job? = null

  /** Session-list reconciliation + the optimistic-delete state machine,
   *  extracted to [SessionListSync] (2026-07-17 decomposition). The public
   *  API below ([refreshSessions], [deleteSession], [applyMainSessionKey],
   *  [sessionsSynced]) delegates so callers and tests are unchanged. */
  private val listSync = SessionListSync(
    scope = scope,
    rpc = rpc,
    chatDao = chatDao,
    ioDispatcher = ioDispatcher,
    messageStream = messageStream,
    keyToServerId = keyToServerId,
    boundKey = { _sessionKey.value },
    detachIfBound = { localKey ->
      if (_sessionKey.value == localKey) {
        activeHydration?.cancel()
        messageStream.setActiveSession(null)
        _sessionId.value = null
        _sessionKey.value = "main"
      }
    },
    loadSession = { key -> load(key) },
    deleteSessionRemote = deleteSessionRemote,
    toast = { msg -> _toastMessage.tryEmit(msg) },
    logWarn = logWarn,
  )

  /** True once the first `session.list` round-trip has completed — see
   *  [SessionListSync.sessionsSynced]. */
  val sessionsSynced: StateFlow<Boolean> get() = listSync.sessionsSynced

  /** Controller-level gateway-event dispatch, extracted to [ChatEventRouter]
   *  (2026-07-17 decomposition). State stays owned here — the router mutates
   *  these flows by reference — so the public StateFlow surface is unchanged. */
  private val eventRouter = ChatEventRouter(
    scope = scope,
    rpc = rpc,
    chatDao = chatDao,
    ioDispatcher = ioDispatcher,
    promptCenter = promptCenter,
    listSync = listSync,
    boundKey = { _sessionKey.value },
    boundSessionId = { _sessionId.value },
    isForeground = isForeground,
    errorText = _errorText,
    isStreaming = _isStreaming,
    isCompacting = _isCompacting,
    sessionRunning = _sessionRunning,
    applyServerEffort = { level -> applyServerEffort(level) },
    currentCwd = _currentCwd,
    currentModel = _currentModel,
    sessionUsage = _sessionUsage,
    persistBoundUsage = { persistBoundUsage() },
    markBoundSessionSeen = { markBoundSessionSeen() },
    demoteStreamingRowsForSession = { key -> demoteStreamingRowsForSession(key) },
    logWarn = logWarn,
  )

  // outboundMutex was here pre-Phase-7 to serialize the inline
  // prompt.submit + sendStatus mutation. OutboxDrainer + the per-session
  // PersistenceCoordinator lock own that serialization now.

  /**
   * Every prompt.submit ack the drainer lands, keyed by outbox id — see
   * [PromptAck]. Replay-buffered so a consumer that subscribes just after
   * [sendMessage] returns still sees an ack that raced ahead of it (the
   * drainer can submit within its 50ms debounce window).
   */
  private val _promptAcks = MutableSharedFlow<PromptAck>(replay = 16, extraBufferCapacity = 16)
  val promptAcks: SharedFlow<PromptAck> = _promptAcks.asSharedFlow()

  /**
   * Voice-turn latency hook: while enabled, streaming deltas skip
   * [MessageStream]'s 33 ms render-coalescing window so the speech feeder
   * (which reads the same [messages] StateFlow) sees a sentence boundary the
   * moment its delta arrives. The voice session enables this for the
   * duration of a spoken turn and MUST reset it in a finally.
   */
  fun setImmediateDeltaFlush(enabled: Boolean) {
    messageStream.immediateDeltaFlush = enabled
  }

  // ── init: wire reactive collectors ────────────────────────────

  init {
    // Fan the drainer's per-row ack into promptAcks (tryEmit is thread-safe;
    // the replay buffer makes a lost emission impossible in practice —
    // 16 outstanding un-consumed acks would need 16 concurrent sends).
    outboxDrainer.onAck = { outboxId, ackSeq ->
      _promptAcks.tryEmit(PromptAck(outboxId, ackSeq))
    }

    // Deferred session create (gap triage 2026-07-11): the drainer meets an
    // outbox row with no server id when the user sends the FIRST message of
    // a fresh chat — load() no longer materializes a session on open. Mint
    // it here via the same ensureServerSessionId that owns session.create,
    // the K1 key promotion, and the bound-session StateFlow swap. If the
    // resolved chat is the bound one, bind the id so streaming events route.
    outboxDrainer.resolveSessionId = resolver@{ sessionKey ->
      if (rpc.rpcClient.connectionState.value != ConnectionState.Open) return@resolver null
      val row = withContext(ioDispatcher) { chatDao.getSessionByKey(sessionKey) } ?: return@resolver null
      // Capture BEFORE the call: a K1 rename swaps _sessionKey to the new
      // key, so comparing after would miss the bound session.
      val wasBound = _sessionKey.value == sessionKey
      val sid = ensureServerSessionId(sessionKey, row)
      if (wasBound) {
        _sessionId.value = sid
        messageStream.setActiveSession(sid)
      }
      sid
    }

    // Drain controller-level events (session.info, error, gateway.ready,
    // clarify/approval/secret request) into our state. Message stream
    // events (message.*, tool.*, reasoning.*) are MessageStream's
    // responsibility and bypass us entirely.
    scope.launch {
      rpc.rpcClient.events.collect { event -> eventRouter.handle(event) }
    }

    // Side-effect dispatch for finalized assistant turns. Session-
    // INDEPENDENT by design (the handler resolves the turn's session from
    // the event itself), so it lives for the process — like desktop's
    // single global handleGatewayEvent subscription. It used to be
    // (re)launched per session switch under an activeCollector job, which
    // bred a whole lifecycle-bug class: an untracked second launch leaked
    // one collector per switch (fix a23a87b), and deleteSession cancelled
    // the collector WITHOUT restarting it, silently dropping every
    // cross-session notification until the next navigation.
    scope.launch {
      messageStream.finalizedAssistants.collect { finalized ->
        if (finalized.message.role == ChatRole.Assistant) {
          dispatchAssistantSideEffects(finalized.message, finalized.sessionId)
        }
        // Clear any blocking prompt parked against this session — the turn
        // is over so a half-answered clarify/approval/secret/sudo would
        // otherwise hover indefinitely. Mirrors desktop's
        // `clearAllPrompts(sessionId)` at use-message-stream.ts:885.
        promptCenter.clearForSession(finalized.sessionId)
        // Accumulate token usage for the bound session so the Composer
        // can show a running "Xk out" hint — matches desktop's
        // setCurrentUsage at use-message-stream.ts:899-901. Background
        // sessions don't update the bound counter.
        if (finalized.sessionId == _sessionId.value) {
          finalized.usage?.let { usage ->
            usage.outputTokens?.let { delta ->
              _sessionOutputTokensTotal.update { (it ?: 0L) + delta }
            }
            // Spread-merge into the usage snapshot — on marmaladed this is
            // the ONLY wire path that carries usage (its session.info has
            // none), so the context donut and the settings-sheet breakdown
            // hydrate from here.
            _sessionUsage.update { existing -> existing?.mergedWith(usage) ?: usage }
            persistBoundUsage()
          }
          finalized.model?.let { _lastTurnModel.value = it }
        }
      }
    }

    // Cold-open context seed. The daemon persists per-session occupancy and
    // stamps it on every session.list row; SessionListSync mirrors it into the
    // bound session's Room row. Before this process has seen a live
    // message.complete for that session there is no usage block, so the
    // composer donut had nothing to draw on a cold open — this fills that gap
    // and ONLY that gap: [seedContext] never overwrites a live reading.
    //
    // Reactive rather than a one-shot read in [load] because the session.list
    // refresh that writes those columns can land AFTER the chat binds (first
    // launch, or right after a destructive DB upgrade) — a cold open must not
    // have to wait for the next navigation. distinctUntilChanged sits INSIDE
    // the flatMapLatest so every re-bind re-emits its own session's seed even
    // when two sessions happen to read the same.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    scope.launch {
      _sessionKey
        .flatMapLatest { key ->
          chatDao.observeSessionContext(key)
            .map { contextOccupancy(it?.contextUsed, it?.contextMax) }
            .distinctUntilChanged()
        }
        .collect { seed -> _sessionUsage.update { current -> seedContext(current, seed) } }
    }

    // Streaming-count pill: derive from the bound session's Room messages
    // list having any pending row. [messages] is itself session-scoped
    // (flatMapLatest on _sessionKey), so this collector never needs a
    // per-session lifecycle either.
    scope.launch {
      messages.collect { list ->
        _pendingRunCount.value = if (list.any { it.pending }) 1 else 0
      }
    }

    // Reconnect handler: when the socket re-opens after being away, the
    // server may have restarted. Re-resume the bound session to sync
    // back up, then drain any queued user messages.
    scope.launch {
      var wasOpen = false
      rpc.rpcClient.connectionState.collect { state ->
        when {
          state == ConnectionState.Open && !wasOpen -> {
            wasOpen = true
            onReconnect()
          }
          state != ConnectionState.Open -> wasOpen = false
        }
      }
    }

    // Composer-queue drain: one sequential process-lifetime worker. When the
    // bound session is idle + connected and has queued prompts, hand the head
    // to sendMessage (whose outbox owns wire durability/retry — the queue
    // entry is done once handed over) and delete it. Then wait for the
    // resulting turn to START (or time out) before considering the next
    // entry: draining greedily before message.start arrives would fire
    // several turns into the gateway's 4009 busy-retry loop at once.
    // The condition is re-evaluated from CURRENT state every pass — never
    // edge-gated — so reconnects/rebinds can't strand entries (desktop
    // composer-queue.ts:252-262 records the same lesson).
    scope.launch {
      while (true) {
        val (key, head) = combine(
          boundQueue, isStreaming, healthOk, _sessionKey,
        ) { queue, streaming, connected, key ->
          if (!streaming && connected) queue.firstOrNull()?.let { key to it } else null
        }.filterNotNull().first()
        // flatMapLatest re-subscription lags a session switch by one
        // emission; drop candidates whose key no longer matches.
        if (key != _sessionKey.value) continue
        // JOIN the send before deleting the queue row: sendMessage launches
        // the outbox insert asynchronously and returns immediately, so
        // deleting unconditionally here would open a lose-from-both-tables
        // window if the process died between the queue delete and the outbox
        // insert becoming durable. Awaiting the handle's Job closes that
        // window — the outbox row exists (Room insert done) before the queue
        // row goes. A null handle (empty send — shouldn't happen for a real
        // queued prompt) still deletes, since the entry carries nothing to
        // preserve.
        val sendHandle = sendMessage(
          message = head.text,
          thinkingLevel = head.thinkingLevel,
          attachments = head.attachments,
          voiceOrigin = head.voiceOrigin,
        )
        sendHandle?.job?.join()
        withContext(ioDispatcher) { chatDao.deleteQueuedPrompt(head.id) }
        withTimeoutOrNull(QUEUE_DRAIN_TURN_START_TIMEOUT_MS) { isStreaming.first { it } }
      }
    }
  }

  // ── Lifecycle ─────────────────────────────────────────────────

  /**
   * Bind to [sessionKey]. Cold path: read Room for cached messages and
   * `gatewaySessionId`. If a server-side id is known, [sessionResume];
   * otherwise [sessionCreate] to materialize one.
   *
   * Cancels any in-flight hydration for a previously-bound session before
   * swapping. (The finalized-assistants and pending-run collectors are
   * process-lifetime — see init — and need no per-session teardown.)
   */
  fun load(sessionKey: String) {
    val key = sessionKey.trim().ifEmpty { "main" }
    // Dedupe: when MarmaladeRuntime's connect-open flow + Home's
    // LaunchedEffect both bind the same key (applyMainSessionKey → load,
    // then Home re-calls switchChatSession → load on the SAME key), the
    // second call would cancel the first's in-flight ensureServerSessionId
    // and throw CancellationException up the chain. The downstream catch
    // re-throws cancellation correctly now, but skipping the redundant
    // cancel up front is cleaner — no torn hydration, no churn.
    if (_sessionKey.value == key && activeHydration?.isActive == true) {
      return
    }
    // Reset per-session usage counters so the Composer's "Xk out" hint
    // tracks the session the user is looking at, not the previous one.
    _sessionOutputTokensTotal.value = null
    // Hydrate the usage snapshot from the cold-open cache so the composer
    // context donut renders immediately on app restart (null if this session
    // has no cached snapshot). The next session.info overwrites it.
    //
    // Assigned BEFORE the key swap on purpose: the row-seed collector (init)
    // re-subscribes the moment _sessionKey changes, and assigning afterwards
    // would wipe the seed it had just applied — with no further Room emission
    // to restore it.
    _sessionUsage.value = loadPersistedUsage(key)
    _sessionKey.value = key
    _lastTurnModel.value = null
    _errorText.value = null
    // NOTE: _pendingPrompts is deliberately NOT reset here. Prompts are
    // scoped per session (PendingPrompt.sessionKey) and rendered through
    // boundPendingPrompts — wiping them on every switch made it impossible
    // to navigate INTO a background session and answer its parked clarify.
    // Reset busy flags too — handleEvent() only clears _isStreaming /
    // _isCompacting when the event's sessionId matches the (now-previous)
    // bound session, so switching away from a still-streaming/compacting
    // session left these stuck at true for the newly-bound session, which
    // has no message.start of its own to clear them. Mirrors the reset
    // startFreshSession() already does explicitly (line ~742). If the new
    // session IS actually running, hydrateFromServer's info.running
    // reconciliation (or the next message.start) will flip this back.
    _isStreaming.value = false
    _isCompacting.value = false
    // A pick belongs to the session it was made in; the one being bound gets
    // its own stored/daemon-default value below.
    pendingEffortPick = null

    activeHydration?.cancel()

    activeHydration = scope.launch {
      val session = withContext(ioDispatcher) { ensureSessionRow(key) }
      // The Room-backed messages Flow auto-populates from
      // chatDao.observeMessagesForSession(key) — no need to read once and
      // copy into a StateFlow anymore.
      // Legacy rows hold "off" — a level marmaladed does not accept (this
      // client's picker used to offer it, and the pick was never sent). Treat
      // anything outside the daemon's vocabulary as "unset" and show the
      // daemon default instead of a value no session can actually run at.
      _thinkingLevel.value = session.thinkingLevel
        .takeIf { it in acceptedEfforts() }
        ?: _defaultEffort.value
        ?: session.thinkingLevel
      // Seed the workspace subtitle from the row so it shows instantly on a
      // cold open; the server's session.info / resume value overwrites it.
      _currentCwd.value = session.cwd
      // Seed the composer model chip from the row's mirror — session.list's
      // `model` for materialized sessions, the user's unsent pick for fresh
      // ones (setCurrentModel persists it). Always assigned, even when null,
      // so switching sessions can't leak the previous chip. session.info
      // stays authoritative and overwrites the seed when it arrives.
      _currentModel.value = session.model

      // If the socket isn't open, stop here — we render from cache
      // and resume hydration on reconnect. ensureServerSessionId
      // requires a live transport.
      if (rpc.rpcClient.connectionState.value != ConnectionState.Open) {
        _sessionId.value = session.gatewaySessionId
        messageStream.setActiveSession(session.gatewaySessionId)
        return@launch
      }

      // Create-on-open is dead (gap triage 2026-07-11): a local-only row —
      // a fresh chat nothing has been sent in — must NOT materialize a
      // server session (and spawn a daemon adapter) just by being LOOKED
      // at. The session is created at first send: the outbox drainer's
      // resolveSessionId hook (wired in init) runs ensureServerSessionId
      // when it meets a row with no server id. Server-known rows resume
      // exactly as before.
      if (session.gatewaySessionId == null && keyToServerId[key] == null) {
        _sessionId.value = null
        messageStream.setActiveSession(null)
        return@launch
      }

      val sid = try {
        ensureServerSessionId(key, session)
      } catch (t: kotlinx.coroutines.CancellationException) {
        // A subsequent load() called activeHydration?.cancel() — that's
        // a normal dedupe, not a real bind failure. Surfacing a toast
        // would mislead the user (seen on cold launch when Home's
        // LaunchedEffect re-binds the same key the runtime just bound
        // via applyMainSessionKey).
        throw t
      } catch (t: Throwable) {
        _errorText.value = "Could not bind session: ${t.message ?: t.javaClass.simpleName}"
        null
      }
      _sessionId.value = sid
      // K1: ensureServerSessionId may have renamed `key` to the server's
      // stored_session_id. Re-read _sessionKey.value so subsequent calls
      // hydrate / collect against the canonical key, not the stale one.
      val canonicalKey = _sessionKey.value
      if (sid != null) {
        hydrateFromServer(canonicalKey, sid)
        // Route MessageStream's unstamped-event handling at the id
        // hydrateFromServer ADOPTED (resume rotates live ids), not the
        // pre-resume placeholder `sid` — the placeholder can be a stored id
        // or a stale live id, and bucketing the focused turn's events under
        // it would leave them unresolvable at flush time.
        messageStream.setActiveSession(_sessionId.value ?: sid)
      }
    }
  }

  /** Re-attach the bound session's event stream (resume + subscribe from
   *  the local seq cursor) — pull-to-refresh / app-foreground hook. */
  fun refresh() {
    val key = _sessionKey.value
    val sid = _sessionId.value ?: return
    scope.launch { hydrateFromServer(key, sid) }
  }

  /**
   * Fetch the daemon's model menu (`model.list`). Stashes the result in
   * [models]; no-ops if the socket isn't open. Auto-called on each
   * reconnect. The daemon returns harness model ids + labels only — no
   * providers/pricing/capabilities (that was the fork's model.options).
   */
  fun refreshModels() {
    if (rpc.rpcClient.connectionState.value != ConnectionState.Open) return
    scope.launch {
      runCatching { rpc.modelList() }
        .onSuccess { r ->
          _models.value = r.models.map { m ->
            ModelCatalogEntry(
              id = m.id,
              name = m.label,
              provider = "",
              effortMin = m.effort_min,
              effortMax = m.effort_max,
            )
          }
          // The transcript's effort.clamped line names the model in human
          // terms, and it is written the instant the event lands — so hand
          // MessageStream the id→label map rather than making it reach back
          // into the catalog it has no reference to.
          messageStream.modelLabels = r.models.associate { it.id to it.label }
          // Daemon-owned new-session defaults (null/empty on older daemons).
          _defaultModel.value = r.default_model
          _defaultEffort.value = r.default_effort
          _efforts.value = r.efforts
          // Seed the composer for a session that has no server row yet: its
          // effort is whatever the daemon will stamp at create, not "off".
          // A materialized session's own effort arrives via session.info and
          // is applied there — never overwritten from here.
          val seed = r.default_effort
          if (seed != null && _sessionId.value == null) _thinkingLevel.value = seed
        }
        .onFailure { logWarn("model.list failed: ${it.message}") }
    }
  }

  /** The effort levels the daemon accepts: its published vocabulary when it
   *  has one, else the list this client shipped with. Guards the create-time
   *  override so a stale local pick (Room rows written before 2026-07-25 hold
   *  "off") can never turn a session.create into an InvalidParams rejection. */
  private fun acceptedEfforts(): List<String> =
    _efforts.value.ifEmpty { app.marmalade.android.rpc.types.EFFORT_LEVELS }

  /** Refresh the sidebar list from the server — see [SessionListSync.refresh]
   *  for the reconciliation/prune semantics. */
  fun refreshSessions(limit: Int? = null, prune: Boolean = true) =
    listSync.refresh(limit, prune)

  /** Switch focus without disconnecting. Persists the current composer
   *  draft and then [load]s the new key. */
  fun switchSession(sessionKey: String) {
    load(sessionKey)
  }

  // ── Slash-command helpers ─────────────────────────────────────
  //
  // Only /title survives the marmaladed flip (the fork-RPC slash family —
  // save/undo/compress/steer/status/… — was removed with the gap triage,
  // 2026-07-11; SlashCommandDispatcher marks them Unavailable). Outcomes
  // surface via [_toastMessage] (transient snackbar).

  /** Snackbar messages emitted by slash-command handlers. Subscribers
   *  (typically ChatScreen) show them transiently. SharedFlow with
   *  replay=0 — late subscribers don't see past events. */
  private val _toastMessage = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
  val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

  private fun launchSlash(needsSid: Boolean = true, action: suspend (sid: String?) -> Unit): Job {
    val sid = _sessionId.value
    if (needsSid && sid.isNullOrBlank()) {
      scope.launch { _toastMessage.emit("No active session — connect first") }
      return scope.launch { /* no-op */ }
    }
    return scope.launch {
      runCatching { action(sid) }
        .onFailure {
          val msg = it.message ?: it.javaClass.simpleName
          _toastMessage.emit("Failed: $msg")
        }
    }
  }

  /** Rename via session.title + refresh sidebar. Called from /title <name>
   *  (no-args opens a dialog separately). */
  fun renameCurrentSession(newTitle: String) = launchSlash { sid ->
    rpc.sessionTitle(sid!!, newTitle)
    refreshSessions()
    _toastMessage.emit("Renamed to \"$newTitle\"")
  }

  /** Surface a one-off snackbar message from outside ChatController. The
   *  dispatcher uses this to flag missing-args errors before invoking the
   *  RPC. */
  fun emitToast(message: String) {
    scope.launch { _toastMessage.emit(message) }
  }

  /**
   * Start a fresh session without sending a message to the gateway.
   *
   * Mirrors desktop's `startFreshSessionDraft()` — a pure client-side
   * action that clears the composer and starts a new local session key.
   * The server-side session is created lazily on the NEXT user send via
   * the existing [ensureServerSessionId] flow. Called from the Composer
   * when the user submits `/new` or `/clear`.
   *
   * Steps:
   *  1. Cancel any in-flight outbox drain for the current session — the
   *     outbox rows stay in Room (they belong to the old key), but we
   *     stop the drainer from picking them up on behalf of a session the
   *     user explicitly left.
   *  2. Build a new local key using the same `chat-yyyyMMdd-HHmmss`
   *     pattern that [SessionListViewModel.createSession] uses (K1 renames
   *     it to the server's stored_session_id on first send).
   *  3. Null out the server-side session id and remove the old key from
   *     the in-memory map so [ensureServerSessionId] will call
   *     `session.create` on the next prompt.
   *  4. Clear draft, pending prompts, and abort any active stream.
   *  5. [load] the new key — switches Room observers and resets all
   *     per-session state flows.
   *
   * NOT called: `sessionCreate` — that fires on the next user send.
   * Other slash commands (/undo, /retry, /branch) require RPC support
   * that doesn't exist yet; they're handled server-side once implemented.
   */
  fun startFreshSession() {
    // Cancel in-flight streaming so the activity indicator doesn't linger.
    _isStreaming.value = false
    _isCompacting.value = false
    // Abort the active server turn — no-op if nothing is in flight.
    val oldSid = _sessionId.value
    if (oldSid != null && rpc.rpcClient.connectionState.value == ConnectionState.Open) {
      scope.launch {
        runCatching { rpc.sessionInterrupt(oldSid) }
          .onFailure { logWarn("startFreshSession: session.interrupt failed: ${it.message}") }
      }
    }
    // Remove the old key from the mapping so ensureServerSessionId calls
    // session.create on the next prompt.
    keyToServerId.remove(_sessionKey.value)

    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val newKey = "chat-$ts"

    // (No assistant-pin follow-up: Home is now ALWAYS the daemon-managed main
    // session, resolved from session.main — there is no user-set "assistant
    // session" to keep in sync. The main session itself never routes through
    // startFreshSession — its "Clear" runs session.clear in place.)

    // Clear the draft before load() so the new composer state is clean.
    // Pending prompts are NOT wiped — they're scoped per session now and a
    // brand-new session has none; wiping would kill parked background
    // prompts the user still needs to answer.
    setDraft("")

    // load() handles: setting _sessionKey, nulling _sessionId, resetting
    // usage counters, cancelling activeHydration/activeCollector, and
    // creating the Room session row.
    load(newKey)
  }

  /**
   * Send a user prompt. Local user bubble appears immediately; network
   * failure parks the message in the Room queue
   * ([MessageEntity.sendStatus] = `"queued"`), to be drained on the
   * next reconnect.
   *
   * @param truncateBeforeUserOrdinal when non-null, supports the
   * rewind / "Edit & resend" UX: the server drops every history entry
   * from the n-th user message onwards before appending [message],
   * producing a new branch sibling of whatever was at that ordinal.
   * UI computes the ordinal (count of user messages up to and including
   * the one being edited). Maps directly to the `truncate_before_user_ordinal`
   * param on `prompt.submit`.
   */
  /**
   * @return a [SendHandle] carrying the outbox id (correlate with
   * [promptAcks] to learn the submit's server-minted seq — the voice popup's
   * reply-harvest floor) and the [Job] running the outbox insert (and
   * per-session bookkeeping), or null when nothing was launched (empty
   * send). Fire-and-forget for UI callers — they ignore it. The
   * composer-queue drain worker joins the job so the queue row is deleted
   * only AFTER the outbox row is durably persisted (no lose-from-both-tables
   * window if the process dies mid-hand-off).
   */
  fun sendMessage(
    message: String,
    thinkingLevel: String,
    attachments: List<OutgoingAttachment> = emptyList(),
    voiceOrigin: Boolean = false,
    truncateBeforeUserOrdinal: Int? = null,
  ): SendHandle? {
    val key = _sessionKey.value
    val text = message.trim()
    if (text.isEmpty() && attachments.isEmpty()) return null

    val outboxId = "outbox-${UUID.randomUUID()}"
    val now = System.currentTimeMillis()
    val sid = _sessionId.value

    // Build a ChatMessage for the outbox-row contentJson (we don't store
    // the bubble in any in-memory flow — the Room outbox observe-Flow is
    // what the UI sees). Attachments render as Image/File parts from their
    // staged local files; the drainer uploads them and rewrites the text
    // part with the resolved refs right before prompt.submit (so the acked
    // row content-matches server history — see OutboxDrainer).
    val bubbleParts = buildList {
      if (text.isNotEmpty()) add(ChatMessagePart.Text(text))
      attachments.forEach { att ->
        when (att.kind) {
          OutgoingAttachment.KIND_IMAGE ->
            add(ChatMessagePart.Image(image = "file://${att.path}"))
          else ->
            add(ChatMessagePart.File(name = att.name, source = att.path, mimeType = att.mimeType))
        }
      }
    }
    val localBubble = ChatMessage(
      id = outboxId,
      role = ChatRole.User,
      parts = bubbleParts,
      timestamp = now,
      sendStatus = "pending",
      voiceOrigin = voiceOrigin,
    )
    val attachmentsJson = attachments.takeIf { it.isNotEmpty() }?.let {
      json.encodeToString(ListSerializer(OutgoingAttachment.serializer()), it)
    }
    // NOTE: the send does NOT touch [thinkingLevel] (the flow) or the session
    // row's level. [setThinkingLevel] owns both and pushes `session.effort`, so
    // a send that re-asserted its argument could only ever regress them — a
    // prompt queued at medium and drained after a switch to high used to do
    // exactly that. The level still rides the outbox row as turn metadata.

    val job = scope.launch {
      // The outbox insert comes FIRST — it renders the user's bubble (the
      // UI observes the Room outbox flow), so nothing slower than a Room
      // write may precede it. Pre-fix, a config.set network round-trip ran
      // before the insert and the bubble appeared 1-2s after the input box
      // cleared (maintainer, on-device 2026-07-01). ensureSessionRow stays ahead
      // of the insert only because outbox.sessionKey FKs sessions.key —
      // it's a local read (+ insert on first use), not a network call.
      //
      // Enqueue under the same per-session mutex MessageStream uses so
      // clientOrdinal stays monotonic across concurrent writers (per
      // invariant I4 in ratified-plan.md).
      withContext(ioDispatcher) {
        ensureSessionRow(key)
        messageStream.persistence.lockFor(key).withLock {
          // Branch-resend: drop local messages at or beyond the user
          // ordinal being branched from BEFORE inserting the new outbox
          // row, so the local view tracks the server's truncate
          // immediately instead of waiting for reconcileHistory's prune
          // grace window to expire.
          if (truncateBeforeUserOrdinal != null) {
            // Convert user-ordinal (1-based user-message index) to
            // clientOrdinal. The user message at position N has its own
            // clientOrdinal; we want everything at-or-after that row plus
            // every later message. Look it up directly.
            val pivotOrdinal = chatDao.getUserOrdinalClientOrdinal(
              sessionKey = key,
              userOrdinal = truncateBeforeUserOrdinal,
            )
            if (pivotOrdinal != null) {
              chatDao.deleteMessagesFromOrdinal(key, pivotOrdinal)
            }
          }
          val maxOutbox = chatDao.getMaxOutboxOrdinal(key) ?: 0L
          val maxMessages = chatDao.getMaxMessagesOrdinal(key) ?: 0L
          val nextOrdinal = kotlin.math.max(maxOutbox, maxMessages) + 1L
          chatDao.insertOutbox(
            OutboxEntity(
              id = outboxId,
              sessionKey = key,
              serverSessionId = sid,
              contentJson = localBubble.toMessageEntity(sessionKey = key, json = json).contentJson,
              attachmentsJson = attachmentsJson,
              thinkingLevel = thinkingLevel,
              truncateBeforeUserOrdinal = truncateBeforeUserOrdinal,
              voiceOrigin = voiceOrigin,
              status = "pending",
              createdAtMs = now,
              clientOrdinal = nextOrdinal,
            ),
          )
          // Bump the session's lastMessageAt right when the user sends,
          // not just when the assistant's reply finalizes. The Sessions
          // tab sorts by lastMessageAt DESC; pre-fix the active session
          // would sink in the list while the user waited for a reply.
          chatDao.updateSessionLastMessage(key, now)
        }
      }

      // Wake the drainer so the send fires inside the next debounce
      // window (~50ms) rather than waiting for the 5-second backoff tick.
      outboxDrainer.poke()

      // NOTE: no effort push here. The level reaches the daemon at pick time
      // ([setThinkingLevel] → `session.effort`, or the `session.create`
      // param for a session that doesn't exist yet), so a send has nothing
      // left to say about it.
    }
    return SendHandle(outboxId = outboxId, job = job)
  }

  // ── Composer send-queue API ───────────────────────────────────

  /**
   * Stage a prompt to send when the bound session's current turn ends
   * (the composer's send button while running). NOT a send: the entry
   * stays editable/removable until the init drain worker hands it to
   * [sendMessage] on the idle edge.
   */
  fun enqueuePrompt(
    message: String,
    thinkingLevel: String,
    attachments: List<OutgoingAttachment> = emptyList(),
    voiceOrigin: Boolean = false,
  ) {
    val key = _sessionKey.value
    val text = message.trim()
    if (text.isEmpty() && attachments.isEmpty()) return
    val attachmentsJson = attachments.takeIf { it.isNotEmpty() }?.let {
      json.encodeToString(ListSerializer(OutgoingAttachment.serializer()), it)
    }
    scope.launch {
      withContext(ioDispatcher) {
        ensureSessionRow(key)
        val next = (chatDao.getMaxQueueOrdinal(key) ?: 0L) + 1L
        chatDao.insertQueuedPrompt(
          QueuedPromptEntity(
            id = "queued-${UUID.randomUUID()}",
            sessionKey = key,
            text = text,
            attachmentsJson = attachmentsJson,
            thinkingLevel = thinkingLevel,
            voiceOrigin = voiceOrigin,
            createdAtMs = System.currentTimeMillis(),
            ordinal = next,
          ),
        )
      }
    }
  }

  /** Remove a queued prompt without sending (the chip's delete / the start
   *  of an edit — editing loads the entry into the composer and deletes the
   *  row; saving re-queues through the normal path). */
  fun removeQueued(id: String) {
    scope.launch { withContext(ioDispatcher) { chatDao.deleteQueuedPrompt(id) } }
  }

  /**
   * "Send now" on a queued chip: promote the entry to the head of the
   * queue and, if a turn is in flight, interrupt it so the idle-edge drain
   * fires this entry next. The abort here is an explicit user tap (desktop
   * parity: composer/index.tsx sendQueuedNow) — not an implicit teardown.
   */
  fun sendQueuedNow(id: String) {
    val key = _sessionKey.value
    scope.launch {
      withContext(ioDispatcher) {
        val min = chatDao.getMinQueueOrdinal(key) ?: 0L
        chatDao.setQueueOrdinal(id, min - 1L)
      }
      if (_isStreaming.value) abort()
    }
  }

  /**
   * Branch the current session (T2 #3). The ONLY branch path: `session.fork`
   * — a harness-native branch that carries FULL context (tool calls +
   * reasoning), cut at [atMessageId] (an ASSISTANT message) or, when null, at
   * the session's end. On success the fork is bound + opened and its soft
   * warning (e.g. file-history not copied) surfaced.
   *
   * A no-fork harness (error.data.reason = "fork_unsupported") gets an honest
   * "branching unavailable" toast — deliberately NO seed-create fallback: the
   * daemon ignores session.create's `messages` seed (router.ts createSession
   * never reads it), so a seeded "branch" would be an EMPTY session sold as a
   * degraded one (2026-07-18 review; the old branchInNewChat fallback was
   * removed for exactly this — recover it from git if the daemon ever
   * implements seeding). On marmalade's Claude harness forks always succeed.
   */
  fun branchSession(atMessageId: String?) {
    if (_isStreaming.value) {
      scope.launch { _toastMessage.emit("Wait for the current turn to finish") }
      return
    }
    if (rpc.rpcClient.connectionState.value != ConnectionState.Open) {
      scope.launch { _toastMessage.emit("Not connected") }
      return
    }
    val sourceId = _sessionId.value
    if (sourceId.isNullOrBlank()) {
      // No server session yet (deferred create, no turn run) — nothing with
      // harness state to fork.
      scope.launch { _toastMessage.emit("Send a message first, then branch") }
      return
    }
    val sourceKey = _sessionKey.value
    val sourceName = sessions.value.firstOrNull { it.key == sourceKey }?.displayName
    // One computed name: rides session.fork's title AND the local row's
    // displayName so the two can't drift.
    val forkName = sourceName?.let { "$it (fork)" } ?: "Fork"
    val parentCwd = _currentCwd.value
    scope.launch {
      runCatching {
        rpc.sessionFork(
          sessionId = sourceId,
          atMessageId = atMessageId,
          title = forkName,
        )
      }.onSuccess { forked ->
        bindForkedSession(forked, forkName, parentCwd)
        _toastMessage.emit(
          forked.warning?.let { "Branched into a new chat — $it" } ?: "Branched into a new chat",
        )
      }.onFailure { err ->
        if (isNoForkError(err)) {
          logWarn("session.fork unsupported by harness — branching unavailable")
          _toastMessage.emit(
            "This harness can't branch chats — tool calls and reasoning can't carry over, so branching is unavailable here.",
          )
        } else {
          logWarn("branchSession failed: ${err.message}")
          _toastMessage.emit("Branch failed: ${err.message}")
        }
      }
    }
  }

  /**
   * Steer the running turn (session.steer, T2 #6): inject a mid-turn guidance
   * message the harness merges into the in-flight agent loop. The daemon
   * rejects unless runState=running, so the composer only offers this while
   * running. Bypasses the durable outbox — steering is a connected,
   * in-the-moment action — and renders the user's bubble from the ack, marked
   * `steered` (on the sender the daemon withholds message.user, so we insert
   * the row here; a later replay dedups by the same server id).
   */
  fun steer(message: String, voiceOrigin: Boolean = false, onAccepted: () -> Unit = {}) {
    val text = message.trim()
    if (text.isEmpty()) return
    val sid = _sessionId.value
    if (sid.isNullOrBlank()) {
      scope.launch { _toastMessage.emit("Send a message first, then steer") }
      return
    }
    if (rpc.rpcClient.connectionState.value != ConnectionState.Open) {
      scope.launch { _toastMessage.emit("Not connected") }
      return
    }
    val key = _sessionKey.value
    scope.launch {
      runCatching {
        rpc.sessionSteer(sid, text, source = if (voiceOrigin) "voice" else null)
      }.onSuccess { ackOrNull ->
        // Accepted — clear the composer now (the caller keeps the draft on a
        // reject, e.g. the turn finished between the button showing and the
        // tap: the daemon rejects a steer when run_state != running).
        onAccepted()
        val ack = ackOrNull ?: return@onSuccess
        val mid = ack.message_id ?: return@onSuccess
        withContext(ioDispatcher) {
          if (!chatDao.messageExists(mid)) {
            val localKey = chatDao.resolveLocalKeyForGatewayId(sid) ?: key
            chatDao.insertMessage(
              ChatMessage.user(id = mid, text = text, timestamp = ack.ts ?: System.currentTimeMillis())
                .copy(seq = ack.seq ?: 0L, steered = true, voiceOrigin = voiceOrigin)
                .toMessageEntity(sessionKey = localKey, json = json),
            )
          }
        }
      }.onFailure { err ->
        logWarn("steer failed: ${err.message}")
        _toastMessage.emit("Couldn't steer: ${err.message}")
      }
    }
  }

  /**
   * Trigger a manual context compaction (session.compact, T2 #11a). The daemon
   * rejects mid-turn or when the harness has no compact seam; progress shows
   * via the "compacting…" chip (session.compaction events).
   */
  fun compact() {
    val sid = _sessionId.value
    if (sid.isNullOrBlank()) {
      scope.launch { _toastMessage.emit("Nothing to compact yet") }
      return
    }
    if (_isStreaming.value) {
      scope.launch { _toastMessage.emit("Wait for the current turn to finish") }
      return
    }
    scope.launch {
      runCatching { rpc.sessionCompact(sid) }
        .onSuccess { _toastMessage.emit("Compacting context…") }
        .onFailure { err ->
          logWarn("compact failed: ${err.message}")
          _toastMessage.emit("Couldn't compact: ${err.message}")
        }
    }
  }

  /**
   * Undo the last completed turn (session.undo, T2 #6). The popped bubbles drop
   * live off the session.undone event (ChatEventRouter); this only surfaces the
   * notice. v1 rewinds the CONVERSATION — file edits made during the popped
   * turn are NOT reverted (files_rewound=false). The caller gates the
   * affordance on the "undo" server feature.
   */
  fun undo() {
    val sid = _sessionId.value
    if (sid.isNullOrBlank()) {
      scope.launch { _toastMessage.emit("Nothing to undo yet") }
      return
    }
    if (_isStreaming.value) {
      scope.launch { _toastMessage.emit("Wait for the current turn to finish") }
      return
    }
    scope.launch {
      runCatching { rpc.sessionUndo(sid) }
        .onSuccess { res ->
          _toastMessage.emit(
            if (res.files_rewound) "Last turn undone."
            else "Last turn undone — file edits from that turn were NOT reverted.",
          )
        }
        .onFailure { err ->
          logWarn("undo failed: ${err.message}")
          _toastMessage.emit("Couldn't undo: ${err.message}")
        }
    }
  }

  /** Persist + open a freshly forked session under its own immutable id.
   *  branchedFromId is stamped locally so the lineage chip shows immediately,
   *  before the next session.list refresh confirms it (server truth). */
  private suspend fun bindForkedSession(
    forked: SessionForkResponse,
    forkName: String,
    parentCwd: String?,
  ) {
    val newKey = forked.session_id
    val now = System.currentTimeMillis()
    withContext(ioDispatcher) {
      chatDao.insertSession(
        SessionEntity(
          key = newKey,
          displayName = forkName,
          createdAt = now,
          updatedAt = now,
          gatewaySessionId = forked.session_id,
          thinkingLevel = _thinkingLevel.value,
          cwd = parentCwd?.takeIf { it.isNotBlank() },
          branchedFromId = forked.forked_from.session_id,
        ),
      )
    }
    keyToServerId[newKey] = forked.session_id
    load(newKey)
  }

  /** The daemon's no-fork-harness rejection carries the STRUCTURED
   *  `error.data.reason = "fork_unsupported"` (protocol FORK_UNSUPPORTED_REASON,
   *  2026-07-18) — branch on that, never on the human message, which is free
   *  to reword. The substring check remains as a fallback for a daemon
   *  predating the structured reason. Other fork rejections (turn in flight,
   *  bad cut message) carry neither and surface as plain errors. */
  // Public, not `internal`: exercised by an `:app` unit test, and `:app`'s test
  // compilation is a friend of `:app`'s main compilation only — never of
  // `:shared`'s. See f142ad9 ("the internal trap").
  fun isNoForkError(err: Throwable): Boolean {
    val reason = (err as? JsonRpcException)
      ?.rpcError?.data?.let { it as? JsonObject }
      ?.get("reason")?.let { it as? JsonPrimitive }?.contentOrNull
    if (reason == FORK_UNSUPPORTED_REASON) return true
    return err.message?.contains("cannot fork", ignoreCase = true) == true
  }

  /** Record a model choice (composer chip state) and apply it on the wire.
   *  A MATERIALIZED session (has a server id) switches model in place via
   *  `session.model` — the daemon restarts the idle child so the change
   *  applies now (context carries over via harness resume); this is the path
   *  the main session and every existing session use. A not-yet-created
   *  session has no server row, so the pick only rides the next
   *  `session.create` `model` param (persisted below). session.model rejects
   *  while a turn is in flight — surfaced via logWarn, not silently dropped. */
  fun setCurrentModel(modelId: String) {
    val previous = _currentModel.value
    _currentModel.value = modelId
    // Persist to the session row (draft idiom) so an unsent pick on a
    // fresh session survives a switch-away-and-back — load() re-seeds the
    // chip from the row's `model` on every bind.
    val key = _sessionKey.value
    scope.launch(ioDispatcher) { chatDao.updateSessionModel(key, modelId) }
    val sid = _sessionId.value
    if (sid != null && rpc.rpcClient.connectionState.value == ConnectionState.Open) {
      scope.launch {
        runCatching { rpc.sessionModel(sid, modelId) }
          // Only after the model actually took: the snap below pushes
          // `session.effort`, and the daemon restarts the child on each of the
          // two calls — ordering them keeps the effort write from landing
          // against the OLD model's bounds.
          .onSuccess { snapThinkingToModelBounds(modelId) }
          .onFailure {
            // The daemon rejects a mid-turn model change (router.ts) — no
            // session.info follows, so revert the optimistic chip + row
            // instead of leaving it claiming a model that never took.
            logWarn("session.model failed: ${it.message}")
            _currentModel.value = previous
            withContext(ioDispatcher) { chatDao.updateSessionModel(key, previous) }
            emitToast("Couldn't switch model: ${it.message ?: "the session has a turn in flight"}")
          }
      }
    } else {
      // Fresh (or offline) session: no server row to reorder against, so the
      // snap is just the draft pick moving. session.create carries it.
      snapThinkingToModelBounds(modelId)
    }
  }

  /**
   * Move the composer's thinking level into the newly-selected model's effort
   * bounds (`model.list` effort_min/effort_max, 2026-07-27).
   *
   * The daemon does NOT re-clamp a session's stored effort on `session.model`
   * (router.ts sets the model and restarts the child; the row keeps its effort),
   * so without this the session would keep running at a level the new model's
   * bounds forbid — and the chip would be telling the truth about a setting the
   * next turn ignores. Routing through [setThinkingLevel] means the snap takes
   * the same wire path as a manual pick.
   *
   * Deliberately silent: the chip just changes. The maintainer rejected the busier
   * banner/toast treatment (design lab E2, 2026-07-27) — the durable
   * `effort.clamped` transcript line is where a clamp gets said out loud, and
   * that one comes from the daemon at the seam that actually clamped.
   *
   * No-ops on an unbounded model, an unknown model, and a level the vocabulary
   * doesn't carry — i.e. on every older daemon.
   */
  private fun snapThinkingToModelBounds(modelId: String) {
    val entry = _models.value.firstOrNull { it.id == modelId } ?: return
    if (entry.effortMin == null && entry.effortMax == null) return
    val snapped = snapEffortToBounds(
      effort = _thinkingLevel.value,
      levels = acceptedEfforts(),
      min = entry.effortMin,
      max = entry.effortMax,
    )
    if (snapped != _thinkingLevel.value) setThinkingLevel(snapped)
  }

  /** Reset the bound session's conversation IN PLACE via `session.clear`
   *  (marmaladed) — same session id, messages/transcript wiped server-side.
   *  This is how the main session, which cannot be deleted, starts over
   *  (non-main sessions still use [startFreshSession] to spin up a NEW chat).
   *  The local Room rows drop off the transient `session.cleared` event
   *  ([ChatEventRouter]), not here — so a cross-device clear empties every
   *  client identically. The daemon rejects (thrown) while a turn is in
   *  flight; the caller's confirm dialog gates the tap. */
  fun clearConversation() {
    val sid = _sessionId.value
    if (sid == null) {
      emitToast("No active session to clear")
      return
    }
    if (rpc.rpcClient.connectionState.value != ConnectionState.Open) {
      emitToast("Can't clear while offline")
      return
    }
    // No optimistic streaming/compacting reset: the daemon rejects a mid-turn
    // clear (router.ts), and the authoritative session.cleared event resets
    // those flags AND empties the local rows when the clear actually lands.
    // Surface a rejection instead of swallowing it into a log line.
    scope.launch {
      runCatching { rpc.sessionClear(sid) }
        .onFailure {
          logWarn("session.clear failed: ${it.message}")
          emitToast("Couldn't clear: ${it.message ?: "the session has a turn in flight"}")
        }
    }
  }

  /** Reset the pick to the harness default (the picker's "Default" row).
   *  Null means session.create omits the `model` param entirely —
   *  MarmaladeRpc.sessionCreate only sends it when non-blank — so the
   *  daemon's own default wins. Persisted like [setCurrentModel] so the
   *  cleared state survives a switch-away-and-back too. */
  fun clearCurrentModel() {
    _currentModel.value = null
    val key = _sessionKey.value
    scope.launch(ioDispatcher) { chatDao.updateSessionModel(key, null) }
  }

  /**
   * Record a reasoning-effort choice and apply it on the wire — the exact shape
   * of [setCurrentModel], because the daemon now exposes the exact twin of
   * `session.model`. A MATERIALIZED session changes effort in place via
   * `session.effort` (the daemon stores it and restarts the idle child, so the
   * NEXT turn really runs at it); a not-yet-created session has no server row,
   * so the pick only rides the next `session.create` `reasoning_effort` param.
   *
   * This is the second half of the maintainer's 2026-07-25 on-device bug — picking medium
   * snapped back to high on send. The first half was cosmetic ([applyServerEffort]
   * guards the echo), but the pick genuinely never reached the daemon on an
   * existing session: effort was create-only, so every turn kept running at the
   * session row's stored level and `session.info` was right to say "high".
   *
   * The Room write persists the pick so it survives a switch-away-and-back.
   */
  fun setThinkingLevel(level: String) {
    val previous = _thinkingLevel.value
    val sid = _sessionId.value
    val online = rpc.rpcClient.connectionState.value == ConnectionState.Open
    // An EXISTING session's effort is server state. Taking the pick locally
    // while offline would show a level no turn will run at, and the reconnect
    // echo would silently undo it — so refuse it out loud instead. A session
    // that doesn't exist yet has no server state to contradict: its pick is a
    // draft the next session.create carries.
    if (sid != null && !online) {
      emitToast("Can't change thinking while offline")
      return
    }
    _thinkingLevel.value = level
    pendingEffortPick = level
    val key = _sessionKey.value
    scope.launch(ioDispatcher) { chatDao.updateSessionThinkingLevel(key, level) }
    if (sid != null) {
      scope.launch {
        runCatching { rpc.sessionEffort(sid, level) }
          // No clear on success: the ack means the ROW holds the pick, but a
          // session.info that raced the call still carries the old level and
          // can land after it. [applyServerEffort] clears the pick when an
          // echo actually agrees with it.
          .onFailure {
            // The daemon rejects a mid-turn change and any level outside its
            // published vocabulary (router.ts). Nothing follows on the wire, so
            // revert the optimistic chip + row rather than leave the composer
            // claiming an effort no turn will run at.
            logWarn("session.effort failed: ${it.message}")
            pendingEffortPick = null
            _thinkingLevel.value = previous
            withContext(ioDispatcher) { chatDao.updateSessionThinkingLevel(key, previous) }
            emitToast("Couldn't change thinking: ${it.message ?: "the session has a turn in flight"}")
          }
      }
    }
  }

  /**
   * Adopt the session's server-side reasoning effort from `session.info` —
   * unless it contradicts a pick the server hasn't confirmed yet.
   *
   * `session.info` fires for any metadata change (including ones this client
   * caused) and a spawn decorates it with the effort the child was given, so
   * applying it unconditionally reverted the user's pick within a second of the
   * tap. A pick is "confirmed" by the first echo that AGREES with it — the
   * respawn info after `session.effort`, or the first info after
   * `session.create` for a session that didn't exist yet. Not by the RPC ack:
   * an info that raced the call still says the old level and can land after it.
   */
  private fun applyServerEffort(level: String) {
    val pending = pendingEffortPick
    if (pending != null && pending != level) return
    if (pending == level) pendingEffortPick = null
    _thinkingLevel.value = level
  }

  /** Stop the in-flight turn for the bound session. The server emits a
   *  terminal `message.complete` or `error`; we don't do anything
   *  optimistic — the bubble keeps spinning until the wire confirms. */
  fun abort() {
    val sid = _sessionId.value ?: return
    if (rpc.rpcClient.connectionState.value != ConnectionState.Open) return
    // Optimistically finalize on tap — desktop's cancelRun does the same
    // (use-prompt-actions.ts:1369-1399). Without this the activity
    // indicator keeps showing "writing"/"tool:Bash" for the 100s-of-ms
    // (or longer) until the server's message.complete confirms; the user
    // taps Stop and sees no UI change. Server still owns the canonical
    // bubble state; this only quiets the spinner.
    _isStreaming.value = false
    _isCompacting.value = false
    scope.launch {
      runCatching { rpc.sessionInterrupt(sid) }
        .onFailure { logWarn("session.interrupt failed: ${it.message}") }
    }
  }

  /** Delete a session — optimistic local removal + server-side
   *  `session.delete` with rollback; see [SessionListSync.delete]. */
  fun deleteSession(key: String) = listSync.delete(key)

  /** Archive / unarchive a session — optimistic local flip + server-side
   *  `session.archive` with revert-on-reject; see [SessionListSync.archive]. */
  fun archiveSession(key: String, archived: Boolean) = listSync.archive(key, archived)

  /** Bind to an authoritative session id (e.g. `session.most_recent`),
   *  ensuring its Room row first — see [SessionListSync.applyMainSessionKey]. */
  fun applyMainSessionKey(sessionId: String) = listSync.applyMainSessionKey(sessionId)

  fun setDraft(text: String?) {
    val key = _sessionKey.value
    scope.launch(ioDispatcher) {
      chatDao.updateSessionDraft(key, text?.takeIf { it.isNotBlank() })
    }
  }

  suspend fun getDraft(): String? =
    withContext(ioDispatcher) { chatDao.getSessionDraft(_sessionKey.value) }

  // ── Interactive-prompt round-trips (delegated to [PromptCenter]) ──

  /** Answer (or, with both args empty, dismiss) an agent question — see
   *  [PromptCenter.respondClarify]. */
  fun respondClarify(requestId: String, answers: Map<String, String>, response: String? = null) =
    promptCenter.respondClarify(requestId, answers, response)

  /** Answer an approval card — see [PromptCenter.respondApproval]. */
  fun respondApproval(requestId: String, choice: String) = promptCenter.respondApproval(requestId, choice)

  /** Hand a credential to the daemon's keyring — see
   *  [PromptCenter.respondSecret]. The value is never logged or persisted. */
  fun respondSecret(requestId: String, value: String) = promptCenter.respondSecret(requestId, value)

  /** Refuse a secret request. The secret card's X routes HERE, not to
   *  [dismissPrompt]: the agent is parked on the tool call and a local-only
   *  close would hang it until the daemon's 10-minute timeout. */
  fun denySecret(requestId: String) = promptCenter.denySecret(requestId)

  /** Send sudo password back to the gateway. Closes the matching prompt. */
  fun respondSudo(requestId: String, password: String) = promptCenter.respondSudo(requestId, password)

  /** Remove a prompt card locally without responding — see
   *  [PromptCenter.dismiss]. */
  fun dismissPrompt(requestId: String) = promptCenter.dismiss(requestId)

  // ── Hydration helpers ─────────────────────────────────────────

  /**
   * Reconcile client-side "still streaming" state for [key] against server
   * truth: demote any Room row still `isStreaming = true` for the session.
   * Called from two sites when the server reports a session is NOT running:
   * the live `session.info` handler and [hydrateFromServer]'s use of
   * `session.resume`'s `info` block. Both call sites also clear
   * [_isStreaming] directly (synchronously, matching the style of every
   * other `_isStreaming` mutation in this class) before calling this —
   * this helper only owns the suspend/Room half of the reconciliation.
   *
   * Root cause this closes: on `message.start` the client sets
   * `_isStreaming = true` and Room writes an `isStreaming = true` row.
   * Normally `message.complete` / `error` clears both. But if the socket
   * disconnects mid-turn and the turn finishes server-side before the
   * client reconnects, no `message.complete` is ever delivered to this
   * client — `_isStreaming` and the Room row both stay stuck at true
   * forever (the "activity bubble lingers after reply" report). The
   * server's `SessionRuntimeInfo.running` flag is the only signal that
   * can break that deadlock, since it reflects current server state
   * rather than a client-observed transition.
   *
   * This does NOT add a client-side timer or disconnect-driven teardown —
   * it only fires in direct response to an explicit server signal
   * (`running: false` / no inflight turn), per the repo's server-owns-run-
   * lifecycle constraint.
   */
  private suspend fun demoteStreamingRowsForSession(key: String) {
    withContext(ioDispatcher) {
      messageStream.persistence.lockFor(key).withLock {
        chatDao.demoteStreamingMessagesForSession(key)
      }
    }
  }

  private suspend fun ensureSessionRow(key: String): SessionEntity {
    val existing = chatDao.getSessionByKey(key)
    if (existing != null) {
      existing.gatewaySessionId?.let { keyToServerId[key] = it }
      return existing
    }
    val now = System.currentTimeMillis()
    val fresh = SessionEntity(
      key = key,
      displayName = null,
      createdAt = now,
      updatedAt = now,
      gatewaySessionId = null,
      thinkingLevel = "off",
    )
    chatDao.insertSession(fresh)
    return fresh
  }

  /**
   * Resolve the server-side session_id for [key]. If the row's
   * gatewaySessionId is unknown, mint one via `session.create` and
   * persist it. Also handles K1's "rename the client-coined local key to
   * the server's session_id" promotion on first create.
   *
   * After this returns:
   *  - `keyToServerId` has an entry for the canonical key (post-rename,
   *    if the rename happened).
   *  - `_sessionKey.value` reflects the canonical key if the bound
   *    session was the one renamed.
   *  - The session row's `gatewaySessionId` column holds the same id —
   *    it doubles as the "server knows this row" marker for the deferred
   *    first-send create.
   */
  private suspend fun ensureServerSessionId(key: String, session: SessionEntity): String {
    keyToServerId[key]?.let { return it }
    session.gatewaySessionId?.let {
      keyToServerId[key] = it
      return it
    }
    // No cached id — materialize one on the server. Carry the row's picked
    // workspace (if any) so a lazy re-materialization uses the same cwd the
    // user chose at create time rather than the gateway default.
    // Carry the composer's picked model too — this create is the one place
    // a session's model is set (chosen once, re-applied by the daemon on
    // every resume).
    val created = rpc.sessionCreate(
      title = session.displayName,
      cwd = session.cwd,
      model = _currentModel.value,
      // The composer's Thinking pick is a per-session override, and create is
      // the ONE place it can ride (marmaladed has no runtime-config surface;
      // the daemon re-applies the stored effort on every resume). Omitted when
      // it matches the daemon default so the daemon stays the source of truth
      // for an untouched session, and never sent as a level the daemon would
      // reject — pre-fix the picker offered none/minimal, and the whole pick
      // was dropped on the floor anyway.
      reasoningEffort = _thinkingLevel.value
        .takeIf { it != _defaultEffort.value && it in acceptedEfforts() },
    )
    // marmaladed has ONE immutable session id (no live/stored split) —
    // session_id IS the persistent key, so the client-coined "chat-…" local
    // key promotes to it.
    val sid = created.session_id

    // K1: if the server's session_id is distinct from our client-coined
    // local key, promote the row's key to it. The FK CASCADE on messages +
    // outbox follows the rename inside the same transaction. Both the
    // rename and the gatewaySessionId UPDATE commit together so observers
    // never see a half-renamed state.
    if (sid.isNotBlank() && sid != key) {
      withContext(ioDispatcher) {
        // Wrap both writes in the per-session lock used by MessageStream
        // + drainer so concurrent flushes see either the old key or the
        // new key, never mid-rename.
        messageStream.persistence.lockFor(key).withLock {
          val renamed = chatDao.renameSessionKey(oldKey = key, newKey = sid)
          if (renamed > 0) {
            // Set gatewaySessionId on the renamed row (note: read by new
            // key now, post-CASCADE).
            val now = System.currentTimeMillis()
            val current = chatDao.getSessionByKey(sid)
            if (current != null) {
              chatDao.updateSessionRow(current.copy(gatewaySessionId = sid, updatedAt = now))
            }
          }
        }
      }
      // Reflect the rename in in-memory state. keyToServerId migrates
      // from old → new key (a stale entry under the old key would let
      // future ensureServerSessionId callers re-hit the rename branch).
      keyToServerId.remove(key)
      keyToServerId[sid] = sid
      // (No assistant-pin follow-up across the K1 rename: Home is always the
      // daemon main session now — see startFreshSession.)
      // If the bound session was the one renamed, swap the StateFlow
      // so Room observers (messages flow keyed by _sessionKey) re-bind.
      // Downstream callers (hydrateFromServer) read _sessionKey.value, so
      // the swap is enough to keep them aligned.
      if (_sessionKey.value == key) _sessionKey.value = sid
    } else {
      keyToServerId[key] = sid
      withContext(ioDispatcher) {
        // UPDATE — REPLACE here CASCADE-wipes messages and outbox for the
        // session (Reviewer Checkpoint 2 follow-up). session is guaranteed
        // to exist; we just read it via ensureSessionRow earlier.
        chatDao.updateSessionRow(session.copy(gatewaySessionId = sid, updatedAt = System.currentTimeMillis()))
      }
    }
    return sid
  }

  /**
   * Stamp this device's read cursor for the bound session at the highest
   * seq rendered (P4: `session.seen(session_id, seq)` — monotonic
   * server-side), plus an optimistic local merge so the "New" chip clears
   * without waiting for the next list refresh. The seq source is the max of
   * what Room has flushed and what MessageStream has applied in memory —
   * the debounced Room writer can trail the live stream by a beat.
   */
  private fun markBoundSessionSeen() {
    val key = _sessionKey.value
    val sid = _sessionId.value ?: return
    scope.launch {
      val renderedSeq = maxOf(
        withContext(ioDispatcher) { chatDao.getMaxServerSeq(key) },
        messageStream.lastSeq(sid),
      )
      if (renderedSeq <= 0L) return@launch
      runCatching { rpc.sessionSeen(sessionId = sid, seq = renderedSeq) }
        .onFailure { logWarn("session.seen failed: ${it.message}") }
      withContext(ioDispatcher) {
        chatDao.mergeSessionSeqCursors(key, lastSeq = 0L, seenSeq = renderedSeq)
      }
    }
  }

  /**
   * Attach to [sid]'s event stream: `session.resume` (revives an ended
   * harness; attaching to a live one just registers this connection), then
   * `session.subscribe(since_seq = highest serverSeq stored locally)` —
   * the daemon replays every cached event newer than our cursor down the
   * socket (they flow through MessageStream's normal live path; same ids,
   * same seq, no separate history pipeline), then streams live.
   *
   * This replaced the positional HistoryReconstruction + content-based
   * reconcileHistory machinery: with server-minted message_id/seq, dedup is
   * id equality and ordering is seq arithmetic. Under marmaladed the
   * session id is immutable — resume returns the SAME id, so there is no
   * live-id rotation to adopt.
   */
  private suspend fun hydrateFromServer(key: String, sid: String) {
    runCatching { rpc.sessionResume(sessionId = sid) }
      .onFailure { logWarn("session.resume failed for $key (sid=$sid): ${it.message}") }
    val sinceSeq = withContext(ioDispatcher) { chatDao.getMaxServerSeq(key) }
    val sub = runCatching { rpc.sessionSubscribe(sessionId = sid, sinceSeq = sinceSeq) }
      .getOrElse {
        logWarn("session.subscribe failed for $key (sid=$sid): ${it.message}")
        return
      }
    // P2 state off the subscribe response — server truth at attach time.
    // A run that completed while we were away clears the stale streaming
    // bubble here (no client-side timers; explicit server signal only).
    if (_sessionKey.value == key) {
      val running = sub.run_state == "running" || sub.run_state == "starting"
      _isStreaming.value = running
      if (!running) demoteStreamingRowsForSession(key)
    }
    withContext(ioDispatcher) {
      chatDao.updateSessionRunState(key, sub.lifecycle, sub.run_state)
      chatDao.mergeSessionSeqCursors(key, lastSeq = sub.last_seq, seenSeq = 0L)
    }
    // Viewing the session IS seeing it: once the replay has been applied,
    // stamp this device's cursor at the subscribe-time head (foreground +
    // bound only — a background hydrate must not clear another session's
    // "New" chip).
    if (isForeground() && _sessionKey.value == key && sub.last_seq > 0) {
      markBoundSessionSeen()
    }
  }

  /**
   * Side-effect dispatcher for one finalized assistant message:
   * marmalade_action dispatch + session.lastMessageAt bump + cross-session
   * notification. UI rendering is driven by the Room messages Flow and is
   * not involved here. Called from the process-lifetime
   * finalizedAssistants collector in init.
   */
  private fun dispatchAssistantSideEffects(message: ChatMessage, sessionId: String) {
    val text = message.text()

    parseMarmaladeAction(text)?.let { action ->
      onDispatchAction(action)
      logDispatch("dispatched ${action.action}", null)
    }

    scope.launch(ioDispatcher) {
      // Resolve the finalized turn's LOCAL session key from its live id.
      // Pre-fix this used the bound session's key unconditionally, so a
      // background session's finalize bumped the WRONG row's lastMessageAt
      // and handed the bound key to the cross-session notification.
      val resolvedKey = chatDao.resolveLocalKeyForGatewayId(sessionId)
        ?: _sessionKey.value.takeIf { sessionId == _sessionId.value }
      if (resolvedKey == null) {
        logWarn("finalized turn for unknown session id $sessionId — skipping side effects")
        return@launch
      }
      // Bump the session's lastMessageAt so the sidebar sorts correctly. The
      // message-row insert is owned by MessageStream's PersistenceCoordinator.
      chatDao.updateSessionLastMessage(resolvedKey, message.timestamp ?: System.currentTimeMillis())
      if (_sessionId.value != sessionId) {
        onOtherSessionMessage?.invoke(resolvedKey, sessionId, text)
      }
    }
  }

  // ── Reconnect ─────────────────────────────────────────────────

  private suspend fun onReconnect() {
    val key = _sessionKey.value
    logWarn("onReconnect fired for key=$key, _sessionId=${_sessionId.value}, cached=${keyToServerId[key]}")
    // Re-enter via load() so the per-controller activeHydration job slot
    // dedupes any concurrent UI-driven load() (e.g. MarmaladeNavHost's
    // LaunchedEffect when the route param settles after session.most_recent
    // updates _mainSessionKey). Pre-fix this called hydrateFromServer
    // directly, racing the UI's load() and producing a StandaloneCoroutine
    // cancellation that orphaned an in-flight session.resume — visible in
    // logcat as `session.resume failed for ... StandaloneCoroutine was
    // cancelled`, followed by an unnecessary session.create.
    load(key)
    // Refresh the model catalog opportunistically — the UI's model picker
    // sheet reads it; a fresh fetch on every reconnect catches added
    // providers without forcing the user to do anything explicit.
    refreshModels()
    // Queued-message drain is now owned by OutboxDrainer, which subscribed
    // to ConnectionState.Open at MarmaladeRuntime startup and will
    // automatically retry any rows with status='pending'/'failed' here.
  }

  // ── markSendStatus + outboundMutex were here pre-Phase-7; both removed.
  // sendStatus is now an OutboxEntity field, not a MessageEntity field, and
  // it's maintained by the OutboxDrainer's updateOutboxAttempt /
  // markOutboxSending / ackOutboxAsMessage calls. The Room outbox Flow
  // surfaces failures to the UI via the bubble's sendStatus, not via a
  // _messages mutation. (Legacy fields stay on MessageEntity until the
  // Phase 8.2 schema cleanup.)

  // ── Cleanup ───────────────────────────────────────────────────

  fun close() {
    activeHydration?.cancel()
  }

  // ── Activity-pill derivation ──────────────────────────────────

  /**
   * Map the latest part of a still-streaming bubble to a short label the
   * UI shows above the bubble:
   *
   *   - empty `parts` (about to start) → `"starting"`
   *   - last part is a not-yet-completed [ChatMessagePart.ToolCall] → `"tool:<NAME>"`
   *   - last part is a [ChatMessagePart.Reasoning] fragment → `"thinking"`
   *   - last part is text (the model is producing user-visible tokens) → `"writing"`
   *
   * Returns null for finalized bubbles (`pending == false`) — the UI
   * should treat null as "no pill".
   */
  private fun deriveStreamingActivity(message: ChatMessage): String? {
    if (!message.pending) return null
    val last = message.parts.lastOrNull() ?: return "starting"
    return when (last) {
      is ChatMessagePart.ToolCall ->
        if (last.result == null) "tool:${last.toolName}" else "writing"
      is ChatMessagePart.Reasoning -> "thinking"
      is ChatMessagePart.Text -> "writing"
      is ChatMessagePart.Image, is ChatMessagePart.File -> "writing"
    }
  }

  // ── Mapping helpers ───────────────────────────────────────────

  private fun SessionEntity.toEntry(): ChatSessionEntry = ChatSessionEntry(
    key = key,
    updatedAtMs = lastMessageAt ?: updatedAt,
    displayName = displayName,
    source = source,
    cwd = cwd,
    lastSeq = lastSeq,
    seenSeq = seenSeq,
    lifecycle = lifecycle,
    runState = runState,
    branchedFromId = branchedFromId,
    workspaceId = workspaceId,
    isMain = isMain,
    archived = archived,
  )

}

// ── Public types referenced by the controller's API ───────────

/** A pending interactive prompt — drives the inline prompt-card UI
 *  (scoped per session via [sessionKey]) and the session list's
 *  needs-input indicator. */
data class PendingPrompt(
  val requestId: String,
  val kind: PromptKind,
  /** Raw LIVE gateway session id stamped on the event (rotates on every
   *  resume — do NOT use for session correlation; kept for the
   *  finalize-cleanup match against the same turn's live id). */
  val sessionId: String?,
  /** Stable LOCAL session key, resolved once at capture. This is what the
   *  per-session card filter and the session list compare against. */
  val sessionKey: String?,
  val title: String,
  val detail: String?,
  val payload: JsonObject,
)

enum class PromptKind { Clarify, Approval, Secret, Sudo }

/** A composer-queue entry (staged-while-running prompt), decoded from
 *  [QueuedPromptEntity] for the chip panel + drain worker. */
data class QueuedPrompt(
  val id: String,
  val text: String,
  val attachments: List<OutgoingAttachment>,
  val thinkingLevel: String,
  val voiceOrigin: Boolean,
  val queuedAtMs: Long,
)

/** How long the drain worker waits for message.start after handing a queue
 *  entry to sendMessage, before it considers the next entry anyway (server
 *  slow / offline race — the outbox owns the entry's fate either way). */
private const val QUEUE_DRAIN_TURN_START_TIMEOUT_MS = 10_000L

private fun QueuedPromptEntity.toQueuedPrompt(json: Json): QueuedPrompt =
  QueuedPrompt(
    id = id,
    text = text,
    attachments = attachmentsJson?.let {
      runCatching {
        json.decodeFromString(ListSerializer(OutgoingAttachment.serializer()), it)
      }.getOrDefault(emptyList())
    } ?: emptyList(),
    thinkingLevel = thinkingLevel,
    voiceOrigin = voiceOrigin,
    queuedAtMs = createdAtMs,
  )

// ── Internal helpers ──────────────────────────────────────────
