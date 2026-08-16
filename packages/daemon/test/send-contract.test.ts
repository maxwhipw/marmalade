import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";
import { mkdirSync, rmSync, writeFileSync, chmodSync } from "node:fs";
import { OpenCodeAdapter } from "../dist/opencode-adapter.js";

// The HarnessSession.send contract (adapter.ts): queue-and-return. send()
// settles on ACCEPTANCE, never turn completion; once accepted, completion is
// onResult + message.complete and failure is onError — exactly once, never
// also a send rejection. These tests drive the REAL OpenCodeAdapter against a
// minimal fake ACP agent (test/fixtures/fake-acp-agent.cjs) — the adapter that
// used to block send() until turn end and double-report failures.

const FIXTURE = join(dirname(fileURLToPath(import.meta.url)), "fixtures", "fake-acp-agent.cjs");

function harness(mode: string) {
  const cwd = join(tmpdir(), `msc-${randomUUID()}`);
  mkdirSync(cwd, { recursive: true });
  writeFileSync(join(cwd, "mode"), mode);
  chmodSync(FIXTURE, 0o755);
  const events: any[] = [];
  const results: any[] = [];
  const errors: Array<{ kind: string; message: string }> = [];
  const adapter = new OpenCodeAdapter({ path: process.env.PATH ?? "", opencodeBin: FIXTURE });
  const session = adapter.spawn(
    {
      principal: "owner", purpose: "coding", authClass: "subscription",
      origin: { channel: "test" } as any, cwd,
      authContext: { home: cwd, claudeConfigDir: cwd, authClass: "subscription" },
    } as any,
    { daemonSessionId: "s_test" },
    {
      onEvent: (ev: any) => events.push(ev),
      onHarnessSession: () => {},
      onResult: (r: any) => results.push(r),
      onActivity: () => {},
      onError: (kind: string, message: string) => errors.push({ kind, message }),
    },
  );
  const waitFor = async (pred: () => boolean, ms = 5000) => {
    const t0 = Date.now();
    while (!pred()) {
      if (Date.now() - t0 > ms) throw new Error("waitFor timed out");
      await new Promise((r) => setTimeout(r, 20));
    }
  };
  return {
    session, events, results, errors, waitFor,
    cleanup: async () => { await session.stop().catch(() => {}); rmSync(cwd, { recursive: true, force: true }); },
  };
}

test("send resolves on acceptance, BEFORE the turn completes; completion arrives via onResult", async () => {
  const h = harness("ok");
  try {
    await h.session.send("hello"); // must return without waiting out the 150ms turn
    assert.equal(h.results.length, 0, "send() must not block until turn end (queue-and-return)");
    await h.waitFor(() => h.results.length === 1);
    const complete = h.events.find((e) => e.params?.type === "message.complete");
    assert.ok(complete, "turn completion must emit message.complete");
    assert.equal(h.errors.length, 0);
  } finally { await h.cleanup(); }
});

test("a turn failure reports ONCE via onError and never rejects send", async () => {
  const h = harness("fail-turn");
  try {
    await h.session.send("hello"); // accepted — must NOT reject even though the turn will fail
    await h.waitFor(() => h.errors.length >= 1);
    // Give any duplicate reporters (init_failed + turn_failed + child paths)
    // time to fire, then assert the single-report contract.
    await new Promise((r) => setTimeout(r, 250));
    assert.equal(h.errors.length, 1, `expected exactly one error, got: ${JSON.stringify(h.errors)}`);
    assert.match(h.errors[0].message, /model backend unavailable/);
    assert.equal(h.results.length, 0, "a failed turn is not a result");
  } finally { await h.cleanup(); }
});

test("child death mid-turn: one error total; a later send REJECTS (cannot accept)", async () => {
  const h = harness("die");
  try {
    await h.session.send("hello");
    await h.waitFor(() => h.errors.length >= 1);
    await new Promise((r) => setTimeout(r, 250));
    assert.equal(h.errors.length, 1, `expected exactly one error, got: ${JSON.stringify(h.errors)}`);
    // Dead child = the prompt can no longer be ACCEPTED — this is the one
    // case where send itself rejects (visible RPC failure, like the Claude
    // adapter's closed PromptQueue).
    await assert.rejects(() => h.session.send("again"), /not running/);
  } finally { await h.cleanup(); }
});

test("deliberate stop() does not report child_exited as a failure", async () => {
  const h = harness("ok");
  try {
    await h.session.send("hello");
    await h.waitFor(() => h.results.length === 1);
    await h.session.stop();
    await new Promise((r) => setTimeout(r, 250)); // let the 'exit' handler fire
    assert.equal(h.errors.length, 0, `stop() must be silent, got: ${JSON.stringify(h.errors)}`);
  } finally { await h.cleanup(); }
});
