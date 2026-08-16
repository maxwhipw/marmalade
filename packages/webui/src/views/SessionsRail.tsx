// SessionsRail.tsx — the session list (spec view 2).
//
// Rows show title/topic, an unread chip (seq arithmetic ONLY — session-ids
// rule 2), a run_state dot, and delete (confirm) + rename via
// session.delete/session.title. Live session.deleted handling is in the gateway
// context (the row vanishes when the event lands). "New" starts a not-yet-
// created conversation (deferred create — the ChatView creates on first send).
//
// Workspaces (2026-07-18): when the daemon advertises "workspaces", sessions
// group under collapsible per-workspace cards (emoji + name + git-branch tag +
// count/unread rollup); unstamped sessions fall into a trailing "Quick
// sessions" group. Membership is DERIVED server-side (session.list stamps
// workspace_id) — the rail never matches cwd. Without the feature it renders
// exactly the old flat list.

import { useState } from "react";
import type { ReactNode } from "react";
import { useGateway } from "../app/gateway-context.js";
import { isUnread } from "../gateway/session-state.js";
import type { SessionSummary } from "../gateway/types.js";
import type { WorkspaceWire } from "@marmalade/protocol";
import { branchTag, collapseKey, groupSessions } from "../components/workspaces.js";
import { partitionArchived } from "../components/archive.js";
import { NewWorkspaceDialog } from "./NewWorkspaceDialog.js";
import { WorkspaceContextPeek } from "./WorkspaceContextPeek.js";

interface Props {
  activeId: string | null;
  onSelect: (id: string | null) => void;
}

