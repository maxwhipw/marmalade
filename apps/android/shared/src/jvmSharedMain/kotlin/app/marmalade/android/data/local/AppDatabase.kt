package app.marmalade.android.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.GatewayEventEntity
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.OutboxEntity
import app.marmalade.android.data.local.entity.QueuedPromptEntity
import app.marmalade.android.data.local.entity.SessionEntity

/**
 * v29: the client-local FTS index is gone. DROPS the `messages_fts` FTS4 table
 * (and the triggers Room generated to keep it in sync with `messages`). Message
 * search is answered by the daemon's FTS5 index via `search.messages` — for
 * cross-session search AND find-in-conversation — so the local table had no
 * reader left while still costing a write on every message insert. The locked
 * rule is "the index lives on the daemon, never client-local Room FTS": FTS4
 * has no bm25, and a per-device index means results depend on which device
 * you're holding. Existing installs are wiped (destructive-migration policy
 * below), which is also what drops the table.
 *
 * v28: persisted context occupancy. Adds `sessions.contextUsed` /
 * `sessions.contextMax` (session.list `context_used`/`context_max`, additive
 * 2026-07-25) — the daemon's turn-end stamp of what occupies the model's
 * context window, mirrored at refresh like `archived`. Seeds the composer's
 * context donut on a COLD open (before this process sees a live
 * message.complete usage block). The percentage is deliberately NOT a column:
 * it is derived from the two (ContextOccupancy), the same way the daemon
 * derives it at read. Existing installs are wiped (destructive-migration
 * policy below).
 *
 * v27: archived sessions. Adds `sessions.archived` (session.list `archived` /
 * session.archive RPC, ratified 2026-07-23) — daemon-backed shared metadata,
 * adopted verbatim on refresh like `isMain`. The client filters archived rows
 * out of the main session list and surfaces them in an "Archived" section;
 * archived is NEVER a behavior filter (the session still runs/resumes/receives
 * cron). Existing installs are wiped (destructive-migration policy below).
 *
 * v26: singleton main session + cross-session origin. Adds `sessions.isMain`
 * (session.list `is_main` — THE daemon-managed main session, pinned to the top
 * of the list with a distinct chip, delete/stop hidden) and
 * `messages.originSource` (the message.user origin.source — "cron"/"agent"
 * turns get a distinct "scheduled"/"from session X" label; the existing
 * `originDeviceId` column is finally populated for the "from session X" text).
 * Both round-trip Room because the chat/session views derive entirely from
 * Room rows.
 *
 * v22: fork lineage. Adds `sessions.branchedFromId` — the source session_id a
 * session branched from (session.fork / session.list `branched_from`). Set
 * locally when a fork is created and mirrored from session.list at refresh
 * (server truth, so cross-device forks show it too). Drives the "branched
 * from …" session-row chip.
 *
 * v21: error round-trip. Adds `messages.error` — the terminal failure text of
 * an errored assistant turn (server `error` event / gateway-error completion
 * text). The chat view derives entirely from Room, so pre-v21 an errored
 * turn's truncated partial re-rendered as clean text after any Room rebuild
 * (cold load, replay REPLACE) — and the voice harvest's error guard
 * (harvestVoiceReply) could not reject it from TTS.
 *
 * v20: composer model chip. Adds `sessions.model` — the harness model id,
 * written by the picker (an unsent pick a deferred session.create will carry)
 * and mirrored from session.list's `model` at refresh. Seeds the chip on bind
 * so a materialized session shows its real model before session.info arrives.
 *
 * v19: the marmaladed stable-ids flip (identity plan P1/P4). Adds
 * `messages.serverSeq` (the daemon's per-session monotonic event seq — THE
 * ordering key + the session.subscribe replay cursor) and
 * `sessions.lastSeq`/`seenSeq` (unread = lastSeq > seenSeq, arithmetic, no
 * wall clocks) + `sessions.runState`/`lifecycle` (P2 split). Drops
 * `sessions.seenAtMs` — the wall-clock seen heuristic it powered is gone.
 *
 * v18: cross-client unread. Added `sessions.seenAtMs` — the gateway-side
 * seen-stamp (build.sh patch 4k) mirrored at refresh; drove the "New" chip.
 * Superseded by seq cursors in v19.
 *
 * v17: workspace picker. Adds `sessions.cwd` — the working directory chosen at
 * session-create time, passed to `session.create` so the gateway loads that
 * project's context files. Wiped on upgrade (destructive migration policy).
 *
 * v16: composer send-queue (parity P0). New composer_queue table — prompts
 * staged while a turn runs, drained on idle. FK CASCADE both ways to
 * sessions.key like outbox.
 *
 * v14: G1 (parity audit) adds FK + CASCADE to gateway_events.sessionKey →
 * sessions.key. sessionKey remains nullable (null FKs are valid SQLite and
 * allow diagnostic events that arrive before a session row exists). On session
 * rename (ON UPDATE CASCADE) or delete (ON DELETE CASCADE) the ring-buffer rows
 * follow automatically.
 *
 * Prior version history:
 *  - v24: messages.steered (session.steer, T2 #6) — marks a user row sent
 *    mid-turn so the bubble renders a "steered" marker. Round-trips Room
 *    because the chat view derives entirely from Room rows.
 *  - v13: K1 (parity audit) adds `ON UPDATE CASCADE` to the FKs on
 *    messages.sessionKey and outbox.sessionKey. The new ChatDao.renameSessionKey
 *    UPDATE relies on that cascade to relabel child rows in a single
 *    transaction (without it the FK check fires mid-rename and aborts).
 *  - v12: post-cleanup schema. Legacy sendStatus + idempotencyKey are
 *    gone from MessageEntity (the status lifecycle lives entirely on
 *    OutboxEntity); the v11 refactor's @Deprecated fields are dropped.
 *
 * Existing installs are wiped — no migration path is provided. Per the
 * ratified fresh-start policy (internal design note, not in this repo), the
 * fallbackToDestructiveMigration call (in the platform builders) is the
 * mechanism that enacts it, not a defensive fallback.
 *
 * ADR 0011 (KMP move): this @Database + DAO + entities live in the shared
 * KMP library's `jvmSharedMain` (both targets are JVM, so `java.*` in the
 * DAO/entity default args — `System.currentTimeMillis()` — needs no rewrite).
 * The `RoomDatabase.Builder` is platform-specific (Android needs a `Context`,
 * desktop needs a file path + `BundledSQLiteDriver`), so the builders live in
 * `androidMain` (`AppDatabase.getDatabase`) and `desktopMain`. `@ConstructedBy`
 * + the `expect object` below is the Room-KMP construction seam — the Room
 * compiler generates the `actual` per target.
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        GatewayEventEntity::class,
        QueuedPromptEntity::class,
    ],
    version = 29,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    // Empty on purpose: androidMain attaches the `getDatabase(Context)`
    // singleton accessor as an extension on this companion, so every existing
    // `AppDatabase.getDatabase(context)` call site is unchanged.
    companion object
}

/**
 * Room-KMP construction seam. The Room compiler generates the `actual object`
 * per target (kspAndroid / kspDesktop), so no hand-written actual exists —
 * hence the suppression.
 */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/**
 * Boot recovery run in each platform builder's `onOpen` callback:
 *  - any outbox row left 'sending' across a process boundary is stale (its
 *    drainer coroutine died) → demote to 'pending';
 *  - any messages row left isStreaming=true is orphaned (its stream died) →
 *    finalize it.
 * Both statements are idempotent (no-op when there's nothing to demote). Run
 * synchronously on the DB-open path, BEFORE any DAO method can execute its
 * first query — the critical ordering invariant that stops MessageStream from
 * writing a fresh isStreaming=true row the boot UPDATE then clobbers (race
 * scenario 5 from Reviewer Checkpoint 1). Shared here so the two platform
 * callbacks (SupportSQLiteDatabase on Android, SQLiteConnection on desktop)
 * can't drift.
 */
internal val BOOT_RECOVERY_STATEMENTS: List<String> = listOf(
    "UPDATE outbox SET status = 'pending' WHERE status = 'sending'",
    "UPDATE messages SET isStreaming = 0 WHERE isStreaming = 1",
)
