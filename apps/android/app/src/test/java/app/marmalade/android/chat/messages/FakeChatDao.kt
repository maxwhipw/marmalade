package app.marmalade.android.chat.messages

import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.dao.SessionContextRow
import app.marmalade.android.data.local.entity.GatewayEventEntity
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.QueuedPromptEntity
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory digital twin of the production [ChatDao]. Intentionally mirrors
 * the Room semantics that matter for chat-correctness tests:
 *
 * - **Stateful storage.** insertMessage, insertOutbox, etc. mutate
 *   underlying maps; observe* flows re-emit on every write.
 * - **FK CASCADE on session DELETE.** insertSession uses Room's
 *   `OnConflictStrategy.REPLACE`, which in SQLite compiles to
 *   `INSERT OR REPLACE` = DELETE existing + INSERT. The DELETE fires the
 *   FK CASCADE on messages.sessionKey + outbox.sessionKey, wiping every
 *   row for that session. This fake reproduces that behavior so tests
 *   catch the bug class (see commit 5e23893 — every sendMessage call
 *   wiped chat history because of this).
 * - **updateSessionRow does NOT trigger CASCADE.** Mirrors the @Update
 *   semantics.
 * - **ackOutboxAsMessage atomicity.** The fake reflects the @Transaction
 *   semantics — both writes land before any observe* flow re-emits.
 *
 * Intentionally NOT mirrored: FTS rowid stability, Room invalidator
 * timing. Tests assert on observable outputs, not on emission count.
 */
internal open class FakeChatDao : ChatDao {

    /**
     * Single source-of-truth state snapshot. Wrapped in StateFlow so every
     * observe* method derives reactively. All write methods atomically
     * .update {} this — one mutation, one downstream re-emission.
     */
    private data class State(
        val sessions: Map<String, SessionEntity> = emptyMap(),
        val messages: List<MessageEntity> = emptyList(),
        val outbox: List<OutboxEntity> = emptyList(),
        val gatewayEvents: List<GatewayEventEntity> = emptyList(),
        val nextGatewayEventRowid: Long = 1L,
        val composerQueue: List<QueuedPromptEntity> = emptyList(),
    )

    private val state = MutableStateFlow(State())

    // ----- Test inspection ----------------------------------------------------

    /** Read the current full state snapshot — for assertions in tests. */
    fun snapshot(): Triple<Map<String, SessionEntity>, List<MessageEntity>, List<OutboxEntity>> =
        Triple(state.value.sessions, state.value.messages, state.value.outbox)

    /** Composer-queue rows — for assertions in tests. */
    fun queueSnapshot(): List<QueuedPromptEntity> = state.value.composerQueue

    // ----- Sessions -----------------------------------------------------------

    override suspend fun insertSession(session: SessionEntity) {
        state.update { st ->
            // INSERT OR REPLACE semantics: if the row exists, delete-then-insert,
            // which fires CASCADE on messages + outbox FKs. Production bug class:
            // every sendMessage rewrites the session row to update thinkingLevel,
            // unintentionally wiping all chat history (commit 5e23893).
            val replaced = st.sessions.containsKey(session.key)
            val messagesAfter = if (replaced) st.messages.filterNot { it.sessionKey == session.key } else st.messages
            val outboxAfter = if (replaced) st.outbox.filterNot { it.sessionKey == session.key } else st.outbox
            val queueAfter = if (replaced) st.composerQueue.filterNot { it.sessionKey == session.key } else st.composerQueue
            st.copy(
                sessions = st.sessions + (session.key to session),
                messages = messagesAfter,
                outbox = outboxAfter,
                composerQueue = queueAfter,
            )
        }
    }

    override suspend fun updateSessionRow(session: SessionEntity) {
        // @Update semantics: UPDATE in place, no DELETE, no CASCADE. Only the
        // sessions map mutates; messages + outbox untouched.
        state.update { st ->
            if (!st.sessions.containsKey(session.key)) return@update st
            st.copy(sessions = st.sessions + (session.key to session))
        }
    }

