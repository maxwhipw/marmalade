package app.marmalade.android

/**
 * The Sessions list's row model.
 *
 * Lives in `commonMain` rather than beside SessionListViewModel because it is
 * the *shareable* half of that screen: pure data, no Android types, and the
 * input every pure grouping/derivation helper in `utils/` takes
 * (WorkspaceGroupUtils, SessionCategoryUtils, SessionKeyUtils, UnreadUtils).
 * The ViewModel that builds these stays in `:app` — see its class comment for
 * why — but a desktop session list will need this model and those helpers
 * verbatim.
 */
data class SessionUiModel(
    val id: String,
    val title: String,
    val createdAt: Long,
    val isGateway: Boolean,
    val lastPreview: String? = null,
    val unreadCount: Int = 0,
    val lastMessageAt: Long? = null,
    val agentId: String? = null,
    val category: String? = null,
    val isDeletable: Boolean = false,
    val isMuted: Boolean = false,
    val emoji: String? = null,
    val needsInput: Boolean = false,
    /** True when the gateway reports a turn is currently in flight for this
     *  session (parity row M3). Keyed off [SessionEntity.gatewaySessionId]
     *  (the live gateway id), not the local stored key. */
    val running: Boolean = false,
    /** True when the daemon reports run_state=awaiting_input (M2): a tool
     *  call is parked behind an approval and the agent is waiting on the maintainer.
     *  Drives the "Waiting" chip — rendered as idle pre-M2. */
    val awaitingInput: Boolean = false,
    /** True when the daemon reports `run_state=hung` — the run wedged. The
     *  state has been on the wire since P2 and was drawn NOWHERE until
     *  2026-07-26, so a hung session was indistinguishable from an idle one.
     *  Drives the red indicator (SessionStatusUtils). */
    val hung: Boolean = false,
    /** Server-reported origin: "tui", "cli", "cron", "telegram", "marmalade",
     *  or a custom `MARMALADE_SESSION_SOURCE` value. Retained as session
     *  metadata; no longer drives tab classification since the two-tab
     *  (Workspaces / Quick Chats) redesign. Null for pre-migration rows. */
    val source: String? = null,
    /** Workspace / working directory (gateway-side absolute path) — server
     *  truth from the REST session list. Drives the by-folder grouping. */
    val cwd: String? = null,
    /** True when the gateway reports activity newer than the last time ANY
     *  client showed the user this conversation (patch 4k seen_at +
     *  UnreadUtils). Deliberately NOT the client-local unreadCount — reading
     *  a reply on desktop clears this on Android too. Drives the "New" chip. */
    val serverUnread: Boolean = false,
    /** Source session_id this session branched from, or null (T2 #3 fork
     *  lineage). Drives the "branched from …" chip, linking back to the
     *  source row when it's still in the list. */
    val branchedFromId: String? = null,
    /** Workspace membership, derived server-side (session.list `workspace_id`).
     *  Null = "Quick sessions". Drives workspace grouping — trusted verbatim
     *  from the stamp, never re-derived from [cwd]. */
    val workspaceId: String? = null,
    /** THE daemon-managed singleton main session (session.list `is_main`).
     *  Pinned to the top of the list with a distinct chip; delete/stop hidden
     *  ([isDeletable] is forced false). Daemon-owned — the client never sets it. */
    val isMain: Boolean = false,
    /** Archived flag (session.list `archived`). Filtered OUT of the main list
     *  and surfaced in an "Archived" section instead; never contributes to
     *  unread badging. Daemon-backed shared metadata, never a behavior filter. */
    val archived: Boolean = false,
)
