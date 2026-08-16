// renderSessionTurns (session-digest.ts): pure turn rendering for the
// get_session_turns tool — turn splitting, opt-in tool/thinking, caps.

import { test } from "node:test";
import assert from "node:assert/strict";
import { renderSessionTurns } from "../dist/session-digest.js";

const ev = (type: string, payload: Record<string, unknown>) =>
  ({ jsonrpc: "2.0", method: "event", params: { type, payload, session_id: "s" } }) as any;

function conversation() {
  return [
    ev("message.user", { message_id: "u1", seq: 1, ts: 1000, text: "first question", origin: { source: "text", device_id: "d" } }),
    ev("message.start", { message_id: "a1", seq: 2 }),
    ev("thinking.delta", { message_id: "a1", seq: 3, text: "hmm, let me think" }),
    ev("message.delta", { message_id: "a1", seq: 4, text: "first " }),
    ev("message.delta", { message_id: "a1", seq: 5, text: "answer" }),
    ev("tool.start", { message_id: "a1", seq: 6, id: "t1", name: "Bash", input: { command: "ls -la" } }),
    ev("message.complete", { message_id: "a1", seq: 7 }),
    ev("message.user", { message_id: "u2", seq: 8, ts: 2000, text: "second question", origin: { source: "voice", device_id: "d" } }),
    ev("message.user", { message_id: "u3", seq: 9, ts: 2100, text: "mid-turn nudge", steered: true, origin: { source: "text", device_id: "d" } }),
    ev("message.delta", { message_id: "a2", seq: 10, text: "second answer" }),
    ev("message.complete", { message_id: "a2", seq: 11 }),
  ];
}

test("default view: user + assistant only, turns split at non-steer user messages", () => {
  const out = renderSessionTurns(conversation(), { turns: 10, includeToolCalls: false, includeThinking: false });
  assert.match(out, /\[user\] first question/);
  assert.match(out, /\[assistant\] first answer/);
  assert.match(out, /\[steer\] mid-turn nudge/);
  assert.match(out, /\[assistant\] second answer/);
  assert.ok(!out.includes("[tool]"), "tools excluded by default");
  assert.ok(!out.includes("[thinking]"), "thinking excluded by default");
  assert.equal(out.split("--- turn").length - 1, 2, "two turns rendered");
  assert.match(out, /via voice/, "non-text source annotated on the turn header");
});

test("turns limit keeps only the tail", () => {
  const out = renderSessionTurns(conversation(), { turns: 1, includeToolCalls: false, includeThinking: false });
  assert.ok(!out.includes("first question"));
  assert.match(out, /second question/);
});

test("tool calls and thinking render on opt-in", () => {
  const out = renderSessionTurns(conversation(), { turns: 10, includeToolCalls: true, includeThinking: true });
  assert.match(out, /\[tool\] Bash .*ls -la/);
  assert.match(out, /\[thinking\] hmm, let me think/);
});

test("message.complete text is the fallback when no deltas were cached", () => {
  const events = [
    ev("message.user", { message_id: "u1", seq: 1, ts: 1, text: "q", origin: { source: "text" } }),
    ev("message.complete", { message_id: "a1", seq: 2, text: "full final answer" }),
  ];
  const out = renderSessionTurns(events, { turns: 5, includeToolCalls: false, includeThinking: false });
  assert.match(out, /\[assistant\] full final answer/);
});

test("oversized blocks are truncated with a marker; empty session renders empty", () => {
  const events = [
    ev("message.user", { message_id: "u1", seq: 1, ts: 1, text: "q", origin: { source: "text" } }),
    ev("message.delta", { message_id: "a1", seq: 2, text: "x".repeat(5000) }),
    ev("message.complete", { message_id: "a1", seq: 3 }),
  ];
  const out = renderSessionTurns(events, { turns: 5, includeToolCalls: false, includeThinking: false });
  assert.match(out, /\(\+\d+ chars\)/);
  assert.ok(out.length < 3000);
  assert.equal(renderSessionTurns([], { turns: 3, includeToolCalls: false, includeThinking: false }), "");
});
