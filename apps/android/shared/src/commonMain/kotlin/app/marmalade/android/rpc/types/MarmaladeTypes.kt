/*
 * Hand-port of hermes-agent upstream: apps/desktop/src/types/hermes.ts.
 *
 * Wire-format DTOs. Field names use snake_case to match the JSON wire format
 * exactly — that's a deliberate departure from Kotlin's camelCase convention.
 * Domain models (ChatMessage etc.) still use camelCase. The boundary between
 * "wire" and "domain" lives in MarmaladeRpc.kt (future commit), which calls
 * JsonRpcClient.request(), decodes into these DTOs, and adapts to camelCase
 * domain types.
 *
 * Decoded by JsonRpcClient.DefaultJson:
 *   - ignoreUnknownKeys = true   (forward-compat with server field additions)
 *   - explicitNulls = false       (omit null fields on encode)
 *   - coerceInputValues = false   (server bugs surface as decode errors)
 *
 * Nullable fields with `= null` default match TS's optional fields. Required
 * fields are non-null. `Map<String, JsonElement>` substitutes for TS's
 * `Record<string, unknown>`. `JsonElement` substitutes for `unknown`.
 */
@file:Suppress("ConstructorParameterNaming", "PropertyName") // wire snake_case is intentional

package app.marmalade.android.rpc.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

// ── Config ──────────────────────────────────────────────────────────────────

@Serializable
data class ConfigFieldSchema(
    val category: String? = null,
    val description: String? = null,
    val options: List<JsonElement>? = null,
    val type: String? = null, // 'boolean' | 'list' | 'number' | 'select' | 'string' | 'text'
)

@Serializable
data class ConfigSchemaResponse(
    val category_order: List<String>? = null,
    val fields: Map<String, ConfigFieldSchema>,
)

@Serializable
data class HermesConfig(
    val agent: AgentConfig? = null,
    val display: DisplayConfig? = null,
    val terminal: TerminalConfig? = null,
    val stt: SttConfig? = null,
    val voice: VoiceConfig? = null,
) {
    @Serializable
    data class AgentConfig(
        val reasoning_effort: String? = null,
        val personalities: Map<String, JsonElement>? = null,
        val service_tier: String? = null,
    )
    @Serializable
    data class DisplayConfig(val personality: String? = null, val skin: String? = null)
    @Serializable
    data class TerminalConfig(val cwd: String? = null)
    @Serializable
    data class SttConfig(val enabled: Boolean? = null)
    @Serializable
    data class VoiceConfig(val max_recording_seconds: Int? = null)
}

/** Untyped raw config map — the dashboard's free-form config editor reads/
 *  writes the YAML as a JSON tree. */
typealias HermesConfigRecord = Map<String, JsonElement>

// ── Audio / Voice ───────────────────────────────────────────────────────────

@Serializable
data class AudioTranscriptionResponse(
    val ok: Boolean,
    val provider: String? = null,
    val transcript: String,
)

@Serializable
data class AudioSpeakResponse(
    val ok: Boolean,
    val data_url: String,
    val mime_type: String,
    val provider: String? = null,
)

@Serializable
data class ElevenLabsVoice(val label: String, val name: String, val voice_id: String)

@Serializable
data class ElevenLabsVoicesResponse(val available: Boolean, val voices: List<ElevenLabsVoice>)

// ── OAuth ───────────────────────────────────────────────────────────────────

@Serializable
data class OAuthProviderStatus(
    val error: String? = null,
    val expires_at: String? = null,
    val has_refresh_token: Boolean? = null,
    val last_refresh: String? = null,
    val logged_in: Boolean,
    val source: String? = null,
    val source_label: String? = null,
    val token_preview: String? = null,
)

@Serializable
data class OAuthProvider(
    val cli_command: String,
    /** Shell command that clears an external provider's credentials. */
    val disconnect_command: String? = null,
    val disconnect_hint: String? = null,
    val disconnectable: Boolean? = null,
    val docs_url: String,
    val flow: String, // 'device_code' | 'external' | 'loopback' | 'pkce'
    val id: String,
    val name: String,
    val status: OAuthProviderStatus,
)

@Serializable
data class OAuthProvidersResponse(val providers: List<OAuthProvider>)

/**
 * Discriminated union on `flow`. Three sub-shapes per the TS source —
 * `pkce` / `device_code` / `loopback`.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("flow")
sealed class OAuthStartResponse {
    abstract val expires_in: Int
    abstract val session_id: String

    @Serializable @SerialName("pkce")
    data class Pkce(
        val auth_url: String,
        override val expires_in: Int,
        override val session_id: String,
    ) : OAuthStartResponse()

    @Serializable @SerialName("device_code")
    data class DeviceCode(
        override val expires_in: Int,
        val poll_interval: Int,
        override val session_id: String,
        val user_code: String,
        val verification_url: String,
    ) : OAuthStartResponse()

    @Serializable @SerialName("loopback")
    data class Loopback(
        val auth_url: String,
        override val expires_in: Int,
        override val session_id: String,
    ) : OAuthStartResponse()
}

@Serializable
data class OAuthSubmitResponse(
    val message: String? = null,
    val ok: Boolean,
    val status: String, // 'approved' | 'error'
)

@Serializable
data class OAuthPollResponse(
    val error_message: String? = null,
    // NOTE (#17): currently unused. If wired up, re-check the wire type — the
    // device-code path sends expires_at as a STRING (web_server.py:4703
    // `str(...)`), so this Long? would crash decode. Model as String? then.
    val expires_at: Long? = null,
    val session_id: String,
    val status: String, // 'approved' | 'denied' | 'error' | 'expired' | 'pending'
)

// ── Environment variables ───────────────────────────────────────────────────

@Serializable
data class EnvVarInfo(
    val advanced: Boolean,
    val category: String,
    /** True when this var is a messaging-platform credential owned by a card on
     *  the dedicated Messaging page. The Keys page hides these to avoid
     *  duplicating the richer channel-configuration UI. */
    val channel_managed: Boolean? = null,
    val description: String,
    val is_password: Boolean,
    val is_set: Boolean,
    val redacted_value: String? = null,
    val tools: List<String>,
    val url: String? = null,
)

// ── Messaging platforms ────────────────────────────────────────────────────

@Serializable
data class MessagingEnvVarInfo(
    val advanced: Boolean,
    val description: String,
    val is_password: Boolean,
    val is_set: Boolean,
    val key: String,
    val prompt: String,
    val redacted_value: String? = null,
    val required: Boolean,
    val url: String? = null,
)

@Serializable
data class MessagingHomeChannel(
    val chat_id: String,
    val name: String,
    val platform: String,
    val thread_id: String? = null,
)

@Serializable
data class MessagingPlatformInfo(
    val configured: Boolean,
    val description: String,
    val docs_url: String,
    val enabled: Boolean,
    val env_vars: List<MessagingEnvVarInfo>,
    val error_code: String? = null,
    val error_message: String? = null,
    val gateway_running: Boolean,
    val home_channel: MessagingHomeChannel? = null,
    val id: String,
    val name: String,
    val state: String? = null,
    val updated_at: String? = null,
)

