import { test } from "node:test";
import assert from "node:assert/strict";
import { normalize, recordApprovalChoice, toolIdentity } from "../dist/normalize.js";

const SID = "s_test";

test("init system message yields session.info + captures sdk id + apiKeySource", () => {
  const out = normalize(
    { type: "system", subtype: "init", session_id: "sdk-uuid", apiKeySource: "none", model: "claude-fable-5", tools: ["Read"] } as any,
    SID,
  );
  assert.equal(out.sdkSessionId, "sdk-uuid");
  assert.equal(out.apiKeySource, "none");
  assert.equal(out.events.length, 1);
  assert.equal(out.events[0].params.type, "session.info");
  assert.equal((out.events[0].params.payload as any).model, "claude-fable-5");
});

test("stream_event text_delta → message.delta", () => {
  const out = normalize(
    { type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: "hel" } } } as any,
    SID,
  );
  assert.equal(out.events[0].params.type, "message.delta");
  assert.equal((out.events[0].params.payload as any).text, "hel");
});

test("stream_event thinking_delta → thinking.delta", () => {
  const out = normalize(
    { type: "stream_event", event: { type: "content_block_delta", delta: { type: "thinking_delta", thinking: "hmm" } } } as any,
    SID,
  );
  assert.equal(out.events[0].params.type, "thinking.delta");
});

test("assistant tool_use block → tool.start", () => {
  const out = normalize(
    { type: "assistant", session_id: "x", message: { content: [{ type: "tool_use", id: "t1", name: "Bash", input: { cmd: "ls" } }] } } as any,
    SID,
  );
  assert.equal(out.events[0].params.type, "tool.start");
  assert.equal((out.events[0].params.payload as any).name, "Bash");
});

test("result message → message.complete + result info with tokens", () => {
  const out = normalize(
    {
      type: "result", subtype: "success", is_error: false, total_cost_usd: 0.012,
      usage: { input_tokens: 1000, output_tokens: 200 }, result: "done", session_id: "x",
    } as any,
    SID,
  );
  assert.ok(out.result);
  assert.equal(out.result!.totalCostUsd, 0.012);
  assert.equal(out.result!.inputTokens, 1000);
  assert.equal(out.result!.outputTokens, 200);
  assert.equal(out.result!.text, "done");
  assert.equal(out.events[0].params.type, "message.complete");
  // P1: the complete event carries the final text so a client that dropped
  // deltas reconciles without a full reload.
  assert.equal((out.events[0].params.payload as any).text, "done");
});

test("message.complete carries a usage block: turn tallies + context occupancy from the last call", () => {
  // The scratch carries the LAST assistant API call's usage — its
  // input+cache+output is the context occupancy; the result's cumulative
  // numbers would re-count cache reads across calls.
  const scratch = {};
  normalize(
    {
      type: "assistant", session_id: "x",
      message: {
        usage: { input_tokens: 50, output_tokens: 10, cache_read_input_tokens: 20_000, cache_creation_input_tokens: 1_000 },
        content: [{ type: "text", text: "partial" }],
      },
    } as any,
    SID, scratch,
  );
  normalize(
    {
      type: "assistant", session_id: "x",
      message: {
        usage: { input_tokens: 100, output_tokens: 200, cache_read_input_tokens: 40_000, cache_creation_input_tokens: 2_000 },
        content: [{ type: "text", text: "final" }],
      },
    } as any,
    SID, scratch,
  );
  const out = normalize(
    {
      type: "result", subtype: "success", is_error: false, total_cost_usd: 0.05,
      usage: { input_tokens: 150, output_tokens: 210, cache_read_input_tokens: 60_000, cache_creation_input_tokens: 3_000 },
      modelUsage: {
        "claude-opus-4-8": { inputTokens: 150, cacheReadInputTokens: 60_000, cacheCreationInputTokens: 3_000, contextWindow: 200_000 },
        "claude-haiku-4-5": { inputTokens: 10, cacheReadInputTokens: 0, cacheCreationInputTokens: 0, contextWindow: 100_000 },
      },
      result: "done", session_id: "x",
    } as any,
    SID, scratch,
  );
  const usage = (out.events[0].params.payload as any).usage;
  assert.ok(usage);
  assert.equal(usage.input_tokens, 150);
  assert.equal(usage.output_tokens, 210);
  assert.equal(usage.cache_read_tokens, 60_000);
  assert.equal(usage.cache_creation_tokens, 3_000);
  assert.equal(usage.cost_usd, 0.05);
  // Last call: 100 + 40_000 + 2_000 + 200 = 42_300 of the MAIN model's 200k.
  assert.equal(usage.context_used, 42_300);
  assert.equal(usage.context_max, 200_000);
  assert.equal(usage.context_percent, 21);
  // The SAME numbers ride the normalized result so the router can persist them
  // on the session row (cold-open context) — computed once, not twice.
  assert.equal(out.result!.contextUsed, 42_300);
  assert.equal(out.result!.contextMax, 200_000);
  assert.equal(out.result!.contextPercent, 21);
});

