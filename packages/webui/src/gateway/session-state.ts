// session-state.ts — one session's message stream, built from stamped events.
//
// A TS twin of the Android client's MessageStream (the port target in the
// client repo's CLAUDE.md). It honors the locked session-ids invariants:
//
//   1. IDs are names. It adopts the server's message_id verbatim; it never
//      synthesizes one when the server minted one (rule 1).
//   2. seq orders and dedups. A per-session watermark drops any payload whose
//      seq we've already applied — replayed and live events flow through the
//      SAME apply() path, so a subscribe-boundary duplicate is silently deduped
//      (rules 2, 5).
//   3. A finalized message stores the COMPLETE event's seq, not the start's,
//      so the replay cursor lands after the whole turn (rule 3). A mid-stream
//      partial keeps its start seq and is rebuilt if message.start replays for
//      the same id (the stale row is dropped).
//   4. Unread is arithmetic: last_seq > seen_seq (rule 2). No wall clock.
//
// This module is pure — no socket, no React. That's what makes the digital-twin
// test possible: feed it scripted frames, assert the derived state.

import { contextFromUsage, type ContextOccupancy } from "../components/context.js";
import { readClamp } from "../components/efforts.js";
import type { ChatMessage, StampedPayload, ToolCard } from "./types.js";

export interface SessionState {
  /** Messages in seq order (assistant + user), the render list. */
  messages: ChatMessage[];
  /** Highest seq applied — the watermark (dedup) AND the replay cursor
   *  (subscribe(since_seq)) AND the unread numerator (last_seq). */
  lastSeq: number;
  runState: string;
  lifecycle: string;
  /** True between session.compaction started and its terminal (completed/failed/
   *  boundary) — drives the transient "compacting…" chip (T2 #11a). Never
   *  persisted; a fresh state starts false. */
  compacting: boolean;
  /** Context-window occupancy from the LAST message.complete seen live in this
   *  session, or null before any (then the chip falls back to the session.list
   *  row's persisted seed — components/context.ts::resolveContext). Cleared by
   *  session.cleared, which empties the window. */
  context: ContextOccupancy | null;
}

export function emptySessionState(): SessionState {
  return { messages: [], lastSeq: 0, runState: "idle", lifecycle: "active", compacting: false, context: null };
}

/** Extract the stamped payload from an event's params.payload. */
function payloadOf(payload: unknown): StampedPayload {
  return (payload as StampedPayload) ?? {};
}

/** Apply one stamped session event to the state, returning a NEW state (the
 *  React reducer contract). `type` is the event name; `payload` is the raw
 *  event payload. Returns the same reference when the event is a duplicate or
 *  irrelevant, so callers can skip a re-render.
 *
 * Watermark dedup is the FIRST gate: replay and live share this path, so a
 * frame at or below the watermark (a subscribe-boundary re-send, or a
 * reconnect overlap) is dropped before it can double-apply. message.start is
 * exempt from the drop because it legitimately rebuilds a stale partial row
 * (rule 3) — its own seq still advances the watermark. */
export function applyEvent(
  state: SessionState,
  type: string,
  payload: unknown,
): SessionState {
  const p = payloadOf(payload);
  const seq = typeof p.seq === "number" ? p.seq : undefined;

  // Dedup: everything except a message.start rebuild is dropped at/below the
  // watermark. (A replayed message.start for an in-flight id must still be
  // allowed to reset the partial — see below.) message.user is also exempt:
  // the submitter injects its OWN message optimistically (the daemon withholds
  // message.user from the sender), and the assistant stream may already have
  // advanced lastSeq past the user's seq — appendUserMessage dedups by id, so
  // letting it through is safe and a replay never doubles it.
  if (seq !== undefined && seq <= state.lastSeq && type !== "message.start" && type !== "message.user") {
    return state;
  }

  switch (type) {
    case "message.user":
      return appendUserMessage(state, p);
    case "message.start":
      return startAssistantMessage(state, p);
    case "message.delta":
      return appendDelta(state, p);
    case "message.complete":
      return completeAssistant(state, p);
    case "tool.start":
      return startTool(state, p);
    case "tool.complete":
      return completeTool(state, p);
    case "status.update":
      return applyStatus(state, p);
    case "session.compaction":
      return applyCompaction(state, p);
    case "session.undone":
      return applyUndone(state, p);
    case "session.cleared":
      return applyCleared(state);
    case "effort.clamped":
      return appendClampNotice(state, p);
    default:
      // Ordered but not rendered (reasoning.delta, session.info, error handled
      // by the client, etc.) — still advance the watermark so the cursor moves.
      return advanceSeq(state, seq);
  }
}

function advanceSeq(state: SessionState, seq: number | undefined): SessionState {
  if (seq === undefined || seq <= state.lastSeq) return state;
  return { ...state, lastSeq: seq };
}