@Serializable
data class MessagingPlatformsResponse(val platforms: List<MessagingPlatformInfo>)

@Serializable
data class MessagingPlatformUpdate(
    val clear_env: List<String>? = null,
    val enabled: Boolean? = null,
    val env: Map<String, String>? = null,
)

@Serializable
data class MessagingPlatformTestResponse(
    val message: String,
    val ok: Boolean,
    val state: String? = null,
)

// ── Gateway / RPC ───────────────────────────────────────────────────────────

@Serializable
data class GatewayReadyPayload(val skin: JsonElement? = null)

/** Already modeled as `GatewayEvent` in rpc/GatewayEvent.kt — keeping the alias
 *  here for explicit name parity with the TS export. */
typealias RpcEvent = app.marmalade.android.rpc.GatewayEvent

// ── Hello handshake (marmaladed protocol v1) ────────────────────────────────

/**
 * `hello` result — the daemon's negotiated handshake reply
 * (marmalade/packages/protocol/src/handshake.ts `HelloResult`). The
 * [features] list is what the client branches on: "stable-ids" = events
 * carry server-minted message_id/seq/ts/origin; "subscribe" =
 * session.subscribe/unsubscribe/seen are served.
 */
@Serializable
data class HelloResponse(
    val protocolVersion: Int,
    val server: HelloServerInfo? = null,
    val principal: String? = null,
    val features: List<String> = emptyList(),
)

@Serializable
data class HelloServerInfo(
    val name: String,
    val version: String? = null,
)

/**
 * `prompt.submit` result (identity plan P1): the server-minted identity of
 * the user message just accepted. Binds the local outbox bubble to the
 * server id immediately (`OutboxDrainer.ackOutboxAsMessage`). All fields
 * default so a legacy gateway's empty result decodes to a null-ish ack.
 */
@Serializable
data class PromptSubmitAck(
    val message_id: String? = null,
    val seq: Long? = null,
    val ts: Long? = null,
)

/**
 * `session.undo` result (protocol methods.ts `SessionUndoResult`, T2 #6): the
 * last completed turn was popped in place (same session id). The popped
 * bubbles drop LIVE off the transient `session.undone` event; this result is
 * only for the caller's notice. [files_rewound] is always false in v1 — the
 * CONVERSATION is rewound, but file edits made during the popped turn are NOT
 * reverted. [last_message_id] is the new tip (an assistant message), or null
 * when the session emptied (first-turn undo).
 */
@Serializable
data class SessionUndoResponse(
    val last_message_id: String? = null,
    val popped_message_ids: List<String> = emptyList(),
    val files_rewound: Boolean = false,
)

/**
 * `session.main` result (protocol methods.ts `SessionMainResult`, assistant
 * plan 2026-07-19): the id of THE daemon-managed singleton main session. The
 * daemon get-or-creates it (always warm, never deleted) and returns its id;
 * the client renders it as the Home/assistant surface and routes voice into
 * it. There is no "set main" — the daemon owns the designation.
 */
@Serializable
data class SessionMainResponse(
    val session_id: String,
)

/**
 * `session.clear` result (protocol methods.ts `SessionClearResult`): the
 * session's conversation was reset IN PLACE (same session_id — clearing is
 * state surgery, not a new session). The local view empties off the transient
 * `session.cleared` event, not this result. This is how the main session —
 * which cannot be deleted — starts over.
 */
@Serializable
data class SessionClearResponse(
    val cleared: Boolean = false,
)

/**
 * `session.model` result (protocol methods.ts `SessionModelResult`): the
 * now-effective harness model id. The daemon stores it on the row and, when
 * the session is live and idle, restarts the child so the change applies now
 * (context carries over via harness resume); otherwise it applies on the next
 * spawn. Rejects (thrown) while a turn is in flight.
 */
@Serializable
data class SessionModelResponse(
    val model: String,
)

/**
 * `session.effort` result (protocol methods.ts `SessionEffortResult`, additive
 * 2026-07-25): the now-effective reasoning effort. Same lifecycle as
 * [SessionModelResponse] — stored on the row, an idle live child restarted so
 * it applies now, rejected (thrown) while a turn is in flight. Before this
 * method existed effort could only ride `session.create`, so the composer's
 * Thinking pick was cosmetic on every existing session.
 */
@Serializable
data class SessionEffortResponse(
    @SerialName("reasoning_effort") val reasoningEffort: String,
)

/**
 * `session.archive` result (protocol methods.ts `SessionArchiveResult`,
 * ratified 2026-07-23): the stored archived flag after the call. Idempotent —
 * re-setting the current value is fine. The daemon rejects the MAIN session
 * ("the main session is daemon-managed and cannot be archived") and unknown
 * ids; both surface as thrown RPC errors.
 */
@Serializable
data class SessionArchiveResponse(
    val archived: Boolean = false,
)

// ── M2 device pairing (marmaladed pairing.claim / device.*) ─────────────────

/**
 * `pairing.claim` result (protocol methods.ts `PairingClaimResult`): the
 * per-device bearer token — shown ONCE, stored hashed at rest daemon-side.
 * The device presents it in `?token=` / hello `auth.token` from now on.
 */
@Serializable
data class PairingClaimResponse(
    val device_token: String,
    val device_id: String,
    val principal: String? = null,
)

/** One row of `device.list` (methods.ts `DeviceListResult`). */
@Serializable
data class DeviceInfo(
    val device_id: String,
    val platform: String? = null,
    val paired: Boolean = false,
    val connected: Boolean = false,
    val first_seen: Long? = null,
    val last_seen: Long? = null,
)

@Serializable
data class DeviceListResponse(val devices: List<DeviceInfo> = emptyList())

@Serializable
data class DeviceRevokeResponse(val revoked: Boolean = false)

// ── Sessions ────────────────────────────────────────────────────────────────

@Serializable
data class SessionCreateResponse(
    val info: SessionRuntimeInfo? = null,
    val message_count: Int? = null,
    val messages: List<SessionMessage>? = null,
    // marmaladed mints ONE immutable session_id (protocol methods.ts
    // SessionCreateResult). The fork's stored_session_id split is dead.
    val session_id: String,
)

/** A {session_id, message_id} reference into another session's lineage —
 *  used by both session.fork's `forked_from` and session.list's
 *  `branched_from`. message_id is null for an end-of-session fork. Shape:
 *  protocol methods.ts (SessionForkResult / session.list row). */
@Serializable
data class SessionLineageRef(
    val session_id: String,
    val message_id: String? = null,
)

/** Structured discriminator on the no-fork-harness rejection's
 *  `error.data.reason` — mirror of protocol methods.ts FORK_UNSUPPORTED_REASON.
 *  Clients branch on THIS, never on the human error message. */
const val FORK_UNSUPPORTED_REASON = "fork_unsupported"

/** session.fork result (T2 #3): the new session's id, where it branched from,
 *  whether the harness carried full context, and a soft warning to surface
 *  (e.g. Claude Code forks don't copy file-history/undo snapshots — show it,
 *  don't block on it). Wire truth: protocol methods.ts SessionForkResult. */
