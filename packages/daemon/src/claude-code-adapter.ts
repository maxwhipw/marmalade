// claude-code-adapter.ts — the Agent SDK fast-path (Decision 4, pattern A2).
//
// Persistent session = streaming-input mode: query() gets an AsyncIterable of
// SDKUserMessage we push into, so multiple prompt.submit turns share one child
// process + one SDK session (prompt caching + continuity). The official binary
// owns auth; we pass only the ALLOWLIST env from policy.buildChildEnv (sec-5.5)
// — never process.env, never a token.

import { query, tool, createSdkMcpServer, forkSession as sdkForkSession, type SDKUserMessage, type Options, type CanUseTool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import type { SessionSpec } from "./policy.js";
import { buildChildEnv } from "./policy.js";
import { normalize, recordApprovalChoice, type NormalizeScratch } from "./normalize.js";
import type { PlanLimitWindow, PlanUsage } from "./adapter.js";
import type { HarnessAdapter, HarnessModel, HarnessSession, SpawnOptions, AdapterCallbacks, ForkResult, ClarifyQuestion } from "./adapter.js";

/** A pushable async queue of SDKUserMessage for streaming-input mode.
 *  Exported for the hardening test suite only — not part of the adapter's
 *  public surface. */
export class PromptQueue implements AsyncIterable<SDKUserMessage> {
  private queue: SDKUserMessage[] = [];
  private waiting: ((v: IteratorResult<SDKUserMessage>) => void) | null = null;
  private closed = false;

  /** `shouldQuery: false` appends the message as context WITHOUT triggering an
   *  assistant turn — used for the M4a state preload so it sits in context
   *  until the first real prompt (coh-H3: preload is context, not a turn). */
  push(text: string, shouldQuery = true): void {
    // A push after close() would sit in the queue forever (the iterator has
    // returned) — the prompt would vanish silently. Fail loud instead: the
    // caller's RPC rejects visibly (hardening: send-after-stop is a bug in
    // the caller, not a case to absorb).
    if (this.closed) throw new Error("PromptQueue is closed — session is stopping");
    const msg: SDKUserMessage = {
      type: "user",
      message: { role: "user", content: text },
      parent_tool_use_id: null,
      ...(shouldQuery ? {} : { shouldQuery: false }),
    };
    if (this.waiting) {
      this.waiting({ value: msg, done: false });
      this.waiting = null;
    } else {
      this.queue.push(msg);
    }
  }

  close(): void {
    this.closed = true;
    if (this.waiting) {
      this.waiting({ value: undefined as unknown as SDKUserMessage, done: true });
      this.waiting = null;
    }
  }

  async *[Symbol.asyncIterator](): AsyncIterator<SDKUserMessage> {
    while (true) {
      if (this.queue.length > 0) {
        yield this.queue.shift()!;
        continue;
      }
      if (this.closed) return;
      const next = await new Promise<IteratorResult<SDKUserMessage>>((resolve) => {
        this.waiting = resolve;
      });
      if (next.done) return;
      yield next.value;
    }
  }
}

/** Marmalade-internal MCP tools are allowed STRUCTURALLY — never prompted
 *  (M2 plan: policy.ts-style code allowlist). They are the daemon's own
 *  in-process tools; prompting the user to approve the daemon's own
 *  bookkeeping is noise. */
const INTERNAL_TOOLS = new Set([
  "mcp__marmalade__update_session_summary",
  "mcp__marmalade__list_devices",
  // Session toolset: read-only views + the one-shot watch are structurally
  // allowed. The MUTATING cross-session tools (send_to_session,
  // steer_session, interrupt_session) deliberately are NOT — they ride the
  // normal approval seam, so approvals=prompt parks them like any tool call.
  "mcp__marmalade__list_sessions",
  "mcp__marmalade__get_session_turns",
  "mcp__marmalade__watch_session",
]);

/** M2: bridge canUseTool to the router's requestApproval seam. The router
 *  owns mode/parking/pattern cache; this only maps Decision → SDK shape.
 *  No callback supplied (tests) = allow with log (M1 behavior).
 *  Exported for tests only. */
export function approvalBridge(
  cb: AdapterCallbacks,
  log: (line: string) => void,
  scratch?: NormalizeScratch,
): CanUseTool {
  return async (toolName, input) => {
    if (INTERNAL_TOOLS.has(toolName)) return { behavior: "allow", updatedInput: input };
    // AskUserQuestion is not a permission check — it's the agent asking the
    // USER something. Route it to the clarify seam (never mode-gated): the
    // answers come back in updatedInput, which is how the SDK returns them to
    // the model. An unanswerable question denies with a proceed-on-your-own
    // message — deny is a tool_result error, not a turn failure.
    if (toolName === "AskUserQuestion") {
      if (!cb.requestClarify) return { behavior: "allow", updatedInput: input };
      const d = await cb.requestClarify(parseClarifyQuestions(input));
      return d.answered
        ? { behavior: "allow", updatedInput: { ...input, answers: d.answers, ...(d.response ? { response: d.response } : {}) } }
        : { behavior: "deny", message: d.message };
    }
    if (!cb.requestApproval) {
      log(`[approval] auto-approved ${toolName} ${JSON.stringify(input).slice(0, 120)}`);
      return { behavior: "allow", updatedInput: input };
    }
    const decision = await cb.requestApproval({ toolName, input });
    // A choice is present only when the user was actually asked (auto-approve, an
    // allowlisted session pattern and the unattended fallback all resolve
    // without one). Stage it against this call so the matching tool.complete
    // can carry it into the transcript — approval.request/resolved are
    // transient, so this is the only durable record that a human decided.
    if (decision.choice) recordApprovalChoice(scratch, toolName, input, decision.choice);
    return decision.behavior === "allow"
      ? { behavior: "allow", updatedInput: input }
      : { behavior: "deny", message: decision.message };
  };
}

/** Defensive mirror of AskUserQuestionInput → the harness-agnostic shape.
 *  The SDK schema guarantees 1–4 questions / 2–4 options, but the bridge
 *  never trusts harness input shapes (same posture as normalize.ts). */
function parseClarifyQuestions(input: Record<string, unknown>): ClarifyQuestion[] {
  const raw = Array.isArray((input as { questions?: unknown }).questions)
    ? ((input as { questions: unknown[] }).questions)
    : [];
  return raw.map((q) => {
    const o = (q ?? {}) as Record<string, unknown>;
    return {
      question: String(o.question ?? ""),
      header: String(o.header ?? ""),
      options: Array.isArray(o.options)
        ? o.options.map((opt) => {
            const p = (opt ?? {}) as Record<string, unknown>;
            return { label: String(p.label ?? ""), description: String(p.description ?? "") };
          })
        : [],
      multiSelect: Boolean(o.multiSelect),
    };
  });
}

/** The cross-session toolset (assistant plan 2026-07-19), exposed on every
 *  session when the router supplies the seam. Refusals (loop guard, unknown
 *  target, no turn in flight) arrive as thrown Errors — returned as the tool
 *  result text so the model reads WHY instead of seeing a hard failure. */
// Return type is any[]: SdkMcpToolDefinition<AnyZodRawShape> is not
// assignable FROM a concretely-schema'd tool (handler-arg variance), so a
// typed array can't hold these — the inline spread in createSdkMcpServer's
// literal is the only place the SDK's types line up.
function sessionToolDefs(cb: AdapterCallbacks): any[] {
  const st = cb.sessionTools;
  if (!st) return [];
  const text = (t: string) => ({ content: [{ type: "text" as const, text: t }] });
  const run = async (fn: () => string | Promise<string>) => {
    try { return text(await fn()); } catch (e) { return text(`Error: ${(e as Error).message}`); }
  };
  return [
    tool(
      "list_sessions",
      "List every marmalade session (id, title, topic/summary, workspace, run_state, last_active, is_main). Use this to answer questions about what other sessions/runs are doing before reaching for their transcripts.",
      {
        workspace: z.string().optional().describe("Only sessions in this workspace (name, case-insensitive)"),
        active_only: z.boolean().optional().describe("Only sessions with a live/running child (default false)"),
      },
      async (args) => run(() => {
        let rows = st.listSessions();
        if (args.workspace) {
          const w = args.workspace.toLowerCase();
          rows = rows.filter((r) => r.workspace?.toLowerCase() === w);
        }
        if (args.active_only) rows = rows.filter((r) => r.run_state === "running" || r.run_state === "starting" || r.run_state === "awaiting_input");
        return JSON.stringify(rows, null, 2);
      }),
    ),
    tool(
      "get_session_turns",
      "Read the last N turns of another session as text (user + assistant messages). Tool calls and thinking are EXCLUDED unless you opt in — prefer the default view; opt in only when you need to debug what a session actually did.",
      {
        session_id: z.string(),
        turns: z.number().int().min(1).max(20).default(3),
        include_tool_calls: z.boolean().default(false),
        include_thinking: z.boolean().default(false),
      },
      async (args) => run(() => st.getSessionTurns(args.session_id, {
        turns: args.turns,
        includeToolCalls: args.include_tool_calls,
        includeThinking: args.include_thinking,
      }) || "(session has no conversation yet)"),
    ),
    tool(
      "send_to_session",
      "Queue a prompt into another session as its next turn (it runs there, not here). Use for: telling a run to continue, asking a session a question, escalating something to the main assistant session. The prompt arrives marked as coming from this session. One hop only: a turn that was itself started by another session cannot use this.",
      { session_id: z.string(), prompt: z.string().min(1) },
      async (args) => run(() => st.sendToSession(args.session_id, args.prompt)),
    ),
    tool(
      "steer_session",
      "Inject mid-turn guidance into another session's RUNNING turn (course-correct without restarting). Fails when no turn is in flight there — use send_to_session for an idle session.",
      { session_id: z.string(), prompt: z.string().min(1) },
      async (args) => run(() => st.steerSession(args.session_id, args.prompt)),
    ),
    tool(
      "interrupt_session",
      "Interrupt another session's running turn (stop what it is doing; the session stays usable). Only when the user asks for it or a run is clearly misbehaving.",
      { session_id: z.string() },
      async (args) => run(() => st.interruptSession(args.session_id)),
    ),
    tool(
      "watch_session",
      "Watch another session: when its current/next turn completes (or it errors), the main assistant session gets a short digest prompt. One-shot — call again to keep watching. Use when the user asks to be told when a run finishes.",
      { session_id: z.string(), note: z.string().optional().describe("Context to include in the digest, e.g. what to check for") },
      async (args) => run(() => st.watchSession(args.session_id, args.note)),
    ),
    // The secret-entry flow. The point of the tool is what it does NOT return:
    // the user types the credential into their own client and it goes straight
    // to the keyring, so the value never enters this context, the transcript,
    // or any log. Hence the emphatic description — a model that "helpfully"
    // asks for the password in chat has already lost the property.
    tool(
      "request_secret",
      "Ask the user to type a credential (password, API key, token) into a secure input on their device; it is written to the keyring entry you name and you never see it. ALWAYS use this instead of asking for a secret in the conversation — a secret typed in chat is permanently in your context, the transcript, and the search index. Returns only confirmation that it was stored. To USE the credential later, read it from the keyring at that entry (or hand the entry name to whatever needs it) — do not ask the user to repeat it.",
      {
        entry: z.string().min(1).describe("Keyring entry path to store it at, e.g. marmalade/email/imap-password. Use a descriptive namespaced path."),
        description: z.string().min(1).describe("What the credential is for, in one line — shown to the user above the input so they know what they are typing and why."),
      },
      async (args) => run(() => st.requestSecret(args.entry, args.description)),
    ),
  ];
}

export interface ClaudeAdapterConfig {
  /** PATH for child processes (allowlist env). */
  path: string;
  /** Optional metered key from the keyring (authClass=metered only). */
  meteredKey?: string;
  /** Override the claude executable (else the SDK's bundled official binary). */
  pathToClaudeCodeExecutable?: string;
  log?: (line: string) => void;
}

/** The models a subscription Claude Code session can run on (model.list).
 *  Static by design: the daemon, not the client, owns this list, and the ids
 *  are the durable API aliases (no date suffixes). Ids pass through to the
 *  SDK verbatim and are NOT gated at session.create — the SDK is the
 *  authority on what actually runs (an unknown id fails the spawn, visibly).
 *  Refresh when Anthropic ships a new tier (source: claude-api skill,
 *  models current as of 2026-07-25). Order is picker order: the default tier
 *  first, legacy tiers last. */
const CLAUDE_MODELS: HarnessModel[] = [
  { id: "claude-opus-5", label: "Opus 5", description: "The standard — agentic coding and long-horizon work" },
  { id: "claude-fable-5", label: "Fable 5", description: "Most capable; slowest and priciest — hard reasoning" },
  { id: "claude-sonnet-5", label: "Sonnet 5", description: "Near-Opus quality, faster and cheaper" },
  { id: "claude-haiku-4-5", label: "Haiku 4.5", description: "Fastest and cheapest — simple, scoped tasks" },
  { id: "claude-opus-4-8", label: "Opus 4.8", description: "Previous-generation Opus" },
];

/** What a model-less session.create runs on when neither config.json nor
 *  MARMALADE_DEFAULT_MODEL says otherwise (product decision 2026-07-25:
 *  Opus 5 is the new standard). Declared by the ADAPTER, not the daemon config, so a
 *  non-Claude harness never inherits a Claude alias. */
const CLAUDE_DEFAULT_MODEL = "claude-opus-5";

export class ClaudeCodeAdapter implements HarnessAdapter {
  readonly name = "claude-code";
  /** session.undo's primitive: resume + resumeSessionAt rewinds in place
   *  (verified live 2026-07-18; design note kept internally). */
  readonly supportsResumeAt = true;
  constructor(private cfg: ClaudeAdapterConfig) {}

  listModels(): HarnessModel[] {
    return CLAUDE_MODELS;
  }

  defaultModel(): string {
    return CLAUDE_DEFAULT_MODEL;
  }

  /** session.fork (T2 #3): SDK forkSession copies the transcript (tool calls
   *  and reasoning included) into a new session file with remapped uuids —
   *  end fork when upToHarnessUuid is absent, mid-point cut (inclusive) when
   *  present. Works on stored sessions; no live child required. */
  async forkSession(
    harnessSessionId: string,
    opts: { cwd: string; upToHarnessUuid?: string; title?: string },
  ): Promise<ForkResult> {
    const result = await sdkForkSession(harnessSessionId, {
      dir: opts.cwd,
      ...(opts.upToHarnessUuid ? { upToMessageId: opts.upToHarnessUuid } : {}),
      ...(opts.title ? { title: opts.title } : {}),
    });
    return {
      harnessSessionId: result.sessionId,
      // Per the SDK contract: forked sessions start without undo history —
      // file-history snapshots are not copied. Conversation context is.
      warning: "fork carries the full conversation, but not file-history/undo snapshots",
    };
  }

  spawn(spec: SessionSpec, opts: SpawnOptions, cb: AdapterCallbacks): HarnessSession {
    const log = this.cfg.log ?? (() => {});
    const prompts = new PromptQueue();
    const env = buildChildEnv(spec, { path: this.cfg.path, meteredKey: this.cfg.meteredKey });

    // marmalade-owned in-process MCP server: exposes the session-summary tool
    // to the agent. In-process + per-session closure = no token needed (the
    // closure IS the attribution). The agent is encouraged (behavior spec) to
    // keep a short summary of the session topic + open items.
    const listDevices = cb.listDevices?.bind(cb);
    const marmaladeMcp = createSdkMcpServer({
      name: "marmalade",
      version: "0.1.0",
      tools: [
        tool(
          "update_session_summary",
          // The trigger is spelled out because this description is the ONLY
          // instruction most sessions get: a non-main session runs on the
          // harness's default persona, so there is no system prompt to put
          // this in. "Only call when something changed worth remembering" was
          // true but passive, and the column stayed null in every session
          // (observed 2026-07-25 — the client's session panel had nothing to
          // show). A first summary is seeded from the opening exchange by a
          // cheap side-call; from then on this tool is how it stays true.
          "Replace this session's summary — what it is for, what has been done, what is still open. Under 1000 characters; it is a recall note for later, not a transcript. CALL IT when you finish a unit of work (a feature, a fix, an investigation that reached a conclusion), when the goal changes, or before starting something long. Do not call it after every message.",
          { topic: z.string().optional(), summary: z.string().max(1000) },
          async (args) => {
            cb.onSummaryUpdate?.({ topic: args.topic, summary: args.summary });
            return { content: [{ type: "text", text: "Session summary updated." }] };
          },
        ),
        // The device roster (P3): only exposed when the router supplies it.
        // The current turn's origin device arrives as turn metadata, not via
        // a tool — this is the live "what can I target" view.
        ...(listDevices ? [tool(
          "list_devices",
          "List the devices paired to marmalade (id, platform, connected-right-now, capabilities). Device-touching actions default to the device the current turn originated from (see the turn-origin metadata); use this roster when the user names a different device or you need to know what is reachable.",
          {},
          async () => ({ content: [{ type: "text" as const, text: JSON.stringify(listDevices(), null, 2) }] }),
        )] : []),
        ...sessionToolDefs(cb),
      ],
    });

    // Declared here rather than beside the stream loop because the approval
    // bridge writes into it too: `canUseTool` gets no tool_use_id, so a
    // decision is matched back to the call that opened it via the scratch's
    // open-tool-use list. Carries the last assistant API call's usage as well,
    // so the result's message.complete can report true context occupancy.
    const scratch: NormalizeScratch = {};

    const options: Options = {
      cwd: spec.cwd,
      env,
      includePartialMessages: true, // stream message.delta
      canUseTool: approvalBridge(cb, log, scratch),
      mcpServers: { marmalade: marmaladeMcp },
      // NO allowedTools entries: a bare allowedTools name auto-approves
      // BEFORE canUseTool runs (the live-seen CLAUDE_SDK_CAN_USE_TOOL_SHADOWED
      // warning). The marmalade-internal tools are allowlisted structurally
      // inside the canUseTool bridge instead.
      // Behavior injection: custom prompt for the main session (identity+duties);
      // undefined lets coding sessions keep the harness default (M4a wires the
      // preset+append path).
      ...(opts.systemPrompt ? { systemPrompt: opts.systemPrompt } : {}),
      ...(opts.resumeHarnessSessionId ? { resume: opts.resumeHarnessSessionId } : {}),
      // Rewind-resume (session.undo): the SDK slices its context to this
      // uuid; the first new turn branches the JSONL non-destructively.
      ...(opts.resumeAtHarnessUuid ? { resumeSessionAt: opts.resumeAtHarnessUuid } : {}),
      ...(opts.model ? { model: opts.model } : {}),
      // Reasoning effort (defaults slice, 2026-07-23): validated upstream
      // against EFFORT_LEVELS; the SDK silently downgrades levels a model
      // doesn't support, which is the behavior we want.
      ...(opts.effort ? { effort: opts.effort as Options["effort"] } : {}),
      ...(this.cfg.pathToClaudeCodeExecutable
        ? { pathToClaudeCodeExecutable: this.cfg.pathToClaudeCodeExecutable }
        : {}),
    };

    const q = query({ prompt: prompts, options });

    // M4a state preload: prepend to the FIRST real prompt so it lands in the
    // same turn as context, no separate no-op message (coh-H3: preload is
    // context, injected once per session generation).
    let pendingContext = opts.firstMessageContext;

    // Drain the SDK message stream → normalized gateway events + callbacks.
    // apiKeySource is reported on the init message; remember it so it's known
    // at result time (for the subscription-vs-metered verification).
    let apiKeySource: string | undefined;
    (async () => {
      try {
        for await (const msg of q) {
          cb.onActivity();
          // Events are tagged with the daemon session id (client-facing);
          // the SDK's own id is captured separately for resume.
          const norm = normalize(msg, opts.daemonSessionId, scratch);
          if (norm.sdkSessionId) cb.onHarnessSession(norm.sdkSessionId);
          if (norm.harnessMessageUuid) cb.onHarnessMessageUuid?.(norm.harnessMessageUuid);
          if (norm.apiKeySource !== undefined) apiKeySource = norm.apiKeySource;
          for (const ev of norm.events) cb.onEvent(ev);
          if (norm.result) cb.onResult(norm.result, apiKeySource);
        }
      } catch (err) {
        // Surface the failure to the router (client-visible error + terminal
        // status) instead of dying silently mid-stream (R3/M3).
        log(`[claude-code-adapter] stream error: ${(err as Error).message}`);
        cb.onError?.("stream_error", (err as Error).message);
      }
    })();

    return {
      async send(prompt: string) {
        if (pendingContext) {
          prompts.push(`${pendingContext}\n\n---\n\n${prompt}`);
          pendingContext = undefined;
        } else {
          prompts.push(prompt);
        }
      },
      async interrupt() {
        await q.interrupt().catch(() => {});
      },
      // T2 #11a: the CLI's manual-compact seam is the /compact slash command
      // sent as a plain streamed user message (verified live 2026-07-18:
      // status compacting → compact_result → compact_boundary trigger=manual).
      // Pushed RAW — the M4a pendingContext prepend or an origin preamble
      // would break the CLI's slash-command detection.
      async compact() {
        prompts.push("/compact");
      },
      // Subscription plan limits (usage.summary.plan_limits): the /usage
      // windows — 5-hour + weekly utilization + resets. The SDK method is
      // EXPERIMENTAL (name says so; pinned 0.3.207), so every failure mode —
      // rename, removal, claude.ai fetch error — degrades to null ("no plan
      // limits"), never a failed usage.summary.
      async planUsage(): Promise<PlanUsage | null> {
        try {
          const u = await q.usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET();
          if (!u.rate_limits_available || !u.rate_limits) return null;
          const rl = u.rate_limits;
          const windows: PlanLimitWindow[] = [];
          const push = (id: string, label: string, w?: { utilization: number | null; resets_at: string | null } | null) => {
            if (w) windows.push({ id, label, utilization: w.utilization, resetsAt: w.resets_at });
          };
          push("five_hour", "5-hour", rl.five_hour);
          push("seven_day", "Weekly (all models)", rl.seven_day);
          push("seven_day_opus", "Weekly (Opus)", rl.seven_day_opus);
          push("seven_day_sonnet", "Weekly (Sonnet)", rl.seven_day_sonnet);
          push("seven_day_oauth_apps", "Weekly (OAuth apps)", rl.seven_day_oauth_apps);
          for (const m of rl.model_scoped ?? []) {
            windows.push({ id: `model:${m.display_name}`, label: `Weekly (${m.display_name})`, utilization: m.utilization, resetsAt: m.resets_at });
          }
          return { subscriptionType: u.subscription_type, windows };
        } catch (err) {
          log(`[claude-code-adapter] planUsage unavailable: ${(err as Error).message}`);
          return null;
        }
      },
      async stop() {
        prompts.close();
        await q.interrupt().catch(() => {});
      },
    };
  }
}
