package app.marmalade.android.chat.messages

import app.marmalade.android.rpc.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Scriptable [PromptTransport] for OutboxDrainer tests.
 *
 * Drives:
 * - [connectionState] — call [open] / [close] to flip the upstream
 *   ConnectionState the drainer subscribes to.
 * - submitPrompt behavior — [queueSuccess] / [queueFailure] / set
 *   [defaultBehavior] to control how each call resolves.
 *
 * Records:
 * - [submitCalls] — every (sessionId, text, truncate, idempotencyKey) the
 *   drainer fired, in order. Tests assert on this for "did the drainer
 *   actually send" + idempotency-key plumbing.
 */
internal class FakePromptTransport(
    initialState: ConnectionState = ConnectionState.Idle,
) : PromptTransport {

    data class Call(
        val sessionId: String,
        val text: String,
        val truncateBeforeUserOrdinal: Int?,
        val idempotencyKey: String,
        val source: String? = null,
    )

    sealed class Behavior {
        object Success : Behavior()
        data class Throw(val message: String) : Behavior()
    }

    private val _connectionState = MutableStateFlow(initialState)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val submitCalls: MutableList<Call> = mutableListOf()

    /** Default outcome for any submitPrompt call without a queued behavior. */
    var defaultBehavior: Behavior = Behavior.Success

    private val queuedBehaviors = ArrayDeque<Behavior>()

    fun open() { _connectionState.value = ConnectionState.Open }
    fun close() { _connectionState.value = ConnectionState.Closed }
    fun setState(state: ConnectionState) { _connectionState.value = state }

    /** Next submitPrompt call returns success. */
    fun queueSuccess() { queuedBehaviors.addLast(Behavior.Success) }

    /** Next submitPrompt call throws [IOException] with the given message. */
    fun queueFailure(message: String = "network") { queuedBehaviors.addLast(Behavior.Throw(message)) }

    /** Ack returned on successful submits (null = legacy gateway). */
    var promptSubmitAck: app.marmalade.android.rpc.types.PromptSubmitAck? = null

    override suspend fun submitPrompt(
        sessionId: String,
        text: String,
        truncateBeforeUserOrdinal: Int?,
        idempotencyKey: String,
        source: String?,
    ): app.marmalade.android.rpc.types.PromptSubmitAck? {
        submitCalls.add(
            Call(
                sessionId = sessionId,
                text = text,
                truncateBeforeUserOrdinal = truncateBeforeUserOrdinal,
                idempotencyKey = idempotencyKey,
                source = source,
            ),
        )
        val behavior = queuedBehaviors.removeFirstOrNull() ?: defaultBehavior
        return when (behavior) {
            Behavior.Success -> promptSubmitAck
            is Behavior.Throw -> throw IOException(behavior.message)
        }
    }

    /** Override to script session-recover behaviour in tests. Default
     *  no-op returns null so the drainer's fallback-to-backoff path is
     *  exercised unless the test overrides this. */
    var resumeSessionImpl: suspend (String) -> String? = { null }

    override suspend fun resumeSession(staleSessionId: String): String? =
        resumeSessionImpl(staleSessionId)

    // ── Attachments ─────────────────────────────────────────────────────────

    data class ImageAttachCall(val sessionId: String, val contentBase64: String, val filename: String)
    data class FileAttachCall(val sessionId: String, val name: String, val dataUrl: String)

    val imageAttachCalls: MutableList<ImageAttachCall> = mutableListOf()
    val fileAttachCalls: MutableList<FileAttachCall> = mutableListOf()

    /** When set, the next matching attach call throws with this message. */
    var attachFailure: String? = null

    /** ref_text returned per file name; defaults to `@file:<name>`. */
    var fileRefTextFor: (String) -> String = { name -> "@file:$name" }

    override suspend fun attachImageBytes(sessionId: String, contentBase64: String, filename: String) {
        imageAttachCalls.add(ImageAttachCall(sessionId, contentBase64, filename))
        attachFailure?.let { attachFailure = null; throw IOException(it) }
    }

    override suspend fun attachFile(sessionId: String, name: String, dataUrl: String): String {
        fileAttachCalls.add(FileAttachCall(sessionId, name, dataUrl))
        attachFailure?.let { attachFailure = null; throw IOException(it) }
        return fileRefTextFor(name)
    }
}
