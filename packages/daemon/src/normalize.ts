// normalize.ts — map Agent SDK messages onto the frozen gateway event
// vocabulary (Decision 4 "one orchestration vocabulary"). PURE and
// unit-tested: the adapter streams SDKMessages through here, and the router
// forwards the resulting gateway events to the connection + transcript cache.
//
// This is where "the adapter normalizes once" (simp-H1) actually happens — the
// transcript cache then persists these normalized events as flat NDJSON; there
// is no second event-sourcing store.

import type { SDKMessage } from "@anthropic-ai/claude-agent-sdk";
import { makeEvent, type JsonRpcEvent } from "@marmalade/protocol";

export interface ResultInfo {
  subtype: string;
  isError: boolean;
  totalCostUsd: number;
  /** input tokens INCLUDING cache read/creation — see normalize() (R10). */
  inputTokens: number;
  outputTokens: number;
  /** The final assistant text on a successful turn (undefined on error). */
  text?: string;
  /** Context-window occupancy after this turn (same number the wire usage
   *  block reports) — feeds the router's context-pressure reminder. */
  contextPercent?: number;
  /** Tokens occupying the window after this turn, and the harness-reported
   *  window of the model that carried it — the SAME numbers wireUsage puts on
   *  message.complete, lifted out so the router can persist them on the
   *  session row (cold-open context for clients). Undefined when the harness
   *  reported no per-call usage (e.g. the ACP/OpenCode adapter). */
  contextUsed?: number;
  contextMax?: number;
}

/** Per-attach mutable scratchpad threaded through normalize() calls. The
 *  result message's cumulative usage overstates context occupancy on
 *  multi-call turns (cache reads re-count per call), so the adapter carries
 *  the LAST assistant API call's usage here — that call's input+cache+output
 *  IS the context window occupancy the donut should show. */
export interface NormalizeScratch {
  lastCallUsage?: {
    input_tokens?: number;
    output_tokens?: number;
    cache_read_input_tokens?: number;
    cache_creation_input_tokens?: number;
  };
  /** tool_use id → the spawn metadata of a live subagent, so the matching
   *  tool_result can be recognised as that subagent SETTLING (and carry its
   *  report) rather than as an ordinary tool completing. Populated in
   *  `case "assistant"`, drained in `case "user"`. */
  subagentSpawns?: Map<string, { subagentType?: string; description?: string }>;
  /**
   * Tool calls that have started and not yet completed, oldest first. This
   * exists solely to give the approval bridge a tool_use id to attach its
   * decision to: the SDK's `CanUseTool` receives only `(toolName, input,
   * options)` — no tool_use_id — so the id has to come from the `tool_use`
   * block that opened the call.
   *
   * Matching the oldest open call of a given [toolIdentity] is safe because
   * approvals are serialized per session (the router parks a second request
   * BEHIND the first), so at most one call of a given identity is awaiting a
   * decision at a time.
   */
  openToolUses?: { id: string; identity: string }[];
  /** tool_use id → the approval choice the user gave for it, staged by the approval
   *  bridge between the decision and the matching tool_result. Drained in
   *  `case "user"` onto `tool.complete`. */
  approvalChoices?: Map<string, string>;
}

/**
 * A stable key for "this exact tool call", used to hand an approval decision
 * back to the `tool_use` block that opened it. Name plus the serialized input:
 * two calls that differ in either are different calls, and two that match in
 * both are interchangeable for the purpose of recording a decision.
 */
export function toolIdentity(name: string | undefined, input: unknown): string {
  let serialized: string;
  try {
    serialized = JSON.stringify(input) ?? "";
  } catch {
    // A cyclic or otherwise unserializable input degrades to name-only rather
    // than throwing — a slightly coarser key loses precision, an exception
    // here would break the turn.
    serialized = "";
  }
  return `${name ?? ""} ${serialized}`;
}

/** Stage an approval choice against the oldest open call of that identity.
 *  A miss (no matching open call) is silently ignored: the decision still
 *  applies, it simply goes unrecorded, which is strictly better than
 *  attaching it to the wrong tool. */
