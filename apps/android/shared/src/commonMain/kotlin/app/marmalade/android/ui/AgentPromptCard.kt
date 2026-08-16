package app.marmalade.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * The single frame every agent-initiated ASK renders into.
 *
 * Marmalade grew two independent interactive systems that do overlapping jobs
 * and shared no code (design-lab `agent-session-ui`):
 *
 *  - the **docked prompt stack** above the composer — Clarify / Approval /
 *    Secret / Sudo, driven by real wire events; and
 *  - **inline transcript blocks** — Confirm / Select / Multiselect /
 *    ActionCard / Status, parsed out of ` ```marmalade ` fences with no wire
 *    event at all.
 *
 * The overlap is total: Approval and Confirm both mean "yes or no to a
 * destructive thing"; Clarify and Select/Multiselect both mean "pick from a
 * list". Which one the maintainer saw depended only on whether the agent emitted an event
 * or a code fence — and the two looked like they came from different apps.
 *
 * This frame is the convergence point. The *content* of an ask legitimately
 * differs (options, a masked field, a command); the *object* never should.
 *
 * Deliberately NOT unified: placement. The docked stack still docks and blocks
 * still sit inline, because that difference is real (a blocking question must
 * not scroll away). Only the frame is shared.
 */
@Composable
fun AgentPromptCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    detail: String? = null,
    /** Null renders no dismiss affordance — inline blocks have no X, since
     *  they are part of the transcript rather than floating chrome. */
    onDismiss: (() -> Unit)? = null,
    tone: AgentPromptTone = AgentPromptTone.Neutral,
    /** Optional mark in front of the title. Most asks don't take one — the
     *  frame is deliberately quiet — but an ask whose KIND changes what the
     *  user should do (the secret card's "the model does not see this") needs
     *  to say so before the title is read. */
    leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = when (tone) {
        AgentPromptTone.Neutral -> null
        AgentPromptTone.Danger -> MaterialTheme.marmaladeColors.bannerError
        AgentPromptTone.Active -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AgentPromptCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = accent?.let { BorderStroke(1.dp, it.copy(alpha = 0.5f)) },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (title != null || onDismiss != null || leading != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = title.orEmpty(),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (tone == AgentPromptTone.Danger) {
                            MaterialTheme.marmaladeColors.bannerError
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (onDismiss != null) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** How loudly an ask presents. [Danger] is for anything destructive or
 *  privileged; [Active] marks an ask that is currently blocking the agent. */
enum class AgentPromptTone { Neutral, Danger, Active }

val AgentPromptCardShape = RoundedCornerShape(12.dp)

/** Shared with callers that need the frame's ground colour (e.g. to tint a
 *  nested surface against it) without re-deriving it. */
val agentPromptCardColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
