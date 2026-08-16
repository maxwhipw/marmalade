package app.marmalade.android.chat

import app.marmalade.android.chat.messages.MessageStream
import app.marmalade.android.chat.messages.withoutContext
import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.MarmaladeRpc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Controller-level gateway-event routing, extracted from [ChatController]
 * (2026-07-17 decomposition). One `when (event.type)` dispatch for the
 * concerns the controller owns — bound-session streaming flags, run-state
 * tracking, error surface, session.info hydration, background-task lines —
 * delegating prompt cards to [PromptCenter] and cross-device deletes to
 * [SessionListSync]. Message-stream events (message.delta, tool.*,
 * reasoning.*) bypass this entirely; [MessageStream] owns them.
 *
 * State stays OWNED by [ChatController] (the flows are passed by reference)
 * so its public StateFlow surface — and every test against it — is
 * unchanged; this class is the mutation site for event-driven flips.
 */
internal class ChatEventRouter(
  private val scope: CoroutineScope,
  private val rpc: MarmaladeRpc,
  private val chatDao: ChatDao,
  private val ioDispatcher: CoroutineDispatcher,
  private val promptCenter: PromptCenter,
  private val listSync: SessionListSync,
  private val boundKey: () -> String,
  private val boundSessionId: () -> String?,
  /** True while the app is visible — gates the explicit session.seen mark. */
  private val isForeground: () -> Boolean,
  private val errorText: MutableStateFlow<String?>,
  private val isStreaming: MutableStateFlow<Boolean>,
  private val isCompacting: MutableStateFlow<Boolean>,
  private val sessionRunning: MutableStateFlow<Map<String, Boolean>>,
  /** Hand the session's server-side reasoning effort to the controller, which
   *  owns the adopt-vs-keep-the-user's-pick decision (an unconfirmed pick
   *  outranks the echo). See ChatController.applyServerEffort. */
  private val applyServerEffort: (String) -> Unit,
  private val currentCwd: MutableStateFlow<String?>,
  private val currentModel: MutableStateFlow<String?>,
  private val sessionUsage: MutableStateFlow<MessageStream.UsageDelta?>,
  /** Cache the bound session's usage snapshot for cold-open rendering. */
  private val persistBoundUsage: () -> Unit,
  /** Stamp this device's read cursor for the bound session (P4). */
  private val markBoundSessionSeen: () -> Unit,
  /** Demote stale `isStreaming` Room rows when the server says idle. */
  private val demoteStreamingRowsForSession: suspend (key: String) -> Unit,
  private val logWarn: (String) -> Unit,
) {

  fun handle(event: GatewayEvent) {
    when (event.type) {
      "gateway.ready" -> {
        // The session.most_recent + auto-resume is the runtime's job;
        // we just clear the error surface so a previous disconnect
        // doesn't linger.
        errorText.value = null
      }
      "message.start" -> {
        if (event.sessionId == null || event.sessionId == boundSessionId()) {
          isStreaming.value = true
          isCompacting.value = false
        }
      }
      "status.update" -> {
        val payload = event.payload as? JsonObject
        val kind = payload?.stringOrNull("kind")
        if (kind == "compacting" &&
          (event.sessionId == null || event.sessionId == boundSessionId())
        ) {
          isCompacting.value = true
        }
        // P2: the daemon pushes {session_id, lifecycle, run_state} on every
        // runState flip — the live signal behind the "agent is working"
        // indicator, for ALL sessions (background ones drive the sidebar
        // pill). No polling, no client-side timers.
        val runState = payload?.stringOrNull("run_state")
        if (runState != null) {
          val lifecycle = payload.stringOrNull("lifecycle")
          val statusSid = payload.stringOrNull("session_id") ?: event.sessionId ?: boundSessionId()
          if (statusSid != null) {
            val running = runState == "running" || runState == "starting"
            sessionRunning.update { it + (statusSid to running) }
            if (statusSid == boundSessionId()) {
              isStreaming.value = running
              if (!running) isCompacting.value = false
            }
            scope.launch(ioDispatcher) {
              val localKey = chatDao.resolveLocalKeyForGatewayId(statusSid)
                ?: statusSid.takeIf { chatDao.getSessionByKey(it) != null }
              if (localKey != null) {
                chatDao.updateSessionRunState(localKey, lifecycle, runState)
              }
            }
          }
        }
      }
      "message.complete" -> {
        if (event.sessionId == null || event.sessionId == boundSessionId()) {
          isStreaming.value = false
          isCompacting.value = false
          // 4k: the user is looking at this session as the turn finalizes —
          // explicitly mark it seen. The server's implicit touches already
          // cover turns WE submitted; this closes the watching-a-turn-
          // another-client-submitted gap. Foreground-gated: a completion
          // while backgrounded was NOT seen (it gets a notification).
          if (isForeground()) {
            markBoundSessionSeen()
          }
        }
      }
      "error" -> {
        val msg = (event.payload as? JsonObject)?.stringOrNull("message")
          ?: event.payload?.toString().orEmpty().take(160)
        if (msg.isNotBlank()) errorText.value = msg
        if (event.sessionId == null || event.sessionId == boundSessionId()) {
          isStreaming.value = false
          isCompacting.value = false
        }
        // Clear pending prompts for the SESSION that errored, regardless of
        // focus. Pre-fix only cleared for the bound session, which leaked
        // background-session prompts forever (desktop scopes by session_id —
        // use-message-stream.ts:1090-1093).
        promptCenter.clearForSession(event.sessionId ?: boundSessionId())
      }
      "clarify.request" -> promptCenter.capture(event, PromptKind.Clarify)
      "clarify.resolved" -> promptCenter.onClarifyResolved(event)
      "approval.request" -> promptCenter.capture(event, PromptKind.Approval)
      "approval.resolved" -> promptCenter.onApprovalResolved(event)
      "secret.request" -> promptCenter.capture(event, PromptKind.Secret)
      // Transient, outcome-only. Also arrives UNPROMPTED when the daemon
      // denies the request itself (10-min timeout, session stop/delete/error,
      // last secrets-capable client gone) — see [PromptCenter.onSecretResolved].
      "secret.resolved" -> promptCenter.onSecretResolved(event)
      "sudo.request" -> promptCenter.capture(event, PromptKind.Sudo)
      "terminal.read.request" -> {
        // The gateway's read_terminal tool blocks on a respond; this device
        // has no live terminal pane, so always answer with empty text.
        // Mirrors desktop's `text: result ? JSON.stringify(result) : ''`
        // contract (use-message-stream.ts:1059-1073). Without this respond
        // the tool hangs the agent indefinitely.
        val payload = event.payload as? JsonObject
        val requestId = payload?.stringOrNull("request_id") ?: return
        scope.launch {
          runCatching { rpc.terminalReadRespond(requestId = requestId, text = "", sessionId = event.sessionId) }
        }
      }
      "session.deleted" -> {
        // A delete ANOTHER device initiated — drop the local mirror.
        // [SessionListSync.onSessionDeleted] owns the cleanup (and skips
        // sessions whose delete WE initiated, via its tombstones).
        val gone = (event.payload as? JsonObject)?.stringOrNull("session_id")
          ?: event.sessionId ?: return
        listSync.onSessionDeleted(gone)
      }
      "session.compaction" -> {
        // T2 #11a: the marmaladed daemon relays the harness's compaction
        // signals as distinct events (started → completed|failed|boundary).
        // `started` shows the "compacting…" chip; any terminal clears it.
        // Auto-compaction rides the same event for free. (The fork-era
        // status.update{kind:"compacting"} branch above is dead against
        // marmaladed, which emits THIS event instead — left harmless.)
        if (event.sessionId == null || event.sessionId == boundSessionId()) {
          val status = (event.payload as? JsonObject)?.stringOrNull("status")
          isCompacting.value = status == "started"
        }
      }
      "session.undone" -> {
        // T2 #6: the last completed turn was popped in place (session.undo).
        // Drop the popped rows from Room LIVE — the chat view derives from the
        // Room flow, so deleting the rows removes the bubbles. Transient event
        // (never cached); a client that misses it reconciles on its next
        // replay (the daemon already truncated the transcript).
        val popped = (event.payload as? JsonObject)?.get("popped_message_ids") as? JsonArray
        if (!popped.isNullOrEmpty()) {
          scope.launch(ioDispatcher) {
            popped.forEach { el ->
              val id = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
              if (!id.isNullOrEmpty()) {
                runCatching { chatDao.deleteMessage(id) }
                  .onFailure { logWarn("session.undone: delete $id failed — ${it.message}") }
              }
            }
          }
        }
      }
      "session.cleared" -> {
        // The daemon reset a session's conversation IN PLACE (session.clear —
        // how the non-deletable main session starts over): same session_id,
        // messages/transcript wiped server-side. Drop the local Room rows so
        // the chat view (derived from the Room flow) empties, but KEEP the
        // session row itself. Mirrors session.undone's live Room drop.
        //
        // This event is TRANSIENT and subscriber-only: a device that wasn't
        // subscribed when the clear fired (bound elsewhere, or offline) never
        // sees it, and replay only ADDS rows — it can't remove them. That
        // missed case is caught arithmetically by SessionListSync.refresh
        // (server last_seq < local max message seq ⇒ wipe), so a
        // background/cross-device clear reconciles on the next session.list.
        val cleared = (event.payload as? JsonObject)?.stringOrNull("session_id")
          ?: event.sessionId ?: return
        if (event.sessionId == null || event.sessionId == boundSessionId()) {
          isStreaming.value = false
          isCompacting.value = false
          // The window is empty again, so the donut's reading is now a lie.
          // Drop it (keeping the token tallies) and re-cache, mirroring the
          // daemon nulling its two columns on session.clear. Unknown until the
          // next turn — never a stale percentage.
          sessionUsage.update { it?.withoutContext() }
          persistBoundUsage()
        }
        scope.launch(ioDispatcher) {
          val localKey = chatDao.resolveLocalKeyForGatewayId(cleared)
            ?: cleared.takeIf { chatDao.getSessionByKey(it) != null }
            ?: return@launch
          runCatching { chatDao.deleteMessagesForSession(localKey) }
            .onFailure { logWarn("session.cleared: wipe $localKey failed — ${it.message}") }
          // Null the local occupancy mirror too — for ANY session, not just the
          // bound one, since the daemon cleared it server-side. Without this the
          // stale row would immediately re-seed the donut it just went dark on.
          runCatching { chatDao.clearSessionContext(localKey) }
            .onFailure { logWarn("session.cleared: context reset $localKey failed — ${it.message}") }
        }
      }
      "session.info" -> {
        // session.info fires when a session's metadata changes (title
        // rename from another client, model swap, branch change, etc.).
        // Trigger a sessions-list refresh so the Sessions tab and the
        // sidebar pick up the change. Desktop refreshes sessions on every
        // session.info too (apps/desktop/src/.../desktop-controller.tsx).
        //
        // Parity row 2: hydrate model/provider so the composer chip
        // shows the server-side model on resume (4007) or cross-client
        // switch, rather than the last local UI pick.
        // Mirrors use-message-stream.ts:737-744.
        val infoPayload = event.payload as? JsonObject
        val infoSessionId = event.sessionId
        // Apply only to the bound session. Unstamped events (no session_id)
        // are treated as targeting the current session (gateway omits the
        // field for the focused session in single-session connections).
        if (infoSessionId == null || infoSessionId == boundSessionId()) {
          val model = infoPayload?.stringOrNull("model")
          // Adopt the gateway's reasoning_effort as the per-session
          // thinkingLevel. New sessions previously defaulted to "off"
          // ignoring whatever the host runs by default — the maintainer's question
          // 2026-06-30: "Is there a host default?" Yes — session.info
          // carries it. Apply it on every info event so cross-client
          // changes propagate too.
          // ...but a pick the server hasn't confirmed yet wins — see
          // [ChatController.applyServerEffort]. This event fires for any
          // metadata change, including ones this client caused, so applying it
          // unconditionally reverted the user's pick a moment after they made
          // it (maintainer, on-device 2026-07-25).
          val reasoningEffort = infoPayload?.stringOrNull("reasoning_effort")
          if (!reasoningEffort.isNullOrEmpty()) applyServerEffort(reasoningEffort)
          // Workspace subtitle: adopt the gateway's per-session cwd so the top
          // bar reflects the project the agent actually loaded (and picks up a
          // cross-client change).
          val cwd = infoPayload?.stringOrNull("cwd")
          if (!cwd.isNullOrEmpty()) {
            currentCwd.value = cwd
          }
          if (!model.isNullOrEmpty()) {
            // Plain model id (setCurrentModel's format). The fork composed
            // "<model> --provider <provider>" here; marmaladed has no
            // provider concept, so the id stands alone.
            currentModel.value = model
          }
          // Parity row M2: hydrate usage from session.info so the token
          // badge doesn't stay stale when switching sessions. Use spread-
          // merge semantics: only overwrite the fields the new payload
          // carries; preserve fields the provider didn't report.
          // Mirrors desktop's setCurrentUsage at use-message-stream.ts:817-819.
          if (infoPayload != null) {
            val delta = MessageStream.extractUsage(infoPayload)
            if (delta != null) {
              sessionUsage.update { existing -> existing?.mergedWith(delta) ?: delta }
              // Cache the fresh snapshot so the donut survives a cold open.
              persistBoundUsage()
            }
          }
        }
        // Parity row M3: track the per-session running flag for ALL sessions
        // (background sessions included), not just the bound one. The resolved
        // sid is (infoSessionId ?: boundSessionId()) — unstamped events target
        // the current session. Skip when resolution returns null or the payload
        // has no "running" key (guards against events that omit the field).
        val runningSid = infoSessionId ?: boundSessionId()
        if (runningSid != null && infoPayload != null && infoPayload.containsKey("running")) {
          val runningVal = (infoPayload["running"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
          if (runningVal != null) {
            sessionRunning.update { it + (runningSid to runningVal) }
            // Reconcile client-side "still streaming" state against server
            // truth for the BOUND session. No live path clears isStreaming
            // / a Room isStreaming=true row when the server says the run
            // ended while we weren't looking (e.g. message.complete arrived
            // while disconnected) — this is that reconciliation. Desktop
            // derives `busy` directly from session.info.running the same
            // way (use-message-stream.ts:785-814). Only handle the
            // false/idle case here: `true` is already covered by
            // message.start, and flipping true->true is a no-op we don't
            // need to special-case.
            if (!runningVal && runningSid == boundSessionId()) {
              val bound = boundKey()
              scope.launch {
                // Desktop-parity guard (use-message-stream.ts:801-803):
                // while a submitted prompt is awaiting its response, a
                // running=false is stale — the server emits session.info
                // echoes for config.set etc. around submit time, and
                // honoring one here would demote the turn that is about
                // to start (or just started). Outbox rows model exactly
                // that awaiting-response window: they exist from send
                // until the prompt.submit ack.
                val awaitingResponse = withContext(ioDispatcher) {
                  chatDao.getOutboxForSessionOnce(bound).isNotEmpty()
                }
                if (!awaitingResponse) {
                  isStreaming.value = false
                  demoteStreamingRowsForSession(bound)
                }
              }
            }
          }
        }
        scope.launch { listSync.refresh() }
      }
      "background.complete" -> {
        // Desktop's surface: a `sys()` transcript line in the session that
        // owned the background task (use-message-stream.ts ~1090).
        // Android v1: insert a `role="system"` MessageEntity — no notification,
        // no background-task tracker (Android never built one).
        val bgPayload = event.payload as? JsonObject
        val taskId = bgPayload?.stringOrNull("task_id")
        if (taskId.isNullOrEmpty()) {
          logWarn("background.complete: missing task_id — skipping")
          return
        }
        val text = bgPayload.stringOrNull("text") ?: ""
        scope.launch(ioDispatcher) {
          // Resolve session key: stamped event → DAO lookup; unstamped → bound session.
          // Local val: GatewayEvent.sessionId now lives in :shared, and Kotlin can't
          // smart-cast a val property across a module boundary (ADR 0011).
          val eventSessionId = event.sessionId
          val resolvedKey: String? = if (eventSessionId != null) {
            chatDao.resolveLocalKeyForGatewayId(eventSessionId)
          } else {
            boundKey()
          }
          if (resolvedKey == null) {
            logWarn("background.complete: unknown session ${event.sessionId} — skipping")
            return@launch
          }
          val now = System.currentTimeMillis()
          try {
            chatDao.insertMessage(
              MessageEntity(
                id = "bg-$taskId-$now",
                sessionKey = resolvedKey,
                role = "system",
                contentJson = """[{"type":"text","text":"[bg $taskId] $text"}]""",
                timestampMs = now,
                clientOrdinal = 0L,
                isStreaming = false,
              ),
            )
          } catch (t: Throwable) {
            logWarn("background.complete: insert failed for task $taskId — ${t.message}")
          }
        }
      }
      else -> { /* MessageStream handles everything else; UI surfaces unknown */ }
    }
  }
}

private fun JsonObject.stringOrNull(key: String): String? {
  val value = this[key] ?: return null
  val primitive = value as? JsonPrimitive ?: return null
  if (!primitive.isString) return null
  return primitive.content
}
