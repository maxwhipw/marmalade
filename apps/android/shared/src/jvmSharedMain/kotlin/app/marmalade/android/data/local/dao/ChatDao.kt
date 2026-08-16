package app.marmalade.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.marmalade.android.data.local.entity.GatewayEventEntity
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.QueuedPromptEntity
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

/** The two persisted context-occupancy columns of a session row, projected on
 *  their own so the chat surface can observe them without re-reading (and
 *  re-emitting on) every unrelated session-row write. */
data class SessionContextRow(
    val contextUsed: Long?,
    val contextMax: Long?,
)

// The content-signature machinery + reconcileHistory lived here until the
// marmaladed stable-ids flip: server history rows now carry immutable
// message_id + seq, so replay dedup is id equality and ordering is seq
// arithmetic — no content fingerprints, no greedy alignment. History arrives
// via session.subscribe(since_seq) through the normal MessageStream path.

@Dao
interface ChatDao {

    // Sessions

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    /**
     * UPDATE the session row in-place. Use this instead of insertSession()
     * for modifications to an existing row — insertSession with REPLACE
     * conflict strategy compiles to `INSERT OR REPLACE`, which in SQLite
     * means "DELETE the conflicting row, INSERT the new one." That DELETE
     * fires the FK CASCADE on messages.sessionKey and outbox.sessionKey,
     * wiping ALL chat history for the session. This @Update path uses an
     * UPDATE statement that does not trigger the CASCADE.
     */
    @androidx.room.Update
    suspend fun updateSessionRow(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY CASE WHEN lastMessageAt IS NULL THEN 1 ELSE 0 END, lastMessageAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE `key` = :key")
    suspend fun getSessionByKey(key: String): SessionEntity?

    @Query("DELETE FROM sessions WHERE `key` = :key")
    suspend fun deleteSession(key: String)

    @Query("SELECT `key` FROM sessions")
    suspend fun getAllSessionKeys(): List<String>

    @Query("UPDATE sessions SET isMuted = :isMuted, updatedAt = :now WHERE `key` = :sessionKey")
    suspend fun updateSessionMuted(sessionKey: String, isMuted: Boolean, now: Long = System.currentTimeMillis())

    @Query("SELECT isMuted FROM sessions WHERE `key` = :sessionKey")
    suspend fun isSessionMuted(sessionKey: String): Boolean?

    /** Reactive mute state for the settings-sheet Notifications toggle. Null
     *  when the session row doesn't exist yet (treated as un-muted). */
    @Query("SELECT isMuted FROM sessions WHERE `key` = :sessionKey")
    fun observeSessionMuted(sessionKey: String): Flow<Boolean?>

    @Query("UPDATE sessions SET lastMessageAt = :timestamp, updatedAt = :timestamp WHERE `key` = :key")
    suspend fun updateSessionLastMessage(key: String, timestamp: Long)

    @Query("UPDATE sessions SET emoji = :emoji, updatedAt = :now WHERE `key` = :sessionKey")
    suspend fun updateSessionEmoji(sessionKey: String, emoji: String?, now: Long = System.currentTimeMillis())

    // GW-2: background-session writers for the unread badge. Incremented per
    // inbound assistant message finalized in a non-active session; reset to 0
    // when the user enters the session via bootstrap().
    @Query("UPDATE sessions SET unreadCount = unreadCount + 1, updatedAt = :now WHERE `key` = :sessionKey")
    suspend fun incrementUnreadCount(sessionKey: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET unreadCount = 0, updatedAt = :now WHERE `key` = :sessionKey")
    suspend fun resetUnreadCount(sessionKey: String, now: Long = System.currentTimeMillis())

    // UI-2: per-session draft persistence. Write on leave, read on enter.
    @Query("UPDATE sessions SET draftText = :text, updatedAt = :now WHERE `key` = :sessionKey")
    suspend fun updateSessionDraft(sessionKey: String, text: String?, now: Long = System.currentTimeMillis())

    @Query("SELECT draftText FROM sessions WHERE `key` = :sessionKey")
    suspend fun getSessionDraft(sessionKey: String): String?

    // Per-session model pick, mirroring the draft idiom: written on picker
    // choice so an unsent pick survives a switch-away-and-back; read on bind
    // to seed the composer chip. refreshSessions overwrites with server truth.
    @Query("UPDATE sessions SET model = :model, updatedAt = :now WHERE `key` = :sessionKey")
    suspend fun updateSessionModel(sessionKey: String, model: String?, now: Long = System.currentTimeMillis())

    // Per-session reasoning effort, same idiom as the model pick above: the
    // picker used to change a StateFlow only, so an unsent pick was lost to
    // the next session.info echo and to any switch-away-and-back.
    @Query("UPDATE sessions SET thinkingLevel = :level, updatedAt = :now WHERE `key` = :sessionKey")
    suspend fun updateSessionThinkingLevel(sessionKey: String, level: String, now: Long = System.currentTimeMillis())

    // Archived flag (session.archive). Written optimistically on the
    // archive/unarchive tap and reverted if the RPC rejects; refreshSessions
    // overwrites with server truth (adopted verbatim, like isMain).
    @Query("UPDATE sessions SET archived = :archived, updatedAt = :now WHERE `key` = :sessionKey")
    suspend fun updateSessionArchived(sessionKey: String, archived: Boolean, now: Long = System.currentTimeMillis())

    /** Reactive persisted context occupancy, seeding the composer donut for the
     *  bound session. A Flow (not a one-shot read at bind) because the row's
     *  columns are written by session.list refresh, which can land AFTER the
     *  chat binds — a cold open must not have to wait for the next navigation.
     *  Null row / null columns = unknown. */
    @Query("SELECT contextUsed, contextMax FROM sessions WHERE `key` = :sessionKey")
    fun observeSessionContext(sessionKey: String): Flow<SessionContextRow?>

    /** Null both context columns, mirroring the daemon nulling them on
     *  `session.clear` — a pre-clear number would overstate an empty window.
     *  Applied on the `session.cleared` broadcast so the local seed can't
     *  resurrect the stale reading before the next refresh. */
    @Query("UPDATE sessions SET contextUsed = NULL, contextMax = NULL, updatedAt = :now WHERE `key` = :sessionKey")
    suspend fun clearSessionContext(sessionKey: String, now: Long = System.currentTimeMillis())

    // Messages

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE sessionKey = :sessionKey ORDER BY timestampMs ASC")
    fun getMessagesForSession(sessionKey: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionKey = :sessionKey ORDER BY timestampMs ASC")
    suspend fun getMessagesForSessionOnce(sessionKey: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE sessionKey = :sessionKey")
    suspend fun deleteMessagesForSession(sessionKey: String)

    /**
     * Delete every message in [sessionKey] whose clientOrdinal is >=
     * [fromOrdinal] (inclusive). Used by branch-resend / edit-resend so
     * the local view tracks the server's truncate semantics immediately
     * (immediate — the user shouldn't watch the dropped tail linger).
     */
    @Query("DELETE FROM messages WHERE sessionKey = :sessionKey AND clientOrdinal >= :fromOrdinal")
    suspend fun deleteMessagesFromOrdinal(sessionKey: String, fromOrdinal: Long)

    /**
     * Resolve a 1-based user-message ordinal (Nth user prompt in the
     * session, oldest-first) to its row's clientOrdinal. Used by
     * branch-resend to convert the truncate_before_user_ordinal hint
     * into the on-disk row identifier deleteMessagesFromOrdinal expects.
     * Returns null when the user has fewer than [userOrdinal] messages.
     */
    @Query(
        """
        SELECT clientOrdinal FROM messages
        WHERE sessionKey = :sessionKey AND role = 'user'
        ORDER BY clientOrdinal ASC
        LIMIT 1 OFFSET :userOrdinal
        """,
    )
    suspend fun getUserOrdinalClientOrdinal(sessionKey: String, userOrdinal: Int): Long?

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionKey = :sessionKey")
    suspend fun getMessageCount(sessionKey: String): Int

    // Widget: fetch the most recent messages for a session (one-shot, for Glance widget snapshot).
    @Query("SELECT * FROM messages WHERE sessionKey = :sessionKey ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecentMessagesOnce(sessionKey: String, limit: Int): List<MessageEntity>

    // Client-side message search lived here (a per-session LIKE, an FTS4 MATCH
    // over messages_fts, and a session-title LIKE) until 2026-07-27. All three
    // are gone with the messages_fts entity: search is answered by the daemon's
    // FTS5 index (search.messages) for both cross-session search and
    // find-in-conversation. The rule is "the index lives on the daemon, never
    // client-local Room FTS" — FTS4 has no bm25, and a per-device index means
    // results depend on which device you're holding.

    // -- Outbox: read-only observers + helper one-shots ----------------------
    //
    // The outbox holds unsent user prompts. The drainer writes to it
    // (insertOutbox below); ackOutboxAsMessage is the only public method
    // that moves rows out of it.

    @Query("SELECT * FROM outbox WHERE sessionKey = :sessionKey ORDER BY clientOrdinal ASC, createdAtMs ASC")
    fun observeOutboxForSession(sessionKey: String): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE sessionKey = :sessionKey ORDER BY clientOrdinal ASC, createdAtMs ASC")
    suspend fun getOutboxForSessionOnce(sessionKey: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE id = :outboxId")
    suspend fun getOutboxByIdOnce(outboxId: String): OutboxEntity?

    /**
     * Drain candidates across all sessions. Excludes 'sending' rows (the
     * in-process inFlightOutboxIds set guards against re-draining those
     * within a process lifetime; boot recovery demotes leftover 'sending'
     * back to 'pending').
     */
    @Query("""
        SELECT * FROM outbox
        WHERE status = 'pending' AND nextAttemptAtMs <= :now
        ORDER BY createdAtMs ASC
    """)
    suspend fun getDueOutbox(now: Long): List<OutboxEntity>

    @Query("SELECT MAX(clientOrdinal) FROM outbox WHERE sessionKey = :sessionKey")
    suspend fun getMaxOutboxOrdinal(sessionKey: String): Long?

    @Query("SELECT MAX(clientOrdinal) FROM messages WHERE sessionKey = :sessionKey")
    suspend fun getMaxMessagesOrdinal(sessionKey: String): Long?

    /**
     * Highest server-minted seq rendered locally for a session — the
     * session.subscribe replay cursor (`since_seq`) and the value stamped
     * via session.seen. 0 when nothing server-originated is stored.
     */
    @Query("SELECT COALESCE(MAX(serverSeq), 0) FROM messages WHERE sessionKey = :sessionKey")
    suspend fun getMaxServerSeq(sessionKey: String): Long

    /** True when a message row with this (server-minted) id already exists —
     *  dedup guard for replayed events (same id = same message). */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId)")
    suspend fun messageExists(messageId: String): Boolean

    /** Merge the daemon's per-session cursors (session.list last_seq/seen_seq).
     *  Monotonic max-merge — a stale list response must never regress either
     *  cursor (chip flicker). */
    @Query("""
        UPDATE sessions
        SET lastSeq = MAX(lastSeq, :lastSeq), seenSeq = MAX(seenSeq, :seenSeq)
        WHERE `key` = :sessionKey
    """)
    suspend fun mergeSessionSeqCursors(sessionKey: String, lastSeq: Long, seenSeq: Long)

    /** P2 state from session.list rows / status.update events. */
    @Query("UPDATE sessions SET lifecycle = :lifecycle, runState = :runState WHERE `key` = :sessionKey")
    suspend fun updateSessionRunState(sessionKey: String, lifecycle: String?, runState: String?)

    // -- Outbox: writes + ack transaction ------------------------------------
    //
    // The ack transaction (ackOutboxAsMessage) is the move from outbox ->
    // messages on RPC success — the only public DAO method that touches both
    // tables (invariant I2 of the ratified plan, kept internally). The
    // lost-response case converges via session.subscribe replay: the server's
    // message.user event re-renders the message under its server id.

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(row: OutboxEntity)

    /**
     * Promote an outbox row into a confirmed MessageEntity, bound to the
     * SERVER-minted identity from prompt.submit's ack (identity plan P1):
     * messages.id = [serverMessageId], serverSeq = [serverSeq]. IDs are names
     * minted once by the daemon — the outbox id was only the local queue
     * handle. Falls back to the outbox id / seq 0 against a legacy gateway
     * whose prompt.submit returns nothing.
     *
     * No-op if the outbox row was already removed.
     */
    @Transaction
    suspend fun ackOutboxAsMessage(
        outboxId: String,
        serverMessageId: String? = null,
        serverSeq: Long = 0L,
        serverTimestampMs: Long? = null,
    ) {
        val row = getOutboxByIdOnce(outboxId) ?: return
        val messageRow = MessageEntity(
            id = serverMessageId ?: row.id,
            sessionKey = row.sessionKey,
            role = "user",
            contentJson = row.contentJson,
            // Local send time keeps the bubble's on-screen position stable;
            // the server clock lands in serverTimestampMs. Order comes from
            // serverSeq anyway — seq orders, timestamps are metadata.
            timestampMs = row.createdAtMs,
            serverTimestampMs = serverTimestampMs,
            isStreaming = false,
            clientOrdinal = row.clientOrdinal,
            streamSeq = 0,
            serverSeq = serverSeq,
            parentMessageId = null,
            originDeviceId = null,
            replyToId = null,
            voiceOrigin = row.voiceOrigin,
            rawPayloadJson = null,
        )
        insertMessage(messageRow)
        deleteFromOutbox(outboxId)
    }

    // -- Composer send-queue --------------------------------------------------
    //
    // Prompts staged while a turn runs (NOT the outbox — see
    // QueuedPromptEntity). ChatController owns the drain loop; the panel
    // above the composer renders observeQueueForSession.

    @Query("SELECT * FROM composer_queue WHERE sessionKey = :sessionKey ORDER BY ordinal ASC, createdAtMs ASC")
    fun observeQueueForSession(sessionKey: String): Flow<List<QueuedPromptEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQueuedPrompt(row: QueuedPromptEntity)

    @Query("DELETE FROM composer_queue WHERE id = :id")
    suspend fun deleteQueuedPrompt(id: String)

    @Query("SELECT MAX(ordinal) FROM composer_queue WHERE sessionKey = :sessionKey")
    suspend fun getMaxQueueOrdinal(sessionKey: String): Long?

    @Query("SELECT MIN(ordinal) FROM composer_queue WHERE sessionKey = :sessionKey")
    suspend fun getMinQueueOrdinal(sessionKey: String): Long?

    /** Promote an entry to the head of its session's queue ("send now"). */
    @Query("UPDATE composer_queue SET ordinal = :ordinal WHERE id = :id")
    suspend fun setQueueOrdinal(id: String, ordinal: Long)

    /**
     * Resolve a gateway session_id back to its local sessions.key.
     * MessageStream needs this because every event payload carries the
     * gateway-side id, but the messages.sessionKey FK references the
     * local key; without the translation every flushSessionToRoom
     * rejects with FOREIGN KEY constraint failed.
     */
    @Query("SELECT `key` FROM sessions WHERE gatewaySessionId = :gatewayId LIMIT 1")
    suspend fun resolveLocalKeyForGatewayId(gatewayId: String): String?

    /**
     * Rename a session row's primary key in place. The FKs on
     * messages.sessionKey and outbox.sessionKey are declared
     * `ON UPDATE CASCADE` (K1, parity audit), so SQLite relabels every
     * child row atomically as part of this single UPDATE — no extra
     * statements needed.
     *
     * Returns the number of rows updated (0 if no session with [oldKey]
     * exists; 1 on success). Callers wrap this in a Room transaction
     * with any other state changes that must commit atomically with
     * the rename (e.g. updating gatewaySessionId on the same row).
     *
     * Used by ChatController.ensureServerSessionId to promote the
     * temporary client-coined "chat-yyyymmdd-HHmmss" key to the
     * server's persistent stored_session_id after the first successful
     * session.create — see parity audit §1 Option B.
     */
    @Query("UPDATE sessions SET `key` = :newKey WHERE `key` = :oldKey")
    suspend fun renameSessionKey(oldKey: String, newKey: String): Int

    @Query("DELETE FROM outbox WHERE id = :outboxId")
    suspend fun deleteFromOutbox(outboxId: String)

    /** Replace a row's cached gateway session_id with a freshly-minted one
     *  (e.g. after a session.resume recovery following a 4001). */
    @Query("UPDATE outbox SET serverSessionId = :newSessionId WHERE id = :outboxId")
    suspend fun updateOutboxServerSessionId(outboxId: String, newSessionId: String)

    @Query("UPDATE outbox SET status = 'sending' WHERE id = :id")
    suspend fun markOutboxSending(id: String)

    /**
     * Drainer-owned rewrite of an outbox row's payload after attachment
     * upload: [attachmentsJson] records per-attachment upload state
     * (attachedSessionId / refText) so a retry after a failed submit skips
     * re-uploading; [contentJson] carries the text part rewritten to the
     * final submitted text (refs + fallback prompt) so the acked row
     * content-matches what was actually submitted.
     */
    @Query("UPDATE outbox SET contentJson = :contentJson, attachmentsJson = :attachmentsJson WHERE id = :id")
    suspend fun updateOutboxPayload(id: String, contentJson: String, attachmentsJson: String?)

    /**
     * Apply a failure outcome from the drainer: status flip + bookkeeping in
     * one update.  Caller computes attemptCount/nextAttemptAtMs/status per
     * the backoff policy in OutboxDrainer.
     */
    @Query("""
        UPDATE outbox
        SET status = :status,
            lastErrorMessage = :errorMsg,
            attemptCount = :attempts,
            nextAttemptAtMs = :nextAttempt
        WHERE id = :id
    """)
    suspend fun updateOutboxAttempt(
        id: String,
        status: String,
        errorMsg: String?,
        attempts: Int,
        nextAttempt: Long,
    )

    /** User-driven retry of a failed row from the chat UI. */
    @Query("""
        UPDATE outbox
        SET status = 'pending', attemptCount = 0, nextAttemptAtMs = 0, lastErrorMessage = NULL
        WHERE id = :id
    """)
    suspend fun resetOutboxForRetry(id: String)

    // -- Boot recovery -------------------------------------------------------
    //
    // Run unconditionally on every cold start (AppDatabase onOpen callback
    // or MarmaladeRuntime init), before the drainer or MessageStream are
    // touched. Both are idempotent: no-op when there's nothing to demote.

    /**
     * Any outbox row left in 'sending' on cold start is by definition stale —
     * the drainer coroutine that owned it died. Demote to 'pending' so the
     * drainer picks it up again; plugin-side idempotency_key handles the
     * possible duplicate prompt.submit cleanly (it returns {} as no-op).
     */
    @Query("UPDATE outbox SET status = 'pending' WHERE status = 'sending'")
    suspend fun demoteStaleSendingOutbox()

    /**
     * Any message row left isStreaming=true on cold start is by definition
     * orphaned — the stream that owned it died. Flip the flag so the row
     * renders as a finalized (possibly partial) message rather than a
     * perpetually-streaming bubble.
     */
    @Query("UPDATE messages SET isStreaming = 0 WHERE isStreaming = 1")
    suspend fun demoteStaleStreamingMessages()

    /**
     * Same demotion as [demoteStaleStreamingMessages], scoped to one session.
     * Used to reconcile client-side "still streaming" state against server
     * truth (`SessionRuntimeInfo.running`) after a reconnect or hydrate: if
     * the server reports the session is no longer running, any local row
     * still marked isStreaming=true is stale (the run finished server-side
     * while the client wasn't looking) and would otherwise be preserved
     * still marked isStreaming=true is stale (the run finished server-side
     * while the client wasn't looking).
     */
    @Query("UPDATE messages SET isStreaming = 0 WHERE isStreaming = 1 AND sessionKey = :sessionKey")
    suspend fun demoteStreamingMessagesForSession(sessionKey: String)

    // -- Prune ---------------------------------------------------------------

    /**
     * Auto-prune failed outbox rows older than the threshold (default 30
     * days). User-facing failed rows can also be removed via long-press →
     * Delete from the chat UI (deleteFromOutbox above).
     */
    @Query("DELETE FROM outbox WHERE status = 'failed' AND createdAtMs < :thresholdMs")
    suspend fun pruneFailedOutboxOlderThan(thresholdMs: Long)

    /**
     * Keep at most :keepLast gateway_events rows per session. Best-effort
     * ring buffer; the drainer of this is the diagnostic event-trace
     * machinery, which can run it after every Nth insert.
     */
    @Query("""
        DELETE FROM gateway_events
        WHERE sessionKey IS :sessionKey
        AND rowid NOT IN (
            SELECT rowid FROM gateway_events
            WHERE sessionKey IS :sessionKey
            ORDER BY receivedAtMs DESC
            LIMIT :keepLast
        )
    """)
    suspend fun pruneGatewayEventsKeepingLast(sessionKey: String?, keepLast: Int)

    // -- Gateway events: diagnostic ring buffer ------------------------------

    /** IGNORE: events stamped with a sessionKey we don't yet have a row for
     *  (startup window before session.create / refreshSessions reconciles)
     *  are intentionally dropped at the SQLite layer rather than surfacing
     *  as FK violations. See [MessageStream.recordToRingBuffer] for the
     *  diagnostic-only context. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGatewayEvent(row: GatewayEventEntity)

    // Ring-buffer read side: the Event Trace screen (Settings → Developer →
    // Event Trace, EventTraceViewModel) observes these. The one-shot variant
    // is also exercised by ChatDaoRoomFkTest's CASCADE checks. (Wired
    // 2026-07-03, closing dead-code audit item W2.)

    @Query("SELECT * FROM gateway_events WHERE sessionKey = :sessionKey OR (:sessionKey IS NULL AND sessionKey IS NULL) ORDER BY receivedAtMs DESC")
    suspend fun getGatewayEventsForSessionOnce(sessionKey: String?): List<GatewayEventEntity>

    @Query("SELECT * FROM gateway_events WHERE sessionKey = :sessionKey OR (:sessionKey IS NULL AND sessionKey IS NULL) ORDER BY receivedAtMs DESC LIMIT :limit")
    fun observeGatewayEventsForSession(sessionKey: String?, limit: Int): Flow<List<GatewayEventEntity>>

    @Query("SELECT * FROM gateway_events ORDER BY receivedAtMs DESC LIMIT :limit")
    fun observeRecentGatewayEvents(limit: Int): Flow<List<GatewayEventEntity>>

}
