package app.marmalade.android.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Onboarding Step 5: Done.
 *
 * Shows the mascot with a green checkmark overlay, "You're all set!" message,
 * connected gateway name, and "Start Chatting" button.
 */
@Composable
fun DoneStep(
    gatewayName: String?,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Mascot with checkmark overlay
        Box(contentAlignment = Alignment.BottomEnd) {
            MascotImage(
                expression = MascotExpression.JOY,
                modifier = Modifier.size(140.dp),
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Success",
                tint = MaterialTheme.marmaladeColors.toolSuccess,
                modifier = Modifier
                    .size(40.dp)
                    .offset(x = 4.dp, y = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "You're all set!",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        val subtitle = if (gatewayName != null) {
            "Connected to $gatewayName"
        } else {
            "Connected to your gateway"
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.marmaladeColors.toolSuccess,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(64.dp))

        Button(
            onClick = onComplete,
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
                text = "Start Chatting",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