@Serializable
data class SessionForkResponse(
    val session_id: String,
    val forked_from: SessionLineageRef,
    val full_context: Boolean = false,
    val warning: String? = null,
)

@Serializable
data class SessionInfo(
    val archived: Boolean? = null,
    val cwd: String? = null,
    // Timestamps are float epoch SECONDS on the wire (gateway `time.time()` /
    // `float(...)`, tui_gateway/server.py). Decoding them as Long crashes the
    // whole response on any fractional value — model as Double + expose *Ms
    // converters, matching SessionListItem.started_at. (#17 audit.)
    val ended_at: Double? = null,
    val id: String,
    /** Original root id of a compression chain; durable across compressions. */
    val _lineage_root_id: String? = null,
    // Token/count fields are required by the server contract, but default them
    // so a single omitted field can't reject the entire session.info/list
    // payload (the #17 failure mode — one bad field nuked the whole response).
    val input_tokens: Int = 0,
    val is_active: Boolean = false,
    val last_active: Double = 0.0,
    val message_count: Int = 0,
    val model: String? = null,
    val output_tokens: Int = 0,
    val preview: String? = null,
    val source: String? = null,
    val started_at: Double = 0.0,
    val title: String? = null,
    val tool_call_count: Int = 0,
    /** Origin platform when this session was handed off from a messaging platform. */
    val handoff_platform: String? = null,
    /** 'pending' | 'in_progress' | 'completed' | 'failed' */
    val handoff_state: String? = null,
    val handoff_error: String? = null,
    /** Owning profile name (cross-profile responses only). */
    val profile: String? = null,
    val is_default_profile: Boolean? = null,
    /** When a client last showed the user this conversation (epoch seconds).
     *  Gateway-side, stamped by build.sh patch 4k on session.resume /
     *  prompt.submit / interactive message.complete + the explicit
     *  `session.seen` RPC. Null on unpatched gateways and never-seen rows.
     *  Unread = last_active > seen_at + epsilon (see UnreadUtils). */
    val seen_at: Double? = null,
) {
    /** Epoch-ms views of the float-seconds wire timestamps. */
    val startedAtMs: Long get() = (started_at * 1000).toLong()
    val lastActiveMs: Long get() = (last_active * 1000).toLong()
    val endedAtMs: Long? get() = ended_at?.let { (it * 1000).toLong() }
    val seenAtMs: Long? get() = seen_at?.let { (it * 1000).toLong() }
}

@Serializable
data class SessionMessage(
    val codex_reasoning_items: JsonElement? = null,
    val content: JsonElement? = null,
    val context: JsonElement? = null,
    val name: String? = null,
    val reasoning: String? = null,
    val reasoning_content: String? = null,
    val reasoning_details: JsonElement? = null,
    val role: String, // 'assistant' | 'system' | 'tool' | 'user'
    val text: JsonElement? = null,
    val timestamp: Long? = null,
    val tool_call_id: String? = null,
    val tool_calls: JsonElement? = null,
    val tool_name: String? = null,
)

/**
 * `session.resume` result. marmaladed returns just `{session_id}` — the
 * SAME immutable id that was resumed (ids are names, not state; resume
 * never re-mints). History does NOT ride the resume response: the client
 * replays it via `session.subscribe(since_seq)`. The old fork gateway's
 * messages/inflight/info blocks are gone with the positional
 * HistoryReconstruction path.
 */
@Serializable
data class SessionResumeResponse(
    val session_id: String,
)

/**
 * `session.subscribe` result (identity plan P4): [replayed] cached events
 * with seq > since_seq were just sent down this socket (they arrive BEFORE
 * this response), [last_seq] is the next reconnect cursor, and
 * [lifecycle]/[run_state] are the session's current P2 state.
 */
@Serializable
data class SessionSubscribeResponse(
    val session_id: String? = null,
    val replayed: Int = 0,
    val last_seq: Long = 0,
    val lifecycle: String? = null,
    val run_state: String? = null,
)

/** `session.seen` result: the stored per-(device, session) cursor after the
 *  monotonic max-merge (>= the seq sent). */
@Serializable
data class SessionSeenResponse(
    val seq: Long = 0,
)

/**
 * `secret.respond` result (protocol methods.ts SecretRespondResult).
 *
 * [resolved] — a pending request matched and was settled (false means the
 * request had already timed out / been denied elsewhere).
 * [stored] — the keyring insert succeeded. False on a deny AND on a keyring
 * failure, so it is not the inverse of [resolved]; [error] disambiguates.
 * [error] — the keyring failure, already redacted of the value daemon-side
 * (keyring.ts), which is why it is safe to show the user.
 */
@Serializable
data class SecretRespondResult(
    val resolved: Boolean = false,
    val stored: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SessionRuntimeInfo(
    val branch: String? = null,
    val config_warning: String? = null,
    val credential_warning: String? = null,
    val cwd: String? = null,
    val desktop_contract: Int? = null,
    val fast: Boolean? = null,
    val model: String? = null,
    val personality: String? = null,
    val provider: String? = null,
    val reasoning_effort: String? = null,
    val running: Boolean? = null,
    val service_tier: String? = null,
    /** Either a list of skill names OR a map of category → list-of-skills. */
    val skills: JsonElement? = null,
    val tools: Map<String, List<String>>? = null,
    val usage: UsageStats? = null,
    val version: String? = null,
    val yolo: Boolean? = null,
)

// ── Filesystem browse (workspace picker) ─────────────────────────────────────
//
// The workspace picker browses directories on the GATEWAY host over
// `/api/fs/*` — there is no local Android filesystem involved. All paths are
// gateway-side absolute paths (the gateway is POSIX, so '/'-separated).

/** One directory/file entry from `GET /api/fs/list`. */
@Serializable
data class FsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean = false,
)

/**
 * `GET /api/fs/list?path=<dir>` response. The gateway returns HTTP 200 even
 * on read failures, signalling them via the [error] code (ENOENT / ENOTDIR /
 * EACCES / read-error) with an empty [entries] list — so callers must inspect
 * [error], not rely on the transport status.
 */
@Serializable
data class FsListResponse(
    val entries: List<FsEntry> = emptyList(),
    val error: String? = null,
)

/** `GET /api/fs/default-cwd` → the gateway's default working directory. */
@Serializable
data class DefaultCwdResponse(
    val cwd: String,
    val branch: String? = null,
)

// ── marmaladed daemon surfaces (fork-rest-triage A+B, 2026-07-11) ────────────
//
// JSON-RPC replacements for the fork REST skills + fs endpoints. Wire truth:
// marmalade/packages/protocol/src/methods.ts (SkillsList/SkillsToggle/
// FsDefaults/FsList). The old REST types above survive as the UI-facing
// shapes (Part D removed their REST client methods; the types stay as the
// screens' render models).

/** One row of the daemon's `skills.list`. */
@Serializable
data class DaemonSkillRow(
    val name: String,
    val description: String? = null,
    val enabled: Boolean,
    /** Harnesses the skill is enabled for (informational in v1 UI). */
    val harnesses: List<String> = emptyList(),
)

