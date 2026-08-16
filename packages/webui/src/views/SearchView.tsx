// SearchView.tsx — cross-session message search (search.messages).
//
// Design: design-lab/labs/session-search (labs 1–3; signed off 2026-07-27).
// The locked decisions this view implements:
//   - FLAT ranked results with client-side visual grouping of consecutive
//     same-session hits (SearchHitList) — flat is what paginates and what makes
//     Best/Newest mean something.
//   - Hits already carry the full (capped) message text, so "peek" expands in
//     place with NO second round-trip.
//   - Workspace scoping is DEEPEST-WINS and that is stated where scope is
//     chosen: scoping to an umbrella folder excludes sessions owned by a
//     nested workspace. Surprising exactly once, so we say it.
//   - Archived sessions are out by default (archived is what you pushed away).
//   - No daemon-offline search, no client-local index: disconnected = no
//     results, said plainly.
//
// The ARCHIVE (additive 2026-07-28) is a second CORPUS reached from the same
// strip, not a filter: one corpus per query, and the mode is stated in the
// placeholder, a banner, the meta line and a badge on every hit — years-old
// history must never masquerade as a live session. Archive hits peek exactly
// like live ones (the text already rode the wire) but cannot be opened: their
// ids are not daemon sessions, so "open" goes to the read-only ArchiveView.
//
// Only the query input is debounced; every other control re-runs immediately
// (they're deliberate, not typed).

import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { SearchMessagesResult } from "@marmalade/protocol";
import { Icon } from "../components/Icon.js";
import { useGateway } from "../app/gateway-context.js";
import {
  buildSearchParams,
  isSearchable,
  scopeSummary,
  unsearchedSummary,
  type ScopeSelection,
} from "../components/search.js";
import { openTargetFromHit, type ChatOpenTarget } from "../components/jump.js";
import { SearchHitList } from "./SearchHitList.js";
import { ArchiveView } from "./ArchiveView.js";

const PAGE = 20;
const DEBOUNCE_MS = 250;

interface SearchViewProps {
  /** Open a live session. `at` is present when a HIT was opened (not a session
   *  header): the chat deep-links to that message and arms the match navigator
   *  for the query that found it (components/jump.ts). */
  onOpenSession: (id: string, at?: ChatOpenTarget) => void;
}

