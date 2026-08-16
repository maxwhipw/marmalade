// App.tsx — the shell: top-bar wordmark + tabs, a simple view switcher, and the
// chat/artifact split. No router library (spec "simplicity": v0 views don't
// need URLs — a view switcher is fine). The Shell seam is passed down for
// external links / notifications; nothing here touches a native API.

import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useGateway } from "./gateway-context.js";
import type { Shell } from "../shell/shell.js";
import { ChatView } from "../views/ChatView.js";
import { SessionsRail } from "../views/SessionsRail.js";
import { SearchView } from "../views/SearchView.js";
import { SettingsView } from "../views/SettingsView.js";
import { CronView } from "../views/CronView.js";
import { UsageView } from "../views/UsageView.js";
import { EditorView } from "../views/EditorView.js";
import { TerminalView } from "../views/TerminalView.js";
import { ArtifactPanel } from "../views/ArtifactPanel.js";
import { ApprovalsSheet } from "../views/ApprovalsSheet.js";
import { ClarifySheet } from "../views/ClarifySheet.js";
import type { FencedBlock } from "../components/markdown.js";
import type { ChatOpenTarget } from "../components/jump.js";

type Tab = "chat" | "search" | "editor" | "terminal" | "cron" | "usage" | "settings";

export function App({ shell }: { shell: Shell }): ReactNode {
  const { status, client, mainSessionId } = useGateway();
  const [tab, setTab] = useState<Tab>("chat");
  const [activeSession, setActiveSession] = useState<string | null>(null);
  // A deep link from a search hit: which message the chat should open at, and
  // the query the match navigator keeps alive (components/jump.ts). Cleared by
  // any plain open, so switching sessions never carries a stale navigator.
  const [chatTarget, setChatTarget] = useState<ChatOpenTarget | null>(null);
  const [artifact, setArtifact] = useState<FencedBlock | null>(null);
  const [artifactOpen, setArtifactOpen] = useState(false);
  // Mobile is single-pane (the rail and the chat can't share a phone width):
  // "list" shows the sessions rail, "chat" shows the conversation. CSS drives
  // the actual show/hide off data-mobile-view; desktop ignores it (both panes
  // render side by side). Picking or creating a session slides to chat; the
  // chat's back bar returns to the list.
  const [mobileView, setMobileView] = useState<"list" | "chat">("list");
  // Homed once = the startup default (or any explicit pick, incl. "+ New" →
  // null) has happened. Guards the assistant-home effect from clobbering a
  // deliberate "+ New" the moment session.main resolves.
  const homedRef = useRef(false);
  const openSession = (id: string | null, target?: ChatOpenTarget | null) => {
    homedRef.current = true;
    setActiveSession(id);
    setChatTarget(target ?? null);
    setMobileView("chat");
  };

  // Assistant home: seed the chat surface to THE main session (session.main,
  // resolved on connect) exactly ONCE at startup. Set activeSession directly
  // rather than via openSession so we don't force the mobile single-pane to
  // "chat" — the phone lands on the session list (assistant pinned at top),
  // desktop shows the assistant conversation. After the first home the user
  // drives activeSession ("+ New" opens a fresh deferred-create conversation).
  useEffect(() => {
    if (homedRef.current || activeSession) return;
    if (mainSessionId) {
      homedRef.current = true;
      setActiveSession(mainSessionId);
    }
  }, [mainSessionId, activeSession]);

  // Reconnecting to a daemon without the search index must not leave the shell
  // parked on a tab that no longer exists.
  useEffect(() => {
    if (tab === "search" && !client.hasFeature("search")) setTab("chat");
  }, [tab, client, status]);

  // The editor tab is feature-gated OFF until the daemon advertises "fs"
  // (spec view 4). hasFeature reads the negotiated hello features.
  const hasFs = client.hasFeature("fs");
  // Terminals: advertised only when the daemon's PTY backend loaded AND
  // config allows (terminal_enabled) — the gate is the hello feature.
  const hasTerminal = client.hasFeature("terminal");
  // Search: the tab isn't disabled, it's ABSENT on a daemon without the index —
  // a search entry point that can't search is worse than none (design-lab
  // labs/session-search, lab 2 frame 3).
  const hasSearch = client.hasFeature("search");

  const openArtifact = (block: FencedBlock) => {
    setArtifact(block);
    setArtifactOpen(true);
  };

  return (
    <div className="mm-app" data-mobile-view={mobileView}>
      <header className="mm-topbar">
        <span className="mm-wordmark">marmalade</span>
        <nav className="mm-tabs">
          <button className="mm-tab" aria-selected={tab === "chat"} onClick={() => setTab("chat")}>
            Chat
          </button>
          {hasSearch && (
            <button className="mm-tab" aria-selected={tab === "search"} onClick={() => setTab("search")}>
              Search
            </button>
          )}
          <button
            className="mm-tab"
            aria-selected={tab === "editor"}
            disabled={!hasFs}
            title={hasFs ? undefined : "Enabled when the daemon ships fs.read/write (phase P2)"}
            onClick={() => setTab("editor")}
          >
            Editor
          </button>
          <button
            className="mm-tab"
            aria-selected={tab === "terminal"}
            disabled={!hasTerminal}
            title={hasTerminal ? undefined : "Enabled when the daemon advertises terminal support"}
            onClick={() => setTab("terminal")}
          >
            Terminal
          </button>
          <button className="mm-tab" aria-selected={tab === "cron"} onClick={() => setTab("cron")}>
            Cron
          </button>
          <button className="mm-tab" aria-selected={tab === "usage"} onClick={() => setTab("usage")}>
            Usage
          </button>
          <button className="mm-tab" aria-selected={tab === "settings"} onClick={() => setTab("settings")}>
            Settings
          </button>
        </nav>
        <span className={`mm-status-dot ${status}`}>
          <span className="dot" /> {status}
        </span>
      </header>

      {tab === "chat" && (
        <>
          <SessionsRail activeId={activeSession} onSelect={(id) => { openSession(id); setArtifactOpen(false); }} />
          <main className="mm-main">
            <ChatView
              sessionId={activeSession}
              openTarget={chatTarget}
              onSessionCreated={openSession}
              onOpenArtifact={openArtifact}
              onBack={() => setMobileView("list")}
            />
            {artifactOpen && <ArtifactPanel block={artifact} onClose={() => setArtifactOpen(false)} />}
          </main>
        </>
      )}

      {tab === "search" && hasSearch && (
        <main className="mm-main" style={{ gridColumn: "1 / -1" }}>
          <SearchView
            onOpenSession={(id, at) => {
              openSession(id, at ?? null);
              setArtifactOpen(false);
              setTab("chat");
            }}
          />
        </main>
      )}

      {tab === "editor" && (
        <>
          <SessionsRail activeId={activeSession} onSelect={openSession} />
          <main className="mm-main">
            <EditorView hasFs={hasFs} />
          </main>
        </>
      )}

      {tab === "terminal" && (
        <main className="mm-main" style={{ gridColumn: "1 / -1" }}>
          <TerminalView />
        </main>
      )}

      {tab === "cron" && (
        <main className="mm-main" style={{ gridColumn: "1 / -1" }}>
          <CronView />
        </main>
      )}

      {tab === "usage" && (
        <main className="mm-main" style={{ gridColumn: "1 / -1" }}>
          <UsageView />
        </main>
      )}

      {tab === "settings" && (
        <main className="mm-main" style={{ gridColumn: "1 / -1" }}>
          <SettingsView />
        </main>
      )}

      {/* Dormant until the daemon emits approval.request (spec view 5 / M2). */}
      <ApprovalsSheet />
      {/* Agent questions (AskUserQuestion → clarify.request round-trip). */}
      <ClarifySheet />
      {/* Shell seam is threaded but unused by v0 views beyond external links in
          artifacts later; referenced here so the wiring is explicit. */}
      <span hidden data-platform={shell.platformLabel} />
    </div>
  );
}
