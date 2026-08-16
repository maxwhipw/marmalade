package app.marmalade.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import app.marmalade.android.rpc.MarmaladeRpc

/**
 * The host app's [MarmaladeRpc] singleton, for `viewModel(factory = …)`
 * defaults in shared screens.
 *
 * ViewModels in `:shared` are plain multiplatform `ViewModel`s — they can't
 * reach a runtime through Android's `Application` the way an
 * `AndroidViewModel` does, so the RPC is constructor-injected and the *host*
 * supplies it. This composition local is that supply point.
 *
 * The Android app provides it once, around the nav host
 * (`MarmaladeNavHost`), from `MarmaladeApplication.marmaladeRuntime`; a
 * desktop host will provide its own the same way. It lives here rather than
 * in `:app` so a shared screen's default argument doesn't have to reach back
 * into an Android-only helper.
 *
 * `static`: the runtime's RPC is a `val` created once at process start and
 * never swapped, so there is nothing to invalidate readers for.
 */
val LocalMarmaladeRpc: ProvidableCompositionLocal<MarmaladeRpc> =
    staticCompositionLocalOf {
        error(
            "No MarmaladeRpc in the composition — wrap the UI in " +
                "CompositionLocalProvider(LocalMarmaladeRpc provides …).",
        )
    }

/** Shorthand for [LocalMarmaladeRpc]`.current`, for `viewModel(factory = …)` call sites. */
@Composable
fun rememberMarmaladeRpc(): MarmaladeRpc = LocalMarmaladeRpc.current
