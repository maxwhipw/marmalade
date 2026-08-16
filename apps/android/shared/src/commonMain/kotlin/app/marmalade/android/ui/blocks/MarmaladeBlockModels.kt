package app.marmalade.android.ui.blocks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Top-level Marmalade block parsed from ```marmalade code blocks in agent messages.
 *
 * Block types: confirm, select, multiselect, action, status.
 * Unknown types are preserved with raw data for graceful degradation.
 */
@Serializable
data class MarmaladeBlock(
    val type: String,
    val blockId: String? = null,
    val title: String? = null,
    val data: JsonObject,
)

// -- Typed data classes parsed from MarmaladeBlock.data --

data class ConfirmData(
    val message: String,
    val confirmLabel: String,
    val cancelLabel: String,
)

data class SelectOption(
    val id: String,
    val label: String,
)

data class SelectData(
    val message: String,
    val options: List<SelectOption>,
)

data class MultiselectData(
    val message: String,
    val options: List<SelectOption>,
    val submitLabel: String,
)

data class ActionItem(
    val id: String,
    val label: String,
    val icon: String? = null,
)

data class ActionData(
    val actions: List<ActionItem>,
)

data class StatusData(
    val message: String,
    val progress: Float?,
    val state: String,
)
