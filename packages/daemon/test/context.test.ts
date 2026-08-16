// context.test.ts — persisted per-session context occupancy (additive,
// 2026-07-25).
//
// The daemon already computes occupancy per turn (normalize.ts::wireUsage, on
// the message.complete usage block) but it was transient: a client opening a
// COLD session had no number until the next turn finished. The turn result now
// stamps context_used/context_max on the session row and session.list decorates
// every row with those two plus a DERIVED context_percent.
//
// Invariants pinned here: stamped from harness-PUSHED usage only (never a read
// query); null means unknown and is never faked; percent needs both halves;
// session.clear resets to unknown; the stamp survives a restart; and a db
// created before the columns migrates cleanly.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";
import { createSessionSpec } from "../dist/policy.js";

/** A harness that reports whatever occupancy the test asks for. `undefined`
 *  context stands in for a harness that reports none at all (the ACP/OpenCode
 *  adapter): the result carries no contextUsed and nothing is stamped. */
function fakeAdapter(ctx?: { used?: number; max?: number }) {
  return {
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n\n/, "");
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({
            subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1,
            text: `echo:${prompt}`,
            ...(ctx?.used === undefined ? {} : { contextUsed: ctx.used }),
            ...(ctx?.max === undefined ? {} : { contextMax: ctx.max }),
          }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harness(ctx?: { used?: number; max?: number }, sessions: SessionManager = SessionManager.inMemory()) {
  const dir = join(tmpdir(), `mar-${randomUUID()}`);
  const transcripts = new TranscriptCache(dir);
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    usage: new UsageMeter(),
    adapter: fakeAdapter(ctx) as any,
    today: () => "2026-07-25",
    now: () => 1000 + ++n,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  return { router, sessions, transcripts, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

const rowFor = (list: any, id: string) => list.sessions.find((x: any) => x.session_id === id);

test("a completed turn stamps the row; session.list carries used/max and a derived percent", async () => {
  const h = harness({ used: 42_300, max: 200_000 });
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };

    // Never-ran: all three read null — unknown, never zero.
    let row = rowFor(await h.router("session.list", {}, c), s.session_id);
    assert.equal(row.context_used, null);
    assert.equal(row.context_max, null);
    assert.equal(row.context_percent, null);

    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, c);

    row = rowFor(await h.router("session.list", {}, c), s.session_id);
    assert.equal(row.context_used, 42_300);
    assert.equal(row.context_max, 200_000);
    // min(100, round(42_300 / 200_000 * 100)) = 21 — same formula the live
    // wire usage block uses, so cold and live numbers agree.
    assert.equal(row.context_percent, 21);
    // The record mirrors the wire (camelCase side).
    assert.equal(h.sessions.get(s.session_id)!.contextUsed, 42_300);
    assert.equal(h.sessions.get(s.session_id)!.contextMax, 200_000);
  } finally { h.cleanup(); }
});

test("a harness that reports no window stores used with a NULL max — percent stays null", async () => {
  const h = harness({ used: 9_000 });
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, c);
    const row = rowFor(await h.router("session.list", {}, c), s.session_id);
    assert.equal(row.context_used, 9_000);
    assert.equal(row.context_max, null);
    assert.equal(row.context_percent, null, "no window means no honest percentage — never faked");
  } finally { h.cleanup(); }
});

test("a harness that reports no occupancy at all (ACP/OpenCode) leaves the row null", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, c);
    const row = rowFor(await h.router("session.list", {}, c), s.session_id);
    assert.equal(row.context_used, null);
    assert.equal(row.context_max, null);
    assert.equal(row.context_percent, null);
  } finally { h.cleanup(); }
});

test("percent clamps at 100 when a turn overruns the reported window", async () => {
  const h = harness({ used: 250_000, max: 200_000 });
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, c);
    assert.equal(rowFor(await h.router("session.list", {}, c), s.session_id).context_percent, 100);
  } finally { h.cleanup(); }
});

