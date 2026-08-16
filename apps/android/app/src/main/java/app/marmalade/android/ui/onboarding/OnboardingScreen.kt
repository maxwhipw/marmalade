package app.marmalade.android.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.android.MarmaladeApplication
import app.marmalade.android.data.SettingsRepository
import app.marmalade.android.data.getInstance
import kotlinx.coroutines.launch

/**
 * Steps in the onboarding flow.
 */
private enum class OnboardingStep(val index: Int, val title: String) {
    Welcome(1, "Welcome"),
    Permissions(2, "Permissions"),
    Gateway(3, "Gateway"),
    Pairing(4, "Connecting"),
    Done(5, "Done"),
}

private const val TOTAL_STEPS = 5

/**
 * Onboarding flow.
 *
 * Steps: Welcome -> Permissions -> Gateway -> Pairing -> Done
 *
 * There is no "pick your assistant session" step any more: Home and voice are
 * ALWAYS the daemon-managed singleton main session (session.main), created and
 * owned server-side — nothing for the user to choose (assistant plan
 * 2026-07-19).
 *
 * Features:
 * - Linear progress bar at top showing step / 5
 * - Back arrow (except on Welcome and Done)
 * - Top snackbar for error messages (ONBOARD-06)
 * - rememberSaveable for currentStep to survive config changes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MarmaladeApplication
    val marmaladeRuntime = app.marmaladeRuntime
    val settings = SettingsRepository.getInstance(context)

    var currentStep by rememberSaveable { mutableStateOf(OnboardingStep.Welcome.name) }
    val step = OnboardingStep.valueOf(currentStep)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // TLS trust dialog removed — marmalade doesn't use TLS pinning. The
    // gateway-trust + pairing UX is replaced by the ConnectScreen in
    // task #13 (paste-URL + token).

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                // Progress bar
                LinearProgressIndicator(
                    progress = { step.index.toFloat() / TOTAL_STEPS },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0),
                    title = {
                        Text(
                            text = "Step ${step.index} of $TOTAL_STEPS",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    navigationIcon = {
                        if (step != OnboardingStep.Welcome &&
                            step != OnboardingStep.Done
                        ) {
                            IconButton(onClick = {
                                currentStep = when (step) {
                                    OnboardingStep.Permissions -> OnboardingStep.Welcome.name
                                    OnboardingStep.Gateway -> OnboardingStep.Permissions.name
                                    OnboardingStep.Pairing -> OnboardingStep.Gateway.name
                                    else -> step.name
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = {
            // Error snackbar anchored at top (ONBOARD-06)
            Box(modifier = Modifier.fillMaxSize()) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.TopCenter),
                ) { snackbarData ->
                    Snackbar(
                        snackbarData = snackbarData,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) { innerPadding ->
        val gatewayName by marmaladeRuntime.gatewayName.collectAsStateWithLifecycle()
        // Auto-advance to PairingStep only when the dashboard endpoint is
        // actually configured — both URL and token persisted. The legacy
        // `SettingsRepository.isConfigured()` is hardcoded `true`, which makes
        // GatewayStep unreachable on a fresh install. Reading from SecurePrefs
        // directly here gives us the real signal: empty url/token → user needs
        // to fill the form.
        val dashboardUrl by marmaladeRuntime.dashboardUrl.collectAsStateWithLifecycle()
        val dashboardToken by marmaladeRuntime.dashboardToken.collectAsStateWithLifecycle()
        val hasDashboardCreds = dashboardUrl.isNotBlank() && dashboardToken.isNotBlank()
        LaunchedEffect(hasDashboardCreds, step) {
            if (hasDashboardCreds && step == OnboardingStep.Gateway) {
                currentStep = OnboardingStep.Pairing.name
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (step) {
                OnboardingStep.Welcome -> {
                    WelcomeStep(
                        onNext = { currentStep = OnboardingStep.Permissions.name },
                    )
                }

                OnboardingStep.Permissions -> {
                    PermissionsStep(
                        onNext = { currentStep = OnboardingStep.Gateway.name },
                    )
                }

                OnboardingStep.Gateway -> {
                    GatewayStep(
                        marmaladeRuntime = marmaladeRuntime,
                        onConnectInitiated = {
                            currentStep = OnboardingStep.Pairing.name
                        },
                    )
                }

                OnboardingStep.Pairing -> {
                    PairingStep(
                        marmaladeRuntime = marmaladeRuntime,
                        onConnected = {
                            // Home + voice are the daemon-managed main session
                            // now — nothing to pick, so pairing goes straight
                            // to Done.
                            currentStep = OnboardingStep.Done.name
                        },
                        onBack = {
                            // Reset connection so Gateway step shows fresh
                            currentStep = OnboardingStep.Gateway.name
                        },
                        onError = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                    )
                }

                OnboardingStep.Done -> {
                    DoneStep(
                        gatewayName = gatewayName,
                        onComplete = {
                            settings.hasCompletedSetup = true
                            onComplete()
                        },
                    )
                }
            }
        }
    }
}
