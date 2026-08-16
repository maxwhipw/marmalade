package app.marmalade.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.marmalade.android.ui.AgentPromptCard
import app.marmalade.android.ui.AgentPromptTone
import app.marmalade.android.chat.ClarifyWizard
import app.marmalade.android.chat.PendingPrompt
import app.marmalade.android.chat.PromptKind
import app.marmalade.android.chat.parseClarifyQuestions
import app.marmalade.android.ui.icons.MarmaladeIcons
import kotlinx.coroutines.delay

/**
 * Sticky stack of pending interactive prompts. Lives above the
 * composer (not in the message flow) so they don't scroll away. Each
 * card calls back into [onClarify] / [onApproval] / [onSecret]; [onDismiss]
 * removes a card locally without sending a response.
 *
 * [onJumpToContext] — non-null when the transcript holds the inline record of
 * the parked question — renders a jump link inside the clarify card's header.
 * It is deliberately part of the card rather than a separate docked pointer:
 * the pointer's presence was a function of the list's `visibleItemsInfo`, and
 * a bottom bar sized by what the list can see oscillates (see ChatScreen).
 */
@Composable
fun PromptCards(
    prompts: List<PendingPrompt>,
    onClarify: (requestId: String, answers: Map<String, String>, response: String?) -> Unit,
    onApproval: (requestId: String, decision: String) -> Unit,
    onSecret: (requestId: String, value: String) -> Unit,
    /** Refuse a secret request on the wire. Separate from [onDismiss] because
     *  the secret card has no local-only close — see its Secret branch below. */
    onSecretDeny: (requestId: String) -> Unit,
    onSudo: (requestId: String, password: String) -> Unit,
    onDismiss: (requestId: String) -> Unit,
    modifier: Modifier = Modifier,
    onJumpToContext: (() -> Unit)? = null,
) {
    if (prompts.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        prompts.forEach { prompt ->
            when (prompt.kind) {
                PromptKind.Clarify -> ClarifyCard(
                    prompt = prompt,
                    onSubmit = { answers, response -> onClarify(prompt.requestId, answers, response) },
                    onJumpToContext = onJumpToContext,
                    // The X ANSWERS with nothing (daemon dismissal): the parked
                    // AskUserQuestion settles with a proceed-on-your-own
                    // message. A local-only close would leave the agent
                    // parked until the turn/connection ends.
                    onDismiss = { onClarify(prompt.requestId, emptyMap(), null) },
                )
                PromptKind.Approval -> ApprovalCard(
                    prompt = prompt,
                    onDecide = { onApproval(prompt.requestId, it) },
                    onDismiss = { onDismiss(prompt.requestId) },
                )
                // The X DENIES (secret.respond {deny:true}). A secret request
                // parks the agent's tool call for ten minutes; closing the
                // card locally would leave it hanging there, and the agent
                // would have no idea the user had already said no.
                PromptKind.Secret -> SecretCard(
                    prompt = prompt,
                    onSubmit = { onSecret(prompt.requestId, it) },
                    onDeny = { onSecretDeny(prompt.requestId) },
                )
                // Sudo gets its own card: same masked input, but it also shows
                // the command and says plainly that it runs as root. See
                // [SudoCard] for why the shared-with-Secret version was wrong.
                PromptKind.Sudo -> SudoCard(
                    prompt = prompt,
                    onSubmit = { onSudo(prompt.requestId, it) },
                    onDismiss = { onDismiss(prompt.requestId) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Thin adapter onto the shared [AgentPromptCard] — the single frame every ask
 * in the app renders into, docked or inline. Kept as a local name so the four
 * card composables below read unchanged.
 */
@Composable
private fun PromptCardFrame(
    title: String,
    detail: String?,
    onDismiss: () -> Unit,
    tone: AgentPromptTone = AgentPromptTone.Neutral,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) = AgentPromptCard(
    title = title,
    detail = detail,
    onDismiss = onDismiss,
    tone = tone,
    leading = leading,
) { content() }

/**
 * The docked answer card for an agent question — a one-question-at-a-time
 * wizard (maintainer, 2026-08-01).
 *
 * A clarify request carries up to four questions. Rendering them stacked put
 * the answer button off the bottom of the screen on a two-question ask, so the
 * card steps: header eyebrow + "2/3" chip, one question's options, a free-text
 * field, and a button that reads "Next" until the last step and "Answer" there.
 *
 * Picking on a single-select question carries you forward on its own after a
 * short highlight beat — except on the last step, where the button stays the
 * confirmation so a mis-tap is recoverable. All the rules live in
 * [ClarifyWizard]; this composable only renders them.
 */
@Composable
private fun ClarifyCard(
    prompt: PendingPrompt,
    onSubmit: (answers: Map<String, String>, response: String?) -> Unit,
    onDismiss: () -> Unit,
    onJumpToContext: (() -> Unit)? = null,
) {
    // Daemon payload (2026-07-18): {request_id, questions[]} — structured
    // AskUserQuestion mirror.
    var wizard by remember(prompt.requestId) {
        mutableStateOf(ClarifyWizard(parseClarifyQuestions(prompt.payload)))
    }
    // Bumped by a pick that should auto-advance; the effect below gives the
    // selection a beat to register before the step changes under the finger.
    var advanceToken by remember(prompt.requestId) { mutableStateOf(0) }
    LaunchedEffect(advanceToken) {
        if (advanceToken == 0) return@LaunchedEffect
        delay(PICK_ADVANCE_DELAY_MS)
        wizard = wizard.next()
    }
    val question = wizard.current

    PromptCardFrame(title = prompt.title, detail = prompt.detail, onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (wizard.canGoBack) {
                IconButton(
                    onClick = { wizard = wizard.back() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Previous question",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            if (question != null && question.header.isNotBlank()) {
                Text(
                    text = question.header.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            wizard.stepLabel?.let { step ->
                Text(
                    text = step,
                    // Tabular figures: the chip must not twitch as 9/10 → 10/10.
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onJumpToContext != null) {
            Text(
                text = "Jump to where this was asked ↑",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onJumpToContext).padding(vertical = 4.dp),
            )
        }
        if (question != null && question.question.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = question.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (question != null && question.options.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            // Capped + scrollable so the card can never grow past the screen,
            // which is the whole point of the wizard. A fixed dp rather than a
            // fraction of the viewport: a bottom bar is measured with an
            // unbounded main axis, so there is no viewport height to take a
            // fraction OF without reading layout back — and reading layout back
            // into the bar's height is exactly the loop this card just escaped.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = OPTIONS_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                question.options.forEach { option ->
                    val selected = wizard.isPicked(option.label)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        onClick = {
                            val advance = wizard.advancesOnPick
                            wizard = wizard.pick(option.label)
                            if (advance && wizard.isPicked(option.label)) advanceToken++
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                if (option.description.isNotBlank()) {
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // Multi-select is the case where "what have I
                            // picked so far" has to survive picking the next
                            // one, so it gets an explicit checkmark.
                            if (selected && question.multiSelect) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // Compact answer field: pin the text style (the default LocalTextStyle
        // in this subtree rendered a comically tall input — the maintainer, 2026-07-03)
        // and cap growth at a few lines.
        OutlinedTextField(
            value = wizard.currentText,
            onValueChange = { wizard = wizard.type(it) },
            placeholder = {
                Text(
                    if (question == null) "Your answer" else "Or type your own answer",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 1,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (wizard.isLastStep) {
                Button(
                    onClick = { onSubmit(wizard.answers(), wizard.response()) },
                    enabled = wizard.canSubmit,
                ) { Text("Answer") }
            } else {
                Button(onClick = { wizard = wizard.next() }) { Text("Next") }
            }
        }
    }
}

/** Beat between picking a single-select option and the wizard stepping on —
 *  long enough to see the selection land, short enough not to feel laggy. */
private const val PICK_ADVANCE_DELAY_MS = 200L

/** Ceiling on the option list before it scrolls internally. Roughly 40% of a
 *  phone viewport; the wire caps options at four, so this only bites on a
 *  malformed ask. */
private val OPTIONS_MAX_HEIGHT = 260.dp

@Composable
private fun ApprovalCard(
    prompt: PendingPrompt,
    onDecide: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Server contract (tools/approval.py): choices are once/session/always/
    // deny; "session"/"always" allowlist the matched pattern_key server-side
    // ("approve all of this kind"). allow_permanent=false means a tirith
    // warning downgraded the pattern — the server won't honor "always", so
    // don't offer it (desktop parity, tool-approval.tsx:71).
    val allowPermanent =
        (prompt.payload["allow_permanent"] as? JsonPrimitive)?.booleanOrNull ?: true
    val command = (prompt.payload["command"] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
    PromptCardFrame(
        title = prompt.title,
        detail = prompt.detail,
        onDismiss = onDismiss,
        tone = AgentPromptTone.Danger,
    ) {
        command?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalButton(
                onClick = { onDecide("once") },
                modifier = Modifier.weight(1f),
            ) { Text("Allow once") }
            FilledTonalButton(
                onClick = { onDecide("session") },
                modifier = Modifier.weight(1f),
            ) { Text("This session") }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (allowPermanent) {
                FilledTonalButton(
                    onClick = { onDecide("always") },
                    modifier = Modifier.weight(1f),
                ) { Text("Always") }
            }
            OutlinedButton(
                onClick = { onDecide("deny") },
                modifier = Modifier.weight(1f),
            ) { Text("Deny") }
        }
    }
}

/**
 * The secure-input card for the daemon's secret-entry flow.
 *
 * This is the only card in the app where the user types something the model
 * must never see, so it is deliberately NOT the generic [CredentialCard]:
 *
 *  - **A shield-key mark and an explicit line** — "the agent never sees what
 *    you type" — because the reason this card exists is invisible otherwise.
 *    A masked field alone only says "secret", not "secret *from whom*".
 *  - **The entry path is the focal claim.** `payload.entry` is the daemon's
 *    promise about where the value lands (`gopass insert <entry>`); showing it
 *    in monospace, labelled, is what makes the promise checkable. A card that
 *    says only "enter your password" is indistinguishable from a phishing
 *    prompt.
 *  - **The description is model-authored** — untrusted text. Plain [Text], no
 *    markdown, no annotated links, no linkification: a request that renders
 *    "click here to verify" as a tappable link is a credential-phishing
 *    primitive handed to whatever wrote the prompt.
 *  - **Dismiss means deny**, not a local close: the agent is parked on the
 *    tool call. See [PromptCards]' Secret branch.
 *
 * The typed value lives only in the `remember(requestId)` below: never a
 * draft, never the clipboard, never a log line. Window-level hardening
 * (FLAG_SECURE, autofill suppression) is Android-specific and lives in
 * MainActivity, which watches for a pending Secret card.
 */
@Composable
private fun SecretCard(
    prompt: PendingPrompt,
    onSubmit: (String) -> Unit,
    onDeny: () -> Unit,
) {
    var value by remember(prompt.requestId) { mutableStateOf("") }
    val entry = prompt.payload.stringOrNull("entry")
    PromptCardFrame(
        title = "Secret requested",
        // The description rides in the body (below the entry path) rather than
        // the frame's detail slot: the path has to be read first.
        detail = null,
        onDismiss = onDeny,
        tone = AgentPromptTone.Active,
        leading = {
            Icon(
                imageVector = MarmaladeIcons.ShieldKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        },
    ) {
        Text(
            text = "The agent never sees what you type here — it goes straight to this device's keyring.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (entry != null) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        text = "STORED AT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        prompt.detail?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(10.dp))
            // Untrusted, model-authored. Plain text only — see the KDoc.
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = { Text("Value") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                // No autocorrect / no learning: the IME must not add a
                // credential to its personal dictionary or suggestion strip.
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.weight(1f),
            ) { Text("Don't provide") }
            Button(
                onClick = { onSubmit(value) },
                enabled = value.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("Store") }
        }
    }
}

/**
 * Sudo, with the command it is about to run as root.
 *
 * This used to reuse [SecretCard] verbatim on the reasoning that the wire
 * payload is just a password string either way. The shapes did match — but the
 * stakes did not: sudo is the highest-consequence prompt in the app and was
 * the only one that never said WHAT it was authorising, while the strictly
 * lower-privilege approval card showed its command. That inverted the risk
 * signal, so sudo now carries the same disclosure plus an explicit root
 * warning (design-lab `agent-session-ui`).
 *
 * If the server sends no command, the warning still renders — "something, as
 * root" is the honest reading, and silence would be worse.
 */
@Composable
private fun SudoCard(
    prompt: PendingPrompt,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) = CredentialCard(
    prompt = prompt,
    placeholder = "Password",
    command = prompt.payload.stringOrNull("command", "cmd"),
    warning = "Runs with root privileges on this machine.",
    onSubmit = onSubmit,
    onDismiss = onDismiss,
)

/** Shared masked-credential frame for [SecretCard] / [SudoCard]. */
@Composable
private fun CredentialCard(
    prompt: PendingPrompt,
    placeholder: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    command: String? = null,
    warning: String? = null,
) {
    var value by remember(prompt.requestId) { mutableStateOf("") }
    PromptCardFrame(
        title = prompt.title,
        detail = prompt.detail,
        onDismiss = onDismiss,
        tone = if (warning != null) AgentPromptTone.Danger else AgentPromptTone.Neutral,
    ) {
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }
        if (command != null) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = { Text(placeholder) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onSubmit(value) },
                enabled = value.isNotBlank(),
            ) { Text("Submit") }
        }
    }
}

/** First non-blank string among [keys] on a prompt payload. */
private fun JsonObject.stringOrNull(vararg keys: String): String? {
    for (k in keys) {
        val v = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (!v.isNullOrEmpty()) return v
    }
    return null
}
