package app.marmalade.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * The width of the window the UI is being composed into, in dp.
 *
 * `expect`/`actual` rather than a composition local because it is not a host
 * *decision* — it is a platform reading, and each platform already publishes
 * one. Android's is `LocalConfiguration.current.screenWidthDp`, which is the
 * exact value the chat bubbles have always sized against; desktop's is the
 * window container size from `LocalWindowInfo`. Keeping the Android actual
 * literal is what makes moving the bubbles a pure code move.
 */
@Composable
expect fun windowWidthDp(): Dp