export function recordApprovalChoice(
  scratch: NormalizeScratch | undefined,
  name: string,
  input: unknown,
  choice: string,
): void {
  if (!scratch) return;
  const identity = toolIdentity(name, input);
  const open = scratch.openToolUses?.find((c) => c.identity === identity);
  if (!open) return;
  scratch.approvalChoices ??= new Map();
  scratch.approvalChoices.set(open.id, choice);
}

/** Tool names whose `tool_use` block spawns a subagent. The SDK exposes the
 *  Agent/Task tool under both spellings depending on version. */
const SUBAGENT_TOOLS = new Set(["Task", "Agent"]);

/**
 * `parent_tool_use_id` off an incoming SDK message — null for the main
 * transcript, set to the spawning Task tool's id for anything a subagent
 * produced. This is THE attribution seam: without reading it, subagent tool
 * frames are indistinguishable from the parent's own (they arrive on the same
 * stream, by default, whether or not `forwardSubagentText` is on).
 */
function parentToolUseId(msg: unknown): string | null {
  const v = (msg as { parent_tool_use_id?: string | null }).parent_tool_use_id;
  return typeof v === "string" && v.length > 0 ? v : null;
}

/** The SDK stamps subagent-originated messages with who and why. Both are
 *  optional — absent on the main transcript, and not guaranteed on every
 *  subagent frame. */
function subagentMeta(msg: unknown): { subagent_type?: string; task_description?: string } {
  const m = msg as { subagent_type?: unknown; task_description?: unknown };
  return {
    ...(typeof m.subagent_type === "string" ? { subagent_type: m.subagent_type } : {}),
    ...(typeof m.task_description === "string" ? { task_description: m.task_description } : {}),
  };
}

/** Attribution block appended to every tool event so a client can nest a
 *  subagent's work under the card that spawned it. Empty for the main
 *  transcript — top-level tool events keep their existing payload shape. */
function attribution(msg: unknown): Record<string, unknown> {
  const parent = parentToolUseId(msg);
  return parent === null ? {} : { parent_tool_use_id: parent, ...subagentMeta(msg) };
}

/** A string field off a tool_use input object, when present and non-empty. */
function inputString(input: unknown, key: string): string | undefined {
  const v = (input as Record<string, unknown> | undefined)?.[key];
  return typeof v === "string" && v.trim().length > 0 ? v : undefined;
}

export interface NormalizedOutput {
  events: JsonRpcEvent[];
  /** Set on the init system message — the SDK's own session id (for resume). */
  sdkSessionId?: string;
  /** Set on the init message — the auth source, for the subscription check. */
  apiKeySource?: string;
  /** Set on the result message — feeds the usage meter + turn completion. */
  result?: ResultInfo;
  /** Any activity at all — the supervisor uses this as a heartbeat signal. */
  activity: boolean;
  /** The harness's own uuid for an assistant message — captured PRIVATELY for
   *  the domain↔harness message mapping (P1). NEVER put on a gateway event. */
  harnessMessageUuid?: string;
}

/**
 * Normalize one SDK message into zero or more gateway events (+ side channels).
 * Handles the essential set for M1; unknown message types pass through as
 * `activity: true` with no events (forward-compatible).
 */
