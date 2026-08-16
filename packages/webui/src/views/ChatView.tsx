// ChatView.tsx — the chat surface (spec view 1).
//
// Streaming bubbles, tool cards, a model chip/picker with a Default row, an
// interrupt button while run_state=running, and a run-state chip. Session
// creation is DEFERRED to first send (Android semantics — spec, session-ids
// rule 4): with no session open, the composer holds a picked model; the first
// prompt creates the session with that model, then submits.

import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useGateway } from "../app/gateway-context.js";
import { useSettings } from "../settings/provider.js";
import { MessageBubble } from "../components/MessageBubble.js";
import { clampNoticeText } from "../components/efforts.js";
import { ModelPicker } from "../components/ModelPicker.js";
import { Icon } from "../components/Icon.js";
import { isUnread } from "../gateway/session-state.js";
import { forkSuccessToast, isNoForkError, NO_FORK_WARNING } from "../components/fork.js";
import { contextChipLabel, contextChipTitle, resolveContext } from "../components/context.js";
import { FindInConversation } from "./FindInConversation.js";
import { buildSearchParams } from "../components/search.js";
import {
  collectSessionMatches,
  jumpPillText,
  matchIndexOf,
  resolveJumpAnchor,
  shouldAutoscroll,
  stepMatchIndex,
  type ChatOpenTarget,
  type JumpTarget,
  type MatchRef,
} from "../components/jump.js";
import type { FencedBlock } from "../components/markdown.js";
import {
  buildSubmitText,
  fileToBase64,
  fileToDataUrl,
  stageFile,
  type StagedAttachment,
} from "../components/attachments.js";

/** The match navigator: the query kept alive after a deep link, plus this
 *  session's matches in transcript order and where in them we are. */
interface NavigatorState {
  query: string;
  matches: MatchRef[];
  index: number;
  /** The daemon's true match count (may exceed `matches.length` — see MATCH_CAP). */
  total: number;
  capped: boolean;
  loading: boolean;
  error: string | null;
}

/** Give up waiting for a target that never replays (deleted, compacted away) —
 *  the clock restarts on every new frame, so a slow replay is never cut off. */
const JUMP_TIMEOUT_MS = 15_000;
const JUMP_PILL_MS = 3_000;

interface Props {
  /** The open session, or null for a new (not-yet-created) conversation. */
  sessionId: string | null;
  onSessionCreated: (id: string) => void;
  onOpenArtifact: (block: FencedBlock) => void;
  /** A deep link from a search hit: open this session scrolled to that message,
   *  with the match navigator armed for the query. null = a plain open. */
  openTarget?: ChatOpenTarget | null;
  /** Return to the sessions list on mobile (single-pane). The back bar is
   *  CSS-hidden on desktop, so this is a no-op there. */
  onBack?: () => void;
}