test("session.clear resets occupancy to unknown (a pre-clear number would overstate)", async () => {
  const h = harness({ used: 42_300, max: 200_000 });
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, c);
    assert.equal(rowFor(await h.router("session.list", {}, c), s.session_id).context_used, 42_300);

    await h.router("session.clear", { session_id: s.session_id }, c);
    const row = rowFor(await h.router("session.list", {}, c), s.session_id);
    assert.equal(row.context_used, null);
    assert.equal(row.context_max, null);
    assert.equal(row.context_percent, null);
  } finally { h.cleanup(); }
});

test("the stamp survives a daemon restart (file-backed reopen)", async () => {
  const dbPath = join(tmpdir(), `mar-ctx-${randomUUID()}.sqlite`);
  const h1 = harness({ used: 42_300, max: 200_000 }, new SessionManager(dbPath));
  let sid: string;
  try {
    const c = h1.conn();
    const s = (await h1.router("session.create", {}, c)) as { session_id: string };
    sid = s.session_id;
    await h1.router("prompt.submit", { session_id: sid, prompt: "hi" }, c);
  } finally {
    h1.sessions.close();
    h1.cleanup();
  }
  const h2 = harness({ used: 1, max: 2 }, new SessionManager(dbPath));
  try {
    const rec = h2.sessions.get(sid!)!;
    assert.equal(rec.contextUsed, 42_300, "occupancy survived the reopen");
    assert.equal(rec.contextMax, 200_000);
    const row = rowFor(await h2.router("session.list", {}, h2.conn()), sid!);
    assert.equal(row.context_percent, 21, "cold open shows context with no turn run");
  } finally {
    h2.sessions.close();
    h2.cleanup();
    rmSync(dbPath, { force: true });
  }
});

test("setContext stores an absent max as NULL, and a later turn overwrites both", () => {
  const mgr = SessionManager.inMemory();
  try {
    mgr.create("s1", createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, defaultConfig()), "claude-code", 1000);
    assert.equal(mgr.get("s1")!.contextUsed, null, "fresh sessions are unknown");
    mgr.setContext("s1", 9_000);
    assert.equal(mgr.get("s1")!.contextUsed, 9_000);
    assert.equal(mgr.get("s1")!.contextMax, null);
    mgr.setContext("s1", 12_000, 200_000);
    assert.equal(mgr.get("s1")!.contextUsed, 12_000);
    assert.equal(mgr.get("s1")!.contextMax, 200_000);
  } finally { mgr.close(); }
});

test("migration adds the context columns to a pre-context db (rows read as unknown)", () => {
  const dbPath = join(tmpdir(), `mar-old-ctx-${randomUUID()}.sqlite`);
  // A db created before the context columns existed, with a row already in it.
  const old = new DatabaseSync(dbPath);
  old.exec(`
    CREATE TABLE sessions (
      id TEXT PRIMARY KEY, principal TEXT NOT NULL, purpose TEXT NOT NULL,
      harness TEXT NOT NULL, harness_session_id TEXT, cwd TEXT NOT NULL,
      auth_class TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL,
      lifecycle TEXT NOT NULL DEFAULT 'active',
      run_state TEXT NOT NULL DEFAULT 'starting',
      created_at INTEGER NOT NULL, last_active INTEGER NOT NULL,
      last_heartbeat INTEGER NOT NULL
    );
  `);
  old.prepare(`INSERT INTO sessions (id, principal, purpose, harness, cwd, auth_class, origin, status, created_at, last_active, last_heartbeat)
               VALUES ('s_old','max','coding','fake','/tmp','owner','{}','idle',1,1,1)`).run();
  old.close();
  const mgr = new SessionManager(dbPath);
  try {
    assert.equal(mgr.get("s_old")!.contextUsed, null, "pre-existing rows read as unknown");
    assert.equal(mgr.get("s_old")!.contextMax, null);
    mgr.setContext("s_old", 5_000, 200_000);
    assert.equal(mgr.get("s_old")!.contextUsed, 5_000, "columns are writable after migration");
    assert.equal(mgr.get("s_old")!.contextMax, 200_000);
  } finally {
    mgr.close();
    rmSync(dbPath, { force: true });
  }
});
