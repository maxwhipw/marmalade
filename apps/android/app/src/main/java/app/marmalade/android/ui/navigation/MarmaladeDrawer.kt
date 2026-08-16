package app.marmalade.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.android.SessionUiModel
import app.marmalade.android.rpc.types.TerminalInfo
import app.marmalade.android.ui.components.MarmaladeMenu
import app.marmalade.android.ui.components.MarmaladeMenuDivider
import app.marmalade.android.ui.components.MarmaladeMenuItem
import app.marmalade.android.ui.components.SessionStatusIndicator
import app.marmalade.android.ui.sessions.WorkspacePaths
import app.marmalade.android.ui.theme.Wordmark
import app.marmalade.android.ui.theme.marmaladeColors
import app.marmalade.android.utils.DrawerSectionUtils
import app.marmalade.android.utils.SessionStatus
import app.marmalade.android.utils.SessionStatusUtils
import app.marmalade.android.utils.WorkspaceGroupUtils

/**
 * The drawer — the app's ONLY navigator (ADR 0013).
 *
 * It replaced a bottom tab bar, a Sessions screen with its own view tabs, and
 * expanding workspace cards. Three navigators meant no control had an obvious
 * home; one navigator gives every control a scope: app-level buttons live in
 * the pinned bottom row, workspace-level actions on the workspace row's
 * overflow, session-level actions on the session row's overflow.
 *
 * Order is deliberate: the pinned main session (the cold-start surface) →
 * workspaces, only the current one expanded → Quick sessions → Terminals as a
 * TOP-LEVEL section. Terminals are never nested under a session because they
 * are not owned by one; each row carries its workspace so a shell started
 * during rapid multi-session work stays findable.
 *
 * Every row that names a thing you can act on carries a ⋯ overflow. That is
 * the rule the ADR's scope table implies — an action lives at the level of the
 * thing it changes — and it is why there is no separate "manage sessions"
 * surface.
 */
