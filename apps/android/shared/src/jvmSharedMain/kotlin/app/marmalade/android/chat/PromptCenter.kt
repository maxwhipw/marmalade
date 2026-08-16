package app.marmalade.android.chat

import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.MarmaladeRpc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Interactive-prompt state machine, extracted from [ChatController]
 * (2026-07-17 decomposition): holds the live `clarify.request` /
 * `approval.request` / `secret.request` / `sudo.request` cards
 * ([pendingPrompts]), captures them off the event stream ([capture]),
 * answers them ([respondClarify]/[respondApproval]/[respondSecret]/
 * [respondSudo]), and clears them on resolution — locally, cross-device
 * ([onApprovalResolved]), or when a turn/session settles
 * ([clearForSession]).
 *
 * Prompts are parked with their stable LOCAL session key, resolved once at
 * capture (see [capture]) — the per-session card filter and the session
 * list's needs-input indicator compare against it.
 */
internal class PromptCenter(
  private val scope: CoroutineScope,
  private val rpc: MarmaladeRpc,
  private val chatDao: ChatDao,
  private val ioDispatcher: CoroutineDispatcher,
  /** Host platform edges — only [ChatHost.cancelPromptNotification] is used
   *  here. Null (tests) behaves like the old null-Context path: no-op. */
  private val host: ChatHost?,
  /** The currently-bound session key / server id (prompt routing + the
   *  "is this card for a background session?" notification decision). */
  private val boundKey: () -> String,
  private val boundSessionId: () -> String?,
  /** Notify the runtime when a prompt arrives in a session the user is NOT
   *  currently viewing, so an OS notification can be dispatched. */
  private val onPromptNotification: ((sessionKey: String, prompt: PendingPrompt) -> Unit)?,
  private val logWarn: (String) -> Unit,
  /** Transient user-facing message (snackbar). A seam rather than a
   *  ChatController reference: the secret round-trip is the first prompt whose
   *  OUTCOME the user has to be told about — "stored at X" vs "the keyring
   *  refused it" is not visible anywhere else, and a silent failure would look
   *  identical to success. Null (tests that don't assert on it) is a no-op. */
  private val notifyUser: ((String) -> Unit)? = null,
) {

  /** Named `notifyUser`, not `notify`: `Object.notify()` is on every JVM
   *  class and shadowing it with a function-typed member is a trap. */
  private fun notify(message: String) {
    notifyUser?.invoke(message)
  }

  /** Live prompt payloads across ALL sessions (each scoped via
   *  [PendingPrompt.sessionKey]). The session list derives its needs-input
   *  indicator from this; the inline cards render from
   *  [ChatController.boundPendingPrompts]. */
  private val _pendingPrompts = MutableStateFlow<List<PendingPrompt>>(emptyList())
  val pendingPrompts: StateFlow<List<PendingPrompt>> = _pendingPrompts.asStateFlow()

  /** Cancel the OS notification for a card's session (notifications are
   *  keyed by the stable LOCAL key — cancelling with a server id never
   *  matched, leaving stale notifications behind). */
  private fun cancelNotification(sessionKey: String?) {
    sessionKey ?: return
    host?.cancelPromptNotification(sessionKey)
  }

  /** Drop a card locally + cancel its notification. */
  private fun remove(requestId: String) {
    val removed = _pendingPrompts.value.firstOrNull { it.requestId == requestId }
    _pendingPrompts.update { list -> list.filterNot { it.requestId == requestId } }
    cancelNotification(removed?.sessionKey)
  }

  fun capture(event: GatewayEvent, kind: PromptKind) {
    val payload = event.payload as? JsonObject ?: return
    // Only the _block()-family prompts (clarify/secret/sudo) carry a
    // request_id. approval.request payloads never do — approvals are a
    // session-keyed FIFO answered by {choice, session_id} — so synthesize a
    // local card id instead of dropping the event (pre-fix, approval cards
    // never rendered from the real gateway at all).
    val requestId = payload.stringValueOrNull("request_id")
      ?: if (kind == PromptKind.Approval) "approval-${java.util.UUID.randomUUID()}" else return
    // Prompts are parked with their session so a clarify raised in a
    // background session can still be answered (desktop parks by session_id
    // and scopes rendering, use-message-stream.ts:962). Two refinements over
    // the raw event stamp:
    //  - Resolve the id to the stable LOCAL key ONCE, here — a prompt stored
    //    only with a server id a legacy row doesn't know becomes
    //    unmatchable (the card either leaked into whatever chat was open —
    //    The maintainer's misplaced clarify, 2026-07-03 — or could never be found).
    //  - An unstamped event (or an unresolvable id) belongs to the bound
    //    session: pin it to the CURRENT key so a later switch can't drag
    //    the card along.
    scope.launch {
      val resolvedKey = event.sessionId
        ?.let { id -> withContext(ioDispatcher) { chatDao.resolveLocalKeyForGatewayId(id) } }
        ?: boundKey()
      val prompt = PendingPrompt(
        requestId = requestId,
        kind = kind,
        sessionId = event.sessionId,
        sessionKey = resolvedKey,
        // Approval payloads carry {command, description, pattern_key(s),
        // allow_permanent} (tools/approval.py approval_data) — no title/detail
        // keys. The card reads command/allow_permanent from [payload].
        title = payload.stringValueOrNull("title") ?: payload.stringValueOrNull("prompt")
          ?: when (kind) {
            PromptKind.Approval -> "Approval required"
            // Daemon clarify.request payloads carry {request_id, questions[]}
            // — no title key; the card renders the questions from [payload].
            // The count is in the title because the card is a wizard now: it
            // shows one question at a time, so "3 questions" is the only place
            // the size of the ask is visible before you start answering.
            PromptKind.Clarify -> clarifyTitle(parseClarifyQuestions(payload).size)
            else -> kind.name
          },
        detail = payload.stringValueOrNull("detail") ?: payload.stringValueOrNull("message")
          ?: payload.stringValueOrNull("description"),
        payload = payload,
      )
      _pendingPrompts.update { list -> list.filterNot { it.requestId == requestId } + prompt }

      // Fire an OS notification for prompts scoped to a NON-bound session —
      // the user can't see that inline card right now. The handler expects
      // the LOCAL key (mute lookup, title resolution, tap-through); pre-fix
      // it received the server id and all three were mis-keyed.
      if (resolvedKey != boundKey()) {
        onPromptNotification?.invoke(resolvedKey, prompt)
      }
    }
  }

  /**
   * Cross-device settle: marmaladed (M2) broadcasts `approval.resolved` when
   * an approval settles — on THIS device or another one. Clear the matching
   * card (by the daemon's request_id; fall back to the session's oldest
   * approval card) so a prompt answered on desktop doesn't linger here.
   */
  fun onApprovalResolved(event: GatewayEvent) {
    val payload = event.payload as? JsonObject
    val rid = payload?.stringValueOrNull("request_id")
    val resolved = _pendingPrompts.value.firstOrNull { it.requestId == rid }
      ?: _pendingPrompts.value.firstOrNull { it.kind == PromptKind.Approval && it.sessionId == event.sessionId }
    if (resolved != null) remove(resolved.requestId)
  }

  /** Clear every card parked against [sessionId] — the turn is over (or the
   *  session errored), so a half-answered prompt would otherwise hover
   *  indefinitely. Mirrors desktop's `clearAllPrompts(sessionId)`. */
  fun clearForSession(sessionId: String?) {
    _pendingPrompts.update { list -> list.filterNot { it.sessionId == sessionId } }
  }

  /**
   * Answer an agent question (daemon clarify round-trip, 2026-07-18).
   * [answers] maps question text → chosen answer (multi-select comma-joined
   * by the card); [response] is freeform text. Both empty = dismissal — the
   * daemon settles the parked AskUserQuestion with a proceed-on-your-own
   * message, so the agent never hangs on a closed card.
   */
  fun respondClarify(requestId: String, answers: Map<String, String>, response: String? = null) {
    scope.launch {
      val responded = _pendingPrompts.value.firstOrNull { it.requestId == requestId }
      // Route to the prompt's own session (approval parity): a background
      // session's question answered from a notification must not settle the
      // bound session's queue. The daemon requires session_id.
      val sid = responded?.sessionId ?: boundSessionId()
      if (sid == null) {
        logWarn("clarify.respond dropped: no session id for $requestId")
        remove(requestId)
        return@launch
      }
      runCatching { rpc.clarifyRespond(requestId = requestId, sessionId = sid, answers = answers, response = response) }
        .onFailure { logWarn("clarify.respond failed: ${it.message}") }
      remove(requestId)
    }
  }

  /**
   * Cross-device settle for clarify: the daemon broadcasts `clarify.resolved`
   * when a question is answered (or dismissed) on ANY device. Mirror of
   * [onApprovalResolved].
   */
  fun onClarifyResolved(event: GatewayEvent) {
    val payload = event.payload as? JsonObject
    val rid = payload?.stringValueOrNull("request_id")
    val resolved = _pendingPrompts.value.firstOrNull { it.requestId == rid }
      ?: _pendingPrompts.value.firstOrNull { it.kind == PromptKind.Clarify && it.sessionId == event.sessionId }
    if (resolved != null) remove(resolved.requestId)
  }

  /**
   * Answer an approval card. [choice] is the server vocabulary — `once` /
   * `session` / `always` / `deny`; `session`/`always` allowlist the matched
   * `pattern_key` server-side, which is what "approve all of this kind"
   * means.
   */
  fun respondApproval(requestId: String, choice: String) {
    scope.launch {
      val responded = _pendingPrompts.value.firstOrNull { it.requestId == requestId }
      // session_id routes the choice to the right per-session queue — use the
      // prompt's own id (desktop parity: tool-approval.tsx sends
      // request.sessionId), not the bound session's; a background session's
      // approval answered from a notification must not drain the bound
      // session's queue.
      val sid = responded?.sessionId ?: boundSessionId()
      runCatching {
        // marmaladed (M2) mints a request_id and accepts it back for exact
        // correlation; a locally-synthesized card id (fork-era events with
        // no request_id) is NOT sent — the daemon then resolves the
        // session's oldest pending request (FIFO, structurally unambiguous
        // because the daemon serializes approvals per session).
        rpc.approvalRespond(
          choice = choice,
          sessionId = sid,
          requestId = requestId.takeUnless { it.startsWith("approval-") },
        )
      }.onFailure { logWarn("approval.respond failed: ${it.message}") }
      remove(requestId)
    }
  }

  /**
   * Hand a credential to the daemon's keyring (secret-entry flow).
   *
   * [value] is passed straight to the RPC and dropped — it is never stored on
   * the prompt, never logged (not even redacted, not even on failure), and
   * never round-trips through any other surface. Every log line below is
   * request-id / entry-only by design.
   *
   * The card is removed immediately on send: the daemon also broadcasts
   * `secret.resolved`, but a card that lingers while the RPC is in flight is a
   * masked field the user can type into twice.
   */
  fun respondSecret(requestId: String, value: String) {
    scope.launch {
      val responded = _pendingPrompts.value.firstOrNull { it.requestId == requestId }
      // Route to the prompt's OWN session (approval/clarify parity): a secret
      // asked in a background session and answered from its notification must
      // not settle the bound session's pending request. The daemon REQUIRES
      // session_id here — with no id to send there is no legal call to make,
      // so drop the card locally and say so.
      val sid = responded?.sessionId ?: boundSessionId()
      if (sid == null) {
        logWarn("secret.respond dropped: no session id for $requestId")
        remove(requestId)
        return@launch
      }
      val entry = responded?.payload?.stringValueOrNull("entry")
      remove(requestId)
      runCatching { rpc.secretRespond(sessionId = sid, requestId = requestId, value = value) }
        .onSuccess { result ->
          when {
            result.stored -> notify("Stored at ${entry ?: "the keyring"}")
            !result.resolved -> notify("Secret request already expired")
            else -> notify("Keyring store failed: ${result.error ?: "unknown error"}")
          }
        }
        .onFailure {
          logWarn("secret.respond failed: ${it.message}")
          notify("Could not send the secret: ${it.message ?: "request failed"}")
        }
    }
  }

  /**
   * Refuse a secret request. This is what the card's X means — a secret card
   * has NO local-only dismissal, because the agent is parked on the tool call
   * until something answers it and a silent close would leave it hanging for
   * the full 10-minute daemon timeout.
   */
  fun denySecret(requestId: String, reason: String = "declined on device") {
    scope.launch {
      val responded = _pendingPrompts.value.firstOrNull { it.requestId == requestId }
      val sid = responded?.sessionId ?: boundSessionId()
      if (sid == null) {
        logWarn("secret.respond(deny) dropped: no session id for $requestId")
        remove(requestId)
        return@launch
      }
      remove(requestId)
      runCatching { rpc.secretRespond(sessionId = sid, requestId = requestId, deny = true, reason = reason) }
        .onFailure { logWarn("secret.respond(deny) failed: ${it.message}") }
    }
  }

  /**
   * Cross-device settle for secrets: the daemon broadcasts `secret.resolved`
   * when the request is answered on ANY device — and also *unprompted*, when
   * it denies the request itself (10-minute timeout, session stop/delete/
   * error, or the last secrets-capable client disconnecting). Mirror of
   * [onClarifyResolved], plus a toast: a secure card vanishing under the
   * user's fingers with no explanation is the one case here that needs words.
   *
   * The local respond paths remove their card before the broadcast arrives,
   * so anything this clears is by definition a card THIS device did not
   * answer. The payload carries the outcome only — never a value.
   */
  fun onSecretResolved(event: GatewayEvent) {
    val payload = event.payload as? JsonObject
    val rid = payload?.stringValueOrNull("request_id")
    val resolved = _pendingPrompts.value.firstOrNull { it.requestId == rid && it.kind == PromptKind.Secret }
      ?: _pendingPrompts.value.firstOrNull { it.kind == PromptKind.Secret && it.sessionId == event.sessionId }
      ?: return
    remove(resolved.requestId)
    // Only speak up about the card the user is actually looking at.
    if (resolved.sessionKey != boundKey()) return
    when (payload?.stringValueOrNull("outcome")) {
      "denied" -> notify("Secret request expired or was denied")
      "failed" -> notify("Keyring store failed: ${payload.stringValueOrNull("error") ?: "unknown error"}")
      // "stored" (another device answered it) needs no words — the card going
      // away IS the message, and this device never saw the value anyway.
      else -> Unit
    }
  }

  /** Send sudo password back to the gateway. Closes the matching prompt. */
  fun respondSudo(requestId: String, password: String) {
    val sid = boundSessionId()
    scope.launch {
      runCatching { rpc.sudoRespond(requestId = requestId, password = password, sessionId = sid) }
        .onFailure { logWarn("sudo.respond failed: ${it.message}") }
      remove(requestId)
    }
  }

  /**
   * Remove a prompt card locally without responding. The agent-side wait is
   * unaffected (it can re-emit or time out server-side) — this only unblocks
   * the UI. Pre-fix the card's X was wired to a no-op and a clarify could
   * not be dismissed at all (maintainer, on-device 2026-07-03).
   */
  fun dismiss(requestId: String) {
    _pendingPrompts.update { list -> list.filterNot { it.requestId == requestId } }
  }
}

private fun JsonObject.stringValueOrNull(key: String): String? {
  val primitive = this[key] as? JsonPrimitive ?: return null
  if (!primitive.isString) return null
  return primitive.content
}
