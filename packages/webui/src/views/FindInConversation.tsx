// FindInConversation.tsx — "find in this conversation" (design-lab
// labs/session-search, lab 3 frame 2).
//
// Scope-of-one over the SAME method: search.messages with
// scope.session_ids = [this session]. Zero extra backend, and it's the
// affordance people reach for most — a long agent session is unscrollable and
// "where did we decide that?" is a daily question.
//
// A jump list, not inline highlighting: with a hundred messages you want to
// pick the right occurrence before losing your scroll position. Picking one
// peeks it in place (SearchHitList); "Open at this message" jumps the
// transcript to it and hands the query to the match navigator (lab 3 frame 1),
// so the panel can close and you keep walking the matches with ↑/↓.

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { SearchMessagesResult } from "@marmalade/protocol";
import { useGateway } from "../app/gateway-context.js";
import { buildSearchParams, isSearchable } from "../components/search.js";
import { openTargetFromHit, type ChatOpenTarget } from "../components/jump.js";
import { SearchHitList } from "./SearchHitList.js";

const PAGE = 50;
const DEBOUNCE_MS = 250;

interface Props {
  sessionId: string;
  onClose: () => void;
  /** Jump the open transcript to a hit and arm the match navigator for the
   *  query that found it. */
  onJump: (target: ChatOpenTarget) => void;
}

export function FindInConversation({ sessionId, onClose, onJump }: Props): ReactNode {
  const { client, workspaces, status } = useGateway();
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [result, setResult] = useState<SearchMessagesResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query), DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [query]);

  useEffect(() => {
    if (!isSearchable(debounced) || status !== "connected") {
      setResult(null);
      setError(null);
      return;
    }
    let live = true;
    client
      .searchMessages(
        buildSearchParams(
          debounced,
          { workspaceIds: [], quickChats: false, sessionIds: [sessionId] },
          // Archived included: this session is the one you're reading, so its
          // own archived flag must never hide its own matches.
          { sort: "recent", includeArchived: true, limit: PAGE, offset: 0 },
        ),
      )
      .then(
        (r) => { if (live) { setResult(r); setError(null); } },
        (e: Error) => { if (live) { setResult(null); setError(e.message); } },
      );
    return () => { live = false; };
  }, [client, debounced, sessionId, status]);

  return (
    <div className="mm-find">
      <div className="mm-search-bar">
        <input
          type="text"
          value={query}
          autoFocus
          placeholder="Find in this conversation…"
          onChange={(e) => setQuery(e.target.value)}
        />
        <button className="mm-btn ghost small" aria-label="Close find" onClick={onClose}>×</button>
      </div>
      {error && <p className="mm-hint" style={{ color: "var(--error)" }}>Search failed: {error}</p>}
      {result && (
        <>
          <div className="mm-search-meta">
            {result.total} {result.total === 1 ? "match" : "matches"} in this session
            {result.hits.length < result.total ? ` · showing ${result.hits.length}` : ""}
          </div>
          {result.hits.length === 0 && (
            <div className="mm-hint">
              No matches for “{debounced}” in this conversation. Only message text is indexed — not tool
              calls, tool results or thinking.
            </div>
          )}
          <SearchHitList
            hits={result.hits}
            sessions={result.sessions}
            workspaces={workspaces}
            grouped={false}
            onOpenSession={(_id, hit) => {
              if (hit) onJump(openTargetFromHit(hit, debounced));
            }}
          />
        </>
      )}
    </div>
  );
}