@Composable
fun MarmaladeDrawerContent(
    mainSession: SessionUiModel?,
    layout: WorkspaceGroupUtils.WorkspaceLayout,
    terminals: List<TerminalInfo>,
    archivedCount: Int,
    currentSessionKey: String?,
    currentWorkspaceId: String?,
    expandedWorkspaces: Set<String>,
    quickSessionsExpanded: Boolean,
    terminalsExpanded: Boolean,
    terminalSupported: Boolean,
    workspacesSupported: Boolean,
    onToggleWorkspace: (String) -> Unit,
    onToggleQuickSessions: () -> Unit,
    onToggleTerminals: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenWorkspace: (String) -> Unit,
    onNewSessionIn: (workspacePath: String?) -> Unit,
    onNewTerminalIn: (workspacePath: String?) -> Unit,
    onRenameSession: (key: String, currentTitle: String) -> Unit,
    onArchiveSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCloseTerminal: (String) -> Unit,
    onOpenArchived: () -> Unit,
    onSearch: () -> Unit,
    onNewWorkspace: () -> Unit,
    onSettings: () -> Unit,
    onDebug: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // The wordmark, not a title: this is the one place in the app that
        // names the product rather than the session, so it wears the brand
        // face (Momo Trust Display, lowercase, tracking 0 — design scheme v0).
        Text(
            text = "marmalade",
            fontFamily = Wordmark,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            color = MaterialTheme.marmaladeColors.wordmark,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 20.dp, top = 14.dp, bottom = 10.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            // The main session is pinned above everything: it is what the app
            // cold-starts into, so it must also be the first thing in the list
            // you navigate back to it with. It gets a card rather than a row
            // because it is not a peer of the sessions below it — it is the
            // one session that always exists.
            if (mainSession != null) {
                item(key = "main") {
                    DrawerMainSessionCard(
                        session = mainSession,
                        isCurrent = mainSession.id == currentSessionKey,
                        onClick = { onOpenSession(mainSession.id) },
                    )
                }
            }

            if (layout.cards.isNotEmpty()) {
                item(key = "ws-header") { DrawerSectionHeader("Workspaces") }
            }
            layout.cards.forEach { card ->
                val expanded = card.id in expandedWorkspaces
                item(key = "ws-${card.id}") {
                    DrawerWorkspaceRow(
                        card = card,
                        expanded = expanded,
                        terminalSupported = terminalSupported,
                        onToggle = { onToggleWorkspace(card.id) },
                        onOpenWorkspace = { onOpenWorkspace(card.id) },
                        onNewSession = { onNewSessionIn(card.workspace.path) },
                        onNewTerminal = { onNewTerminalIn(card.workspace.path) },
                    )
                }
                if (expanded) {
                    items(card.sessions, key = { "s-${it.id}" }) { session ->
                        DrawerSessionRow(
                            session = session,
                            isCurrent = session.id == currentSessionKey,
                            indent = true,
                            onClick = { onOpenSession(session.id) },
                            onRename = { onRenameSession(session.id, session.title) },
                            onArchive = { onArchiveSession(session.id) },
                            onDelete = { onDeleteSession(session.id) },
                        )
                    }
                    // Creating where you are looking beats hunting for the
                    // workspace overflow — the same create the ⋯ offers, one
                    // tap closer, inheriting this workspace's folder.
                    item(key = "ws-new-${card.id}") {
                        DrawerCreateRow(
                            label = "New session",
                            onClick = { onNewSessionIn(card.workspace.path) },
                        )
                    }
                }
            }

            if (layout.quickSessions.isNotEmpty()) {
                item(key = "quick-header") {
                    // Terminals had a + and this didn't (maintainer, 2026-07-25).
                    // Both are top-level sections you can add to, so both get
                    // the same affordance; null path = the daemon's own cwd.
                    DrawerSectionHeader(
                        title = "Quick sessions",
                        expanded = quickSessionsExpanded,
                        onToggle = onToggleQuickSessions,
                        collapsedCount = layout.quickSessions.size,
                        collapsedStatus = DrawerSectionUtils.sessionsStatus(layout.quickSessions),
                        action = {
                            IconButton(
                                onClick = { onNewSessionIn(null) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New quick session",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                items(
                    if (quickSessionsExpanded) layout.quickSessions else emptyList(),
                    key = { "q-${it.id}" },
                ) { session ->
                    DrawerSessionRow(
                        session = session,
                        isCurrent = session.id == currentSessionKey,
                        indent = false,
                        onClick = { onOpenSession(session.id) },
                        onRename = { onRenameSession(session.id, session.title) },
                        onArchive = { onArchiveSession(session.id) },
                        onDelete = { onDeleteSession(session.id) },
                    )
                }
            }

            if (terminalSupported) {
                item(key = "term-header") {
                    DrawerSectionHeader(
                        title = "Terminals",
                        // A count, not just a plus: the whole reason terminals
                        // are top-level is so a live shell can't get lost, and a
                        // count is what makes that visible with the section
                        // scrolled past — or collapsed. It counts shells that
                        // are WORKING, not shells that exist, so it agrees with
                        // the rows' own indicators (maintainer, 2026-07-26); with none
                        // busy it falls back to the plain roster size.
                        trailingLabel = terminals
                            .count {
                                SessionStatusUtils.forTerminal(
                                    it.last_active, System.currentTimeMillis(),
                                ) == SessionStatus.RUNNING
                            }
                            .takeIf { it > 0 }
                            ?.let { "$it WORKING" }
                            ?: terminals.size.takeIf { it > 0 }?.let { "$it OPEN" },
                        expanded = terminalsExpanded,
                        onToggle = onToggleTerminals,
                        action = {
                            IconButton(
                                onClick = { onNewTerminalIn(null) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New terminal",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                if (terminals.isEmpty() && terminalsExpanded) {
                    item(key = "term-empty") { DrawerHint("No terminals running") }
                }
                items(
                    if (terminalsExpanded) terminals else emptyList(),
                    key = { "t-${it.terminal_id}" },
                ) { terminal ->
                    DrawerTerminalRow(
                        terminal = terminal,
                        workspaceName = layout.cards
                            .firstOrNull { it.id == terminal.workspace_id }
                            ?.workspace?.name,
                        onClick = { onOpenTerminal(terminal.terminal_id) },
                        onClose = { onCloseTerminal(terminal.terminal_id) },
                    )
                }
            }

            // Archived isn't in the ADR's drawer list, but deleting the Sessions
            // screen took its only entry point with it — a row here keeps
            // archived sessions reachable instead of silently orphaned.
            if (archivedCount > 0) {
                item(key = "archived") {
                    DrawerLeafRow(
                        icon = Icons.Outlined.Archive,
                        label = "Archived ($archivedCount)",
                        onClick = onOpenArchived,
                    )
                }
            }

            // The bottom row is pinned, so the last list row would otherwise
            // sit flush against its divider.
            item(key = "tail") { Spacer(modifier = Modifier.height(8.dp)) }
        }

        HorizontalDivider()
        // App-scoped actions, pinned: they belong to no session and no
        // workspace, so they sit apart from the list rather than inside it.
        // The tint is what makes "apart" read as deliberate instead of as the
        // list having run out.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "messages", not "sessions": since 2026-07-27 this is full-text
            // search over message TEXT (daemon search.messages), not a session
            // title match.
            DrawerAppAction(Icons.Default.Search, "Search messages", onSearch)
            if (workspacesSupported) {
                DrawerAppAction(Icons.Outlined.CreateNewFolder, "New workspace", onNewWorkspace)
            }
            // No bare "+" here (maintainer, 2026-07-25): app-scoped, it couldn't say
            // WHERE the session would be created. Creation lives where the
            // destination is unambiguous — the workspace row's overflow, the
            // inline row under an expanded workspace, the Quick sessions
            // header, and the switcher sheet's footer.
            DrawerAppAction(Icons.Default.Settings, "Settings", onSettings)
            // The frame explorer is app-scoped like the rest of this row, and
            // it is the surface you reach for when something looks wrong — two
            // taps into Settings → Developer was too far to be useful then
            // (maintainer, 2026-07-26). It keeps its Settings row as well.
            DrawerAppAction(Icons.Outlined.BugReport, "Debug", onDebug)
        }
    }
}

/**
 * A section header, optionally collapsible (design lab `drawer-sections`
 * option C / ADR 0014).
 *
 * A collapsible header owns the SAME chevron the workspace rows use, on the
 * same x — the point of collapsing over a segmented switcher was that there is
 * nothing new to learn. The chevron slot is reserved even for the fixed
 * "Workspaces" header, so the three section labels stay on one line of sight.
 *
 * [collapsedCount] and [collapsedStatus] draw only while collapsed: expanded,
 * the rows themselves are the count, and a duplicated dot would just be noise.
 */
@Composable
private fun DrawerSectionHeader(
    title: String,
    trailingLabel: String? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    collapsedCount: Int? = null,
    collapsedStatus: SessionStatus = SessionStatus.IDLE,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded != null) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (expanded == false) {
            // The whole cost of collapsing is that the rows stop announcing
            // themselves; the header takes that job over while they're hidden.
            SectionStatusDot(collapsedStatus)
            if (collapsedCount != null && collapsedCount > 0) {
                Text(
                    text = collapsedCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
        if (trailingLabel != null) {
            Text(
                text = trailingLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.marmaladeColors.statusRunning,
            )
        }
        action?.invoke()
    }
}

/** The collapsed section's stand-in for its rows' status indicators — the same
 *  component, so a header can never disagree with the rows it is hiding. */
@Composable
private fun SectionStatusDot(status: SessionStatus) {
    if (status == SessionStatus.IDLE) return
    SessionStatusIndicator(status = status)
    Spacer(modifier = Modifier.width(2.dp))
}

@Composable
private fun DrawerHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 44.dp, top = 2.dp, bottom = 6.dp),
    )
}

/** Highlight for "you are here" — the same tint the switcher sheet uses. */
@Composable
private fun currentTint(isCurrent: Boolean): Color =
    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent

/**
 * The pinned main session. Deliberately the only card in the drawer: it is the
 * cold-start surface and the one session that cannot be deleted, so it reads as
 * a different kind of thing from the rows below it rather than as the first of
 * them.
 *
 * The peach gradient is the userBubble pair, which is the one warm accent the
 * theme guarantees legible ink for in BOTH modes (the avatarMain gradient is
 * near-black in dark, so rich-brown ink would vanish on it).
 */
@Composable
private fun DrawerMainSessionCard(
    session: SessionUiModel,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.marmaladeColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(colors.userBubble, colors.userBubbleGradientEnd),
                )
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = session.emoji ?: "🍊", fontSize = 17.sp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onUserBubble,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (isCurrent) "main session · you are here" else "main session",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onUserBubble.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusDot(session = session, onAccent = true)
    }
}

@Composable
private fun DrawerWorkspaceRow(
    card: WorkspaceGroupUtils.WorkspaceCard,
    expanded: Boolean,
    terminalSupported: Boolean,
    onToggle: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onNewSession: () -> Unit,
    onNewTerminal: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // No "current workspace" highlight (maintainer, 2026-07-25): only ONE thing in
    // the drawer is highlighted, and it is the session you are in. Two tinted
    // rows — one of them the container of the other — read as two selections.
    // The current workspace is still the one expanded by default; that is the
    // affordance it needs, not a fill.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowDown
            else Icons.Default.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        // A tile always, emoji or not: without a fallback the rows of a
        // workspace that has no emoji start at a different x than the ones
        // that do, and the list reads as broken rather than as sparse.
        WorkspaceAvatar(emoji = card.workspace.emoji, name = card.workspace.name)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.workspace.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val branch = card.workspace.detection.git_branch
            if (branch != null) {
                Text(
                    text = branch,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Collapsed workspaces hide their sessions, so an unread inside one
        // would be invisible without this.
        if (card.unreadCount > 0 && !expanded) {
            SessionStatusIndicator(status = SessionStatus.UNREAD)
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Workspace actions",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MarmaladeMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MarmaladeMenuItem(
                    label = "New session here",
                    icon = Icons.Default.Add,
                    emphasized = true,
                    onClick = { menuOpen = false; onNewSession() },
                )
                if (terminalSupported) {
                    MarmaladeMenuItem(
                        label = "New terminal here",
                        icon = Icons.Outlined.Terminal,
                        onClick = { menuOpen = false; onNewTerminal() },
                    )
                }
                MarmaladeMenuItem(
                    label = "Workspace settings",
                    icon = Icons.Default.Settings,
                    supporting = WorkspacePaths.pathName(card.workspace.path),
                    onClick = { menuOpen = false; onOpenWorkspace() },
                )
            }
        }
    }
}

/** Emoji if the workspace has one, its initial if not — same tile either way. */
@Composable
private fun WorkspaceAvatar(emoji: String?, name: String) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji != null) {
            Text(text = emoji, fontSize = 14.sp)
        } else {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun DrawerSessionRow(
    session: SessionUiModel,
    isCurrent: Boolean,
    indent: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 22.dp else 8.dp, end = 8.dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(currentTint(isCurrent))
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "You are here" as a bar, not just a tint: at drawer width the tint
        // alone is a very quiet signal, and this is the one row the user is
        // scanning for.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent
                ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // The dot leads every row so the titles align regardless of state —
        // a trailing dot left ragged text and made "running" easy to miss.
        StatusDot(session = session, onAccent = false)
        Spacer(modifier = Modifier.width(8.dp))
        session.emoji?.let {
            Text(text = it, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = session.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Session actions",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MarmaladeMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MarmaladeMenuItem(
                    label = "Rename",
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    onClick = { menuOpen = false; onRename() },
                )
                MarmaladeMenuItem(
                    label = "Archive",
                    icon = Icons.Outlined.Archive,
                    supporting = "Hides it from this list",
                    onClick = { menuOpen = false; onArchive() },
                )
                if (session.isDeletable) {
                    MarmaladeMenuDivider()
                    MarmaladeMenuItem(
                        label = "Delete",
                        icon = Icons.Outlined.Delete,
                        destructive = true,
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/**
 * The row's status indicator. The legend and its reasoning live on
 * [SessionStatus]; this is only the plumbing.
 *
 * Idle now draws NOTHING (maintainer, 2026-07-26) — but the component still occupies
 * its full width, which is what keeps every title on one left edge instead of
 * sliding sideways the moment a session starts running.
 */
@Composable
private fun StatusDot(session: SessionUiModel, onAccent: Boolean) {
    SessionStatusIndicator(
        status = SessionStatusUtils.forSession(session),
        // On the peach main-session card the plain green loses contrast; the
        // card's own ink carries the unread dot there instead.
        accentTint = if (onAccent) MaterialTheme.marmaladeColors.onUserBubble else null,
    )
}

@Composable
private fun DrawerCreateRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 8.dp, top = 1.dp, bottom = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 17.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DrawerTerminalRow(
    terminal: TerminalInfo,
    workspaceName: String?,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.inverseSurface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ">_",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = terminal.shell.ifBlank { "shell" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The workspace is the whole point of this row: it is what makes a
            // shell findable after you've forgotten where you started it.
            Text(
                text = workspaceName ?: WorkspacePaths.pathName(terminal.cwd),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Working shells wear the same indicator a running session wears; a
        // shell parked at a prompt draws nothing (maintainer, 2026-07-26). Every row
        // here is alive by definition, so "alive" needs no glyph — only "busy"
        // does. Recomputed per composition, which is when the roster refreshes.
        SessionStatusIndicator(
            status = SessionStatusUtils.forTerminal(
                lastActive = terminal.last_active,
                now = System.currentTimeMillis(),
            ),
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Terminal actions",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MarmaladeMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MarmaladeMenuItem(
                    label = "Close terminal",
                    icon = Icons.Outlined.Delete,
                    supporting = "Ends the shell process",
                    destructive = true,
                    onClick = { menuOpen = false; onClose() },
                )
            }
        }
    }
}

@Composable
private fun DrawerLeafRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DrawerAppAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
