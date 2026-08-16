package app.marmalade.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.marmalade.android.ui.effortBoundCaption
import app.marmalade.android.ui.effortOptions

/**
 * Reasoning-effort picker — bottom sheet listing the levels the DAEMON accepts
 * (`model.list` `efforts`: low/medium/high/xhigh/max) with friendly labels and
 * a checkmark on the current selection.
 *
 * The vocabulary is the daemon's, not ours. Until 2026-07-25 this sheet
 * offered a hand-written none/minimal/low/medium/high/xhigh list: `none` and
 * `minimal` are not levels marmaladed accepts (session.create rejects them
 * with InvalidParams) and "Max" was pinned to `xhigh`, hiding the real `max`.
 * Pass the daemon's list in [levels]; [EFFORT_LEVELS] is only the fallback for
 * a daemon too old to publish one.
 *
 * The displayed value comes from [currentEffort] (which is what
 * `ChatController.thinkingLevel` already exposes); tapping a row calls
 * [onSelect] with the raw effort string the daemon accepts.
 */

/** Options for a selector, from the daemon's vocabulary when it published one.
 *  Labels live in :shared ([effortOptions]) so this sheet and the Models
 *  settings screen can never name a level differently. */
internal fun reasoningOptions(levels: List<String>): List<Triple<String, String, String>> =
  effortOptions(levels)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningPickerSheet(
  currentEffort: String,
  levels: List<String>,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
  /** The SELECTED model's configured effort bounds (`model.list`
   *  effort_min/effort_max, 2026-07-27). Levels outside them render disabled
   *  with a caption naming the bound, because the daemon would clamp the pick
   *  anyway — offering it silently is offering a lie. Both null (every older
   *  daemon, every unbounded model) = the sheet this shipped with. */
  effortMin: String? = null,
  effortMax: String? = null,
  /** How to name the bounding model in that caption ("Below Opus 5 minimum").
   *  Ignored when the model is unbounded. */
  modelLabel: String = "this model",
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
      Text(
        text = "Thinking",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
      )
      val normalized = currentEffort.trim().lowercase()
      val vocabulary = reasoningOptions(levels)
      vocabulary.forEach { (effort, label, desc) ->
        val boundCaption = effortBoundCaption(
          effort = effort,
          levels = vocabulary.map { it.first },
          min = effortMin,
          max = effortMax,
          modelLabel = modelLabel,
        )
        ReasoningRow(
          label = label,
          // The bound REPLACES the level's blurb rather than stacking under
          // it: a disabled row's one job is to say why it's disabled.
          description = boundCaption ?: desc,
          selected = normalized == effort,
          enabled = boundCaption == null,
          onClick = {
            onSelect(effort)
            onDismiss()
          },
        )
      }
    }
  }
}

@Composable
private fun ReasoningRow(
  label: String,
  description: String,
  selected: Boolean,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  // M3's disabled treatment: dim the whole row rather than greying only the
  // label, so "not available" reads at a glance without a second colour.
  val contentAlpha = if (enabled) 1f else 0.38f
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(32.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = contentAlpha)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Psychology,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
      )
    }
    if (selected) {
      Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = "Selected",
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
