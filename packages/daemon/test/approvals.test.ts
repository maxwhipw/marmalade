import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";

// Digital-twin harness for M2 approvals: a fake adapter that CAPTURES the
// requestApproval callback so tests can drive the tool-approval seam exactly
// the way canUseTool / requestPermission do.
function harness() {
  const dir = join(tmpdir(), `apr-${randomUUID()}`);
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const spawned: any[] = []; // captured AdapterCallbacks per spawn
  let n = 0;
  const router = createRouter({
    cfg: { ...defaultConfig(), approvalsMode: "auto" },
    sessions,
    transcripts,
    usage: new UsageMeter(),
    adapter: {
      name: "fake",
      spawn(_spec: any, _opts: any, cb: any) {
        spawned.push(cb);
        return { send: async () => {}, interrupt: async () => {}, stop: async () => {} };
      },
    } as any,
    today: () => "2026-07-12",
    now: () => 1000 + n++,
    mintSessionId: () => `s_${spawned.length + 1}`,
  });
  const conn = (platform = "android") => {
    const sent: any[] = [];
    return {
      ws: { send: (s: string) => sent.push(JSON.parse(s)) },
      principal: "owner", legacy: false, capabilities: [],
      authenticated: true, deviceIdVerified: false, platform,
      _sent: sent,
    } as any;
  };
  return { router, sessions, transcripts, spawned, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

function eventsOf(conn: any, type: string): any[] {
  return conn._sent.filter((f: any) => f.method === "event" && f.params?.type === type).map((f: any) => f.params.payload);
}

test("auto mode (global default): tool calls allow immediately, nothing emitted", async () => {
  const h = harness();
  try {
    const c = h.conn();
    await h.router("session.create", {}, c);
    const decision = await h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "ls -la" } });
    assert.deepEqual(decision, { behavior: "allow" });
    assert.equal(eventsOf(c, "approval.request").length, 0);
  } finally { h.cleanup(); }
});

test("prompt mode parks: approval.request emitted, awaiting_input, once → allow + approval.resolved", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { approvals: "prompt" }, c)) as any;
    const decisionP = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "rm -rf build" } });
    await new Promise((r) => setImmediate(r));

    const reqs = eventsOf(c, "approval.request");
    assert.equal(reqs.length, 1);
    assert.ok(reqs[0].request_id);
    assert.equal(reqs[0].tool_name, "Bash");
    assert.equal(reqs[0].command, "rm -rf build");
    assert.equal(reqs[0].pattern_key, "Bash:rm");
    assert.equal(reqs[0].allow_permanent, false, "'always' not offered in v1");
    assert.ok(typeof reqs[0].seq === "number", "transient events still get seq/ts");
    assert.equal(h.sessions.get(session_id)!.runState, "awaiting_input");
    // The transient request is NOT in the transcript cache (decision 6).
    const cached = h.transcripts.replay(session_id).filter((e: any) => e.params.type === "approval.request");
    assert.equal(cached.length, 0);

    const r = (await h.router("approval.respond", { session_id, choice: "once" }, c)) as any;
    assert.equal(r.resolved, true);
    assert.deepEqual(await decisionP, { behavior: "allow", choice: "once" });
    assert.equal(h.sessions.get(session_id)!.runState, "running");
    const resolved = eventsOf(c, "approval.resolved");
    assert.equal(resolved.length, 1);
    assert.equal(resolved[0].request_id, reqs[0].request_id);
    assert.equal(resolved[0].choice, "once");
  } finally { h.cleanup(); }
});

test("deny resolves with a device-attributed message", async () => {
  const h = harness();
  try {
    const c = h.conn("android");
    const { session_id } = (await h.router("session.create", { approvals: "prompt" }, c)) as any;
    const decisionP = h.spawned[0].requestApproval({ toolName: "Write", input: { file_path: "/etc/passwd" } });
    await new Promise((r) => setImmediate(r));
    await h.router("approval.respond", { session_id, choice: "deny" }, c);
    assert.deepEqual(await decisionP, { behavior: "deny", message: "Denied by user from android device", choice: "deny" });
  } finally { h.cleanup(); }
});

test("choice=session remembers the pattern_key: repeat calls skip the prompt", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { approvals: "prompt" }, c)) as any;
    const first = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "git status" } });
    await new Promise((r) => setImmediate(r));
    await h.router("approval.respond", { session_id, choice: "session" }, c);
    assert.deepEqual(await first, { behavior: "allow", choice: "session" });

    // Same pattern (Bash:git) — no new approval.request.
    const second = await h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "git diff HEAD" } });
    // No choice: the pattern allowlist decided, the user was never asked. That
    // distinction is what the transcript record depends on.
    assert.deepEqual(second, { behavior: "allow" });
    assert.equal(eventsOf(c, "approval.request").length, 1);

    // Different pattern (Bash:rm) prompts again.
    const third = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "rm x" } });
    await new Promise((r) => setImmediate(r));
    assert.equal(eventsOf(c, "approval.request").length, 2);
    await h.router("approval.respond", { session_id, choice: "deny" }, c);
    assert.equal((await third).behavior, "deny");
  } finally { h.cleanup(); }
});