test("usage block degrades: no scratch → tallies only; no usage on result → none", () => {
  const bare = normalize(
    {
      type: "result", subtype: "success", is_error: false, total_cost_usd: 0.01,
      usage: { input_tokens: 5, output_tokens: 6 }, result: "ok", session_id: "x",
    } as any,
    SID,
  );
  const u = (bare.events[0].params.payload as any).usage;
  assert.equal(u.input_tokens, 5);
  assert.equal(u.context_percent, undefined);
  // Nothing to persist either — the row stays at "unknown" rather than 0.
  assert.equal(bare.result!.contextUsed, undefined);
  assert.equal(bare.result!.contextMax, undefined);
  const none = normalize(
    { type: "result", subtype: "success", is_error: false, total_cost_usd: 0, result: "ok", session_id: "x" } as any,
    SID,
  );
  assert.equal((none.events[0].params.payload as any).usage, undefined);
});

test("assistant uuid is captured PRIVATELY (side channel), never on an event (P1)", () => {
  const out = normalize(
    {
      type: "assistant", uuid: "sdk-msg-uuid-1", session_id: "x",
      message: { content: [{ type: "text", text: "hi" }] },
    } as any,
    SID,
  );
  assert.equal(out.harnessMessageUuid, "sdk-msg-uuid-1");
  assert.ok(!out.events.some((e) => JSON.stringify(e).includes("sdk-msg-uuid-1")));
});

test("unknown message type is activity-only, no events (forward-compatible)", () => {
  const out = normalize({ type: "some_future_type" } as any, SID);
  assert.equal(out.events.length, 0);
  assert.equal(out.activity, true);
});

// ── Compaction surfacing (T2 #11a) ──────────────────────────────────────────
// The engine is harness-delegated; these lock the SDK-signal → event mapping
// verified live 2026-07-18 (status compacting → compact_result → boundary).

test("system status compacting → session.compaction started", () => {
  const out = normalize({ type: "system", subtype: "status", status: "compacting" } as any, SID);
  assert.equal(out.events.length, 1);
  assert.equal(out.events[0].params.type, "session.compaction");
  assert.equal((out.events[0].params.payload as any).status, "started");
});

test("system status settle: compact_result success → completed, failed → failed+error", () => {
  const ok = normalize({ type: "system", subtype: "status", status: null, compact_result: "success" } as any, SID);
  assert.equal((ok.events[0].params.payload as any).status, "completed");
  const bad = normalize({ type: "system", subtype: "status", status: null, compact_result: "failed", compact_error: "boom" } as any, SID);
  assert.equal((bad.events[0].params.payload as any).status, "failed");
  assert.equal((bad.events[0].params.payload as any).error, "boom");
});

test("non-compaction status (requesting / bare null) emits nothing", () => {
  assert.equal(normalize({ type: "system", subtype: "status", status: "requesting" } as any, SID).events.length, 0);
  assert.equal(normalize({ type: "system", subtype: "status", status: null } as any, SID).events.length, 0);
});

test("compact_boundary → session.compaction boundary with trigger + token counts", () => {
  const out = normalize(
    { type: "system", subtype: "compact_boundary", compact_metadata: { trigger: "manual", pre_tokens: 28539, post_tokens: 6809 } } as any,
    SID,
  );
  const p = out.events[0].params.payload as any;
  assert.equal(out.events[0].params.type, "session.compaction");
  assert.equal(p.status, "boundary");
  assert.equal(p.trigger, "manual");
  assert.equal(p.pre_tokens, 28539);
  assert.equal(p.post_tokens, 6809);
});

test("zero-turn result (slash command settling, e.g. /compact) suppresses message.complete but keeps result info", () => {
  const out = normalize(
    { type: "result", subtype: "success", is_error: false, num_turns: 0, total_cost_usd: 0.01, usage: { input_tokens: 5, output_tokens: 1 }, result: "" } as any,
    SID,
  );
  assert.equal(out.events.length, 0);
  assert.ok(out.result);
  assert.equal(out.result!.inputTokens, 5);
});

test("normal result (num_turns ≥ 1) still emits message.complete (regression guard)", () => {
  const out = normalize(
    { type: "result", subtype: "success", is_error: false, num_turns: 1, total_cost_usd: 0, usage: {}, result: "hi" } as any,
    SID,
  );
  assert.equal(out.events.length, 1);
  assert.equal(out.events[0].params.type, "message.complete");
});

