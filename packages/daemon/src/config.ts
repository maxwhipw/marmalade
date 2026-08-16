// Daemon configuration + well-known paths.
// v0.1 is single-host, single-principal (owner). Paths are derived, not stored.
//
// Config sources, in precedence order (highest wins):
//   1. environment (MARMALADE_*) — the original knobs, all still honored
//   2. ~/.marmalade/daemon/config.json — the config FILE, which supersedes
//      the earlier env-only design where environment was the global half
//   3. built-in defaults
// The file is STRICT-validated: an unknown key or malformed value fails
// startup loudly (visible non-zero exit for systemd) — a typo'd key that is
// silently ignored is the silent-failure class this daemon is paranoid about.

import { homedir } from "node:os";
import { readFileSync, writeFileSync, renameSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { z } from "zod";
import type { BudgetConfig } from "./usage.js";
import type { NtfyConfig } from "./ntfy.js";
import type { KeyringConfig } from "./keyring.js";

/** The SDK's reasoning-effort levels (claude-agent-sdk EffortLevel). The wire
 *  keeps reasoning_effort as a plain string for compat; the daemon validates
 *  against this list at the config/create seams so a typo fails loudly
 *  instead of being silently dropped by the SDK. */
export const EFFORT_LEVELS = ["low", "medium", "high", "xhigh", "max"] as const;
export type EffortLevel = (typeof EFFORT_LEVELS)[number];

/** Per-model reasoning-effort bounds (2026-07-27). Optional floor/ceiling for
 *  a given model id: Opus is never run below "high", Fable never above
 *  "medium". The daemon CLAMPS rather than rejects — switching models must
 *  never fail a create, it just lands on the nearest allowed level, and the
 *  clamped value is what gets stamped on the row and returned on the wire. */
export interface EffortBounds {
  min?: EffortLevel;
  max?: EffortLevel;
}

/** Clamp `effort` into `bounds` using EFFORT_LEVELS order (cheapest → deepest).
 *  No bounds (or no relevant edge) = unchanged. */
export function clampEffort(effort: EffortLevel, bounds?: EffortBounds): EffortLevel {
  if (!bounds) return effort;
  const order = EFFORT_LEVELS as readonly string[];
  let i = order.indexOf(effort);
  if (i < 0) return effort; // not one of ours — callers validate before clamping
  if (bounds.min !== undefined) i = Math.max(i, order.indexOf(bounds.min));
  if (bounds.max !== undefined) i = Math.min(i, order.indexOf(bounds.max));
  return EFFORT_LEVELS[i];
}

/** One `model_efforts` entry. Strict, and refined so a nonsense bound fails at
 *  the config/RPC seam instead of silently clamping everything to one level:
 *  at least one of min/max must be present, and min must not exceed max. */
export const EffortBoundsSchema = z.object({
  min: z.enum(EFFORT_LEVELS).optional(),
  max: z.enum(EFFORT_LEVELS).optional(),
}).strict().superRefine((v, ctx) => {
  if (v.min === undefined && v.max === undefined) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, message: "needs at least one of min/max" });
    return;
  }
  if (v.min !== undefined && v.max !== undefined
      && EFFORT_LEVELS.indexOf(v.min) > EFFORT_LEVELS.indexOf(v.max)) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: `min "${v.min}" is deeper than max "${v.max}"`,
    });
  }
});

