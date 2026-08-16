// fs-browse.ts — fs.defaults + fs.list for the new-session workspace picker
// (fork-rest-triage Part B). Read-only stat/list, names only, no contents.
//
// Confinement is REALPATH-based, not string-prefix (review gate 2026-07-11):
// a prefix check is bypassable via symlinks and `..`. The requested path is
// resolved with realpath and containment is verified on the RESOLVED path
// against the resolved home dir. The same rule applies to any future
// fs.read/fs.write (webui plan).

import { realpathSync, readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve, sep } from "node:path";

export interface FsEntry {
  name: string;
  dir: boolean;
}

/** Resolve + confine a requested directory path to the user's home. Throws
 *  on escape (`..`, symlink-out), non-existence, or a non-directory. Dot-entries
 *  are hidden unless [showHidden] is set (the picker's "Show hidden" toggle). */
export function listDirConfined(requested: string, homeOverride?: string, showHidden = false): { path: string; entries: FsEntry[] } {
  const home = realpathSync(homeOverride ?? homedir());
  // realpath resolves both `..` segments and symlinks; a path that does not
  // exist throws here (ENOENT surfaces as a clean error to the client).
  const real = realpathSync(resolve(home, requested));
  if (real !== home && !real.startsWith(home + sep)) {
    throw new Error(`path outside home: ${requested}`);
  }
  const entries = readdirSync(real, { withFileTypes: true })
    .filter((e) => showHidden || !e.name.startsWith("."))
    .map((e) => {
      // stat (not the dirent) so a symlinked directory counts as a dir —
      // workspace symlinks (a project dir linking to a checkout elsewhere)
      // must be enterable. A broken symlink degrades to dir:false.
      let dir = e.isDirectory();
      if (!dir && e.isSymbolicLink()) {
        try { dir = statSync(join(real, e.name)).isDirectory(); } catch { /* broken link */ }
      }
      return { name: e.name, dir };
    });
  return { path: real, entries };
}
