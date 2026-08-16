import { test } from "node:test";
import assert from "node:assert/strict";
import { approvalBridge } from "../dist/claude-code-adapter.js";
import { toolIdentity } from "../dist/normalize.js";

// The Claude adapter's canUseTool bridge (M2): Decision → SDK shape mapping
// + the structural allowlist for marmalade-internal MCP tools.

test("internal marmalade tools are allowed STRUCTURALLY — the callback is never consulted", async () => {
  let called = false;
  const bridge = approvalBridge({ requestApproval: async () => { called = true; return { behavior: "deny", message: "no" }; } } as any, () => {});
  const r = await bridge("mcp__marmalade__update_session_summary", { summary: "x" }, {} as any);
  assert.equal(r.behavior, "allow");
  const r2 = await bridge("mcp__marmalade__list_devices", {}, {} as any);
  assert.equal(r2.behavior, "allow");
  assert.equal(called, false);
});

test("allow decision maps to allow+updatedInput; deny maps to deny+message", async () => {
  const bridge = approvalBridge({
    requestApproval: async (info: any) =>
      info.toolName === "Bash" ? { behavior: "deny", message: "Denied by user from android device" } : { behavior: "allow" },
  } as any, () => {});
  const denied = await bridge("Bash", { command: "rm -rf /" }, {} as any);
  assert.deepEqual(denied, { behavior: "deny", message: "Denied by user from android device" });
  const allowed = await bridge("Read", { file_path: "/tmp/x" }, {} as any);
  assert.deepEqual(allowed, { behavior: "allow", updatedInput: { file_path: "/tmp/x" } });
});

test("no callback (M1 behavior / tests): everything allows with a log line", async () => {
  const lines: string[] = [];
  const bridge = approvalBridge({} as any, (l) => lines.push(l));
  const r = await bridge("Bash", { command: "ls" }, {} as any);
  assert.equal(r.behavior, "allow");
  assert.ok(lines[0].includes("auto-approved"));
});

// ── AskUserQuestion → clarify seam ──────────────────────────────────────────

const ASK_INPUT = {
  questions: [{
    question: "Which auth method?",
    header: "Auth",
    options: [
      { label: "OAuth", description: "Standard flow" },
      { label: "Token", description: "Static bearer" },
    ],
    multiSelect: false,
  }],
};

test("AskUserQuestion routes to requestClarify — answers merge into updatedInput, approvals never consulted", async () => {
  let approvalCalled = false;
  let clarified: any = null;
  const bridge = approvalBridge({
    requestApproval: async () => { approvalCalled = true; return { behavior: "allow" }; },
    requestClarify: async (qs: any) => {
      clarified = qs;
      return { answered: true, answers: { "Which auth method?": "OAuth" }, response: "prefer PKCE" };
    },
  } as any, () => {});
  const r: any = await bridge("AskUserQuestion", ASK_INPUT as any, {} as any);
  assert.equal(approvalCalled, false, "a question is not an approval");
  assert.deepEqual(clarified, ASK_INPUT.questions, "parsed questions reach the router seam");
  assert.equal(r.behavior, "allow");
  assert.deepEqual(r.updatedInput.answers, { "Which auth method?": "OAuth" });
  assert.equal(r.updatedInput.response, "prefer PKCE");
  assert.deepEqual(r.updatedInput.questions, ASK_INPUT.questions, "original input preserved");
});

test("AskUserQuestion unanswered → deny with the router's proceed-on-your-own message", async () => {
  const bridge = approvalBridge({
    requestClarify: async () => ({ answered: false, message: "The user could not answer (no one is connected) — proceed with your best judgment." }),
  } as any, () => {});
  const r: any = await bridge("AskUserQuestion", ASK_INPUT as any, {} as any);
  assert.equal(r.behavior, "deny");
  assert.match(r.message, /best judgment/);
});

