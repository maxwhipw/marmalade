// workspaces.test.ts — folder workspaces (Paseo-style grouping): store CRUD,
// home confinement, detection, and the derived workspace_id stamp on
// session.list. A workspace is metadata over cwd — delete un-groups, never
// touches sessions.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";
import { prettifyBasename, detectWorkspace, CONTEXT_FILE_CAP } from "../dist/workspace-store.js";
import { symlinkSync } from "node:fs";

function fakeAdapter() {
  return {
    name: "fake",
    spawn(_spec: any, opts: any, cb: any) {
      cb.onHarnessSession(`h-${opts.daemonSessionId}`);
      return {
        async send() {},
        async interrupt() {},
        async stop() {},
      };
    },
  };
}

/** A fake home dir with project folders inside; workspaces confine to it. */
function harness(extraDeps: Record<string, unknown> = {}) {
  const home = join(tmpdir(), `mws-${randomUUID()}`);
  mkdirSync(join(home, "coding", "proj-a"), { recursive: true });
  mkdirSync(join(home, "coding", "proj-a", "inner"), { recursive: true });
  mkdirSync(join(home, "coding", "proj-b"), { recursive: true });
  const sessions = SessionManager.inMemory({ workspaceHome: home });
  const transcripts = new TranscriptCache(join(home, ".transcripts"));
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(),
    sessions,
    transcripts,
    usage: new UsageMeter(),
    adapter: fakeAdapter() as any,
    today: () => "2026-07-18",
    now: () => 1000 + n,
    mintSessionId: () => `s_${++n}`,
    ...extraDeps,
  });
  const conn = () => ({
    ws: { send: () => {} },
    principal: "owner", legacy: false, capabilities: [],
  }) as any;
  return { router, sessions, home, conn, cleanup: () => rmSync(home, { recursive: true, force: true }) };
}

test("workspace.create defaults the name from a prettified basename and detects folder context", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const dir = join(h.home, "coding", "proj-a");
  writeFileSync(join(dir, "CLAUDE.md"), "# proj");
  mkdirSync(join(dir, ".memory"));
  writeFileSync(join(dir, ".memory", "one.md"), "note");
  writeFileSync(join(dir, ".memory", "two.md"), "note");
  writeFileSync(join(dir, ".memory", "not-a-note.txt"), "x");
  mkdirSync(join(dir, ".git"));
  writeFileSync(join(dir, ".git", "HEAD"), "ref: refs/heads/main\n");

  const r = (await h.router("workspace.create", { path: dir, emoji: "🍊" }, h.conn())) as any;
  assert.equal(r.workspace.name, "Proj A");
  assert.equal(r.workspace.path, dir);
  assert.equal(r.workspace.emoji, "🍊");
  assert.equal(r.workspace.detection.git_branch, "main");
  assert.equal(r.workspace.detection.has_claude_md, true);
  assert.equal(r.workspace.detection.has_agents_md, false);
  assert.equal(r.workspace.detection.memory_notes, 2);
});

test("workspace.create rejects paths outside home, missing folders, and duplicates", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  await assert.rejects(h.router("workspace.create", { path: "/etc" }, h.conn()), /outside home/);
  await assert.rejects(h.router("workspace.create", { path: join(h.home, "nope") }, h.conn()), /./);
  const dir = join(h.home, "coding", "proj-b");
  await h.router("workspace.create", { path: dir }, h.conn());
  await assert.rejects(h.router("workspace.create", { path: dir }, h.conn()), /already exists/);
});

test("session.list stamps workspace_id by cwd prefix — deepest match wins, unmatched is null", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const outer = join(h.home, "coding", "proj-a");
  const inner = join(outer, "inner");
  const wOuter = ((await h.router("workspace.create", { path: outer }, h.conn())) as any).workspace;
  const wInner = ((await h.router("workspace.create", { path: inner }, h.conn())) as any).workspace;

  const c = h.conn();
  const sOuter = (await h.router("session.create", { cwd: outer }, c)) as any;
  const sInner = (await h.router("session.create", { cwd: inner }, c)) as any;
  const sFree = (await h.router("session.create", { cwd: h.home }, c)) as any;

  const list = ((await h.router("session.list", {}, c)) as any).sessions;
  const byId = new Map(list.map((s: any) => [s.session_id, s.workspace_id]));
  assert.equal(byId.get(sOuter.session_id), wOuter.workspace_id);
  assert.equal(byId.get(sInner.session_id), wInner.workspace_id, "nested folder groups under the DEEPEST workspace");
  assert.equal(byId.get(sFree.session_id), null);

  // A sibling path sharing the prefix STRING must not match (prefix is
  // path-segment-aware, not string startsWith).
  mkdirSync(join(h.home, "coding", "proj-a-extra"));
  const sSib = (await h.router("session.create", { cwd: join(h.home, "coding", "proj-a-extra") }, c)) as any;
  const list2 = ((await h.router("session.list", {}, c)) as any).sessions;
  assert.equal(list2.find((s: any) => s.session_id === sSib.session_id).workspace_id, null);
});