export function normalize(
  msg: SDKMessage,
  sessionId: string,
  scratch?: NormalizeScratch,
): NormalizedOutput {
  const out: NormalizedOutput = { events: [], activity: true };

  switch (msg.type) {
    case "system": {
      if (msg.subtype === "init") {
        out.sdkSessionId = msg.session_id;
        out.apiKeySource = msg.apiKeySource;
        out.events.push(
          makeEvent(
            "session.info",
            { session_id: sessionId, model: msg.model, tools: msg.tools },
            sessionId,
          ),
        );
      } else if (msg.subtype === "status") {
        // Compaction surfacing (T2 #11a): the SDK reports compaction via
        // status messages — `compacting` at start, then a null status carrying
        // compact_result/compact_error when it settles. Non-compaction
        // statuses (e.g. `requesting`) emit nothing.
        const s = msg as { status?: string | null; compact_result?: string; compact_error?: string };
        if (s.status === "compacting") {
          out.events.push(makeEvent("session.compaction", { status: "started" }, sessionId));
        } else if (s.compact_result === "success") {
          out.events.push(makeEvent("session.compaction", { status: "completed" }, sessionId));
        } else if (s.compact_result === "failed") {
          out.events.push(makeEvent(
            "session.compaction",
            { status: "failed", ...(s.compact_error ? { error: s.compact_error } : {}) },
            sessionId,
          ));
        }
      } else if (msg.subtype === "compact_boundary") {
        // The boundary marker carries the numbers (manual AND auto compaction
        // — auto surfacing comes for free through the same seam).
        const m = (msg as { compact_metadata: { trigger: string; pre_tokens: number; post_tokens?: number } }).compact_metadata;
        out.events.push(makeEvent(
          "session.compaction",
          {
            status: "boundary",
            trigger: m.trigger,
            pre_tokens: m.pre_tokens,
            ...(m.post_tokens !== undefined ? { post_tokens: m.post_tokens } : {}),
          },
          sessionId,
        ));
      }
      return out;
    }

    case "stream_event": {
      // Partial assistant deltas (only when includePartialMessages: true).
      const ev = msg.event as { type?: string; delta?: { type?: string; text?: string; thinking?: string } };
      if (ev?.type === "content_block_delta") {
        if (ev.delta?.type === "text_delta" && ev.delta.text) {
          out.events.push(makeEvent("message.delta", { text: ev.delta.text }, sessionId));
        } else if (ev.delta?.type === "thinking_delta" && ev.delta.thinking) {
          out.events.push(makeEvent("thinking.delta", { text: ev.delta.thinking }, sessionId));
        }
      } else if (ev?.type === "message_start") {
        out.events.push(makeEvent("message.start", {}, sessionId));
      }
      return out;
    }

    case "assistant": {
      // Full assistant message — surface tool_use blocks as tool.start.
      // Subagent messages arrive on THIS same stream with parent_tool_use_id
      // set (by default for tool frames; also for text when the adapter sets
      // forwardSubagentText). Everything below is attribution-aware.
      const fromSubagent = parentToolUseId(msg) !== null;
      const attrib = attribution(msg);

      // The undo anchor and the context-donut reading describe the MAIN
      // transcript. A subagent's message must not overwrite either: its uuid
      // is not a resumable point in the parent's lineage (session.undo /
      // resumeSessionAt would rewind to a subagent frame), and its API call
      // reports a smaller, unrelated window.
      const uuid = (msg as { uuid?: string }).uuid;
      if (uuid && !fromSubagent) out.harnessMessageUuid = uuid;
      const callUsage = (msg.message as { usage?: NormalizeScratch["lastCallUsage"] }).usage;
      if (scratch && callUsage && !fromSubagent) scratch.lastCallUsage = callUsage;

      const content = (msg.message as { content?: unknown[] }).content ?? [];
      for (const block of content) {
        const b = block as { type?: string; id?: string; name?: string; input?: unknown };
        if (b.type !== "tool_use") continue;
        out.events.push(
          makeEvent("tool.start", { id: b.id, name: b.name, input: b.input, ...attrib }, sessionId),
        );
        // Remember the call so an approval decision can find its id later.
        // tool.start ALWAYS precedes the canUseTool bridge (verified on the
        // wire 2026-07-27: tool.start seq 8 → clarify.request seq 9), so the
        // id is on record before any decision arrives.
        if (b.id && scratch) {
          scratch.openToolUses ??= [];
          scratch.openToolUses.push({ id: b.id, identity: toolIdentity(b.name, b.input) });
        }
        // A Task/Agent tool_use IS the spawn. Emit the lifecycle event beside
        // the tool frame (additive — a client that ignores subagent.* still
        // sees the tool card) and remember it so the matching tool_result can
        // settle it.
        if (b.name && SUBAGENT_TOOLS.has(b.name) && b.id) {
          const subagentType = inputString(b.input, "subagent_type");
          const description =
            inputString(b.input, "description") ?? inputString(b.input, "prompt");
          if (scratch) {
            scratch.subagentSpawns ??= new Map();
            scratch.subagentSpawns.set(b.id, { subagentType, description });
          }
          out.events.push(
            makeEvent(
              "subagent.start",
              {
                tool_use_id: b.id,
                ...(subagentType ? { subagent_type: subagentType } : {}),
                ...(description ? { description } : {}),
              },
              sessionId,
            ),
          );
        }
      }
      return out;
    }

    case "user": {
      // Tool results arrive as synthetic user messages.
      const attrib = attribution(msg);
      // Structured tool output — the tool's full Output object, richer than the
      // model-facing string. For the Agent/Task tool this is the subagent's
      // final report plus run totals, which the SDK documents as the thing to
      // render from instead of parsing tool_result text. Previously dropped.
      const toolUseResult = (msg as { tool_use_result?: unknown }).tool_use_result;
      const content = (msg.message as { content?: unknown[] }).content ?? [];
      for (const block of content) {
        const b = block as { type?: string; tool_use_id?: string; content?: unknown; is_error?: boolean };
        if (b.type !== "tool_result") continue;
        // The approval decision the user gave for THIS call, if they were asked
        // at all. approval.request/resolved are emitTransient — they drive the
        // docked card and then vanish — so without this the fact that a human
        // allowed (or denied) a command left no trace in the transcript, on
        // cold load, or in search. Additive: absent whenever nobody was asked,
        // which is the overwhelmingly common case (approvals default to auto).
        const approvalChoice = b.tool_use_id
          ? scratch?.approvalChoices?.get(b.tool_use_id)
          : undefined;
        if (b.tool_use_id && scratch) {
          scratch.approvalChoices?.delete(b.tool_use_id);
          if (scratch.openToolUses) {
            scratch.openToolUses = scratch.openToolUses.filter((c) => c.id !== b.tool_use_id);
          }
        }
        out.events.push(
          makeEvent(
            "tool.complete",
            {
              tool_use_id: b.tool_use_id,
              ...(b.is_error === true ? { is_error: true } : {}),
              ...(b.content !== undefined ? { content: b.content } : {}),
              ...(toolUseResult !== undefined ? { result: toolUseResult } : {}),
              ...(approvalChoice !== undefined ? { approval: { choice: approvalChoice } } : {}),
              ...attrib,
            },
            sessionId,
          ),
        );
        // If this result settles a tracked spawn, the subagent is done.
        const spawn = b.tool_use_id ? scratch?.subagentSpawns?.get(b.tool_use_id) : undefined;
        if (spawn && b.tool_use_id) {
          scratch!.subagentSpawns!.delete(b.tool_use_id);
          out.events.push(
            makeEvent(
              "subagent.complete",
              {
                tool_use_id: b.tool_use_id,
                ...(spawn.subagentType ? { subagent_type: spawn.subagentType } : {}),
                ...(spawn.description ? { description: spawn.description } : {}),
                ...(b.is_error === true ? { is_error: true } : {}),
                ...(toolUseResult !== undefined ? { result: toolUseResult } : {}),
              },
              sessionId,
            ),
          );
        }
      }
      return out;
    }

    case "tool_progress": {
      // Live per-tool heartbeat (elapsed seconds). Emission is NARROW — REPL
      // inner calls, and Bash/PowerShell only under CLAUDE_CODE_REMOTE /
      // CLAUDE_CODE_CONTAINER_ID — so this is a bonus signal, never something
      // a client should depend on for liveness. Forwarded because it is free.
      const p = msg as unknown as {
        tool_use_id?: string;
        tool_name?: string;
        elapsed_time_seconds?: number;
      };
      out.events.push(
        makeEvent(
          "tool.progress",
          {
            tool_use_id: p.tool_use_id,
            ...(p.tool_name ? { name: p.tool_name } : {}),
            ...(typeof p.elapsed_time_seconds === "number"
              ? { elapsed_s: p.elapsed_time_seconds }
              : {}),
            ...attribution(msg),
          },
          sessionId,
        ),
      );
      return out;
    }

    case "result": {
      const r = msg as Extract<SDKMessage, { type: "result" }>;
      const usage = r.usage as {
        input_tokens?: number;
        output_tokens?: number;
        cache_creation_input_tokens?: number;
        cache_read_input_tokens?: number;
      } | undefined;
      // Include cache tokens — they DOMINATE once the ~29k preload is cache-hit
      // every turn, so an input count without them undercounts by orders of
      // magnitude and would make the budget guardrail never trip (R10).
      const inputTokens =
        (usage?.input_tokens ?? 0) +
        (usage?.cache_creation_input_tokens ?? 0) +
        (usage?.cache_read_input_tokens ?? 0);
      const wire = wireUsage(r, usage, scratch);
      out.result = {
        subtype: r.subtype,
        isError: r.is_error,
        totalCostUsd: r.total_cost_usd,
        inputTokens,
        outputTokens: usage?.output_tokens ?? 0,
        text: r.subtype === "success" ? (r as { result?: string }).result : undefined,
        contextPercent: wire?.usage.context_percent,
        contextUsed: wire?.usage.context_used,
        contextMax: wire?.usage.context_max,
      };
      // A zero-turn result (num_turns 0) is a slash command settling — e.g.
      // the /compact push behind session.compact. No assistant message
      // streamed, so a message.complete here would render an empty bubble;
      // the result still returns (usage metering + runState idle).
      if ((r as { num_turns?: number }).num_turns === 0) return out;
      // message.complete carries the final text (identity plan): a client that
      // dropped mid-stream deltas reconciles to the authoritative message
      // without a full reload. Additive — legacy clients ignore it.
      out.events.push(
        makeEvent(
          "message.complete",
          {
            subtype: r.subtype,
            is_error: r.is_error,
            ...(out.result.text !== undefined ? { text: out.result.text } : {}),
            ...(wire ?? {}),
          },
          sessionId,
        ),
      );
      return out;
    }

    default:
      return out; // unknown message type: activity only, forward-compatible
  }
}

