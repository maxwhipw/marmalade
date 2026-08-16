package app.marmalade.android.rpc

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Wire-conformance contract: everything this client puts on the marmaladed
 * wire must be something the daemon actually routes. The goal is loud CI
 * failure the moment someone adds a client call to a nonexistent daemon
 * method (or handles an event the daemon never emits) — a class of bug that
 * otherwise only shows up as a MethodNotFound toast on-device.
 *
 * Ground truth (daemon repo, cross-checked 2026-07-11):
 *  - JSON-RPC methods: marmalade/packages/daemon/src/router.ts `case`
 *    statements, plus `hello` (routed at the gateway/handshake layer in
 *    gateway.ts, not router.ts).
 *  - Event names: marmalade/packages/protocol/src/frames.ts
 *    `KnownGatewayEventName`.
 *
 * The client's own surface is read from source at test time (the method/event
 * names aren't introspectable at runtime), so this stays honest as the client
 * grows — a new `client.request("foo.bar", …)` fails here until foo.bar is
 * classified as live or KNOWN_DORMANT.
 */
class WireConformanceTest {

    // ── Daemon truth ────────────────────────────────────────────────────────

    /**
     * Methods the daemon routes and answers for real. From router.ts `case`
     * statements (session.* / prompt.submit / model.list) + `hello` (handled
     * in gateway.ts at connection upgrade). Verified against
     * marmalade/packages/daemon/src/router.ts on 2026-07-11.
     */
    private val liveDaemonMethods = setOf(
        "hello",
        "session.create",
        "session.resume",
        "session.subscribe",
        "session.unsubscribe",
        "session.seen",
        "session.delete",
        "session.title",
        "session.list",
        "session.interrupt",
        "session.stop",
        "session.summary",
        "prompt.submit",
        "model.list",
        // Daemon-owned new-session defaults (daemon, 2026-07-25): router.ts
        // cases "settings.get" / "settings.update" read + patch the model and
        // reasoning-effort defaults in config.json, applied live and persisted.
        // Wire truth methods.ts SettingsGet/UpdateParams + SettingsResult;
        // advertised as the "settings" hello feature.
        "settings.get",
        "settings.update",
        // Singleton main session (daemon, 2026-07-19 — assistant plan): router.ts
        // cases "session.main" (get-or-create the daemon-managed Home session),
        // "session.clear" (reset conversation in place — the non-deletable main's
        // start-over), "session.model" (change model on an existing session). Wire
        // truth methods.ts SessionMain/Clear/ModelParams+Result.
        "session.main",
        "session.clear",
        "session.model",
        // Mutable per-session reasoning effort (daemon, 2026-07-25): router.ts
        // case "session.effort" — session.model's twin, validated against
        // EFFORT_LEVELS, rejected mid-turn, idle live child restarted so it
        // applies now. Wire truth methods.ts SessionEffortParams/Result. Before
        // it, effort was create-only and the composer's pick was cosmetic on
        // every existing session.
        "session.effort",
        // Archived sessions (daemon session.archive, 2026-07-23): router.ts case
        // "session.archive" sets the shared archived flag; wire truth methods.ts
        // SessionArchiveParams/Result. Idempotent; rejects the main session.
        "session.archive",
        // fork-rest-triage A+B+E (daemon, 2026-07-11/12):
        "skills.list",
        "skills.toggle",
        "fs.defaults",
        "fs.list",
        "mcp.list",
        "mcp.toggle",
        "plugins.list",
        "plugins.toggle",
        // M2 pairing (daemon, 2026-07-11) — client-side calls land with the
        // rebuilt pairing UI:
        "pairing.start",
        "pairing.claim",
        "device.list",
        "device.revoke",
        // M2 approvals (daemon, 2026-07-12):
        "approval.respond",
        "session.approvals",
        // T1 attachments (daemon, 2026-07-17): staged per-session, consumed by
        // the next prompt.submit; gated client-side on the "attachments" hello
        // feature. router.ts image.attach_bytes / file.attach / image.detach.
        "image.attach_bytes",
        "file.attach",
        "image.detach",
        // Scheduled prompts (daemon cron.*, 2026-07-17 — parity-map T2 #1):
        // router.ts routes all five; wire truth methods.ts CronScheduleSchema.
        "cron.list",
        "cron.create",
        "cron.update",
        "cron.delete",
        "cron.run_now",
        // Usage rollups (daemon usage.summary, 2026-07-18 — parity-map T2 #8):
        // wire truth methods.ts UsageSummaryParams/Result.
        "usage.summary",
        // Session fork (daemon session.fork, 2026-07-18 — parity-map T2 #3):
        // harness-native branch; router.ts case "session.fork". Wire truth
        // methods.ts SessionForkParams/Result.
        "session.fork",
        // Steer / compact / undo (daemon, 2026-07-18 — T2 #6 / #11a): router.ts
        // cases "session.steer" / "session.compact" / "session.undo". Wire
        // truth methods.ts SessionSteerParams / SessionCompactParams /
        // SessionUndoParams+Result. (These share names with dead fork RPCs but
        // are daemon contracts now — the daemon reimplemented them.)
        "session.steer",
        "session.compact",
        "session.undo",
        // Server-side STT fallback (daemon audio.transcribe, 2026-07-18):
        // voice popup only, gated on the "transcription" hello feature.
        // router.ts case "audio.transcribe"; wire truth methods.ts
        // AudioTranscribeParams/Result.
        "audio.transcribe",
        // Agent questions (daemon clarify round-trip, 2026-07-18): the daemon
        // bridges AskUserQuestion to clarify.request/clarify.resolved events
        // and routes clarify.respond (router.ts; wire truth methods.ts
        // ClarifyRespondParams). "clarify" hello feature.
        "clarify.respond",
        // Secret entry: the daemon parks the agent's request_secret tool call,
        // pushes secret.request to clients declaring the "secrets" capability,
        // and writes the answered value to the keyring. router.ts case
        // "secret.respond"; wire truth methods.ts SecretRespondParams (strict:
        // exactly one of value / deny:true, session_id required).
        "secret.respond",
        // Workspaces (daemon, 2026-07-18): Paseo-style folder workspaces —
        // workspace.* CRUD + workspace_id stamped on session.list rows. router.ts
        // cases "workspace.create"/"list"/"update"/"delete"/"context"; wire truth
        // methods.ts WorkspaceCreate/List/Update/Delete/ContextParams. "workspaces"
        // hello feature.
        "workspace.create",
        "workspace.list",
        "workspace.update",
        "workspace.delete",
        "workspace.context",
        // Terminals (daemon terminal.*, 2026-07-19):
        // daemon-hosted PTY terminals ALONGSIDE agent sessions. router.ts cases
        // "terminal.create"/"attach"/"detach"/"input"/"resize"/"close"/"list";
        // wire truth methods.ts terminal section. Gated client-side on the
        // "terminal" hello feature. The transient terminal.data/terminal.exit
        // events route AROUND the chat path (TerminalController, not
        // ChatEventRouter/MessageStream), so they are deliberately absent from
        // daemonEventNames — the event test only scans the chat dispatch.
        "terminal.create",
        "terminal.attach",
        "terminal.detach",
        "terminal.input",
        "terminal.resize",
        "terminal.close",
        "terminal.list",
        // Full-text message search (daemon FTS5 sidecar, 2026-07-24): router.ts
        // case "search.messages"; wire truth methods.ts SearchMessagesParams /
        // SearchHitWire / SearchSessionWire / SearchMessagesResult. Gated
        // client-side on the "search" hello feature — the daemon 404s the method
        // when the sidecar isn't wired.
        "search.messages",
        // The pre-daemon archive corpus (2026-07-28): router.ts case
        // "search.archive"; wire truth methods.ts SearchArchiveParams /
        // SearchArchiveResult, plus SearchScope.corpus on search.messages.
        // Gated client-side on its OWN "search_archive" hello feature — a
        // daemon can run the FTS sidecar without having indexed ~/.claude/projects.
        "search.archive",
    )

