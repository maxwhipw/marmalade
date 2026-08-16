// adapter.ts — the thin, versioned harness seam (Decision 4).
// Budget: 1–2 breaking changes per adapter per year. Two CONCRETE adapters
// live behind this in v0.1 (Claude Code now, OpenCode at M3); a generic
// AcpAdapter is extracted only on the rule of three (simp-H2).

import type { JsonRpcEvent } from "@marmalade/protocol";
import type { SessionSpec } from "./policy.js";
import type { ResultInfo } from "./normalize.js";

export interface SpawnOptions {
  /** The daemon's own session id — tagged onto every normalized event. */
  daemonSessionId: string;
  /** Resume an existing harness session (cwd-sensitive for Claude Code). */
  resumeHarnessSessionId?: string;
  /** Rewind-resume (session.undo): resume AT this harness message uuid — the
   *  harness rewinds its context to that point in place, non-destructively
   *  (the popped tail stays off-chain in the harness's own store). Only
   *  meaningful with resumeHarnessSessionId; only passed when the adapter
   *  advertises supportsResumeAt. */
  resumeAtHarnessUuid?: string;
  /** Custom system prompt (main session) or undefined (harness default). */
  systemPrompt?: string;
  /** First-message context — e.g. the state-read preload (M4a). */
  firstMessageContext?: string;
  model?: string;
  /** Reasoning-effort level (SDK EffortLevel: low|medium|high|xhigh|max).
   *  Validated at the config/create seams; adapters without an effort seam
   *  ignore it. */
  effort?: string;
}

export interface AdapterCallbacks {
  /** A normalized gateway event was produced. */
  onEvent(event: JsonRpcEvent): void;
  /** The SDK reported its own session id (persist for resume). */
  onHarnessSession(harnessSessionId: string): void;
  /** The harness reported its own uuid for the current assistant message —
   *  captured PRIVATELY (P1: harness ids never cross the gateway). Optional:
   *  a harness with no per-message ids (ACP today) simply never calls it. */
  onHarnessMessageUuid?(uuid: string): void;
  /** A turn finished — feeds the usage meter + completion. */
  onResult(result: ResultInfo, apiKeySource: string | undefined): void;
  /** Any stream activity — the supervisor uses this as a heartbeat. */
  onActivity(): void;
  /** A terminal failure (spawn/init failed, child died, stream errored). The
   *  router turns this into a client-visible error + terminal status, so a
   *  failure is never eternal silence (the OpenClaw wound). */
  onError?(kind: string, message: string): void;
  /** The agent updated its own session summary (via the marmalade MCP tool).
   *  Optional — an adapter that can't expose the tool simply omits it. */
  onSummaryUpdate?(s: { topic?: string; summary: string }): void;
  /** The live device roster (P3) — backs the `list_devices` MCP tool so the
   *  agent can target "my phone" vs "my desktop". Optional: an adapter that
   *  can't expose tools simply never calls it. */
  listDevices?(): DeviceInfo[];
  /** Cross-session tools (assistant plan 2026-07-19) — backs the marmalade
   *  MCP session toolset (list_sessions / get_session_turns / send_to_session
   *  / steer_session / interrupt_session / watch_session). The router
   *  supplies it (it owns the live map, identity, and the loop guard); an
   *  adapter that can't expose tools simply never calls it. Methods THROW
   *  Error with a human message on refusal (loop guard, unknown target) —
   *  the tool handler returns the message as the tool result. */
  sessionTools?: SessionToolsApi;
  /** Tool-use approval (M2): BOTH adapters route through this one seam. The
   *  router supplies it (it owns subscribers, the pending map, emit, and
   *  runState); adapters stay mode-ignorant — in "auto" mode the router-side
   *  callback auto-allows-with-log. Optional: absent = allow everything
   *  (M1 behavior, tests). */
  requestApproval?(info: ApprovalInfo): Promise<ApprovalDecision>;
  /** Agent question (clarify): the harness wants the USER to answer a
   *  structured multiple-choice question (Claude Code's AskUserQuestion).
   *  Unlike approvals this is never mode-gated — a question always parks
   *  while subscribers exist. The router supplies it; an unanswerable
   *  question (headless, dismissed, disconnected) resolves answered:false
   *  with a message telling the agent to proceed on its own judgment.
   *  Optional: absent = the adapter passes the tool through untouched. */
  requestClarify?(questions: ClarifyQuestion[]): Promise<ClarifyDecision>;
}

