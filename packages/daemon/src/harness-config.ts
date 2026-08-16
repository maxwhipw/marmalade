// harness-config.ts — mcp.list/toggle + plugins.list/toggle over the
// HARNESS's own configuration (fork-rest-triage Part E). The daemon has no
// MCP/plugin concept of its own; managing them means editing what Claude Code
// reads at spawn. Enable/disable ONLY in v1 — no add/remove/edit of server
// definitions over the wire (bigger security surface, designed separately).
//
// Ground truth (verified on-machine 2026-07-12):
//   - MCP servers (user scope): `mcpServers` in $CLAUDE_CONFIG_DIR/.claude.json.
//     marmaladed spawns children with CLAUDE_CONFIG_DIR=~/.claude (subscription
//     authClass, policy.ts), so THE file its sessions read is
//     ~/.claude/.claude.json — NOT ~/.claude.json (the interactive CLI's default,
//     a different file). Claude Code has no user-scope disabled flag, so
//     disable = move the definition into a marmalade-owned stash file next to
//     sessions.db; enable = move it back. Definitions are never lost.
//   - Plugins: `enabledPlugins` ("name@marketplace" → bool) in
//     $CLAUDE_CONFIG_DIR/settings.json — a native toggle; inventory in
//     $CLAUDE_CONFIG_DIR/plugins/installed_plugins.json.
//
// Caveat (documented, accepted for v1): Claude Code itself rewrites these
// files while running; a toggle racing an interactive session's write can be
// lost. Toggles take effect on the NEXT session spawn either way — the result
// carries {effective: "next_session"} so clients can message it honestly.

