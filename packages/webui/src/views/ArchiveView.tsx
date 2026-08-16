// ArchiveView.tsx — the read-only viewer for ONE pre-daemon archive session
// (search.archive, additive 2026-07-28).
//
// This is history, not state. The corpus is the Claude Code transcripts that
// existed before marmaladed did: the daemon indexed them read-only and can
// serve their text, but it cannot resume, title, fork, delete or write to
// them — there is no session behind these ids. So this view is deliberately
// inert: no composer, no session actions, no live subscription. Just the
// transcript, its provenance in the header, and paging.
//
// Served entirely from the index (the daemon's FTS table holds the full text),
// so a since-deleted .jsonl still renders — the viewer never touches a file.

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { SearchArchiveMessageWire, SearchArchiveResult } from "@marmalade/protocol";
import { Icon } from "../components/Icon.js";
import { useGateway } from "../app/gateway-context.js";
import { appendArchivePage, archiveSessionLabel, roleLabel } from "../components/search.js";

const PAGE = 100;

interface Props {
  /** An ARCHIVE session id (the Claude Code session UUID from a hit). */
  archiveSessionId: string;
  /** Back to the results that got you here — the only exit. */
  onBack: () => void;
}

export function ArchiveView({ archiveSessionId, onBack }: Props): ReactNode {
  const { client, status } = useGateway();
  const [meta, setMeta] = useState<SearchArchiveResult["session"] | null>(null);
  const [messages, setMessages] = useState<SearchArchiveMessageWire[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // First page per session id. Switching sessions resets everything — a
  // half-loaded transcript must never bleed into the next one.
  useEffect(() => {
    let live = true;
    setMeta(null);
    setMessages([]);
    setTotal(0);
    setError(null);
    if (status !== "connected") return;
    setLoading(true);
    client
      .searchArchive({ session_id: archiveSessionId, limit: PAGE, offset: 0 })
      .then(
        (r) => {
          if (!live) return;
          setMeta(r.session);
          setMessages(r.messages);
          setTotal(r.total);
        },
        (e: Error) => { if (live) setError(e.message); },
      )
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [client, archiveSessionId, status]);

  const loadMore = () => {
    if (loading || messages.length >= total) return;
    setLoading(true);
    client
      .searchArchive({ session_id: archiveSessionId, limit: PAGE, offset: messages.length })
      .then(
        (r) => {
          setMessages((prev) => appendArchivePage(prev, r.messages));
          setTotal(r.total);
        },
        (e: Error) => setError(e.message),
      )
      .finally(() => setLoading(false));
  };

  return (
    <div className="mm-archive">
      <div className="mm-archive-head">
        <button className="mm-btn ghost small" onClick={onBack}>← Back to results</button>
        <span className="mm-chip mm-hit-archive"><Icon token="icon.ui.archive" size={14} /> read-only archive</span>
      </div>

      {meta && (
        <header className="mm-archive-meta">
          <h2 className="mm-archive-title">{archiveSessionLabel(meta)}</h2>
          <div className="mm-archive-sub">
            <code>{meta.cwd}</code>
          </div>
          <div className="mm-archive-sub">
            last active {new Date(meta.last_active).toLocaleString()} · {meta.message_count}{" "}
            {meta.message_count === 1 ? "message" : "messages"} indexed
          </div>
          {/* Say the limit plainly rather than letting a missing composer imply it. */}
          <p className="mm-hint">
            A conversation from before this daemon existed. It can be read and copied, never
            resumed, continued or edited — and only the message text was indexed, so tool calls,
            tool results and thinking are not here.
          </p>
        </header>
      )}

      {status !== "connected" && (
        <p className="mm-hint" style={{ color: "var(--error)" }}>
          The archive lives on the daemon — reconnect to read it.
        </p>
      )}
      {error && <p className="mm-hint" style={{ color: "var(--error)" }}>Couldn't load: {error}</p>}

      <div className="mm-archive-scroll">
        {messages.map((m) => (
          <div key={m.ordinal} className={`mm-bubble ${m.role}`}>
            <div className="mm-archive-stamp">
              {roleLabel(m.role)} · {new Date(m.ts).toLocaleString()}
            </div>
            {m.text}
          </div>
        ))}
      </div>

      {messages.length < total && (
        <button className="mm-btn outline" disabled={loading} onClick={loadMore}>
          {/* Pages run FORWARD: the transcript is ascending by ordinal, so
              "more" is the rest of the conversation, not older history. */}
          {loading ? "Loading…" : `Load ${Math.min(PAGE, total - messages.length)} more`}
        </button>
      )}
      {loading && messages.length === 0 && <p className="mm-hint">Loading transcript…</p>}
    </div>
  );
}
