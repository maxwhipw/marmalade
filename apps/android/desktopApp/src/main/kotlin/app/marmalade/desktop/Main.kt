package app.marmalade.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.marmalade.desktop.tray.ComposeNativeTrayHost
import app.marmalade.desktop.tray.TrayMenu

/**
 * Entry point for the Compose Multiplatform desktop client (Phase 2 spike).
 *
 * The daemon URL can be overridden with `MARMALADE_DAEMON_URL` for pointing at
 * a non-default port; the default is the daemon's loopback bind.
 *
 * **Close-to-tray.** The window's close button hides the window instead of
 * ending the process, so the runtime keeps its socket, its reconnect loop and
 * its notifications — a chat client the user has "closed" is still the thing
 * that tells them the agent finished. Only the tray's Quit really exits.
 * Hiding also drops [DesktopRuntime.windowFocused], which is what keeps the
 * `session.seen` cursor honest: a turn that lands while we're in the tray was
 * not read, so it stays unread on the phone (and earns a notification).
 *
 * If no tray can be shown ([app.marmalade.desktop.tray.TrayHost.available]),
 * close quits for real rather than hiding a window with no way back.
 */
fun main() = application {
    val runtime = remember {
        DesktopRuntime(
            daemonHttpUrl = System.getenv("MARMALADE_DAEMON_URL")
                ?: DesktopRuntime.DEFAULT_DAEMON_URL,
        )
    }
    val tray = remember { ComposeNativeTrayHost() }
    val windowState = rememberWindowState(size = DpSize(1200.dp, 820.dp))
    var visible by remember { mutableStateOf(true) }

    val quit = {
        runtime.close()
        exitApplication()
    }
    val open = {
        // A window restored from the tray must come back where it can be seen,
        // whatever the user did to it before hiding.
        windowState.isMinimized = false
        visible = true
    }

    with(tray) {
        TrayIcon(TrayMenu(tooltip = "Marmalade", onOpen = open, onQuit = quit))
    }

    Window(
        onCloseRequest = {
            if (!tray.available) {
                quit()
            } else {
                visible = false
                // The AWT focus listener fires on hide too, but the runtime
                // must not read "focused" for even a frame while we're gone.
                runtime.windowFocused = false
            }
        },
        visible = visible,
        title = "Marmalade",
        state = windowState,
    ) {
        // Raising is a window-manager request, so it belongs to the real AWT
        // frame rather than to the visibility flag alone: without it KWin
        // shows the window again behind whatever has focus.
        LaunchedEffect(visible) {
            if (visible) {
                window.toFront()
                window.requestFocus()
            }
        }
        // Inside the Window so it can reach the AWT frame; see WindowFocusBridge.
        WindowFocusBridge(runtime)
        DesktopApp(runtime)
    }
}
