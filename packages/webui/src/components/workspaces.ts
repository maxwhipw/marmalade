// workspaces.ts — pure helpers behind the workspace-grouped session rail.
//
// A workspace is metadata over a folder path on the daemon host; membership is
// DERIVED server-side (session.list stamps each row's workspace_id by deepest
// cwd-prefix match) — this module never touches cwd. It only buckets already-
// stamped rows under their workspace and rolls up counts, so it stays pure and
// testable (test/workspaces.test.ts) exactly like the fork/unread helpers.

import type { WorkspaceWire } from "@marmalade/protocol";
import type { SessionSummary } from "../gateway/types.js";

/** One rendered group in the rail: a workspace header + its sessions, or the
 *  trailing "Quick sessions" bucket (workspace === null). */
export interface SessionGroup {
  /** null = the Quick-sessions bucket (unstamped / unknown workspace_id). */
  workspace: WorkspaceWire | null;
  sessions: SessionSummary[];
  /** Rolled-up unread count across the group's sessions. */
  unreadCount: number;
}

/** Stable localStorage key for a group's collapsed flag. Quick sessions and
 *  each workspace get their own key so collapse survives reloads per-group. */
export function collapseKey(workspaceId: string | null): string {
  return `mm.ws.collapsed.${workspaceId ?? "__quick__"}`;
}

/** Bucket sessions under their stamped workspace_id, preserving the incoming
 *  session order within each group and the workspace list's order across
 *  groups. Sessions whose workspace_id is null/absent — or points at a
 *  workspace the daemon didn't return — fall into the trailing Quick-sessions
 *  group. Empty workspaces still render (a header with zero sessions), so a
 *  freshly-added workspace is visible before it has sessions.
 *
 *  @param isUnread pure unread predicate per session (seq arithmetic), injected
 *    so this module stays free of session-state internals.
 */
export function groupSessions(
  sessions: SessionSummary[],
  workspaces: WorkspaceWire[],
  isUnread: (s: SessionSummary) => boolean,
): SessionGroup[] {
  const known = new Set(workspaces.map((w) => w.workspace_id));
  const byWorkspace = new Map<string, SessionSummary[]>();
  const quick: SessionSummary[] = [];

  for (const s of sessions) {
    const wid = s.workspace_id;
    if (wid && known.has(wid)) {
      const bucket = byWorkspace.get(wid) ?? [];
      bucket.push(s);
      byWorkspace.set(wid, bucket);
    } else {
      quick.push(s);
    }
  }

  const groups: SessionGroup[] = workspaces.map((w) => {
    const rows = byWorkspace.get(w.workspace_id) ?? [];
    return { workspace: w, sessions: rows, unreadCount: rows.filter(isUnread).length };
  });
  // Quick sessions always render last (even when empty is fine to hide, but we
  // keep it whenever there ARE quick sessions — an empty bucket is noise).
  if (quick.length > 0) {
    groups.push({ workspace: null, sessions: quick, unreadCount: quick.filter(isUnread).length });
  }
  return groups;
}

/** A muted git-branch tag for a workspace header, or null when the folder is
 *  not a git repo (display-only — deliberately not a git UI). */
export function branchTag(w: WorkspaceWire): string | null {
  return w.detection.git_branch ? `git · ${w.detection.git_branch}` : null;
}
