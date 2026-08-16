// search.ts — pure helpers behind message search (search.messages, additive
// 2026-07-24). Everything here is presentation logic over an already-answered
// page of hits; nothing derives workspace membership (the daemon stamps
// workspace_id through the SAME matcher session.list uses, deepest wins) and
// nothing re-ranks (the daemon's bm25/recency order is the order we draw).
//
// Kept separate from the views so the marker parsing, the consecutive-session
// grouping and the scope-param building are unit-testable (test/search.test.ts)
// exactly like the workspace/fork/unread helpers.

import {
  SNIPPET_CLOSE,
  SNIPPET_OPEN,
  type SearchArchiveMessageWire,
  type SearchHitWire,
  type SearchScope,
  type SearchSessionWire,
} from "@marmalade/protocol";
import type { WorkspaceWire } from "@marmalade/protocol";

// ── Snippet markers ─────────────────────────────────────────────────────────

/** One run of snippet text: `match` spans are the ones the daemon wrapped in
 *  SNIPPET_OPEN/SNIPPET_CLOSE (private-use chars, so they can never collide
 *  with message text). The view renders matches as <mark>. */
export interface SnippetPart {
  text: string;
  match: boolean;
}

/** Split a wire snippet into plain/match runs, stripping every marker.
 *  Defensive by construction: an unopened close or an unclosed open still
 *  yields readable text and never leaks a U+E000/U+E001 into the DOM. */
export function parseSnippet(snippet: string): SnippetPart[] {
  const parts: SnippetPart[] = [];
  let match = false;
  let buf = "";
  const flush = () => {
    if (buf) parts.push({ text: buf, match });
    buf = "";
  };
  for (const ch of snippet) {
    if (ch === SNIPPET_OPEN) {
      flush();
      match = true;
    } else if (ch === SNIPPET_CLOSE) {
      flush();
      match = false;
    } else {
      buf += ch;
    }
  }
  flush();
  return parts;
}

// ── Visual grouping (flat list, grouped presentation) ───────────────────────

/** Consecutive hits from the same session, sharing one header row. This is
 *  purely visual: the list stays the daemon's flat ranked page (that's what
 *  paginates and what makes Best/Newest mean something), and a session that
 *  reappears further down the ranking gets a SECOND group rather than being
 *  folded back into the first — folding would reorder the ranking. */
export interface HitGroup {
  sessionId: string;
  /** The session context for the header, or null when the page's `sessions`
   *  map didn't carry it (never expected; render defensively). */
  session: SearchSessionWire | null;
  hits: SearchHitWire[];
}

export function groupConsecutive(
  hits: SearchHitWire[],
  sessions: Record<string, SearchSessionWire>,
): HitGroup[] {
  const groups: HitGroup[] = [];
  for (const hit of hits) {
    const last = groups[groups.length - 1];
    if (last && last.sessionId === hit.session_id) {
      last.hits.push(hit);
    } else {
      groups.push({
        sessionId: hit.session_id,
        session: sessions[hit.session_id] ?? null,
        hits: [hit],
      });
    }
  }
  return groups;
}

// ── Scope ───────────────────────────────────────────────────────────────────

/** What the scope strip has selected. No selection at all = everywhere (the
 *  daemon treats an absent scope as "everywhere this principal can see"). */
export interface ScopeSelection {
  workspaceIds: string[];
  quickChats: boolean;
  /** Find-in-conversation is scope-of-one through the same method. */
  sessionIds?: string[];
  /** Which CORPUS to search. Absent = live (the daemon's own sessions) and the
   *  frame carries no `corpus` field at all — byte-identical to the request
   *  shape that shipped before the archive existed. "archive" = the pre-daemon
   *  Claude Code history, read-only: one corpus per query, never a union. */
  corpus?: "archive";
}

/** Build the wire scope, or undefined for "everywhere" — an empty object would
 *  read the same to the daemon, but omitting it keeps the frame honest. */
export function buildScope(sel: ScopeSelection): SearchScope | undefined {
  const scope: SearchScope = {};
  if (sel.workspaceIds.length > 0) scope.workspace_ids = [...sel.workspaceIds];
  if (sel.quickChats) scope.quick_chats = true;
  if (sel.sessionIds && sel.sessionIds.length > 0) scope.session_ids = [...sel.sessionIds];
  // Archive is a corpus switch, not a filter — it stands alone in an otherwise
  // empty scope (workspace/quick-chat scoping still ORs on top of it, resolved
  // daemon-side against the archive session's cwd through the same matcher).
  if (sel.corpus === "archive") scope.corpus = "archive";
  return Object.keys(scope).length === 0 ? undefined : scope;
}

