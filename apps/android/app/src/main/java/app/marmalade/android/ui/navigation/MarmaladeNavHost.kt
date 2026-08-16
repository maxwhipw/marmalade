package app.marmalade.android.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import app.marmalade.android.ui.panel.SessionToolPanel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import app.marmalade.android.node.ConnectionPhase
import app.marmalade.android.ui.theme.marmaladeColors
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.node.MarmaladeRuntime
import app.marmalade.android.ui.chat.ChatScreen
import kotlinx.coroutines.launch
import app.marmalade.android.ui.chat.friendlySessionName
import app.marmalade.android.ui.home.HomeScreen
import app.marmalade.android.ui.settings.AppInfoScreen
import app.marmalade.android.ui.settings.AppearanceScreen
import app.marmalade.android.ui.settings.CreditsScreen
import app.marmalade.android.ui.settings.DeveloperSettingsScreen
import app.marmalade.android.ui.settings.LicensesScreen
import app.marmalade.android.ui.settings.SettingsMainScreen
import app.marmalade.android.ui.settings.AssistantSettingsScreen
import app.marmalade.android.ui.settings.ConnectionSettingsScreen
import app.marmalade.android.ui.settings.McpSettingsScreen
import app.marmalade.android.ui.settings.PairingScreen
import app.marmalade.android.ui.settings.PluginsSettingsScreen
import app.marmalade.android.ui.settings.CronSettingsScreen
import app.marmalade.android.ui.settings.UsageSettingsScreen
import app.marmalade.android.ui.settings.ModelsSettingsScreen
import app.marmalade.android.ui.settings.SkillsSettingsScreen
import app.marmalade.android.ui.settings.SpeechRecognitionScreen
import app.marmalade.android.SessionListViewModel
import app.marmalade.android.utils.DrawerSectionUtils
import app.marmalade.android.utils.SessionSwitcherUtils
import app.marmalade.android.notification.ChatNotificationHelper
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/**
 * Duration shared by the NavHost's horizontal route slide and the bottom
 * bar's vertical hide/show. Sessions → chat is *one* motion; running the two
 * halves on different clocks is what read as janky.
 */
private const val NAV_ANIM_MS = 200

/**
 * Route constants for the settings sub-navigation graph.
 * Extracted for testability without Android context.
 */
object SettingsRoutes {
    const val MAIN = "settings/main"
    const val APPEARANCE = "settings/appearance"
    const val VOICE = "settings/voice" // Deprecated alias — redirects to ASSISTANT
    const val SPEECH_RECOGNITION = "settings/speech_recognition"
    const val ASSISTANT = "settings/assistant"
    const val CONNECTION = "settings/connection"
    const val PAIRING = "settings/pairing"
    const val MODELS = "settings/models"
    const val SKILLS = "settings/skills"
    const val SCHEDULED = "settings/scheduled"
    const val USAGE = "settings/usage"
    const val MCP = "settings/mcp"
    const val PLUGINS = "settings/plugins"
    const val DEVELOPER = "settings/developer"
    const val EVENT_TRACE = "settings/event_trace"
    const val APP_INFO = "settings/app_info"
    const val CREDITS = "settings/credits"
    const val LICENSES = "settings/licenses"
    val ALL = listOf(MAIN, APPEARANCE, VOICE, SPEECH_RECOGNITION, ASSISTANT, CONNECTION, PAIRING, MODELS, SKILLS, SCHEDULED, USAGE, MCP, PLUGINS, DEVELOPER, EVENT_TRACE, APP_INFO, CREDITS, LICENSES)
}

/**
 * Main app composable: the navigation drawer wrapping a Scaffold + NavHost.
 *
 * ADR 0013 deleted the bottom navigation bar and the Sessions screen — the
 * drawer is the only navigator, the title bar is the session switcher, and the
 * app always shows a session. Every route change is a push or a pop, so the
 * slide direction no longer depends on any tab order (see [slideDirectionFor]).
 */
