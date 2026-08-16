package app.marmalade.android.chat

import app.marmalade.android.chat.messages.MessageStream
import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.rpc.ConnectionState
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.ui.chat.friendlySessionName
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session-LIST state machine, extracted from [ChatController] (2026-07-17
 * decomposition): server-list reconciliation ([refresh]), the optimistic
 * delete machine ([delete] + rollback + the [pendingDeletes] tombstones),
 * the cross-device `session.deleted` cleanup ([onSessionDeleted]), and the
 * authoritative-bind row ensure ([applyMainSessionKey]).
 *
 * Everything here is keyed by the stable LOCAL session key (== the daemon's
 * immutable session_id post-K1). Per-message state stays with
 * [ChatController]/[MessageStream]; this class only owns list rows and their
 * delete lifecycle.
 */
internal class SessionListSync(
  private val scope: CoroutineScope,
  private val rpc: MarmaladeRpc,
  private val chatDao: ChatDao,
  private val ioDispatcher: CoroutineDispatcher,
  private val messageStream: MessageStream,
  /** Shared local-key → server-id cache (ChatController owns the map; both
   *  sides mutate it under the same semantics as before the extraction). */
  private val keyToServerId: ConcurrentHashMap<String, String>,
  /** The currently-bound session key (never pruned/yanked mid-view). */
  private val boundKey: () -> String,
  /** Detach controller state from [localKey] if it is the bound session
   *  (cancel hydration, null the id, rebind "main") — the delete paths must
   *  not leave the UI pointed at a row that's going away. */
  private val detachIfBound: (localKey: String) -> Unit,
  /** Delegate to [ChatController.load] (applyMainSessionKey binds after its
   *  row ensure). */
  private val loadSession: (key: String) -> Unit,
  /** Delete a session server-side, returning true on success (or if it's
   *  already gone). Null in unit tests → local-only delete. */
  private val deleteSessionRemote: (suspend (storedId: String) -> Boolean)?,
  private val toast: (String) -> Unit,
  private val logWarn: (String) -> Unit,
) {

  /** Keys with a delete in flight. [refresh] skips (re-)inserting these so a
   *  concurrent `session.list` can't resurrect a row we're removing before
   *  the server-side delete commits. */
  private val pendingDeletes = ConcurrentHashMap.newKeySet<String>()

  /** True once the first `session.list` round-trip has completed (success
   *  OR failure) since process start. Until then the sessions tab can't
   *  distinguish "no sessions" from "haven't asked the server yet", so it
   *  shows a spinner instead of the empty state. Sticky — a later refresh
   *  failure doesn't hide an already-rendered list. */
  private val _sessionsSynced = MutableStateFlow(false)
  val sessionsSynced: StateFlow<Boolean> = _sessionsSynced.asStateFlow()

  /**
   * Refresh the sidebar list from the server.
   *
   * @param limit max rows to fetch from the server (default 40).
   * @param prune when true (default), delete any local Room rows whose key is
   *   NOT present in the server's returned set — keeps the local cache
   *   converged with server truth. Two exclusions apply:
   *   - The currently-bound session is never yanked out from under the user
   *     mid-session.
   *   - Sessions that have pending outbox rows are preserved so un-acked user
   *     messages aren't silently dropped.
   *
   *   Pass `prune = false` for a soft-merge (insert/update only) when the
   *   caller knows the server list is partial (e.g. a paginated fetch where
   *   more pages follow).
   */
  fun refresh(limit: Int? = null, prune: Boolean = true) {
    if (rpc.rpcClient.connectionState.value != ConnectionState.Open) return
    scope.launch {
      try {
        // marmaladed session.list: rows keyed by the immutable session_id,
        // carrying the rollup topic/summary (title material), last_active
        // (ms), the P4 unread cursors, and the P2 lifecycle/run_state.
        val rows: List<ServerSessionRow> = rpc.sessionList(limit = limit ?: 40).sessions.map { s ->
          ServerSessionRow(
            id = s.session_id,
            // Explicit rename (session.title) beats the agent's rollup topic.
            title = s.title?.trim()?.takeIf { it.isNotEmpty() } ?: s.topic ?: "",
            preview = s.summary ?: "",
            activityMs = s.last_active ?: 0L,
            source = s.purpose ?: "",
            lastSeq = s.last_seq,
            seenSeq = s.seen_seq,
            lifecycle = s.lifecycle,
            runState = s.run_state,
            model = s.model,
            branchedFrom = s.branched_from?.session_id,
            workspaceId = s.workspace_id,
            isMain = s.is_main,
            archived = s.archived,
            contextUsed = s.context_used,
            contextMax = s.context_max,
          )
        }
        withContext(ioDispatcher) {
          val serverKeys = mutableSetOf<String>()
          rows.forEach { row ->
            val key = row.id
            // Record the true server set FIRST (drives tombstone-clearing +
            // prune below), then skip re-inserting any row with a delete in
            // flight — re-inserting would resurrect a session the user just
            // removed before its server-side delete has committed (the
            // "delete comes back" race).
            serverKeys.add(key)
            if (key in pendingDeletes) return@forEach
            val existing = chatDao.getSessionByKey(key)
            val merged = SessionEntity(
              key = key,
              // Terminal fallback to friendlySessionName(key) matches the
              // top-bar render path in MarmaladeNavHost.kt. Without it,
              // sessions the server hasn't auto-titled (no title, no
              // preview, no prior displayName) render as the hard-coded
              // "New Session" string via SessionCategoryUtils, making the
              // sidebar useless for distinguishing them.
              displayName = row.title.trim().ifEmpty { null }
                ?: row.preview.trim().ifEmpty { null }
                ?: existing?.displayName
                ?: friendlySessionName(key),
              lastMessageAt = row.activityMs.takeIf { it > 0 } ?: existing?.lastMessageAt,
              createdAt = existing?.createdAt ?: System.currentTimeMillis(),
              updatedAt = System.currentTimeMillis(),
              // NEVER overwrite a learned server id. gatewaySessionId maps
              // the session_id the gateway stamps on EVERY event back to
              // this local row (resolveLocalKeyForGatewayId); it is written
              // with authoritative ids by session.create / session.resume.
              // Seed with the row id only while nothing is known — that
              // seed is what routes ensureServerSessionId to the resume
              // path (vs session.create) for server-known sessions.
              gatewaySessionId = existing?.gatewaySessionId ?: row.id,
              thinkingLevel = existing?.thinkingLevel ?: "off",
              draftText = existing?.draftText,
              emoji = existing?.emoji,
              isMuted = existing?.isMuted ?: false,
              ttsEnabled = existing?.ttsEnabled ?: false,
              unreadCount = existing?.unreadCount ?: 0,
              category = existing?.category,
              agentId = existing?.agentId,
              // Origin marker off the wire (session metadata; no longer drives
              // tab grouping after the two-tab redesign). Preserve any existing
              // value if the server omits it on a partial refresh; otherwise
              // adopt the freshly reported source.
              source = row.source.trim().ifEmpty { existing?.source },
              // Workspace: daemon rows don't carry cwd; keep the local value.
              cwd = existing?.cwd,
              // P4 unread cursors. Monotonic max-merge — a stale list
              // response racing our own optimistic seen-stamp must never
              // regress either cursor (chip flicker).
              lastSeq = maxOf(row.lastSeq, existing?.lastSeq ?: 0L),
              seenSeq = maxOf(row.seenSeq, existing?.seenSeq ?: 0L),
              // P2 state — the list row is current server truth; adopt it.
              lifecycle = row.lifecycle ?: existing?.lifecycle,
              runState = row.runState ?: existing?.runState,
              // Model: server value wins when reported; preserve the local
              // value (including an unsent picker choice) when the row
              // omits it — same nullable-merge idiom as lifecycle above.
              model = row.model ?: existing?.model,
              // Fork lineage: server truth when the row carries it (so a
              // cross-device fork lights the chip), else keep any local value
              // set at fork time. Stable metadata — never regresses to null.
              branchedFromId = row.branchedFrom ?: existing?.branchedFromId,
              // Workspace membership: DERIVED server-side every list — adopt it
              // verbatim (INCLUDING null). Never preserve-on-null: a deleted
              // workspace or a moved cwd must clear the stamp, else the session
              // lingers in a phantom card. Trust the stamp, never re-derive.
              workspaceId = row.workspaceId,
              // is_main is server truth (the daemon owns the designation) —
              // adopt verbatim every refresh, like workspaceId.
              isMain = row.isMain,
              // archived is daemon-backed shared metadata — adopt verbatim
              // every refresh (never preserved-on-null), same as isMain. An
              // optimistic archive tap is confirmed here; a stale list racing a
              // just-committed tap self-heals on the next refresh.
              archived = row.archived,
              // Context occupancy is daemon truth stamped at turn end — adopt
              // verbatim INCLUDING null, like archived/isMain. Preserving on
              // null would keep showing a donut for a session the daemon just
              // cleared (it nulls both columns), or invent one for a client
              // talking to a daemon that predates the fields.
              contextUsed = row.contextUsed,
              contextMax = row.contextMax,
            )
            if (existing != null) {
              chatDao.updateSessionRow(merged)
            } else {
              chatDao.insertSession(merged)
            }
            // Missed-truncation reconcile (session.clear / session.undo): the
            // daemon's removal broadcast (session.cleared / session.undone) is
            // TRANSIENT + subscriber-only, and replay only ADDS rows — so a
            // session cleared/undone while this device wasn't subscribed to it
            // keeps its stale local rows forever. Detect it arithmetically: the
            // server's raw last_seq fell below our local max message seq ⇒ the
            // server truncated. Wipe the local rows; the next open replays
            // clean. Scoped to NON-bound sessions — the bound session's live
            // subscription handles its own clear, and wiping rows out from under
            // the open chat (no re-subscribe here) would blank it. Arithmetic,
            // never content-based (identity rule 2).
            if (key != boundKey() && row.lastSeq < chatDao.getMaxServerSeq(key)) {
              chatDao.deleteMessagesForSession(key)
            }
            // Deliberately NOT touching keyToServerId here: session.create /
            // session.resume responses own it; poisoning the cache from list
            // rows made ensureServerSessionId hand a stale placeholder to
            // consumers that expected the authoritative id.
          }

          // Lift delete tombstones the server has confirmed: any pending-delete
          // key the list no longer returns is genuinely gone, so refreshes may
          // flow for it again. Keys the server STILL lists stay tombstoned (and
          // skipped above) so an eventually-consistent list can't re-add them.
          pendingDeletes.removeAll { it !in serverKeys }

          // Prune local rows that the server no longer knows about, unless the
          // caller opted out (soft-merge) or the exclusion conditions apply.
          if (prune) {
            val bound = boundKey()
            val localKeys = chatDao.getAllSessionKeys()
            for (localKey in localKeys) {
              if (localKey in serverKeys) continue          // still on server
              if (localKey == bound) continue               // user is looking at it
              val hasOutbox = chatDao.getOutboxForSessionOnce(localKey).isNotEmpty()
              if (hasOutbox) continue                       // has un-acked messages
              chatDao.deleteMessagesForSession(localKey)
              chatDao.deleteSession(localKey)
              keyToServerId.remove(localKey)
            }
          }
        }
      } catch (t: Throwable) {
        logWarn("session.list failed: ${t.message ?: t.javaClass.simpleName}")
      } finally {
        // Attempt finished either way — the sessions tab may now trust an
        // empty list as genuinely empty rather than not-yet-fetched.
        _sessionsSynced.value = true
      }
    }
  }

  /** Delete a session: optimistically remove the local row FIRST, then
   *  delete server-side via marmaladed `session.delete`, rolling back if the
   *  server delete fails.
   *
   *  Two things make this actually stick (each a bug we hit before):
   *   1. **Optimistic + guarded.** The row is removed before the awaits and
   *      [key] is held in [pendingDeletes] so a concurrent [refresh] can't
   *      re-insert the still-listed row before the delete commits.
   *   2. **Rollback on failure.** A genuine server failure re-inserts the row
   *      and toasts, instead of leaving the UI lying / letting it silently
   *      reappear on the next refresh.
   *
   *  CROSS-REPO CONTRACT: deletion is the SERVER's job. One `session.delete`
   *  with the one id the list surfaces stops a live harness and cascades the
   *  index row, message identity rows, every device's seen cursor, and the
   *  transcript (daemon router.ts). No close-before-delete ritual — the
   *  fork's error-4023 dance is not part of protocol v1. */
  fun delete(key: String) {
    scope.launch {
      // Tombstone the key BEFORE touching anything so no insert path can
      // resurrect the row mid-delete: [refresh] and [applyMainSessionKey]
      // both consult [pendingDeletes]. The tombstone is DURABLE — it lives
      // until a later session.list confirms the row is gone (cleared in
      // refresh), not just until the DELETE's response returns, because the
      // gateway's session.list is eventually consistent and can still list
      // the row for a beat after the DELETE commits.
      pendingDeletes.add(key)

      // Detach from the session WITHOUT eagerly creating a replacement. The
      // old `load("main")` ran ensureServerSessionId → session.create and
      // materialised a fresh (K1-renamed) row that popped to the top of the
      // list as a ghost. A bare rebind is enough; the next real navigation
      // hydrates.
      detachIfBound(key)

      val serverId = keyToServerId[key]
      val captured = withContext(ioDispatcher) { chatDao.getSessionByKey(key) }
      // Optimistic local removal (FK cascade also drops messages + outbox).
      withContext(ioDispatcher) {
        chatDao.deleteMessagesForSession(key)
        chatDao.deleteSession(key)
      }
      keyToServerId.remove(key)
      if (serverId != null) messageStream.removeSession(serverId)

      if (rpc.rpcClient.connectionState.value != ConnectionState.Open) {
        rollbackDelete(key, captured, "Can't delete a session while offline")
        return@launch
      }
      val serverGone = deleteSessionRemote?.let { remote ->
        runCatching { remote(key) }.getOrElse { t ->
          logWarn("session delete failed for $key: ${t.message}")
          false
        }
      } ?: true // no remote wired (unit tests) → local-only delete
      if (!serverGone) {
        rollbackDelete(key, captured, "Couldn't delete session")
        return@launch
      }
      // Success: LEAVE the tombstone. refresh lifts it once the server list
      // no longer returns the key, so an eventually-consistent list can't
      // re-add it in the gap. The no-remote (unit test) path has no list to
      // confirm against, so drop it now.
      if (deleteSessionRemote == null) pendingDeletes.remove(key)
    }
  }

  /** Re-insert a captured session row after a failed delete and surface a
   *  toast. Removes [key] from [pendingDeletes] so refreshes flow again. */
  private suspend fun rollbackDelete(
    key: String,
    captured: SessionEntity?,
    message: String,
  ) {
    if (captured != null) {
      withContext(ioDispatcher) { chatDao.insertSession(captured) }
    }
    pendingDeletes.remove(key)
    toast(message)
  }

  /** Set a session's archived flag via marmaladed `session.archive`:
   *  optimistically write the local row FIRST (so the list re-shapes instantly
   *  — archived rows drop out of the main list), then commit server-side,
   *  reverting the row and surfacing the error if the RPC rejects.
   *
   *  Follows the setCurrentModel/clearConversation pattern (Fable review): a
   *  rejection is SURFACED (revert + toast), never swallowed into a bare
   *  logWarn. The daemon refuses the MAIN session ("the main session is
   *  daemon-managed and cannot be archived") and unknown ids; the UI hides the
   *  action for the main row, so a rejection here means a mid-flight state
   *  change (e.g. a daemon restart) — revert and tell the user.
   *
   *  Unlike delete there is no tombstone: archived is idempotent daemon truth
   *  adopted verbatim on every refresh, so a racing list self-heals to the
   *  committed value on the next round-trip. */
  fun archive(key: String, archived: Boolean) {
    scope.launch {
      val captured = withContext(ioDispatcher) { chatDao.getSessionByKey(key) }
      val previous = captured?.archived ?: !archived
      if (previous == archived) return@launch // already in the target state — no-op
      // Optimistic local flip (in-place UPDATE — no FK cascade).
      withContext(ioDispatcher) { chatDao.updateSessionArchived(key, archived) }

      if (rpc.rpcClient.connectionState.value != ConnectionState.Open) {
        withContext(ioDispatcher) { chatDao.updateSessionArchived(key, previous) }
        toast(if (archived) "Can't archive while offline" else "Can't unarchive while offline")
        return@launch
      }
      // Post-K1 the local key IS the wire session_id; keep the resolver for a
      // legacy row whose gatewaySessionId learned a different id.
      val serverId = keyToServerId[key] ?: captured?.gatewaySessionId ?: key
      runCatching { rpc.sessionArchive(serverId, archived) }
        .onFailure { t ->
          logWarn("session.archive($archived) failed for $key: ${t.message}")
          withContext(ioDispatcher) { chatDao.updateSessionArchived(key, previous) }
          toast(
            if (archived) "Couldn't archive: ${t.message ?: "the session is unavailable"}"
            else "Couldn't unarchive: ${t.message ?: "the session is unavailable"}",
          )
        }
    }
  }

  /**
   * Cross-device `session.deleted` cleanup: marmaladed broadcasts the event
   * to every subscriber before a session.delete cascade commits. Our own
   * deletes already did the local cleanup optimistically (pendingDeletes
   * guards them); this path is for a delete ANOTHER device initiated — drop
   * the local mirror so the session doesn't linger until the next list
   * refresh.
   */
  fun onSessionDeleted(gone: String) {
    scope.launch(ioDispatcher) {
      // Under K1 the local key IS the wire session_id; the resolver only
      // matters for a legacy row whose key predates the promotion.
      val localKey = chatDao.resolveLocalKeyForGatewayId(gone) ?: gone
      if (localKey in pendingDeletes) return@launch // we initiated; already handled
      detachIfBound(localKey)
      keyToServerId.remove(localKey)
      messageStream.removeSession(gone)
      chatDao.deleteMessagesForSession(localKey)
      chatDao.deleteSession(localKey)
    }
  }

  /**
   * Bind the controller to the session id that `session.most_recent` (or any
   * other authoritative source) just resolved to. Under marmalade, the local
   * key and the server id are the same string; this method ensures a
   * [SessionEntity] row exists with the correct
   * [SessionEntity.gatewaySessionId] before delegating to
   * [ChatController.load], so the resume flow doesn't accidentally fire
   * `session.create` against a session that already exists.
   *
   * Idempotent — calling twice with the same id is a no-op after the first
   * row insert.
   */
  fun applyMainSessionKey(sessionId: String) {
    val key = sessionId.trim()
    if (key.isEmpty()) return
    // Don't resurrect a session being deleted: on reconnect the gateway's
    // session.most_recent can still point at the just-deleted session for a
    // beat, and inserting its row here (unlike refresh, this path had no
    // guard) brings it right back.
    if (key in pendingDeletes) return
    if (boundKey() == key) return
    scope.launch {
      withContext(ioDispatcher) {
        val existing = chatDao.getSessionByKey(key)
        val now = System.currentTimeMillis()
        if (existing != null) {
          // Preserve any learned server id — overwriting one breaks
          // resolveLocalKeyForGatewayId for every subsequent stamped event
          // (see refresh for the full story). Seed only when nothing is
          // known yet.
          if (existing.gatewaySessionId == null) {
            chatDao.updateSessionRow(existing.copy(gatewaySessionId = key, updatedAt = now))
          }
        } else {
          chatDao.insertSession(
            SessionEntity(
              key = key,
              displayName = null,
              createdAt = now,
              updatedAt = now,
              gatewaySessionId = key,
              thinkingLevel = "off",
            ),
          )
        }
      }
      loadSession(key)
    }
  }
}