@Serializable
data class SkillsListResponse(val skills: List<DaemonSkillRow> = emptyList())

@Serializable
data class SkillsToggleResponse(val applied: Boolean = false)

/** `fs.defaults` → the cwd session.create uses when none is passed. */
@Serializable
data class FsDefaultsResponse(val default_cwd: String)

/** One entry of the daemon's `fs.list` (names only; no absolute path). */
@Serializable
data class DaemonFsEntry(val name: String, val dir: Boolean = false)

/** `fs.list` result: the RESOLVED (realpath) directory + its entries. */
@Serializable
data class DaemonFsListResponse(
    val path: String,
    val entries: List<DaemonFsEntry> = emptyList(),
)

/** Map a daemon skill row onto the UI-facing [SkillInfo] shape. */
fun DaemonSkillRow.toSkillInfo(): SkillInfo = SkillInfo(
    category = null,
    description = description.orEmpty(),
    enabled = enabled,
    name = name,
)

/** Map a daemon fs.list result onto the picker-facing [FsListResponse] shape:
 *  entry paths are rebuilt from the resolved base path. */
fun DaemonFsListResponse.toForkShape(): FsListResponse = FsListResponse(
    entries = entries.map {
        FsEntry(
            name = it.name,
            path = if (path.endsWith("/")) path + it.name else "$path/${it.name}",
            isDirectory = it.dir,
        )
    },
)

/** One row of the daemon's `mcp.list` (fork-rest-triage Part E). [command]
 *  (stdio launch line) and [url] (http/sse endpoint) are additive detail —
 *  absent for definitions with neither; env is never carried. */
@Serializable
data class DaemonMcpServerRow(
    val name: String,
    val transport: String = "stdio",
    val enabled: Boolean,
    val harness: String = "",
    val command: String? = null,
    val url: String? = null,
)

@Serializable
data class DaemonMcpListResponse(val servers: List<DaemonMcpServerRow> = emptyList())

/** One row of the daemon's `plugins.list` (fork-rest-triage Part E). [source]
 *  (marketplace, from the "name@marketplace" key), [version], and
 *  [description] are additive detail — absent when the plugin isn't installed
 *  locally or has no manifest. */
@Serializable
data class DaemonPluginRow(
    val name: String,
    val enabled: Boolean,
    val harness: String = "",
    val source: String? = null,
    val version: String? = null,
    val description: String? = null,
)

@Serializable
data class DaemonPluginsListResponse(val plugins: List<DaemonPluginRow> = emptyList())

/** Daemon `mcp.toggle` / `plugins.toggle` result: a toggle takes effect on
 *  the NEXT session spawn ([effective] = "next_session"). */
@Serializable
data class DaemonToggleResponse(
    val applied: Boolean = false,
    val effective: String = "",
)

/** Map a daemon MCP row onto the UI-facing [McpServerInfo] shape. [tools] stays
 *  null — the daemon reads config only and never contacts the server. */
fun DaemonMcpServerRow.toMcpServerInfo(): McpServerInfo = McpServerInfo(
    name = name,
    enabled = enabled,
    transport = transport,
    url = url,
    command = command,
)

/** Map a daemon plugin row onto the UI-facing [PluginInfo] shape (the screen
 *  derives on/off from the status string via pluginEnabled()). [source] falls
 *  back to the harness when the daemon didn't parse a marketplace. */
fun DaemonPluginRow.toPluginInfo(): PluginInfo = PluginInfo(
    name = name,
    version = version.orEmpty(),
    description = description.orEmpty(),
    source = source ?: harness,
    status = if (enabled) "enabled" else "disabled",
)

@Serializable
data class UsageStats(
    val calls: Int = 0,
    val context_max: Int? = null,
    val context_percent: Double? = null,
    val context_used: Int? = null,
    val cost_usd: Double? = null,
    val input: Int = 0,
    val output: Int = 0,
    val total: Int = 0,
)

/**
 * marmaladed `session.list` response (daemon router.ts). Rows carry the P2
 * lifecycle/run_state split, the P4 unread cursors (`last_seq` vs THIS
 * device's `seen_seq` — unread is arithmetic), and the rollup topic/summary.
 * Timestamps are epoch MILLISECONDS (daemon `Date.now()`), not the old
 * fork's float seconds.
 */
@Serializable
data class SessionListResponse(
    val sessions: List<SessionListRow> = emptyList(),
)

@Serializable
data class SessionListRow(
    val session_id: String,
    val purpose: String? = null,
    /** THE daemon-managed singleton main session (assistant surface). Clients
     *  pin it to the top of the list with a distinct chip, hide delete/stop for
     *  it, and route wake-word/voice into it. Daemon-owned — there is no
     *  "set main". Wire truth: daemon router.ts `is_main: r.id === mainId`. */
    val is_main: Boolean = false,
    /** Legacy derived status view (v1 clients); prefer lifecycle/run_state. */
    val status: String? = null,
    val lifecycle: String? = null,
    val run_state: String? = null,
    val harness: String? = null,
    val last_active: Long? = null,
    val last_seq: Long = 0,
    val seen_seq: Long = 0,
    /** Human label set via session.title / session.create's title param.
     *  Takes precedence over the rollup topic for display. */
    val title: String? = null,
    val topic: String? = null,
    val summary: String? = null,
    val summary_updated_at: Long? = null,
    /** Harness model id the session was created/resumed with (plain id, no
     *  provider concept — same shape as the `model` field on the
     *  `session.info` event payload). Null when the daemon hasn't reported
     *  one yet. */
    val model: String? = null,
    /** Fork lineage (T2 #3): where this session branched from, or null. Pure
     *  display metadata for the "branched from …" chip — never a visibility
     *  filter. */
    val branched_from: SessionLineageRef? = null,
    /** Workspace membership, DERIVED server-side by cwd-prefix match (deepest
     *  wins) and stamped per row — never re-derived client-side. Null = the
     *  session's cwd falls under no workspace ("Quick sessions"). Cleared the
     *  moment a workspace is deleted (workspace.delete un-groups). Wire truth:
     *  daemon router.ts `workspace_id: workspaceFor(r.cwd)`. */
    val workspace_id: String? = null,
    /** Archived flag (session.archive, ratified 2026-07-23). Pure shared list
     *  metadata, daemon-backed so every client agrees — NEVER a behavior filter
     *  (an archived session still runs, resumes, and receives cron fires).
     *  Clients filter archived rows out of the main list and surface them in an
     *  "Archived" section. Absent on OLD daemons that predate the flag → false.
     *  Wire truth: daemon router.ts session.list `archived`. */
    val archived: Boolean = false,
    /** Persisted context occupancy (additive 2026-07-25). [context_used] =
     *  tokens sitting in the window after the last COMPLETED turn (that turn's
     *  last API call — the same number the live `message.complete` usage block
     *  carries); [context_max] = the harness-reported window of the model that
     *  ran it. Stamped daemon-side at turn end, never queried on read; pure list
     *  metadata that is never fed to a model.
     *
     *  Absent (daemon predating the fields) and null (never ran, no
     *  window-reporting harness, or just cleared) BOTH mean unknown — the
     *  client renders no donut rather than a fabricated number, and both halves
     *  are required (used without max is unknown). */
    val context_used: Long? = null,
    val context_max: Long? = null,
    /** Derived daemon-side from the two above (router.ts::contextPercent) and
     *  deliberately NOT read by this client — the donut recomputes from
     *  used/max with the same formula so the percentage has one home. Decoded
     *  only so the row type mirrors the wire; typed loosely so a non-integer
     *  could never break the whole session.list decode. */
    val context_percent: Double? = null,
)