test("SERIALIZATION: a second concurrent request parks behind the first; responds resolve in order", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { approvals: "prompt" }, c)) as any;
    const first = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "one" } });
    const second = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "two" } });
    await new Promise((r) => setImmediate(r));

    // Only the FIRST request is visible until it resolves.
    assert.equal(eventsOf(c, "approval.request").length, 1);
    assert.equal(eventsOf(c, "approval.request")[0].command, "one");

    await h.router("approval.respond", { session_id, choice: "once" }, c);
    assert.deepEqual(await first, { behavior: "allow", choice: "once" });
    await new Promise((r) => setImmediate(r));

    // NOW the second one is emitted.
    const reqs = eventsOf(c, "approval.request");
    assert.equal(reqs.length, 2);
    assert.equal(reqs[1].command, "two");
    await h.router("approval.respond", { session_id, choice: "deny" }, c);
    assert.equal((await second).behavior, "deny");
  } finally { h.cleanup(); }
});

test("unattended fallback: no subscribers → auto-approve-with-log (headless must not hang)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    await h.router("session.create", { approvals: "prompt" }, c);
    (h.router as any).disconnect(c); // creator detaches → zero subscribers
    const decision = await h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "cron job" } });
    assert.deepEqual(decision, { behavior: "allow" });
  } finally { h.cleanup(); }
});

test("last subscriber detaching MID-PARK resolves parked approvals as allow (run survives)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    await h.router("session.create", { approvals: "prompt" }, c);
    const parked = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "long thing" } });
    await new Promise((r) => setImmediate(r));
    assert.equal(eventsOf(c, "approval.request").length, 1);
    (h.router as any).disconnect(c);
    assert.deepEqual(await parked, { behavior: "allow" });
  } finally { h.cleanup(); }
});

test("mid-park subscribe re-emits the pending approval.request verbatim (same seq)", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { approvals: "prompt" }, c)) as any;
    const parked = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "thing" } });
    await new Promise((r) => setImmediate(r));
    const original = eventsOf(c, "approval.request")[0];

    const c2 = h.conn();
    await h.router("session.subscribe", { session_id, since_seq: 0 }, c2);
    const reEmitted = eventsOf(c2, "approval.request");
    assert.equal(reEmitted.length, 1);
    assert.deepEqual(reEmitted[0], original, "same frame, no re-stamp");

    // Either device can respond; c2 does.
    await h.router("approval.respond", { session_id, choice: "once" }, c2);
    assert.deepEqual(await parked, { behavior: "allow", choice: "once" });
    // Both devices see approval.resolved so cards clear everywhere.
    assert.equal(eventsOf(c, "approval.resolved").length, 1);
    assert.equal(eventsOf(c2, "approval.resolved").length, 1);
  } finally { h.cleanup(); }
});

test("session.approvals flips a RUNNING session's mode; session.list exposes the effective mode", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", {}, c)) as any;
    let list = (await h.router("session.list", {}, c)) as any;
    assert.equal(list.sessions[0].approvals, "auto", "global default");

    await h.router("session.approvals", { session_id, mode: "prompt" }, c);
    list = (await h.router("session.list", {}, c)) as any;
    assert.equal(list.sessions[0].approvals, "prompt");

    // The flip gates the NEXT tool call.
    const parked = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "x" } });
    await new Promise((r) => setImmediate(r));
    assert.equal(eventsOf(c, "approval.request").length, 1);
    await h.router("approval.respond", { session_id, choice: "once" }, c);
    await parked;
  } finally { h.cleanup(); }
});

test("approval.respond with no pending request errors; request_id targets explicitly", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { approvals: "prompt" }, c)) as any;
    await assert.rejects(h.router("approval.respond", { session_id, choice: "once" }, c), /no matching pending approval/);

    const parked = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "x" } });
    await new Promise((r) => setImmediate(r));
    const rid = eventsOf(c, "approval.request")[0].request_id;
    await assert.rejects(
      h.router("approval.respond", { session_id, choice: "once", request_id: "wrong-id" }, c),
      /no matching pending approval/,
    );
    await h.router("approval.respond", { session_id, choice: "once", request_id: rid }, c);
    assert.deepEqual(await parked, { behavior: "allow", choice: "once" });
  } finally { h.cleanup(); }
});

test("session.stop denies parked approvals so canUseTool promises never leak", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const { session_id } = (await h.router("session.create", { approvals: "prompt" }, c)) as any;
    const parked = h.spawned[0].requestApproval({ toolName: "Bash", input: { command: "x" } });
    await new Promise((r) => setImmediate(r));
    await h.router("session.stop", { session_id }, c);
    assert.equal((await parked).behavior, "deny");
  } finally { h.cleanup(); }
});