function appendUserMessage(state: SessionState, p: StampedPayload): SessionState {
  const id = p.message_id ?? `user-${p.seq}`;
  if (state.messages.some((m) => m.id === id)) return advanceSeq(state, p.seq);
  const msg: ChatMessage = {
    id,
    role: "user",
    text: typeof p.text === "string" ? p.text : "",
    seq: p.seq ?? 0,
    streaming: false,
    origin: p.origin,
    tools: [],
    // session.steer marks the user row (payload steered:true) so it renders
    // distinctly; the sender sets it optimistically too.
    ...(p.steered === true ? { steered: true } : {}),
  };
  // Insert in seq order rather than blind-append: the optimistic own-message
  // can be applied AFTER a racing assistant stream already appended, and the
  // render list must stay user-before-its-reply.
  const messages = [...state.messages];
  const at = messages.findIndex((m) => m.seq > msg.seq);
  if (at === -1) messages.push(msg);
  else messages.splice(at, 0, msg);
  return {
    ...state,
    messages,
    lastSeq: Math.max(state.lastSeq, p.seq ?? 0),
  };
}

function startAssistantMessage(state: SessionState, p: StampedPayload): SessionState {
  const id = p.message_id ?? `assistant-${p.seq}`;
  // A replayed start for an id we already have: drop the stale partial and
  // rebuild fresh (rule 3 — MessageStream deletes the stale row for the id).
  const without = state.messages.filter((m) => m.id !== id);
  const msg: ChatMessage = {
    id,
    role: "assistant",
    text: "",
    seq: p.seq ?? 0,
    streaming: true,
    origin: p.origin,
    tools: [],
  };
  return {
    ...state,
    messages: [...without, msg],
    lastSeq: Math.max(state.lastSeq, p.seq ?? 0),
    // A new assistant turn means compaction is over — backstops the chip if a
    // daemon hard-crash between a cached `started` and its terminal left the
    // transcript `started`-terminated (replay would otherwise re-wedge it).
    compacting: false,
  };
}

function appendDelta(state: SessionState, p: StampedPayload): SessionState {
  const id = p.message_id;
  const text = typeof p.text === "string" ? p.text : "";
  const idx = id ? state.messages.findIndex((m) => m.id === id) : -1;
  if (idx < 0) {
    // A delta with no open message (ACP streams omit message.start): synthesize
    // one keyed on the id, mirroring the daemon's ensureOpen (identity.ts),
    // then append the text into it.
    return appendDelta(startAssistantMessage(state, p), p);
  }
  const messages = state.messages.slice();
  messages[idx] = { ...messages[idx], text: messages[idx].text + text };
  return { ...state, messages, lastSeq: Math.max(state.lastSeq, p.seq ?? 0) };
}

function completeAssistant(state: SessionState, p: StampedPayload): SessionState {
  const id = p.message_id;
  const idx = id ? state.messages.findIndex((m) => m.id === id) : -1;
  const messages = state.messages.slice();
  if (idx >= 0) {
    // Rule 3: the finalized row stores the COMPLETE event's seq so the replay
    // cursor lands after the whole turn.
    messages[idx] = {
      ...messages[idx],
      streaming: false,
      seq: p.seq ?? messages[idx].seq,
      // has_cut_point (additive): tri-state — absent on pre-flag transcripts
      // keeps hasCutPoint undefined (legacy branch offer stays).
      ...(typeof p.has_cut_point === "boolean" ? { hasCutPoint: p.has_cut_point } : {}),
    };
  }
  // The turn's usage block carries live context occupancy — keep the last
  // reading; a turn that reports none (ACP harness, or a pre-usage transcript
  // replay) leaves the previous one standing rather than blanking the chip.
  const context = contextFromUsage(p.usage) ?? state.context;
  return { ...state, messages, context, lastSeq: Math.max(state.lastSeq, p.seq ?? 0) };
}

function startTool(state: SessionState, p: StampedPayload): SessionState {
  const messageId = p.message_id;
  const idx = messageId ? state.messages.findIndex((m) => m.id === messageId) : -1;
  if (idx < 0) return advanceSeq(state, p.seq);
  const card: ToolCard = {
    toolUseId: typeof p.id === "string" ? p.id : undefined,
    name: typeof p.name === "string" ? p.name : "tool",
    seq: p.seq ?? 0,
    running: true,
  };
  const messages = state.messages.slice();
  messages[idx] = { ...messages[idx], tools: [...messages[idx].tools, card] };
  return { ...state, messages, lastSeq: Math.max(state.lastSeq, p.seq ?? 0) };
}

