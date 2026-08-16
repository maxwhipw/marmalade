// marmaladed — the orchestrator daemon entry point.
//
// M0+M1: config → session index → transcript cache → usage meter →
// ClaudeCodeAdapter → router → gateway. The supervisor loop (M1.5) and state
// preload (M4a) layer on from here. Structural security invariants (policy.ts)
// are enforced from day one.

import { randomUUID } from "node:crypto";
import { homedir } from "node:os";
import { join } from "node:path";
import { defaultConfig, loadConfigFile, defaultConfigPath } from "./config.js";
import { SessionManager, defaultDbPath } from "./session-manager.js";
import { TranscriptCache } from "./transcript-cache.js";
import { ClaudeCodeAdapter } from "./claude-code-adapter.js";
import { createRouter } from "./router.js";
import { Gateway } from "./gateway.js";
import { Supervisor } from "./supervisor.js";
import { SkillsStore } from "./skills-store.js";
import { HarnessConfigStore } from "./harness-config.js";
import { CronScheduler } from "./cron-scheduler.js";
import { AttachmentStore } from "./attachments.js";
import { Transcriber } from "./transcription.js";
import { fetchSecret } from "./keyring.js";
import { NtfyNotifier } from "./ntfy.js";
import { TerminalManager, loadPtyModule } from "./terminal.js";
import { SearchStore, defaultSearchDbPath, maxEventSeq } from "./search-store.js";
import { scanArchive, defaultArchiveDir } from "./archive-indexer.js";

export { defaultConfig, loadConfigFile, defaultConfigPath, ConfigFileSchema } from "./config.js";
export type { ConfigFile } from "./config.js";
export * from "./policy.js";
export { SessionManager, defaultDbPath } from "./session-manager.js";
export { Gateway, RpcMethodError } from "./gateway.js";
export type { Connection, RequestHandler } from "./gateway.js";
export { UsageMeter } from "./usage.js";
export { TranscriptCache } from "./transcript-cache.js";
export { normalize } from "./normalize.js";
export { SessionIdentity, mintMessageId, wireOrigin } from "./identity.js";
export type { Origin } from "./identity.js";
export { MessageStore } from "./message-store.js";
export type { MessageRecord, MessageStatus, MessageRole } from "./message-store.js";
export { SeenStore } from "./seen-store.js";
export { DeviceStore } from "./device-store.js";
export type { DeviceRecord } from "./device-store.js";
export { PairingStore, encodeSetupCode, isTailnetIPv4, detectTailnetIPv4 } from "./pairing.js";
export type { TokenIdentity } from "./pairing.js";
export { ClaudeCodeAdapter } from "./claude-code-adapter.js";
export { OpenCodeAdapter } from "./opencode-adapter.js";
export { normalizeAcp } from "./acp-normalize.js";
export { Supervisor } from "./supervisor.js";
export { renderMainSystemPrompt } from "./behavior.js";
export { assembleStatePreload, resolveStateTiers } from "./state-preload.js";
export { syncAll, syncHarness, listRegistrySkills } from "./skills-sync.js";
export { SkillsStore } from "./skills-store.js";
export { HarnessConfigStore } from "./harness-config.js";
export { listDirConfined } from "./fs-browse.js";
export { CronStore } from "./cron-store.js";
export type { CronJobRecord } from "./cron-store.js";
export { CronScheduler } from "./cron-scheduler.js";
export { computeNextRunAt, computeNextFireAt, validateSchedule } from "./cron-schedule.js";
export type { CronScheduleSpec } from "./cron-schedule.js";
export type { HarnessAdapter, HarnessSession } from "./adapter.js";
export { NtfyNotifier } from "./ntfy.js";
export type { NtfyConfig } from "./ntfy.js";
export {
  fetchSecret, storeSecret, listSecrets, removeSecret, KeyringError,
  DEFAULT_KEYRING_COMMAND, DEFAULT_KEYRING_INSERT_COMMAND,
  DEFAULT_KEYRING_LIST_COMMAND, DEFAULT_KEYRING_REMOVE_COMMAND,
} from "./keyring.js";
export type { KeyringConfig } from "./keyring.js";
export { TerminalManager, loadPtyModule } from "./terminal.js";
export type { PtyModule } from "./terminal.js";
export { SearchStore, defaultSearchDbPath, extractMessages, buildMatchExpression, maxEventSeq } from "./search-store.js";
export { scanArchive, extractArchiveSession, listArchiveFiles, defaultArchiveDir } from "./archive-indexer.js";

