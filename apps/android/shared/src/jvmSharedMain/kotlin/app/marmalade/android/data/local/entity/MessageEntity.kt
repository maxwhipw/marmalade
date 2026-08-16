package app.marmalade.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["key"],
            childColumns = ["sessionKey"],
            onDelete = ForeignKey.CASCADE,
            // K1: ON UPDATE CASCADE lets a single UPDATE on sessions.key
            // (ChatDao.renameSessionKey) propagate to child rows. Without
            // it the FK check fires mid-rename and the transaction aborts.
            onUpdate = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionKey"), Index("timestampMs")]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val sessionKey: String,                 // FK to sessions.key
    val role: String,                       // "user" | "assistant" | "system" | "tool" | "subagent"
    val contentJson: String,                // JSON array of ChatMessageContent objects
    val timestampMs: Long = System.currentTimeMillis(),
    // Server-authoritative timestamp (the daemon's `ts`, epoch ms) when the
    // row was bound to a prompt.submit ack. Display metadata only — seq
    // orders (serverSeq below); timestampMs keeps the local send position.
    val serverTimestampMs: Long? = null,
    val isStreaming: Boolean = false,        // True between message.start and message.complete
    // Monotonic per-session ordinal assigned at outbox enqueue (carried to
    // messages on ack) or 0 for server-originated rows. Tiebreaker for UI
    // sort when timestampMs collides.
    val clientOrdinal: Long = 0L,
    // Delta-arrival ordinal for the active stream. Lets a mid-stream crash
    // resume render the partial without races against PersistenceCoordinator.
    val streamSeq: Int = 0,
    // Server-minted per-session event seq (marmaladed identity plan P1) of
    // the event that created/finalized this row. THE ordering key for
    // server-originated rows AND the session.subscribe replay cursor
    // (since_seq = MAX(serverSeq)). 0 = local-only row (outbox echo the
    // server never acked, system chrome) — those sort by timestamp after
    // all seq-bearing rows. seq orders; timestamps are metadata.
    val serverSeq: Long = 0L,
    // FK back to the parent assistant message for subagent-originated rows.
    val parentMessageId: String? = null,
    // Multi-device: null = this device; populated from the message.user
    // event's origin.deviceId. For a source="agent" turn this is
    // "session:<sender>" — the id of the session that sent the cross-session
    // prompt (rendered as "from session X"). Must round-trip Room: the chat
    // view derives entirely from Room rows.
    val originDeviceId: String? = null,
    // The message.user event's origin.source: "text" | "voice" | "cron" |
    // "agent" | … (daemon-minted, never spoofable). "cron"/"agent" turns get a
    // distinct label on the user bubble (like a scheduled/cross-session turn);
    // "voice" is also mirrored to [voiceOrigin]. Null on pre-flag rows. Must
    // round-trip Room — the chat view derives entirely from Room rows (v26).
    val originSource: String? = null,
    val replyToId: String? = null,
    @ColumnInfo(name = "voice_origin", defaultValue = "0")
    val voiceOrigin: Boolean = false,
    // Terminal failure text for an errored assistant turn (ChatMessage.error:
    // server `error` event / gateway-error completion text / approval denied).
    // Must round-trip through Room: the chat view is derived from Room rows,
    // so dropping it re-rendered errored turns as clean text after a rebuild —
    // and the voice harvest's error guard would never see it (v21).
    val error: String? = null,
    // session.fork cut availability (daemon has_cut_point on message.complete).
    // Nullable tri-state like ChatMessage.hasCutPoint: null = unknown/legacy
    // (offer the branch affordance), false = hide it. Must round-trip Room —
    // the chat view derives entirely from Room rows (v23).
    val hasCutPoint: Boolean? = null,
    // A user message sent mid-turn via session.steer (T2 #6) — renders a
    // "steered" marker. Must round-trip Room: the chat view derives entirely
    // from Room rows, so dropping it would lose the marker after a rebuild
    // (v24). Set from the message.user payload steered:true (replay/other
    // devices) and from the steer ack on the sending device.
    val steered: Boolean = false,
    // Original wire-protocol JSON that produced this message; populated by
    // ingest seams in MessageStream. (Pre-v29 this noted its exclusion from the
    // local FTS4 index; that index is gone — search is the daemon's job.)
    val rawPayloadJson: String? = null,
)