/** What an adapter knows about a pending tool call (M2 approvals). */
export interface ApprovalInfo {
  toolName: string;
  /** The tool input as the harness reported it (shape is harness-specific). */
  input: unknown;
  /** Optional harness-provided human description of the call. */
  description?: string;
}

/** The router's verdict for one tool call.
 *
 *  [choice] is the answer the user actually gave — `once` / `session` / `always` /
 *  `deny` — and is present ONLY when a human was genuinely asked. Auto-approve
 *  (mode=auto), a session pattern already allowlisted, and the unattended
 *  fallback all resolve without it, which is the distinction the transcript
 *  record depends on: "the user allowed this" and "nobody was asked" must not look
 *  the same in scrollback. */
export type ApprovalDecision =
  | { behavior: "allow"; choice?: string }
  | { behavior: "deny"; message: string; choice?: string };

/** One structured agent question — a harness-agnostic mirror of Claude
 *  Code's AskUserQuestion input (1–4 questions, 2–4 options each). */
export interface ClarifyQuestion {
  question: string;
  header: string;
  options: { label: string; description: string }[];
  multiSelect: boolean;
}

/** The router's verdict for one clarify request. answers maps question text
 *  → chosen answer (multi-select comma-joined — the harness contract);
 *  response is freeform text typed instead of picking an option.
 *  answered:false carries the message the agent sees (dismissed / nobody
 *  connected) — it should proceed on its own judgment, not fail the turn. */
export type ClarifyDecision =
  | { answered: true; answers: Record<string, string>; response?: string }
  | { answered: false; message: string };

/** One session as the session toolset reports it (snake_case — this JSON
 *  goes straight into the model's context via the list_sessions tool). */
export interface SessionToolInfo {
  session_id: string;
  /** True for THE daemon-managed main session (the assistant surface). */
  is_main: boolean;
  title: string | null;
  /** Archived flag (session.archive) — list metadata; archived sessions
   *  still run and can be messaged. */
  archived: boolean;
  topic: string | null;
  summary: string | null;
  /** Workspace name when the session's cwd falls under a registered
   *  workspace (null otherwise) — the human grouping label. */
  workspace: string | null;
  cwd: string;
  run_state: string;
  lifecycle: string;
  model: string | null;
  last_active: number;
  created_at: number;
}

/** The cross-session toolset seam the router supplies (see
 *  AdapterCallbacks.sessionTools). All ids are DOMAIN session ids. */
export interface SessionToolsApi {
  listSessions(): SessionToolInfo[];
  /** Render the last `turns` turns of a session as readable text. Tool calls
   *  and thinking are opt-in — the default is user/assistant text only. */
  getSessionTurns(sessionId: string, opts: { turns: number; includeToolCalls: boolean; includeThinking: boolean }): string;
  /** Queue a prompt into another session (source="agent"). Auto-revives a
   *  non-live target. Loop-guarded: refuses when the CALLING turn itself
   *  originated from an agent prompt (one hop max) or targets self. */
  sendToSession(sessionId: string, prompt: string): Promise<string>;
  /** Inject mid-turn guidance into another session's RUNNING turn. Same
   *  guards as sendToSession; requires a turn in flight on the target. */
  steerSession(sessionId: string, prompt: string): Promise<string>;
  /** Interrupt another session's running turn. Same loop/self guards. */
  interruptSession(sessionId: string): Promise<string>;
  /** One-shot watch: when the target session's current/next turn completes
   *  (or the session errors), the daemon drops a short digest prompt into
   *  the MAIN session. Re-arm by calling again. */
  watchSession(sessionId: string, note?: string): string;
  /** The secret-entry flow: ask the USER to type a credential into their
   *  client, where it goes straight to the keyring entry `entry`. Resolves to
   *  the ONLY thing the agent may learn — "stored at <entry>", a denial, or a
   *  failure — and NEVER to the value. Never rejects: every outcome is a
   *  sentence the model reads as its tool result. */
  requestSecret(entry: string, description: string): Promise<string>;
}

/** A roster entry as the agent sees it (snake_case, matching the wire). */
export interface DeviceInfo {
  device_id: string;
  platform: string;
  /** Currently connected to the gateway right now. */
  connected: boolean;
  capabilities: string[];
  first_seen: number;
  last_seen: number;
}

/** One subscription rate-limit window as the harness reports it (feeds
 *  usage.summary.plan_limits). */
export interface PlanLimitWindow {
  /** Harness-native id ("five_hour", "seven_day", "model:Fable"…). */
  id: string;
  /** Human label ("5-hour", "Weekly (Opus)"). */
  label: string;
  /** Percent used, 0–100, or null when the harness can't say. */
  utilization: number | null;
  /** ISO 8601 reset time, or null. */
  resetsAt: string | null;
}