// ── Workspaces (marmaladed "workspaces" hello feature, 2026-07-18) ───────────
// Paseo-style folder workspaces: a human name + emoji over a folder path on the
// daemon host. Membership is DERIVED (session.list stamps workspace_id by cwd
// prefix); a workspace is metadata, not a container — workspace.delete un-groups
// and never touches sessions. Wire truth:
// marmalade/packages/protocol/src/methods.ts (WorkspaceWire + workspace.* params).

/** What the workspace folder brings to a session spawned in it, read live at
 *  list time. Display-only — the git chip is deliberately not a git UI. */
@Serializable
data class WorkspaceDetection(
    /** Current branch, "detached", or null when the folder is not a git repo. */
    val git_branch: String? = null,
    val has_claude_md: Boolean = false,
    val has_agents_md: Boolean = false,
    /** .md note count in the folder's .memory/ (0 when absent). */
    val memory_notes: Int = 0,
)

/** One workspace (WorkspaceWire). [path] is the realpath-resolved match key
 *  (immutable — remove + re-add to move). [emoji] null = show a first-letter
 *  avatar. Timestamps are epoch ms (daemon `Date.now()`). */
@Serializable
data class WorkspaceInfo(
    val workspace_id: String,
    val path: String,
    val name: String,
    val emoji: String? = null,
    val created_at: Long = 0L,
    val updated_at: Long = 0L,
    val detection: WorkspaceDetection = WorkspaceDetection(),
)

@Serializable
data class WorkspaceMutateResponse(val workspace: WorkspaceInfo)

@Serializable
data class WorkspaceListResponse(val workspaces: List<WorkspaceInfo> = emptyList())

@Serializable
data class WorkspaceDeleteResponse(val deleted: Boolean = false)

/** One inherited context file (CLAUDE.md / AGENTS.md) as read by
 *  workspace.context. [content] is capped daemon-side (16KB); [truncated] says
 *  the file was longer than the peek. Wire: WorkspaceContextFileWire. */
@Serializable
data class WorkspaceContextFile(
    val content: String = "",
    val truncated: Boolean = false,
)

/** What a session spawned in a workspace inherits, read live (workspace.context).
 *  A read-only peek — files are capped, memory is FILENAMES only (notes stay on
 *  the host by design). Null files = absent (or resolving outside home). Wire
 *  truth: marmalade/packages/protocol/src/methods.ts WorkspaceContextResult. */
@Serializable
data class WorkspaceContextResponse(
    val workspace_id: String,
    val claude_md: WorkspaceContextFile? = null,
    val agents_md: WorkspaceContextFile? = null,
    /** .md note filenames in .memory/, sorted. Names only. */
    val memory: List<String> = emptyList(),
    val git_branch: String? = null,
)

@Serializable
data class SessionSearchResult(
    /** Lineage root of the matched conversation; stable across compression. */
    val lineage_root: String? = null,
    val model: String? = null,
    val role: String? = null,
    /** Live compression tip of the matched conversation — resume by this id. */
    val session_id: String,
    // Float epoch seconds on the wire (gateway forwards the sessions-table
    // `started_at` float). Long would crash the search response. (#17 audit.)
    val session_started: Double? = null,
    val snippet: String,
    val source: String? = null,
)

@Serializable
data class SessionSearchResponse(val results: List<SessionSearchResult>)

// ── Search (marmaladed "search" hello feature, 2026-07-24) ───────────────────
// Full-text search over MESSAGE TEXT — user prompts and assistant prose only.
// Tool calls, tool results, thinking/reasoning and system prompts are never
// indexed (separate event types, so the exclusion falls out of the data model).
//
// Scope is resolved DAEMON-SIDE through the same workspace matcher session.list
// stamps rows with (cwd prefix, deepest wins). The client never re-derives
// membership: `SearchSessionInfo.workspace_id` in the response IS the answer.
// Wire truth: marmalade/packages/protocol/src/methods.ts (SearchScope,
// SearchMessagesParams, SearchHitWire, SearchSessionWire, SearchMessagesResult)
// + packages/daemon/src/router.ts case "search.messages".

/** One matching message. [session_id] + [message_id] + [seq] is the deep-link
 *  tuple; [seq] orders within the session (identity rule 2 — never wall clock).
 *
 *  [role] is a String, not an enum, so a daemon that grows a third role
 *  decodes instead of throwing (`ignoreUnknownKeys` does not cover enums).
 *  Compare against [SearchRoles]. */
@Serializable
data class SearchHit(
    val session_id: String,
    val message_id: String,
    val seq: Long,
    val role: String,
    /** Epoch ms (daemon `Date.now()`). Metadata only — display, never order. */
    val ts: Long,
    /** Match context with the matched spans wrapped in
     *  [app.marmalade.android.search.SnippetMarkers.OPEN] /
     *  [app.marmalade.android.search.SnippetMarkers.CLOSE]. NEVER render raw —
     *  parse with `SnippetMarkers.parse` and style the spans. */
    val snippet: String,
    /** The whole message, capped daemon-side at 4096 chars — this is what
     *  powers "peek" with no second round-trip. */
    val text: String = "",
    /** For `role == "user"` hits: the answering assistant message's opening,
     *  capped at 500 chars. Absent when the turn has no answer yet. */
    val reply_text: String? = null,
)

/** Enough session context to render a hit without a second call. */
@Serializable
data class SearchSessionInfo(
    val title: String? = null,
    /** The daemon matcher's verdict (deepest wins); null = quick chat. This is
     *  the ONLY source for a hit's workspace chip. */
    val workspace_id: String? = null,
    val archived: Boolean = false,
    /** Epoch ms. */
    val last_active: Long = 0L,
    /** Present ONLY on entries from the pre-daemon archive corpus
     *  ([SearchCorpus.ARCHIVE]). Absent/null = a live daemon session, which the
     *  client may open and resume. An archive entry is read-only: its
     *  `session_id` is a Claude Code UUID the daemon's session table has never
     *  heard of, so `session.resume` would fail — the transcript is fetched with
     *  `search.archive` instead. Never open a hit without checking this. */
    val corpus: String? = null,
) {
    /** True when this session is pre-daemon history — read-only, not openable. */
    val isArchive: Boolean get() = corpus == SearchCorpus.ARCHIVE
}

@Serializable
data class SearchMessagesResponse(
    /** Total matches in scope. [hits] is one page of them. */
    val total: Int = 0,
    val hits: List<SearchHit> = emptyList(),
    /** Keyed by `session_id`; covers this page's hits only. */
    val sessions: Map<String, SearchSessionInfo> = emptyMap(),
)

