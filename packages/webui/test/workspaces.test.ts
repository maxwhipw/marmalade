// workspaces.test.ts — pure grouping helpers behind the workspace-grouped rail.

import { describe, expect, test } from "vitest";
import { branchTag, collapseKey, groupSessions } from "../src/components/workspaces.js";
import type { SessionSummary } from "../src/gateway/types.js";
import type { WorkspaceWire } from "@marmalade/protocol";

const ws = (over: Partial<WorkspaceWire> & { workspace_id: string }): WorkspaceWire => ({
  path: `/home/u/${over.workspace_id}`,
  name: over.workspace_id,
  emoji: null,
  created_at: 0,
  updated_at: 0,
  detection: { git_branch: null, has_claude_md: false, has_agents_md: false, memory_notes: 0 },
  ...over,
});

const sess = (id: string, workspace_id: string | null | undefined, unread = false): SessionSummary => ({
  session_id: id,
  lifecycle: "active",
  run_state: "idle",
  last_seq: unread ? 5 : 3,
  seen_seq: 3,
  workspace_id,
});

// Unread predicate for the tests: last_seq > seen_seq.
const isUnread = (s: SessionSummary): boolean => s.last_seq > s.seen_seq;

describe("groupSessions", () => {
  test("buckets sessions under their stamped workspace_id, order preserved", () => {
    const workspaces = [ws({ workspace_id: "a" }), ws({ workspace_id: "b" })];
    const sessions = [sess("s1", "a"), sess("s2", "b"), sess("s3", "a")];
    const groups = groupSessions(sessions, workspaces, isUnread);
    expect(groups.map((g) => g.workspace?.workspace_id ?? null)).toEqual(["a", "b"]);
    expect(groups[0].sessions.map((s) => s.session_id)).toEqual(["s1", "s3"]);
    expect(groups[1].sessions.map((s) => s.session_id)).toEqual(["s2"]);
  });

  test("null / absent / unknown workspace_id fall into a trailing Quick group", () => {
    const workspaces = [ws({ workspace_id: "a" })];
    const sessions = [
      sess("s1", "a"),
      sess("s2", null),
      sess("s3", undefined),
      sess("s4", "gone"), // points at a workspace the daemon didn't return
    ];
    const groups = groupSessions(sessions, workspaces, isUnread);
    expect(groups).toHaveLength(2);
    expect(groups[1].workspace).toBeNull();
    expect(groups[1].sessions.map((s) => s.session_id)).toEqual(["s2", "s3", "s4"]);
  });

  test("empty workspaces still render (a header with no sessions)", () => {
    const groups = groupSessions([], [ws({ workspace_id: "a" })], isUnread);
    expect(groups).toHaveLength(1);
    expect(groups[0].sessions).toEqual([]);
    expect(groups[0].unreadCount).toBe(0);
  });

  test("no Quick group when there are no unstamped sessions", () => {
    const groups = groupSessions([sess("s1", "a")], [ws({ workspace_id: "a" })], isUnread);
    expect(groups.some((g) => g.workspace === null)).toBe(false);
  });

  test("unreadCount rolls up per group", () => {
    const workspaces = [ws({ workspace_id: "a" })];
    const sessions = [sess("s1", "a", true), sess("s2", "a", false), sess("s3", null, true)];
    const groups = groupSessions(sessions, workspaces, isUnread);
    expect(groups[0].unreadCount).toBe(1);
    expect(groups[1].unreadCount).toBe(1);
  });
});

describe("collapseKey", () => {
  test("distinct stable keys per workspace, and one for Quick sessions", () => {
    expect(collapseKey("a")).toBe("mm.ws.collapsed.a");
    expect(collapseKey(null)).toBe("mm.ws.collapsed.__quick__");
    expect(collapseKey("a")).not.toBe(collapseKey("b"));
  });
});

describe("branchTag", () => {
  test("muted tag when git, null otherwise", () => {
    expect(branchTag(ws({ workspace_id: "a", detection: { git_branch: "main", has_claude_md: false, has_agents_md: false, memory_notes: 0 } }))).toBe("git · main");
    expect(branchTag(ws({ workspace_id: "a" }))).toBeNull();
  });
});
