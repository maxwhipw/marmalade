package app.marmalade.desktop.tray

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ApplicationScope

/**
 * What the tray icon offers: a tooltip and the two actions a close-to-tray app
 * owes the user — get the window back, and really quit.
 *
 * Kept this small on purpose. Session counts, unread badges and per-session
 * menu entries are all plausible later; none of them are needed to make
 * hiding the window safe, which is the whole point of the tray today.
 */
data class TrayMenu(
    val tooltip: String,
    /** Show + raise + focus the main window. */
    val onOpen: () -> Unit,
    /** Real exit: tear the runtime down and end the application. */
    val onQuit: () -> Unit,
)

/**
 * The system-tray surface, behind our own interface so the backend is
 * swappable.
 *
 * Exactly one implementation exists ([ComposeNativeTrayHost], over
 * kdroidFilter/ComposeNativeTray) and all of its library-specific code lives
 * in that one file. AWT's `java.awt.SystemTray` is NOT an option here: it is
 * broken under Wayland, which is the target session.
 *
 * Rendered inside Compose's `application { }` block, so it lives and dies with
 * the process rather than with the window — that is what lets the window be
 * hidden while the app keeps running.
 */
interface TrayHost {
    /**
     * True when a tray icon can actually be shown. Close-to-tray is gated on
     * it: a window that hides with no tray to restore it from is a lost app,
     * so the close button falls back to a real quit when this is false.
     */
    val available: Boolean

    @Composable
    fun ApplicationScope.TrayIcon(menu: TrayMenu)
}
