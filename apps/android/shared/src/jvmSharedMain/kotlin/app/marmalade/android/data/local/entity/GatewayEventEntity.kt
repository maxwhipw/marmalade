package app.marmalade.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.marmalade.android.data.local.entity.SessionEntity

/**
 * Diagnostic ring buffer of raw gateway frames. Best-effort; not load-bearing.
 * Capped at ~500 rows per session via periodic prune. Surfaced through the
 * Settings → Debug → Event Trace screen.
 */
@Entity(
    tableName = "gateway_events",
    // sessionKey is nullable — null FKs are valid SQLite (they skip FK enforcement),
    // so events recorded before a session row exists (startup races, unscoped events)
    // are preserved rather than rejected.
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["key"],
            childColumns = ["sessionKey"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionKey", "receivedAtMs")],
)
data class GatewayEventEntity(
    @PrimaryKey(autoGenerate = true)
    val rowid: Long = 0,
    val sessionKey: String?,
    val type: String,
    val payloadJson: String,
    val receivedAtMs: Long,
)