export function ChatView({ sessionId, onSessionCreated, onOpenArtifact, openTarget, onBack }: Props): ReactNode {
  const { client, sessionStates, sessions, models, defaultModel, status, refreshSessions } = useGateway();
  const { settings } = useSettings();
  const [draft, setDraft] = useState("");
  const [pickedModel, setPickedModel] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Non-blocking fork toast (spec: show result.warning, don't block). Local to
  // the chat surface — it survives the session switch on a successful branch
  // because ChatView stays mounted (only sessionId changes).
  const [toast, setToast] = useState<string | null>(null);
  // Staged (not yet uploaded) attachments — uploads happen lazily inside
  // send(), against the possibly just-created session (attachments.ts).
  const [staged, setStaged] = useState<StagedAttachment[]>([]);
  const [sendError, setSendError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const attachmentsSupported = client.hasFeature("attachments");
  const undoSupported = client.hasFeature("undo");
  // Find-in-conversation is scope-of-one over search.messages — gated on the
  // same "search" feature as the cross-session view (no index, no affordance).
  const searchSupported = client.hasFeature("search");
  const [findOpen, setFindOpen] = useState(false);
  // Open-at-seq: a jump that hasn't landed yet (the target may still be
  // replaying), the bubble it landed on, and the transient distance pill.
  const [nav, setNav] = useState<NavigatorState | null>(null);
  const [pendingJump, setPendingJump] = useState<JumpTarget | null>(null);
  const [focusId, setFocusId] = useState<string | null>(null);
  const [jumpPill, setJumpPill] = useState<string | null>(null);
  // Bumped on every navigator start/dismiss so a slow match collection can't
  // land on a navigator that's already been replaced or closed.
  const navRunRef = useRef(0);

  const state = sessionId ? sessionStates.get(sessionId) : undefined;
  const row = sessionId ? sessions.find((s) => s.session_id === sessionId) : undefined;

  // Attach on open (resume + subscribe from the watermark).
  useEffect(() => {
    if (sessionId) void client.openSession(sessionId);
  }, [client, sessionId]);

  // A find panel belongs to ONE conversation — switching sessions closes it
  // rather than silently re-scoping its results.
  useEffect(() => {
    setFindOpen(false);
  }, [sessionId]);

  // Stamp seen once the view has rendered up to the watermark (spec view 1 /
  // session-ids rule 2 — seen stamps on render).
  useEffect(() => {
    if (sessionId && state && isUnread(state.lastSeq, client.getSeenSeq(sessionId))) {
      void client.markSeen(sessionId);
    }
  }, [client, sessionId, state]);

  // Autoscroll on new content — suppressed while a jump is in flight or the
  // navigator is open (it would fight the very replay that delivers the target,
  // and later drag you off the match you're reading).
  useEffect(() => {
    if (!shouldAutoscroll({ pendingJump: pendingJump !== null, navigatorOpen: nav !== null })) return;
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [state?.messages, pendingJump, nav]);

  // ── Open at a message ─────────────────────────────────────────────────────
  // Arm a jump + the navigator for `query` in this session. The jump is only
  // REQUESTED here; it lands in the effect below, once the target has replayed.
  const startNavigator = (sid: string, t: { messageId: string; seq: number; query: string }) => {
    const run = ++navRunRef.current;
    setNav({ query: t.query, matches: [{ messageId: t.messageId, seq: t.seq }], index: 0, total: 1, capped: false, loading: true, error: null });
    setPendingJump({ messageId: t.messageId, seq: t.seq });
    setJumpPill(null);
    void collectSessionMatches((offset, limit) =>
      client
        .searchMessages(
          buildSearchParams(
            t.query,
            { workspaceIds: [], quickChats: false, sessionIds: [sid] },
            // rank vs recent doesn't matter: the navigator re-sorts into
            // transcript order. include_archived so a session you archived
            // still finds its own matches.
            { sort: "rank", includeArchived: true, limit, offset },
          ),
        )
        .then((r) => ({ hits: r.hits, total: r.total })),
    ).then(
      (c) => {
        if (navRunRef.current !== run) return;
        setNav((prev) =>
          prev && {
            ...prev,
            matches: c.matches.length > 0 ? c.matches : prev.matches,
            index: c.matches.length > 0 ? matchIndexOf(c.matches, t) : 0,
            total: Math.max(c.total, 1),
            capped: c.capped,
            loading: false,
          },
        );
      },
      (e: Error) => {
        if (navRunRef.current !== run) return;
        setNav((prev) => prev && { ...prev, loading: false, error: e.message });
      },
    );
  };

  const closeNavigator = () => {
    navRunRef.current++;
    setNav(null);
    setPendingJump(null);
    setFocusId(null);
    setJumpPill(null);
  };

  // A deep link arms the navigator; any other open (session switch, plain open
  // from the rail) clears it — a navigator belongs to ONE query in ONE session.
  useEffect(() => {
    // searchSupported gates it exactly like the rest of the search UI: without
    // the index there is no match list to walk, so there is no navigator.
    if (openTarget && sessionId && searchSupported && openTarget.sessionId === sessionId) {
      startNavigator(sessionId, openTarget);
    } else {
      closeNavigator();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openTarget, sessionId, searchSupported]);

  // Land the pending jump. The transcript replays frame by frame, so this runs
  // again on every messages change until the target exists — then it scrolls,
  // highlights, and clears. If it never arrives we stop waiting rather than
  // suppress autoscroll forever.
  useEffect(() => {
    if (!pendingJump) return;
    const messages = state?.messages ?? [];
    const anchor = resolveJumpAnchor(messages, pendingJump);
    if (anchor) {
      const sel = `[data-message-id="${typeof CSS !== "undefined" && CSS.escape ? CSS.escape(anchor) : anchor}"]`;
      const el = scrollRef.current?.querySelector(sel) as HTMLElement | null | undefined;
      el?.scrollIntoView?.({ block: "center" });
      setFocusId(anchor);
      setJumpPill(jumpPillText(messages, anchor));
      setPendingJump(null);
      return;
    }
    const t = setTimeout(() => setPendingJump(null), JUMP_TIMEOUT_MS);
    return () => clearTimeout(t);
  }, [pendingJump, state?.messages]);

  useEffect(() => {
    if (!jumpPill) return;
    const t = setTimeout(() => setJumpPill(null), JUMP_PILL_MS);
    return () => clearTimeout(t);
  }, [jumpPill]);

  // ↑/↓ walk this session's matches in transcript order, clamped at the ends.
  const stepMatch = (dir: 1 | -1) => {
    if (!nav) return;
    const next = stepMatchIndex(nav.index, nav.matches.length, dir);
    if (next === nav.index) return;
    setNav({ ...nav, index: next });
    setPendingJump(nav.matches[next]);
  };

  // Auto-dismiss the fork toast (non-blocking); dismissible by hand too.
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 8000);
    return () => clearTimeout(t);
  }, [toast]);

  const runState = state?.runState ?? row?.run_state ?? "idle";
  const running = runState === "running";

  // Context occupancy: the row's persisted seed makes a COLD-opened session
  // show a number immediately; a live message.complete usage block supersedes
  // it. Switching sessions reseeds for free (both inputs are per-session).
  // null → no chip at all, never a fabricated percentage.
  const context = resolveContext(state?.context, row);

  const send = async () => {
    const text = draft.trim();
    if ((!text && staged.length === 0) || busy) return;
    setBusy(true);
    setSendError(null);
    try {
      // Steer: a running turn takes the message as mid-turn guidance
      // (session.steer) instead of starting a new turn. Attachments don't ride
      // a steer — the daemon consumes staged files only on prompt.submit, so we
      // require text and leave any staged chips for the next normal send.
      if (sessionId && running) {
        if (!text) return;
        await client.steerSession(sessionId, text, "text");
        setDraft("");
        // Participating again ends the search landing: the navigator (and its
        // autoscroll suppression) would otherwise hide your own reply.
        closeNavigator();
        return;
      }
      let id = sessionId;
      if (!id) {
        // Deferred create: the picked model rides session.create.
        id = await client.createSession(pickedModel ? { model: pickedModel } : {});
        onSessionCreated(id);
      }
      // Upload staged files now — the daemon queues them on the session and
      // the submit below consumes the queue. A failed upload aborts the send
      // with draft + chips intact (nothing was submitted yet).
      const refTexts: string[] = [];
      for (const a of staged) {
        if (a.kind === "image") {
          await client.attachImageBytes(id, await fileToBase64(a.file), a.file.name);
        } else {
          const ref = await client.attachFile(id, a.file.name, await fileToDataUrl(a.file));
          if (ref) refTexts.push(ref);
        }
      }
      const submitText = buildSubmitText(text, refTexts, staged.some((a) => a.kind === "image"));
      await client.submitPrompt(id, submitText, "text");
      setDraft("");
      setStaged([]);
      closeNavigator();
    } catch (e) {
      setSendError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  // Branch (session.fork): at an assistant message (atMessageId) or at the end
  // of the chat (no atMessageId). On success, refresh the rail so the new row +
  // its "branched from" chip appear, open the fork, and toast the soft warning.
  // A no-fork harness rejects with "cannot fork … seeded" — the webui has no
  // seed-create branch, so it shows the heavier context-loss warning instead
  // (the marmalade daemon's Claude harness always forks; this is OpenCode-only).
  const branch = async (atMessageId?: string) => {
    if (!sessionId || busy) return;
    setBusy(true);
    try {
      const result = await client.forkSession(sessionId, atMessageId ? { atMessageId } : {});
      refreshSessions();
      onSessionCreated(result.session_id);
      setToast(forkSuccessToast(result));
    } catch (e) {
      setToast(isNoForkError(e) ? NO_FORK_WARNING : `Branch failed: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  // Compact (session.compact): trigger a manual context compaction. The daemon
  // rejects while a turn is in flight, so the button is disabled when running;
  // progress shows via the "compacting…" chip (session.compaction events).
  const compact = async () => {
    if (!sessionId || busy) return;
    setBusy(true);
    try {
      await client.compactSession(sessionId);
    } catch (e) {
      setToast(`Compact failed: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  // Model change (session.model): swap the open session's model in place. The
  // daemon restarts the idle child so it applies now (context carries over via
  // harness resume) and rejects while a turn is in flight — the picker is
  // disabled then. A null pick (the "Default" row) is ignored on an existing
  // session: session.model has no "unset".
  const changeModel = async (modelId: string) => {
    if (!sessionId || busy) return;
    try {
      await client.setModel(sessionId, modelId);
      refreshSessions(); // the row's model updates so the picker reflects it
    } catch (e) {
      setToast(`Model change failed: ${(e as Error).message}`);
    }
  };

  // Clear (session.clear): reset the main session's conversation in place —
  // its only "start over" (the main session can't be deleted). The view empties
  // off the transient session.cleared event, not this ack. Rejected while a
  // turn is in flight, so it's disabled when running.
  const clearConversation = async () => {
    if (!sessionId || busy) return;
    if (!confirm("Clear the assistant conversation? It resets to a fresh context — this can't be undone.")) return;
    setBusy(true);
    try {
      await client.clearSession(sessionId);
    } catch (e) {
      setToast(`Clear failed: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  // Undo (session.undo): pop the last completed turn. The popped bubbles drop
  // live via the session.undone event; the result is only for this notice —
  // v1 rewinds the conversation, NOT file edits (files_rewound is always
  // false). Feature-gated on hasFeature("undo"); disabled while running.
  const undo = async () => {
    // busy-latch is load-bearing here: undo is DESTRUCTIVE (the daemon deletes
    // the popped rows/transcript and there's no un-undo), so a double-click
    // must not fire two session.undo RPCs and pop two turns.
    if (!sessionId || busy) return;
    setBusy(true);
    try {
      const r = await client.undoSession(sessionId);
      setToast(
        r.files_rewound
          ? "Last turn undone."
          : "Last turn undone — file edits made during that turn were NOT reverted.",
      );
    } catch (e) {
      setToast(`Undo failed: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const addFiles = (files: FileList | null) => {
    if (!files) return;
    setStaged((prev) => [...prev, ...Array.from(files).map(stageFile)]);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    const enterSends = settings.chat.sendMode === "enter";
    const combo = enterSends ? !e.shiftKey : e.ctrlKey || e.metaKey;
    if (e.key === "Enter" && combo) {
      e.preventDefault();
      void send();
    }
  };

  const disconnected = status !== "connected";

  const title = row?.title ?? row?.topic ?? (sessionId ? "untitled" : "New conversation");

  return (
    <div className="mm-chat">
      {onBack && (
        <div className="mm-chat-mobilebar">
          <button className="mm-btn ghost small" onClick={onBack} aria-label="Back to sessions">‹ Sessions</button>
          <span className="mm-chat-mobilebar-title">{title}</span>
        </div>
      )}
      {findOpen && sessionId && searchSupported && (
        <FindInConversation
          sessionId={sessionId}
          onClose={() => setFindOpen(false)}
          onJump={(t) => {
            // Same landing as a cross-session deep link: jump in place and keep
            // the query alive in the navigator. The panel has done its job.
            setFindOpen(false);
            startNavigator(sessionId, t);
          }}
        />
      )}
      {nav && (
        <div className="mm-matchnav" role="status">
          <span className="mm-matchnav-q" title={`Matches for “${nav.query}” in this conversation`}>
            {nav.query}
          </span>
          <span className="mm-matchnav-count">
            {nav.loading ? "…" : `${nav.index + 1} / ${nav.total}`}
          </span>
          {nav.capped && (
            <span
              className="mm-matchnav-hint"
              title={`Too many matches to walk them all — the first ${nav.matches.length} in this conversation are navigable.`}
            >
              first {nav.matches.length}
            </span>
          )}
          {nav.error && <span className="mm-matchnav-hint">match list failed: {nav.error}</span>}
          <button
            className="mm-btn ghost small"
            aria-label="Previous match"
            title="Previous match in this conversation"
            disabled={nav.index <= 0}
            onClick={() => stepMatch(-1)}
          >
            ↑
          </button>
          <button
            className="mm-btn ghost small"
            aria-label="Next match"
            title="Next match in this conversation"
            disabled={nav.index >= nav.matches.length - 1}
            onClick={() => stepMatch(1)}
          >
            ↓
          </button>
          <button
            className="mm-btn ghost small"
            aria-label="Dismiss match navigator"
            title="Back to a normal session view"
            onClick={closeNavigator}
          >
            ×
          </button>
        </div>
      )}
      {jumpPill && <div className="mm-jump-pill">{jumpPill}</div>}
      <div className="mm-chat-scroll" ref={scrollRef}>
        {!sessionId && !draft && (
          <div className="mm-empty">
            New conversation. Pick a model and send a message to begin.
          </div>
        )}
        {state?.messages.map((m) => (
          m.role === "notice" && m.clamp ? (
            // effort.clamped (E3): a quiet, muted record — no color, nothing to
            // click, nothing to dismiss. Durable, so it replays on cold load.
            <div key={m.id} className="mm-notice" data-message-id={m.id}>
              {clampNoticeText(m.clamp, models)}
            </div>
          ) : (
          <MessageBubble
            key={m.id}
            message={m}
            focused={focusId === m.id}
            renderMd={settings.chat.renderMarkdown}
            toolsExpanded={settings.chat.toolCardsExpanded}
            onOpenArtifact={onOpenArtifact}
            onUiCallback={(text) => {
              // UI-tree callback = a plain user message through the normal
              // send path (spec §Interaction contract) — same as typing it.
              if (sessionId && !busy) void client.submitPrompt(sessionId, text, "text");
            }}
            onBranchFrom={sessionId && !running ? (id) => void branch(id) : undefined}
          />
          )
        ))}
      </div>
      {toast && (
        <div className="mm-toast" role="status">
          <span>{toast}</span>
          <button className="mm-toast-close" aria-label="Dismiss" onClick={() => setToast(null)}>×</button>
        </div>
      )}
      <div className="mm-composer">
        {sendError && (
          <div className="mm-hint" style={{ color: "var(--error)", gridColumn: "1 / -1" }}>
            {sendError}
          </div>
        )}
        {attachmentsSupported && staged.length > 0 && (
          <div className="mm-attach-chips">
            {staged.map((a) => (
              <span key={a.id} className="mm-chip" aria-pressed="false" title={a.file.name}>
                <Icon token={a.kind === "image" ? "icon.tool.image" : "icon.tool.read"} size={14} /> {a.file.name}
                <button
                  className="mm-attach-remove"
                  aria-label={`Remove ${a.file.name}`}
                  onClick={() => setStaged((prev) => prev.filter((s) => s.id !== a.id))}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        )}
        <div className="meta" style={{ flexDirection: "column", alignItems: "stretch", gap: 6 }}>
          <ModelPicker
            models={models}
            defaultModel={defaultModel}
            value={sessionId ? (row?.model ?? null) : pickedModel}
            onChange={(id) => {
              // New session: local intent that rides session.create. Existing
              // session: drive session.model live (ignore a null "Default" pick
              // — session.model has no "unset").
              if (sessionId) { if (id) void changeModel(id); }
              else setPickedModel(id);
            }}
            disabled={disconnected || running}
          />
          <span className="mm-chip" aria-pressed="false" title="run state">
            {runState}
          </span>
          {context && (
            <span className="mm-chip" aria-pressed="false" title={contextChipTitle(context)}>
              {contextChipLabel(context)}
            </span>
          )}
          {state?.compacting && (
            <span className="mm-chip" aria-live="polite" title="Compacting the conversation context">
              compacting…
            </span>
          )}
          {sessionId && searchSupported && (
            <button
              className="mm-btn ghost small"
              aria-pressed={findOpen}
              title="Find in this conversation (search.messages, scoped to this session)"
              onClick={() => setFindOpen((v) => !v)}
            >
              <Icon token="icon.ui.find" size={14} /> Find
            </button>
          )}
          {sessionId && (
            <button
              className="mm-btn ghost small"
              title="Branch a new chat from the end of this conversation (keeps full context)"
              disabled={disconnected || busy || running}
              onClick={() => void branch()}
            >
              ⑂ Branch this chat
            </button>
          )}
          {sessionId && (
            <button
              className="mm-btn ghost small"
              title="Compact the conversation context now (session.compact)"
              disabled={disconnected || busy || running}
              onClick={() => void compact()}
            >
              Compact now
            </button>
          )}
          {sessionId && row?.is_main && (
            <button
              className="mm-btn ghost small"
              title="Reset the assistant conversation (session.clear) — starts fresh; can't be undone"
              disabled={disconnected || busy || running}
              onClick={() => void clearConversation()}
            >
              Clear conversation
            </button>
          )}
          {sessionId && undoSupported && (
            <button
              className="mm-btn ghost small"
              title="Undo the last completed turn (session.undo) — conversation only; file edits are NOT reverted"
              disabled={disconnected || busy || running}
              onClick={() => void undo()}
            >
              ↩ Undo last turn
            </button>
          )}
        </div>
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder={disconnected ? "disconnected — connect in settings" : "Message marmalade…"}
          disabled={disconnected}
        />
        {attachmentsSupported && (
          <>
            <button
              className="mm-btn ghost"
              aria-label="Attach files"
              title="Attach files"
              disabled={disconnected || busy}
              onClick={() => fileRef.current?.click()}
            >
              <Icon token="icon.agent.attachment" size={18} title="Attach files" />
            </button>
            <input
              ref={fileRef}
              type="file"
              multiple
              style={{ display: "none" }}
              onChange={(e) => {
                addFiles(e.target.files);
                e.target.value = "";
              }}
            />
          </>
        )}
        {running ? (
          <>
            <button className="mm-btn outline" onClick={() => sessionId && void client.interrupt(sessionId)}>
              interrupt
            </button>
            <button
              className="mm-btn accent"
              title="Send a message to steer the running reply (session.steer)"
              onClick={() => void send()}
              disabled={disconnected || busy || !draft.trim()}
            >
              Steer →
            </button>
          </>
        ) : (
          <button
            className="mm-btn accent"
            onClick={() => void send()}
            disabled={disconnected || busy || (!draft.trim() && staged.length === 0)}
          >
            Send →
          </button>
        )}
      </div>
    </div>
  );
}