/** Internal projection of a marmaladed `session.list` row for
 *  [SessionListSync.refresh]. */
private data class ServerSessionRow(
  val id: String,
  val title: String,
  val preview: String,
  val activityMs: Long,
  val source: String,
  /** P4 unread cursors: unread = lastSeq > seenSeq. */
  val lastSeq: Long,
  val seenSeq: Long,
  /** P2 lifecycle/runState split. */
  val lifecycle: String?,
  val runState: String?,
  /** Harness model id the session was created/resumed with. Null = the
   *  server didn't report one (row omitted it — preserve the local value). */
  val model: String?,
  /** Fork lineage: the source session_id this branched from, or null (T2 #3
   *  session.list `branched_from.session_id`). */
  val branchedFrom: String?,
  /** Workspace membership, derived server-side (session.list `workspace_id`).
   *  Adopted verbatim including null — never preserved-on-null. */
  val workspaceId: String?,
  /** THE daemon-managed singleton main session (session.list `is_main`).
   *  Adopted verbatim — the daemon owns the designation. */
  val isMain: Boolean,
  /** Archived flag (session.list `archived`). Adopted verbatim including
   *  false — daemon-backed shared metadata, never a behavior filter. */
  val archived: Boolean,
  /** Persisted context occupancy (session.list `context_used`/`context_max`).
   *  Adopted verbatim including null — the daemon nulls both on session.clear,
   *  and an old daemon omits them entirely; either way the donut goes dark
   *  rather than showing a stale number. */
  val contextUsed: Long?,
  val contextMax: Long?,
)
