package app.marmalade.android.chat

/**
 * Lightweight value classes used by the chat surface that are NOT part of
 * the wire-protocol port. These belong to the UI / outgoing-payload layer
 * and survive the transition from OpenClaw → marmalade-agent untouched.
 *
 * Wire-shaped types (the things that come off the JSON-RPC events / get
 * persisted to Room) live in `chat/messages/` next to the message-stream
 * implementation.
 */

/**
 * The hardcoded key MarmaladeRuntime boots `_mainSessionKey` with before
 * `session.most_recent` answers on connect. It names NO real conversation —
 * binding it renders an empty phantom chat, and a message sent into it gets
 * `session.create`'d into a brand-new server session on reconnect (the
 * offline lost-message bug, 2026-07-03). Resolution layers
 * (HomeScreen.resolveAssistantSessionKey, the runtime's cache seed) treat
 * this value as "unknown" and prefer any real cached session over it.
 */
const val MAIN_SESSION_PLACEHOLDER = "main"

/** A row in the session sidebar / picker. */
data class ChatSessionEntry(
    val key: String,
    val updatedAtMs: Long?,
    val displayName: String? = null,
    val totalTokens: Long? = null,
    val contextTokens: Long? = null,
    val model: String? = null,
    /** Gateway-reported origin ("tui", "cli", "cron", "telegram", …).
     *  Sidebar tab classifier keys on this. Null for pre-migration rows. */
    val source: String? = null,
    /** Workspace / working directory (gateway-side absolute path). Local
     *  Room value; drives the by-folder session grouping. */
    val cwd: String? = null,
    /** P4 unread cursors (session.list last_seq / this device's seen_seq).
     *  Unread = lastSeq > seenSeq — arithmetic, no wall clocks. */
    val lastSeq: Long = 0L,
    val seenSeq: Long = 0L,
    /** P2 lifecycle/runState split ("active"/"ended"; "idle"/"running"/…). */
    val lifecycle: String? = null,
    val runState: String? = null,
    /** Fork lineage: the source session_id this branched from, or null
     *  (T2 #3). Drives the "branched from …" sidebar chip. */
    val branchedFromId: String? = null,
    /** Workspace membership, derived server-side (session.list `workspace_id`).
     *  Null = under no workspace ("Quick sessions"). Drives workspace grouping —
     *  trusted verbatim, never re-derived from cwd. */
    val workspaceId: String? = null,
    /** THE daemon-managed singleton main session (session.list `is_main`). Pins
     *  to the top of the list with a distinct chip; delete/stop hidden. */
    val isMain: Boolean = false,
    /** Archived flag (session.list `archived`). Daemon-backed shared metadata;
     *  the client hides archived rows from the main list and surfaces them in an
     *  "Archived" section. Never a behavior filter. */
    val archived: Boolean = false,
)

/** A model catalog row — drives the model-picker sheet. */
data class ModelCatalogEntry(
    val id: String,
    val name: String,
    val provider: String,
    val contextWindow: Long? = null,
    val description: String = "",
    /** The reasoning-effort bounds the daemon configured for this model
     *  (`model.list` effort_min/effort_max, 2026-07-27). Both null = unbounded,
     *  which is also every older daemon — the Thinking sheet then offers the
     *  full vocabulary, exactly as it did before the feature. */
    val effortMin: String? = null,
    val effortMax: String? = null,
)

/**
 * Composer attachment staged for send. The payload lives as a FILE under
 * `filesDir/attachments/` (staged at pick time by AttachmentStaging) — never
 * as inline base64: this type serializes into `outbox.attachmentsJson`, and
 * Room reads rows through a ~2 MB CursorWindow, so a multi-MB base64 body in
 * the row would crash every outbox query for the session.
 *
 * Upload happens at DRAIN time (OutboxDrainer), not send time, so attachments
 * survive the offline-queue path like the message text does. [kind] routes
 * the upload: "image" → `image.attach_bytes`, "file" → `file.attach`.
 *
 * [attachedSessionId]/[refText] are drainer-owned upload state, mirroring
 * desktop's `ComposerAttachment.attachedSessionId` dedup
 * (`use-prompt-actions.ts:464`): a retry after a failed submit skips
 * re-uploading anything already attached to the same live session id.
 */
@kotlinx.serialization.Serializable
data class OutgoingAttachment(
    val kind: String,
    val name: String,
    val mimeType: String,
    val path: String,
    val sizeBytes: Long,
    val attachedSessionId: String? = null,
    val refText: String? = null,
) {
    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_FILE = "file"
    }
}