/** The wire's `role` values (SearchHitWire.role / SearchMessagesParams.role). */
object SearchRoles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/** The wire's `sort` values (SearchMessagesParams.sort). */
object SearchSorts {
    /** bm25 relevance — the wire default. */
    const val RANK = "rank"
    const val RECENT = "recent"
}

// ── Archive corpus (marmaladed "search_archive" hello feature, 2026-07-28) ───
// A SECOND CORPUS, not a filter. [SearchCorpus.ARCHIVE] searches the maintainer's
// pre-daemon Claude Code history (`~/.claude/projects`), indexed read-only and
// years deep. Nothing in it can be opened, resumed or written — an archive hit's
// `session_id` is a Claude Code UUID, not a daemon session id.
//
// Consequences the client must respect, all daemon-side facts:
//  - one corpus per query (the wire field is a single enum, not a set);
//  - `include_archived` is meaningless in archive mode and ignored — the whole
//    corpus is historical and none of it carries an `archived` flag;
//  - archive hits never carry `reply_text` (reply lookup is live-corpus
//    machinery keyed on the daemon's own message index);
//  - a hit's `seq` is the archive `ordinal`, the position within the extracted
//    transcript, which is also the paging key for `search.archive`;
//  - an archive session a live session already replays is hidden by the daemon,
//    so a migrated conversation is found once — in the live corpus, where it
//    can be opened.
// Wire truth: methods.ts SearchScope.corpus / SearchArchiveParams /
// SearchArchiveResult + router.ts cases "search.messages" (archive branch) and
// "search.archive". Gate every archive affordance on the "search_archive" hello
// feature — an older daemon MethodNotFounds `search.archive` and silently
// ignores an unknown `corpus`, which would look like an empty archive.

/** The wire's `scope.corpus` values (SearchScope.corpus). */
object SearchCorpus {
    /** The daemon's own sessions — the wire default, and OMITTED from the
     *  request rather than restated (see `buildSearchMessagesParams`). */
    const val LIVE = "live"

    /** The pre-daemon `~/.claude/projects` history. Read-only. */
    const val ARCHIVE = "archive"
}

/** One message in an archive transcript (`search.archive`).
 *
 *  [ordinal] is the 0-based position within the session's extracted messages —
 *  the archive's stand-in for `seq`, and the paging key. [role] is a String for
 *  the same reason [SearchHit.role] is: a corpus that grows a third role decodes
 *  instead of throwing. Compare against [SearchRoles]. */
@Serializable
data class SearchArchiveMessage(
    val ordinal: Int = 0,
    val role: String = SearchRoles.ASSISTANT,
    /** Epoch ms, from the original Claude Code transcript. */
    val ts: Long = 0L,
    val text: String = "",
)

/** Header for an archive transcript. [cwd] is the directory the original
 *  session ran in — the only identity this history has beyond its uuid, so the
 *  viewer shows it rather than pretending a title always exists. */
@Serializable
data class SearchArchiveSessionInfo(
    val title: String? = null,
    val cwd: String = "",
    /** Epoch ms. */
    val last_active: Long = 0L,
    val message_count: Int = 0,
)

/** One page of an archive transcript. [messages] is ascending by
 *  [SearchArchiveMessage.ordinal]; [total] is the whole session's indexed
 *  message count, so `messages.size < total` is exactly the load-more
 *  condition. */
@Serializable
data class SearchArchiveResponse(
    val session: SearchArchiveSessionInfo = SearchArchiveSessionInfo(),
    val total: Int = 0,
    val messages: List<SearchArchiveMessage> = emptyList(),
)

// ── Models ──────────────────────────────────────────────────────────────────

/** One model the daemon's harness can run a session on (`model.list`).
 *  `id` is the harness model identifier passed verbatim back on
 *  session.create's `model` param; `label` is the human picker name. */
@Serializable
data class ModelListEntry(
    val id: String,
    val label: String,
    /** One-line blurb for a settings list ("The standard — agentic coding").
     *  Additive (2026-07-25); null on an older daemon, so pickers fall back
     *  to the label alone. */
    val description: String? = null,
    /** Per-model reasoning-effort bounds (additive, 2026-07-27). Present only
     *  when the daemon config bounds THIS model; both null = unbounded, which
     *  is also what every pre-2026-07-27 daemon reports. Values are members of
     *  [ModelListResponse.efforts].
     *
     *  The daemon CLAMPS into `[effort_min, effort_max]` at session.create /
     *  session.effort and returns the clamped truth, so ignoring these keys is
     *  safe — it just means the picker can't grey out the levels the session
     *  will never actually run at. */
    val effort_min: String? = null,
    val effort_max: String? = null,
)

/** One model's reasoning-effort bounds in the daemon's settings slice
 *  (`settings.get`/`update` `model_efforts`, additive 2026-07-27). At least one
 *  edge is set and min never exceeds max — the daemon rejects a violating entry
 *  with InvalidParams. Both null is not a legal wire value; it means "no entry"
 *  and is expressed by OMITTING the model id (read) or sending JSON null
 *  (write, which deletes it). */
@Serializable
data class ModelEffortBounds(
    val min: String? = null,
    val max: String? = null,
)

@Serializable
data class ModelListResponse(
    val models: List<ModelListEntry> = emptyList(),
    /** Daemon-owned new-session defaults (additive, 2026-07-23): the model /
     *  reasoning effort a model-less / effort-less session.create will be
     *  stamped with. Absent = the harness's own default (unknowable until a
     *  turn runs) — the client keeps rendering "Default". */
    val default_model: String? = null,
    val default_effort: String? = null,
    /** The reasoning-effort levels the daemon ACCEPTS, cheapest → deepest
     *  (additive, 2026-07-25). session.create validates `reasoning_effort`
     *  against exactly this set, so a picker rendering anything else offers a
     *  guaranteed error — which is what shipped before this field existed
     *  (the client offered none/minimal; neither is a level). Empty = an
     *  older daemon; fall back to [app.marmalade.android.rpc.types.EFFORT_LEVELS]. */
    val efforts: List<String> = emptyList(),
)

/** The effort vocabulary this client ships with, used only when the daemon
 *  predates model.list's `efforts`. Kept identical to the daemon's
 *  EFFORT_LEVELS (config.ts) — the wire list is authoritative. */
val EFFORT_LEVELS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

/** The daemon-owned, client-editable settings slice (`settings.get` /
 *  `settings.update`) — the new-session defaults behind the Models screen.
 *  Server-owned on purpose: every device must agree on what a new session
 *  starts with (same precedent as seen_at and workspaces). */
@Serializable
data class DaemonSettings(
    /** The EFFECTIVE default model (daemon config, else the harness's own
     *  tier). Null only when neither names one. */
    val default_model: String? = null,
    /** The default reasoning effort. Null = defer to the harness — a real
     *  choice the UI renders as its own option, not an empty selection. */
    val default_effort: String? = null,
    /** Keys pinned by an environment variable on the daemon host. Writing one
     *  is rejected (env outranks the config file), so the UI disables it. */
    val locked: List<String> = emptyList(),
    /** Per-model reasoning-effort bounds keyed by model id (additive,
     *  2026-07-27). `{}` = the daemon supports bounds but nothing is bounded;
     *  **null = the daemon predates the feature** — the distinction is the
     *  client's feature detection, so this stays nullable rather than
     *  defaulting to an empty map. Never env-locked (bounds are file-only). */
    val model_efforts: Map<String, ModelEffortBounds>? = null,
)