export interface DaemonConfig {
  /** WS gateway binds. Loopback is ALWAYS bound (the CLI and pairing.start
   *  need a trusted same-box path); MARMALADE_BIND_HOST adds a tailnet
   *  listener for remote token-auth devices — a dual-bind, because a same-box
   *  connection to the tailnet IP arrives non-loopback (empirically: source
   *  IP = the tailnet IP), so a single tailnet bind would lose the loopback
   *  trust boundary. MCP servers are always localhost-only (sec-H3). */
  gatewayHosts: string[];
  gatewayPort: number;
  /** Where the daemon keeps its own state (SQLite index, transcript cache). */
  stateDir: string;
  /** Root under which per-authClass CLAUDE_CONFIG_DIR namespaces live
   *  (sec-H1). The subscription context uses the real ~/.claude; metered/local
   *  get dedicated dirs with NO subscription OAuth reachable. */
  authContextRoot: string;
  /** The canonical marmalade behavior spec (identity/state-upkeep/self-improve)
   *  rendered into the main-session system prompt (M4a). */
  behaviorDir: string;
  /** OPTIONAL user behavior addendum (2026-07-27). User-owned prose appended to
   *  the main-session system prompt as a trailing "## User additions" section,
   *  so behaviors can be added WITHOUT editing the locked core spec. Lives
   *  outside the daemon state dir on purpose — it's the user's file, not
   *  daemon state. Missing/empty = the prompt is exactly what it was. */
  userBehaviorPath: string;
  /** The user's notes-wiki root — source of the rollup state reads (M4a). */
  wikiRoot: string;
  /** The agent-skills registry (M7/Part A): skill dirs containing SKILL.md. */
  skillsRegistryDir: string;
  /** Harness skill dirs the manifest reconciles symlinks into (Part A). */
  skillsTargets: { harness: string; skillsDir: string }[];
  /** Global tool-use approvals default (M2): "auto" approves-with-log (the
   *  operator runs their own daemon on bypass); "prompt" parks tool calls behind
   *  approval.request. Per-session override via session.create/approvals. */
  approvalsMode: "auto" | "prompt";
  /** Reaping (hardening): cap on concurrently LIVE sessions — each live
   *  session is a full harness child process. Hitting the cap evicts the
   *  longest-idle idle session; when every slot is busy, session.create
   *  fails visibly instead of silently forking a process herd. */
  maxLiveSessions: number;
  /** How long a session may sit runState=idle before the reaper stops its
   *  child. Reaping ends the session (resumable, SAME id); prompt.submit
   *  auto-revives it, so clients never notice beyond first-turn latency.
   *  Default sits just past Claude's 1-HOUR prompt-cache TTL (by design):
   *  inside the window a follow-up turn rides the warm cache, so keeping
   *  the child is worth it; past it the next turn is a cache miss anyway
   *  and reaping costs nothing extra (decision 2026-07-17). */
  idleReapMs: number;
  /** Daily spend guardrail (T2 #8). Applies to UNATTENDED turns only: an
   *  over-budget day blocks cron fires (recorded as job errors) and flags
   *  usage.summary; a user-typed prompt is never refused. Absent = no limit. */
  budget?: BudgetConfig;
  /** ntfy SECONDARY alert path (hardening #2) — the client's always-on WS is
   *  the primary mobile mechanism. Absent (no topic configured) = off. */
  ntfy?: NtfyConfig;
  /** STT argv for audio.transcribe ({file}/{dir} placeholders). Absent = the
   *  default faster-whisper command (whisper-ctranslate2, transcription.ts); the
   *  feature is only advertised when the command's binary actually resolves. */
  transcribeCommand?: string[];
  /** Context-pressure reminder threshold (percent of the model's window).
   *  When a session's reported context_percent crosses this, the NEXT prompt
   *  gets a one-shot harness-only preamble nudging the agent to persist
   *  durable state (memory/handoff) at its next natural stopping point —
   *  advisory only, never an auto-clear. Re-arms when the percent drops back
   *  below the threshold (compaction/clear). 0 disables. */
  contextReminderPercent: number;
  /** New-session defaults the daemon OWNS and DECLARES (2026-07-23). When a
   *  session.create arrives without a model / reasoning_effort, these are
   *  stamped on the row and passed to the harness — so clients see the REAL
   *  model/effort on session rows instead of an opaque "Default" (the SDK's
   *  own default is invisible until a turn completes). Absent = current
   *  behavior (defer to the harness). Declared to clients via model.list's
   *  default_model / default_effort so pickers can label the default
   *  pre-create. */
  defaultModel?: string;
  /** Model for the one-shot title/summary seed fired after a session's first
   *  turn (session-namer.ts). Cheap by design — it labels an exchange, it does
   *  not do the work. Absent = DEFAULT_NAMING_MODEL (Haiku). Set to an empty
   *  string via MARMALADE_NAMING_MODEL to disable seeding entirely. */
  namingModel?: string;
  defaultEffort?: EffortLevel;
  /** Per-model reasoning-effort bounds, keyed by harness model id (2026-07-27).
   *  File-only (no env pin, not in envLockedSettings) — it's a small map, and
   *  clients edit it through settings.update. Absent/empty = no model is
   *  bounded. Bounds may name ids the current harness catalog doesn't list
   *  (the catalog changes under us); those entries simply never match. */
  modelEfforts?: Record<string, EffortBounds>;
  /** PTY terminals (terminal.*). A terminal is an
   *  arbitrary shell as the daemon's user — not a new trust boundary (the
   *  agent already runs commands for any paired device) but it bypasses the
   *  harness approval layer, hence this kill-switch. Default on; the feature
   *  is only ADVERTISED when node-pty also loads (host-conditional). */
  terminalEnabled: boolean;
  /** Secret broker (keyring.ts): the command marmalade shells out to for a
   *  credential it must never store itself — the metered ANTHROPIC_API_KEY
   *  (policy 5.3) today, assistant-service credentials (IMAP, CalDAV) next.
   *  Absent = no keyring configured; every consumer degrades to "no secret
   *  available" rather than inventing one. */
  keyring?: KeyringConfig;
}

