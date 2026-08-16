package app.marmalade.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.getDatabase
import app.marmalade.android.data.local.entity.SessionEntity
import app.marmalade.android.data.repository.ChatRepository
import app.marmalade.android.data.repository.getInstance
import app.marmalade.android.rpc.types.toForkShape
import app.marmalade.android.utils.SessionCategoryUtils
import app.marmalade.android.utils.SessionKeyUtils
import app.marmalade.android.utils.WorkspaceGroupUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * STAYS in `:app` — deliberately, and this was measured (desktop-client plan
 * Phase 1, option (b), 2026-07-25). Unlike the settings ViewModels and
 * EventTrace/Pairing, this one is not an RPC client with an Android wart: it
 * is a view over the Android runtime itself. It touches 14 distinct
 * `MarmaladeRuntime` members — including the `chat` and `terminal`
 * sub-controllers, which are large objects in their own right — plus
 * ChatNotificationHelper (per-session notification channels) and android.util
 * .Log.
 *
 * A port in the [DevicePairingHost][app.marmalade.android.rpc.DevicePairingHost]
 * style is the obvious idea and it does NOT work here: at 14+ members the
 * interface stops being a seam and becomes a mirror of the whole runtime,
 * which is exactly the failure mode that port's doc warns against. Extracting
 * `node/` instead is explicitly out of scope for Phase 1 ("resist extracting
 * Android-only subsystems for completeness").
 *
 * What WAS shareable has been moved out rather than left tangled here:
 * [SessionUiModel] and the four pure helpers it feeds (WorkspaceGroupUtils,
 * SessionCategoryUtils, SessionKeyUtils, UnreadUtils) now live in `:shared`
 * commonMain. A desktop session list can reuse the model and every derivation
 * rule; only this Android-runtime wiring is left behind, which is the correct
 * split. Revisit only if `node/` itself is ever ported.
 */
class SessionListViewModel(application: Application) : AndroidViewModel(application) {

    private val chatRepository = ChatRepository.getInstance(application)
    private val marmaladeRuntime = (application as MarmaladeApplication).marmaladeRuntime
    private val chatDao = AppDatabase.getDatabase(application).chatDao()

    // Delegate to SessionKeyUtils for testable classification logic
    private fun extractAgentId(key: String) = SessionKeyUtils.extractAgentId(key)
    private fun isDeletable(key: String) = SessionKeyUtils.isDeletable(key)

    /** Null until the combine below produces its first value. The screen needs
     *  to tell "not yet emitted" apart from "genuinely no sessions": the flow
     *  is cold until the Sessions tab first subscribes, and by then
     *  [sessionsSynced] is usually already true (the WS sync ran while the
     *  user was on Home) — so an emptyList() initial value would flash the
     *  no-sessions empty state for the first frame(s) of every process. */
    private val sessionsOrNull: StateFlow<List<SessionUiModel>?> = combine(
        marmaladeRuntime.chatSessions,
        marmaladeRuntime.mainSessionKey,
        chatRepository.allSessions,
        marmaladeRuntime.pendingPrompts,
        marmaladeRuntime.sessionRunning,
    ) { nodeEntries, _, localSessions, pendingPrompts, sessionRunning ->
        // Build lookups for local session state (gateway sessions also persisted locally)
        val localMap = localSessions.associateBy { it.key }
        val gatewayModels = nodeEntries.map { entry ->
            val (cat, title) = SessionCategoryUtils.parseSessionCategory(entry.displayName)
            val local = localMap[entry.key]
            // sessionRunning is keyed by the LIVE gateway session_id, not the stored key.
            // After K1 the stored key IS stored_session_id, while gatewaySessionId holds
            // the live id. Use gatewaySessionId for the lookup; fall back to false when
            // the local row hasn't been written yet (entry not yet in Room).
            val liveId = local?.gatewaySessionId
            SessionUiModel(
                id = entry.key,
                title = title,
                createdAt = entry.updatedAtMs ?: System.currentTimeMillis(),
                isGateway = true,
                lastPreview = extractAgentId(entry.key)?.let { "Agent: $it" },
                agentId = extractAgentId(entry.key),
                category = cat,
                // The main session is never deletable — the daemon refuses
                // session.delete/stop for it (it clears in place instead).
                isDeletable = isDeletable(entry.key) && !entry.isMain,
                isMain = entry.isMain,
                lastMessageAt = entry.updatedAtMs,
                isMuted = local?.isMuted ?: false,
                emoji = local?.emoji,
                needsInput = pendingPrompts.any { it.sessionKey == entry.key },
                // Live status.update pushes win (freshest); the session.list
                // run_state row is the fallback for sessions with no push yet.
                running = liveId?.let { sessionRunning[it] }
                    ?: (entry.runState == "running" || entry.runState == "starting"),
                awaitingInput = entry.runState == "awaiting_input",
                hung = entry.runState == "hung",
                source = entry.source ?: local?.source,
                cwd = entry.cwd ?: local?.cwd,
                // P4: unread is seq arithmetic — messages exist past this
                // device's read cursor. Cross-client by construction.
                serverUnread = app.marmalade.android.utils.UnreadUtils.isUnread(
                    lastSeq = entry.lastSeq,
                    seenSeq = entry.seenSeq,
                ),
                branchedFromId = entry.branchedFromId,
                workspaceId = entry.workspaceId,
                archived = entry.archived,
            )
        }
        val httpModels = localSessions.map { session ->
            val liveId = session.gatewaySessionId
            SessionUiModel(
                id = session.key,
                title = session.displayName?.takeIf { it.isNotBlank() }
                    ?: session.key.substringAfterLast(':'),
                createdAt = session.lastMessageAt ?: session.createdAt,
                isGateway = false,
                lastPreview = null,
                unreadCount = session.unreadCount,
                lastMessageAt = session.lastMessageAt,
                isDeletable = true,
                isMuted = session.isMuted,
                emoji = session.emoji,
                needsInput = pendingPrompts.any { it.sessionKey == session.key },
                running = liveId?.let { sessionRunning[it] } ?: false,
                source = session.source,
                cwd = session.cwd,
                workspaceId = session.workspaceId,
                archived = session.archived,
            )
        }

        // Deduplicate: gateway sessions are also persisted to Room by ChatController,
        // so the same key can appear in both lists. Prefer gateway model (has live data).
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<SessionUiModel>()
        for (m in gatewayModels) { seen.add(m.id); deduped.add(m) }
        for (m in httpModels) { if (seen.add(m.id)) deduped.add(m) }
        deduped.sortedByDescending { it.createdAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allSessions: StateFlow<List<SessionUiModel>> = sessionsOrNull
        .map { it ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True when the daemon advertises the "workspaces" hello feature. Gates
     *  the workspace grouping toggle + the New workspace menu item; when false
     *  the UI stays RECENT-only (old-daemon degradation). */
    val workspacesSupported: StateFlow<Boolean> = marmaladeRuntime.workspacesSupported

    /** True when the daemon advertises the "terminal" hello feature. Gates the
     *  Terminals tab + the workspace-detail terminal section. */
    val terminalSupported: StateFlow<Boolean> = marmaladeRuntime.terminalSupported

    /** The live terminal roster (TerminalController owns it — RPC + the
     *  terminal.exit prune). The screens filter by workspace_id. */
    val terminals: StateFlow<List<app.marmalade.android.rpc.types.TerminalInfo>> =
        marmaladeRuntime.terminal.terminals

    /** Refetch terminal.list. Safe on every tab/screen entry; the controller
     *  records transport errors instead of throwing. */
    fun refreshTerminals() {
        if (!marmaladeRuntime.terminalSupported.value) return
        viewModelScope.launch { marmaladeRuntime.terminal.refresh() }
    }

    /** Spawn a shell (cwd = workspace path, or null for a quick terminal) and
     *  hand back its id for navigation. */
    fun createTerminal(cwd: String?, onCreated: (terminalId: String) -> Unit) {
        viewModelScope.launch {
            val info = try {
                marmaladeRuntime.terminal.create(cwd = cwd)
            } catch (t: Throwable) {
                android.util.Log.w("SessionListVM", "createTerminal failed: ${t.message}")
                return@launch
            }
            onCreated(info.terminal_id)
        }
    }

    fun closeTerminal(terminalId: String) {
        viewModelScope.launch {
            try {
                marmaladeRuntime.terminal.close(terminalId)
            } catch (t: Throwable) {
                android.util.Log.w("SessionListVM", "closeTerminal failed: ${t.message}")
            }
        }
    }

    /** The daemon's workspaces (workspace.list), refreshed alongside sessions.
     *  Empty until the first successful fetch, or when unsupported. */
    private val _workspaces = MutableStateFlow<List<app.marmalade.android.rpc.types.WorkspaceInfo>>(emptyList())
    val workspaces: StateFlow<List<app.marmalade.android.rpc.types.WorkspaceInfo>> = _workspaces.asStateFlow()

    /** Refetch workspace.list. No-op (clears) when the feature is absent so a
     *  downgrade can't leave stale cards. Safe to call on every list entry. */
    fun refreshWorkspaces() {
        if (!marmaladeRuntime.workspacesSupported.value) {
            _workspaces.value = emptyList()
            return
        }
        viewModelScope.launch {
            _workspaces.value = try {
                withContext(Dispatchers.IO) { marmaladeRuntime.marmaladeRpc.workspaceList().workspaces }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.w("SessionListVM", "workspace.list failed: ${t.message}")
                _workspaces.value // keep the last good list on a transient failure
            }
        }
    }

    /** The Sessions layout: ordered workspace cards + the flat Quick-Chats
     *  bucket (folderless sessions). Membership comes from the DERIVED server
     *  stamp, never re-derived from cwd. The daemon-managed main/assistant
     *  session is excluded entirely — it lives on the Home tab, never in the
     *  Sessions list. Recomputes when sessions or the workspace list changes. */
    val workspaceLayout: StateFlow<WorkspaceGroupUtils.WorkspaceLayout> = combine(
        allSessions,
        _workspaces,
    ) { models, workspaces ->
        // Archived rows are filtered out here so they never appear in the main
        // list (cards + Quick Chats) NOR contribute to a card's unread rollup
        // (point 6). They're surfaced via [archivedSessions] in an "Archived"
        // section instead. The main/assistant session is excluded too — it
        // lives on the Home tab.
        WorkspaceGroupUtils.groupByWorkspace(
            models.filter { !it.isMain && !it.archived },
            workspaces,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        WorkspaceGroupUtils.WorkspaceLayout(emptyList(), emptyList()),
    )

    /** THE daemon-managed main session, or null before one is resolved. The
     *  drawer pins it above the workspaces (ADR 0013) — [workspaceLayout]
     *  deliberately excludes it, so it is surfaced separately rather than by
     *  re-filtering the layout at every call site. */
    val mainSession: StateFlow<SessionUiModel?> = allSessions
        .map { models -> models.firstOrNull { it.isMain } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Archived, non-main sessions (session.list `archived`). Drives the
     *  "Archived" sections: the Quick Chats tab shows folderless archived
     *  sessions; the workspace detail screen filters these by workspaceId so
     *  archived sessions stay reachable. Most-recent first. */
    val archivedSessions: StateFlow<List<SessionUiModel>> = allSessions
        .map { models ->
            models.filter { !it.isMain && it.archived }
                .sortedByDescending { it.lastMessageAt ?: it.createdAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Archive / unarchive a session (marmaladed `session.archive`). Optimistic
     *  local flip + revert-on-reject with a toast lives in [SessionListSync];
     *  the main session is never offered this (the daemon refuses it). */
    fun archiveSession(sessionKey: String, archived: Boolean) {
        marmaladeRuntime.chat.archiveSession(sessionKey, archived)
    }

    /** Read-only context peek for the detail screen (workspace.context).
     *  Fetched once on screen entry. Throws the daemon's error (unknown id) so
     *  the screen can drop the strip and log a WARN. */
    suspend fun workspaceContext(
        workspaceId: String,
    ): app.marmalade.android.rpc.types.WorkspaceContextResponse =
        withContext(Dispatchers.IO) { marmaladeRuntime.marmaladeRpc.workspaceContext(workspaceId) }

    /** Create a workspace over [path] (workspace.create) and refresh. Returns
     *  the created workspace on success, or throws the daemon's error message
     *  (duplicate / outside home / missing folder) for the caller to surface. */
    suspend fun createWorkspace(
        path: String,
        name: String? = null,
        emoji: String? = null,
    ): app.marmalade.android.rpc.types.WorkspaceInfo {
        val ws = withContext(Dispatchers.IO) {
            marmaladeRuntime.marmaladeRpc.workspaceCreate(path, name, emoji).workspace
        }
        refreshWorkspaces()
        return ws
    }

    /** Rename / re-emoji a workspace (workspace.update) and refresh. Pass
     *  [clearEmoji] = true to drop the emoji (wire null). */
    fun updateWorkspace(
        workspaceId: String,
        name: String? = null,
        emoji: String? = null,
        clearEmoji: Boolean = false,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    marmaladeRuntime.marmaladeRpc.workspaceUpdate(workspaceId, name, emoji, clearEmoji)
                }
                refreshWorkspaces()
            } catch (t: Throwable) {
                android.util.Log.w("SessionListVM", "workspace.update failed: ${t.message}")
            }
        }
    }

    /** Remove a workspace (workspace.delete — un-groups, sessions kept). Refresh
     *  both the workspace list AND the session list so the un-grouped sessions
     *  drop into Quick sessions on the next stamp. */
    fun deleteWorkspace(workspaceId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    marmaladeRuntime.marmaladeRpc.workspaceDelete(workspaceId)
                }
                refreshWorkspaces()
                refreshSessions()
            } catch (t: Throwable) {
                android.util.Log.w("SessionListVM", "workspace.delete failed: ${t.message}")
            }
        }
    }

    init {
        refreshSessions()
        refreshWorkspaces()
    }

    fun refreshSessions() {
        // Gateway-only mode: always refresh via node
        marmaladeRuntime.refreshChatSessions(limit = 100)
    }

    /** One-tap session create inside a workspace: cwd = the workspace path, no
     *  dialog, server auto-titles. Navigates straight into the new chat. */
    fun createSessionInWorkspace(workspacePath: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = try {
                marmaladeRuntime.createGatewaySession(title = "New Chat", cwd = workspacePath)
            } catch (t: Throwable) {
                android.util.Log.w("SessionListVM", "createSessionInWorkspace failed: ${t.message}")
                return@launch
            }
            refreshSessions()
            onCreated(id)
        }
    }

    /**
     * The gateway's default working directory, used to pre-select the
     * workspace picker. Returns null on any failure (offline / auth) — the
     * dialog then just omits the picker default and the server applies its own
     * default at create time.
     */
    suspend fun getDefaultWorkspace(): app.marmalade.android.rpc.types.DefaultCwdResponse? =
        try {
            // marmaladed fs.defaults (JSON-RPC), mapped onto the picker's
            // fork-era shape until Part D deletes the REST types.
            withContext(Dispatchers.IO) {
                app.marmalade.android.rpc.types.DefaultCwdResponse(
                    cwd = marmaladeRuntime.marmaladeRpc.fsDefaults().default_cwd,
                )
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c // dialog dismissed mid-fetch — propagate, don't log as failure
        } catch (t: Throwable) {
            android.util.Log.w("SessionListVM", "fs.defaults failed: ${t.message}")
            null
        }

    /** List gateway-side directory entries for the workspace picker
     *  (marmaladed fs.list — home-confined, realpath-resolved). Throws on
     *  transport failure or a confinement rejection (the picker surfaces it). */
    suspend fun browseWorkspace(path: String, showHidden: Boolean = false): app.marmalade.android.rpc.types.FsListResponse =
        withContext(Dispatchers.IO) {
            marmaladeRuntime.marmaladeRpc.fsList(path, showHidden).toForkShape()
        }

    fun createSession(name: String, isGateway: Boolean, agentId: String? = null, emoji: String? = null, cwd: String? = null, onCreated: (String, Boolean) -> Unit) {
        if (isGateway) {
            val effectiveName = name.trim().ifEmpty { "New Chat" }
            viewModelScope.launch {
                val id = try {
                    marmaladeRuntime.createGatewaySession(title = effectiveName, agentId = agentId, cwd = cwd)
                } catch (t: Throwable) {
                    // RPC failure — surface as a logged warning; don't navigate to a broken session.
                    android.util.Log.w("SessionListVM", "createGatewaySession failed: ${t.message}")
                    return@launch
                }
                if (!emoji.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        chatDao.updateSessionEmoji(id, emoji)
                    }
                }
                onCreated(id, true)
            }
        } else {
            viewModelScope.launch {
                val id = chatRepository.createSession(name.trim())
                if (!emoji.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        chatDao.updateSessionEmoji(id, emoji)
                    }
                }
                onCreated(id, false)
            }
        }
    }

    fun setUseNodeChat(useNodeChat: Boolean) {
        // Gateway-only mode: useNodeChat is always true, this is a no-op
    }

    fun renameSession(sessionKey: String, newName: String, isGateway: Boolean, emoji: String? = null) {
        if (isGateway) {
            viewModelScope.launch {
                marmaladeRuntime.patchChatSession(sessionKey, newName.trim())
                // Store emoji locally (gateway doesn't support it)
                if (emoji != null) {
                    withContext(Dispatchers.IO) {
                        val existing = chatDao.getSessionByKey(sessionKey)
                        if (existing != null) {
                            chatDao.updateSessionEmoji(sessionKey, emoji.ifBlank { null })
                        } else {
                            chatDao.insertSession(SessionEntity(key = sessionKey, emoji = emoji.ifBlank { null }))
                        }
                    }
                }
                marmaladeRuntime.refreshChatSessions()
            }
        } else {
            viewModelScope.launch {
                chatRepository.renameSession(sessionKey, newName.trim())
                withContext(Dispatchers.IO) {
                    chatDao.updateSessionEmoji(sessionKey, emoji?.ifBlank { null })
                }
            }
        }
    }

    fun deleteSession(sessionKey: String, isGateway: Boolean) {
        viewModelScope.launch {
            if (isGateway) {
                // Route through ChatController.deleteSession so that:
                //  - The RPC fires using the resolved gateway session_id (from
                //    keyToServerId, not the raw local key).
                //  - keyToServerId + MessageStream are cleaned up atomically.
                //  - If the deleted session is the currently-bound one, the
                //    controller automatically switches focus to "main".
                // This replaces the old two-step (deleteChatSession + chatRepository.deleteSession)
                // which left ChatController's in-memory state stale and never triggered navigation.
                marmaladeRuntime.chat.deleteSession(sessionKey)
            } else {
                // Non-gateway (local-only) sessions: no server RPC needed.
                chatRepository.deleteSession(sessionKey)
            }
            // Clean up the per-session notification channel regardless of session type.
            app.marmalade.android.notification.ChatNotificationHelper.deleteSessionChannel(
                getApplication(), sessionKey
            )
        }
    }

    /** Reset a session's conversation in place via `session.clear` (the main
     *  session's Clear affordance — it can't be deleted). The daemon wipes the
     *  transcript and broadcasts `session.cleared`; the local Room rows drop
     *  off that event (ChatEventRouter), so nothing local is removed here. Uses
     *  the resolved gateway id (post-K1 the local key == the server id). */
    fun clearSession(sessionKey: String) {
        viewModelScope.launch {
            val sid = withContext(Dispatchers.IO) {
                chatDao.getSessionByKey(sessionKey)?.gatewaySessionId
            } ?: sessionKey
            runCatching {
                withContext(Dispatchers.IO) { marmaladeRuntime.marmaladeRpc.sessionClear(sid) }
            }.onFailure {
                android.util.Log.w("SessionListVM", "session.clear failed: ${it.message}")
            }
        }
    }

    fun toggleMuteSession(sessionKey: String, isMuted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = chatDao.getSessionByKey(sessionKey)
            if (existing != null) {
                chatDao.updateSessionMuted(sessionKey, isMuted)
            } else {
                // Create a local session entity for gateway sessions that don't have one yet
                chatDao.insertSession(SessionEntity(key = sessionKey, isMuted = isMuted))
            }
            // Clean up channel if muting
            if (isMuted) {
                app.marmalade.android.notification.ChatNotificationHelper.deleteSessionChannel(
                    getApplication(), sessionKey
                )
            }
        }
    }
}