/** Account-level subscription plan usage (Claude Code's /usage windows). */
export interface PlanUsage {
  /** Plan tier ("pro" | "max" | …) or null when unknown. */
  subscriptionType: string | null;
  windows: PlanLimitWindow[];
}

export interface HarnessSession {
  /** Send a prompt into this (persistent) session.
   *
   *  CONTRACT (queue-and-return): the promise settles when the prompt is
   *  ACCEPTED into the harness's input — never when the turn completes. It
   *  rejects ONLY when the prompt cannot be accepted at all (queue closed,
   *  child dead) so the caller's RPC fails visibly. Once accepted, the turn
   *  reports exclusively through the callbacks: completion via onResult (+ a
   *  message.complete event), failure via onError — exactly once, never ALSO
   *  as a send rejection (no double-reporting). This keeps prompt.submit's
   *  ack latency uniform across adapters: the RPC result is the minted
   *  message identity, not the turn. */
  send(prompt: string, source?: "text" | "voice"): Promise<void>;
  interrupt(): Promise<void>;
  /** Ask the harness to compact this session's context NOW (T2 #11a). Same
   *  queue-and-return contract as send: settles on acceptance; progress and
   *  outcome arrive as session.compaction events through onEvent. Optional —
   *  a harness without a manual-compact seam omits it and session.compact
   *  rejects with a clear message (never reimplement compaction: T3). */
  compact?(): Promise<void>;
  /** The harness account's subscription plan-limit windows (Claude Code's
   *  /usage data: 5-hour + weekly utilization). ACCOUNT-global, not
   *  per-session — usage.summary asks any one live session and tags the
   *  result with the adapter name. Resolve null (never reject) when the
   *  account has no plan limits (API key auth) or the harness can't say.
   *  Optional: a harness without the seam (OpenCode today) omits it and
   *  usage.summary.plan_limits is simply empty. If we ever add another
   *  subscription harness (e.g. a Codex adapter), implement THIS seam there
   *  and its limits surface on every client with no further wiring. */
  planUsage?(): Promise<PlanUsage | null>;
  /** Stop the session and release the child process. */
  stop(): Promise<void>;
}

/** A model the harness can run a session on (backs the model.list RPC). */
export interface HarnessModel {
  /** The harness's own model identifier, passed verbatim to spawn (for
   *  Claude Code: the API alias, e.g. "claude-opus-5"). */
  id: string;
  /** Human label for pickers ("Opus 5"). */
  label: string;
  /** One-line blurb for a settings list. Optional — pickers fall back to the
   *  label alone. */
  description?: string;
}

/** Result of a harness-native session fork (session.fork, T2 #3). */
export interface ForkResult {
  /** The NEW harness session id (resumable via SpawnOptions.resumeHarnessSessionId). */
  harnessSessionId: string;
  /** Soft caveat about what the fork does NOT carry (e.g. Claude Code copies
   *  the conversation but not file-history/undo snapshots). */
  warning?: string;
}

export interface HarnessAdapter {
  readonly name: string;
  /** True when spawn honors SpawnOptions.resumeAtHarnessUuid (the rewind
   *  primitive behind session.undo). Absent/false = session.undo rejects
   *  loudly (OpenCode/ACP today — no resume-at seam). */
  readonly supportsResumeAt?: boolean;
  /** Spawn a session for the given spec. Streams events via callbacks. */
  spawn(spec: SessionSpec, opts: SpawnOptions, cb: AdapterCallbacks): HarnessSession;
  /** Models this harness can run (model.list). Optional: an adapter with no
   *  model choice (OpenCode today) simply omits it → empty list on the wire. */
  listModels?(): HarnessModel[];
  /** The model a model-less session.create falls back to when the daemon
   *  config names none — the harness's OWN preferred tier, so no Claude alias
   *  can leak into a non-Claude adapter. Config (file/env) always outranks it.
   *  Optional: omit to keep the old behavior (defer to whatever the harness
   *  picks internally, which the daemon can't see until a turn runs). */
  defaultModel?(): string | undefined;
  /** Fork a stored session's state into a NEW harness session, optionally cut
   *  at a harness message uuid (inclusive). Optional: an adapter without fork
   *  support omits it and session.fork rejects with a clear message (the
   *  client's fallback is its seed-create branch). Must not require the
   *  source session to be live. */
  forkSession?(
    harnessSessionId: string,
    opts: { cwd: string; upToHarnessUuid?: string; title?: string },
  ): Promise<ForkResult>;
}