    /**
     * Methods the client still SENDS but the daemon does NOT route yet — they
     * MethodNotFound on the wire and their UI degrades (or is gated off).
     * Kept deliberately, not dead, so this set must be explicit: a NEW unknown
     * method (neither live nor listed here) fails the test.
     *
     *  - sudo/terminal.read responds → interactive round-trips land with M2
     *    with M2. (approval went live with M2, clarify
     *    with the 2026-07-18 AskUserQuestion bridge, secret with the
     *    secret-entry flow — router.ts case "secret.respond".)
     *
     * (image/file attach + detach moved to liveDaemonMethods on 2026-07-17 when
     * the daemon shipped T1 attachments + the "attachments" hello feature.)
     */
    private val knownDormantMethods = setOf(
        "sudo.respond",
        "terminal.read.respond",
    )

    /**
     * Event names the daemon may emit — frames.ts KnownGatewayEventName, plus
     * `session.deleted`, which the daemon sends directly (router.ts
     * session.delete broadcasts it outside the enum). `terminal.read.request`
     * appears NOWHERE in the daemon (not in frames.ts, never emitted) — it is
     * fork-era vocabulary the client still auto-responds to, kept here only so
     * the handler doesn't trip the unknown-event check; it dies with the
     * dormant respond family (fork-rest-triage / m2-approvals). Verified
     * against frames.ts + router.ts on 2026-07-11.
     */
    private val daemonEventNames = setOf(
        "gateway.ready",
        "session.info",
        "message.start",
        "message.delta",
        "message.complete",
        "message.user",
        "thinking.delta",
        "reasoning.delta",
        "reasoning.available",
        "status.update",
        "tool.start",
        "tool.progress",
        "tool.complete",
        "tool.generating",
        "clarify.request",
        "approval.request",
        "sudo.request",
        "secret.request",
        "background.complete",
        "error",
        "skin.changed",
        "subagent.spawn_requested",
        "subagent.start",
        // Emitted directly by the daemon, outside the KnownGatewayEventName enum:
        "session.deleted",         // router.ts session.delete broadcast
        "session.undone",          // router.ts session.undo broadcast (in frames.ts enum too)
        "session.cleared",         // router.ts session.clear broadcast (transient; schematized in events.ts)
        "approval.resolved",       // M2 transient broadcast when an approval settles
        "clarify.resolved",        // transient broadcast when an agent question settles (any device)
        // Transient, outcome-only ({request_id, outcome, error?} — never a
        // value). Also arrives unprompted on the daemon's own denial paths
        // (10-min timeout, session stop/delete/error, last secrets-capable
        // client disconnecting). events.ts SecretResolvedPayload.
        "secret.resolved",
        // T2 #11a: emitted by normalize.ts on harness compaction (started →
        // completed|failed|boundary). NOTE: not yet listed in frames.ts
        // KnownGatewayEventName (the enum is open/documentation-only), but the
        // daemon does emit it — like session.deleted, it lives outside the enum.
        "session.compaction",
        // Per-model effort bounds (2026-07-27): emitted at every clamp seam
        // (session.create, session.effort) when a bound actually moved the
        // requested level. DURABLE — stamped and cached, so it replays on cold
        // load. In frames.ts KnownGatewayEventName; payload in events.ts.
        "effort.clamped",
        // Fork-era, daemon never emits it (see doc comment above):
        "terminal.read.request",
    )

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `every JSON-RPC method the client sends is live or known-dormant`() {
        val src = stripComments(readClientSource("app/marmalade/android/rpc/MarmaladeRpc.kt"))
        val sent = RPC_METHOD_PATTERN.findAll(src).map { it.groupValues[1] }.toSet()
        // Guard against a silently under-matching pattern (a false green): the
        // client sends this many distinct methods today; if extraction drops
        // below it, the pattern/path drifted and the test is not actually
        // checking anything.
        assertTrue(
            "expected >= $MIN_EXPECTED_METHODS RPC methods, found ${sent.size}: $sent " +
                "— pattern or source path drifted",
            sent.size >= MIN_EXPECTED_METHODS,
        )

        val allowed = liveDaemonMethods + knownDormantMethods
        val unknown = sent - allowed
        if (unknown.isNotEmpty()) {
            fail(
                "MarmaladeRpc sends method(s) the daemon neither routes nor has a " +
                    "documented dormant plan for: $unknown\n" +
                    "Add each to liveDaemonMethods (if router.ts routes it) or to " +
                    "knownDormantMethods with a comment pointing at the triage/M2 plan.",
            )
        }
    }