export interface Daemon {
  sessions: SessionManager;
  gateway: Gateway;
  supervisor: Supervisor;
  stop(): Promise<void>;
}

function todayStr(): string {
  return new Date().toISOString().slice(0, 10);
}

export async function startDaemon(): Promise<Daemon> {
  // Config file (env still overrides per knob). loadConfigFile THROWS on a
  // malformed/unknown-key file — startup fails visibly rather than running
  // with a silently ignored config.
  const cfg = defaultConfig(loadConfigFile());
  console.log(`[marmaladed] config: ${defaultConfigPath()}${cfg.budget ? ` (budget: ${cfg.budget.dailyLimit} ${cfg.budget.metric}/day)` : ""}`);
  const sessions = new SessionManager(defaultDbPath(cfg.stateDir));
  const transcripts = new TranscriptCache(join(cfg.stateDir, "transcripts"));
  // Search index (search.messages): a SIDECAR db, never sessions.db — "SQLite =
  // index, NDJSON = content", and an FTS table holds content. Disposable by
  // declaration: delete search.db and the boot reconcile below rebuilds it.
  const search = new SearchStore(defaultSearchDbPath(cfg.stateDir));
  // Persisted rollups in the session db (T2 #8) — totals survive restart.
  const usage = sessions.usage;
  const log = (line: string) => console.log(line);

  // Metered key (policy 5.3): fetched from the keyring at startup, never read
  // from an inherited env var. A CONFIGURED entry that won't fetch fails
  // startup loudly — a broken keyring that silently yields no key would only
  // surface much later, as an authClass=metered spawn refusing to start.
  const meteredKey = cfg.keyring?.meteredKeyEntry
    ? await fetchSecret(cfg.keyring.meteredKeyEntry, cfg.keyring)
    : undefined;
  if (meteredKey) log(`[keyring] metered key loaded from entry "${cfg.keyring!.meteredKeyEntry}"`);

  const adapter = new ClaudeCodeAdapter({
    path: process.env.PATH ?? "",
    ...(meteredKey ? { meteredKey } : {}),
    log,
  });

  // ntfy SECONDARY alert path (hardening #2) — daemon-side alerts when a
  // topic is configured; the client's always-on WS stays the primary
  // mobile mechanism. undefined = feature off, every seam degrades to logs.
  const ntfy = cfg.ntfy ? new NtfyNotifier(cfg.ntfy, { log }) : undefined;
  if (cfg.ntfy) log(`[ntfy] secondary alerts on → ${cfg.ntfy.server}/${cfg.ntfy.topic}`);

  // Forward-declared so the router can clear a session's supervisor latch on
  // stop/failure (assigned below; the router only calls it at runtime) — and
  // read live gateway connections for the device roster (P3).
  let supervisorRef: Supervisor | undefined;
  let gatewayRef: Gateway | undefined;
  // Scheduled prompts (T2 #1): jobs persist in the same SQLite file; the
  // fire seam is wired to the router below (it doesn't exist yet here).
  const cron = new CronScheduler({
    store: sessions.cron,
    now: () => Date.now(),
    log,
    alert: (title, message) => { void ntfy?.publish(title, message, { priority: 4 }); },
  });
  // Server-side STT fallback (audio.transcribe): wired only when the STT
  // command's binary resolves on this host; the same check gates the
  // "transcription" hello feature below, so clients never fall back into a
  // method that can't run.
  const transcriber = new Transcriber(cfg.transcribeCommand);
  const transcriptionAvailable = transcriber.available();
  log(`[stt] audio.transcribe ${transcriptionAvailable ? "available" : "unavailable (no STT command on PATH)"}`);

  // PTY terminals (terminal.*): host-conditional like
  // transcription — wired only when config allows AND node-pty's native build
  // loads; the "terminal" hello feature is gated on the same check, so clients
  // never render a Terminals surface the daemon can't serve.
  let terminals: TerminalManager | undefined;
  if (cfg.terminalEnabled) {
    const ptyModule = await loadPtyModule(log);
    if (ptyModule) {
      terminals = new TerminalManager(ptyModule, { now: () => Date.now(), log });
      log(`[terminal] PTY terminals available (shell: ${process.env.SHELL ?? "/bin/bash"}, cwd: ${process.cwd()})`);
    } else {
      log("[terminal] unavailable — node-pty native module did not load");
    }
  } else {
    log("[terminal] disabled by config (terminal_enabled: false)");
  }

  const handler = createRouter({
    cfg,
    sessions,
    transcripts,
    usage,
    adapter,
    today: todayStr,
    now: () => Date.now(),
    mintSessionId: () => `s_${randomUUID()}`,
    supervisor: { clear: (id) => supervisorRef?.clear(id) },
    connectedDevices: () => {
      const ids = new Set<string>();
      for (const c of gatewayRef?.connections ?? []) if (c.deviceId) ids.add(c.deviceId);
      return ids;
    },
    // Part E: MCP/plugins management edits the SUBSCRIPTION namespace's
    // config (CLAUDE_CONFIG_DIR=~/.claude → ~/.claude/.claude.json +
    // settings.json) — the files the daemon's own sessions read.
    harnessConfig: new HarnessConfigStore(
      join(homedir(), ".claude"),
      join(cfg.stateDir, "mcp-disabled.json"),
      log,
    ),
    skills: new SkillsStore(
      join(cfg.stateDir, "skills-manifest.json"),
      cfg.skillsRegistryDir,
      cfg.skillsTargets,
      log,
    ),
    cron,
    // T1 attachments: staged under the daemon state dir, per session, consumed
    // by the next prompt.submit (sibling of the transcript cache above).
    attachments: new AttachmentStore(join(cfg.stateDir, "attachments")),
    search,
    ...(transcriptionAvailable ? { transcriber } : {}),
    ...(terminals ? { terminals } : {}),
    ntfy,
    disconnectDevice: (deviceId) => {
      for (const c of gatewayRef?.connections ?? []) {
        if (c.deviceId === deviceId) { try { c.ws.close(); } catch { /* gone */ } }
      }
    },
    log,
  });

  // Reconcile orphans from a previous daemon BEFORE accepting connections, so
  // last-run's sessions can't be seen mid-reconcile or flagged as failures.
  const reconciled = sessions.markOrphansExited(Date.now());
  if (reconciled > 0) log(`[marmaladed] reconciled ${reconciled} orphaned session(s) from a prior run`);

  // One-time migration + crash heal for the transcript cache: fold every
  // session's raw delta runs into one consolidated event per message. Runs
  // AFTER markOrphansExited, so a message left `streaming` by the dead process
  // is already `incomplete` and folds with partial:true instead of being
  // skipped. Only sessions with an index row are touched — an orphan NDJSON
  // file has no message statuses to judge it by, so it's left alone. A file
  // that is already compacted costs one read and no write.
  // Search reconcile rides the same pass — AFTER each file is compacted, so the
  // indexer sees whole consolidated messages, never a half-folded run. Only
  // sessions with an index row are visited (an orphan NDJSON has no openable
  // session, and a hit that can't be opened is garbage, not a result), and the
  // watermark comparison makes this a no-op when nothing changed — so a normal
  // restart costs one row read per session, while a deleted search.db rebuilds.
  for (const rec of sessions.list()) {
    try {
      const { streaming, partial } = sessions.messages.unsettledIds(rec.id);
      transcripts.compact(rec.id, { skipMessageIds: streaming, partialMessageIds: partial });
    } catch (e) {
      log(`[transcript] compact ${rec.id} failed: ${(e as Error).message}`);
    }
    try {
      const events = transcripts.replay(rec.id);
      search.reconcile(rec.id, maxEventSeq(events), events);
    } catch (e) {
      log(`[search] reconcile ${rec.id} failed: ${(e as Error).message}`);
    }
  }

  // The ARCHIVE corpus — the pre-daemon ~/.claude/projects history, indexed
  // read-only into the same sidecar (archive-indexer.ts). Deliberately NOT
  // awaited: it is hundreds of files and tens of megabytes, and it is an
  // accessory. The scan yields to the event loop between files, so the gateway
  // accepts connections throughout and a first boot simply has fewer archive
  // hits until it finishes. Failure is logged and dropped for the same reason.
  const archiveDir = process.env.MARMALADE_ARCHIVE_DIR ?? defaultArchiveDir();
  void scanArchive(search, archiveDir, { log })
    .then((s) => {
      if (s.indexed > 0 || s.failed > 0) {
        log(`[archive] scan done — ${s.indexed} indexed, ${s.skipped} unchanged, ${s.empty} empty, ${s.failed} failed`);
      }
    })
    .catch((e: unknown) => log(`[archive] scan failed: ${(e as Error).message}`));

  const gateway = new Gateway(cfg, handler, "0.1.0", log);
  gatewayRef = gateway;
  if (transcriptionAvailable) gateway.extraFeatures.push("transcription");
  if (terminals) gateway.extraFeatures.push("terminal");
  // Token → verified identity (M2): non-loopback connections authenticate
  // against the hashed device-token store.
  gateway.authenticateToken = (token) => sessions.pairing.authenticate(token, Date.now());
  // A closed connection must vanish from every subscriber set (P4).
  gateway.onDisconnect = (conn) => handler.disconnect(conn);
  // A hello with a declared device identity feeds the roster (P3) — a hello
  // without deviceId keeps the "local" default and stays off the roster.
  gateway.onHello = (conn) => {
    if (conn.deviceId) sessions.devices.touch(conn.deviceId, conn.platform ?? "unknown", conn.capabilities, Date.now());
  };
  await gateway.start();

  const supervisor = new Supervisor(sessions, {
    now: () => Date.now(),
    log,
    // The silent-failure alert must reach a real sink, not just a log line.
    // Push lands with the client (M2); for now fan a gateway error event to any
    // connected client so the failure is visible, not silent.
    onSilentFailure: (rec) => {
      const ev = { jsonrpc: "2.0" as const, method: "event" as const, params: { type: "error", payload: { kind: "silent_failure", session_id: rec.id, purpose: rec.purpose }, session_id: rec.id } };
      for (const conn of gateway.connections) {
        try { conn.ws.send(JSON.stringify(ev)); } catch { /* gone */ }
      }
      void ntfy?.publish(
        "Marmalade: silent session failure",
        `session ${rec.id} (${rec.purpose}) stopped heartbeating — marked hung`,
        { priority: 5 },
      );
    },
  });
  supervisorRef = supervisor;
  supervisor.start();

  // Idle reaper (hardening): stop the child of sessions idle past
  // cfg.idleReapMs. A reaped session ends (resumable, same id) and
  // prompt.submit auto-revives it — this is what keeps a client reconnect
  // loop or an exited CLI from stranding harness children forever.
  // Cron fires go through the router's internal submit (origin: cron, same
  // auto-revive path as a client prompt). Started AFTER the gateway is up:
  // start() runs restart-catchup, firing every slot missed while the daemon
  // was down — persist + catchup is the whole point (a restart silently
  // dropping jobs is the silent-failure class this feature exists to kill).
  cron.onFire = (job) => handler.submitCron(job.sessionId, job.prompt);
  await cron.start().catch((e) => log(`[cron] startup catch-up failed: ${(e as Error).message}`));

  // The singleton main session (assistant plan): warm before the first wake
  // word — created on first boot, resumed on every restart. Non-fatal: a
  // failed spawn logs loudly and the next session.main call retries.
  await handler.ensureMain()
    .then((id) => log(`[main-session] warm: ${id}`))
    .catch((e) => log(`[main-session] boot warm-up failed: ${(e as Error).message}`));

  const reaper = setInterval(() => {
    handler.reapIdle().then((ids) => {
      if (ids.length) log(`[reaper] reaped ${ids.length} idle session(s): ${ids.join(", ")}`);
    }).catch((e) => log(`[reaper] pass failed: ${(e as Error).message}`));
  }, 60_000);
  reaper.unref?.();

  console.log(`[marmaladed] gateway listening on ${cfg.gatewayHosts.map((h) => `ws://${h}:${cfg.gatewayPort}`).join(" + ")}`);

  return {
    sessions,
    gateway,
    supervisor,
    async stop() {
      clearInterval(reaper);
      cron.stop();
      supervisor.stop();
      terminals?.stopAll();
      await gateway.stop();
      sessions.close();
      search.close();
    },
  };
}

// Run when invoked directly (systemd ExecStart).
if (import.meta.url === `file://${process.argv[1]}`) {
  // A stray rejection or exception must not silently kill the whole daemon and
  // every session with it (R1). Log and survive; genuinely fatal states still
  // surface in the logs for systemd to act on.
  process.on("unhandledRejection", (reason) => console.error(`[marmaladed] unhandledRejection: ${String(reason)}`));
  process.on("uncaughtException", (err) => console.error(`[marmaladed] uncaughtException: ${err?.stack ?? err}`));

  // A failed bind (e.g. the tailnet interface isn't up yet at boot) must be a
  // visible non-zero exit so systemd Restart=on-failure retries — top-level
  // await would route the rejection through the log-and-survive handler above.
  const daemon = await startDaemon().catch((err) => {
    console.error(`[marmaladed] startup failed: ${err?.stack ?? err}`);
    process.exit(1);
  });
  const shutdown = () => {
    daemon.stop().then(() => process.exit(0)).catch((e) => {
      console.error(`[marmaladed] shutdown error: ${e}`);
      process.exit(1);
    });
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}
