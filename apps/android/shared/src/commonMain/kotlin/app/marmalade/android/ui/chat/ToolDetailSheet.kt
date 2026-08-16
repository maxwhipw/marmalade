package app.marmalade.android.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.ui.theme.marmaladeColors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Everything known about one tool call, in a bottom sheet.
 *
 * The collapsed run and the spine are deliberately terse — one line each — so
 * a turn's tool count stops driving its vertical cost. That trade only works
 * if the detail is still reachable, which is what this sheet is for: tap any
 * row and get the full arguments, the tool's actual output, timing, and (for
 * subagent work) who ran it.
 *
 * Requested by the maintainer, 2026-07-26: *"not enough info on the tool calls, I'd like
 * to be able to tap them too and see their info pop up in a sheet"*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailSheet(
    call: ChatMessagePart.ToolCall,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val args = remember(call.args, call.argsText) { call.displayArgs() }
    val resultObject = call.result as? JsonObject
    // The tool's own printed output, split out of the merged result so it
    // reads as text rather than being buried in a JSON blob.
    val output = remember(call.result) { toolOutputText(call.result) }
    // Whatever else the result carries (duration, error, todos, the subagent
    // report) minus the bulky `content` we already rendered above it.
    val metadata = remember(resultObject) {
        resultObject?.let { obj ->
            buildJsonObject { obj.filterKeys { it != "content" }.forEach { (k, v) -> put(k, v) } }
        }?.takeIf { it.isNotEmpty() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = iconForTool(call.toolName),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = humanizeToolName(call.toolName),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The raw name matters here even though the header hides
                    // it — it's what you need to talk to the agent about a
                    // specific MCP tool, or to find it in a log.
                    Text(
                        text = call.toolName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                ToolDetailStatus(call)
            }

            if (call.parentToolUseId != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Run by a subagent, not the main agent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }

            // The decision the maintainer made about this call. Only present when he was
            // genuinely asked — approvals default to auto, so the absence of
            // this block means "nobody was prompted", not "approved quietly".
            call.approvalChoice?.let { choice ->
                val denied = choice == "deny"
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (denied) {
                        MaterialTheme.marmaladeColors.bannerError.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = approvalRecordLabel(choice),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (denied) {
                            MaterialTheme.marmaladeColors.bannerError
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }

            ToolDetailSection("Arguments") {
                if (args.isNotEmpty()) {
                    ChatCodeBlock(code = prettyPrintJson(args), language = "json")
                } else if (call.argsText.isNotBlank() && call.argsText != "{}") {
                    ChatCodeBlock(code = call.argsText, language = null)
                } else {
                    ToolDetailEmpty("This tool was called with no arguments.")
                }
            }

            // Image-producing tools (generate_image, screenshot) return a URL or
            // data-uri; rendering the blob as monospace text is useless. Mirrors
            // desktop's tool-fallback image extraction.
            val imageUrl = remember(call.result, call.argsText) {
                extractImageUrl(call.result?.toString().orEmpty()) ?: extractImageUrl(call.argsText)
            }
            ToolDetailSection(if (call.isError) "Error output" else "Output") {
                when {
                    imageUrl != null -> AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                    )
                    output != null -> ChatCodeBlock(
                        code = output,
                        language = null,
                        textColor = if (call.isError) MaterialTheme.marmaladeColors.bannerError else Color.Unspecified,
                    )
                    call.result == null -> ToolDetailEmpty("Still running — no output yet.")
                    else -> ToolDetailEmpty("This tool returned no output.")
                }
            }

            if (metadata != null) {
                ToolDetailSection("Result data") {
                    ChatCodeBlock(code = prettyPrintJson(metadata), language = "json")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = call.toolCallId,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolDetailStatus(call: ChatMessagePart.ToolCall) {
    val duration = toolDurationLabel(call.result)
    val (label, color) = when {
        call.isError -> "error" to MaterialTheme.marmaladeColors.bannerError
        call.result == null -> "running" to MaterialTheme.marmaladeColors.statusConnecting
        else -> (duration ?: "done") to MaterialTheme.marmaladeColors.toolSuccess
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ToolDetailSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(18.dp))
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    content()
}

@Composable
private fun ToolDetailEmpty(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The tool's printed output. `content` is the tool_result body the daemon
 * forwards; older rows (and some tools) instead carry `message` / `summary` /
 * `preview`, so fall through those before giving up.
 */
internal fun toolOutputText(result: JsonElement?): String? {
    val obj = result as? JsonObject ?: return (result as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    (obj["content"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
    // A structured content array (the Anthropic block shape) — join its text.
    (obj["content"] as? JsonArray)?.let { arr ->
        val text = arr.mapNotNull { el ->
            ((el as? JsonObject)?.get("text") as? JsonPrimitive)?.content
        }.joinToString("\n").trim()
        if (text.isNotEmpty()) return text
    }
    return obj.firstString("message", "summary", "preview", "report", "text")
}

/**
 * How an approval decision reads in the transcript.
 *
 * Deliberately phrased as something MAX did, not as a state the tool is in:
 * the whole point of recording it is that a human authorised (or refused) this
 * particular command, and scrollback should say so plainly a week later. The
 * server vocabulary is once / session / always / deny (tools/approval.py).
 */
// Public, not internal: reached from `:app`'s PromptRecordTest.
fun approvalRecordLabel(choice: String): String = when (choice) {
    "once" -> "You allowed this, once."
    "session" -> "You allowed this, and everything like it for the session."
    "always" -> "You allowed this, and everything like it from now on."
    "deny" -> "You denied this."
    // An unknown choice still gets recorded rather than swallowed — a decision
    // the UI can't name is not a decision that didn't happen.
    else -> "You answered the approval prompt: $choice."
}

/** The same decision as a chip-sized marker for the collapsed row. */
// Public, not internal: reached from `:app`'s PromptRecordTest.
fun approvalRecordChip(choice: String): String =
    if (choice == "deny") "denied" else "allowed"
