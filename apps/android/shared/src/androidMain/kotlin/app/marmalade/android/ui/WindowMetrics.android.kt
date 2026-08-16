package app.marmalade.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Android: the window width the framework already reports in dp. */
@Composable
actual fun windowWidthDp(): Dp = LocalConfiguration.current.screenWidthDp.dp