/** The config-file schema (~/.marmalade/daemon/config.json). Every key
 *  optional; .strict() so a typo'd key fails startup instead of being
 *  silently ignored. snake_case matches the wire convention. */
export const ConfigFileSchema = z.object({
  /** Extra (non-loopback) bind host, e.g. the tailnet IP. Same semantics as
   *  MARMALADE_BIND_HOST (loopback is always bound regardless). */
  bind_host: z.string().min(1).optional(),
  bind_port: z.number().int().min(1).max(65535).optional(),
  approvals_mode: z.enum(["auto", "prompt"]).optional(),
  max_live_sessions: z.number().int().min(1).optional(),
  idle_reap_ms: z.number().int().min(60_000).optional(),
  budget: z.object({
    metric: z.enum(["usd", "tokens"]),
    daily_limit: z.number().positive(),
  }).optional(),
  ntfy: z.object({
    server: z.string().url().optional(),
    topic: z.string().min(1),
    token: z.string().min(1).optional(),
  }).strict().optional(),
  /** STT argv for audio.transcribe, e.g. ["whisper-ctranslate2", "{file}", ...]. */
  transcribe_command: z.array(z.string().min(1)).min(1).optional(),
  /** Context-pressure reminder threshold (percent, 0 disables). */
  context_reminder_percent: z.number().int().min(0).max(100).optional(),
  /** PTY terminals kill-switch (terminal.* methods + "terminal" feature). */
  terminal_enabled: z.boolean().optional(),
  /** New-session defaults (daemon-owned): stamped on model-less /
   *  effort-less session.create rows and declared via model.list. */
  default_model: z.string().min(1).optional(),
  /** Naming-model override for the title/summary seed. "" disables seeding. */
  naming_model: z.string().optional(),
  default_effort: z.enum(EFFORT_LEVELS).optional(),
  /** Per-model effort bounds: { "claude-opus-5": { min: "high" }, ... }.
   *  The daemon clamps session effort into these at create/session.effort. */
  model_efforts: z.record(z.string().min(1), EffortBoundsSchema).optional(),
  /** Secret broker. `command` is an argv with a {entry} placeholder (default
   *  gopass); `insert_command` is the WRITE side (secret-entry flow — the
   *  value goes on the child's stdin, never in the argv); `list_command`
   *  prints one entry name per line and `remove_command` deletes one entry
   *  (both drive `marmalade secret`); `passphrase_file` feeds
   *  GOPASS_AGE_PASSWORD so gopass's age backend runs non-interactively;
   *  `metered_key_entry` names the entry holding the metered
   *  ANTHROPIC_API_KEY (policy 5.3). */
  keyring: z.object({
    command: z.array(z.string().min(1)).min(1).optional(),
    insert_command: z.array(z.string().min(1)).min(1).optional(),
    list_command: z.array(z.string().min(1)).min(1).optional(),
    remove_command: z.array(z.string().min(1)).min(1).optional(),
    passphrase_file: z.string().min(1).optional(),
    metered_key_entry: z.string().min(1).optional(),
  }).strict().optional(),
}).strict();
export type ConfigFile = z.infer<typeof ConfigFileSchema>;

export function defaultConfigPath(): string {
  return process.env.MARMALADE_CONFIG ?? join(homedir(), ".marmalade", "daemon", "config.json");
}