test("AskUserQuestion with no requestClarify callback passes through untouched", async () => {
  const bridge = approvalBridge({} as any, () => {});
  const r: any = await bridge("AskUserQuestion", ASK_INPUT as any, {} as any);
  assert.equal(r.behavior, "allow");
  assert.deepEqual(r.updatedInput, ASK_INPUT);
});

test("malformed AskUserQuestion input parses defensively (no throw, empty questions)", async () => {
  let clarified: any = null;
  const bridge = approvalBridge({
    requestClarify: async (qs: any) => { clarified = qs; return { answered: false, message: "x" }; },
  } as any, () => {});
  await bridge("AskUserQuestion", { questions: "not-an-array" } as any, {} as any);
  assert.deepEqual(clarified, []);
  await bridge("AskUserQuestion", { questions: [{ options: null }] } as any, {} as any);
  assert.deepEqual(clarified, [{ question: "", header: "", options: [], multiSelect: false }]);
});

// ── recording the decision (2026-07-27) ─────────────────────────────────────
// approval.request/resolved are emitTransient — they drive the docked card and
// then vanish — so the fact that a human PERSONALLY allowed or denied a command
// left no trace in the transcript, on cold load, or in search. The choice is
// staged against the tool call that opened it and rides that call's
// tool.complete. The SDK's CanUseTool gets no tool_use_id, which is why the
// correlation goes through the scratch's open-tool-use list.

test("a human decision is staged against the open tool call it settles", async () => {
  const scratch: any = { openToolUses: [{ id: "toolu_1", identity: toolIdentity("Bash", { command: "rm -rf build" }) }] };
  const bridge = approvalBridge(
    { requestApproval: async () => ({ behavior: "allow", choice: "once" }) } as any,
    () => {},
    scratch,
  );
  await bridge("Bash", { command: "rm -rf build" }, {} as any);
  assert.equal(scratch.approvalChoices.get("toolu_1"), "once");
});

test("a deny is recorded too — a refusal is as much a decision as an approval", async () => {
  const scratch: any = { openToolUses: [{ id: "toolu_2", identity: toolIdentity("Write", { file_path: "/etc/passwd" }) }] };
  const bridge = approvalBridge(
    { requestApproval: async () => ({ behavior: "deny", message: "nope", choice: "deny" }) } as any,
    () => {},
    scratch,
  );
  await bridge("Write", { file_path: "/etc/passwd" }, {} as any);
  assert.equal(scratch.approvalChoices.get("toolu_2"), "deny");
});

test("NOTHING is staged when nobody was asked — auto-approve leaves no record", async () => {
  // The distinction the whole record depends on: "the user allowed this" and
  // "the daemon allowed this without asking" must not look the same.
  const scratch: any = { openToolUses: [{ id: "toolu_3", identity: toolIdentity("Bash", { command: "ls" }) }] };
  const bridge = approvalBridge(
    { requestApproval: async () => ({ behavior: "allow" }) } as any,
    () => {},
    scratch,
  );
  await bridge("Bash", { command: "ls" }, {} as any);
  assert.equal(scratch.approvalChoices, undefined);
});

test("a decision with no matching open call is dropped, not misattributed", async () => {
  const scratch: any = { openToolUses: [{ id: "toolu_4", identity: toolIdentity("Read", { file_path: "/a" }) }] };
  const bridge = approvalBridge(
    { requestApproval: async () => ({ behavior: "allow", choice: "once" }) } as any,
    () => {},
    scratch,
  );
  await bridge("Bash", { command: "different tool entirely" }, {} as any);
  assert.equal(scratch.approvalChoices, undefined);
});

test("the bridge still works with no scratch at all (adapters that don't thread one)", async () => {
  const bridge = approvalBridge(
    { requestApproval: async () => ({ behavior: "allow", choice: "once" }) } as any,
    () => {},
  );
  assert.equal((await bridge("Bash", { command: "ls" }, {} as any)).behavior, "allow");
});