@Serializable
data class AuxiliaryTaskAssignment(
    val base_url: String,
    val model: String,
    val provider: String,
    val task: String,
)

@Serializable
data class AuxiliaryModelsResponse(
    val main: MainModel,
    val tasks: List<AuxiliaryTaskAssignment>,
) {
    @Serializable data class MainModel(val model: String, val provider: String)
}

@Serializable
data class ModelAssignmentRequest(
    /** Optional API key for a custom/local endpoint. Persisted to model.api_key. */
    val api_key: String? = null,
    /** OpenAI-compatible endpoint URL for custom/local providers. */
    val base_url: String? = null,
    val model: String,
    val provider: String,
    val scope: String, // 'main' | 'auxiliary'
    val task: String? = null,
)

// ── Analytics ───────────────────────────────────────────────────────────────

@Serializable
data class AnalyticsDailyEntry(
    val actual_cost: Double,
    val api_calls: Int,
    val cache_read_tokens: Int,
    val day: String,
    val estimated_cost: Double,
    val input_tokens: Int,
    val output_tokens: Int,
    val reasoning_tokens: Int,
    val sessions: Int,
)

@Serializable
data class AnalyticsModelEntry(
    val api_calls: Int,
    val estimated_cost: Double,
    val input_tokens: Int,
    val model: String,
    val output_tokens: Int,
    val sessions: Int,
)

@Serializable
data class AnalyticsSkillEntry(
    // Float epoch seconds on the wire (DB timestamp). Long crashes analytics. (#17)
    val last_used_at: Double? = null,
    val manage_count: Int,
    val percentage: Double,
    val skill: String,
    val total_count: Int,
    val view_count: Int,
)

@Serializable
data class AnalyticsSkillsSummary(
    val distinct_skills_used: Int,
    val total_skill_actions: Int,
    val total_skill_edits: Int,
    val total_skill_loads: Int,
)

@Serializable
data class AnalyticsTotals(
    val total_actual_cost: Double,
    val total_api_calls: Int? = null,
    val total_cache_read: Int? = null,
    val total_estimated_cost: Double,
    val total_input: Int? = null,
    val total_output: Int? = null,
    val total_reasoning: Int? = null,
    val total_sessions: Int,
)

@Serializable
data class AnalyticsResponse(
    val by_model: List<AnalyticsModelEntry>,
    val daily: List<AnalyticsDailyEntry>,
    val period_days: Int,
    val skills: SkillsBlock,
    val totals: AnalyticsTotals,
) {
    @Serializable
    data class SkillsBlock(
        val summary: AnalyticsSkillsSummary,
        val top_skills: List<AnalyticsSkillEntry>,
    )
}

// ── Cron (scheduled prompts) ────────────────────────────────────────────────
// Wire truth: marmalade/packages/protocol/src/methods.ts (CronScheduleSchema,
// CronJobWire). Rewritten 2026-07-17 from the dead fork-gateway shapes (string
// schedules, REST ids) to the daemon's cron.* contract. Semantics the client
// must respect (daemon test/cron-router.test.ts): one-shots self-disable after
// firing; disabled jobs stay listed (the disable REASON rides last_error);
// run_now is out-of-band — next_run_at doesn't move.

/**
 * Wire discriminated union flattened per `.claude/rules/protocol.md` (no
 * sealed-class protocol layer): [kind] = "cron" (expr/tz/staggerMs) |
 * "every" (everyMs/anchorMs) | "at" (atMs, one-shot). Unknown kinds must
 * render as opaque text, never crash — the daemon may grow new ones.
 */
@Serializable
data class CronSchedule(
    val kind: String,
    val expr: String? = null,
    val tz: String? = null,
    @SerialName("stagger_ms") val staggerMs: Long? = null,
    @SerialName("every_ms") val everyMs: Long? = null,
    @SerialName("anchor_ms") val anchorMs: Long? = null,
    @SerialName("at_ms") val atMs: Long? = null,
)

@Serializable
data class CronJob(
    @SerialName("job_id") val jobId: String,
    val name: String? = null,
    @SerialName("session_id") val sessionId: String,
    val prompt: String,
    val schedule: CronSchedule,
    val enabled: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    /** Next fire time (UTC ms, stagger included); null = will never fire. */
    @SerialName("next_run_at") val nextRunAt: Long? = null,
    @SerialName("last_run_at") val lastRunAt: Long? = null,
    /** "ok" | "error" — kept a String for forward compat, not an enum. */
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
)

@Serializable
data class CronJobResponse(val job: CronJob)

@Serializable
data class CronListResponse(val jobs: List<CronJob>)

@Serializable
data class CronDeleteResponse(val deleted: Boolean)

/** fired=false = the job is mid-run (single-flight skip), not an error. */
@Serializable
data class CronRunNowResponse(val fired: Boolean)

// ── Usage (daily rollups — daemon usage.summary, T2 #8) ─────────────────────
// Wire truth: marmalade/packages/protocol/src/methods.ts (UsageEntryWire,
// UsageSummaryResult). Provider truth: token counts are the ground-truth
// metric; cost_usd is the SDK's notional API-equivalent figure (often
// meaningless under subscription auth) — render it secondary, labeled.

@Serializable
data class UsageEntry(
    /** YYYY-MM-DD, daemon-local (UTC-day convention). */
    val day: String,
    /** "main" | "cadence" | … — a String for forward compat, not an enum. */
    val purpose: String,
    @SerialName("cost_usd") val costUsd: Double,
    @SerialName("input_tokens") val inputTokens: Long,
    @SerialName("output_tokens") val outputTokens: Long,
    val turns: Int,
)

/** The daemon's daily budget guardrail (usage.summary.budget, config file),
 *  or absent when none is configured. `over` gates UNATTENDED (cron) turns
 *  only — interactive prompts are NEVER blocked. Wire truth: methods.ts
 *  UsageSummaryResult.budget. */
@Serializable
data class UsageBudget(
    /** "usd" | "tokens" — a String for forward compat, not an enum. */
    val metric: String,
    val daily_limit: Double,
    /** Today's total in the budget's metric. */
    val today_total: Double,
    val over: Boolean = false,
)

/** One subscription rate-limit window (Claude Code's /usage: 5-hour /
 *  weekly / per-model). Wire truth: methods.ts PlanLimitWindowWire. */
@Serializable
data class PlanLimitWindow(
    /** Harness-native id ("five_hour", "seven_day", "model:Fable"…). */
    val id: String,
    /** Human label ("5-hour", "Weekly (Opus)"). */
    val label: String,
    /** Percent used, 0–100, or null when the harness can't say. */
    val utilization: Double? = null,
    /** ISO 8601 reset time, or null. */
    @SerialName("resets_at") val resetsAt: String? = null,
)

