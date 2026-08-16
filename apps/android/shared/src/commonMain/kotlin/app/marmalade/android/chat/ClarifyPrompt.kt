package app.marmalade.android.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The daemon's clarify.request payload (agent questions, AskUserQuestion
 * round-trip). Wire truth: marmalade/packages/daemon/src/router.ts
 * makeSessionClarifies — `questions[]` of {question, header, options[]
 * {label, description}, multi_select}. The answer contract
 * (ClarifyRespondParams): `answers` maps question text → chosen answer,
 * multi-select answers comma-joined; `response` is freeform text; sending
 * NEITHER = dismissed (the agent proceeds on its own judgment).
 */
data class ClarifyOption(val label: String, val description: String)

data class ClarifyQuestion(
  val question: String,
  val header: String,
  val options: List<ClarifyOption>,
  val multiSelect: Boolean,
)

/** Parse the questions out of a clarify.request payload — or out of the
 *  `AskUserQuestion` tool call's own `input`, which carries the same
 *  questions[] in the harness's camelCase spelling (`multiSelect`). Both
 *  spellings are accepted precisely so the transient card and the PERSISTED
 *  tool row can share one parser; see [parseClarifyAnswers].
 *
 *  Defensive like the rest of the event layer: missing/malformed fields
 *  degrade to empties, never throw (the card simply renders what survived). */
fun parseClarifyQuestions(payload: JsonObject): List<ClarifyQuestion> {
  val questions = payload["questions"] as? JsonArray ?: return emptyList()
  return questions.mapNotNull { q ->
    val obj = q as? JsonObject ?: return@mapNotNull null
    ClarifyQuestion(
      question = obj.stringOr("question"),
      header = obj.stringOr("header"),
      options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { o ->
        val opt = o as? JsonObject ?: return@mapNotNull null
        ClarifyOption(label = opt.stringOr("label"), description = opt.stringOr("description"))
      },
      multiSelect = (obj["multi_select"] ?: obj["multiSelect"])
        .let { it as? JsonPrimitive }?.booleanOrNull ?: false,
    )
  }
}

/**
 * The answers the maintainer actually gave, read off the `AskUserQuestion` tool result.
 *
 * This is what makes the asked→answered→**recorded** lifecycle possible without
 * inventing a new persisted message part. `AskUserQuestion` reaches the daemon
 * through the SDK's `canUseTool` bridge, so it is a REAL tool call on the wire:
 * the daemon writes `tool.start` (carrying [parseClarifyQuestions]'s questions)
 * and `tool.complete` (carrying `result.answers`) into the session transcript,
 * where both are seq-ordered and replayed on subscribe. The `clarify.request` /
 * `clarify.resolved` events that drive the docked card are `emitTransient` and
 * are NOT — so the docked card is the ephemeral half and this pair is the
 * durable one. Verified on the wire 2026-07-27 (probe session `s_d914489f`:
 * tool.start seq 8 → clarify.request seq 9 → clarify.resolved seq 12 →
 * tool.complete seq 13, with only 8 and 13 in the transcript).
 *
 * Maps question text → the chosen answer, matching the wire `answers` contract
 * (multi-select answers comma-joined by the harness). Anything else degrades to
 * an empty map, which the card renders as "asked, no answer recorded" rather
 * than as unanswered.
 */
fun parseClarifyAnswers(result: JsonObject?): Map<String, String> {
  val answers = result?.get("answers") as? JsonObject ?: return emptyMap()
  return answers.mapNotNull { (question, value) ->
    val primitive = value as? JsonPrimitive ?: return@mapNotNull null
    if (!primitive.isString) return@mapNotNull null
    question to primitive.content
  }.toMap()
}

/** Fold the card's staged picks into the wire `answers` map: question text →
 *  chosen answer, multi-select selections comma-joined (the harness
 *  contract). Questions with no pick are omitted, not sent empty. */
fun buildClarifyAnswers(picks: Map<String, List<String>>): Map<String, String> =
  picks.filterValues { it.isNotEmpty() }.mapValues { (_, labels) -> labels.joinToString(", ") }

/** Title for an ask, docked or recorded. The count matters now that the card
 *  is a one-question-at-a-time wizard: it's the only place the size of the ask
 *  shows before you start answering. Zero questions = an unparseable ask,
 *  which still renders as one. */
fun clarifyTitle(questionCount: Int): String =
  if (questionCount > 1) "The agent has $questionCount questions" else "The agent has a question"

private fun JsonObject.stringOr(key: String, fallback: String = ""): String {
  val primitive = this[key] as? JsonPrimitive ?: return fallback
  return if (primitive.isString) primitive.content else fallback
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