    @Test
    fun `every event the client handles is one the daemon can emit`() {
        // Controller-level event dispatch lives in ChatEventRouter since the
        // 2026-07-17 ChatController decomposition.
        val router = stripComments(readClientSource("app/marmalade/android/chat/ChatEventRouter.kt"))
        val stream = stripComments(readClientSource("app/marmalade/android/chat/messages/MessageStream.kt"))
        val handled = EVENT_CASE_PATTERN.findAll(router + "\n" + stream)
            .flatMap { m -> BARE_STRING.findAll(m.value).map { it.groupValues[1] } }
            .toSet()
        assertTrue(
            "expected >= $MIN_EXPECTED_EVENTS handled events, found ${handled.size}: $handled " +
                "— pattern or source path drifted",
            handled.size >= MIN_EXPECTED_EVENTS,
        )

        val unknown = handled - daemonEventNames
        if (unknown.isNotEmpty()) {
            fail(
                "ChatEventRouter/MessageStream handle event(s) the daemon does not emit " +
                    "per frames.ts KnownGatewayEventName (+ session.deleted / " +
                    "terminal.read.request): $unknown\n" +
                    "Either the daemon added the event (extend daemonEventNames + " +
                    "frames.ts) or the handler is dead.",
            )
        }
    }

    // ── Source access ───────────────────────────────────────────────────────