/** Read + strict-validate the config file. Missing file → {} (all defaults);
 *  malformed JSON or an invalid/unknown key → throw with a message naming the
 *  file and the problem (startup fails visibly; systemd surfaces it). */
export function loadConfigFile(path: string = defaultConfigPath()): ConfigFile {
  let raw: string;
  try {
    raw = readFileSync(path, "utf8");
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === "ENOENT") return {};
    throw new Error(`config file ${path} unreadable: ${(e as Error).message}`);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    throw new Error(`config file ${path} is not valid JSON: ${(e as Error).message}`);
  }
  const result = ConfigFileSchema.safeParse(parsed);
  if (!result.success) {
    const issues = result.error.issues
      .map((i) => `${i.path.join(".") || "(root)"}: ${i.message}`)
      .join("; ");
    throw new Error(`config file ${path} invalid — ${issues}`);
  }
  return result.data;
}

/** Merge `patch` into the config FILE and write it back (settings.update's
 *  persistence half, 2026-07-25). An undefined value in the patch leaves the
 *  key alone; an explicit `undefined` can't be expressed in JSON, so callers
 *  clear a key by passing `undefined` here after deleting it from the merged
 *  object — see the `delete` in the caller. The merged object is re-validated
 *  through the STRICT schema before it touches disk, so a bad write fails the
 *  RPC instead of poisoning the next daemon start. Write is atomic (tmp file
 *  + rename) so a crash mid-write can't leave a truncated config behind.
 *  Returns the config file as written. */
export function writeConfigFile(
  patch: Partial<ConfigFile>,
  path: string | undefined = defaultConfigPath(),
): ConfigFile {
  path ??= defaultConfigPath();
  const current = loadConfigFile(path);
  const merged: Record<string, unknown> = { ...current };
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined) delete merged[key];
    else merged[key] = value;
  }
  const validated = ConfigFileSchema.parse(merged);
  mkdirSync(dirname(path), { recursive: true });
  const tmp = `${path}.tmp`;
  writeFileSync(tmp, `${JSON.stringify(validated, null, 2)}\n`, "utf8");
  renameSync(tmp, path);
  return validated;
}

/** Which settings keys are pinned by an environment variable. env outranks
 *  the file, so writing one of these would persist a value the daemon then
 *  ignores — settings.update rejects them and clients disable the control. */
export function envLockedSettings(env: NodeJS.ProcessEnv = process.env): string[] {
  const locked: string[] = [];
  if (env.MARMALADE_DEFAULT_MODEL) locked.push("default_model");
  if (env.MARMALADE_DEFAULT_EFFORT) locked.push("default_effort");
  return locked;
}

/** Build the daemon config. `file` is passed explicitly by index.ts
 *  (loadConfigFile()); defaulting to {} keeps tests hermetic — a bare
 *  defaultConfig() never reads the developer machine's real config file. */