export function SessionsRail({ activeId, onSelect }: Props): ReactNode {
  const { sessions, workspaces, seenSeqs, sessionStates, client, refreshWorkspaces } = useGateway();
  const hasWorkspaces = client.hasFeature("workspaces");
  const [showNewWorkspace, setShowNewWorkspace] = useState(false);
  const [peekWorkspace, setPeekWorkspace] = useState<WorkspaceWire | null>(null);
  // Collapse state lives in localStorage (per group key). A tick bumps a
  // counter so toggling re-renders without a full state mirror of every key.
  const [, setTick] = useState(0);

  // Pure unread predicate reused by the row renderer and the group rollup.
  const unreadOf = (row: SessionSummary): boolean => {
    const live = sessionStates.get(row.session_id);
    const lastSeq = Math.max(row.last_seq, live?.lastSeq ?? 0);
    const seenSeq = seenSeqs.get(row.session_id) ?? row.seen_seq;
    return isUnread(lastSeq, seenSeq);
  };

  const del = async (row: SessionSummary) => {
    if (!confirm(`Delete "${row.title ?? row.topic ?? row.session_id}"? This can't be undone.`)) return;
    await client.deleteSession(row.session_id);
    if (activeId === row.session_id) onSelect(null);
  };

  // Clear resets the conversation in place (session.clear) — the ONLY "start
  // over" for the main session, which can't be deleted. The view empties off
  // the transient session.cleared event; refetch so the row's unread/summary
  // reflect the wipe.
  const clear = async (row: SessionSummary) => {
    if (!confirm(`Clear "${row.title ?? row.topic ?? "Assistant"}"? The conversation resets — this can't be undone.`)) return;
    try {
      await client.clearSession(row.session_id);
      client.listSessions().catch(() => {});
    } catch (e) {
      alert(`Clear failed: ${(e as Error).message}`);
    }
  };

  // Archive/unarchive flips the daemon-backed flag (session.archive) and
  // refetches so the row moves between the main list and the Archived
  // section. Failure surfaces (the daemon refuses e.g. the main session).
  const setArchived = async (row: SessionSummary, archived: boolean) => {
    try {
      await client.archiveSession(row.session_id, archived);
      await client.listSessions();
    } catch (e) {
      alert(`${archived ? "Archive" : "Unarchive"} failed: ${(e as Error).message}`);
    }
  };

  const rename = async (row: SessionSummary) => {
    const next = prompt("Rename session", row.title ?? row.topic ?? "");
    if (next && next.trim()) await client.renameSession(row.session_id, next.trim());
  };

  const isCollapsed = (workspaceId: string | null): boolean =>
    localStorage.getItem(collapseKey(workspaceId)) === "1";

  const toggleCollapse = (workspaceId: string | null) => {
    const key = collapseKey(workspaceId);
    if (localStorage.getItem(key) === "1") localStorage.removeItem(key);
    else localStorage.setItem(key, "1");
    setTick((n) => n + 1);
  };

  // ── Per-workspace header actions ──────────────────────────────────────────

  const newSessionHere = async (w: WorkspaceWire) => {
    // A session created with cwd inside the workspace folder gets stamped into
    // this workspace by the daemon on the next session.list.
    const id = await client.createSession({ cwd: w.path });
    onSelect(id);
  };

  const renameWorkspace = async (w: WorkspaceWire) => {
    const next = prompt("Rename workspace", w.name);
    if (next && next.trim() && next.trim() !== w.name) {
      await client.updateWorkspace(w.workspace_id, { name: next.trim() });
      refreshWorkspaces();
    }
  };

  const removeWorkspace = async (w: WorkspaceWire) => {
    if (!confirm(`Remove workspace "${w.name}"? Sessions are kept — this only un-groups them.`)) return;
    await client.deleteWorkspace(w.workspace_id);
    refreshWorkspaces();
    client.listSessions().catch(() => {}); // rows re-fall into Quick sessions
  };

  // ── Row renderer (shared by flat + grouped) ───────────────────────────────

  const renderRow = (row: SessionSummary): ReactNode => {
    const live = sessionStates.get(row.session_id);
    const unread = unreadOf(row);
    const runState = live?.runState ?? row.run_state;
    return (
      <div
        key={row.session_id}
        className="mm-rail-row"
        aria-selected={activeId === row.session_id}
        onClick={() => onSelect(row.session_id)}
      >
        <div className="mm-rail-title">
          <span className={`run-dot ${runState === "running" ? "running" : ""}`} />
          {row.is_main && <span className="mm-chip mm-main-chip" title="The daemon-managed assistant — always warm, can't be deleted">Assistant</span>}
          <span>{row.title ?? row.topic ?? "untitled"}</span>
          {unread && <span className="mm-unread">new</span>}
        </div>
        {row.branched_from && (() => {
          const src = sessions.find((s) => s.session_id === row.branched_from!.session_id);
          const label = src?.title ?? src?.topic ?? "source chat";
          // Link back to the source when it still exists; a dim, unclickable
          // chip when it's been deleted (tri-state chip: null = unselectable).
          return src ? (
            <button
              className="mm-chip mm-branched"
              title={`Branched from "${label}"`}
              onClick={(e) => { e.stopPropagation(); onSelect(src.session_id); }}
            >
              ⑂ branched from {label}
            </button>
          ) : (
            <span className="mm-chip mm-branched null" title="Source chat was deleted">
              ⑂ branched from (deleted)
            </span>
          );
        })()}
        {row.summary && <div className="mm-rail-sub">{row.summary}</div>}
        <div className="mm-rail-actions" onClick={(e) => e.stopPropagation()}>
          <button className="mm-btn ghost small" onClick={() => void rename(row)}>rename</button>
          {row.is_main ? (
            // The main session is daemon-owned: no delete/stop/archive —
            // Clear is the only reset (session.clear). Hiding these matches
            // the wire (the daemon refuses delete/stop/archive on main).
            <button className="mm-btn ghost small" onClick={() => void clear(row)}>clear</button>
          ) : (
            <>
              <button className="mm-btn ghost small" onClick={() => void setArchived(row, !row.archived)}>
                {row.archived ? "unarchive" : "archive"}
              </button>
              <button className="mm-btn ghost small" onClick={() => void del(row)}>delete</button>
            </>
          )}
        </div>
      </div>
    );
  };

  // THE main session pins to the top as its own "Assistant" section (in BOTH
  // flat + grouped modes) and is excluded from the normal list/groups so it
  // never doubles inside a workspace card.
  const mainRow = sessions.find((s) => s.is_main) ?? null;
  const nonMain = mainRow ? sessions.filter((s) => !s.is_main) : sessions;
  // Archived rows leave the main list/groups entirely and render in ONE
  // collapsed trailing "Archived" section (both flat + grouped modes) —
  // reachable, out of the way, shared state via the daemon.
  const { active: rest, archived } = partitionArchived(nonMain);
  const [showArchived, setShowArchived] = useState(false);

  const assistantPin = mainRow && (
    <section className="mm-assistant-pin">{renderRow(mainRow)}</section>
  );

  const archivedSection = archived.length > 0 && (
    <section className="mm-ws-group mm-archived">
      <div className="mm-ws-header">
        <button
          className="mm-ws-title"
          aria-expanded={showArchived}
          onClick={() => setShowArchived((v) => !v)}
        >
          <span className="mm-ws-caret">{showArchived ? "▾" : "▸"}</span>
          <span className="mm-ws-name">Archived</span>
          <span className="mm-ws-count">{archived.length}</span>
        </button>
      </div>
      {showArchived && archived.map(renderRow)}
    </section>
  );

  // ── Flat list (no "workspaces" feature) — exactly the old rail ────────────

  if (!hasWorkspaces) {
    return (
      <nav className="mm-rail">
        <button className="mm-btn accent" style={{ width: "100%", marginBottom: 8 }} onClick={() => onSelect(null)}>
          + New
        </button>
        {assistantPin}
        {rest.length === 0 && !mainRow && archived.length === 0 && <div className="mm-rail-sub" style={{ padding: 12 }}>No sessions yet.</div>}
        {rest.map(renderRow)}
        {archivedSection}
      </nav>
    );
  }

  // ── Grouped list ──────────────────────────────────────────────────────────

  const groups = groupSessions(rest, workspaces, unreadOf);

  return (
    <nav className="mm-rail">
      <div style={{ display: "flex", gap: 6, marginBottom: 8 }}>
        <button className="mm-btn accent" style={{ flex: 1 }} onClick={() => onSelect(null)}>
          + New
        </button>
        <button className="mm-btn ghost" title="Add a workspace" onClick={() => setShowNewWorkspace(true)}>
          + Workspace
        </button>
      </div>

      {assistantPin}

      {sessions.length === 0 && workspaces.length === 0 && (
        <div className="mm-rail-sub" style={{ padding: 12 }}>No sessions yet.</div>
      )}

      {groups.map((g) => {
        const w = g.workspace;
        const wid = w?.workspace_id ?? null;
        const collapsed = isCollapsed(wid);
        const tag = w ? branchTag(w) : null;
        return (
          <section key={wid ?? "__quick__"} className="mm-ws-group">
            <div className="mm-ws-header">
              <button
                className="mm-ws-title"
                aria-expanded={!collapsed}
                onClick={() => toggleCollapse(wid)}
              >
                <span className="mm-ws-caret">{collapsed ? "▸" : "▾"}</span>
                {w ? (
                  <>
                    {w.emoji && <span className="mm-ws-emoji">{w.emoji}</span>}
                    <span className="mm-ws-name">{w.name}</span>
                  </>
                ) : (
                  <span className="mm-ws-name">Quick sessions</span>
                )}
                <span className="mm-ws-count">{g.sessions.length}</span>
                {g.unreadCount > 0 && <span className="mm-unread">{g.unreadCount}</span>}
              </button>
              {tag && <span className="mm-ws-branch">{tag}</span>}
            </div>

            {w && !collapsed && (
              <div className="mm-ws-actions">
                <button className="mm-btn ghost small" onClick={() => void newSessionHere(w)}>+ session</button>
                <button className="mm-btn ghost small" onClick={() => setPeekWorkspace(w)}>context</button>
                <button className="mm-btn ghost small" onClick={() => void renameWorkspace(w)}>rename</button>
                <button className="mm-btn ghost small" onClick={() => void removeWorkspace(w)}>remove</button>
              </div>
            )}

            {!collapsed && g.sessions.map(renderRow)}
            {!collapsed && g.sessions.length === 0 && w && (
              <div className="mm-rail-sub" style={{ padding: "4px 12px" }}>No sessions here yet.</div>
            )}
          </section>
        );
      })}

      {archivedSection}

      {showNewWorkspace && (
        <NewWorkspaceDialog
          onClose={() => setShowNewWorkspace(false)}
          onCreated={() => {
            refreshWorkspaces();
            // A new (empty) workspace should render expanded.
            localStorage.removeItem(collapseKey(null));
            setTick((n) => n + 1);
          }}
        />
      )}
      {peekWorkspace && (
        <WorkspaceContextPeek workspace={peekWorkspace} onClose={() => setPeekWorkspace(null)} />
      )}
    </nav>
  );
}