// ── subagent attribution ──────────────────────────────────────────────────
// Subagent frames arrive on the SAME stream as the parent's, distinguished
// ONLY by parent_tool_use_id. Before these landed, normalize never read that
// field: a subagent's tool calls were indistinguishable from the parent's, its
// report was discarded, and its assistant messages corrupted two pieces of
// parent state (the undo anchor + the context-donut reading).

const PARENT = "toolu_task_parent";

test("top-level tool.start carries NO attribution keys (payload shape unchanged)", () => {
  const out = normalize(
    { type: "assistant", message: { content: [{ type: "tool_use", id: "t1", name: "Bash", input: { cmd: "ls" } }] } } as any,
    SID,
  );
  const p = out.events[0].params.payload as any;
  assert.equal(out.events[0].params.type, "tool.start");
  assert.ok(!("parent_tool_use_id" in p), "top-level tool events must stay byte-compatible");
  assert.ok(!("subagent_type" in p));
});

test("subagent tool.start carries parent_tool_use_id + who/why", () => {
  const out = normalize(
    {
      type: "assistant",
      parent_tool_use_id: PARENT,
      subagent_type: "Explore",
      task_description: "sweep adapters/",
      message: { content: [{ type: "tool_use", id: "child1", name: "Grep", input: { pattern: "x" } }] },
    } as any,
    SID,
  );
  const p = out.events[0].params.payload as any;
  assert.equal(p.parent_tool_use_id, PARENT);
  assert.equal(p.subagent_type, "Explore");
  assert.equal(p.task_description, "sweep adapters/");
});

test("a subagent assistant message does NOT overwrite the undo anchor", () => {
  const parent = normalize({ type: "assistant", uuid: "parent-uuid", message: { content: [] } } as any, SID);
  assert.equal(parent.harnessMessageUuid, "parent-uuid");
  const child = normalize(
    { type: "assistant", uuid: "child-uuid", parent_tool_use_id: PARENT, message: { content: [] } } as any,
    SID,
  );
  assert.equal(child.harnessMessageUuid, undefined,
    "a subagent uuid is not a resumable point in the parent's lineage");
});

test("a subagent assistant message does NOT overwrite the context reading", () => {
  const scratch: any = {};
  normalize(
    { type: "assistant", message: { content: [], usage: { input_tokens: 90_000, output_tokens: 500 } } } as any,
    SID, scratch,
  );
  assert.equal(scratch.lastCallUsage.input_tokens, 90_000);
  normalize(
    { type: "assistant", parent_tool_use_id: PARENT, message: { content: [], usage: { input_tokens: 900 } } } as any,
    SID, scratch,
  );
  assert.equal(scratch.lastCallUsage.input_tokens, 90_000,
    "a subagent's smaller window must not skew the parent's donut");
});

test("a Task tool_use emits subagent.start beside the tool frame", () => {
  const scratch: any = {};
  const out = normalize(
    {
      type: "assistant",
      message: { content: [{ type: "tool_use", id: PARENT, name: "Task",
        input: { subagent_type: "Explore", description: "sweep adapters/" } }] },
    } as any,
    SID, scratch,
  );
  assert.deepEqual(out.events.map((e: any) => e.params.type), ["tool.start", "subagent.start"]);
  const p = out.events[1].params.payload as any;
  assert.equal(p.tool_use_id, PARENT);
  assert.equal(p.subagent_type, "Explore");
  assert.equal(p.description, "sweep adapters/");
  assert.ok(scratch.subagentSpawns.has(PARENT), "the spawn is tracked for its completion");
});

test("tool.complete carries the structured tool_use_result (was discarded)", () => {
  const out = normalize(
    {
      type: "user",
      tool_use_result: { status: "completed", report: "found 3 emitters" },
      message: { content: [{ type: "tool_result", tool_use_id: "t1", content: "ok" }] },
    } as any,
    SID,
  );
  const p = out.events[0].params.payload as any;
  assert.equal(p.tool_use_id, "t1");
  assert.equal(p.result.report, "found 3 emitters");
  assert.equal(p.content, "ok");
});

test("a tracked spawn's tool_result settles it as subagent.complete with the report", () => {
  const scratch: any = {};
  normalize(
    { type: "assistant", message: { content: [{ type: "tool_use", id: PARENT, name: "Task",
      input: { subagent_type: "Explore", description: "sweep" } }] } } as any,
    SID, scratch,
  );
  const out = normalize(
    {
      type: "user",
      tool_use_result: { status: "completed", report: "nothing emits subagent.*" },
      message: { content: [{ type: "tool_result", tool_use_id: PARENT, content: "…" }] },
    } as any,
    SID, scratch,
  );
  assert.deepEqual(out.events.map((e: any) => e.params.type), ["tool.complete", "subagent.complete"]);
  const p = out.events[1].params.payload as any;
  assert.equal(p.subagent_type, "Explore");
  assert.equal(p.result.report, "nothing emits subagent.*");
  assert.equal(scratch.subagentSpawns.size, 0, "settled spawns are drained");
});

