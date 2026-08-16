package app.marmalade.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val key: String,                        // Gateway session key (e.g., "main", "agent:claude:chat-20260315")
    val displayName: String? = null,        // Human-readable name
    val agentId: String? = null,            // Agent ID if agent session
    val category: String? = null,           // Parsed from key prefix (telegram, discord, etc.) -- Phase 3
    val lastMessageAt: Long? = null,        // For sort order
    val unreadCount: Int = 0,               // Badge count -- Phase 3
    val thinkingLevel: String = "off",      // Per-session thinking level -- Phase 2
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val gatewaySessionId: String? = null,   // Gateway-side session ID
    val ttsEnabled: Boolean = false,        // Per-session TTS toggle -- Phase 2
    val isMuted: Boolean = false,           // Per-session notification mute -- Phase 5
    val emoji: String? = null,              // Client-side session emoji/avatar
    val draftText: String? = null,          // UI-2: unsent input persisted per session (Drafts)
    // Origin marker mirrored from the gateway's session row (`JsonRpcSessionRow
    // .source`). Populated by refreshSessions; drives sidebar tab grouping via
    // SourceUtils. Values include "tui", "cli", "cron", "telegram", "marmalade",
    // custom `MARMALADE_SESSION_SOURCE` env values, or null for pre-migration rows.
    val source: String? = null,
    // Workspace / working directory picked at session-create time (gateway-side
    // absolute path). Passed to `session.create` so the gateway loads that
    // project's context files (AGENTS.md / CLAUDE.md / .marmalade.md). Null =
    // the gateway's default cwd. Preserved so a lazy re-materialization of the
    // row (ensureServerSessionId) carries the same workspace.
    val cwd: String? = null,
    // Highest server-minted message seq the daemon reports for this session
    // (session.list `last_seq`). With [seenSeq] this makes unread pure
    // arithmetic: unread = lastSeq > seenSeq. 0 = nothing known.
    val lastSeq: Long = 0L,
    // This device's per-session read cursor (session.list `seen_seq` /
    // session.seen RPC): the highest seq this device has rendered.
    // Cross-client by construction — the daemon keys cursors per
    // (device, session), and submitting IS seeing.
    val seenSeq: Long = 0L,
    // P2 lifecycle/runState split, from session.list rows + status.update
    // events. lifecycle: "active" | "ended"; runState: "starting" | "idle" |
    // "running" | "awaiting_input" | "hung". Null = not yet reported.
    val lifecycle: String? = null,
    val runState: String? = null,
    // Harness model id (plain id, no provider — e.g. "claude-opus-4-8").
    // Two writers: the user's picker choice (setCurrentModel — the unsent
    // pick a deferred session.create will carry) and the daemon's
    // session.list `model` field mirrored at refresh (server value wins
    // when reported). Seeds the composer chip on bind so a materialized
    // session shows its real model before session.info arrives. Null =
    // harness default / not yet reported.
    val model: String? = null,
    // Fork lineage (T2 #3): the source session_id this session branched from
    // (session.list `branched_from.session_id`), or null. Set locally when a
    // fork is created and mirrored from session.list on refresh (server truth,
    // so cross-device forks show the chip too). Drives the "branched from …"
    // session-row chip. Stable metadata — a session's lineage never changes.
    val branchedFromId: String? = null,
    // Workspace membership, DERIVED server-side by cwd-prefix match and stamped
    // on each session.list row (`workspace_id`). Unlike [cwd] this is NOT local
    // truth — it's overwritten from every refresh (never preserved-on-null),
    // because adding/removing a workspace re-groups sessions and a removed
    // workspace must clear (→ Quick sessions). Null = under no workspace.
    val workspaceId: String? = null,
    // THE daemon-managed singleton main session (session.list `is_main`,
    // assistant plan 2026-07-19). Server truth, adopted verbatim on every
    // refresh (like [workspaceId], never preserved-on-null): the main session
    // pins to the top of the list with a distinct chip, hides delete/stop, and
    // offers Clear/model instead. Daemon-owned — the client never sets it.
    val isMain: Boolean = false,
    // Archived flag (session.list `archived`, session.archive RPC, ratified
    // 2026-07-23). Daemon-backed shared metadata, adopted verbatim on every
    // refresh (like [isMain]/[workspaceId], never preserved-on-null). NEVER a
    // behavior filter — an archived session still runs/resumes/receives cron;
    // the client just hides it from the main list and surfaces it in an
    // "Archived" section. Written optimistically on the archive/unarchive tap
    // and reverted if the RPC rejects.
    val archived: Boolean = false,
    // Persisted context occupancy (session.list `context_used`/`context_max`,
    // additive 2026-07-25). Daemon truth stamped at turn end, adopted verbatim
    // on every refresh (like [archived]/[isMain] — INCLUDING null, which is what
    // a session.clear leaves behind). Seeds the composer's context donut on a
    // COLD open, before this process has seen a live message.complete usage
    // block. Null in either column = unknown → no donut, never a fabricated
    // number. The percentage is NOT stored: it is derived from these two
    // (ContextOccupancy) so the formula has one home, exactly as the daemon
    // derives it at read.
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
)
