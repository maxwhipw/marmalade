package app.marmalade.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marmalade.android.chat.ClarifyQuestion
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.chat.clarifyTitle
import app.marmalade.android.chat.parseClarifyAnswers
import app.marmalade.android.chat.parseClarifyQuestions
import app.marmalade.android.ui.AgentPromptCard
import app.marmalade.android.ui.AgentPromptTone
import kotlinx.serialization.json.JsonObject

/**
 * A question the agent asked the maintainer, kept in the transcript afterwards.
 *
 * This is recommendation 2 of the `agent-session-ui` design lab — the one the
 * lab called its worst finding. Before this, an answered question was
 * *destroyed* on submit: the docked card vanished and nothing was written to
 * the message history, so a decision that steered the whole session left no
 * trace in scrollback, cold load, or search.
 *
 * The fix needs no new persisted message part and no synthesized row. The lab
 * assumed it would, but `AskUserQuestion` reaches the daemon through the SDK's
 * `canUseTool` bridge, which makes it a REAL tool call: the daemon writes
 * `tool.start` (the questions) and `tool.complete` (the maintainer's answers) into the
 * session transcript, seq-ordered and replayed on subscribe. The docked card's
 * `clarify.request`/`clarify.resolved` events are `emitTransient` and are not
 * persisted at all. So the durable record was already on the wire — it was just
 * rendering as a generic 🧩 `AskUserQuestion` tool card with raw JSON args.
 *
 * Three states, one frame:
 *  - **asked** — [ChatMessagePart.ToolCall.result] is null. The questions and
 *    the options offered, read-only and fully expanded; answering happens on
 *    the docked card above the composer, which this card says out loud.
 *  - **answered** — the result carries `answers`. Collapses to ONE line: the
 *    answers themselves ([clarifyRecordSummary]). Tap to expand the full
 *    record — every question, the options that were on the table, and which
 *    one was chosen. A settled question is scrollback, not an interface, and a
 *    two-question ask left open is a wall of options in the middle of the
 *    transcript.
 *  - **unanswered** — the result exists but carries no answers, which is what a
 *    dismissal looks like on the wire: the agent was told to proceed on its own
 *    judgment. Recorded as such rather than as an answer.
 */