/** Subscription plan limits as ONE harness reports them. An array on the
 *  wire so a future subscription harness beside Claude Code (e.g. a Codex
 *  adapter) surfaces as its own `harness`-tagged entry with no wire change —
 *  the Usage screen renders each entry as its own card automatically. */
@Serializable
data class PlanLimits(
    /** Daemon adapter name ("claude-code" today). */
    val harness: String,
    /** Plan tier ("pro" | "max" | …) or null when the harness doesn't say. */
    @SerialName("subscription_type") val subscriptionType: String? = null,
    val windows: List<PlanLimitWindow> = emptyList(),
)

@Serializable
data class UsageSummaryResponse(
    /** The daemon's current day — anchor "today" on THIS, not the phone
     *  clock (tz drift). */
    val today: String,
    val entries: List<UsageEntry>,
    /** Daily budget state, or null when the daemon has none configured. */
    val budget: UsageBudget? = null,
    /** Subscription plan-limit windows per harness. Empty when no live
     *  session can report them (or the account isn't on a subscription). */
    @SerialName("plan_limits") val planLimits: List<PlanLimits> = emptyList(),
)

// ── Profiles ────────────────────────────────────────────────────────────────

@Serializable
data class ProfileCreatePayload(
    val clone_all: Boolean? = null,
    val clone_from: String? = null,
    val clone_from_default: Boolean? = null,
    val name: String,
    val no_skills: Boolean? = null,
)

@Serializable
data class ProfileInfo(
    val has_env: Boolean,
    val is_default: Boolean,
    val model: String? = null,
    val name: String,
    val path: String,
    val provider: String? = null,
    val skill_count: Int,
)

@Serializable
data class ProfileSetupCommand(val command: String)

@Serializable
data class ProfileSoul(val content: String, val exists: Boolean)

@Serializable
data class ProfilesResponse(val profiles: List<ProfileInfo>)

// ── MCP servers ─────────────────────────────────────────────────────────────

/**
 * One MCP server entry from `GET /api/mcp/servers`.
 *
 * Minimal projection of the upstream [McpServer] shape — only the fields
 * the UI needs to render a list row and drive an enable/disable toggle.
 * [tools] is nullable: null = server not yet contacted; empty list = connected
 * but exposes no tools.
 */
@Serializable
data class McpServerInfo(
    val name: String,
    val enabled: Boolean,
    /** "http" | "stdio" | "unknown" */
    val transport: String,
    val url: String? = null,
    val command: String? = null,
    val tools: List<String>? = null,
)

// ── Skills / Tools ──────────────────────────────────────────────────────────

@Serializable
data class SkillInfo(
    // Server can send `category: null` for un-categorized skills — the
    // initial port assumed it was always present, so the decode threw
    // JsonDecodingException("Expected string literal but 'null' literal
    // was found at path: $[27].category"), crashing the entire Skills
    // Settings page on first load. Same class as the started_at Long-vs-
    // Double bug — wire-shape assumptions need to match reality.
    val category: String? = null,
    val description: String,
    val enabled: Boolean,
    val name: String,
)

// ── Plugins ─────────────────────────────────────────────────────────────────

/**
 * One row in the Plugins settings list. Wire shape mirrors `plugins.manage`
 * action=list rows (server.py:10397).
 *
 * [source] is `"bundled"` for plugins shipped with marmalade-agent vs.
 * `"user"` or another label for installed-by-user plugins. [status] is
 * the user-facing enable/disable string (e.g. `"enabled"`, `"disabled"`).
 */
@Serializable
data class PluginInfo(
    val name: String,
    val version: String = "",
    val description: String = "",
    val source: String = "",
    val status: String = "",
)

/** Response of `plugins.manage` action=list. */
@Serializable
data class PluginsListResponse(
    val plugins: List<PluginInfo>,
    @kotlinx.serialization.SerialName("user_count")
    val userCount: Int = 0,
    @kotlinx.serialization.SerialName("bundled_count")
    val bundledCount: Int = 0,
)

/** Response of `plugins.manage` action=toggle. */
@Serializable
data class PluginsToggleResponse(
    val ok: Boolean = false,
    val unchanged: Boolean = false,
    val name: String = "",
    val plugin: PluginInfo? = null,
)

// ── Attachments ─────────────────────────────────────────────────────────────

/**
 * Response from `image.attach` / `image.attach_bytes` — the gateway wrote the
 * image into its images dir and queued it on the session; the next
 * `prompt.submit` consumes every queued image via the server's vision
 * pipeline. [path] is the gateway-side file path (useful for `image.detach`).
 * Server: `tui_gateway/server.py:6520` (attach_bytes). Mirrors desktop's
 * `ImageAttachResponse` (`apps/desktop/src/app/types.ts`).
 */
@Serializable
data class ImageAttachResponse(
    val attached: Boolean = false,
    val path: String? = null,
    val count: Int? = null,
    val message: String? = null,
)

/**
 * Response from `audio.transcribe` — the daemon's server-side STT fallback
 * (used by the voice popup when on-device Whisper fails). Gated on the
 * "transcription" hello feature. Wire truth:
 * `marmalade/packages/protocol/src/methods.ts` AudioTranscribeResult.
 */
@Serializable
data class AudioTranscribeResponse(
    val transcript: String,
    val provider: String? = null,
)

/**
 * Response from `file.attach` — the gateway staged the uploaded bytes into the
 * session workspace and returns [refText] (`@file:<workspace-relative-path>`),
 * which the client prepends to the prompt text so the agent's file tools can
 * read the attachment. Server: `tui_gateway/server.py:6843`.
 */
@Serializable
data class FileAttachResponse(
    val attached: Boolean = false,
    val name: String? = null,
    val path: String? = null,
    @SerialName("ref_path") val refPath: String? = null,
    @SerialName("ref_text") val refText: String? = null,
    val uploaded: Boolean? = null,
    val message: String? = null,
)

// ── Logs / system ───────────────────────────────────────────────────────────

@Serializable
data class LogsResponse(val file: String, val lines: List<String>)

// ── Async actions ───────────────────────────────────────────────────────────

@Serializable
data class ActionResponse(val name: String, val ok: Boolean, val pid: Int)

@Serializable
data class ActionStatusResponse(
    val exit_code: Int? = null,
    val lines: List<String>,
    val name: String,
    val pid: Int? = null,
    val running: Boolean,
)

// ── Update channel ──────────────────────────────────────────────────────────

@Serializable
data class BackendUpdateCommit(
    val sha: String,
    val summary: String,
    val author: String,
    val at: Long,
)

/** Shape of `GET /api/hermes/update/check` — the backend's own update state.
 *  Used by the desktop's remote update overlay so the backend version (not the
 *  Electron client clone) drives "what's changed + Install" in remote mode. */
@Serializable
data class BackendUpdateCheckResponse(
    val install_method: String,
    val current_version: String,
    val behind: Int? = null,
    val update_available: Boolean,
    val can_apply: Boolean,
    val update_command: String? = null,
    val message: String? = null,
    val commits: List<BackendUpdateCommit>? = null,
)
