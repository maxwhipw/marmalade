package app.marmalade.android.rpc

import app.marmalade.android.rpc.types.CronDeleteResponse
import app.marmalade.android.rpc.types.CronJobResponse
import app.marmalade.android.rpc.types.CronListResponse
import app.marmalade.android.rpc.types.CronRunNowResponse
import app.marmalade.android.rpc.types.CronSchedule
import app.marmalade.android.rpc.types.SearchArchiveResponse
import app.marmalade.android.rpc.types.SearchMessagesResponse
import app.marmalade.android.search.SearchArchiveDefaults
import app.marmalade.android.search.SearchDefaults
import app.marmalade.android.search.SearchScopeSelection
import app.marmalade.android.search.buildSearchArchiveParams
import app.marmalade.android.search.buildSearchMessagesParams
import app.marmalade.android.rpc.types.UsageSummaryResponse
import app.marmalade.android.rpc.types.FileAttachResponse
import app.marmalade.android.rpc.types.ImageAttachResponse
import app.marmalade.android.rpc.types.DaemonSettings
import app.marmalade.android.rpc.types.ModelEffortBounds
import app.marmalade.android.rpc.types.ModelListResponse
import app.marmalade.android.rpc.types.SessionForkResponse
import app.marmalade.android.rpc.types.PluginsListResponse
import app.marmalade.android.rpc.types.PluginsToggleResponse
import app.marmalade.android.rpc.types.SecretRespondResult
import app.marmalade.android.rpc.types.SessionCreateResponse
import app.marmalade.android.rpc.types.SessionListResponse
import app.marmalade.android.rpc.types.SessionResumeResponse
import app.marmalade.android.rpc.types.SessionSeenResponse
import app.marmalade.android.rpc.types.SessionSubscribeResponse
import app.marmalade.android.rpc.types.DaemonFsListResponse
import app.marmalade.android.rpc.types.DaemonMcpListResponse
import app.marmalade.android.rpc.types.DaemonPluginsListResponse
import app.marmalade.android.rpc.types.DaemonToggleResponse
import app.marmalade.android.rpc.types.FsDefaultsResponse
import app.marmalade.android.rpc.types.toPluginInfo
import app.marmalade.android.rpc.types.SkillsListResponse
import app.marmalade.android.rpc.types.SkillsToggleResponse
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Typed wrappers for the marmalade-agent JSON-RPC methods the chat surface
 * needs. Thin adapter layer over [JsonRpcClient.request] — handles the
 * deserialization boundary so call sites stay clean:
 *
 *     val sid = rpc.sessionCreate().session_id
 *     rpc.promptSubmit(sid, "hello world")
 *     val sessions = rpc.sessionList(limit = 40)
 *
 * Method coverage matches marmaladed's protocol v1 surface (the wire truth is
 * `marmalade/packages/protocol/src/` + the daemon's router.ts):
 *
 * - hello (device identity handshake)
 * - session.create / list / resume / subscribe / unsubscribe / seen /
 *   interrupt / stop / delete / title
 * - session.main / clear / model (the daemon-managed singleton main session:
 *   get-or-create Home, reset-in-place, and change model — assistant plan
 *   2026-07-19)
 * - prompt.submit (returns the server-minted {message_id, seq, ts})
 *
 * Fork-era methods with no daemon equivalent were removed with the gap
 * triage (2026-07-11): session.close/save/compress/status,
 * delegation.status, process.stop, slash.exec, prompt.background,
 * commands.catalog, complete.slash. session.steer / session.compact /
 * session.undo are LIVE again since 2026-07-18 — the daemon reimplemented
 * them (T2 #6 / #11a), so they're daemon contracts now, not fork leftovers.
 * Attachments (image/file attach/detach) and cron.* are LIVE since
 * 2026-07-17. clarify.respond is LIVE since 2026-07-18 (the daemon bridges
 * AskUserQuestion to clarify.request/respond). Still dormant until a daemon
 * counterpart lands: the secret/sudo responds (approvals went live with M2).
 * WireConformanceTest is the authoritative live/dormant ledger.
 */
open class MarmaladeRpc(private val client: JsonRpcClient) {

    /** Underlying client — exposed for observers of [JsonRpcClient.connectionState]
     *  and [JsonRpcClient.events] that don't want a second handle. */
    open val rpcClient: JsonRpcClient get() = client

    // ── Handshake ───────────────────────────────────────────────────────────

    /**
     * Negotiated v1 `hello` (marmaladed handshake.ts). Sent once per socket,
     * right after connect. Binds this connection's device identity server-side
     * — the daemon stamps every message origin from the AUTHENTICATED
     * connection, never the message body, so this is where "sent from the
     * Pixel" comes from. Returns the negotiated [HelloResponse.features].
     *
     * A legacy fork gateway has no hello route; callers catch and continue
     * on the legacy path (gateway.ready has already arrived either way).
     */
    open suspend fun hello(
        deviceId: String,
        platform: String,
        tzOffsetMinutes: Int,
        capabilities: List<String>,
        clientName: String,
        clientVersion: String,
        token: String? = null,
    ): app.marmalade.android.rpc.types.HelloResponse = call(
        "hello",
        buildJsonObject {
            put("protocolVersion", 1)
            put("client", buildJsonObject {
                put("name", clientName)
                put("version", clientVersion)
                putJsonArray("capabilities") { capabilities.forEach { add(JsonPrimitive(it)) } }
                put("deviceId", deviceId)
                put("platform", platform)
                put("tzOffset", tzOffsetMinutes)
            })
            if (!token.isNullOrBlank()) {
                put("auth", buildJsonObject { put("token", token) })
            }
        },
        app.marmalade.android.rpc.types.HelloResponse.serializer(),
    )

    // ── M2 device pairing ───────────────────────────────────────────────────

    /**
     * Redeem a pairing setup code's bootstrap token for this device's durable
     * bearer token (daemon router.ts `pairing.claim` — the ONLY method an
     * unauthenticated connection may call). Binds this connection's VERIFIED
     * identity server-side in the same step, so the claim socket is already
     * authenticated afterwards.
     */
    open suspend fun pairingClaim(
        token: String,
        deviceId: String,
        platform: String = "android",
    ): app.marmalade.android.rpc.types.PairingClaimResponse = call(
        "pairing.claim",
        buildJsonObject {
            put("token", token)
            put("device_id", deviceId)
            put("platform", platform)
        },
        app.marmalade.android.rpc.types.PairingClaimResponse.serializer(),
    )

    /** Paired-device roster with live-connection decoration (device.list). */
    open suspend fun deviceList(): app.marmalade.android.rpc.types.DeviceListResponse = call(
        "device.list",
        buildJsonObject {},
        app.marmalade.android.rpc.types.DeviceListResponse.serializer(),
    )

    /** Revoke a device's tokens + roster row; its live connections drop NOW
     *  (device.revoke). Revoking THIS device logs the phone out. */
    open suspend fun deviceRevoke(deviceId: String): app.marmalade.android.rpc.types.DeviceRevokeResponse = call(
        "device.revoke",
        buildJsonObject { put("device_id", deviceId) },
        app.marmalade.android.rpc.types.DeviceRevokeResponse.serializer(),
    )

    // ── Session lifecycle ───────────────────────────────────────────────────

    /**
     * Create a new agent session. The returned [SessionCreateResponse.session_id]
     * is the handle used by every subsequent call against this session.
     *
     * Params mirror the desktop client's session.create (every call carries
     * the composer's current model/effort/fast as per-session overrides —
     * picking a model for a new chat must not mutate the profile default).
     *
     * - [cols] terminal-width hint. Desktop ships its renderer width; Android
     *   has no equivalent so this defaults to 80 unless the caller has a
     *   meaningful number to pass.
     * - [cwd] explicit workspace directory. Only sent when the caller picked
     *   one; an unset cwd lands in "No workspace" rather than the gateway's
     *   launch directory.
     * - [model] / [provider] / [reasoningEffort] / [fast] — per-session
     *   overrides. Omit for the profile defaults. `fast` is only pinned when
     *   `true`; passing `false` is treated as unset by the server (lets the
     *   profile's tier win), so we drop it from params in that case.
     * - [profile] selects a non-default backend profile (multi-profile setups
     *   only).
     * - [title] human label for the session list.
     *
     * NOTE: there is no `resume` param. Continuing a stored session is
     * [sessionResume]; passing `resume` to session.create is silently ignored
     * server-side.
     */
    open suspend fun sessionCreate(
        cols: Int = 80,
        cwd: String? = null,
        model: String? = null,
        provider: String? = null,
        reasoningEffort: String? = null,
        fast: Boolean? = null,
        profile: String? = null,
        title: String? = null,
    ): SessionCreateResponse = call(
        "session.create",
        buildJsonObject {
            put("cols", cols)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
            if (!model.isNullOrBlank()) put("model", model)
            if (!provider.isNullOrBlank()) put("provider", provider)
            if (!reasoningEffort.isNullOrBlank()) put("reasoning_effort", reasoningEffort)
            if (fast == true) put("fast", true)
            if (!profile.isNullOrBlank()) put("profile", profile)
            if (!title.isNullOrBlank()) put("title", title)
        },
        SessionCreateResponse.serializer(),
    )

    /**
     * Branch a session into a NEW session that carries the FULL harness
     * context (conversation incl. tool calls/reasoning), optionally cut at an
     * ASSISTANT message ([atMessageId]). Absent cut = fork from the session's
     * end. Server: `session.fork` (protocol methods.ts SessionForkResult).
     *
     * This is the ONLY branch path — harness-native, real context. A harness
     * that cannot fork rejects with `error.data.reason = "fork_unsupported"`;
     * the caller warns and stops (the seed-create fallback died 2026-07-18
     * with the daemon's unconsumed `messages` param). The fork starts
     * ended/resumable; the first prompt.submit auto-revives it.
     */
    open suspend fun sessionFork(
        sessionId: String,
        atMessageId: String? = null,
        title: String? = null,
    ): SessionForkResponse = call(
        "session.fork",
        buildJsonObject {
            put("session_id", sessionId)
            if (!atMessageId.isNullOrBlank()) put("at_message_id", atMessageId)
            if (!title.isNullOrBlank()) put("title", title)
        },
        SessionForkResponse.serializer(),
    )

    /**
     * List sessions (marmaladed router.ts `session.list`). Rows carry
     * lifecycle/run_state (P2) and this device's unread cursors
     * last_seq/seen_seq (P4). The daemon reads no params; [limit] is sent
     * for forward compat and ignored server-side.
     */
    open suspend fun sessionList(
        limit: Int = 40,
    ): SessionListResponse = call(
        "session.list",
        buildJsonObject { put("limit", limit) },
        SessionListResponse.serializer(),
    )

    /**
     * Resume a stored session: same immutable session_id in, same id out
     * (resume never re-mints — ids are names, not state). Revives an ended
     * session's harness; attaching to a LIVE session just subscribes this
     * connection. History arrives via [sessionSubscribe] replay, not here.
     */
    open suspend fun sessionResume(
        sessionId: String,
        cols: Int = 80,
    ): SessionResumeResponse = call(
        "session.resume",
        buildJsonObject {
            put("session_id", sessionId)
            put("cols", cols)
        },
        SessionResumeResponse.serializer(),
    )

    /**
     * Attach this connection to a session's event stream (P4): the daemon
     * replays cached events with seq > [sinceSeq] down the socket (they
     * arrive BEFORE this response), then streams live. Dedup on the client
     * is by message_id / the seq watermark; order is by seq — never clock.
     */
    open suspend fun sessionSubscribe(
        sessionId: String,
        sinceSeq: Long = 0,
    ): SessionSubscribeResponse = call(
        "session.subscribe",
        buildJsonObject {
            put("session_id", sessionId)
            put("since_seq", sinceSeq)
        },
        SessionSubscribeResponse.serializer(),
    )

    /** Detach this connection from a session's event stream. */
    open suspend fun sessionUnsubscribe(sessionId: String) {
        client.request(
            "session.unsubscribe",
            buildJsonObject { put("session_id", sessionId) },
        )
    }

    /**
     * Resolve THE daemon-managed singleton main session (marmaladed
     * `session.main`, assistant plan 2026-07-19). Get-or-create: the daemon
     * mints it at boot and lazily here if missing, resumes its child if not
     * live, and returns its immutable id. The client binds Home to this id and
     * routes voice into it — it is NEVER created client-side and never appears
     * as deletable. There is no "set main": the daemon owns the designation.
     */
    open suspend fun sessionMain(): app.marmalade.android.rpc.types.SessionMainResponse = call(
        "session.main",
        buildJsonObject {},
        app.marmalade.android.rpc.types.SessionMainResponse.serializer(),
    )

    /**
     * Reset a session's conversation IN PLACE (marmaladed `session.clear`):
     * same session_id, messages/transcript/seen cursors wiped server-side,
     * harness state dropped (the next turn starts fresh). Title and model
     * persist. This is how the main session — which cannot be deleted — starts
     * over. The daemon rejects (thrown) while a turn is in flight; the local
     * view empties off the transient `session.cleared` event, not this result.
     */
    open suspend fun sessionClear(sessionId: String): app.marmalade.android.rpc.types.SessionClearResponse = call(
        "session.clear",
        buildJsonObject { put("session_id", sessionId) },
        app.marmalade.android.rpc.types.SessionClearResponse.serializer(),
    )

    /**
     * Change a session's model (marmaladed `session.model`). Stored on the row;
     * when the session is live and idle the daemon restarts the child so the
     * change applies now (context carries over via harness resume), otherwise
     * on the next spawn. Rejects (thrown) while a turn is in flight. Unlike the
     * `session.create` `model` param (a create-time override), this mutates an
     * EXISTING session — the path the main session and any materialized session
     * use to switch models.
     */
    open suspend fun sessionModel(sessionId: String, model: String): app.marmalade.android.rpc.types.SessionModelResponse = call(
        "session.model",
        buildJsonObject {
            put("session_id", sessionId)
            put("model", model)
        },
        app.marmalade.android.rpc.types.SessionModelResponse.serializer(),
    )

    /**
     * Change a session's reasoning effort (marmaladed `session.effort`, additive
     * 2026-07-25) — [sessionModel]'s twin in every respect: stored on the row, a
     * live idle child restarted so it applies now, thrown rejection while a turn
     * is in flight, and only for EXISTING sessions (a not-yet-created one rides
     * the `session.create` `reasoning_effort` param instead). The level must be
     * one of `model.list`'s `efforts`; anything else is InvalidParams.
     */
    open suspend fun sessionEffort(
        sessionId: String,
        reasoningEffort: String,
    ): app.marmalade.android.rpc.types.SessionEffortResponse = call(
        "session.effort",
        buildJsonObject {
            put("session_id", sessionId)
            put("reasoning_effort", reasoningEffort)
        },
        app.marmalade.android.rpc.types.SessionEffortResponse.serializer(),
    )

    /**
     * Rename a session. Server: `session.title` (`tui_gateway/server.py:4760`).
     * The OpenClaw client previously called `sessions.patch` which doesn't
     * exist server-side — a silent failure that this method replaces.
     */
    /**
     * Stamp this device's per-session read cursor (P4): "this device has
     * rendered up to [seq]". Monotonic server-side (max-merge — a stale
     * stamp never regresses the cursor); returns the stored cursor.
     * Submitting a prompt auto-stamps, so the explicit call is for
     * *watching* — rendering events another device's turn produced.
     */
    open suspend fun sessionSeen(sessionId: String, seq: Long): SessionSeenResponse = call(
        "session.seen",
        buildJsonObject {
            put("session_id", sessionId)
            put("seq", seq)
        },
        SessionSeenResponse.serializer(),
    )

    open suspend fun sessionTitle(sessionId: String, title: String) {
        client.request(
            "session.title",
            buildJsonObject {
                put("session_id", sessionId)
                put("title", title)
            },
        )
    }

    /**
     * Set a session's archived flag (marmaladed `session.archive`, ratified
     * 2026-07-23). Archived is pure shared list metadata, daemon-backed so all
     * clients agree — it is NEVER a behavior filter (an archived session still
     * runs, resumes, receives prompts, and cron fires). Filtering archived rows
     * out of the main list is the client's presentation job. Idempotent. The
     * daemon rejects (thrown) the MAIN session and unknown ids. Returns the
     * stored flag after the call.
     */
    open suspend fun sessionArchive(
        sessionId: String,
        archived: Boolean,
    ): app.marmalade.android.rpc.types.SessionArchiveResponse = call(
        "session.archive",
        buildJsonObject {
            put("session_id", sessionId)
            put("archived", archived)
        },
        app.marmalade.android.rpc.types.SessionArchiveResponse.serializer(),
    )

    /**
     * Delete a session. Server: `session.delete` (`tui_gateway/server.py:4718`).
     * The OpenClaw client previously called `sessions.delete` which doesn't
     * exist server-side.
     */
    open suspend fun sessionDelete(sessionId: String) {
        client.request(
            "session.delete",
            buildJsonObject { put("session_id", sessionId) },
        )
    }

    /**
     * Stop the agent mid-turn for [sessionId]. The server emits a terminal
     * `message.complete` (or `error`) so the chat UI clears its pending
     * bubble. Matches the user-pressed Stop button in desktop.
     */
    open suspend fun sessionInterrupt(sessionId: String) {
        client.request(
            "session.interrupt",
            buildJsonObject { put("session_id", sessionId) },
        )
    }

    /**
     * Steer a RUNNING turn (marmaladed `session.steer`, T2 #6): inject a
     * mid-turn user message the harness merges into the in-flight agent loop.
     * Same ack shape as [promptSubmit] ({message_id, seq, ts}). The daemon
     * rejects when no turn is in flight ("no turn in flight — use
     * prompt.submit"), so callers steer only while runState=running and submit
     * otherwise. The steer is a real user message; its replayed `message.user`
     * carries `steered:true` for other devices, and the sender marks its own
     * bubble steered from this ack.
     */
    open suspend fun sessionSteer(
        sessionId: String,
        prompt: String,
        source: String? = null,
    ): app.marmalade.android.rpc.types.PromptSubmitAck? {
        val raw = client.request(
            "session.steer",
            buildJsonObject {
                put("session_id", sessionId)
                put("prompt", prompt)
                if (!source.isNullOrBlank()) put("source", source)
            },
        )
        return raw?.let {
            runCatching {
                JsonRpcClient.DefaultJson.decodeFromJsonElement(
                    app.marmalade.android.rpc.types.PromptSubmitAck.serializer(), it,
                )
            }.getOrNull()
        }?.takeIf { it.message_id != null }
    }

    /**
     * Trigger a manual context compaction (marmaladed `session.compact`, T2
     * #11a). Queue-and-return: the ack is acceptance; progress/outcome arrive
     * as `session.compaction` events (started → completed|failed) that drive
     * the "compacting…" chip. Rejects (thrown) when a turn is in flight or the
     * harness has no compact seam.
     */
    open suspend fun sessionCompact(sessionId: String) {
        client.request(
            "session.compact",
            buildJsonObject { put("session_id", sessionId) },
        )
    }

    /**
     * Undo the last completed turn in place (marmaladed `session.undo`, T2 #6):
     * same session id, the popped rows/transcript deleted server-side. The
     * popped bubbles drop LIVE off the transient `session.undone` event; this
     * result drives only the notice (files_rewound is always false in v1 —
     * conversation only, file edits are NOT reverted). Rejects (thrown) when a
     * turn is in flight, nothing is undoable, the harness can't rewind, or the
     * cut lands on a fork-copied message. Gate the affordance on the "undo"
     * hello feature.
     */
    open suspend fun sessionUndo(sessionId: String): app.marmalade.android.rpc.types.SessionUndoResponse = call(
        "session.undo",
        buildJsonObject { put("session_id", sessionId) },
        app.marmalade.android.rpc.types.SessionUndoResponse.serializer(),
    )

    // ── Plugins ────────────────────────────────────────────────────────────

    /**
     * List the harness's installed plugins (marmaladed `plugins.list`,
     * fork-rest-triage Part E — replaces fork `plugins.manage` action=list).
     * The daemon reads Claude Code's native enabledPlugins; rows are mapped
     * onto the fork-era [PluginsListResponse] shape the screen consumes
     * (version/description are not available from the daemon in v1).
     */
    open suspend fun pluginsList(): PluginsListResponse {
        val daemon = call("plugins.list", buildJsonObject {}, DaemonPluginsListResponse.serializer())
        return PluginsListResponse(
            plugins = daemon.plugins.map { it.toPluginInfo() },
            userCount = daemon.plugins.size,
            bundledCount = 0,
        )
    }

    /**
     * Toggle a plugin (marmaladed `plugins.toggle` — flips the harness's
     * native enabledPlugins flag; takes effect on the NEXT session spawn).
     * Mapped onto the fork-era [PluginsToggleResponse]; the daemon returns
     * no refreshed row, so [PluginsToggleResponse.plugin] is null and the
     * caller's optimistic state stands.
     */
    open suspend fun pluginsToggle(name: String, enable: Boolean): PluginsToggleResponse {
        val daemon = call("plugins.toggle", buildJsonObject {
            put("name", name)
            put("enabled", enable)
        }, DaemonToggleResponse.serializer())
        return PluginsToggleResponse(ok = daemon.applied, name = name)
    }

    /** The harness's MCP servers with marmalade-managed enablement
     *  (marmaladed `mcp.list`, Part E — replaces fork REST /api/mcp). */
    open suspend fun mcpList(): DaemonMcpListResponse = call(
        "mcp.list",
        buildJsonObject {},
        DaemonMcpListResponse.serializer(),
    )

    /** Enable/disable an MCP server (marmaladed `mcp.toggle`; effective on
     *  the next session spawn). */
    open suspend fun mcpToggle(name: String, enabled: Boolean): DaemonToggleResponse = call(
        "mcp.toggle",
        buildJsonObject {
            put("name", name)
            put("enabled", enabled)
        },
        DaemonToggleResponse.serializer(),
    )

    // ── Prompts ─────────────────────────────────────────────────────────────

    /**
     * Submit a user prompt to a session. The server's response stream comes
     * back through [JsonRpcClient.events] tagged with [sessionId] — typically
     * consumed by [app.marmalade.android.chat.messages.MessageStream].
     *
     * - [truncateBeforeUserOrdinal] supports edit / regenerate flows: drops
     *   every history entry from the n-th user message onwards before
     *   appending the new prompt. Desktop's "edit & resubmit" affordance
     *   uses this; defaults to null = append.
     * - [timeout] overrides the client default for very long-running model
     *   setups.
     *
     * Attachments are NOT a server-side param. Images upload beforehand via
     * [imageAttachBytes] (the server queues them and consumes the queue on
     * this submit); files upload via [fileAttach] and their returned
     * `@file:` refs get prepended to [text]. The previous `attachments`
     * list-of-strings param was silently dropped by the server.
     */
    open suspend fun promptSubmit(
        sessionId: String,
        text: String,
        truncateBeforeUserOrdinal: Int? = null,
        idempotencyKey: String? = null,
        source: String? = null,
        timeout: Duration? = null,
    ): app.marmalade.android.rpc.types.PromptSubmitAck? {
        val params = buildJsonObject {
            put("session_id", sessionId)
            // marmaladed's PromptSubmitParams reads `prompt` (protocol v1,
            // methods.ts); the legacy fork gateway reads `text`. Send both —
            // zod strips the unknown key daemon-side, the fork ignores
            // `prompt` — so the same frame works against either server.
            put("prompt", text)
            put("text", text)
            if (truncateBeforeUserOrdinal != null) {
                put("truncate_before_user_ordinal", truncateBeforeUserOrdinal)
            }
            // Sent to the plugin so it can dedupe duplicate prompt.submit calls
            // (e.g., after a process-kill mid-ack). Plugin-side LRU lookup of
            // (user_id, key) returns {} as no-op for repeats inside the 5-minute
            // window. Unknown param today; plugin patch lands in Phase 9.
            if (idempotencyKey != null) {
                put("idempotency_key", idempotencyKey)
            }
            // Origin label read by the gateway to shape the turn (e.g.
            // source="voice" prepends VOICE_TURN_PREFIX + uses
            // persist_user_message so the model answers concisely +
            // TTS-safely without polluting session history). Old gateways
            // that predate the source param ignore it — no fallback path
            // needed on the wire.
            if (!source.isNullOrBlank()) {
                put("source", source)
            }
        }
        val raw = if (timeout != null) client.request("prompt.submit", params, timeout)
        else client.request("prompt.submit", params)
        // The daemon returns {message_id, seq, ts} — the server-minted
        // identity of the user message just accepted (P1). A legacy gateway
        // returns {} / null; decode failures degrade to null rather than
        // failing the send (the submit itself succeeded).
        return raw?.let {
            runCatching {
                JsonRpcClient.DefaultJson.decodeFromJsonElement(
                    app.marmalade.android.rpc.types.PromptSubmitAck.serializer(), it,
                )
            }.getOrNull()
        }?.takeIf { it.message_id != null }
    }

    // ── Attachments ─────────────────────────────────────────────────────────

    /**
     * Upload image bytes and queue them on the session. The next
     * `prompt.submit` for [sessionId] consumes every queued image through the
     * server's vision pipeline (native content-parts or pre-analysis,
     * per-provider — `tui_gateway/server.py:5997-6052`). This is the
     * remote-client path (`image.attach_bytes`): the file lives on the
     * phone's disk, so the path-based `image.attach` can't see it. Matches
     * desktop remote mode (`use-prompt-actions.ts:246-250`).
     *
     * Server cap: 25 MB decoded. Android stays far below it — images are
     * compressed to ≤5 MB JPEG before upload (ImageUtils).
     */
    open suspend fun imageAttachBytes(
        sessionId: String,
        contentBase64: String,
        filename: String,
    ): ImageAttachResponse = call(
        "image.attach_bytes",
        buildJsonObject {
            put("session_id", sessionId)
            put("content_base64", contentBase64)
            put("filename", filename)
        },
        ImageAttachResponse.serializer(),
        timeout = ATTACH_TIMEOUT,
    )

    /**
     * Stage a non-image file (base64 data URL) into the session workspace.
     * Returns the `@file:` ref to prepend to the prompt text — the agent
     * reads the staged file with its normal file tools. PDFs go through this
     * path too, matching desktop (no upstream client calls `pdf.attach`).
     * Server: `file.attach` (`tui_gateway/server.py:6843`); desktop caller:
     * `use-prompt-actions.ts:288-293`.
     */
    open suspend fun fileAttach(
        sessionId: String,
        name: String,
        dataUrl: String,
    ): FileAttachResponse = call(
        "file.attach",
        buildJsonObject {
            put("session_id", sessionId)
            put("name", name)
            put("data_url", dataUrl)
        },
        FileAttachResponse.serializer(),
        timeout = ATTACH_TIMEOUT,
    )

    /**
     * Remove a queued image (by the gateway-side [path] returned from the
     * attach call) before it's consumed by a submit. Server: `image.detach`
     * (`tui_gateway/server.py:6890`).
     */
    open suspend fun imageDetach(sessionId: String, path: String) {
        client.request(
            "image.detach",
            buildJsonObject {
                put("session_id", sessionId)
                put("path", path)
            },
        )
    }

    // ── Audio ───────────────────────────────────────────────────────────────

    /**
     * Server-side STT fallback (`audio.transcribe`): one finished utterance as
     * base64 audio in, a transcript out. The voice popup calls this only when
     * on-device Whisper failed AND the daemon advertised the "transcription"
     * hello feature. Wire truth: daemon `router.ts` case "audio.transcribe".
     */
    open suspend fun audioTranscribe(
        audioBase64: String,
        mime: String = "audio/wav",
    ): app.marmalade.android.rpc.types.AudioTranscribeResponse = call(
        "audio.transcribe",
        buildJsonObject {
            put("audio_base64", audioBase64)
            put("mime", mime)
        },
        app.marmalade.android.rpc.types.AudioTranscribeResponse.serializer(),
        timeout = TRANSCRIBE_TIMEOUT,
    )

    // ── Models ──────────────────────────────────────────────────────────────

    /** The daemon's model menu (marmaladed `model.list`, additive v1): the
     *  models the harness adapter can run a session on. Ids go back verbatim
     *  as [sessionCreate]'s `model` param. Replaces the fork's `model.options`
     *  (providers/pricing/capabilities — no daemon equivalent). */
    open suspend fun modelList(): ModelListResponse = call(
        "model.list",
        buildJsonObject {},
        ModelListResponse.serializer(),
    )

    /** The daemon's new-session defaults (`settings.get`, additive
     *  2026-07-25). Gated on the "settings" server feature — an older daemon
     *  404s the method and the Models screen degrades to read-only. */
    open suspend fun settingsGet(): DaemonSettings = call(
        "settings.get",
        buildJsonObject {},
        DaemonSettings.serializer(),
    )

    /**
     * Patch the daemon's new-session defaults (`settings.update`). PATCH
     * semantics: an argument left null here is OMITTED from the request and
     * the daemon leaves that key alone. To CLEAR a key (back to "defer to the
     * harness") pass [clearEffort] / [clearModel] — a JSON null, which is a
     * different request from omission and cannot be expressed by a null
     * Kotlin argument.
     *
     * The daemon applies the change live (the next session.create is stamped
     * with it) and persists it. It rejects an unknown model, an unknown
     * effort, and any key pinned by an env var on the host — surfaced to the
     * caller as a JsonRpcException, never swallowed.
     *
     * Returns the post-write settings, which are authoritative: no event is
     * broadcast, so other clients re-read on their next settings.get.
     */
    open suspend fun settingsUpdate(
        defaultModel: String? = null,
        defaultEffort: String? = null,
        clearModel: Boolean = false,
        clearEffort: Boolean = false,
        /**
         * Per-model effort bounds — a PER-MODEL patch, not a whole-map
         * replace. An omitted model id keeps its bounds; a **null VALUE**
         * removes that model's entry; an object REPLACES it wholesale (so
         * `{min}` after `{min,max}` drops the max). A null map omits the key
         * entirely and leaves every model's bounds alone.
         *
         * The daemon rejects an unknown model id and a min above its max with
         * InvalidParams.
         */
        modelEfforts: Map<String, ModelEffortBounds?>? = null,
    ): DaemonSettings = call(
        "settings.update",
        buildJsonObject {
            if (clearModel) put("default_model", JsonNull)
            else if (defaultModel != null) put("default_model", defaultModel)
            if (clearEffort) put("default_effort", JsonNull)
            else if (defaultEffort != null) put("default_effort", defaultEffort)
            if (modelEfforts != null) {
                put(
                    "model_efforts",
                    buildJsonObject {
                        modelEfforts.forEach { (modelId, bounds) ->
                            if (bounds == null) {
                                put(modelId, JsonNull)
                            } else {
                                put(
                                    modelId,
                                    buildJsonObject {
                                        bounds.min?.let { put("min", it) }
                                        bounds.max?.let { put("max", it) }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        DaemonSettings.serializer(),
    )

    // ── Skills + workspace fs (marmaladed, fork-rest-triage A+B) ────────────

    /** The daemon's skills registry with per-harness enablement
     *  (`skills.list`, additive v1). Replaces fork REST `GET /api/skills`. */
    open suspend fun skillsList(): SkillsListResponse = call(
        "skills.list",
        buildJsonObject {},
        SkillsListResponse.serializer(),
    )

    /** Enable/disable a skill (`skills.toggle`): the daemon persists the
     *  manifest and reconciles harness symlinks immediately. Replaces fork
     *  REST `POST /api/skills/{name}/toggle`. */
    open suspend fun skillsToggle(name: String, enabled: Boolean): SkillsToggleResponse = call(
        "skills.toggle",
        buildJsonObject {
            put("name", name)
            put("enabled", enabled)
        },
        SkillsToggleResponse.serializer(),
    )

    /** The daemon's default session cwd (`fs.defaults`) — seeds the workspace
     *  picker. Replaces fork REST `GET /api/fs/default-cwd`. */
    open suspend fun fsDefaults(): FsDefaultsResponse = call(
        "fs.defaults",
        buildJsonObject {},
        FsDefaultsResponse.serializer(),
    )

    /** Read-only, home-confined directory listing (`fs.list`) for the
     *  workspace picker. Replaces fork REST `GET /api/fs/list`. Confinement
     *  violations surface as thrown RPC errors, not an error field.
     *  [showHidden] includes dot-directories (the picker's "Show hidden"
     *  toggle); omitted when false so older daemons keep hiding them. */
    open suspend fun fsList(path: String, showHidden: Boolean = false): DaemonFsListResponse = call(
        "fs.list",
        buildJsonObject {
            put("path", path)
            if (showHidden) put("show_hidden", true)
        },
        DaemonFsListResponse.serializer(),
    )

    // ── Workspaces (marmaladed "workspaces" hello feature) ──────────────────
    // Paseo-style folder workspaces: a human name + emoji over a folder path on
    // the daemon host. Membership is DERIVED (session.list stamps workspace_id
    // by cwd prefix); a workspace is metadata, not a container. Gate every call
    // on the "workspaces" hello feature — old daemons 404 these methods. Wire
    // truth: marmalade/packages/protocol/src/methods.ts + router.ts.

    /** Create a workspace over [path] (realpath-confined to home; the folder
     *  must exist). Absent [name] defaults to a prettified basename daemon-side.
     *  The daemon rejects duplicates / missing folders as thrown RPC errors. */
    open suspend fun workspaceCreate(
        path: String,
        name: String? = null,
        emoji: String? = null,
    ): app.marmalade.android.rpc.types.WorkspaceMutateResponse = call(
        "workspace.create",
        buildJsonObject {
            put("path", path)
            if (!name.isNullOrBlank()) put("name", name)
            if (!emoji.isNullOrBlank()) put("emoji", emoji)
        },
        app.marmalade.android.rpc.types.WorkspaceMutateResponse.serializer(),
    )

    /** List all workspaces with live detection (git branch / CLAUDE.md / …). */
    open suspend fun workspaceList(): app.marmalade.android.rpc.types.WorkspaceListResponse = call(
        "workspace.list",
        buildJsonObject {},
        app.marmalade.android.rpc.types.WorkspaceListResponse.serializer(),
    )

    /** Rename / re-emoji a workspace (path is immutable). Pass [emoji] = "" to
     *  clear it — the wire sends explicit `null`; omitting it leaves it as-is. */
    open suspend fun workspaceUpdate(
        workspaceId: String,
        name: String? = null,
        emoji: String? = null,
        clearEmoji: Boolean = false,
    ): app.marmalade.android.rpc.types.WorkspaceMutateResponse = call(
        "workspace.update",
        buildJsonObject {
            put("workspace_id", workspaceId)
            if (!name.isNullOrBlank()) put("name", name)
            if (clearEmoji) put("emoji", JsonNull)
            else if (!emoji.isNullOrBlank()) put("emoji", emoji)
        },
        app.marmalade.android.rpc.types.WorkspaceMutateResponse.serializer(),
    )

    /** Delete a workspace — un-groups only. Its sessions are kept (they move to
     *  Quick sessions on the next session.list, workspace_id → null). */
    open suspend fun workspaceDelete(
        workspaceId: String,
    ): app.marmalade.android.rpc.types.WorkspaceDeleteResponse = call(
        "workspace.delete",
        buildJsonObject { put("workspace_id", workspaceId) },
        app.marmalade.android.rpc.types.WorkspaceDeleteResponse.serializer(),
    )

    /** Read-only peek at what a session spawned in this workspace inherits:
     *  CLAUDE.md / AGENTS.md content (capped per file — `truncated` flags it),
     *  the .memory note FILENAMES, and the current git branch. Errors on an
     *  unknown workspace id. Fetched once per detail-screen entry. */
    open suspend fun workspaceContext(
        workspaceId: String,
    ): app.marmalade.android.rpc.types.WorkspaceContextResponse = call(
        "workspace.context",
        buildJsonObject { put("workspace_id", workspaceId) },
        app.marmalade.android.rpc.types.WorkspaceContextResponse.serializer(),
    )

    // ── Interactive prompts (server → client → server round-trips) ──────────

    /**
     * Respond to a `clarify.request` — the daemon parked an agent question
     * (AskUserQuestion) and broadcast its questions; this settles it. LIVE
     * since 2026-07-18. Daemon contract (protocol methods.ts
     * ClarifyRespondParams): `{session_id, request_id?, answers?, response?}`
     * — [answers] maps question text → chosen answer (multi-select answers
     * comma-joined, the harness contract); [response] is freeform text typed
     * instead of picking. Sending NEITHER = dismissed: the agent is told to
     * proceed on its own judgment. Empty maps are omitted, never sent as `{}`.
     */
    open suspend fun clarifyRespond(
        requestId: String?,
        sessionId: String,
        answers: Map<String, String> = emptyMap(),
        response: String? = null,
    ) {
        client.request(
            "clarify.respond",
            buildJsonObject {
                put("session_id", sessionId)
                if (requestId != null) put("request_id", requestId)
                if (answers.isNotEmpty()) {
                    put("answers", buildJsonObject { answers.forEach { (q, a) -> put(q, a) } })
                }
                if (!response.isNullOrBlank()) put("response", response)
            },
        )
    }

    /**
     * Respond to an `approval.request` (marmaladed M2 tool-use approvals).
     * The daemon contract (protocol methods.ts ApprovalRespondParams) is
     * `{session_id, choice, request_id?}` — session-keyed FIFO with
     * request_id carried anyway: when [requestId] is null the daemon
     * resolves the session's OLDEST pending request (unambiguous because the
     * daemon serializes approvals per session).
     *
     * [choice] is one of `"once"` / `"session"` / `"always"` / `"deny"`.
     * `"session"` allowlists the matched `pattern_key` for the session's
     * life; `"always"` is not offered in v1 (allow_permanent:false hides
     * the button). [all] is fork-era vocabulary the daemon ignores.
     */
    open suspend fun approvalRespond(
        choice: String,
        sessionId: String? = null,
        all: Boolean = false,
        requestId: String? = null,
    ) {
        client.request(
            "approval.respond",
            buildJsonObject {
                put("choice", choice)
                if (sessionId != null) put("session_id", sessionId)
                if (requestId != null) put("request_id", requestId)
                if (all) put("all", true)
            },
        )
    }

    /**
     * Respond to a `secret.request` — the ONE RPC on this protocol whose
     * params carry a live credential.
     *
     * Contract (protocol methods.ts SecretRespondParams), all of it load-
     * bearing:
     *  - the schema is `.strict()`, so an extra key is a hard InvalidParams —
     *    only session_id / request_id / value / deny / reason may be sent;
     *  - a `.refine()` demands EXACTLY ONE of `value` or `deny: true`, so the
     *    deny branch must omit `value` entirely rather than send it empty;
     *  - `session_id` is REQUIRED (unlike approval.respond's optional one);
     *  - `request_id` is optional — omitting it makes the daemon settle the
     *    session's OLDEST pending request (FIFO, unambiguous because secret
     *    requests are serialized per session, same gate as approvals).
     *
     * [value] goes into the keyring child's stdin and nowhere else. It must
     * never be logged, persisted, or echoed — including by this client. Do
     * not add [value] to any log line here or at any call site.
     */
    open suspend fun secretRespond(
        sessionId: String,
        requestId: String? = null,
        value: String? = null,
        deny: Boolean = false,
        reason: String? = null,
    ): SecretRespondResult = call(
        "secret.respond",
        buildJsonObject {
            put("session_id", sessionId)
            if (requestId != null) put("request_id", requestId)
            if (deny) {
                put("deny", true)
                if (!reason.isNullOrBlank()) put("reason", reason)
            } else {
                put("value", requireNotNull(value) { "secret.respond needs a value unless deny=true" })
            }
        },
        SecretRespondResult.serializer(),
    )

    /**
     * Respond to a `sudo.request` from the terminal_tool's sudo password
     * capture. Without this respond, the gateway-side tool blocks forever
     * on the sudo wait — matches desktop's `setSudoRequest` flow.
     */
    open suspend fun sudoRespond(requestId: String, password: String, sessionId: String? = null) {
        client.request(
            "sudo.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("password", password)
                if (sessionId != null) put("session_id", sessionId)
            },
        )
    }

    /**
     * Respond to a `terminal.read.request` (read_terminal tool). The gateway
     * tool blocks on the respond; we always reply, even with empty text when
     * no terminal pane exists on this device — otherwise the tool hangs the
     * agent indefinitely on Android. Matches the desktop's "always answer"
     * contract in `use-message-stream.ts:1069-1072`.
     */
    open suspend fun terminalReadRespond(requestId: String, text: String, sessionId: String? = null) {
        client.request(
            "terminal.read.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("text", text)
                if (sessionId != null) put("session_id", sessionId)
            },
        )
    }

    // ── Cron (scheduled prompts — daemon cron.*) ────────────────────────────
    // Wire truth: marmalade/packages/protocol/src/methods.ts. The daemon owns
    // ALL schedule state; the client refetches after mutations rather than
    // mirroring semantics locally.

    open suspend fun cronList(): CronListResponse = call(
        "cron.list",
        buildJsonObject {},
        CronListResponse.serializer(),
    )

    open suspend fun cronCreate(
        sessionId: String,
        prompt: String,
        schedule: CronSchedule,
        name: String? = null,
    ): CronJobResponse = call(
        "cron.create",
        buildJsonObject {
            put("session_id", sessionId)
            put("prompt", prompt)
            put("schedule", JsonRpcClient.DefaultJson.encodeToJsonElement(CronSchedule.serializer(), schedule))
            if (name != null) put("name", name)
        },
        CronJobResponse.serializer(),
    )

    /** Partial update — only non-null fields ride the wire. Enable/disable is
     *  `enabled`; a schedule change makes the daemon recompute next_run_at. */
    open suspend fun cronUpdate(
        jobId: String,
        enabled: Boolean? = null,
        name: String? = null,
        prompt: String? = null,
        schedule: CronSchedule? = null,
    ): CronJobResponse = call(
        "cron.update",
        buildJsonObject {
            put("job_id", jobId)
            if (enabled != null) put("enabled", enabled)
            if (name != null) put("name", name)
            if (prompt != null) put("prompt", prompt)
            if (schedule != null) {
                put("schedule", JsonRpcClient.DefaultJson.encodeToJsonElement(CronSchedule.serializer(), schedule))
            }
        },
        CronJobResponse.serializer(),
    )

    open suspend fun cronDelete(jobId: String): CronDeleteResponse = call(
        "cron.delete",
        buildJsonObject { put("job_id", jobId) },
        CronDeleteResponse.serializer(),
    )

    /** Out-of-band fire — the scheduled next_run_at does not move; fired=false
     *  means the job is mid-run (single-flight), not an error. */
    open suspend fun cronRunNow(jobId: String): CronRunNowResponse = call(
        "cron.run_now",
        buildJsonObject { put("job_id", jobId) },
        CronRunNowResponse.serializer(),
    )

    // ── Usage (daily rollups — daemon usage.summary, T2 #8) ─────────────────

    /** Trailing [days]-day window (1..90) ending at the daemon's today. */
    open suspend fun usageSummary(days: Int = 7): UsageSummaryResponse = call(
        "usage.summary",
        buildJsonObject { put("days", days) },
        UsageSummaryResponse.serializer(),
    )

    // ── Terminals (daemon terminal.*) ──────────────────────────────────────
    // Daemon-hosted PTY terminals ALONGSIDE agent sessions. A terminal is NOT a
    // session: no identity/transcript/replay/seq — output is transient and
    // attach-scoped (terminal.data goes only to attached connections). Gate the
    // whole surface on the "terminal" hello feature (MarmaladeRuntime.
    // terminalSupported). terminal.data/terminal.exit route AROUND the chat
    // path (TerminalController), never through Room. Base64 both directions so
    // control bytes survive JSON. Wire truth: protocol methods.ts terminal
    // section + events.ts.

    /** Spawn a shell ($SHELL, login) as the daemon's user; the creating
     *  connection is auto-attached. cwd defaults to the daemon's cwd. */
    open suspend fun terminalCreate(
        cols: Int = 80,
        rows: Int = 24,
        cwd: String? = null,
    ): app.marmalade.android.rpc.types.TerminalCreateResponse = call(
        "terminal.create",
        buildJsonObject {
            put("cols", cols)
            put("rows", rows)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        },
        app.marmalade.android.rpc.types.TerminalCreateResponse.serializer(),
    )

    /** Join a terminal's live output stream. The result carries the scrollback
     *  snapshot (base64); attach + snapshot are atomic server-side, so the
     *  client writes the snapshot then applies subsequent terminal.data with no
     *  gap. Re-attach (reconnect) is the same call. */
    open suspend fun terminalAttach(
        terminalId: String,
    ): app.marmalade.android.rpc.types.TerminalAttachResponse = call(
        "terminal.attach",
        buildJsonObject { put("terminal_id", terminalId) },
        app.marmalade.android.rpc.types.TerminalAttachResponse.serializer(),
    )

    /** Leave the output stream (client navigated away). The shell keeps running;
     *  this only stops delivery to THIS connection. Fire-and-forget. */
    open suspend fun terminalDetach(terminalId: String) {
        client.request(
            "terminal.detach",
            buildJsonObject { put("terminal_id", terminalId) },
        )
    }

    /** Write keystrokes/paste to the PTY. [dataB64] is base64 so control bytes
     *  (^C, arrow-key ESC sequences) survive JSON. */
    open suspend fun terminalInput(terminalId: String, dataB64: String) {
        client.request(
            "terminal.input",
            buildJsonObject {
                put("terminal_id", terminalId)
                put("data_b64", dataB64)
            },
        )
    }

    /** Emulator geometry changed (rotation, keyboard, fit) → the PTY gets
     *  SIGWINCH; full-screen apps re-draw. */
    open suspend fun terminalResize(terminalId: String, cols: Int, rows: Int) {
        client.request(
            "terminal.resize",
            buildJsonObject {
                put("terminal_id", terminalId)
                put("cols", cols)
                put("rows", rows)
            },
        )
    }

    /** Kill the shell. Attached connections get a terminal.exit event when the
     *  process actually dies; the roster row goes with it. */
    open suspend fun terminalClose(
        terminalId: String,
    ): app.marmalade.android.rpc.types.TerminalCloseResponse = call(
        "terminal.close",
        buildJsonObject { put("terminal_id", terminalId) },
        app.marmalade.android.rpc.types.TerminalCloseResponse.serializer(),
    )

    /** The live terminal roster (shell · cwd · pid). Honest empty list when no
     *  shells are running (no tmux-style persistence across daemon restarts). */
    open suspend fun terminalList(): app.marmalade.android.rpc.types.TerminalListResponse = call(
        "terminal.list",
        buildJsonObject {},
        app.marmalade.android.rpc.types.TerminalListResponse.serializer(),
    )

    // ── Search (marmaladed "search" hello feature) ──────────────────────────

    /**
     * Full-text search over session MESSAGE TEXT (`search.messages`).
     *
     * Gate every call on the "search" hello feature
     * ([app.marmalade.android.node.MarmaladeRuntime.searchSupported] in :app) —
     * a daemon without the FTS sidecar answers MethodNotFound.
     *
     * [query] is RAW user text; the daemon builds the FTS MATCH expression from
     * it (quoted phrases are honoured, a trailing `*` is a prefix marker).
     * Never pre-escape or send FTS syntax. The daemon requires at least 2
     * characters.
     *
     * Scope semantics, defaults and the exact request shape live in
     * [buildSearchMessagesParams] — this is a thin typed wrapper so the JSON
     * stays unit-testable without a socket.
     */
    open suspend fun searchMessages(
        query: String,
        scope: SearchScopeSelection = SearchScopeSelection.EVERYWHERE,
        role: String? = null,
        since: Long? = null,
        includeArchived: Boolean = SearchDefaults.INCLUDE_ARCHIVED,
        sort: String = SearchDefaults.SORT,
        limit: Int = SearchDefaults.LIMIT,
        offset: Int = SearchDefaults.OFFSET,
    ): SearchMessagesResponse = call(
        "search.messages",
        buildSearchMessagesParams(
            query = query,
            scope = scope,
            role = role,
            since = since,
            includeArchived = includeArchived,
            sort = sort,
            limit = limit,
            offset = offset,
        ),
        SearchMessagesResponse.serializer(),
    )

    /**
     * One page of a PRE-DAEMON archive session's transcript (`search.archive`).
     *
     * Gate every call on the "search_archive" hello feature
     * ([app.marmalade.android.node.MarmaladeRuntime.searchArchiveSupported] in
     * :app) — a daemon without the archive index answers MethodNotFound.
     *
     * [sessionId] is an ARCHIVE session id (a Claude Code UUID) from an archive
     * hit's `session_id`. This is the ONLY way to read that conversation: there
     * is no archive equivalent of `session.resume`, by design — the corpus is
     * history, not state, so nothing here can be opened, resumed or written.
     *
     * Served entirely from the daemon's index, so a since-deleted `.jsonl` still
     * renders and the fetch never re-reads disk.
     */
    open suspend fun searchArchive(
        sessionId: String,
        limit: Int = SearchArchiveDefaults.LIMIT,
        offset: Int = SearchArchiveDefaults.OFFSET,
    ): SearchArchiveResponse = call(
        "search.archive",
        buildSearchArchiveParams(sessionId = sessionId, limit = limit, offset = offset),
        SearchArchiveResponse.serializer(),
    )

    // ── plumbing ────────────────────────────────────────────────────────────

    private suspend fun <T> call(
        method: String,
        params: JsonObject,
        deserializer: DeserializationStrategy<T>,
        timeout: Duration? = null,
    ): T {
        val raw = (if (timeout != null) client.request(method, params, timeout) else client.request(method, params))
            ?: throw IllegalStateException("$method returned null result (expected ${deserializer::class.simpleName})")
        return JsonRpcClient.DefaultJson.decodeFromJsonElement(deserializer, raw)
    }

    companion object {
        /** Attach uploads push multi-MB base64 frames over the WS — give them
         *  headroom beyond the 20s default (a 13 MB frame over a slow tailnet
         *  link can take tens of seconds). */
        private val ATTACH_TIMEOUT = 60.seconds

        /** Server-side STT is slow by design (whisper reloads its model per
         *  invocation; the daemon's own exec timeout is 180s) — the RPC must
         *  outlive it so the daemon's error string reaches the client. */
        private val TRANSCRIBE_TIMEOUT = 200.seconds
    }
}