import { readFileSync, renameSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";

export interface McpServerRow {
  name: string;
  transport: string;
  enabled: boolean;
  harness: string;
  /** stdio launch line ("command arg1 arg2"). env is NEVER forwarded — it can
   *  hold API keys; args are shown verbatim (the operator's own device + config). */
  command?: string;
  /** http/sse endpoint URL. */
  url?: string;
}

export interface PluginRow {
  name: string;
  enabled: boolean;
  harness: string;
  /** Marketplace the plugin came from (the part after "@" in [name]). */
  source?: string;
  version?: string;
  description?: string;
}

/** Display endpoint for an MCP definition: the http/sse url and/or the stdio
 *  launch line. env is deliberately omitted (secret channel). */
function mcpEndpoint(def: Record<string, unknown> | undefined): Pick<McpServerRow, "command" | "url"> {
  if (!def) return {};
  const url = typeof def.url === "string" ? def.url : undefined;
  const cmd = typeof def.command === "string" ? def.command : undefined;
  const args = Array.isArray(def.args) ? def.args.filter((a): a is string => typeof a === "string") : [];
  const command = cmd ? [cmd, ...args].join(" ") : undefined;
  return { ...(url ? { url } : {}), ...(command ? { command } : {}) };
}

/** Version + description for an installed plugin. Version comes from the
 *  install record; description from the plugin manifest
 *  (`<installPath>/.claude-plugin/plugin.json`). Best-effort — a missing or
 *  unreadable manifest just yields undefined, never throws. */
function pluginMeta(entry: unknown): { version?: string; description?: string } {
  const rec = (Array.isArray(entry) ? entry[0] : entry) as Record<string, unknown> | undefined;
  if (!rec || typeof rec !== "object") return {};
  const clean = (v: unknown): string | undefined =>
    typeof v === "string" && v.trim() && v !== "unknown" ? v : undefined;
  let version = clean(rec.version);
  let description: string | undefined;
  const installPath = typeof rec.installPath === "string" ? rec.installPath : undefined;
  if (installPath) {
    const manifest = readJson(join(installPath, ".claude-plugin", "plugin.json"));
    description = clean(manifest.description)?.slice(0, 300);
    version = version ?? clean(manifest.version);
  }
  return { ...(version ? { version } : {}), ...(description ? { description } : {}) };
}

const HARNESS = "claude-code";

function readJson(path: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(readFileSync(path, "utf8"));
    return parsed && typeof parsed === "object" ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

/** tmp + rename so a crash mid-write never truncates a harness config file. */
function writeJsonAtomic(path: string, value: unknown): void {
  mkdirSync(dirname(path), { recursive: true });
  const tmp = `${path}.marmalade-tmp`;
  writeFileSync(tmp, JSON.stringify(value, null, 2));
  renameSync(tmp, path);
}

export class HarnessConfigStore {
  /** The Claude state file holding user-scope mcpServers. */
  private readonly claudeJsonPath: string;
  private readonly settingsPath: string;
  private readonly installedPluginsPath: string;

  constructor(
    claudeConfigDir: string,
    /** Marmalade-owned stash for disabled MCP definitions. */
    private stashPath: string,
    private log: (line: string) => void = () => {},
  ) {
    this.claudeJsonPath = join(claudeConfigDir, ".claude.json");
    this.settingsPath = join(claudeConfigDir, "settings.json");
    this.installedPluginsPath = join(claudeConfigDir, "plugins", "installed_plugins.json");
  }

  // ── MCP ───────────────────────────────────────────────────────────────────

  private readMcpServers(): Record<string, Record<string, unknown>> {
    return (readJson(this.claudeJsonPath).mcpServers ?? {}) as Record<string, Record<string, unknown>>;
  }

  private readStash(): Record<string, Record<string, unknown>> {
    return (readJson(this.stashPath)[HARNESS] ?? {}) as Record<string, Record<string, unknown>>;
  }

  listMcp(): McpServerRow[] {
    const active = this.readMcpServers();
    const stashed = this.readStash();
    const rows: McpServerRow[] = [];
    for (const [name, def] of Object.entries(active)) {
      rows.push({ name, transport: String(def?.type ?? "stdio"), enabled: true, harness: HARNESS, ...mcpEndpoint(def) });
    }
    for (const [name, def] of Object.entries(stashed)) {
      if (name in active) continue; // active wins if both somehow hold it
      rows.push({ name, transport: String(def?.type ?? "stdio"), enabled: false, harness: HARNESS, ...mcpEndpoint(def) });
    }
    return rows.sort((a, b) => a.name.localeCompare(b.name));
  }

  toggleMcp(name: string, enabled: boolean): { applied: boolean; effective: "next_session" } {
    const claudeJson = readJson(this.claudeJsonPath);
    const servers = (claudeJson.mcpServers ?? {}) as Record<string, unknown>;
    const stashFile = readJson(this.stashPath);
    const stash = (stashFile[HARNESS] ?? {}) as Record<string, unknown>;

    if (enabled) {
      if (name in servers) return { applied: true, effective: "next_session" }; // already on
      if (!(name in stash)) throw new Error(`unknown MCP server: ${name}`);
      servers[name] = stash[name];
      delete stash[name];
    } else {
      if (!(name in servers)) {
        if (name in stash) return { applied: true, effective: "next_session" }; // already off
        throw new Error(`unknown MCP server: ${name}`);
      }
      stash[name] = servers[name];
      delete servers[name];
    }
    // Persist the stash FIRST: if the second write fails, a definition may be
    // in both places (list resolves that; nothing is lost) — never in neither.
    stashFile[HARNESS] = stash;
    writeJsonAtomic(this.stashPath, stashFile);
    claudeJson.mcpServers = servers;
    writeJsonAtomic(this.claudeJsonPath, claudeJson);
    this.log(`[harness-config] mcp "${name}" ${enabled ? "enabled" : "disabled"}`);
    return { applied: true, effective: "next_session" };
  }

  // ── Plugins ───────────────────────────────────────────────────────────────

  listPlugins(): PluginRow[] {
    const enabledMap = (readJson(this.settingsPath).enabledPlugins ?? {}) as Record<string, unknown>;
    const installed = (readJson(this.installedPluginsPath).plugins ?? {}) as Record<string, unknown>;
    const names = new Set([...Object.keys(installed), ...Object.keys(enabledMap)]);
    return [...names]
      .sort((a, b) => a.localeCompare(b))
      .map((name) => {
        // Plugin keys are "name@marketplace" — the suffix is the source.
        const source = name.includes("@") ? name.slice(name.lastIndexOf("@") + 1) : undefined;
        const meta = pluginMeta(installed[name]);
        return {
          name,
          enabled: enabledMap[name] === true,
          harness: HARNESS,
          ...(source ? { source } : {}),
          ...(meta.version ? { version: meta.version } : {}),
          ...(meta.description ? { description: meta.description } : {}),
        };
      });
  }

  togglePlugin(name: string, enabled: boolean): { applied: boolean; effective: "next_session" } {
    const installed = (readJson(this.installedPluginsPath).plugins ?? {}) as Record<string, unknown>;
    const settings = readJson(this.settingsPath);
    const enabledMap = (settings.enabledPlugins ?? {}) as Record<string, unknown>;
    if (!(name in installed) && !(name in enabledMap)) throw new Error(`unknown plugin: ${name}`);
    enabledMap[name] = enabled;
    settings.enabledPlugins = enabledMap;
    writeJsonAtomic(this.settingsPath, settings);
    this.log(`[harness-config] plugin "${name}" ${enabled ? "enabled" : "disabled"}`);
    return { applied: true, effective: "next_session" };
  }
}
