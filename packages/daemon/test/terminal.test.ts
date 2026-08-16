// terminal.test.ts — TerminalManager against REAL PTYs (node-pty on Linux).
// The manager is exercised directly (the router cases are thin parse+delegate
// wrappers; the live end-to-end check covers them). bash --norc --noprofile
// keeps spawns deterministic — no user profile, no login-shell latency.

import test from "node:test";
import assert from "node:assert/strict";
import { TerminalManager, loadPtyModule, type PtyModule } from "../src/terminal.ts";
import { defaultConfig } from "../src/config.ts";
import type { Connection } from "../src/gateway.ts";

// One shared pty module load for the file (the native module is a singleton).
const ptyModule: PtyModule | null = await loadPtyModule();
assert.ok(ptyModule, "node-pty must load on this host (it built at install time)");

interface FakeConn {
  conn: Connection;
  frames: { type: string; payload: Record<string, unknown> }[];
  /** Concatenated decoded terminal.data bytes for a terminal id. */
  output(terminalId: string): string;
  bufferedAmount: number;
}

function fakeConn(): FakeConn {
  const frames: FakeConn["frames"] = [];
  const self: FakeConn = {
    frames,
    bufferedAmount: 0,
    conn: {
      ws: {
        send: (raw: string) => {
          const f = JSON.parse(raw) as { params?: { type?: string; payload?: Record<string, unknown> } };
          if (f.params?.type) frames.push({ type: f.params.type, payload: f.params.payload ?? {} });
        },
        get bufferedAmount() { return self.bufferedAmount; },
      },
      principal: "owner", legacy: false, capabilities: [],
      authenticated: true, deviceIdVerified: true, deviceId: "test-device",
    } as unknown as Connection,
    output(terminalId: string): string {
      return frames
        .filter((f) => f.type === "terminal.data" && f.payload.terminal_id === terminalId)
        .map((f) => Buffer.from(String(f.payload.data_b64), "base64").toString("utf8"))
        .join("");
    },
  };
  return self;
}

function manager(opts: Partial<ConstructorParameters<typeof TerminalManager>[1]> = {}): TerminalManager {
  return new TerminalManager(ptyModule!, {
    now: () => Date.now(),
    shell: "/bin/bash",
    shellArgs: ["--norc", "--noprofile"],
    ...opts,
  });
}

async function waitFor(cond: () => boolean, what: string, timeoutMs = 5000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (cond()) return;
    await new Promise((r) => setTimeout(r, 25));
  }
  assert.fail(`timed out waiting for: ${what}`);
}

const b64 = (s: string) => Buffer.from(s, "utf8").toString("base64");

test("create → input → output round-trip reaches the attached connection", async () => {
  const m = manager();
  const c = fakeConn();
  try {
    const info = m.create({ cols: 80, rows: 24 }, c.conn);
    assert.equal(info.shell, "bash");
    assert.ok(info.pid > 0);
    m.input(info.terminal_id, b64("echo terminal-round$(echo trip)\r"));
    await waitFor(() => c.output(info.terminal_id).includes("terminal-roundtrip"), "echo output");
  } finally {
    m.stopAll();
  }
});

test("attach returns a snapshot carrying earlier output; late attacher then streams live", async () => {
  const m = manager();
  const creator = fakeConn();
  try {
    const info = m.create({ cols: 80, rows: 24 }, creator.conn);
    m.input(info.terminal_id, b64("echo before-$(echo attach)\r"));
    await waitFor(() => creator.output(info.terminal_id).includes("before-attach"), "pre-attach output");

    const late = fakeConn();
    const { snapshot_b64 } = m.attach(info.terminal_id, late.conn);
    const snapshot = Buffer.from(snapshot_b64, "base64").toString("utf8");
    assert.ok(snapshot.includes("before-attach"), "snapshot must carry scrollback");
    // The late attacher must NOT have received the old output as events…
    assert.equal(late.output(info.terminal_id), "");
    // …but does stream from now on.
    m.input(info.terminal_id, b64("echo after-$(echo attach)\r"));
    await waitFor(() => late.output(info.terminal_id).includes("after-attach"), "post-attach stream");
  } finally {
    m.stopAll();
  }
});

