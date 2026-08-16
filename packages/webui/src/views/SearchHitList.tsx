// SearchHitList.tsx — the result rows shared by cross-session search and
// find-in-conversation (design-lab labs/session-search, lab 2 frame 1 +
// lab 3 frames 2–3).
//
// The list is the daemon's FLAT ranked page — that's what paginates and what
// makes Best/Newest unambiguous. `grouped` only adds a VISUAL header when the
// session changes between consecutive hits (locked decision: flat with
// client-side grouping), so no hit is ever reordered or hidden.
//
// Clicking a hit PEEKS: it expands in place to the full (4 KB-capped) message
// text the wire already carried, plus the reply preview for a user hit. No
// second round-trip. "Open at this message" is the explicit second step: the
// hit rides back to the caller so it can DEEP LINK (open-at-seq — the
// transcript opens scrolled to that message with the match navigator armed;
// lab 3 frame 1). A session header opens the session plainly, with no hit.

import { useState } from "react";
import type { ReactNode } from "react";
import type { SearchHitWire, SearchSessionWire, WorkspaceWire } from "@marmalade/protocol";
import { Icon } from "../components/Icon.js";
import {
  groupConsecutive,
  hitAgeLabel,
  isArchiveSession,
  parseSnippet,
  roleLabel,
} from "../components/search.js";

interface Props {
  hits: SearchHitWire[];
  sessions: Record<string, SearchSessionWire>;
  workspaces: WorkspaceWire[];
  /** Draw a session header when the session changes (cross-session search).
   *  Off for find-in-conversation, where every hit is the same session. */
  grouped: boolean;
  /** Open a live session. `hit` is present when the caller can deep-link to
   *  that exact message (absent from a session header — that's a plain open). */
  onOpenSession: (sessionId: string, hit?: SearchHitWire) => void;
  /** Where an ARCHIVE hit goes instead: the read-only transcript viewer. Absent
   *  when the caller can't show one (find-in-conversation is live-only), and
   *  then an archive hit simply has no open affordance — never a live open. */
  onOpenArchive?: (archiveSessionId: string) => void;
  now?: number;
}

export function SearchHitList({
  hits,
  sessions,
  workspaces,
  grouped,
  onOpenSession,
  onOpenArchive,
  now,
}: Props): ReactNode {
  // One peek open at a time (the lab's frame 3): expanding another collapses
  // the previous, so the page never becomes a wall of full messages.
  const [openHit, setOpenHit] = useState<string | null>(null);
  const clock = now ?? Date.now();

  const workspaceChip = (session: SearchSessionWire | null): ReactNode => {
    if (!session) return null;
    if (!session.workspace_id) return <span className="mm-chip mm-hit-ws"><Icon token="icon.ui.chat" size={14} /> Quick chat</span>;
    const w = workspaces.find((x) => x.workspace_id === session.workspace_id);
    return (
      <span className="mm-chip mm-hit-ws" title={w?.path}>
        {w?.emoji ? `${w.emoji} ` : ""}
        {w?.name ?? session.workspace_id}
      </span>
    );
  };

  // Archive hits are the pre-daemon corpus: read-only history, nothing the
  // daemon can resume. The badge says so on every row, and the open affordance
  // routes to the transcript viewer instead of a session that doesn't exist.
  const archiveBadge = <span className="mm-chip mm-hit-archive"><Icon token="icon.ui.archive" size={14} /> archive</span>;

  const renderHit = (hit: SearchHitWire, session: SearchSessionWire | null): ReactNode => {
    const key = `${hit.session_id}:${hit.message_id}`;
    const expanded = openHit === key;
    const archive = isArchiveSession(session);
    return (
      <div key={key} className="mm-hit" data-expanded={expanded} data-corpus={archive ? "archive" : "live"}>
        <button
          className="mm-hit-snippet"
          aria-expanded={expanded}
          onClick={() => setOpenHit(expanded ? null : key)}
        >
          {parseSnippet(hit.snippet).map((part, i) =>
            part.match ? <mark key={i}>{part.text}</mark> : <span key={i}>{part.text}</span>,
          )}
        </button>
        <div className="mm-hit-meta">
          <span className="mm-hit-role">{roleLabel(hit.role)}</span>
          <span>·</span>
          <span>{new Date(hit.ts).toLocaleString()}</span>
          <span>·</span>
          <span>msg #{hit.seq}</span>
          {!grouped && archive && archiveBadge}
          {!grouped && !archive && session?.archived && <span className="mm-chip">archived</span>}
        </div>
        {expanded && (
          <div className="mm-hit-peek">
            <div className="mm-hit-peek-label">
              {roleLabel(hit.role).toUpperCase()} · {new Date(hit.ts).toLocaleString()}
            </div>
            <div className="mm-hit-peek-text">{hit.text}</div>
            {hit.reply_text && (
              <>
                <div className="mm-hit-peek-label">AGENT REPLIED</div>
                <div className="mm-hit-peek-text mm-hit-peek-reply">{hit.reply_text}</div>
              </>
            )}
            <div className="mm-row" style={{ marginTop: 8 }}>
              {archive ? (
                onOpenArchive && (
                  <button className="mm-btn outline small" onClick={() => onOpenArchive(hit.session_id)}>
                    Open archived transcript
                  </button>
                )
              ) : (
                <button
                  className="mm-btn outline small"
                  title="Open the conversation scrolled to this message"
                  onClick={() => onOpenSession(hit.session_id, hit)}
                >
                  Open at this message
                </button>
              )}
              <button
                className="mm-btn ghost small"
                onClick={() => void navigator.clipboard?.writeText(hit.text)}
              >
                Copy
              </button>
            </div>
          </div>
        )}
      </div>
    );
  };

  if (!grouped) {
    return <div className="mm-hits">{hits.map((h) => renderHit(h, sessions[h.session_id] ?? null))}</div>;
  }

  return (
    <div className="mm-hits">
      {groupConsecutive(hits, sessions).map((g, gi) => (
        <section
          key={`${g.sessionId}:${gi}`}
          className="mm-hit-group"
          data-corpus={isArchiveSession(g.session) ? "archive" : "live"}
        >
          <button
            className="mm-hit-group-head"
            onClick={() =>
              isArchiveSession(g.session) ? onOpenArchive?.(g.sessionId) : onOpenSession(g.sessionId)
            }
          >
            {workspaceChip(g.session)}
            <span className="mm-hit-title">{g.session?.title ?? "untitled"}</span>
            {isArchiveSession(g.session) && archiveBadge}
            {!isArchiveSession(g.session) && g.session?.archived && <span className="mm-chip">archived</span>}
            {g.session && <span className="mm-hit-age">{hitAgeLabel(g.session.last_active, clock)}</span>}
            {g.hits.length > 1 && <span className="mm-ws-count">{g.hits.length}</span>}
          </button>
          <div className="mm-hit-group-body">
            {g.hits.map((h) => renderHit(h, g.session))}
          </div>
        </section>
      ))}
    </div>
  );
}