// ── Archive corpus ──────────────────────────────────────────────────────────

/** True when a hit's session came from the pre-daemon archive corpus. Such a
 *  session can NOT be opened, resumed, titled or written: the daemon doesn't
 *  know its id as a session at all. The only affordance is the read-only
 *  transcript viewer (search.archive). */
export function isArchiveSession(session: SearchSessionWire | null | undefined): boolean {
  return session?.corpus === "archive";
}

/** Merge a page of archive transcript messages into what's already loaded:
 *  de-duped by `ordinal` and kept ascending, so a re-fetched page (double
 *  "load more", a retry) can never double a message or scramble the order. */
export function appendArchivePage(
  prev: SearchArchiveMessageWire[],
  page: SearchArchiveMessageWire[],
): SearchArchiveMessageWire[] {
  const byOrdinal = new Map<number, SearchArchiveMessageWire>();
  for (const m of prev) byOrdinal.set(m.ordinal, m);
  for (const m of page) byOrdinal.set(m.ordinal, m);
  return [...byOrdinal.values()].sort((a, b) => a.ordinal - b.ordinal);
}

/** Header label for an archive session: its extracted title, else the leaf of
 *  its cwd (these are years-old conversations — many were never titled). */
export function archiveSessionLabel(session: { title: string | null; cwd: string }): string {
  const title = session.title?.trim();
  if (title) return title;
  const leaf = session.cwd.split("/").filter(Boolean).pop();
  return leaf ?? session.cwd;
}

export interface SearchQueryOptions {
  sort: "rank" | "recent";
  includeArchived: boolean;
  limit: number;
  offset: number;
}

/** The full search.messages params for a query + scope + page. Trailing
 *  whitespace is trimmed (the daemon requires >= 2 chars of real query). */
export function buildSearchParams(
  query: string,
  sel: ScopeSelection,
  opts: SearchQueryOptions,
): {
  query: string;
  scope?: SearchScope;
  include_archived: boolean;
  sort: "rank" | "recent";
  limit: number;
  offset: number;
} {
  const scope = buildScope(sel);
  return {
    query: query.trim(),
    ...(scope ? { scope } : {}),
    include_archived: opts.includeArchived,
    sort: opts.sort,
    limit: opts.limit,
    offset: opts.offset,
  };
}

/** The daemon's floor: a 1-char query is rejected, so the view never sends it. */
export function isSearchable(query: string): boolean {
  return query.trim().length >= 2;
}

/** Human summary of the current scope, for the meta line and the empty state. */
export function scopeSummary(sel: ScopeSelection, workspaces: WorkspaceWire[]): string {
  if (sel.sessionIds && sel.sessionIds.length > 0) return "this conversation";
  const names = sel.workspaceIds.map(
    (id) => workspaces.find((w) => w.workspace_id === id)?.name ?? id,
  );
  if (sel.quickChats) names.push("quick chats");
  if (names.length === 0) return "everywhere";
  if (names.length <= 2) return names.join(" and ");
  return `${names.length} scopes`;
}

/** The honest half of the empty state: what was NOT searched. null when the
 *  search really did cover everything (nothing to disclaim). */
export function unsearchedSummary(sel: ScopeSelection, workspaces: WorkspaceWire[]): string | null {
  if (sel.sessionIds && sel.sessionIds.length > 0) return "Other sessions weren't searched.";
  const missedWorkspaces = workspaces.filter((w) => !sel.workspaceIds.includes(w.workspace_id)).length;
  const missedQuick = !sel.quickChats;
  if (sel.workspaceIds.length === 0 && !sel.quickChats) return null; // everywhere
  const bits: string[] = [];
  if (missedWorkspaces > 0) {
    bits.push(missedWorkspaces === 1 ? "1 workspace" : `${missedWorkspaces} workspaces`);
  }
  if (missedQuick) bits.push("your quick chats");
  if (bits.length === 0) return null;
  const singular = bits.length === 1 && missedWorkspaces === 1 && !missedQuick;
  return `${bits.join(" and ")} ${singular ? "wasn't" : "weren't"} searched.`;
}

// ── Row labels ──────────────────────────────────────────────────────────────

/** "You" / "Agent" — the lab's wording, not the wire's role token. */
export function roleLabel(role: "user" | "assistant"): string {
  return role === "user" ? "You" : "Agent";
}

/** Compact age for a hit row: minutes/hours within a day, then days/weeks. */
export function hitAgeLabel(ts: number, now: number): string {
  const ms = Math.max(0, now - ts);
  const mins = Math.floor(ms / 60_000);
  if (mins < 1) return "now";
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  if (days < 14) return `${days}d`;
  return `${Math.floor(days / 7)}w`;
}