function completeTool(state: SessionState, p: StampedPayload): SessionState {
  const toolUseId = typeof p.tool_use_id === "string" ? p.tool_use_id : undefined;
  const messages = state.messages.map((m) => {
    if (!m.tools.some((t) => t.toolUseId === toolUseId)) return m;
    return {
      ...m,
      tools: m.tools.map((t) =>
        t.toolUseId === toolUseId
          ? { ...t, running: false, durationMs: typeof p.duration_ms === "number" ? p.duration_ms : undefined }
          : t,
      ),
    };
  });
  return { ...state, messages, lastSeq: Math.max(state.lastSeq, p.seq ?? 0) };
}

function applyStatus(state: SessionState, p: StampedPayload): SessionState {
  const lifecycle = typeof p.lifecycle === "string" ? p.lifecycle : state.lifecycle;
  return {
    ...state,
    runState: typeof p.run_state === "string" ? p.run_state : state.runState,
    lifecycle,
    // A compacting chip must never outlive the session — if the terminal
    // session.compaction was missed, an ended lifecycle clears it. (Manual
    // compaction happens while idle, so we do NOT key the clear on run_state.)
    compacting: lifecycle === "ended" ? false : state.compacting,
    lastSeq: Math.max(state.lastSeq, p.seq ?? 0),
  };
}

/** session.compaction (T2 #11a): started → show the chip; any terminal
 *  (completed/failed/boundary) → clear it. The daemon relays the harness's
 *  compaction signals (manual via session.compact OR the harness's own
 *  auto-compact); auto surfaces through the same event for free. */
function applyCompaction(state: SessionState, p: StampedPayload): SessionState {
  const next = p.status === "started";
  if (next === state.compacting) return advanceSeq(state, p.seq);
  return { ...state, compacting: next, lastSeq: Math.max(state.lastSeq, p.seq ?? 0) };
}

/** session.undone (transient — no seq, never cached): the last completed turn
 *  was popped (session.undo). Drop the popped bubbles live; a client that
 *  ignores it reconciles on its next replay (the daemon already truncated the
 *  transcript, so replay is consistent by construction). */
function applyUndone(state: SessionState, p: StampedPayload): SessionState {
  const ids = Array.isArray(p.popped_message_ids) ? (p.popped_message_ids as unknown[]) : [];
  if (ids.length === 0) return state;
  const popped = new Set(ids.filter((x): x is string => typeof x === "string"));
  const messages = state.messages.filter((m) => !popped.has(m.id));
  if (messages.length === state.messages.length) return state;
  return { ...state, messages };
}

/** session.cleared (transient — no seq, never cached): the conversation was
 *  reset in place (session.clear). Same session id — this is state surgery, not
 *  a delete. Empty the render list, drop any compacting chip and the context
 *  reading; the watermark
 *  is KEPT because the daemon preserves the session's seq high-water on clear
 *  (a cleared seq is never reissued — session-ids rule 1), so the next turn's
 *  events stay above it and pass the dedup gate. A client that misses the event
 *  reconciles on its next replay (the transcript was already deleted). */
function applyCleared(state: SessionState): SessionState {
  if (state.messages.length === 0 && !state.compacting && state.context === null) return state;
  // Context goes back to unknown, matching the daemon (session.clear nulls the
  // stored columns): a pre-clear number would overstate a now-empty window.
  return { ...state, messages: [], compacting: false, context: null };
}

/** effort.clamped (design-lab E3): a per-model bound moved the requested
 *  reasoning effort. DURABLE — the daemon stamps and caches it, so it arrives
 *  live AND replays on a cold load; both paths run through here, and the
 *  watermark gate above already dropped the subscribe-boundary duplicate. It
 *  becomes a muted "notice" row in seq order, never a turn. A payload we can't
 *  read (a future shape) just advances the cursor. */
function appendClampNotice(state: SessionState, p: StampedPayload): SessionState {
  const clamp = readClamp(p);
  if (!clamp) return advanceSeq(state, p.seq);
  const seq = p.seq ?? 0;
  const id = p.message_id ?? `effort-clamped-${seq}`;
  if (state.messages.some((m) => m.id === id)) return advanceSeq(state, p.seq);
  const msg: ChatMessage = {
    id,
    role: "notice",
    // The wording is derived at render from the live model catalog, so the
    // line follows a model's label instead of freezing yesterday's.
    text: "",
    seq,
    streaming: false,
    tools: [],
    clamp,
  };
  // Same seq-ordered insert as a user row: a clamp minted at session.create
  // lands before the first turn even if it is applied after one.
  const messages = [...state.messages];
  const at = messages.findIndex((m) => m.seq > seq);
  if (at === -1) messages.push(msg);
  else messages.splice(at, 0, msg);
  return { ...state, messages, lastSeq: Math.max(state.lastSeq, seq) };
}

/** Unread is arithmetic (session-ids rule 2): last_seq > seen_seq, never a
 *  wall-clock heuristic. */
export function isUnread(lastSeq: number, seenSeq: number): boolean {
  return lastSeq > seenSeq;
}