@Composable
fun MarmaladeNavHost(
    marmaladeRuntime: MarmaladeRuntime,
    currentThemeMode: String = "system",
    onThemeModeChange: (String) -> Unit = {},
    currentThemePreset: String = "",
    onThemePresetChange: (String) -> Unit = {},
    navigateToSessionKey: MutableState<String?>? = null,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Activity-scoped (this call site sits above the NavHost, so the store
    // owner is the Activity): the Sessions list, the workspace detail screen
    // and the title-bar switcher share ONE instance, and therefore one
    // workspace/session/terminal roster instead of three refetching copies.
    // Step 2's drawer needs the same app-wide roster.
    val sessionListViewModel: SessionListViewModel = viewModel()

    // The title-bar session switcher (ADR 0013 step 1). Hoisted above the
    // NavHost so the same sheet serves Home and every session-detail route,
    // and so it isn't torn down by the navigation it triggers.
    var switcherOpen by remember { mutableStateOf(false) }
    var showNewWorkspaceSheet by remember { mutableStateOf(false) }
    // Rename target for the drawer's session overflow (key to current title).
    // Hoisted here rather than inside the drawer content because the dialog has
    // to outlive the drawer closing under it.
    var drawerRenameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val allChatSessions by marmaladeRuntime.chatSessions.collectAsStateWithLifecycle()

    // No snackbar host — errors surface via the inline banner inside
    // ChatScreen (and the gateway shutdown banner for shutdown hints).
    // The snackbar was doubling-up on every gateway hiccup and stacking
    // on top of the banner, which the user explicitly rejected.

    // Handle notification deep-link navigation (cold-start and warm-start)
    val pendingSessionKey = navigateToSessionKey?.value
    LaunchedEffect(pendingSessionKey) {
        if (pendingSessionKey != null) {
            navController.navigate(MarmaladeDestination.sessionDetailRoute(pendingSessionKey)) {
                launchSingleTop = true
            }
            navigateToSessionKey?.value = null
        }
    }

    // Detect keyboard visibility so the chat composer owns the bottom inset.
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isKeyboardOpen = imeInsets.getBottom(density) > 0

    // ── The drawer: the app's only navigator (ADR 0013) ──────────────────────
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    fun closeDrawer() = drawerScope.launch { drawerState.close() }

    // The session tool panel rides the RIGHT edge (ADR 0013): the left belongs
    // to the drawer, and one edge cannot own two surfaces. Compose has no
    // right-hand drawer, so the sheet is mirrored by flipping layout direction
    // for the drawer machinery only — its CONTENT is flipped back, or every
    // row inside would render right-to-left.
    val panelState = rememberDrawerState(DrawerValue.Closed)

    val drawerLayout by sessionListViewModel.workspaceLayout.collectAsStateWithLifecycle()
    val drawerTerminals by sessionListViewModel.terminals.collectAsStateWithLifecycle()
    val drawerMainSession by sessionListViewModel.mainSession.collectAsStateWithLifecycle()
    val drawerArchived by sessionListViewModel.archivedSessions.collectAsStateWithLifecycle()
    val drawerTerminalSupported by sessionListViewModel.terminalSupported.collectAsStateWithLifecycle()
    val drawerWorkspacesSupported by sessionListViewModel.workspacesSupported.collectAsStateWithLifecycle()
    val boundSessionKey by marmaladeRuntime.chatSessionKey.collectAsStateWithLifecycle()
    val currentWorkspaceId = remember(drawerLayout, drawerTerminals, boundSessionKey) {
        SessionSwitcherUtils.currentWorkspaceId(
            layout = drawerLayout,
            terminals = drawerTerminals,
            currentSessionKey = boundSessionKey,
        )
    }
    // Collapse state is per workspace id and USER-owned once touched. The
    // default (only the current workspace expanded) applies to workspaces the
    // user hasn't explicitly toggled, so navigating into a session doesn't
    // silently re-collapse one they opened by hand.
    val toggledWorkspaces = remember { mutableStateMapOf<String, Boolean>() }
    val expandedWorkspaces = remember(drawerLayout, currentWorkspaceId, toggledWorkspaces.toMap()) {
        drawerLayout.cards
            .map { it.id }
            .filter { id -> toggledWorkspaces[id] ?: (id == currentWorkspaceId) }
            .toSet()
    }
    // The two top-level sections collapse too (design lab `drawer-sections`
    // option C / ADR 0014), on the same user-owns-it-once-touched rule as the
    // workspaces above: null means "no opinion yet", so the default in
    // DrawerSectionUtils still tracks app state (Terminals re-opens when a
    // shell starts), and a toggle freezes it.
    var quickSectionToggle by remember { mutableStateOf<Boolean?>(null) }
    var terminalsSectionToggle by remember { mutableStateOf<Boolean?>(null) }
    val quickSessionsExpanded = DrawerSectionUtils.quickSessionsExpanded(quickSectionToggle)
    val terminalsExpanded =
        DrawerSectionUtils.terminalsExpanded(terminalsSectionToggle, drawerTerminals.size)
    // Refresh what the drawer shows whenever it opens — the roster goes stale
    // while the user sits in a chat.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            sessionListViewModel.refreshSessions()
            sessionListViewModel.refreshWorkspaces()
            sessionListViewModel.refreshTerminals()
        }
    }

    val panelSessionName = allChatSessions.firstOrNull { it.key == boundSessionKey }
        ?.displayName
        ?: boundSessionKey?.let(::friendlySessionName)
        ?: "Session"

    fun openSession(key: String) {
        navController.navigate(MarmaladeDestination.sessionDetailRoute(key)) {
            popUpTo(MarmaladeDestination.SESSION_DETAIL_ROUTE) { inclusive = true }
            launchSingleTop = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || currentRoute != MarmaladeDestination.TERMINAL_DETAIL_ROUTE,
        drawerContent = {
            // Narrower than Material's 360dp default: at 360 on a Pixel the
            // sheet covers ~88% of the screen and the scrim stops reading as
            // "the app is still behind this".
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                MarmaladeDrawerContent(
                    mainSession = drawerMainSession,
                    layout = drawerLayout,
                    terminals = drawerTerminals,
                    archivedCount = drawerArchived.size,
                    currentSessionKey = boundSessionKey,
                    currentWorkspaceId = currentWorkspaceId,
                    expandedWorkspaces = expandedWorkspaces,
                    quickSessionsExpanded = quickSessionsExpanded,
                    terminalsExpanded = terminalsExpanded,
                    terminalSupported = drawerTerminalSupported,
                    workspacesSupported = drawerWorkspacesSupported,
                    onToggleWorkspace = { id ->
                        toggledWorkspaces[id] = !(toggledWorkspaces[id] ?: (id == currentWorkspaceId))
                    },
                    onToggleQuickSessions = { quickSectionToggle = !quickSessionsExpanded },
                    onToggleTerminals = { terminalsSectionToggle = !terminalsExpanded },
                    onOpenSession = { key -> closeDrawer(); openSession(key) },
                    onOpenTerminal = { id ->
                        closeDrawer()
                        navController.navigate(MarmaladeDestination.terminalDetailRoute(id))
                    },
                    onOpenWorkspace = { id ->
                        closeDrawer()
                        navController.navigate(MarmaladeDestination.workspaceDetailRoute(id))
                    },
                    onNewSessionIn = { path ->
                        closeDrawer()
                        if (path != null) {
                            sessionListViewModel.createSessionInWorkspace(path) { openSession(it) }
                        } else {
                            sessionListViewModel.createSession(name = "New Chat", isGateway = true) { key, _ ->
                                openSession(key)
                            }
                        }
                    },
                    onNewTerminalIn = { path ->
                        closeDrawer()
                        sessionListViewModel.createTerminal(path) { id ->
                            navController.navigate(MarmaladeDestination.terminalDetailRoute(id))
                        }
                    },
                    // Session-scoped actions. The drawer stays OPEN for these —
                    // they act on a row in the list you are reading, and closing
                    // it would hide the result of your own action.
                    onRenameSession = { key, title -> drawerRenameTarget = key to title },
                    onArchiveSession = { key ->
                        sessionListViewModel.archiveSession(key, true)
                    },
                    onDeleteSession = { key ->
                        sessionListViewModel.deleteSession(key, isGateway = true)
                    },
                    onCloseTerminal = { id -> sessionListViewModel.closeTerminal(id) },
                    onOpenArchived = {
                        closeDrawer()
                        navController.navigate(MarmaladeDestination.ARCHIVED_ROUTE)
                    },
                    onSearch = {
                        closeDrawer()
                        navController.navigate(MarmaladeDestination.SEARCH_ROUTE)
                    },
                    onNewWorkspace = { closeDrawer(); showNewWorkspaceSheet = true },
                    onSettings = {
                        closeDrawer()
                        navController.navigate(MarmaladeDestination.Settings.route)
                    },
                    onDebug = {
                        closeDrawer()
                        navController.navigate(MarmaladeDestination.Debugging.route)
                    },
                )
            }
        },
    ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    ModalNavigationDrawer(
        drawerState = panelState,
        // Opened by the top-bar glyph only: an edge swipe here would fight the
        // chat's horizontal gestures and the left drawer's.
        gesturesEnabled = panelState.isOpen,
        drawerContent = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                // Mirror of DrawerDefaults.shape. That default rounds the END
                // corners, and the content here is re-provided as LTR (so the
                // panel itself reads left-to-right) — which put the radius on
                // the off-screen right edge and left the visible left edge
                // square. Round the START corners instead so the panel matches
                // the left drawer (maintainer, 2026-07-26).
                ModalDrawerSheet(
                    modifier = Modifier.width(330.dp),
                    drawerShape = MaterialTheme.shapes.large.copy(
                        topEnd = CornerSize(0.dp),
                        bottomEnd = CornerSize(0.dp),
                    ),
                ) {
                    SessionToolPanel(
                        runtime = marmaladeRuntime,
                        chat = marmaladeRuntime.chat,
                        sessionName = panelSessionName,
                    )
                }
            }
        },
    ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Scaffold(
    ) { innerPadding ->
        // When keyboard is open, imePadding() inside ChatScreen handles the bottom.
        // Zero out Scaffold's bottom padding to avoid double-counting the nav bar.
        val layoutDirection = LocalLayoutDirection.current
        val adjustedPadding = if (isKeyboardOpen) {
            PaddingValues(
                top = innerPadding.calculateTopPadding(),
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = 0.dp,
            )
        } else {
            innerPadding
        }

        // Cross-tab disconnection banner. The OpenClaw manual-host gating
        // is gone; under marmalade, the endpoint is configured via
        // SettingsRepository (the new ConnectScreen in task #13 writes to
        // it). Retry triggers a reconnect on the existing JsonRpcClient.
        val isGatewayConnected by marmaladeRuntime.isConnected.collectAsStateWithLifecycle()
        val gatewayConnectionStatus by marmaladeRuntime.connectionStatus.collectAsStateWithLifecycle()
        // The banner is for "configured-but-down" — not "never set up". A user
        // who hasn't entered a dashboard URL yet should head to Settings →
        // Connection; flashing a red retry banner at them is wrong. Read the chat
        // creds directly off SecurePrefs (via the runtime's exposed flows) so
        // SettingsRepository.isConfigured() doesn't have to know about them.
        val dashboardUrlValue by marmaladeRuntime.dashboardUrl.collectAsStateWithLifecycle()
        val dashboardTokenValue by marmaladeRuntime.dashboardToken.collectAsStateWithLifecycle()
        val isGatewayConfigured =
            dashboardUrlValue.isNotBlank() && dashboardTokenValue.isNotBlank()

        Column(modifier = Modifier.padding(adjustedPadding)) {
            AnimatedVisibility(
                visible = isGatewayConfigured && !isGatewayConnected,
                enter = fadeIn(animationSpec = tween(300)) + expandVertically(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300)) + shrinkVertically(animationSpec = tween(300)),
            ) {
                DisconnectionBanner(
                    status = gatewayConnectionStatus,
                    onRetryClick = { marmaladeRuntime.connectMarmalade() },
                )
            }

        NavHost(
            navController = navController,
            startDestination = MarmaladeDestination.Home.route,
            enterTransition = {
                slideIntoContainer(
                    towards = slideDirectionFor(isPop = false),
                    animationSpec = navSlideSpec(),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = slideDirectionFor(isPop = false),
                    animationSpec = navSlideSpec(),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = slideDirectionFor(isPop = true),
                    animationSpec = navSlideSpec(),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = slideDirectionFor(isPop = true),
                    animationSpec = navSlideSpec(),
                )
            },
        ) {
            composable(MarmaladeDestination.Home.route) {
                HomeScreen(
                    marmaladeRuntime = marmaladeRuntime,
                    onStatusClick = {
                        navController.navigate(SettingsRoutes.CONNECTION)
                    },
                    onTitleClick = { switcherOpen = true },
                    onMenuClick = { drawerScope.launch { drawerState.open() } },
                    onPanelClick = { drawerScope.launch { panelState.open() } },
                )
            }
            // Cross-session full-text search (drawer bottom row — search is an
            // app-scoped control, ADR 0013 §5). Daemon-backed since 2026-07-27:
            // `search.messages` over the daemon's FTS5 index, replacing the
            // client-local Room FTS4 title/message search that used to live
            // here. Client-local was rejected by design — no bm25, and results
            // would depend on which device you were holding.
            composable(MarmaladeDestination.SEARCH_ROUTE) {
                val searchSupported by marmaladeRuntime.searchSupported
                    .collectAsStateWithLifecycle()
                // The pre-daemon ~/.claude/projects corpus is its own feature —
                // a daemon can have the FTS sidecar without having indexed it.
                val archiveSupported by marmaladeRuntime.searchArchiveSupported
                    .collectAsStateWithLifecycle()
                val searchViewModel: app.marmalade.android.ui.search.SearchViewModel = viewModel(
                    // Keyed on support so a reconnect that gains (or loses) the
                    // feature rebuilds the VM instead of holding a stale gate.
                    key = "message-search:$searchSupported:$archiveSupported",
                    factory = app.marmalade.android.ui.search.SearchViewModel.factory(
                        rpc = marmaladeRuntime.marmaladeRpc,
                        supported = searchSupported,
                        archiveSupported = archiveSupported,
                    ),
                )
                app.marmalade.android.ui.search.MessageSearchScreen(
                    viewModel = searchViewModel,
                    // Open the session AT the matched message (lab 3 frame 1).
                    // The anchor rides the singleton ChatController rather than
                    // the route: the target row usually isn't in Room yet, so
                    // the request has to outlive navigation and resolve when
                    // hydration replays it. sessionKey == the daemon session id
                    // (.claude/rules/session-ids.md rule 4), so the same string
                    // is both the anchor's key and the route argument.
                    onOpenSession = { anchor ->
                        marmaladeRuntime.chat.requestAnchor(anchor)
                        navController.popBackStack()
                        navController.navigate(
                            MarmaladeDestination.sessionDetailRoute(anchor.sessionKey)
                        )
                    },
                    // An archive hit goes to the READ-ONLY viewer and search
                    // stays on the back stack — you are browsing history, so
                    // "back to the results" is the expected next move, unlike
                    // opening a live session where you've arrived somewhere.
                    onOpenArchiveTranscript = { archiveSessionId ->
                        navController.navigate(
                            MarmaladeDestination.archiveTranscriptRoute(archiveSessionId)
                        )
                    },
                    onClose = { navController.popBackStack() },
                )
            }
            // Read-only transcript for one pre-daemon archive session
            // (`search.archive`). Reached ONLY from an archive search hit —
            // there is no resume for these ids, so there is no other way in and
            // deliberately no composer once you're here.
            composable(
                route = MarmaladeDestination.ARCHIVE_TRANSCRIPT_ROUTE,
                arguments = listOf(
                    navArgument("archiveSessionId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val archiveSessionId = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("archiveSessionId").orEmpty(), "UTF-8",
                )
                val archiveSupported by marmaladeRuntime.searchArchiveSupported
                    .collectAsStateWithLifecycle()
                val archiveViewModel:
                    app.marmalade.android.ui.search.ArchiveTranscriptViewModel = viewModel(
                    key = "archive-transcript:$archiveSessionId:$archiveSupported",
                    factory = app.marmalade.android.ui.search.ArchiveTranscriptViewModel.factory(
                        rpc = marmaladeRuntime.marmaladeRpc,
                        archiveSessionId = archiveSessionId,
                        supported = archiveSupported,
                    ),
                )
                app.marmalade.android.ui.search.ArchiveTranscriptScreen(
                    viewModel = archiveViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            // Archived sessions — hidden from the drawer's lists, so this is
            // their only home now that the Sessions screen is gone.
            composable(MarmaladeDestination.ARCHIVED_ROUTE) {
                app.marmalade.android.ui.sessions.ArchivedSessionsScreen(
                    viewModel = sessionListViewModel,
                    onBack = { navController.popBackStack() },
                    onSessionClick = { key ->
                        navController.navigate(MarmaladeDestination.sessionDetailRoute(key))
                    },
                )
            }
            composable(
                route = MarmaladeDestination.WORKSPACE_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument("workspaceId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val workspaceId = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("workspaceId").orEmpty(), "UTF-8",
                )
                app.marmalade.android.ui.sessions.WorkspaceDetailScreen(
                    viewModel = sessionListViewModel,
                    workspaceId = workspaceId,
                    onBack = { navController.popBackStack() },
                    onSessionClick = { sessionKey ->
                        navController.navigate(
                            MarmaladeDestination.sessionDetailRoute(sessionKey)
                        )
                    },
                    onTerminalClick = { terminalId ->
                        navController.navigate(
                            MarmaladeDestination.terminalDetailRoute(terminalId)
                        )
                    },
                )
            }
            // The route always exists so a stale back-stack entry can't crash
            // navigation; the bottom-bar entry that points here is gated on
            // Settings → Developer → Frame explorer reaches this.
            composable(MarmaladeDestination.Debugging.route) {
                app.marmalade.android.ui.debugging.DebuggingScreen(
                    marmaladeRuntime = marmaladeRuntime,
                    onBack = { navController.popBackStack() },
                )
            }
            navigation(
                startDestination = SettingsRoutes.MAIN,
                route = MarmaladeDestination.Settings.route,
            ) {
                composable(SettingsRoutes.MAIN) {
                    SettingsMainScreen(
                        onNavigate = { navController.navigate(it) },
                    )
                }
                composable(SettingsRoutes.APPEARANCE) {
                    AppearanceScreen(
                        onBack = { navController.popBackStack() },
                        currentThemeMode = currentThemeMode,
                        onThemeModeChange = onThemeModeChange,
                        currentThemePreset = currentThemePreset,
                        onThemePresetChange = onThemePresetChange,
                    )
                }
                composable(SettingsRoutes.SPEECH_RECOGNITION) {
                    SpeechRecognitionScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.ASSISTANT) {
                    AssistantSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                // Deprecated VOICE route — redirect to ASSISTANT for backward compat
                composable(SettingsRoutes.VOICE) {
                    AssistantSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.CONNECTION) {
                    ConnectionSettingsScreen(
                        marmaladeRuntime = marmaladeRuntime,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.MODELS) {
                    // The daemon owns these defaults; the feature flag decides
                    // whether the screen can WRITE them or only display them.
                    val settingsSupported by marmaladeRuntime.settingsSupported
                        .collectAsStateWithLifecycle()
                    ModelsSettingsScreen(
                        onBack = { navController.popBackStack() },
                        settingsSupported = settingsSupported,
                    )
                }
                composable(SettingsRoutes.SKILLS) {
                    SkillsSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.SCHEDULED) {
                    CronSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.USAGE) {
                    UsageSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.MCP) {
                    McpSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.PLUGINS) {
                    PluginsSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.DEVELOPER) {
                    DeveloperSettingsScreen(
                        onBack = { navController.popBackStack() },
                        // Keep the hoisted nav-bar state in sync so the Debug
                        // tab appears / disappears the moment the user toggles.
                        onNavigate = { navController.navigate(it) },
                    )
                }
                composable(SettingsRoutes.EVENT_TRACE) {
                    app.marmalade.android.ui.debugging.EventTraceScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.PAIRING) {
                    PairingScreen(
                        onBack = { navController.popBackStack() },
                        host = marmaladeRuntime,
                    )
                }
                composable(SettingsRoutes.APP_INFO) {
                    AppInfoScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navController.navigate(it) },
                    )
                }
                composable(SettingsRoutes.CREDITS) {
                    val ctx = LocalContext.current
                    val settings = remember { SettingsRepository.getInstance(ctx) }
                    CreditsScreen(
                        settings = settings,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(SettingsRoutes.LICENSES) {
                    LicensesScreen(onBack = { navController.popBackStack() })
                }
            }
            // Open terminal (daemon PTY) — full-screen native libghostty-vt
            // canvas + extra keys (ADR 0016; the xterm.js WebView renderer was
            // deleted 2026-07-28). Reached from the Sessions screen's Terminals
            // tab and workspace detail screens. The route always exists (not
            // feature-gated) so a stale back-stack entry can't crash nav.
            composable(
                route = MarmaladeDestination.TERMINAL_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument("terminalId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val terminalId = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("terminalId").orEmpty(), "UTF-8",
                )
                val isConnected by marmaladeRuntime.isConnected.collectAsStateWithLifecycle()
                // Same top-left handle the chat screens get: the drawer is the
                // only navigator (ADR 0013), and a terminal is a peer of a
                // session, not a sub-screen of one. Leaving is system back —
                // edge-swipe to open the drawer stays off on this route
                // (gesturesEnabled above) because the terminal owns horizontal
                // gestures, so the button is its only handle.
                app.marmalade.android.ui.terminal.TerminalScreen(
                    controller = marmaladeRuntime.terminal,
                    terminalId = terminalId,
                    connected = isConnected,
                    onMenuClick = { drawerScope.launch { drawerState.open() } },
                )
            }
            // Session detail: full-screen chat when navigated from Sessions tab
            composable(
                route = MarmaladeDestination.SESSION_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument("sessionKey") { type = NavType.StringType }
                ),
            ) { backStackEntry ->
                val sessionKey = backStackEntry.arguments?.getString("sessionKey").orEmpty()
                val context = LocalContext.current

                // Clear notification for this session when the user opens it
                LaunchedEffect(sessionKey) {
                    if (sessionKey.isNotEmpty()) {
                        ChatNotificationHelper.cancelNotification(context, sessionKey)
                    }
                }

                // Look up the human-readable display name from gateway sessions,
                // fall back to parsing the key if not found
                val chatSessions by marmaladeRuntime.chatSessions.collectAsStateWithLifecycle()
                val displayName = chatSessions
                    .find { it.key == sessionKey }?.displayName
                    ?: friendlySessionName(sessionKey)

                // Switch ChatController to the requested session
                LaunchedEffect(sessionKey) {
                    marmaladeRuntime.switchChatSession(sessionKey)
                }

                val isConnected by marmaladeRuntime.isConnected.collectAsStateWithLifecycle()
                val attachmentsSupported by marmaladeRuntime.attachmentsSupported.collectAsStateWithLifecycle()
                val chatSearchSupported by marmaladeRuntime.searchSupported.collectAsStateWithLifecycle()

                val sttState = app.marmalade.android.ui.chat.rememberInlineSTTState(
                    onMicBusy = {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(app.marmalade.android.R.string.error_mic_busy),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                ChatScreen(
                    chat = marmaladeRuntime.chat,
                    sessionName = displayName,
                    showBackArrow = false,
                    onBackPressed = null,
                    onSettingsClick = null,
                    isInlineSTTActive = sttState.isActive,
                    inlineSTTPartialText = sttState.partialText,
                    onMicTap = { sttState.toggle() },
                    onMicLongPress = { sttState.triggerVoicePopup() },
                    isConnected = isConnected,
                    onStatusClick = { navController.navigate(SettingsRoutes.CONNECTION) },
                    onTitleClick = { switcherOpen = true },
                    onMenuClick = { drawerScope.launch { drawerState.open() } },
                    onPanelClick = { drawerScope.launch { panelState.open() } },
                    // /sessions, /switch and /resume now open the same switcher
                    // the title does, instead of throwing the user back to a
                    // tab (ADR 0013: the sheet IS the session picker).
                    onOpenSessionPicker = { switcherOpen = true },
                    onSessionDeleted = { navController.popBackStack() },
                    attachmentsSupported = attachmentsSupported,
                    searchSupported = chatSearchSupported,
                    searchRpc = marmaladeRuntime.marmaladeRpc,
                )
            }
        }

        if (switcherOpen) {
            val switcherSessionKey by marmaladeRuntime.chatSessionKey.collectAsStateWithLifecycle()
            app.marmalade.android.ui.sessions.SessionSwitcher(
                viewModel = sessionListViewModel,
                currentSessionKey = switcherSessionKey,
                onSelectSession = { key ->
                    switcherOpen = false
                    navController.navigate(MarmaladeDestination.sessionDetailRoute(key)) {
                        // Replace the chat we're leaving rather than stacking
                        // chats: switching sessions 10 times must not build a
                        // 10-deep back stack. No-op when we came from Home.
                        popUpTo(MarmaladeDestination.SESSION_DETAIL_ROUTE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onSelectTerminal = { terminalId ->
                    switcherOpen = false
                    navController.navigate(MarmaladeDestination.terminalDetailRoute(terminalId))
                },
                onDismiss = { switcherOpen = false },
            )
        }
        } // Column
    }
    } // LocalLayoutDirection Ltr (panel content)
    } // ModalNavigationDrawer (right-edge panel)
    } // LocalLayoutDirection Rtl (panel machinery)
    } // ModalNavigationDrawer (left drawer)

    // Rename from the drawer's session overflow. Hosted above the drawer so it
    // survives the drawer scrolling or closing underneath it.
    drawerRenameTarget?.let { (key, title) ->
        app.marmalade.android.ui.sessions.RenameSessionDialog(
            currentTitle = title,
            onConfirm = { newName ->
                sessionListViewModel.renameSession(key, newName, isGateway = true)
                drawerRenameTarget = null
            },
            onDismiss = { drawerRenameTarget = null },
        )
    }

    // New-workspace flow, hosted above the NavHost so the drawer button can
    // open it from any route.
    if (showNewWorkspaceSheet) {
        app.marmalade.android.ui.sessions.NewWorkspaceSheet(
            viewModel = sessionListViewModel,
            onDismiss = { showNewWorkspaceSheet = false },
            onCreated = { showNewWorkspaceSheet = false },
        )
    }
}

/** The one slide spec every route change uses. */
private fun navSlideSpec() =
    tween<IntOffset>(
        durationMillis = NAV_ANIM_MS,
        easing = FastOutSlowInEasing,
    )

/**
 * Which way a route change slides.
 *
 * With the bottom bar gone (ADR 0013) every navigation is a push or a pop:
 * forward brings the new screen in from the right, the matching pop reverses
 * it. The old version compared bottom-bar tab indices, which no longer exist.
 */
internal fun slideDirectionFor(isPop: Boolean): AnimatedContentTransitionScope.SlideDirection =
    if (isPop) AnimatedContentTransitionScope.SlideDirection.Right
    else AnimatedContentTransitionScope.SlideDirection.Left

/**
 * Slim "you're offline" banner. Replaces the OpenClaw-era
 * `ui/gateway/DisconnectionBanner` which was deleted alongside the
 * pairing flow; the marmalade transport just needs a "tap to reconnect"
 * affordance, no host / token surface here.
 */
@Composable
private fun DisconnectionBanner(
    status: String,
    onRetryClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.marmaladeColors.statusDisconnected.copy(alpha = 0.18f),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = status.ifBlank { "Offline" },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.material3.TextButton(onClick = onRetryClick) {
                Text("Reconnect")
            }
        }
    }
}
