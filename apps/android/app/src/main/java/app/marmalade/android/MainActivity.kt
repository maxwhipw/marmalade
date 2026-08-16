package app.marmalade.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.android.chat.PromptKind
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import app.marmalade.android.ui.LocalMarmaladeRpc
import app.marmalade.android.ui.MarmaladeHostBridges
import app.marmalade.android.ui.navigation.MarmaladeNavHost
import app.marmalade.android.ui.onboarding.OnboardingScreen
import app.marmalade.android.ui.theme.MarmaladeTheme
import app.marmalade.android.ui.theme.ThemePreset
import app.marmalade.android.ui.theme.resolveThemeIsDark


/**
 * Single-Activity entry point for the Marmalade app.
 *
 * Sets up edge-to-edge display and renders either the onboarding flow
 * (for first-run) or the main navigation shell. The hasCompletedSetup
 * flag in SettingsRepository controls which is shown.
 */
class MainActivity : ComponentActivity() {

    /**
     * Mutable state for notification deep-link navigation.
     * Updated on cold-start (onCreate) and warm-start (onNewIntent).
     * MarmaladeNavHost observes this to navigate to the target session.
     */
    private val navigateToSessionKey = mutableStateOf<String?>(null)

    /**
     * Composer prefill from a marmalade:// deep link. Format mirrors the
     * desktop's onDeepLink contract: `marmalade://<command>?text=<encoded>`
     * — the host carries the command, `text` is the prefilled argument.
     * Example: `marmalade://blueprint?text=https%3A%2F%2Fexample.com` →
     * composer set to "/blueprint https://example.com".
     */
    val composerPrefill = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val sessionKey = intent.getStringExtra("navigate_to_session")
        if (sessionKey != null) {
            android.util.Log.d("DeepLink", "onNewIntent: navigating to $sessionKey")
            navigateToSessionKey.value = sessionKey
            intent.removeExtra("navigate_to_session")
        }
        consumeMarmaladeDeepLink(intent)
    }

    private fun consumeMarmaladeDeepLink(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "marmalade") return
        val command = data.host?.takeIf { it.isNotBlank() } ?: return
        val text = data.getQueryParameter("text").orEmpty()
        val draft = if (text.isNotEmpty()) "/$command $text" else "/$command"
        composerPrefill.value = draft
        // Persist as the bound session's draft so the Composer picks it up
        // on next focus / session-switch — ChatController.getDraft is read
        // by InputTextField at start of compose. Matches desktop's onDeepLink
        // → composer-set flow (desktop-controller.tsx:299-304).
        (application as? MarmaladeApplication)?.marmaladeRuntime?.chat?.setDraft(draft)
        android.util.Log.d("DeepLink", "marmalade:// → composer draft set: $draft")
    }

    /**
     * Window-level hardening for the secret-entry card (daemon secret.request).
     *
     * The card itself masks its field, but a masked field is still readable
     * from a screenshot, a screen recording, the recents thumbnail, and an
     * autofill service's view structure. Those are all window properties, not
     * Compose properties, so this lives here in `:app` rather than in the
     * shared card:
     *
     *  - **FLAG_SECURE** blocks screenshots, screen recording, non-secure
     *    displays, and blanks the recents thumbnail.
     *  - **importantForAutofill = NO_EXCLUDE_DESCENDANTS on the decor view**
     *    takes the whole window out of autofill traversal. `NO` alone is not
     *    enough: per the platform docs it excuses only the view it is set on
     *    and autofill still walks the children, which is where the field is.
     *    Verified alongside this: Compose text fields register autofill nodes
     *    only when opted in via `contentType` semantics (foundation 1.8+) —
     *    the card's OutlinedTextField sets none, so there is no second,
     *    Compose-owned registration path that would survive this flag.
     *
     * Both are set only while a Secret card is pending in the BOUND session
     * (what the user can actually see) and cleared as soon as it settles —
     * FLAG_SECURE left on permanently would block ordinary screenshots of
     * chat, which the maintainer uses.
     */
    @Composable
    private fun SecureWhileSecretPending(app: MarmaladeApplication) {
        val prompts by app.marmaladeRuntime.chat.boundPendingPrompts.collectAsState()
        val secretPending = prompts.any { it.kind == PromptKind.Secret }
        DisposableEffect(secretPending) {
            if (secretPending) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                window.decorView.importantForAutofill =
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            onDispose {
                if (secretPending) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_AUTO
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as MarmaladeApplication).marmaladeRuntime.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        (application as MarmaladeApplication).marmaladeRuntime.setForeground(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen skipped -- core-splashscreen not available in offline Gradle cache.
        // installSplashScreen() would go here before super.onCreate() if available.

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as MarmaladeApplication
        val settings = SettingsRepository.getInstance(this)

        // Handle notification deep-link on cold start
        val initialSessionKey = intent?.getStringExtra("navigate_to_session")
        if (initialSessionKey != null) {
            android.util.Log.d("DeepLink", "onCreate: navigating to $initialSessionKey")
            navigateToSessionKey.value = initialSessionKey
            intent?.removeExtra("navigate_to_session")
        }
        intent?.let { consumeMarmaladeDeepLink(it) }

        // Check for crash log from previous run
        val crashFile = app.crashFile()
        val crashLog = if (crashFile.exists()) crashFile.readText() else null

        setContent {
            SecureWhileSecretPending(app)

            val themeMode = remember { mutableStateOf(settings.themeMode) }
            val themePresetName = remember { mutableStateOf(settings.themePreset) }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = resolveThemeIsDark(themeMode.value, systemDark)
            val themePreset = ThemePreset.fromString(themePresetName.value)

            MarmaladeTheme(darkTheme = darkTheme, themePreset = themePreset) {
                var showOnboarding by rememberSaveable {
                    mutableStateOf(!settings.hasCompletedSetup)
                }

                // Crash report dialog
                var showCrashDialog by rememberSaveable { mutableStateOf(crashLog != null) }
                if (showCrashDialog && crashLog != null) {
                    AlertDialog(
                        onDismissRequest = {
                            showCrashDialog = false
                            crashFile.delete()
                        },
                        title = { Text("Crash Report") },
                        text = {
                            Text(
                                text = crashLog,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                modifier = Modifier
                                    .heightIn(max = 400.dp)
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState()),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", crashLog))
                                showCrashDialog = false
                                crashFile.delete()
                            }) {
                                Text("Copy & Dismiss")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showCrashDialog = false
                                crashFile.delete()
                            }) {
                                Text("Dismiss")
                            }
                        },
                    )
                }

                if (showOnboarding) {
                    OnboardingScreen(
                        onComplete = {
                            showOnboarding = false
                        },
                    )
                } else {
                    // Settings screens that live in :shared are plain
                    // multiplatform composables — they can't cast an
                    // Application to reach the runtime, so the host hands
                    // them the RPC down the composition. This is the Android
                    // host's supply point (a desktop host provides the same
                    // local from its own runtime); the nav host is the only
                    // place those screens are composed.
                    CompositionLocalProvider(
                        LocalMarmaladeRpc provides app.marmaladeRuntime.marmaladeRpc,
                    ) {
                        // The same idea for the chat UI's platform bridges
                        // (clipboard / attachment intent / reduce-motion):
                        // shared composables ask the composition, the Android
                        // host answers. See ui/HostBridgeProviders.kt.
                        MarmaladeHostBridges {
                            MarmaladeNavHost(
                                marmaladeRuntime = app.marmaladeRuntime,
                                currentThemeMode = themeMode.value,
                                onThemeModeChange = { mode ->
                                    settings.themeMode = mode
                                    themeMode.value = mode
                                },
                                currentThemePreset = themePresetName.value,
                                onThemePresetChange = { name ->
                                    settings.themePreset = name
                                    themePresetName.value = name
                                },
                                navigateToSessionKey = navigateToSessionKey,
                            )
                        }
                    }
                }
            }
        }
    }
}