export function defaultConfig(file: ConfigFile = {}): DaemonConfig {
  const home = homedir();
  // repo root = up from packages/daemon/dist/config.js
  const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
  // Precedence per knob: env > config file > default.
  const bindHost = process.env.MARMALADE_BIND_HOST ?? file.bind_host;
  const approvals = process.env.MARMALADE_APPROVALS ?? file.approvals_mode;
  // ntfy is ON iff a topic resolves (env or file) — no topic, no feature.
  const ntfyTopic = process.env.MARMALADE_NTFY_TOPIC ?? file.ntfy?.topic;
  const ntfyToken = process.env.MARMALADE_NTFY_TOKEN ?? file.ntfy?.token;
  const defaultModel = process.env.MARMALADE_DEFAULT_MODEL ?? file.default_model;
  const namingModel = process.env.MARMALADE_NAMING_MODEL ?? file.naming_model;
  const effortRaw = process.env.MARMALADE_DEFAULT_EFFORT ?? file.default_effort;
  if (effortRaw !== undefined && !(EFFORT_LEVELS as readonly string[]).includes(effortRaw)) {
    throw new Error(`default_effort "${effortRaw}" is not one of ${EFFORT_LEVELS.join("/")}`);
  }
  const defaultEffort = effortRaw as EffortLevel | undefined;
  return {
    // Loopback always; bind_host / MARMALADE_BIND_HOST adds a second listener
    // on a tailnet interface (gateway.ts refuses anything that isn't loopback
    // or 100.64.0.0/10 — non-loopback connections require a paired device
    // token, M2).
    gatewayHosts: bindHost && bindHost !== "127.0.0.1"
      ? ["127.0.0.1", bindHost]
      : ["127.0.0.1"],
    gatewayPort: Number(process.env.MARMALADE_BIND_PORT ?? file.bind_port ?? 9130), // chosen to avoid colliding with common local services
    stateDir: join(home, ".marmalade", "daemon"),
    authContextRoot: join(home, ".marmalade", "auth-contexts"),
    behaviorDir: process.env.MARMALADE_BEHAVIOR_DIR ?? join(repoRoot, "behavior"),
    userBehaviorPath: process.env.MARMALADE_USER_BEHAVIOR ?? join(home, ".marmalade", "behavior.md"),
    wikiRoot: process.env.MARMALADE_WIKI_ROOT ?? join(home, ".marmalade", "wiki"),
    skillsRegistryDir: process.env.MARMALADE_SKILLS_REGISTRY ?? join(home, ".marmalade", "skills"),
    // v1: Claude Code's user-level skills dir. Per-authClass namespaces
    // (policy.ts) matter for MCP/plugins (Part E), not skills — the main
    // (subscription) context reads the real ~/.claude.
    skillsTargets: [{ harness: "claude-code", skillsDir: join(home, ".claude", "skills") }],
    // Anything other than "prompt" means auto.
    approvalsMode: approvals === "prompt" ? "prompt" : "auto",
    maxLiveSessions: Number(process.env.MARMALADE_MAX_LIVE_SESSIONS ?? file.max_live_sessions ?? 8),
    idleReapMs: Number(process.env.MARMALADE_IDLE_REAP_MS ?? file.idle_reap_ms ?? 65 * 60_000),
    ...(file.budget ? { budget: { metric: file.budget.metric, dailyLimit: file.budget.daily_limit } } : {}),
    ...(ntfyTopic ? {
      ntfy: {
        server: process.env.MARMALADE_NTFY_SERVER ?? file.ntfy?.server ?? "https://ntfy.sh",
        topic: ntfyTopic,
        ...(ntfyToken ? { token: ntfyToken } : {}),
      },
    } : {}),
    ...(file.transcribe_command ? { transcribeCommand: file.transcribe_command } : {}),
    contextReminderPercent: Number(
      process.env.MARMALADE_CONTEXT_REMINDER_PERCENT ?? file.context_reminder_percent ?? 75,
    ),
    ...(defaultModel ? { defaultModel } : {}),
    ...(namingModel !== undefined ? { namingModel } : {}),
    ...(defaultEffort ? { defaultEffort } : {}),
    // File-only by design: bounds are a policy map, not a single knob, and an
    // env var for them would be unreadable and unwritable from a client.
    ...(file.model_efforts ? { modelEfforts: file.model_efforts } : {}),
    // File-only, same reasoning as model_efforts: a small structured object,
    // not a single knob — an env var for it would be unreadable and
    // unwritable from a client. Secret VALUES never live here either way;
    // this is only where to go ask for them.
    ...(file.keyring ? {
      keyring: {
        ...(file.keyring.command ? { command: file.keyring.command } : {}),
        ...(file.keyring.insert_command ? { insertCommand: file.keyring.insert_command } : {}),
        ...(file.keyring.list_command ? { listCommand: file.keyring.list_command } : {}),
        ...(file.keyring.remove_command ? { removeCommand: file.keyring.remove_command } : {}),
        ...(file.keyring.passphrase_file ? { passphraseFile: file.keyring.passphrase_file } : {}),
        ...(file.keyring.metered_key_entry ? { meteredKeyEntry: file.keyring.metered_key_entry } : {}),
      },
    } : {}),
    // Env override accepts 0/false to disable (precedence: env > file > on).
    terminalEnabled: process.env.MARMALADE_TERMINAL_ENABLED !== undefined
      ? !["0", "false"].includes(process.env.MARMALADE_TERMINAL_ENABLED)
      : file.terminal_enabled ?? true,
  };
}
