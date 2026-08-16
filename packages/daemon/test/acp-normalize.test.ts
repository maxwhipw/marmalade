import { test } from "node:test";
import assert from "node:assert/strict";
import { normalizeAcp } from "../dist/acp-normalize.js";
import { normalize } from "../dist/normalize.js";

const SID = "s_test";

test("ACP agent_message_chunk → message.delta (same event as the Claude path)", () => {
  const out = normalizeAcp({ sessionUpdate: "agent_message_chunk", content: { type: "text", text: "hello" } } as any, SID);
  assert.equal(out.events[0].params.type, "message.delta");
  assert.equal((out.events[0].params.payload as any).text, "hello");
});

test("ACP agent_thought_chunk → thinking.delta", () => {
  const out = normalizeAcp({ sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "hmm" } } as any, SID);
  assert.equal(out.events[0].params.type, "thinking.delta");
});

test("ACP tool_call → tool.start; tool_call_update completed → tool.complete", () => {
  const start = normalizeAcp({ sessionUpdate: "tool_call", toolCallId: "t1", title: "read_file" } as any, SID);
  assert.equal(start.events[0].params.type, "tool.start");
  const done = normalizeAcp({ sessionUpdate: "tool_call_update", toolCallId: "t1", status: "completed" } as any, SID);
  assert.equal(done.events[0].params.type, "tool.complete");
});

test("ACP usage_update surfaces token counts for the meter", () => {
  const out = normalizeAcp({ sessionUpdate: "usage_update", usage: { inputTokens: 500, outputTokens: 120 } } as any, SID);
  assert.deepEqual(out.usage, { inputTokens: 500, outputTokens: 120 });
});

test("SEAM NEUTRALITY: Claude and OpenCode assistant text both normalize to message.delta", () => {
  // Two entirely different harness message formats → the SAME gateway event.
  const claude = normalize(
    { type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: "X" } } } as any,
    SID,
  );
  const opencode = normalizeAcp({ sessionUpdate: "agent_message_chunk", content: { type: "text", text: "X" } } as any, SID);
  assert.equal(claude.events[0].params.type, opencode.events[0].params.type);
  assert.equal(claude.events[0].params.type, "message.delta");
  assert.equal((claude.events[0].params.payload as any).text, (opencode.events[0].params.payload as any).text);
});

test("unknown ACP updates are ignored (forward-compatible)", () => {
  const out = normalizeAcp({ sessionUpdate: "current_mode_update" } as any, SID);
  assert.equal(out.events.length, 0);
});
