package app.marmalade.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marmalade.android.chat.ModelCatalogEntry

/**
 * Model picker — Paseo-style bottom sheet. Searchable list of available models
 * grouped by provider, check on the current selection. Tapping a row calls
 * [onSelect] with the model's full id. The list is presented in the order the
 * gateway returns it (curated by marmalade-agent's picker context) — search is
 * a plain substring filter that preserves that order.
 *
 * A leading "Default" row (selected when [currentModelId] is null) clears the
 * pick via the dedicated [onSelectDefault] callback — deliberately NOT a
 * sentinel entry in [models], so no fake id can leak onto the wire. Hidden
 * while a search query is active (it's not a model, it shouldn't "match").
 * [defaultModelLabel], when the daemon advertised one, annotates that row
 * ("Default (Opus 4.8)") so the picker and the composer chip agree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
  models: List<ModelCatalogEntry>,
  currentModelId: String?,
  onSelect: (String) -> Unit,
  onSelectDefault: () -> Unit,
  onDismiss: () -> Unit,
  defaultModelLabel: String? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var search by remember { mutableStateOf("") }
  // Drop the drag handle (it floated near the status bar and read as neither
  // visible nor a dismiss affordance) in favour of an explicit close button in
  // the header. Swipe-down and scrim-tap still dismiss.
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    dragHandle = null,
  ) {
    // Deliberately do NOT auto-focus the search field: opening the sheet
    // shouldn't summon the keyboard and cover the model list. The user taps
    // the field to search.
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Filled.Memory,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Model",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Close",
            modifier = Modifier.size(20.dp),
          )
        }
      }
      OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        placeholder = { Text("Search models…") },
        leadingIcon = {
          Icon(imageVector = Icons.Filled.Search, contentDescription = null)
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 8.dp),
      )

      val q = search.trim()
      val filtered = if (q.isEmpty()) models
                     else models.filter {
                       it.name.contains(q, ignoreCase = true) ||
                       it.id.contains(q, ignoreCase = true) ||
                       it.provider.contains(q, ignoreCase = true) ||
                       it.description.contains(q, ignoreCase = true)
                     }

      if (models.isEmpty()) {
        Text(
          text = "No models reported by the gateway yet.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(20.dp),
        )
      } else if (filtered.isEmpty()) {
        Text(
          text = "No matches for \"$q\".",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(20.dp),
        )
      } else {
        // Group by provider, preserving the curated order.
        val grouped = LinkedHashMap<String, MutableList<ModelCatalogEntry>>()
        filtered.forEach { grouped.getOrPut(it.provider) { mutableListOf() }.add(it) }
        LazyColumn(
          contentPadding = PaddingValues(bottom = 24.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (q.isEmpty()) {
            item(key = "default") {
              ModelRow(
                entry = ModelCatalogEntry(
                  id = "",
                  name = defaultModelLabel?.let { "Default ($it)" } ?: "Default",
                  provider = "",
                  description = "Let the daemon pick its configured model",
                ),
                selected = currentModelId == null,
                onClick = {
                  onSelectDefault()
                  onDismiss()
                },
              )
            }
          }
          grouped.forEach { (provider, entries) ->
            if (provider.isNotBlank()) {
              item(key = "hdr-$provider") {
                Text(
                  text = provider,
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                )
              }
            }
            items(items = entries, key = { e -> "m-${e.id}" }) { entry ->
              ModelRow(
                entry = entry,
                selected = entry.id == currentModelId,
                onClick = {
                  onSelect(entry.id)
                  onDismiss()
                },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ModelRow(
  entry: ModelCatalogEntry,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(32.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Memory,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val sub = entry.description.ifBlank { entry.id }
      Text(
        text = sub,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (selected) {
      Spacer(modifier = Modifier.width(8.dp))
      Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = "Selected",
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
