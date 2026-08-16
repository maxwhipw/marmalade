package app.marmalade.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.marmalade.android.ui.sessions.WorkspacePaths
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Compact top bar for the chat screen (~48dp).
 *
 * Exactly four things, in ADR 0013's scope order: the drawer handle (app
 * navigation), the title (tap = switch session), the panel glyph (views OF
 * this session), and ⋮ (actions ON this session).
 *
 * It used to carry a TTS toggle, a conversation-mode toggle and a settings
 * gear as well. All three moved: speak-replies and conversation mode are
 * session state and live in the ⋮ sheet; app settings live in the drawer's
 * bottom row ("never in a top bar" — ADR 0013's control map). Four trailing
 * buttons on a 48dp bar was the "pile of buttons" the ADR exists to fix.
 *
 * Deliberately carries NO connection chip/status: the cross-tab banner owns
 * "connecting/offline" and the Gateway tab badge owns the at-a-glance dot —
 * a third copy here was redundant noise (maintainer, 2026-07-03).
 */
@Composable
fun ChatTopBar(
    sessionName: String,
    showBackArrow: Boolean = false,
    /** Gateway-side working directory for this session; shown as a subtitle
     *  under the title. Null/blank → no subtitle. */
    workspacePath: String? = null,
    onBackPressed: (() -> Unit)? = null,
    onStatusClick: (() -> Unit)? = null,
    /** Opens the session menu (the per-chat sheet) — actions ON this session. */
    onSessionMenuClick: (() -> Unit)? = null,
    /** Opens the navigation drawer (ADR 0013: the drawer is the only
     *  navigator, so the top-left is its handle rather than a back arrow). */
    onMenuClick: (() -> Unit)? = null,
    /** Opens the session tool panel from the right edge (ADR 0013). */
    onPanelClick: (() -> Unit)? = null,
    /** Opens the session switcher (ADR 0013: the title bar IS the switcher).
     *  When set it takes the title tap and adds the caret affordance; when
     *  null the title falls back to [onStatusClick]. */
    onTitleClick: (() -> Unit)? = null,
) {
    val hapticFeedback = LocalHapticFeedback.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The drawer handle owns the top-left. A back arrow only appears
            // where there is no drawer to open (never, today) — system back
            // still pops, so a second affordance for it would be noise.
            if (onMenuClick != null) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open navigation drawer",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else if (showBackArrow && onBackPressed != null) {
                IconButton(
                    onClick = onBackPressed,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }

            // Title (tappable → gateway/status screen) with an optional
            // workspace subtitle. Tapping the subtitle toggles basename ↔ full
            // path (space is tight, so basename by default; reset per path so
            // a session switch doesn't inherit the expanded state).
            val workspace = workspacePath?.takeIf { it.isNotBlank() }
            var showFullPath by remember(workspace) { mutableStateOf(false) }
            // With the switcher wired, the whole title block is one tap target
            // and the path toggle steps aside — the sheet header shows the full
            // workspace path anyway, so two competing taps here bought nothing.
            val titleAction = onTitleClick ?: onStatusClick
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
                    .then(
                        if (onTitleClick != null) Modifier.clickable { onTitleClick() }
                        else Modifier
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sessionName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Weight (not fillMaxWidth) so the caret keeps its
                        // space and a long name ellipsizes before it.
                        modifier = Modifier
                            .weight(1f, fill = onTitleClick == null)
                            .then(
                                if (onTitleClick == null && titleAction != null) {
                                    Modifier.clickable { titleAction() }
                                } else {
                                    Modifier
                                }
                            ),
                    )
                    if (onTitleClick != null) {
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "Switch session",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (workspace != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (showFullPath) workspace
                            else WorkspacePaths.pathName(workspace),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onTitleClick != null) Modifier
                            else Modifier.clickable { showFullPath = !showFullPath },
                        )
                    }
                }
            }

            // Panel glyph — the right edge's handle, mirroring the drawer's.
            if (onPanelClick != null) {
                IconButton(
                    onClick = onPanelClick,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ViewSidebar,
                        contentDescription = "Open session panel",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // ⋮ — everything that acts on THIS session (ADR 0013). Haptic
            // because it was a haptic affordance before the consolidation and
            // the bar's other two buttons are navigation, not action.
            IconButton(
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSessionMenuClick?.invoke()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Session actions",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * In-chat search bar — replaces [ChatTopBar] while search mode is active
 * (opened from the settings sheet's "Search in chat" row). The query filters
 * the message list in [ChatScreen]; this bar owns only the input + match
 * count + close affordance. Auto-focuses so the keyboard opens immediately.
 *
 * [resultCount] is null when the query is blank (nothing to count yet).
 */
@Composable
fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int?,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close search",
                    modifier = Modifier.size(20.dp),
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search in chat",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }

            if (resultCount != null) {
                Text(
                    text = resultCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}
