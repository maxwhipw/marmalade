package app.marmalade.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Composer send-queue: prompts staged while a turn is running, drained into
 * [ChatController.sendMessage] (and thereby the outbox) when the session goes
 * idle. Deliberately NOT the outbox — outbox rows are committed sends that
 * fire eagerly (relying on the gateway's 4009 busy-refusal only as a
 * double-tap guard), render as transcript bubbles, and are owned by the
 * drainer. Queue rows are still-editable staging chips above the composer.
 * Desktop analogue: store/composer-queue.ts (localStorage-persisted).
 *
 * Keyed by the stable LOCAL session key, so live-id rotation across
 * reconnects can't strand entries (desktop needs migrateQueuedPrompts for
 * this; we don't).
 */
@Entity(
    tableName = "composer_queue",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["key"],
            childColumns = ["sessionKey"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionKey", "ordinal")],
)
data class QueuedPromptEntity(
    @PrimaryKey
    val id: String,
    val sessionKey: String,
    val text: String,
    /** JSON list of [app.marmalade.android.chat.OutgoingAttachment], same
     *  encoding as OutboxEntity.attachmentsJson. */
    val attachmentsJson: String? = null,
    val thinkingLevel: String = "off",
    val voiceOrigin: Boolean = false,
    val createdAtMs: Long,
    /** FIFO position within the session; "send now" promotes by setting
     *  ordinal below the current minimum. */
    val ordinal: Long,
)
