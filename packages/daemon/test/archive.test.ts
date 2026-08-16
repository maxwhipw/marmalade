// archive.test.ts — session.archive (additive, ratified 2026-07-23).
//
// Archived is daemon-backed shared list metadata: clients filter archived
// rows out of the main list. It is NEVER a behavior filter — an archived
// session still runs, resumes, and receives prompts. The main session
// cannot be archived (pinned home surface, same refusal class as delete).

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

function fakeAdapter() {
  return {
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send(rawPrompt: string) {
          cb.onActivity();
          const prompt = rawPrompt.replace(/^\[turn origin — [^\]]*\]\n\n/, "");
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.delta", payload: { text: `echo:${prompt}` }, session_id: opts.daemonSessionId } });
          cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: {}, session_id: opts.daemonSessionId } });
          cb.onResult({ subtype: "success", isError: false, totalCostUsd: 0, inputTokens: 1, outputTokens: 1, text: `echo:${prompt}` }, "test");
        },
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

function harness(sessions: SessionManager = SessionManager.inMemory()) {
  const dir = join(tmpdir(), `mar-${randomUUID()}`);
  const transcripts = new TranscriptCache(dir);
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    usage: new UsageMeter(),
    adapter: fakeAdapter() as any,
    today: () => "2026-07-24",
    now: () => 1000 + ++n,
    mintSessionId: () => `s_${++n}`,
  });
  const conn = () => {
    const sent: any[] = [];
    return { ws: { send: (s: string) => sent.push(JSON.parse(s)) }, principal: "owner", legacy: false, capabilities: [], _sent: sent } as any;
  };
  return { router, sessions, transcripts, conn, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test("session.archive round-trips; session.list rows carry archived; new sessions default false", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const a = (await h.router("session.create", {}, c)) as { session_id: string };
    const b = (await h.router("session.create", {}, c)) as { session_id: string };

    let list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    assert.equal(list.sessions.find((x) => x.session_id === a.session_id).archived, false, "default is false");

    const r = (await h.router("session.archive", { session_id: a.session_id, archived: true }, c)) as { archived: boolean };
    assert.equal(r.archived, true);

    list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    assert.equal(list.sessions.find((x) => x.session_id === a.session_id).archived, true, "archived on the list row");
    assert.equal(list.sessions.find((x) => x.session_id === b.session_id).archived, false, "sibling untouched");
    // An archived session stays LISTED — filtering is the client's job.
    assert.ok(list.sessions.some((x) => x.session_id === a.session_id), "archived rows still listed");

    // Idempotent re-set, then unarchive.
    await h.router("session.archive", { session_id: a.session_id, archived: true }, c);
    const r2 = (await h.router("session.archive", { session_id: a.session_id, archived: false }, c)) as { archived: boolean };
    assert.equal(r2.archived, false);
    list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    assert.equal(list.sessions.find((x) => x.session_id === a.session_id).archived, false);
  } finally { h.cleanup(); }
});

test("session.archive rejects unknown sessions and bad params", async () => {
  const h = harness();
  try {
    const c = h.conn();
    await assert.rejects(() => h.router("session.archive", { session_id: "nope", archived: true }, c), /unknown session/);
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    // archived is required and boolean — no truthy coercion on the wire.
    await assert.rejects(() => h.router("session.archive", { session_id: s.session_id }, c));
    await assert.rejects(() => h.router("session.archive", { session_id: s.session_id, archived: "yes" }, c));
  } finally { h.cleanup(); }
});

test("the main session cannot be archived", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const main = (await h.router("session.main", {}, c)) as { session_id: string };
    await assert.rejects(
      () => h.router("session.archive", { session_id: main.session_id, archived: true }, c),
      /cannot be archived/,
    );
    const list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    assert.equal(list.sessions.find((x) => x.session_id === main.session_id).archived, false);
  } finally { h.cleanup(); }
});

test("archived is metadata, not behavior: prompt.submit still works and the flag survives the turn", async () => {
  const h = harness();
  try {
    const c = h.conn();
    const s = (await h.router("session.create", {}, c)) as { session_id: string };
    await h.router("session.archive", { session_id: s.session_id, archived: true }, c);
    await h.router("prompt.submit", { session_id: s.session_id, prompt: "hi" }, c);
    const list = (await h.router("session.list", {}, c)) as { sessions: any[] };
    assert.equal(list.sessions.find((x) => x.session_id === s.session_id).archived, true, "turn did not clear the flag");
  } finally { h.cleanup(); }
});

test("archived persists across a daemon restart (file-backed reopen)", async () => {
  const dbPath = join(tmpdir(), `mar-db-${randomUUID()}.sqlite`);
  const h1 = harness(new SessionManager(dbPath));
  let sid: string;
  try {
    const c = h1.conn();
    const s = (await h1.router("session.create", {}, c)) as { session_id: string };
    sid = s.session_id;
    await h1.router("session.archive", { session_id: sid, archived: true }, c);
  } finally {
    h1.sessions.close();
    h1.cleanup();
  }
  const h2 = harness(new SessionManager(dbPath));
  try {
    assert.equal(h2.sessions.get(sid!)!.archived, true, "flag survived reopen");
  } finally {
    h2.sessions.close();
    h2.cleanup();
    rmSync(dbPath, { force: true });
  }
});

test("migration adds the archived column to a pre-archive db (defaults false)", () => {
  const dbPath = join(tmpdir(), `mar-old-${randomUUID()}.sqlite`);
  // A db created before the archived column existed: the pre-archive sessions
  // schema with a row already in it.
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
               VALUES ('s_old','owner','coding','fake','/tmp','owner','{}','idle',1,1,1)`).run();
  old.close();
  const mgr = new SessionManager(dbPath);
  try {
    const rec = mgr.get("s_old")!;
    assert.equal(rec.archived, false, "pre-existing rows read as unarchived");
    mgr.setArchived("s_old", true);
    assert.equal(mgr.get("s_old")!.archived, true, "column is writable after migration");
  } finally {
    mgr.close();
    rmSync(dbPath, { force: true });
  }
});
