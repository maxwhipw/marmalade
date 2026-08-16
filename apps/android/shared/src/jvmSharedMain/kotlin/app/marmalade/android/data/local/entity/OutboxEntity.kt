package app.marmalade.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable queue of unsent user-prompt intents. Owned exclusively by the
 * outbox drainer + ChatController.sendMessage; promoted into MessageEntity
 * by ChatDao.ackOutboxAsMessage on RPC success. Cascades when its session
 * is deleted.
 *
 * status: "pending" | "sending" | "failed"
 */
@Entity(
    tableName = "outbox",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["key"],
            childColumns = ["sessionKey"],
            onDelete = ForeignKey.CASCADE,
            // K1: ON UPDATE CASCADE lets renameSessionKey propagate to
            // child outbox rows atomically inside a single transaction.
            onUpdate = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("sessionKey"),
        Index("sessionKey", "status", "nextAttemptAtMs"),
        Index("status", "createdAtMs"),
    ],
)
data class OutboxEntity(
    @PrimaryKey
    val id: String,
    val sessionKey: String,
    val serverSessionId: String? = null,
    val contentJson: String,
    val attachmentsJson: String? = null,
    val thinkingLevel: String = "off",
    val truncateBeforeUserOrdinal: Int? = null,
    val voiceOrigin: Boolean = false,
    val status: String = "pending",
    val attemptCount: Int = 0,
    val lastErrorMessage: String? = null,
    val nextAttemptAtMs: Long = 0L,
    val createdAtMs: Long,
    val clientOrdinal: Long,
)