    override fun getAllSessions(): Flow<List<SessionEntity>> =
        state.map { it.sessions.values.sortedByDescending { s -> s.lastMessageAt ?: 0L } }

    override suspend fun getSessionByKey(key: String): SessionEntity? =
        state.value.sessions[key]

    override suspend fun deleteSession(key: String) {
        state.update { st ->
            st.copy(
                sessions = st.sessions - key,
                messages = st.messages.filterNot { it.sessionKey == key },
                outbox = st.outbox.filterNot { it.sessionKey == key },
                composerQueue = st.composerQueue.filterNot { it.sessionKey == key },
            )
        }
    }

    override suspend fun getAllSessionKeys(): List<String> = state.value.sessions.keys.toList()

    override suspend fun updateSessionMuted(sessionKey: String, isMuted: Boolean, now: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(sessions = st.sessions + (sessionKey to s.copy(isMuted = isMuted, updatedAt = now)))
        }
    }

    override suspend fun isSessionMuted(sessionKey: String): Boolean? =
        state.value.sessions[sessionKey]?.isMuted

    override fun observeSessionMuted(sessionKey: String): Flow<Boolean?> =
        state.map { it.sessions[sessionKey]?.isMuted }

    override suspend fun updateSessionLastMessage(key: String, timestamp: Long) {
        state.update { st ->
            val s = st.sessions[key] ?: return@update st
            st.copy(sessions = st.sessions + (key to s.copy(lastMessageAt = timestamp, updatedAt = timestamp)))
        }
    }

    override suspend fun updateSessionEmoji(sessionKey: String, emoji: String?, now: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(sessions = st.sessions + (sessionKey to s.copy(emoji = emoji, updatedAt = now)))
        }
    }

    override suspend fun incrementUnreadCount(sessionKey: String, now: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(sessions = st.sessions + (sessionKey to s.copy(unreadCount = s.unreadCount + 1, updatedAt = now)))
        }
    }

    override suspend fun resetUnreadCount(sessionKey: String, now: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(sessions = st.sessions + (sessionKey to s.copy(unreadCount = 0, updatedAt = now)))
        }
    }

    override suspend fun updateSessionDraft(sessionKey: String, text: String?, now: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(sessions = st.sessions + (sessionKey to s.copy(draftText = text, updatedAt = now)))
        }
    }

    override suspend fun getSessionDraft(sessionKey: String): String? =
        state.value.sessions[sessionKey]?.draftText

    override suspend fun updateSessionModel(sessionKey: String, model: String?, now: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(sessions = st.sessions + (sessionKey to s.copy(model = model, updatedAt = now)))
        }
    }

    override suspend fun updateSessionThinkingLevel(sessionKey: String, level: String, now: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(
                sessions = st.sessions +
                    (sessionKey to s.copy(thinkingLevel = level, updatedAt = now)),
            )
        }
    }

    override suspend fun updateSessionArchived(sessionKey: String, archived: Boolean, now: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(sessions = st.sessions + (sessionKey to s.copy(archived = archived, updatedAt = now)))
        }
    }

    override fun observeSessionContext(sessionKey: String): Flow<SessionContextRow?> =
        state.map { st ->
            st.sessions[sessionKey]?.let { SessionContextRow(it.contextUsed, it.contextMax) }
        }

    override suspend fun clearSessionContext(sessionKey: String, now: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(
                sessions = st.sessions + (
                    sessionKey to s.copy(contextUsed = null, contextMax = null, updatedAt = now)
                    ),
            )
        }
    }

    // ----- Messages -----------------------------------------------------------

    override suspend fun insertMessage(message: MessageEntity) {
        state.update { st ->
            // REPLACE on conflict (Room @Insert default for messages): if id
            // already exists, swap. No CASCADE here — the conflict is on
            // messages.id, not on a session FK.
            val others = st.messages.filterNot { it.id == message.id }
            st.copy(messages = others + message)
        }
    }

    override fun getMessagesForSession(sessionKey: String): Flow<List<MessageEntity>> =
        state.map { it.messages.filter { m -> m.sessionKey == sessionKey }.sortedBy { m -> m.timestampMs } }

    override suspend fun getMessagesForSessionOnce(sessionKey: String): List<MessageEntity> =
        state.value.messages.filter { it.sessionKey == sessionKey }.sortedBy { it.timestampMs }

    override suspend fun deleteMessagesForSession(sessionKey: String) {
        state.update { st -> st.copy(messages = st.messages.filterNot { it.sessionKey == sessionKey }) }
    }

    override suspend fun deleteMessagesFromOrdinal(sessionKey: String, fromOrdinal: Long) {
        state.update { st ->
            st.copy(messages = st.messages.filterNot {
                it.sessionKey == sessionKey && it.clientOrdinal >= fromOrdinal
            })
        }
    }

    override suspend fun getUserOrdinalClientOrdinal(sessionKey: String, userOrdinal: Int): Long? {
        return state.value.messages
            .filter { it.sessionKey == sessionKey && it.role == "user" }
            .sortedBy { it.clientOrdinal }
            .getOrNull(userOrdinal)
            ?.clientOrdinal
    }

    override suspend fun deleteMessage(messageId: String) {
        state.update { st -> st.copy(messages = st.messages.filterNot { it.id == messageId }) }
    }

    override suspend fun getMessageCount(sessionKey: String): Int =
        state.value.messages.count { it.sessionKey == sessionKey }

    override suspend fun getRecentMessagesOnce(sessionKey: String, limit: Int): List<MessageEntity> =
        state.value.messages.filter { it.sessionKey == sessionKey }
            .sortedByDescending { it.timestampMs }
            .take(limit)

    // ----- Outbox -------------------------------------------------------------

    override fun observeOutboxForSession(sessionKey: String): Flow<List<OutboxEntity>> =
        state.map { it.outbox.filter { o -> o.sessionKey == sessionKey }.sortedBy { o -> o.clientOrdinal } }

    override suspend fun getOutboxForSessionOnce(sessionKey: String): List<OutboxEntity> =
        state.value.outbox.filter { it.sessionKey == sessionKey }.sortedBy { it.clientOrdinal }

    override suspend fun getOutboxByIdOnce(outboxId: String): OutboxEntity? =
        state.value.outbox.firstOrNull { it.id == outboxId }

    override suspend fun getDueOutbox(now: Long): List<OutboxEntity> =
        state.value.outbox.filter { it.status == "pending" && it.nextAttemptAtMs <= now }
            .sortedBy { it.createdAtMs }

    override suspend fun getMaxOutboxOrdinal(sessionKey: String): Long? =
        state.value.outbox.filter { it.sessionKey == sessionKey }.maxOfOrNull { it.clientOrdinal }

    override suspend fun getMaxMessagesOrdinal(sessionKey: String): Long? =
        state.value.messages.filter { it.sessionKey == sessionKey }.maxOfOrNull { it.clientOrdinal }

    override suspend fun getMaxServerSeq(sessionKey: String): Long =
        state.value.messages.filter { it.sessionKey == sessionKey }.maxOfOrNull { it.serverSeq } ?: 0L

    override suspend fun messageExists(messageId: String): Boolean =
        state.value.messages.any { it.id == messageId }

    override suspend fun mergeSessionSeqCursors(sessionKey: String, lastSeq: Long, seenSeq: Long) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(
                sessions = st.sessions + (sessionKey to s.copy(
                    lastSeq = maxOf(s.lastSeq, lastSeq),
                    seenSeq = maxOf(s.seenSeq, seenSeq),
                )),
            )
        }
    }

    override suspend fun updateSessionRunState(sessionKey: String, lifecycle: String?, runState: String?) {
        state.update { st ->
            val s = st.sessions[sessionKey] ?: return@update st
            st.copy(sessions = st.sessions + (sessionKey to s.copy(lifecycle = lifecycle, runState = runState)))
        }
    }

    override suspend fun insertOutbox(row: OutboxEntity) {
        state.update { st ->
            if (st.outbox.any { it.id == row.id }) {
                error("FakeChatDao: outbox id collision for ${row.id} — production uses ABORT conflict strategy")
            }
            st.copy(outbox = st.outbox + row)
        }
    }

    override suspend fun deleteFromOutbox(outboxId: String) {
        state.update { st -> st.copy(outbox = st.outbox.filterNot { it.id == outboxId }) }
    }

    // ----- Composer send-queue -------------------------------------------------

    override fun observeQueueForSession(sessionKey: String): Flow<List<QueuedPromptEntity>> =
        state.map { st ->
            st.composerQueue.filter { it.sessionKey == sessionKey }
                .sortedWith(compareBy({ it.ordinal }, { it.createdAtMs }))
        }

    override suspend fun insertQueuedPrompt(row: QueuedPromptEntity) {
        state.update { st ->
            if (st.composerQueue.any { it.id == row.id }) {
                error("FakeChatDao: composer_queue id collision for ${row.id}")
            }
            st.copy(composerQueue = st.composerQueue + row)
        }
    }

    override suspend fun deleteQueuedPrompt(id: String) {
        state.update { st -> st.copy(composerQueue = st.composerQueue.filterNot { it.id == id }) }
    }

    override suspend fun getMaxQueueOrdinal(sessionKey: String): Long? =
        state.value.composerQueue.filter { it.sessionKey == sessionKey }.maxOfOrNull { it.ordinal }

    override suspend fun getMinQueueOrdinal(sessionKey: String): Long? =
        state.value.composerQueue.filter { it.sessionKey == sessionKey }.minOfOrNull { it.ordinal }

    override suspend fun setQueueOrdinal(id: String, ordinal: Long) {
        state.update { st ->
            st.copy(
                composerQueue = st.composerQueue.map {
                    if (it.id == id) it.copy(ordinal = ordinal) else it
                },
            )
        }
    }

    override suspend fun updateOutboxServerSessionId(outboxId: String, newSessionId: String) {
        state.update { st ->
            st.copy(outbox = st.outbox.map {
                if (it.id == outboxId) it.copy(serverSessionId = newSessionId) else it
            })
        }
    }

    override suspend fun resolveLocalKeyForGatewayId(gatewayId: String): String? {
        return state.value.sessions.values.firstOrNull { it.gatewaySessionId == gatewayId }?.key
    }

    override suspend fun renameSessionKey(oldKey: String, newKey: String): Int {
        var updated = 0
        state.update { st ->
            val row = st.sessions[oldKey] ?: return@update st
            updated = 1
            // Simulate ON UPDATE CASCADE: child messages + outbox follow.
            val renamedSession = row.copy(key = newKey)
            val sessions = (st.sessions - oldKey) + (newKey to renamedSession)
            val messages = st.messages.map {
                if (it.sessionKey == oldKey) it.copy(sessionKey = newKey) else it
            }
            val outbox = st.outbox.map {
                if (it.sessionKey == oldKey) it.copy(sessionKey = newKey) else it
            }
            st.copy(sessions = sessions, messages = messages, outbox = outbox)
        }
        return updated
    }

    override suspend fun markOutboxSending(id: String) {
        state.update { st ->
            val row = st.outbox.firstOrNull { it.id == id } ?: return@update st
            st.copy(outbox = st.outbox.filterNot { it.id == id } + row.copy(status = "sending"))
        }
    }

    override suspend fun updateOutboxPayload(id: String, contentJson: String, attachmentsJson: String?) {
        state.update { st ->
            val row = st.outbox.firstOrNull { it.id == id } ?: return@update st
            st.copy(
                outbox = st.outbox.filterNot { it.id == id } + row.copy(
                    contentJson = contentJson,
                    attachmentsJson = attachmentsJson,
                ),
            )
        }
    }

    override suspend fun updateOutboxAttempt(
        id: String, status: String, errorMsg: String?,
        attempts: Int, nextAttempt: Long,
    ) {
        state.update { st ->
            val row = st.outbox.firstOrNull { it.id == id } ?: return@update st
            st.copy(
                outbox = st.outbox.filterNot { it.id == id } + row.copy(
                    status = status,
                    lastErrorMessage = errorMsg,
                    attemptCount = attempts,
                    nextAttemptAtMs = nextAttempt,
                ),
            )
        }
    }

    override suspend fun resetOutboxForRetry(id: String) {
        state.update { st ->
            val row = st.outbox.firstOrNull { it.id == id } ?: return@update st
            st.copy(
                outbox = st.outbox.filterNot { it.id == id } + row.copy(
                    status = "pending",
                    attemptCount = 0,
                    nextAttemptAtMs = 0,
                    lastErrorMessage = null,
                ),
            )
        }
    }

    // ----- Boot recovery + prune ---------------------------------------------

    override suspend fun demoteStaleSendingOutbox() {
        state.update { st ->
            st.copy(outbox = st.outbox.map { if (it.status == "sending") it.copy(status = "pending") else it })
        }
    }

    override suspend fun demoteStaleStreamingMessages() {
        state.update { st ->
            st.copy(messages = st.messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it })
        }
    }

    override suspend fun demoteStreamingMessagesForSession(sessionKey: String) {
        state.update { st ->
            st.copy(
                messages = st.messages.map {
                    if (it.isStreaming && it.sessionKey == sessionKey) it.copy(isStreaming = false) else it
                },
            )
        }
    }

    override suspend fun pruneFailedOutboxOlderThan(thresholdMs: Long) {
        state.update { st ->
            st.copy(outbox = st.outbox.filterNot { it.status == "failed" && it.createdAtMs < thresholdMs })
        }
    }

    override suspend fun pruneGatewayEventsKeepingLast(sessionKey: String?, keepLast: Int) {
        state.update { st ->
            val relevant = st.gatewayEvents.filter { it.sessionKey == sessionKey }
            if (relevant.size <= keepLast) return@update st
            val keep = relevant.sortedByDescending { it.receivedAtMs }.take(keepLast).toSet()
            st.copy(gatewayEvents = st.gatewayEvents.filter { it.sessionKey != sessionKey || it in keep })
        }
    }

    // ----- Gateway events ring buffer ----------------------------------------

    override suspend fun insertGatewayEvent(row: GatewayEventEntity) {
        state.update { st ->
            st.copy(
                gatewayEvents = st.gatewayEvents + row.copy(rowid = st.nextGatewayEventRowid),
                nextGatewayEventRowid = st.nextGatewayEventRowid + 1,
            )
        }
    }

    override suspend fun getGatewayEventsForSessionOnce(sessionKey: String?): List<GatewayEventEntity> =
        state.value.gatewayEvents
            .filter { it.sessionKey == sessionKey }
            .sortedByDescending { it.receivedAtMs }

    override fun observeGatewayEventsForSession(sessionKey: String?, limit: Int): Flow<List<GatewayEventEntity>> =
        state.map { st ->
            st.gatewayEvents.filter { it.sessionKey == sessionKey }
                .sortedByDescending { it.receivedAtMs }
                .take(limit)
        }

    override fun observeRecentGatewayEvents(limit: Int): Flow<List<GatewayEventEntity>> =
        state.map { it.gatewayEvents.sortedByDescending { e -> e.receivedAtMs }.take(limit) }
}
