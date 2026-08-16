// cron-cli.ts — `marmalade cron …` subcommands: manage scheduled prompts
// (parity-map T2 #1) from the terminal. This is the migration path for
// OpenClaw crons until the client UIs land: everything routes through the
// daemon's cron.* methods, nothing here owns schedule state.
//
// Pure argument-parsing/formatting lives in this module (unit-tested); the
// WS plumbing is a thin one-shot RPC session in runCronCommand.

export interface CronCliCall {
  (method: string, params: Record<string, unknown>): Promise<any>;
}

/** "30s" | "15m" | "2h" | "1d" → ms. Bare numbers are SECONDS. */
export function parseDuration(raw: string): number {
  const m = /^(\d+(?:\.\d+)?)(s|m|h|d)?$/.exec(raw.trim());
  if (!m) throw new Error(`invalid duration "${raw}" — use e.g. 30s, 15m, 2h, 1d`);
  const n = Number(m[1]);
  const unit = { s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000 }[m[2] ?? "s"]!;
  return Math.round(n * unit);
}

/** Local-time friendly "2026-07-18T09:00" (or anything Date.parse takes) → UTC ms. */
export function parseAt(raw: string): number {
  const ms = Date.parse(raw);
  if (!Number.isFinite(ms)) throw new Error(`invalid time "${raw}" — use e.g. 2026-07-18T09:00`);
  return ms;
}

/** --flag value pairs → map; returns [flags, positionals]. */
export function parseFlags(argv: string[]): [Map<string, string>, string[]] {
  const flags = new Map<string, string>();
  const pos: string[] = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    if (a.startsWith("--")) {
      const eq = a.indexOf("=");
      if (eq > 0) flags.set(a.slice(2, eq), a.slice(eq + 1));
      else {
        const next = argv[i + 1];
        if (next !== undefined && !next.startsWith("--")) { flags.set(a.slice(2), next); i++; }
        else flags.set(a.slice(2), "true");
      }
    } else pos.push(a);
  }
  return [flags, pos];
}

/** Build the wire schedule from the exclusive --cron/--every/--at flags. */
export function scheduleFromFlags(flags: Map<string, string>, nowMs: number): Record<string, unknown> {
  const given = ["cron", "every", "at"].filter((k) => flags.has(k));
  if (given.length !== 1) {
    throw new Error("exactly one of --cron \"<expr>\" | --every <dur> | --at <time> is required");
  }
  if (flags.has("cron")) {
    return {
      kind: "cron", expr: flags.get("cron")!,
      ...(flags.has("tz") ? { tz: flags.get("tz")! } : {}),
      ...(flags.has("stagger") ? { stagger_ms: parseDuration(flags.get("stagger")!) } : {}),
    };
  }
  if (flags.has("every")) {
    return { kind: "every", every_ms: parseDuration(flags.get("every")!), anchor_ms: nowMs };
  }
  return { kind: "at", at_ms: parseAt(flags.get("at")!) };
}

export interface CronJobView {
  job_id: string;
  name: string | null;
  session_id: string;
  prompt: string;
  schedule: Record<string, unknown>;
  enabled: boolean;
  next_run_at: number | null;
  last_run_at: number | null;
  last_status: string | null;
  last_error: string | null;
}

export function describeSchedule(s: Record<string, unknown>): string {
  switch (s.kind) {
    case "cron": return `cron "${s.expr}"${s.tz ? ` (${s.tz})` : ""}`;
    case "every": return `every ${formatMs(Number(s.every_ms))}`;
    case "at": return `once at ${new Date(Number(s.at_ms)).toLocaleString()}`;
    default: return JSON.stringify(s);
  }
}

export function formatMs(ms: number): string {
  if (ms % 86_400_000 === 0) return `${ms / 86_400_000}d`;
  if (ms % 3_600_000 === 0) return `${ms / 3_600_000}h`;
  if (ms % 60_000 === 0) return `${ms / 60_000}m`;
  return `${ms / 1000}s`;
}