/**
 * The `usage` block riding message.complete — feeds every client's context
 * donut / token breakdown (Android hand-mirrors these snake_case keys in
 * MessageStream.extractUsage; do not rename). Per-turn tallies come from the
 * result's cumulative usage; context occupancy comes from the LAST assistant
 * API call (scratch), whose input+cache+output is what actually sits in the
 * window — the cumulative numbers re-count cache reads per call and would
 * overstate it. context_max is the harness-reported window of the model that
 * carried the turn. Returns undefined when the turn reported no tokens.
 */
function wireUsage(
  r: Extract<SDKMessage, { type: "result" }>,
  usage: { input_tokens?: number; output_tokens?: number; cache_creation_input_tokens?: number; cache_read_input_tokens?: number } | undefined,
  scratch?: NormalizeScratch,
): { usage: Record<string, number> } | undefined {
  if (!usage) return undefined;
  const u: Record<string, number> = {};
  const put = (key: string, v: number | undefined) => {
    if (v !== undefined && Number.isFinite(v)) u[key] = v;
  };
  put("input_tokens", usage.input_tokens);
  put("output_tokens", usage.output_tokens);
  put("cache_read_tokens", usage.cache_read_input_tokens);
  put("cache_creation_tokens", usage.cache_creation_input_tokens);
  put("cost_usd", r.total_cost_usd);

  const last = scratch?.lastCallUsage;
  if (last) {
    const used =
      (last.input_tokens ?? 0) +
      (last.cache_read_input_tokens ?? 0) +
      (last.cache_creation_input_tokens ?? 0) +
      (last.output_tokens ?? 0);
    if (used > 0) {
      put("context_used", used);
      // The turn's main model = the modelUsage entry that consumed the most
      // input (subagent/haiku side-calls report their own smaller windows).
      const models = Object.values(
        (r as { modelUsage?: Record<string, { inputTokens?: number; cacheReadInputTokens?: number; cacheCreationInputTokens?: number; contextWindow?: number }> }).modelUsage ?? {},
      );
      const main = models.reduce<(typeof models)[number] | undefined>((best, m) => {
        const load = (x?: (typeof models)[number]) =>
          x ? (x.inputTokens ?? 0) + (x.cacheReadInputTokens ?? 0) + (x.cacheCreationInputTokens ?? 0) : -1;
        return load(m) > load(best) ? m : best;
      }, undefined);
      const max = main?.contextWindow;
      if (max && max > 0) {
        put("context_max", max);
        put("context_percent", Math.min(100, Math.round((used / max) * 100)));
      }
    }
  }
  return Object.keys(u).length > 0 ? { usage: u } : undefined;
}