test("detach and disconnect stop delivery; the shell keeps running", async () => {
  const m = manager();
  const a = fakeConn();
  const b = fakeConn();
  try {
    const info = m.create({ cols: 80, rows: 24 }, a.conn);
    m.attach(info.terminal_id, b.conn);
    m.detach(info.terminal_id, a.conn);   // explicit detach
    m.disconnect(b.conn);                  // gateway close path
    const before = { a: a.frames.length, b: b.frames.length };
    m.input(info.terminal_id, b64("echo silent-$(echo check)\r"));
    // The output still lands in the ring buffer (shell alive)…
    await waitFor(() => {
      const snap = Buffer.from(m.attach(info.terminal_id, fakeConn().conn).snapshot_b64, "base64").toString("utf8");
      return snap.includes("silent-check");
    }, "shell alive after detach");
    // …but neither detached connection saw a new frame.
    assert.equal(a.frames.length, before.a);
    assert.equal(b.frames.length, before.b);
    assert.equal(m.size(), 1);
  } finally {
    m.stopAll();
  }
});

test("close kills the shell: attached get terminal.exit and the roster empties", async () => {
  const m = manager();
  const c = fakeConn();
  const info = m.create({ cols: 80, rows: 24 }, c.conn);
  m.close(info.terminal_id);
  await waitFor(
    () => c.frames.some((f) => f.type === "terminal.exit" && f.payload.terminal_id === info.terminal_id),
    "terminal.exit event",
  );
  assert.equal(m.size(), 0);
  m.stopAll();
});

test("shell exit (typing `exit`) also emits terminal.exit with code 0", async () => {
  const m = manager();
  const c = fakeConn();
  const info = m.create({ cols: 80, rows: 24 }, c.conn);
  m.input(info.terminal_id, b64("exit 0\r"));
  await waitFor(
    () => c.frames.some((f) => f.type === "terminal.exit" && f.payload.exit_code === 0),
    "clean exit event",
  );
  assert.equal(m.size(), 0);
  m.stopAll();
});

test("terminal cap is enforced with a visible error", () => {
  const m = manager({ maxTerminals: 1 });
  try {
    m.create({ cols: 80, rows: 24 }, null);
    assert.throws(() => m.create({ cols: 80, rows: 24 }, null), /terminal cap reached/);
  } finally {
    m.stopAll();
  }
});

test("scrollback ring evicts from the front — snapshot stays under the cap", async () => {
  const m = manager({ scrollbackBytes: 2048 });
  const c = fakeConn();
  try {
    const info = m.create({ cols: 80, rows: 24 }, c.conn);
    m.input(info.terminal_id, b64("for i in $(seq 1 200); do echo padding-line-$i; done; echo ring-done\r"));
    await waitFor(() => c.output(info.terminal_id).includes("ring-done"), "loop output");
    const snap = Buffer.from(m.attach(info.terminal_id, fakeConn().conn).snapshot_b64, "base64");
    // Cap plus at most one chunk of overshoot (whole-chunk eviction).
    assert.ok(snap.length <= 2048 + 64 * 1024, `snapshot ${snap.length} should be near the cap`);
    assert.ok(snap.toString("utf8").includes("ring-done"), "newest output survives eviction");
    assert.ok(!snap.toString("utf8").includes("padding-line-1\r"), "oldest output evicted");
  } finally {
    m.stopAll();
  }
});

test("oversized input is refused; unknown ids error cleanly", () => {
  const m = manager();
  try {
    const info = m.create({ cols: 80, rows: 24 }, null);
    assert.throws(
      () => m.input(info.terminal_id, Buffer.alloc(1024 * 1024 + 1).toString("base64")),
      /input too large/,
    );
    assert.throws(() => m.input("t_nope", b64("x")), /unknown terminal/);
    assert.throws(() => m.attach("t_nope", fakeConn().conn), /unknown terminal/);
    assert.throws(() => m.resize("t_nope", 80, 24), /unknown terminal/);
    assert.throws(() => m.close("t_nope"), /unknown terminal/);
  } finally {
    m.stopAll();
  }
});

test("resize updates the roster row", () => {
  const m = manager();
  try {
    const info = m.create({ cols: 80, rows: 24 }, null);
    const r = m.resize(info.terminal_id, 120, 40);
    assert.deepEqual(r, { cols: 120, rows: 40 });
    assert.equal(m.list()[0].cols, 120);
  } finally {
    m.stopAll();
  }
});

test("config: terminal_enabled defaults on, file can kill-switch, env overrides", () => {
  assert.equal(defaultConfig({}).terminalEnabled, true);
  assert.equal(defaultConfig({ terminal_enabled: false }).terminalEnabled, false);
  process.env.MARMALADE_TERMINAL_ENABLED = "0";
  try {
    assert.equal(defaultConfig({ terminal_enabled: true }).terminalEnabled, false);
  } finally {
    delete process.env.MARMALADE_TERMINAL_ENABLED;
  }
});
