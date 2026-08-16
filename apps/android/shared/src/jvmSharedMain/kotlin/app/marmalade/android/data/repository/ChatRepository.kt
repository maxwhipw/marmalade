package app.marmalade.android.data.repository

import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

/**
 * Thin session/message repository over [ChatDao]. Moved to the shared KMP
 * library's `jvmSharedMain` alongside the Room store it wraps (ADR 0011,
 * increment 3c): it holds no Android types, only a [ChatDao] + JVM `java.util`
 * helpers, so both targets share it as-is. The `getInstance(Context)` singleton
 * (androidMain) supplies the DAO from `AppDatabase.getDatabase`; the desktop
 * shell will supply one from `buildDesktopDatabase(path).chatDao()`.
 */
class ChatRepository(private val chatDao: ChatDao) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Empty on purpose: androidMain attaches the `getInstance(Context)` singleton
    // accessor as an extension on this companion, so the call site is unchanged.
    companion object

    // Sessions
    val allSessions: Flow<List<SessionEntity>> = chatDao.getAllSessions()

    suspend fun createSession(title: String = "New Conversation"): String {
        val key = java.util.UUID.randomUUID().toString()
        val session = SessionEntity(key = key, displayName = title)
        chatDao.insertSession(session)
        return key
    }

    suspend fun deleteSession(sessionKey: String) {
        chatDao.deleteSession(sessionKey)
    }

    suspend fun renameSession(sessionKey: String, newName: String) {
        val existing = chatDao.getSessionByKey(sessionKey) ?: return
        // Must use updateSessionRow (@Update), not insertSession (REPLACE) —
        // REPLACE compiles to DELETE+INSERT and the DELETE fires the FK
        // CASCADE on messages.sessionKey / outbox.sessionKey, wiping all
        // chat history for the session. See ChatDao.updateSessionRow's doc
        // comment and the 5e23893 regression this mirrors.
        chatDao.updateSessionRow(existing.copy(displayName = newName, updatedAt = System.currentTimeMillis()))
    }

    suspend fun getSessionByKey(key: String): SessionEntity? {
        return chatDao.getSessionByKey(key)
    }

    // Messages
    fun getMessages(sessionKey: String): Flow<List<MessageEntity>> {
        return chatDao.getMessagesForSession(sessionKey)
    }
}