/** One job → a compact multi-line block for the terminal. */
export function formatJob(j: CronJobView, nowMs: number): string {
  const state = !j.enabled
    ? "disabled"
    : j.next_run_at
      ? `next in ${formatMs(Math.max(0, Math.round((j.next_run_at - nowMs) / 1000) * 1000))} (${new Date(j.next_run_at).toLocaleString()})`
      : "never (unarmed)";
  const last = j.last_run_at
    ? `last ${j.last_status ?? "?"} at ${new Date(j.last_run_at).toLocaleString()}${j.last_status === "error" && j.last_error ? ` — ${j.last_error}` : ""}`
    : "never ran";
  const lines = [
    `${j.job_id}  ${j.name ?? "(unnamed)"}  [${state}]`,
    `  ${describeSchedule(j.schedule)} → session ${j.session_id}`,
    `  prompt: ${j.prompt.length > 100 ? j.prompt.slice(0, 100) + "…" : j.prompt}`,
    `  ${last}`,
  ];
  if (!j.enabled && j.last_error && j.last_status !== "error") lines.push(`  reason: ${j.last_error}`);
  return lines.join("\n");
}

export const CRON_USAGE = `usage: marmalade cron <command>

  list                          show all jobs (disabled included)
  add --prompt "…" [--session <id>|last] [--name <label>]
      --cron "<expr>" [--tz <IANA>] [--stagger <dur>]
    | --every <dur>             e.g. 15m, 2h, 1d
    | --at "<time>"             one-shot, e.g. 2026-07-18T09:00
  rm <job_id>                   delete a job
  run <job_id>                  fire now (schedule unmoved)
  enable <job_id>               re-arm a disabled job
  disable <job_id>              pause a job

--session defaults to "last" (the most recently active session).`;

/** Execute one cron subcommand against an open RPC session. Returns the exit
 *  code; prints via `out` (injected for tests). */
export async function cronCommand(
  argv: string[],
  call: CronCliCall,
  out: (line: string) => void,
  nowMs: number = Date.now(),
): Promise<number> {
  const [flags, pos] = parseFlags(argv);
  const sub = pos[0];

  const resolveSession = async (): Promise<string> => {
    const want = flags.get("session") ?? "last";
    if (want !== "last") return want;
    const { sessions } = (await call("session.list", {})) as { sessions: { session_id: string; last_active: number }[] };
    if (!sessions.length) throw new Error("no sessions exist — open one first (marmalade), or pass --session <id>");
    return sessions[0]!.session_id; // session.list is ordered by last_active DESC
  };

  switch (sub) {
    case "list": {
      const { jobs } = (await call("cron.list", {})) as { jobs: CronJobView[] };
      if (!jobs.length) { out("no cron jobs. add one: marmalade cron add --prompt \"…\" --every 1h"); return 0; }
      for (const j of jobs) out(formatJob(j, nowMs));
      return 0;
    }
    case "add": {
      const prompt = flags.get("prompt");
      if (!prompt) throw new Error("--prompt is required");
      const schedule = scheduleFromFlags(flags, nowMs);
      const session_id = await resolveSession();
      const { job } = (await call("cron.create", {
        session_id, prompt, schedule,
        ...(flags.has("name") ? { name: flags.get("name")! } : {}),
      })) as { job: CronJobView };
      out(`created ${job.job_id}`);
      out(formatJob(job, nowMs));
      return 0;
    }
    case "rm": {
      if (!pos[1]) throw new Error("usage: marmalade cron rm <job_id>");
      const { deleted } = (await call("cron.delete", { job_id: pos[1] })) as { deleted: boolean };
      out(deleted ? `deleted ${pos[1]}` : `no such job ${pos[1]}`);
      return deleted ? 0 : 1;
    }
    case "run": {
      if (!pos[1]) throw new Error("usage: marmalade cron run <job_id>");
      const { fired } = (await call("cron.run_now", { job_id: pos[1] })) as { fired: boolean };
      out(fired ? `fired ${pos[1]}` : `${pos[1]} is mid-run — skipped (single-flight)`);
      return 0;
    }
    case "enable":
    case "disable": {
      if (!pos[1]) throw new Error(`usage: marmalade cron ${sub} <job_id>`);
      const { job } = (await call("cron.update", { job_id: pos[1], enabled: sub === "enable" })) as { job: CronJobView };
      out(formatJob(job, nowMs));
      return 0;
    }
    default:
      out(CRON_USAGE);
      return sub === undefined || sub === "help" ? 0 : 1;
  }
}