@Composable
fun PromptRecordCard(
    part: ChatMessagePart.ToolCall,
    modifier: Modifier = Modifier,
) {
    val questions = remember(part.args, part.argsText) {
        parseClarifyQuestions(part.displayArgs())
    }
    val answers = remember(part.result) { parseClarifyAnswers(part.result as? JsonObject) }
    val settled = part.result != null
    val dismissed = settled && answers.isEmpty()
    // Per-card, not persisted: which record you last opened is not worth
    // carrying across a cold load.
    var expanded by remember(part.toolCallId) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        AgentPromptCard(
            title = when {
                dismissed -> "Asked · dismissed"
                settled && questions.size > 1 -> "Asked · answered · ${questions.size} questions"
                settled -> "Asked · answered"
                else -> clarifyTitle(questions.size)
            },
            // While the agent is parked, say where the answer goes — the card
            // here is the record, not the input surface.
            detail = if (settled) null else "Waiting on you — answer above the composer",
            tone = if (settled) AgentPromptTone.Neutral else AgentPromptTone.Active,
        ) {
            if (questions.isEmpty()) {
                // A malformed or unparseable ask still gets a row: losing the
                // fact that a question happened is the bug this card exists to
                // fix, so degrade to a marker rather than rendering nothing.
                Text(
                    text = "A question was asked, but its contents could not be read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Settled and closed: one line. A finished question is scrollback,
            // not an interface — but it must still SAY what was decided, which
            // is the whole reason this card exists, so the summary is the
            // answers themselves rather than a bare count.
            if (settled && questions.isNotEmpty() && !expanded) {
                Text(
                    text = clarifyRecordSummary(questions, answers),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (dismissed) FontWeight.Normal else FontWeight.Medium,
                    color = if (dismissed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .padding(vertical = 2.dp),
                )
            } else {
                questions.forEach { q ->
                    QuestionRecord(
                        question = q,
                        answer = answers[q.question],
                        settled = settled,
                        dismissed = dismissed,
                        // Options are context for the choice: always shown
                        // while the ask is live, and part of what "expand"
                        // reveals once it has settled.
                        showOptions = true,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (settled && questions.isNotEmpty()) {
                Text(
                    text = if (expanded) "Hide" else "Show what was asked",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * The one line a settled ask collapses to.
 *
 * The answers themselves, in the order the questions were asked — a count
 * would hide the decision, and the decision being visible in scrollback is the
 * entire reason this card exists. Unanswered questions of a partly-answered
 * ask are omitted here; the expanded record accounts for them.
 *
 * Public for [PromptRecordTest] — the collapse is the part with rules.
 */
fun clarifyRecordSummary(
    questions: List<ClarifyQuestion>,
    answers: Map<String, String>,
): String {
    if (answers.isEmpty()) return DISMISSED
    val chosen = questions.mapNotNull { answers[it.question] }
    // Answers that match no question text (a harness that rekeyed them, a
    // truncated arg reparse): still say something true rather than nothing.
    if (chosen.isEmpty()) return "Answered"
    return chosen.joinToString(" · ")
}

/** Sending neither answers nor response IS the dismissal contract: the agent
 *  was told to proceed on its own judgment. Not the same as a choice. */
const val DISMISSED = "Dismissed — the agent proceeded on its own judgment"

/** One question's record: what was asked, and — once settled — what was chosen. */
@Composable
private fun QuestionRecord(
    question: ClarifyQuestion,
    answer: String?,
    settled: Boolean,
    showOptions: Boolean,
    /** True when the ask settled with no answers at all — a dismissal. With
     *  SOME answers present, an unanswered question was skipped, not
     *  dismissed, and the record must not conflate the two. */
    dismissed: Boolean = true,
) {
    if (question.header.isNotBlank()) {
        Text(
            text = question.header.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (question.question.isNotBlank()) {
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    if (settled) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "→",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                // No answer on a settled ask means it was dismissed (or nobody
                // could answer) — the daemon then tells the agent to use its own
                // judgment. That is a different outcome from a choice, and the
                // record has to say so. A question skipped within an otherwise
                // answered ask is a third thing again.
                text = answer ?: if (dismissed) DISMISSED else "No answer given",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (answer != null) FontWeight.Medium else FontWeight.Normal,
                color = if (answer != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    // Plain `if`, deliberately not AnimatedVisibility: this card is an item in
    // a reverse-layout LazyColumn, and animating an item's height from inside
    // the item feeds the list's scroll anchor a moving target for the length of
    // the animation. Expansion is a tap; it does not need a transition.
    if (showOptions && question.options.isNotEmpty()) {
        Column {
            Spacer(Modifier.height(6.dp))
            question.options.forEach { option ->
                val chosen = settled && answer != null && answer.split(", ").contains(option.label)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (chosen) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                            color = if (chosen) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (option.description.isNotBlank()) {
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (chosen) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * The LazyColumn key of the inline card for the question the agent is parked
 * on, or null if there isn't one on screen to point at.
 *
 * "The" question is unambiguous: the daemon serializes clarifies per session
 * (a second question parks BEHIND the first), so at most one `AskUserQuestion`
 * is outstanding at a time — the last unsettled one is it. That structural
 * guarantee is what lets the dock point at the row without any id correlation,
 * which matters because the transient `clarify.request`'s `request_id` and the
 * tool call's `tool_use_id` are different ids with no link on the wire.
 *
 * Returning null is the safe answer and the caller MUST treat it that way: no
 * inline row means the docked card is the only way to answer, so it has to keep
 * rendering in full.
 */
// Public, not internal: reached from `:app`'s ChatScreen.
fun parkedQuestionRowKey(messages: List<ChatMessage>): String? =
    messages
        .asReversed()
        .firstNotNullOfOrNull { message ->
            message.parts
                .filterIsInstance<ChatMessagePart.ToolCall>()
                .lastOrNull { it.isAgentQuestion && it.result == null }
        }
        ?.let { "prompt:${it.toolCallId}" }
