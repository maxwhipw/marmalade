package app.marmalade.android.chat.messages

import app.marmalade.android.rpc.ConnectionState
import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.JsonRpcClient
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.ModelListResponse
import app.marmalade.android.rpc.types.PromptSubmitAck
import app.marmalade.android.rpc.types.SecretRespondResult
import app.marmalade.android.rpc.types.SessionCreateResponse
import app.marmalade.android.rpc.types.SessionForkResponse
import app.marmalade.android.rpc.types.SessionLineageRef
import app.marmalade.android.rpc.types.SessionListResponse
import app.marmalade.android.rpc.types.SessionResumeResponse
import app.marmalade.android.rpc.types.SessionSeenResponse
import app.marmalade.android.rpc.types.SessionSubscribeResponse
import app.marmalade.android.rpc.types.SessionUndoResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Scriptable [MarmaladeRpc] for ChatController e2e tests. Overrides every
 * suspend method the controller calls with a sensible scriptable default
 * + records every promptSubmit so tests can assert on the wire.
 *
 * Drives:
 * - [connectionState] — flip via [openTransport] / [closeTransport].
 * - [events] — push gateway events via [emit].
 */
internal class FakeMarmaladeRpc : MarmaladeRpc(client = DummyJsonRpcClient) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 256)

    override val rpcClient: JsonRpcClient = TestJsonRpcClient(_connectionState, _events)

    val submittedPrompts = mutableListOf<PromptSubmitCall>()

    /** Records every approvalRespond call — guards the {choice, session_id,
     *  request_id?} daemon contract (session-keyed FIFO; the daemon-minted
     *  request_id rides along for exact correlation when known). */
    data class ApprovalRespondCall(val choice: String, val sessionId: String?, val all: Boolean, val requestId: String? = null)
    val approvalRespondCalls = mutableListOf<ApprovalRespondCall>()

    override suspend fun approvalRespond(choice: String, sessionId: String?, all: Boolean, requestId: String?) {
        approvalRespondCalls += ApprovalRespondCall(choice, sessionId, all, requestId)
    }

    /** Records every clarifyRespond call — guards the {session_id,
     *  request_id?, answers?, response?} daemon contract (empty answers +
     *  null response = dismissal). */
    data class ClarifyRespondCall(
        val requestId: String?,
        val sessionId: String,
        val answers: Map<String, String>,
        val response: String?,
    )
    val clarifyRespondCalls = mutableListOf<ClarifyRespondCall>()

    override suspend fun clarifyRespond(
        requestId: String?,
        sessionId: String,
        answers: Map<String, String>,
        response: String?,
    ) {
        clarifyRespondCalls += ClarifyRespondCall(requestId, sessionId, answers, response)
    }

    /** Records every secretRespond call — guards the strict `{session_id,
     *  request_id?, value XOR deny}` daemon contract. [value] is recorded
     *  ONLY so a test can prove the right string reached the wire; nothing
     *  else in the client is allowed to hold it. */
    data class SecretRespondCall(
        val sessionId: String,
        val requestId: String?,
        val value: String?,
        val deny: Boolean,
        val reason: String?,
    )
    val secretRespondCalls = mutableListOf<SecretRespondCall>()

    /** Scripted result for the next [secretRespond] — flip it to exercise the
     *  keyring-failure and already-expired branches. */
    var secretRespondResult = SecretRespondResult(resolved = true, stored = true)

    override suspend fun secretRespond(
        sessionId: String,
        requestId: String?,
        value: String?,
        deny: Boolean,
        reason: String?,
    ): SecretRespondResult {
        secretRespondCalls += SecretRespondCall(sessionId, requestId, value, deny, reason)
        return secretRespondResult
    }

    /** Records every sessionCreate call for assertions. */
    data class SessionCreateCall(
        val title: String?,
        val agentId: String? = null,
        /** Per-session model override (marmaladed session.create `model`). */
        val model: String? = null,
        /** Per-session reasoning effort (session.create `reasoning_effort`) —
         *  the composer's Thinking pick, sent only when it differs from the
         *  daemon default. */
        val reasoningEffort: String? = null,
    )
    val sessionCreateCalls = mutableListOf<SessionCreateCall>()

    /** When non-null, sessionCreate throws this instead of returning a response. */
    var sessionCreateError: Throwable? = null

    data class PromptSubmitCall(
        val sessionId: String,
        val text: String,
        val truncateBeforeUserOrdinal: Int?,
        val idempotencyKey: String?,
    )

    /** Resume payload returned by sessionResume (marmaladed: same id back). */
    var sessionResumeResponse: SessionResumeResponse =
        SessionResumeResponse(session_id = "server-session-1")

    var sessionCreateResponse: SessionCreateResponse =
        SessionCreateResponse(session_id = "server-session-1")

    /** Scriptable subscribe result + call log (P4 replay attach). */
    data class SessionSubscribeCall(val sessionId: String, val sinceSeq: Long)
    val sessionSubscribeCalls = mutableListOf<SessionSubscribeCall>()
    var sessionSubscribeResponse: SessionSubscribeResponse =
        SessionSubscribeResponse(replayed = 0, last_seq = 0, lifecycle = "active", run_state = "idle")

    override suspend fun sessionSubscribe(sessionId: String, sinceSeq: Long): SessionSubscribeResponse {
        sessionSubscribeCalls += SessionSubscribeCall(sessionId, sinceSeq)
        return sessionSubscribeResponse
    }

    /** Records every session.seen stamp (P4 read cursor). */
    data class SessionSeenCall(val sessionId: String, val seq: Long)
    val sessionSeenCalls = mutableListOf<SessionSeenCall>()

    override suspend fun sessionSeen(sessionId: String, seq: Long): SessionSeenResponse {
        sessionSeenCalls += SessionSeenCall(sessionId, seq)
        return SessionSeenResponse(seq = seq)
    }

    /** Ack returned by promptSubmit (null = legacy gateway, no server ids). */
    var promptSubmitAck: PromptSubmitAck? = null

    fun openTransport() { _connectionState.value = ConnectionState.Open }
    fun closeTransport() { _connectionState.value = ConnectionState.Closed }

    suspend fun emit(event: GatewayEvent) { _events.emit(event) }

    override suspend fun promptSubmit(
        sessionId: String,
        text: String,
        truncateBeforeUserOrdinal: Int?,
        idempotencyKey: String?,
        source: String?,
        timeout: kotlin.time.Duration?,
    ): PromptSubmitAck? {
        submittedPrompts.add(
            PromptSubmitCall(sessionId, text, truncateBeforeUserOrdinal, idempotencyKey),
        )
        return promptSubmitAck
    }

    override suspend fun sessionCreate(
        cols: Int,
        cwd: String?,
        model: String?,
        provider: String?,
        reasoningEffort: String?,
        fast: Boolean?,
        profile: String?,
        title: String?,
    ): SessionCreateResponse {
        sessionCreateCalls.add(
            SessionCreateCall(title = title, model = model, reasoningEffort = reasoningEffort),
        )
        sessionCreateError?.let { throw it }
        return sessionCreateResponse
    }

    override suspend fun sessionResume(
        sessionId: String,
        cols: Int,
    ): SessionResumeResponse = sessionResumeResponse

    /** Records every session.fork call; scriptable result or error (T2 #3). */
    data class SessionForkCall(val sessionId: String, val atMessageId: String?, val title: String?)
    val sessionForkCalls = mutableListOf<SessionForkCall>()

    /** When non-null, sessionFork throws this instead of returning. */
    var sessionForkError: Throwable? = null
    var sessionForkResponse: SessionForkResponse = SessionForkResponse(
        session_id = "server-fork-1",
        forked_from = SessionLineageRef(session_id = "server-session-main", message_id = null),
        full_context = true,
    )

    override suspend fun sessionFork(
        sessionId: String,
        atMessageId: String?,
        title: String?,
    ): SessionForkResponse {
        sessionForkCalls += SessionForkCall(sessionId, atMessageId, title)
        sessionForkError?.let { throw it }
        return sessionForkResponse
    }

    /** Scriptable response for sessionList. Default: empty list. */
    var sessionListResponse: SessionListResponse =
        SessionListResponse(sessions = emptyList())

    override suspend fun sessionList(limit: Int): SessionListResponse = sessionListResponse

    val interruptedSessions = mutableListOf<String>()
    override suspend fun sessionInterrupt(sessionId: String) {
        interruptedSessions += sessionId
    }

    override suspend fun sessionDelete(sessionId: String) {}

    override suspend fun sessionTitle(sessionId: String, title: String) {}

    /** Records every session.archive call — guards the {session_id, archived}
     *  contract. Set [sessionArchiveError] to script a rejection (mid-flight
     *  daemon restart / main-session refusal) for revert tests. */
    data class SessionArchiveCall(val sessionId: String, val archived: Boolean)
    val sessionArchiveCalls = mutableListOf<SessionArchiveCall>()
    var sessionArchiveError: Throwable? = null
    override suspend fun sessionArchive(
        sessionId: String,
        archived: Boolean,
    ): app.marmalade.android.rpc.types.SessionArchiveResponse {
        sessionArchiveCalls += SessionArchiveCall(sessionId, archived)
        sessionArchiveError?.let { throw it }
        return app.marmalade.android.rpc.types.SessionArchiveResponse(archived = archived)
    }

    /** Scriptable model.list menu (marmaladed). Default: empty. */
    var modelListResponse: ModelListResponse = ModelListResponse(models = emptyList())

    override suspend fun modelList(): ModelListResponse = modelListResponse

    // ── Steer / compact / undo (T2 #6 / #11a) ───────────────────────────────

    data class SessionSteerCall(val sessionId: String, val prompt: String, val source: String?)
    val sessionSteerCalls = mutableListOf<SessionSteerCall>()
    /** Ack returned by sessionSteer (null = no server ids). */
    var sessionSteerAck: PromptSubmitAck? = PromptSubmitAck(message_id = "steer-msg-1", seq = 5, ts = 5)

    override suspend fun sessionSteer(sessionId: String, prompt: String, source: String?): PromptSubmitAck? {
        sessionSteerCalls += SessionSteerCall(sessionId, prompt, source)
        return sessionSteerAck
    }

    val compactedSessions = mutableListOf<String>()
    override suspend fun sessionCompact(sessionId: String) { compactedSessions += sessionId }

    // ── Singleton main session (session.main / clear / model) ────────────────

    /** Records every session.model call — guards the {session_id, model}
     *  contract used to switch an EXISTING session's model in place. */
    data class SessionModelCall(val sessionId: String, val model: String)
    val sessionModelCalls = mutableListOf<SessionModelCall>()
    var sessionModelError: Throwable? = null
    override suspend fun sessionModel(sessionId: String, model: String): app.marmalade.android.rpc.types.SessionModelResponse {
        sessionModelCalls += SessionModelCall(sessionId, model)
        sessionModelError?.let { throw it }
        return app.marmalade.android.rpc.types.SessionModelResponse(model = model)
    }

    /** Records every session.effort call — guards the {session_id,
     *  reasoning_effort} contract used to change an EXISTING session's
     *  reasoning effort in place. */
    data class SessionEffortCall(val sessionId: String, val effort: String)
    val sessionEffortCalls = mutableListOf<SessionEffortCall>()
    var sessionEffortError: Throwable? = null
    override suspend fun sessionEffort(
        sessionId: String,
        reasoningEffort: String,
    ): app.marmalade.android.rpc.types.SessionEffortResponse {
        sessionEffortCalls += SessionEffortCall(sessionId, reasoningEffort)
        sessionEffortError?.let { throw it }
        return app.marmalade.android.rpc.types.SessionEffortResponse(reasoningEffort = reasoningEffort)
    }

    /** Records every session.clear call — the main session's reset-in-place. */
    val sessionClearCalls = mutableListOf<String>()
    override suspend fun sessionClear(sessionId: String): app.marmalade.android.rpc.types.SessionClearResponse {
        sessionClearCalls += sessionId
        return app.marmalade.android.rpc.types.SessionClearResponse(cleared = true)
    }

    val sessionUndoCalls = mutableListOf<String>()
    var sessionUndoError: Throwable? = null
    var sessionUndoResponse: SessionUndoResponse = SessionUndoResponse(files_rewound = false)

    override suspend fun sessionUndo(sessionId: String): SessionUndoResponse {
        sessionUndoCalls += sessionId
        sessionUndoError?.let { throw it }
        return sessionUndoResponse
    }

}

/** Dummy JsonRpcClient required by the MarmaladeRpc(client) superclass call.
 *  We never use it; FakeMarmaladeRpc overrides rpcClient to point at the
 *  TestJsonRpcClient with controllable state. */
private val DummyJsonRpcClient: JsonRpcClient by lazy {
    JsonRpcClient(
        webSocketFactory = object : app.marmalade.android.rpc.WebSocketFactory {
            override fun create(
                request: okhttp3.Request,
                listener: okhttp3.WebSocketListener,
            ): okhttp3.WebSocket = throw UnsupportedOperationException("test stub")
        },
    )
}

private class TestJsonRpcClient(
    state: StateFlow<ConnectionState>,
    events: SharedFlow<GatewayEvent>,
) : JsonRpcClient(
    webSocketFactory = object : app.marmalade.android.rpc.WebSocketFactory {
        override fun create(
            request: okhttp3.Request,
            listener: okhttp3.WebSocketListener,
        ): okhttp3.WebSocket = throw UnsupportedOperationException("test stub")
    },
) {
    override val connectionState: StateFlow<ConnectionState> = state
    override val events: SharedFlow<GatewayEvent> = events
}