    /**
     * Read a client source file by its package-relative path. Robust to the
     * unit-test working directory (AGP runs with user.dir = the module dir, but
     * CI invocations vary): walk up from user.dir until a candidate
     * `<dir>/<sourceRoot>/<rel>` exists. Source roots cover :app (src/main/java)
     * and the :shared KMP module (ADR 0011: rpc client now in jvmSharedMain, the
     * pure protocol types in commonMain).
     */
    private fun readClientSource(packageRelative: String): String {
        val sourceRoots = listOf(
            "src/main/java",
            "app/src/main/java",
            "shared/src/jvmSharedMain/kotlin",
            "shared/src/commonMain/kotlin",
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val tried = mutableListOf<String>()
        while (dir != null) {
            for (root in sourceRoots) {
                val f = File(File(dir, root), packageRelative)
                tried += f.path
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        error("could not locate $packageRelative from user.dir; tried:\n${tried.joinToString("\n")}")
    }

    /**
     * Strip `//` line comments and block comments so method/event names that
     * only appear in prose (docblocks reference dead fork methods by name)
     * don't count as sent/handled. Crude but sufficient for Kotlin source with
     * no `//`-in-string-literal cases in these files.
     */
    private fun stripComments(src: String): String =
        src.replace(BLOCK_COMMENT, " ")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    companion object {
        /** Detection floors — grown by steer/compact/undo (+3 methods) and
         *  session.compaction/session.undone (+2 events), 2026-07-18, by the
         *  seven terminal.* methods (+7), 2026-07-19, and by session.main/clear/
         *  model (+3 methods) + session.cleared (+1 event), 2026-07-19, and by
         *  session.archive (+1 method), 2026-07-23. If extraction drops below
         *  these, the pattern/path drifted and the test would false-green. */
        private const val MIN_EXPECTED_METHODS = 35
        private const val MIN_EXPECTED_EVENTS = 25

        /** `client.request("x", …)` or `call("x", …)` — the two send seams in
         *  MarmaladeRpc. The method string is the first arg; it commonly sits
         *  on the NEXT line, so allow whitespace/newlines before it. */
        private val RPC_METHOD_PATTERN =
            Regex("""(?:client\.request|\bcall)\(\s*"([a-z][a-zA-Z._]+)"""", RegexOption.DOT_MATCHES_ALL)

        /** A whole `when` case label group ending in `->`, possibly multi-label
         *  (`"tool.start", "tool.progress" ->`). Bare strings are pulled out of
         *  the matched span by [BARE_STRING]. */
        private val EVENT_CASE_PATTERN =
            Regex(""""[a-z][a-zA-Z._]+"(?:\s*,\s*"[a-z][a-zA-Z._]+")*\s*->""")

        private val BARE_STRING = Regex(""""([a-z][a-zA-Z._]+)"""")

        private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    }
}