export function SearchView({ onOpenSession }: SearchViewProps): ReactNode {
  const { client, workspaces, status } = useGateway();
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [scope, setScope] = useState<ScopeSelection>({ workspaceIds: [], quickChats: false });
  const [sort, setSort] = useState<"rank" | "recent">("rank");
  const [includeArchived, setIncludeArchived] = useState(false);
  // The archive corpus (pre-daemon Claude Code history) — a SEPARATE corpus,
  // one per query, not a filter that widens the live results. Gated on the
  // daemon having scanned it at all.
  const hasArchive = client.hasFeature("search_archive");
  const archiveMode = scope.corpus === "archive";
  // The read-only viewer opens IN PLACE of the results, so backing out of it
  // returns to the exact page you searched — no re-query, no lost scroll.
  const [openArchiveId, setOpenArchiveId] = useState<string | null>(null);
  const [result, setResult] = useState<SearchMessagesResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The scope/sort/query the current `result` was answered for — the empty
  // state must name what was ACTUALLY searched, not what the strip shows now.
  const askedRef = useRef<{ query: string; scope: ScopeSelection } | null>(null);

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query), DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [query]);

  // One search per (debounced query, scope, sort, archived) — always from
  // offset 0. "Load more" appends below, outside this effect.
  useEffect(() => {
    if (!isSearchable(debounced) || status !== "connected") {
      setResult(null);
      setError(null);
      return;
    }
    let live = true;
    const asked = { query: debounced, scope };
    setLoading(true);
    client
      .searchMessages(
        buildSearchParams(debounced, scope, {
          sort,
          // include_archived is meaningless in the archive corpus (nothing
          // there has an archived flag) — send the honest default, not the
          // live corpus's leftover toggle.
          includeArchived: archiveMode ? false : includeArchived,
          limit: PAGE,
          offset: 0,
        }),
      )
      .then(
        (r) => {
          if (!live) return;
          askedRef.current = asked;
          setResult(r);
          setError(null);
        },
        (e: Error) => {
          if (!live) return;
          setResult(null);
          setError(e.message);
        },
      )
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [client, debounced, scope, sort, includeArchived, archiveMode, status]);

  // Reconnecting to a daemon that never scanned the archive must not leave the
  // strip parked in a corpus it can't serve.
  useEffect(() => {
    if (!hasArchive && archiveMode) {
      setScope((prev) => ({ ...prev, corpus: undefined }));
      setOpenArchiveId(null);
    }
  }, [hasArchive, archiveMode]);

  const loadMore = () => {
    if (!result || loading) return;
    setLoading(true);
    client
      .searchMessages(
        buildSearchParams(debounced, scope, {
          sort,
          includeArchived: archiveMode ? false : includeArchived,
          limit: PAGE,
          offset: result.hits.length,
        }),
      )
      .then(
        (r) =>
          setResult((prev) =>
            prev
              ? { total: r.total, hits: [...prev.hits, ...r.hits], sessions: { ...prev.sessions, ...r.sessions } }
              : r,
          ),
        (e: Error) => setError(e.message),
      )
      .finally(() => setLoading(false));
  };

  const toggleWorkspace = (id: string) =>
    setScope((prev) => ({
      ...prev,
      workspaceIds: prev.workspaceIds.includes(id)
        ? prev.workspaceIds.filter((w) => w !== id)
        : [...prev.workspaceIds, id],
    }));

  const everywhere = scope.workspaceIds.length === 0 && !scope.quickChats;
  const asked = askedRef.current;

  // The viewer replaces the search body (rather than becoming a tab of its
  // own): an archive transcript is only ever reached FROM a result, and back
  // must land on that same result page.
  if (openArchiveId) {
    return <ArchiveView archiveSessionId={openArchiveId} onBack={() => setOpenArchiveId(null)} />;
  }

  return (
    <div className="mm-search" data-corpus={archiveMode ? "archive" : "live"}>
      <div className="mm-search-bar">
        <input
          type="text"
          value={query}
          autoFocus
          placeholder={archiveMode ? "Search the archive (pre-daemon history)…" : "Search all messages…"}
          onChange={(e) => setQuery(e.target.value)}
        />
        {query && (
          <button className="mm-btn ghost small" aria-label="Clear query" onClick={() => setQuery("")}>×</button>
        )}
        <div className="mm-search-sort" role="group" aria-label="Sort">
          <button className="mm-chip" aria-pressed={sort === "rank"} onClick={() => setSort("rank")}>Best</button>
          <button className="mm-chip" aria-pressed={sort === "recent"} onClick={() => setSort("recent")}>Newest</button>
        </div>
      </div>

      <div className="mm-search-chips">
        <button
          className="mm-chip"
          aria-pressed={everywhere}
          title="Search every session this daemon can show you"
          onClick={() => setScope((prev) => ({ workspaceIds: [], quickChats: false, corpus: prev.corpus }))}
        >
          Everywhere
        </button>
        {workspaces.map((w) => (
          <button
            key={w.workspace_id}
            className="mm-chip"
            aria-pressed={scope.workspaceIds.includes(w.workspace_id)}
            title={w.path}
            onClick={() => toggleWorkspace(w.workspace_id)}
          >
            {w.emoji ? `${w.emoji} ` : ""}{w.name}
          </button>
        ))}
        <button
          className="mm-chip"
          aria-pressed={scope.quickChats}
          title="Sessions whose folder falls inside no workspace"
          onClick={() => setScope((prev) => ({ ...prev, quickChats: !prev.quickChats }))}
        >
          <Icon token="icon.ui.chat" size={14} /> Quick chats
        </button>
        {/* Meaningless in the archive corpus (nothing there has an archived
            flag), so it's absent there rather than a dead control. */}
        {!archiveMode && (
          <button
            className="mm-chip"
            aria-pressed={includeArchived}
            title="Archived sessions are excluded by default"
            onClick={() => setIncludeArchived((v) => !v)}
          >
            <Icon token="icon.ui.archive" size={14} /> Include archived
          </button>
        )}
        {/* Corpus switch, kept last and visually apart: it doesn't widen the
            live results, it REPLACES them with pre-daemon history. */}
        {hasArchive && (
          <button
            className="mm-chip mm-chip-archive"
            aria-pressed={archiveMode}
            title="Search the pre-daemon Claude Code history instead of live sessions"
            onClick={() =>
              setScope((prev) => ({ ...prev, corpus: prev.corpus === "archive" ? undefined : "archive" }))
            }
          >
            <Icon token="icon.ui.archive" size={14} /> Archive
          </button>
        )}
      </div>

      {archiveMode && (
        <div className="mm-archive-banner">
          <strong>Searching the archive.</strong> These are your Claude Code conversations from
          before this daemon existed — a separate, read-only corpus. Live sessions are <em>not</em>{" "}
          in these results, and nothing found here can be opened or resumed: an archive hit opens a
          read-only transcript. Workspace and quick-chat scoping still apply (by the old session's
          folder). A conversation this daemon already replays is hidden here — you'll find it in
          the live corpus, where it still works.
        </div>
      )}

      {/* Deepest-wins is surprising exactly once — say it where scope is chosen. */}
      <p className="mm-hint">
        Sessions belong to a workspace by folder, and the <strong>deepest folder wins</strong>: scoping to
        an umbrella workspace excludes the sessions owned by a workspace nested inside it. Same rule as
        the session list's grouping. Quick chats are the sessions in no workspace at all.
      </p>

      {status !== "connected" && (
        <p className="mm-hint" style={{ color: "var(--error)" }}>
          Search runs on the daemon — reconnect to search. There is no offline index.
        </p>
      )}

      {error && <p className="mm-hint" style={{ color: "var(--error)" }}>Search failed: {error}</p>}

      {!isSearchable(query) && status === "connected" && (
        <p className="mm-hint">
          Type at least two characters. Message text only — tool calls, tool results, thinking and
          system prompts are never indexed. <code>"exact phrase"</code> and <code>prefix*</code> work.
        </p>
      )}

      {result && (
        <>
          <div className="mm-search-meta">
            {result.total} {result.total === 1 ? "match" : "matches"} · showing {result.hits.length} ·{" "}
            {scopeSummary(scope, workspaces)}
            {archiveMode ? " · archive corpus (read-only)" : includeArchived ? " · archived included" : ""}
          </div>

          {result.hits.length === 0 && asked && (
            <div className="mm-empty">
              <div>No matches for “{asked.query}” in {scopeSummary(asked.scope, workspaces)}.</div>
              {unsearchedSummary(asked.scope, workspaces) && (
                <div className="mm-hint">{unsearchedSummary(asked.scope, workspaces)}</div>
              )}
              {!archiveMode && !includeArchived && (
                <div className="mm-hint">Archived sessions weren't searched.</div>
              )}
              {archiveMode && <div className="mm-hint">Live sessions weren't searched — this is the archive.</div>}
              {(!everywhere || (!archiveMode && !includeArchived)) && (
                <button
                  className="mm-btn outline small"
                  onClick={() => {
                    // Stay in the corpus you're looking at — widening scope
                    // must not silently teleport you back to live results.
                    setScope((prev) => ({ workspaceIds: [], quickChats: false, corpus: prev.corpus }));
                    setIncludeArchived(true);
                  }}
                >
                  Search everywhere instead
                </button>
              )}
            </div>
          )}

          <SearchHitList
            hits={result.hits}
            sessions={result.sessions}
            workspaces={workspaces}
            grouped
            onOpenSession={(id, hit) =>
              // The query the RESULTS were answered for, not what's typed now.
              onOpenSession(id, hit ? openTargetFromHit(hit, asked?.query ?? debounced) : undefined)
            }
            onOpenArchive={setOpenArchiveId}
          />

          {result.hits.length < result.total && (
            <button className="mm-btn outline" disabled={loading} onClick={loadMore}>
              {loading ? "Loading…" : `Load ${Math.min(PAGE, result.total - result.hits.length)} more`}
            </button>
          )}
        </>
      )}

      {loading && !result && <p className="mm-hint">Searching…</p>}
    </div>
  );
}
