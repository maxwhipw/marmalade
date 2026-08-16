import { test } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync, mkdirSync, writeFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { DeviceStore } from "../dist/device-store.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { createRouter } from "../dist/router.js";
import { defaultConfig } from "../dist/config.js";
import { Gateway } from "../dist/gateway.js";
import { renderMainSystemPrompt, DEVICE_DISPOSITION, DYNAMIC_UI_CATALOG } from "../dist/behavior.js";

// P3 — the device roster: registry fed by hello, list_devices seam, per-turn
// origin injection, static disposition. Tested over persistent FILE dbs +
// failure injection per the locked guardrails.

function tmp(): string {
  const dir = join(tmpdir(), `mdev-${randomUUID()}`);
  mkdirSync(dir, { recursive: true });
  return dir;
}

test("device roster over a FILE db: upsert keeps first_seen, tracks latest declaration, survives reopen", () => {
  const dir = tmp();
  const dbPath = join(dir, "sessions.db");
  try {
    {
      const s = new SessionManager(dbPath);
      s.devices.touch("test-phone", "android", ["streaming"], 1000);
      // Re-hello later with an upgraded client: platform/capabilities/last_seen
      // reflect the latest declaration; first_seen is the original pairing.
      s.devices.touch("test-phone", "android", ["streaming", "stable-ids"], 2000);
      s.devices.touch("cli-marmalade", "cli", [], 1500);
      s.close();
    }
    const s2 = new SessionManager(dbPath);
    const roster = s2.devices.list();
    assert.equal(roster.length, 2);
    assert.equal(roster[0].deviceId, "test-phone", "most recently seen first");
    assert.equal(roster[0].firstSeen, 1000);
    assert.equal(roster[0].lastSeen, 2000);
    assert.deepEqual(roster[0].capabilities, ["streaming", "stable-ids"]);
    assert.equal(s2.devices.get("cli-marmalade")!.platform, "cli");
    s2.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("failure injection: a corrupt capabilities cell degrades that row, not the roster", () => {
  const db = new DatabaseSync(":memory:");
  const devices = new DeviceStore(db);
  devices.touch("good", "android", ["streaming"], 1000);
  devices.touch("bad", "cli", [], 2000);
  db.prepare(`UPDATE devices SET capabilities = 'not json {{' WHERE device_id = 'bad'`).run();
  const roster = devices.list();
  assert.equal(roster.length, 2, "corrupt row still listed");
  assert.deepEqual(roster.find((d) => d.deviceId === "bad")!.capabilities, []);
  assert.deepEqual(roster.find((d) => d.deviceId === "good")!.capabilities, ["streaming"]);
});

test("hello with a declared deviceId fires the gateway's onHello hook (the roster feed)", async () => {
  const port = 9700 + Math.floor(process.hrtime()[1] % 500);
  const cfg = { gatewayHosts: ["127.0.0.1"], gatewayPort: port } as any;
  const g = new Gateway(cfg, (async () => ({})) as any, "test", () => {});
  const seen: any[] = [];
  g.onHello = (conn) => seen.push({ deviceId: conn.deviceId, platform: conn.platform, capabilities: conn.capabilities });
  g.start();
  try {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/api/ws?token=t`);
    await once(ws, "open");
    await once(ws, "message"); // gateway.ready
    ws.send(JSON.stringify({ jsonrpc: "2.0", id: "h", method: "hello", params: {
      protocolVersion: 1,
      client: { name: "android", version: "1", capabilities: ["stable-ids"], deviceId: "test-phone", platform: "android" },
    } }));
    await once(ws, "message"); // hello result
    assert.equal(seen.length, 1);
    assert.equal(seen[0].deviceId, "test-phone");
    assert.equal(seen[0].platform, "android");
    assert.deepEqual(seen[0].capabilities, ["stable-ids"]);
    ws.close();
  } finally { await g.stop(); }
});

// A capturing fake adapter: records what the harness actually receives and
// exposes the listDevices seam the router wires up.
function routerHarness() {
  const dir = tmp();
  const sessions = SessionManager.inMemory();
  const sent: string[] = [];
  let listDevices: (() => any[]) | undefined;
  const adapter = {
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      listDevices = cb.listDevices;
      return {
        async send(prompt: string) {
          sent.push(prompt);
          cb.onActivity();
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1 }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts: new TranscriptCache(dir),
    usage: new UsageMeter(),
    adapter: adapter as any,
    today: () => "2026-07-11",
    now: () => 1000 + n,
    mintSessionId: () => `s_${++n}`,
    connectedDevices: () => new Set(["test-phone"]),
  });
  const conn = (deviceId?: string, platform?: string, tzOffset?: number) =>
    ({ ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [], deviceId, platform, tzOffset } as any);
  return { router, sessions, sent, conn, getListDevices: () => listDevices, cleanup: () => { rmSync(dir, { recursive: true, force: true }); } };
}

test("per-turn origin injection: the HARNESS sees the [turn origin] preamble; the transcript keeps the raw prompt", async () => {
  const h = routerHarness();
  try {
    const c = h.conn("test-phone", "android", 120);
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "open Spotify" }, c);
    assert.equal(h.sent.length, 1);
    assert.match(h.sent[0], /^\[turn origin — device "test-phone" \(android\), via text, sender local time \d\d:\d\d \(UTC\+02:00\)\]\n\nopen Spotify$/);
  } finally { h.cleanup(); }
});

test("a connection with no declared identity gets the local/unknown preamble, no local time", async () => {
  const h = routerHarness();
  try {
    const c = h.conn(); // legacy-ish: no hello identity
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, c);
    assert.match(h.sent[0], /^\[turn origin — device "local" \(unknown\), via text\]\n\nhi$/);
  } finally { h.cleanup(); }
});

test("listDevices seam: the roster reaches the adapter decorated with connected-right-now", async () => {
  const h = routerHarness();
  try {
    h.sessions.devices.touch("test-phone", "android", ["stable-ids"], 1000);
    h.sessions.devices.touch("cli-marmalade", "cli", [], 900);
    const c = h.conn("test-phone", "android");
    await h.router("session.create", {}, c);
    const roster = h.getListDevices()!();
    assert.equal(roster.length, 2);
    const phone = roster.find((d: any) => d.device_id === "test-phone");
    assert.equal(phone.connected, true, "test-phone has a live gateway connection");
    assert.equal(phone.platform, "android");
    assert.deepEqual(phone.capabilities, ["stable-ids"]);
    assert.equal(roster.find((d: any) => d.device_id === "cli-marmalade").connected, false);
  } finally { h.cleanup(); }
});

test("device disposition is appended to a real behavior spec, and ONLY to a real spec", () => {
  const dir = tmp();
  try {
    // No spec files: empty prompt → the router keeps the harness default
    // persona, so no disposition either.
    assert.equal(renderMainSystemPrompt(dir), "");
    writeFileSync(join(dir, "identity.md"), "You are marmalade.");
    const rendered = renderMainSystemPrompt(dir);
    assert.ok(rendered.startsWith("You are marmalade."));
    assert.ok(rendered.includes(DEVICE_DISPOSITION), "disposition rides the spec");
    assert.ok(rendered.includes(DYNAMIC_UI_CATALOG), "UI catalog rides the spec (dynamic-ui step 6)");
    // Nothing emits marmalade-ui until the spec teaches it: the catalog is
    // the ONLY teaching path, so an empty spec means no trees anywhere.
    assert.ok(DYNAMIC_UI_CATALOG.includes("```marmalade-ui"));
    // Static: two renders are byte-identical (cache-shared string).
    assert.equal(rendered, renderMainSystemPrompt(dir));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
