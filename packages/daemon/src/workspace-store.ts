// workspace-store.ts — named folder workspaces (Paseo-style session grouping).
//
// A workspace is METADATA over a folder: a human name + emoji for a path on
// the daemon host. Sessions belong to a workspace by cwd prefix match —
// derived at read time, never stored on the session row — so pre-existing
// sessions, cron sessions, and sessions from other clients adopt into a
// workspace automatically, and deleting a workspace un-groups without ever
// touching a session. Deepest match wins when workspaces nest (an umbrella
// folder containing repos).
//
// The daemon owns the entity (not client-local state) for the same reason
// seen cursors are server-side: the name/emoji must agree across Android,
// webui, and desktop. Paths are realpath-confined to home, same rule as
// fs.list (fs-browse.ts).

import type { DatabaseSync } from "node:sqlite";
import { existsSync, readFileSync, readdirSync, realpathSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { basename, join, resolve, sep } from "node:path";
import { randomUUID } from "node:crypto";

const SCHEMA = `
CREATE TABLE IF NOT EXISTS workspaces (
  id         TEXT PRIMARY KEY,
  path       TEXT NOT NULL UNIQUE,
  name       TEXT NOT NULL,
  emoji      TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
`;

export interface WorkspaceRecord {
  id: string;
  /** Realpath-resolved absolute folder path (the match key). */
  path: string;
  name: string;
  emoji: string | null;
  createdAt: number;
  updatedAt: number;
}

/** What the folder brings to a session spawned in it — read live from the
 *  filesystem at list time (a few stats per workspace), never cached: the
 *  host is the truth and it changes under us. */
export interface WorkspaceDetection {
  /** Current git branch, "detached" on a detached HEAD, null when not a repo. */
  gitBranch: string | null;
  hasClaudeMd: boolean;
  hasAgentsMd: boolean;
  /** Count of .md notes in .memory/ (0 when absent). */
  memoryNotes: number;
}

/** "marmalade-client-android" → "Marmalade Client Android". */
export function prettifyBasename(path: string): string {
  return basename(path)
    .split(/[-_\s]+/)
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ") || path;
}

/** Read the branch without spawning git: .git/HEAD is `ref: refs/heads/X`
 *  on a branch, a bare hash when detached. `.git` may be a FILE in a
 *  worktree/submodule ("gitdir: …") — that still counts as a repo. */
function readGitBranch(dir: string): string | null {
  try {
    const gitPath = join(dir, ".git");
    let headFile = join(gitPath, "HEAD");
    if (statSync(gitPath).isFile()) {
      const m = /^gitdir: (.+)$/m.exec(readFileSync(gitPath, "utf8"));
      if (!m) return null;
      headFile = join(resolve(dir, m[1].trim()), "HEAD");
    }
    const head = readFileSync(headFile, "utf8").trim();
    const ref = /^ref: refs\/heads\/(.+)$/.exec(head);
    return ref ? ref[1] : "detached";
  } catch {
    return null;
  }
}

export function detectWorkspace(dir: string): WorkspaceDetection {
  let memoryNotes = 0;
  try {
    memoryNotes = readdirSync(join(dir, ".memory")).filter((f) => f.endsWith(".md")).length;
  } catch { /* no .memory dir */ }
  return {
    gitBranch: readGitBranch(dir),
    hasClaudeMd: existsSync(join(dir, "CLAUDE.md")),
    hasAgentsMd: existsSync(join(dir, "AGENTS.md")),
    memoryNotes,
  };
}

/** Per-file cap on a context preview — a peek, not a file transfer. */
export const CONTEXT_FILE_CAP = 16 * 1024;

export interface WorkspaceContextFile {
  content: string;
  /** True when the file exceeded CONTEXT_FILE_CAP and was cut. */
  truncated: boolean;
}

/** Read-only context peek (workspace.context): what a session spawned in this
 *  folder inherits. Files are realpath-confined to [home] AFTER resolution —
 *  a CLAUDE.md symlinked outside home reads as absent, never as its target. */
export function readWorkspaceContext(dir: string, home: string): {
  claudeMd: WorkspaceContextFile | null;
  agentsMd: WorkspaceContextFile | null;
  memory: string[];
  gitBranch: string | null;
} {
  const realHome = realpathSync(home);
  const readCapped = (name: string): WorkspaceContextFile | null => {
    try {
      const real = realpathSync(join(dir, name));
      if (real !== realHome && !real.startsWith(realHome + sep)) return null;
      const content = readFileSync(real, "utf8");
      return content.length > CONTEXT_FILE_CAP
        ? { content: content.slice(0, CONTEXT_FILE_CAP), truncated: true }
        : { content, truncated: false };
    } catch {
      return null;
    }
  };
  let memory: string[] = [];
  try {
    memory = readdirSync(join(dir, ".memory")).filter((f) => f.endsWith(".md")).sort();
  } catch { /* no .memory dir */ }
  return {
    claudeMd: readCapped("CLAUDE.md"),
    agentsMd: readCapped("AGENTS.md"),
    memory,
    gitBranch: readGitBranch(dir),
  };
}

export class WorkspaceStore {
  /** Same cap spirit as session titles — a list label, not a document. */
  static readonly MAX_NAME = 120;

  /** The confined home this store validates against (tests override it). */
  get home(): string {
    return this.homeOverride ?? homedir();
  }

  constructor(private db: DatabaseSync, private homeOverride?: string) {
    this.db.exec(SCHEMA);
  }

  /** Resolve + confine like fs.list: realpath (kills `..` and symlink-out),
   *  must exist, be a directory, and live under home. */
  private confine(requested: string): string {
    const home = realpathSync(this.homeOverride ?? homedir());
    const real = realpathSync(resolve(home, requested));
    if (real !== home && !real.startsWith(home + sep)) {
      throw new Error(`path outside home: ${requested}`);
    }
    if (!statSync(real).isDirectory()) throw new Error(`not a directory: ${requested}`);
    return real;
  }

  create(opts: { path: string; name?: string; emoji?: string }, now: number): WorkspaceRecord {
    const path = this.confine(opts.path);
    if (this.byPath(path)) throw new Error(`a workspace already exists for ${path}`);
    const rec: WorkspaceRecord = {
      id: `w_${randomUUID()}`,
      path,
      name: (opts.name?.trim() || prettifyBasename(path)).slice(0, WorkspaceStore.MAX_NAME),
      emoji: opts.emoji ?? null,
      createdAt: now,
      updatedAt: now,
    };
    this.db
      .prepare(`INSERT INTO workspaces (id, path, name, emoji, created_at, updated_at) VALUES (?,?,?,?,?,?)`)
      .run(rec.id, rec.path, rec.name, rec.emoji, rec.createdAt, rec.updatedAt);
    return rec;
  }

  get(id: string): WorkspaceRecord | undefined {
    const row = this.db.prepare(`SELECT * FROM workspaces WHERE id = ?`).get(id) as
      | Record<string, unknown>
      | undefined;
    return row ? rowToRecord(row) : undefined;
  }

  private byPath(path: string): WorkspaceRecord | undefined {
    const row = this.db.prepare(`SELECT * FROM workspaces WHERE path = ?`).get(path) as
      | Record<string, unknown>
      | undefined;
    return row ? rowToRecord(row) : undefined;
  }

  list(): WorkspaceRecord[] {
    const rows = this.db.prepare(`SELECT * FROM workspaces ORDER BY name`).all() as Record<string, unknown>[];
    return rows.map(rowToRecord);
  }

  /** Rename / re-emoji. Metadata only — the path is the identity's anchor and
   *  is immutable (remove + re-add to move a workspace). emoji: null clears. */
  update(id: string, patch: { name?: string; emoji?: string | null }, now: number): WorkspaceRecord {
    const rec = this.get(id);
    if (!rec) throw new Error(`unknown workspace ${id}`);
    const name = patch.name?.trim() ? patch.name.trim().slice(0, WorkspaceStore.MAX_NAME) : rec.name;
    const emoji = patch.emoji === undefined ? rec.emoji : patch.emoji;
    this.db
      .prepare(`UPDATE workspaces SET name = ?, emoji = ?, updated_at = ? WHERE id = ?`)
      .run(name, emoji, now, id);
    return this.get(id)!;
  }

  /** Un-group, never a cascade: sessions keep their cwd and simply stop
   *  matching. Returns false for an unknown id (idempotent). */
  delete(id: string): boolean {
    const res = this.db.prepare(`DELETE FROM workspaces WHERE id = ?`).run(id);
    return Number(res.changes ?? 0) > 0;
  }

  /** cwd → workspace id matcher for session.list stamping. Built once per
   *  list call; deepest (longest) matching path wins on nested workspaces. */
  matcher(): (cwd: string) => string | null {
    const all = this.list().sort((a, b) => b.path.length - a.path.length);
    return (cwd: string) => {
      for (const w of all) {
        if (cwd === w.path || cwd.startsWith(w.path + sep)) return w.id;
      }
      return null;
    };
  }
}

function rowToRecord(row: Record<string, unknown>): WorkspaceRecord {
  return {
    id: row.id as string,
    path: row.path as string,
    name: row.name as string,
    emoji: (row.emoji as string | null) ?? null,
    createdAt: row.created_at as number,
    updatedAt: row.updated_at as number,
  };
}