test("workspace.update renames / re-emojis; path is immutable; delete un-groups without touching sessions", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const dir = join(h.home, "coding", "proj-b");
  const w = ((await h.router("workspace.create", { path: dir }, h.conn())) as any).workspace;

  const c = h.conn();
  const s = (await h.router("session.create", { cwd: dir }, c)) as any;

  const upd = (await h.router("workspace.update", { workspace_id: w.workspace_id, name: "Finance", emoji: "💰" }, c)) as any;
  assert.equal(upd.workspace.name, "Finance");
  assert.equal(upd.workspace.emoji, "💰");
  const cleared = (await h.router("workspace.update", { workspace_id: w.workspace_id, emoji: null }, c)) as any;
  assert.equal(cleared.workspace.emoji, null);
  assert.equal(cleared.workspace.name, "Finance", "emoji-only update keeps the name");

  const del = (await h.router("workspace.delete", { workspace_id: w.workspace_id }, c)) as any;
  assert.equal(del.deleted, true);
  const again = (await h.router("workspace.delete", { workspace_id: w.workspace_id }, c)) as any;
  assert.equal(again.deleted, false, "delete is idempotent");

  const list = ((await h.router("session.list", {}, c)) as any).sessions;
  const row = list.find((x: any) => x.session_id === s.session_id);
  assert.ok(row, "session survives workspace deletion");
  assert.equal(row.workspace_id, null, "…and is simply un-grouped");
});

test("workspace.list returns all workspaces sorted by name", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  await h.router("workspace.create", { path: join(h.home, "coding", "proj-b"), name: "Zeta" }, h.conn());
  await h.router("workspace.create", { path: join(h.home, "coding", "proj-a"), name: "Alpha" }, h.conn());
  const list = ((await h.router("workspace.list", {}, h.conn())) as any).workspaces;
  assert.deepEqual(list.map((w: any) => w.name), ["Alpha", "Zeta"]);
});

test("workspace.context returns capped file previews, memory names, and branch — symlink-out reads as absent", async (t) => {
  const h = harness();
  t.after(h.cleanup);
  const dir = join(h.home, "coding", "proj-a");
  writeFileSync(join(dir, "CLAUDE.md"), "x".repeat(CONTEXT_FILE_CAP + 100));
  // AGENTS.md is a symlink escaping home — must read as absent, not leak.
  writeFileSync("/tmp/mws-outside.md", "outside home");
  symlinkSync("/tmp/mws-outside.md", join(dir, "AGENTS.md"));
  mkdirSync(join(dir, ".memory"));
  writeFileSync(join(dir, ".memory", "b.md"), "note");
  writeFileSync(join(dir, ".memory", "a.md"), "note");
  mkdirSync(join(dir, ".git"));
  writeFileSync(join(dir, ".git", "HEAD"), "ref: refs/heads/trunk\n");

  const w = ((await h.router("workspace.create", { path: dir }, h.conn())) as any).workspace;
  const ctx = (await h.router("workspace.context", { workspace_id: w.workspace_id }, h.conn())) as any;
  assert.equal(ctx.claude_md.truncated, true);
  assert.equal(ctx.claude_md.content.length, CONTEXT_FILE_CAP);
  assert.equal(ctx.agents_md, null, "symlink escaping home reads as absent");
  assert.deepEqual(ctx.memory, ["a.md", "b.md"]);
  assert.equal(ctx.git_branch, "trunk");

  await assert.rejects(h.router("workspace.context", { workspace_id: "w_nope" }, h.conn()), /unknown workspace/);
});

test("prettifyBasename and git detection edge cases", () => {
  assert.equal(prettifyBasename("/x/marmalade-client-android"), "Marmalade Client Android");
  assert.equal(prettifyBasename("/x/finance"), "Finance");
  assert.equal(prettifyBasename("/x/agent_wiki"), "Agent Wiki");
  // Detached HEAD reads as "detached", not a branch name.
  const dir = join(tmpdir(), `mws-git-${randomUUID()}`);
  mkdirSync(join(dir, ".git"), { recursive: true });
  writeFileSync(join(dir, ".git", "HEAD"), "0123456789abcdef0123456789abcdef01234567\n");
  try {
    assert.equal(detectWorkspace(dir).gitBranch, "detached");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("terminal.create/attach/list stamp workspace_id from cwd (same derivation as sessions)", async (t) => {
  // Fake terminal manager — the stamp is the router's job; no PTY needed.
  const rows: Record<string, unknown>[] = [];
  const fakeTerminals = {
    create: (p: { cwd?: string }) => {
      const row = {
        terminal_id: `t_${rows.length + 1}`, shell: "bash", cwd: p.cwd ?? "/x",
        cols: 80, rows: 24, pid: 1, created_at: 0, last_active: 0,
      };
      rows.push(row);
      return row;
    },
    attach: (id: string) => ({
      terminal: rows.find((r) => r.terminal_id === id),
      snapshot_b64: "",
    }),
    list: () => rows,
  };
  const h = harness({ terminals: fakeTerminals });
  t.after(h.cleanup);
  const dir = join(h.home, "coding", "proj-a");
  const w = ((await h.router("workspace.create", { path: dir }, h.conn())) as any).workspace;

  const inWs = (await h.router("terminal.create", { cwd: join(dir, "inner") }, h.conn())) as any;
  assert.equal(inWs.terminal.workspace_id, w.workspace_id);
  const quick = (await h.router("terminal.create", { cwd: h.home }, h.conn())) as any;
  assert.equal(quick.terminal.workspace_id, null);

  const att = (await h.router("terminal.attach", { terminal_id: inWs.terminal.terminal_id }, h.conn())) as any;
  assert.equal(att.terminal.workspace_id, w.workspace_id);

  const list = (await h.router("terminal.list", {}, h.conn())) as any;
  const byId = new Map(list.terminals.map((r: any) => [r.terminal_id, r.workspace_id]));
  assert.equal(byId.get(inWs.terminal.terminal_id), w.workspace_id);
  assert.equal(byId.get(quick.terminal.terminal_id), null);
});
