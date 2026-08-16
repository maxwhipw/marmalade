package app.marmalade.android.chat.messages

import app.marmalade.android.rpc.ConnectionState
import app.marmalade.android.rpc.MarmaladeRpc
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow transport surface the [OutboxDrainer] depends on. Decouples the
 * drainer from the full [MarmaladeRpc] surface so tests can substitute a
 * fake without instantiating a real JsonRpcClient. The production wiring
 * is the [marmaladeRpcAdapter] factory below.
 */
interface PromptTransport {
    val connectionState: StateFlow<ConnectionState>

    /**
     * Submit a prompt; returns the server-minted identity of the accepted
     * user message ({message_id, seq, ts}, identity plan P1) or null on a
     * legacy gateway that mints nothing.
     */
    suspend fun submitPrompt(
        sessionId: String,
        text: String,
        truncateBeforeUserOrdinal: Int?,
        idempotencyKey: String,
        source: String? = null,
    ): app.marmalade.android.rpc.types.PromptSubmitAck?

    /**
     * Re-resume an old server session_id to mint a fresh live one. Used by
     * [OutboxDrainer] when a prompt.submit returns "session not found"
     * (gateway restarted between outbox-insert and drain). Returns the new
     * server-side session id, or null if the resume itself failed.
     * Matches desktop's recovery path at
     * `apps/desktop/src/app/session/hooks/use-prompt-actions.ts:710-721`.
     */
    suspend fun resumeSession(staleSessionId: String): String?

    /**
     * Upload image bytes (base64) and queue them on [sessionId]; the queue is
     * consumed by the next prompt.submit for that session. Throws on RPC
     * failure or a `attached=false` response so the drainer's backoff path
     * owns the retry.
     */
    suspend fun attachImageBytes(sessionId: String, contentBase64: String, filename: String)

    /**
     * Stage a non-image file (base64 data URL) into the session workspace.
     * Returns the `@file:` ref text to prepend to the prompt. Throws on
     * failure like [attachImageBytes].
     */
    suspend fun attachFile(sessionId: String, name: String, dataUrl: String): String
}

/** Production adapter — wraps [MarmaladeRpc]. */
fun marmaladeRpcAdapter(rpc: MarmaladeRpc): PromptTransport = object : PromptTransport {
    override val connectionState: StateFlow<ConnectionState> = rpc.rpcClient.connectionState
    override suspend fun submitPrompt(
        sessionId: String,
        text: String,
        truncateBeforeUserOrdinal: Int?,
        idempotencyKey: String,
        source: String?,
    ): app.marmalade.android.rpc.types.PromptSubmitAck? {
        return rpc.promptSubmit(
            sessionId = sessionId,
            text = text,
            truncateBeforeUserOrdinal = truncateBeforeUserOrdinal,
            idempotencyKey = idempotencyKey,
            source = source,
        )
    }

    override suspend fun resumeSession(staleSessionId: String): String? {
        return runCatching {
            rpc.sessionResume(sessionId = staleSessionId, cols = 80)
                .session_id.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override suspend fun attachImageBytes(sessionId: String, contentBase64: String, filename: String) {
        val result = rpc.imageAttachBytes(
            sessionId = sessionId,
            contentBase64 = contentBase64,
            filename = filename,
        )
        check(result.attached) { result.message ?: "image attach refused: $filename" }
    }

    override suspend fun attachFile(sessionId: String, name: String, dataUrl: String): String {
        val result = rpc.fileAttach(sessionId = sessionId, name = name, dataUrl = dataUrl)
        val refText = result.refText
        if (!result.attached || refText.isNullOrBlank()) {
            error(result.message ?: "file attach refused: $name")
        }
        return refText
    }
}
