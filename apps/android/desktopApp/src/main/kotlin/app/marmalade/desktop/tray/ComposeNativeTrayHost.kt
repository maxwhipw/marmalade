package app.marmalade.desktop.tray

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ApplicationScope
import com.kdroid.composetray.tray.api.Tray
import com.kdroid.composetray.utils.IconRenderProperties

/**
 * [TrayHost] over **ComposeNativeTray** (kdroidFilter, MIT) — the only file in
 * the client that names that library.
 *
 * On Linux the library drives libappindicator, i.e. a StatusNotifierItem over
 * D-Bus, which is what KDE Plasma actually consumes. That is the reason for
 * the dependency at all: AWT's `SystemTray` draws nothing under Wayland.
 *
 * The icon goes over as a **composable** rather than as a file path: the
 * library renders it offscreen and hands the result to the platform, which
 * spares us extracting the bundled PNG to a temp file, and the path-taking
 * overload is deprecated upstream. Decoding is done through Skia (already on
 * the desktop classpath) rather than through a `compose.ui.res` loader,
 * because those loaders have moved twice across the CMP versions this module
 * straddles.
 *
 * A failed decode costs the tray, not the app: [icon] goes null, [available]
 * reports false, and Main.kt's close button quits instead of hiding a window
 * with no way back.
 */
class ComposeNativeTrayHost(
    private val log: (String) -> Unit = { println("[marmalade-desktop] $it") },
) : TrayHost {

    private val icon: ImageBitmap? by lazy { loadIcon() }

    override val available: Boolean get() = icon != null

    @Composable
    override fun ApplicationScope.TrayIcon(menu: TrayMenu) {
        val bitmap = icon ?: return
        Tray(
            iconContent = {
                // fillMaxSize is required by the library: the icon is rendered
                // into a fixed offscreen scene, and anything that measures
                // itself instead comes out tiny.
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            iconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
            tooltip = menu.tooltip,
            // Left-click on the indicator. A Linux app indicator has no click
            // event of its own, so on Linux the library renders this as the
            // menu's FIRST entry, labelled below — which is why "Open
            // Marmalade" is not repeated in menuContent: doing both puts two
            // identical entries in the KDE menu (verified over dbusmenu).
            primaryAction = menu.onOpen,
            primaryActionLinuxLabel = OPEN_LABEL,
            menuContent = {
                Divider()
                Item(QUIT_LABEL) { menu.onQuit() }
            },
        )
    }

    private fun loadIcon(): ImageBitmap? = try {
        val bytes = javaClass.getResourceAsStream(ICON_RESOURCE)?.use { it.readBytes() }
            ?: error("$ICON_RESOURCE missing from the desktop jar")
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
        log("WARN tray icon unavailable, running without a tray: ${e.message}")
        null
    }

    private companion object {
        /** Rendered from the `concept1-kawaii-classic` mascot draft (internal
         *  design asset, not in this repo), the same artwork the Android
         *  launcher icon derives from. */
        const val ICON_RESOURCE = "/marmalade-tray.png"
        const val OPEN_LABEL = "Open Marmalade"
        const val QUIT_LABEL = "Quit"
    }
}