test("an ordinary tool_result does NOT emit subagent.complete", () => {
  const scratch: any = {};
  const out = normalize(
    { type: "user", message: { content: [{ type: "tool_result", tool_use_id: "unrelated" }] } } as any,
    SID, scratch,
  );
  assert.deepEqual(out.events.map((e: any) => e.params.type), ["tool.complete"]);
});

test("an errored tool_result flags is_error on both events", () => {
  const scratch: any = {};
  normalize({ type: "assistant", message: { content: [{ type: "tool_use", id: PARENT, name: "Task", input: {} }] } } as any, SID, scratch);
  const out = normalize(
    { type: "user", message: { content: [{ type: "tool_result", tool_use_id: PARENT, is_error: true }] } } as any,
    SID, scratch,
  );
  assert.equal((out.events[0].params.payload as any).is_error, true);
  assert.equal((out.events[1].params.payload as any).is_error, true);
});

test("tool_progress → tool.progress (was dropped silently)", () => {
  const out = normalize(
    { type: "tool_progress", tool_use_id: "t1", tool_name: "Bash",
      parent_tool_use_id: PARENT, elapsed_time_seconds: 12 } as any,
    SID,
  );
  assert.equal(out.events[0].params.type, "tool.progress");
  const p = out.events[0].params.payload as any;
  assert.equal(p.name, "Bash");
  assert.equal(p.elapsed_s, 12);
  assert.equal(p.parent_tool_use_id, PARENT);
});

// ── the approval record (2026-07-27) ────────────────────────────────────────

test("tool.start registers the call so an approval decision can find its id", () => {
  const scratch: any = {};
  normalize(
    {
      type: "assistant",
      message: { content: [{ type: "tool_use", id: "t1", name: "Bash", input: { command: "rm -rf build" } }] },
    } as any,
    SID,
    scratch,
  );
  assert.equal(scratch.openToolUses.length, 1);
  assert.equal(scratch.openToolUses[0].id, "t1");
  assert.equal(scratch.openToolUses[0].identity, toolIdentity("Bash", { command: "rm -rf build" }));
});

test("a staged approval choice rides its tool.complete and is then cleared", () => {
  const scratch: any = {};
  normalize(
    {
      type: "assistant",
      message: { content: [{ type: "tool_use", id: "t1", name: "Bash", input: { command: "rm -rf build" } }] },
    } as any,
    SID,
    scratch,
  );
  recordApprovalChoice(scratch, "Bash", { command: "rm -rf build" }, "once");

  const out = normalize(
    { type: "user", message: { content: [{ type: "tool_result", tool_use_id: "t1", content: "ok" }] } } as any,
    SID,
    scratch,
  );
  const p = out.events[0].params.payload as any;
  assert.deepEqual(p.approval, { choice: "once" });
  // Both sides of the bookkeeping drain, so a long turn doesn't accumulate
  // stale entries and a later call can't inherit this decision.
  assert.equal(scratch.approvalChoices.size, 0);
  assert.equal(scratch.openToolUses.length, 0);
});

test("tool.complete stays byte-identical when nobody was asked", () => {
  const scratch: any = {};
  normalize(
    { type: "assistant", message: { content: [{ type: "tool_use", id: "t1", name: "Read", input: { file_path: "/a" } }] } } as any,
    SID,
    scratch,
  );
  const out = normalize(
    { type: "user", message: { content: [{ type: "tool_result", tool_use_id: "t1", content: "ok" }] } } as any,
    SID,
    scratch,
  );
  const p = out.events[0].params.payload as any;
  assert.equal("approval" in p, false, "additive only — the common path is untouched");
});

test("two identical calls in flight: the decision lands on the OLDER one", () => {
  // Approvals are serialized per session, so only one call of a given identity
  // can be awaiting a decision — the oldest open one is that call.
  const scratch: any = {};
  normalize(
    {
      type: "assistant",
      message: {
        content: [
          { type: "tool_use", id: "t1", name: "Bash", input: { command: "ls" } },
          { type: "tool_use", id: "t2", name: "Bash", input: { command: "ls" } },
        ],
      },
    } as any,
    SID,
    scratch,
  );
  recordApprovalChoice(scratch, "Bash", { command: "ls" }, "once");
  assert.equal(scratch.approvalChoices.get("t1"), "once");
  assert.equal(scratch.approvalChoices.has("t2"), false);
});

test("an unserializable tool input degrades to a name-only identity instead of throwing", () => {
  const cyclic: any = { a: 1 };
  cyclic.self = cyclic;
  assert.equal(toolIdentity("Bash", cyclic), "Bash ");
});
