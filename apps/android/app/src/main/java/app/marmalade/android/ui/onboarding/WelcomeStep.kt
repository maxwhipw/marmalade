package app.marmalade.android.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.android.ui.home.MascotExpression
import app.marmalade.android.ui.home.MascotImage
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Onboarding Step 1: Welcome.
 *
 * Shows the mascot image, app name in amber, tagline, and a "Get Started" button.
 * Content fades in on entry.
 */
@Composable
fun WelcomeStep(
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.fillMaxWidth(),
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            MascotImage(
                expression = MascotExpression.JOY,
                modifier = Modifier.size(160.dp),
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "marmalade",
                fontFamily = app.marmalade.android.ui.theme.Wordmark,
                fontSize = 32.sp,
                // Momo Trust Display ships one static 400 master; asking for a
                // weight it lacks yields synthetic bold. See theme/Type.kt.
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.marmaladeColors.wordmark,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your AI assistant, always ready",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(64.dp))

            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.marmaladeColors.accentButtonBg,
                    contentColor = MaterialTheme.marmaladeColors.accentButtonFg,
                ),
            ) {
                Text(
                    text = "Get Started",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
