package app.marmalade.android.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.android.ui.AgentPromptCard
import app.marmalade.android.ui.AgentPromptTone
import app.marmalade.android.ui.theme.marmaladeColors

/**
 * Renders a Marmalade interactive block as a standalone card in the chat stream.
 *
 * Routes to the correct block composable based on block type.
 * For unknown types or parse failures, renders the raw JSON in a styled code block
 * (graceful degradation per user decision).
 *
 * @param block The parsed MarmaladeBlock with type and data
 * @param onInteraction Callback when user interacts with the block.
 *   The callback receives the formatted ```marmalade-response string ready for chat.send.
 */
@Composable
fun MarmaladeBlockRenderer(
    block: MarmaladeBlock,
    onInteraction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parsedData = MarmaladeBlockParser.parseBlockData(block)

    // One frame for every agent-initiated ask, docked or inline — see
    // [AgentPromptCard]. These blocks used to carry their own surfaceVariant +
    // outline chrome, so a "yes or no to a destructive thing" rendered as a
    // visibly different object depending only on whether the agent emitted a
    // wire event or a ```marmalade``` fence.
    //
    // No dismiss affordance: a block is part of the transcript, not floating
    // chrome, so there is nothing to dismiss it *from*.
    AgentPromptCard(
        modifier = modifier.padding(horizontal = 16.dp),
        title = block.title?.takeIf { it.isNotBlank() },
        // Neutral for now: the block schema carries no destructive/severity
        // flag, so there is nothing to key a Danger tone off without guessing
        // from the message text. Add it to ConfirmData first if we want the
        // parity with ApprovalCard.
        tone = AgentPromptTone.Neutral,
    ) {
        when {
            parsedData is ConfirmData -> {
                ConfirmBlock(
                    data = parsedData,
                    onInteraction = { response ->
                        onInteraction(
                            MarmaladeBlockParser.formatBlockResponse(
                                blockId = block.blockId,
                                type = "confirm",
                                response = response,
                            )
                        )
                    },
                )
            }

            parsedData is SelectData -> {
                SelectBlock(
                    data = parsedData,
                    onInteraction = { selectedId ->
                        onInteraction(
                            MarmaladeBlockParser.formatBlockResponse(
                                blockId = block.blockId,
                                type = "select",
                                response = selectedId,
                            )
                        )
                    },
                )
            }

            parsedData is MultiselectData -> {
                MultiselectBlock(
                    data = parsedData,
                    onInteraction = { selectedIds ->
                        onInteraction(
                            MarmaladeBlockParser.formatBlockResponse(
                                blockId = block.blockId,
                                type = "multiselect",
                                response = selectedIds,
                            )
                        )
                    },
                )
            }

            parsedData is ActionData -> {
                ActionCardBlock(
                    data = parsedData,
                    onInteraction = { actionId ->
                        onInteraction(
                            MarmaladeBlockParser.formatBlockResponse(
                                blockId = block.blockId,
                                type = "action",
                                response = actionId,
                            )
                        )
                    },
                )
            }

            parsedData is StatusData -> {
                StatusBlock(data = parsedData)
            }

            else -> {
                // Graceful degradation: unknown type or parse failure
                // Render the raw JSON in a styled code block
                RawJsonFallback(block = block)
            }
        }
    }
}

/**
 * Fallback display for unrecognized block types: renders the raw JSON
 * in a styled code block so the user can see what the agent sent.
 */
@Composable
private fun RawJsonFallback(block: MarmaladeBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .background(MaterialTheme.marmaladeColors.codeBackground, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.marmaladeColors.codeBorder, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            text = "marmalade:${block.type}",
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            text = block.data.toString(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.marmaladeColors.codeText,
        )
    }
}
