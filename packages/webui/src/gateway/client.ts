// client.ts — the webui's gateway client: a TS twin of the Android transport
// (client repo .claude/rules/session-ids.md are the locked invariants).
//
// Responsibilities:
//   - Connect + hello on every connect (deviceId/platform/tzOffset/capabilities),
//     storing negotiated `features` — ALL feature-gated UI derives from this list
//     (spec "Protocol client", same pattern as Android's attachmentsSupported).
//   - Request/response with ack binding: a session.create result binds the local
//     synth session key to the server session_id (session-ids rules 1, 4).
//   - Attach = session.resume + session.subscribe(since_seq = watermark); replayed
//     events flow through the SAME dispatch as live ones (rule 5). Dedup is the
//     session-state watermark, not a separate history path.
//   - session.seen stamps the render cursor; unread is arithmetic (rule 2).
//   - Reconnect with backoff; on reconnect, re-hello then re-subscribe every
//     attached session from its watermark — a reconnect never kills a run.
//
// The client owns NO React. It exposes a small typed event emitter; the app's
// context adapts it to React state. That keeps this file pure enough to drive
// from the digital-twin test (test/gateway-client.test.ts) with a scripted
// socket, exactly like the daemon's node:test replay fixtures.

import {
  JsonRpcEvent,
  JsonRpcResponse,
  PROTOCOL_VERSION,
  type CronJobWire,
  type UsageSummaryResult,
  type CronScheduleWire,
  type HelloResult,
  type ServerFeature,
  type SearchArchiveResult,
  type SearchMessagesResult,
  type SearchScope,
  type SessionForkResult,
  type SessionUndoResult,
  type TerminalAttachResult,
  type TerminalInfoWire,
  type WorkspaceWire,
  type WorkspaceContextResult,
} from "@marmalade/protocol";
import type { GatewaySocket, SocketFactory } from "./socket.js";
import { browserSocketFactory } from "./socket.js";
import {
  applyEvent,
  emptySessionState,
  type SessionState,
} from "./session-state.js";
import type { DaemonSettings, ModelInfo, SessionSummary, SubscribeResult } from "./types.js";
import type { EffortBounds } from "../components/efforts.js";

export type ConnectionStatus =
  | "disconnected"
  | "connecting"
  | "connected"
  | "reconnecting";

export interface GatewayClientOptions {
  url: string;
  /** Stable per-install id (localStorage) — the origin the daemon stamps. */
  deviceId: string;
  deviceName: string;
  /** Bearer token, kept for when gateway auth lands (spec Settings). */
  token?: string;
  socketFactory?: SocketFactory;
  /** Injected for tests; wall-clock in production. */
  now?: () => number;
  /** Base reconnect backoff in ms (doubles per attempt, capped). */
  backoffBaseMs?: number;
  backoffMaxMs?: number;
}

/** The events the client emits. The app subscribes; nothing polls. */
export interface GatewayEvents {
  status: (status: ConnectionStatus) => void;
  hello: (result: HelloResult) => void;
  /** A session's derived state changed (new/updated). */
  session: (sessionId: string, state: SessionState) => void;
  /** The session list was (re)fetched. */
  sessions: (sessions: SessionSummary[]) => void;
  /** The workspace list was (re)fetched (empty when the daemon lacks the
   *  "workspaces" feature — the rail then renders a flat list). */
  workspaces: (workspaces: WorkspaceWire[]) => void;
  /** The models list was (re)fetched. defaultModel is the daemon's new-session
   *  default (model.list default_model), or null when the daemon doesn't
   *  advertise one — the picker then renders a bare "Default". */
  models: (models: ModelInfo[], defaultModel: string | null, efforts: string[]) => void;
  /** A session was deleted (locally or by another client). */
  deleted: (sessionId: string) => void;
  /** Any raw inbound frame — feeds the settings debug event log. */
  frame: (frame: unknown) => void;
  /** Transient terminal stream (terminal.data / terminal.exit). Delivery is
   *  attach-scoped SERVER-side — this fires only for terminals this
   *  connection attached. Payload stays raw; the view decodes. */
  terminal: (type: "terminal.data" | "terminal.exit", payload: Record<string, unknown>) => void;
  error: (message: string) => void;
}

type Listener = (...args: never[]) => void;

/** An RPC rejection that preserves the wire error's `code` and `data` —
 *  clients branch on structured `data` (e.g. data.reason on session.fork's
 *  no-fork rejection), never by parsing the human message. */
export class RpcError extends Error {
  constructor(
    message: string,
    public readonly code?: number,
    public readonly data?: unknown,
  ) {
    super(message);
    this.name = "RpcError";
  }
}

interface Pending {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
}

/** One attached session's client-side bookkeeping. */
interface Attached {
  state: SessionState;
  /** The device's seen cursor for unread math. */
  seenSeq: number;
  /** True once subscribed on the current connection — reset on reconnect so the
   *  reconnect handler re-subscribes from the watermark. */
  subscribed: boolean;
}

export class GatewayClient {
  private socket: GatewaySocket | null = null;
  private status: ConnectionStatus = "disconnected";
  private nextId = 1;
  private readonly pending = new Map<string, Pending>();
  private readonly listeners = new Map<keyof GatewayEvents, Set<Listener>>();
  private readonly attached = new Map<string, Attached>();
  private features: ServerFeature[] = [];
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private closedByUser = false;
  private readonly now: () => number;
  private readonly makeSocket: SocketFactory;
  private readonly backoffBaseMs: number;
  private readonly backoffMaxMs: number;

  constructor(private readonly opts: GatewayClientOptions) {
    this.now = opts.now ?? Date.now;
    this.makeSocket = opts.socketFactory ?? browserSocketFactory;
    this.backoffBaseMs = opts.backoffBaseMs ?? 500;
    this.backoffMaxMs = opts.backoffMaxMs ?? 15_000;
  }

  // ── Public surface ─────────────────────────────────────────────────────────

  connect(): void {
    this.closedByUser = false;
    this.openSocket();
  }

  disconnect(): void {
    this.closedByUser = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.socket?.close();
    this.setStatus("disconnected");
  }

  getStatus(): ConnectionStatus {
    return this.status;
  }

  /** Negotiated server features (spec: ALL feature-gated UI derives from this). */
  getFeatures(): ServerFeature[] {
    return this.features;
  }

  /** Feature-gate check. Accepts any string, not just the closed ServerFeature
   *  enum, so forward-compat gates (e.g. "fs" for the editor, "attachments")
   *  can be checked before the protocol enum grows to include them — the daemon
   *  advertises the string; the UI reads it (spec: ALL gated UI derives here). */
  hasFeature(feature: string): boolean {
    return (this.features as string[]).includes(feature);
  }

  getSessionState(sessionId: string): SessionState | undefined {
    return this.attached.get(sessionId)?.state;
  }

  getSeenSeq(sessionId: string): number {
    return this.attached.get(sessionId)?.seenSeq ?? 0;
  }

  on<K extends keyof GatewayEvents>(event: K, fn: GatewayEvents[K]): () => void {
    let set = this.listeners.get(event);
    if (!set) this.listeners.set(event, (set = new Set()));
    set.add(fn as Listener);
    return () => set!.delete(fn as Listener);
  }

  // ── RPC methods used in v0 (spec "Protocol client") ─────────────────────────

  async listSessions(): Promise<SessionSummary[]> {
    const r = (await this.call("session.list", {})) as { sessions: SessionSummary[] };
    this.emit("sessions", r.sessions);
    return r.sessions;
  }

  async listModels(): Promise<ModelInfo[]> {
    // default_model is additive (2026-07-23): the daemon-owned new-session
    // default. Absent = unknown (the harness's own default applies) → null, and
    // the picker keeps rendering a bare "Default". `efforts` (2026-07-25) is
    // the effort vocabulary the daemon actually validates against — the Models
    // settings card renders exactly it rather than a hardcoded list.
    const r = (await this.call("model.list", {})) as {
      models: ModelInfo[];
      default_model?: string;
      efforts?: string[];
    };
    this.emit("models", r.models, r.default_model ?? null, r.efforts ?? []);
    return r.models;
  }

  // ── Daemon settings (the "Models" card) ───────────────────────────────────
  // Server-owned, cross-client: the new-session model/effort defaults live in
  // the daemon's config.json, so every client (webui, Android, CLI) sees the
  // same answer. Gated on hasFeature("settings"); an older daemon degrades to
  // a read-only card.

  async getSettings(): Promise<DaemonSettings | null> {
    if (!this.hasFeature("settings")) return null;
    return (await this.call("settings.get", {})) as DaemonSettings;
  }

  /** Patch the daemon defaults. Returns the post-write state (authoritative —
   *  no event is broadcast to other clients; they re-read on their next
   *  settings.get). Refreshes model.list so the composer's "Default (…)"
   *  label follows the change immediately. */
  async updateSettings(patch: {
    default_model?: string | null;
    default_effort?: string | null;
    /** A PER-MODEL patch, not a whole-map replace: null deletes that model's
     *  bounds, an object replaces them wholesale, ids left out are untouched
     *  (protocol SettingsUpdateParams). */
    model_efforts?: Record<string, EffortBounds | null>;
  }): Promise<DaemonSettings> {
    const r = (await this.call("settings.update", patch)) as DaemonSettings;
    await this.listModels();
    return r;
  }

  // ── Workspaces (Paseo-style folder grouping) ───────────────────────────────
  // Gated UI-side on hasFeature("workspaces"). Membership is DERIVED by the
  // daemon (workspace_id stamped per session.list row) — the client never
  // matches cwd itself. All mutations refetch the list rather than trust a
  // local mirror (same posture as cron).

  async listWorkspaces(): Promise<WorkspaceWire[]> {
    if (!this.hasFeature("workspaces")) {
      this.emit("workspaces", []);
      return [];
    }
    const r = (await this.call("workspace.list", {})) as { workspaces: WorkspaceWire[] };
    this.emit("workspaces", r.workspaces);
    return r.workspaces;
  }

  async createWorkspace(params: { path: string; name?: string; emoji?: string }): Promise<WorkspaceWire> {
    const r = (await this.call("workspace.create", {
      path: params.path,
      ...(params.name ? { name: params.name } : {}),
      ...(params.emoji ? { emoji: params.emoji } : {}),
    })) as { workspace: WorkspaceWire };
    return r.workspace;
  }

  /** emoji: null clears it; omit to leave unchanged. */
  async updateWorkspace(
    workspaceId: string,
    params: { name?: string; emoji?: string | null },
  ): Promise<WorkspaceWire> {
    const r = (await this.call("workspace.update", {
      workspace_id: workspaceId,
      ...(params.name !== undefined ? { name: params.name } : {}),
      ...(params.emoji !== undefined ? { emoji: params.emoji } : {}),
    })) as { workspace: WorkspaceWire };
    return r.workspace;
  }

  /** Un-group only — sessions are kept, by design (workspace.delete). */
  async deleteWorkspace(workspaceId: string): Promise<boolean> {
    const r = (await this.call("workspace.delete", { workspace_id: workspaceId })) as { deleted: boolean };
    return r.deleted;
  }

  /** Read-only peek at the context a session spawned here inherits. */
  async workspaceContext(workspaceId: string): Promise<WorkspaceContextResult> {
    return (await this.call("workspace.context", { workspace_id: workspaceId })) as WorkspaceContextResult;
  }

  /** Deferred first-send create (spec Chat: "pick rides the deferred first-send
   *  session.create — mirror Android's semantics exactly"). Returns the
   *  server-minted session_id, the ack that binds the local key. */
  async createSession(params: { model?: string; title?: string; cwd?: string } = {}): Promise<string> {
    const r = (await this.call("session.create", params)) as { session_id: string };
    this.ensureAttached(r.session_id);
    // The creating connection is auto-subscribed server-side (router.ts), so no
    // explicit subscribe here — but mark it so a reconnect re-subscribes it.
    const a = this.attached.get(r.session_id)!;
    a.subscribed = true;
    return r.session_id;
  }

  /** session.main — resolve THE daemon-managed singleton main session (the
   *  assistant/Home surface), get-or-created and kept warm daemon-side. There
   *  is no "set main": the daemon owns the designation. Returns its id; the
   *  caller opens it like any session (openSession) and routes voice/wake-word
   *  turns into it. */
  async mainSession(): Promise<string> {
    const r = (await this.call("session.main", {})) as { session_id: string };
    this.ensureAttached(r.session_id);
    return r.session_id;
  }

  /** session.clear — reset a session's CONVERSATION in place (same id). Used on
   *  the main session, which cannot be deleted, to start over. The daemon wipes
   *  messages/transcript and fans a transient session.cleared event to every
   *  subscriber (this device included) — the render list empties off that event
   *  (session-state applyCleared), not from this ack. Rejects while a turn is in
   *  flight. */
  async clearSession(sessionId: string): Promise<void> {
    await this.call("session.clear", { session_id: sessionId });
  }

  /** session.model — change a materialized session's model. Stored on the row;
   *  a live idle child is restarted so it applies now (context carries over via
   *  harness resume). Rejects while a turn is in flight. Returns the now-stored
   *  model id. */
  async setModel(sessionId: string, model: string): Promise<string> {
    const r = (await this.call("session.model", { session_id: sessionId, model })) as { model: string };
    return r.model;
  }

  /** Attach: resume + subscribe from the watermark, so replay is gapless and
   *  the same dispatch handles replayed + live events (rules 5, 2). */
  async openSession(sessionId: string): Promise<void> {
    await this.call("session.resume", { session_id: sessionId });
    await this.subscribe(sessionId);
  }

  async submitPrompt(sessionId: string, prompt: string, source: "text" | "voice" = "text"): Promise<void> {
    await this.sendUserPrompt("prompt.submit", sessionId, prompt, source, false);
  }

  /** session.steer (T2 #6) — inject a mid-turn guidance message into a RUNNING
   *  turn. Same ack shape as prompt.submit; the daemon rejects with "no turn in
   *  flight — use prompt.submit" when the session isn't running, so the caller
   *  routes here only while run_state === "running" and to submitPrompt when
   *  idle. The steer is a real user message — mark our optimistic bubble
   *  `steered` so it renders distinctly (the daemon tags the replayed
   *  message.user steered too for other devices). */
  async steerSession(sessionId: string, prompt: string, source: "text" | "voice" = "text"): Promise<void> {
    await this.sendUserPrompt("session.steer", sessionId, prompt, source, true);
  }

  /** Shared prompt.submit / session.steer path: fire the RPC, then render our
   *  OWN user message from the ack. The daemon withholds message.user from the
   *  submitting client (transcript-only for us — double-bubble avoidance), so
   *  without this the sender never sees what they just sent. appendUserMessage
   *  dedups by id, so a later subscribe replay of the same message.user is
   *  absorbed rather than doubled. BOTH ids must be present (&&): with
   *  message_id missing, applyEvent would synthesize a `user-<seq>` id that
   *  can't dedup against the replayed message.user (double bubble); with seq
   *  missing, `seq ?? 0` would pin the bubble to the transcript top. A partial
   *  ack renders nothing — the subscribe replay delivers the message under its
   *  real identity instead. */
  private async sendUserPrompt(
    method: "prompt.submit" | "session.steer",
    sessionId: string,
    prompt: string,
    source: "text" | "voice",
    steered: boolean,
  ): Promise<void> {
    const ack = (await this.call(method, { session_id: sessionId, prompt, source })) as
      { message_id?: string; seq?: number; ts?: number } | undefined;
    const a = this.attached.get(sessionId);
    if (a) {
      if (ack && ack.message_id !== undefined && ack.seq !== undefined) {
        const next = applyEvent(a.state, "message.user", {
          message_id: ack.message_id,
          seq: ack.seq,
          ts: ack.ts,
          text: prompt,
          ...(steered ? { steered: true } : {}),
        });
        if (next !== a.state) {
          a.state = next;
          this.emit("session", sessionId, next);
        }
      }
      // The daemon auto-stamps the submitting device's seen cursor (submitting
      // IS seeing — router.ts); mirror it locally so we don't badge our own
      // message. After the optimistic apply so lastSeq includes it.
      a.seenSeq = Math.max(a.seenSeq, a.state.lastSeq);
    }
  }

  async interrupt(sessionId: string): Promise<void> {
    await this.call("session.interrupt", { session_id: sessionId });
  }

  /** session.compact (T2 #11a) — trigger a manual context compaction. Queue-
   *  and-return: the ack is acceptance; progress/outcome arrive as
   *  session.compaction events (started → completed|failed). The daemon rejects
   *  when a turn is in flight or the harness has no compact seam. */
  async compactSession(sessionId: string): Promise<void> {
    await this.call("session.compact", { session_id: sessionId });
  }

  /** approval.respond (M2). Ready-not-live per spec
   *  "Methods used in v0": wired so the dormant ApprovalsSheet can answer, but
   *  never invoked until the daemon starts emitting approval.request. Payload
   *  {choice, session_id?, all?} — session-keyed FIFO, no request_id needed. */
  async submitApprovalResponse(
    sessionId: string,
    choice: "once" | "session" | "deny",
    all = false,
  ): Promise<void> {
    await this.call("approval.respond", { choice, session_id: sessionId, all });
  }

  /** clarify.respond — answer a parked agent question (clarify.request).
   *  answers maps question text → chosen answer (multi-select comma-joined);
   *  response is freeform text. Sending neither = dismissed (the agent
   *  proceeds on its own judgment). */
  async submitClarifyResponse(
    sessionId: string,
    requestId: string | undefined,
    answers?: Record<string, string>,
    response?: string,
  ): Promise<void> {
    await this.call("clarify.respond", {
      session_id: sessionId,
      ...(requestId ? { request_id: requestId } : {}),
      ...(answers && Object.keys(answers).length ? { answers } : {}),
      ...(response ? { response } : {}),
    });
  }

  // ── Attachments (T1) — staged per-session, consumed by the NEXT
  // prompt.submit. Gated UI-side on hasFeature("attachments").

  async attachImageBytes(sessionId: string, contentBase64: string, filename?: string): Promise<{ attached: boolean; path: string; count: number }> {
    return (await this.call("image.attach_bytes", {
      session_id: sessionId,
      content_base64: contentBase64,
      ...(filename ? { filename } : {}),
    })) as { attached: boolean; path: string; count: number };
  }

  /** Returns ref_text (`@file:…`) to prepend to the prompt, or null for a PDF
   *  (its pages ride the queue as images). */
  async attachFile(sessionId: string, name: string, dataUrl: string): Promise<string | null> {
    const r = (await this.call("file.attach", { session_id: sessionId, name, data_url: dataUrl })) as
      { ref_text?: string | null };
    return r.ref_text ?? null;
  }

  // ── Search (search.messages, additive 2026-07-24) ─────────────────────────
  // Full-text search over MESSAGE TEXT only, answered by the daemon's FTS5
  // sidecar index. Gated on hasFeature("search"): a daemon without the index
  // renders NO search entry points, so this rejects locally rather than
  // sending a frame the daemon would answer with MethodNotFound.
  //
  // Scope is resolved daemon-side through the SAME workspace matcher
  // session.list stamps rows with (deepest wins) — the client never matches
  // cwd, and never re-derives membership from the returned paths.

  async searchMessages(params: {
    query: string;
    scope?: SearchScope;
    role?: "user" | "assistant";
    since?: number;
    include_archived?: boolean;
    sort?: "rank" | "recent";
    limit?: number;
    offset?: number;
  }): Promise<SearchMessagesResult> {
    if (!this.hasFeature("search")) throw new Error("this daemon has no message search");
    return (await this.call("search.messages", { ...params })) as SearchMessagesResult;
  }

  /** search.archive — the read-only transcript of ONE pre-daemon archive
   *  session (the viewer behind an archive hit). Separate feature gate from
   *  "search": a daemon can index its own sessions without having scanned
   *  ~/.claude/projects, and then there is no archive corpus to open.
   *  There is no archive equivalent of session.resume by design. */
  async searchArchive(params: {
    session_id: string;
    limit?: number;
    offset?: number;
  }): Promise<SearchArchiveResult> {
    if (!this.hasFeature("search_archive")) throw new Error("this daemon has no archive corpus");
    return (await this.call("search.archive", { ...params })) as SearchArchiveResult;
  }

  // ── Cron (scheduled prompts, parity-map T2 #1) ─────────────────────────────
  // Thin RPC wrappers; the daemon owns ALL schedule state and semantics
  // (one-shots self-disable, run_now doesn't move next_run_at, disabled jobs
  // stay listed). The view refetches after every mutation rather than trusting
  // a local mirror.

  async cronList(): Promise<CronJobWire[]> {
    const r = (await this.call("cron.list", {})) as { jobs: CronJobWire[] };
    return r.jobs;
  }

  /** Daily usage rollups, trailing `days` window ending at the daemon's
   *  today (T2 #8). Read-only; clients aggregate. */
  async usageSummary(days: number): Promise<UsageSummaryResult> {
    return (await this.call("usage.summary", { days })) as UsageSummaryResult;
  }

  async cronCreate(params: {
    session_id: string;
    prompt: string;
    schedule: CronScheduleWire;
    name?: string;
  }): Promise<CronJobWire> {
    const r = (await this.call("cron.create", params)) as { job: CronJobWire };
    return r.job;
  }

  async cronUpdate(params: {
    job_id: string;
    enabled?: boolean;
    name?: string | null;
    prompt?: string;
    schedule?: CronScheduleWire;
  }): Promise<CronJobWire> {
    const r = (await this.call("cron.update", params)) as { job: CronJobWire };
    return r.job;
  }

  async cronDelete(jobId: string): Promise<boolean> {
    const r = (await this.call("cron.delete", { job_id: jobId })) as { deleted: boolean };
    return r.deleted;
  }

  /** fired=false = the job is mid-run (single-flight); not an error. */
  async cronRunNow(jobId: string): Promise<boolean> {
    const r = (await this.call("cron.run_now", { job_id: jobId })) as { fired: boolean };
    return r.fired;
  }

  // ── Terminals (terminal.*) ────────────────────────────────────────────────
  // Gated UI-side on hasFeature("terminal"). A terminal is NOT a session:
  // output is attach-scoped + transient; reconnect recovery is re-attach +
  // snapshot repaint (the view owns no scrollback beyond xterm's own).

  async terminalList(): Promise<TerminalInfoWire[]> {
    const r = (await this.call("terminal.list", {})) as { terminals: TerminalInfoWire[] };
    return r.terminals;
  }

  async terminalCreate(cols: number, rows: number, cwd?: string): Promise<TerminalInfoWire> {
    const r = (await this.call("terminal.create", {
      cols, rows, ...(cwd ? { cwd } : {}),
    })) as { terminal: TerminalInfoWire };
    return r.terminal;
  }

  /** Join the stream + get the scrollback snapshot (atomic server-side: write
   *  the snapshot, then apply terminal.data events — gapless, no overlap). */
  async terminalAttach(terminalId: string): Promise<TerminalAttachResult> {
    return (await this.call("terminal.attach", { terminal_id: terminalId })) as TerminalAttachResult;
  }

  async terminalDetach(terminalId: string): Promise<void> {
    await this.call("terminal.detach", { terminal_id: terminalId });
  }

  /** Keystrokes/paste, base64 (control bytes survive JSON). Callers typically
   *  void this — input is latency-tolerant of an unawaited ack. */
  async terminalInput(terminalId: string, dataB64: string): Promise<void> {
    await this.call("terminal.input", { terminal_id: terminalId, data_b64: dataB64 });
  }

  async terminalResize(terminalId: string, cols: number, rows: number): Promise<void> {
    await this.call("terminal.resize", { terminal_id: terminalId, cols, rows });
  }

  async terminalClose(terminalId: string): Promise<void> {
    await this.call("terminal.close", { terminal_id: terminalId });
  }

  async deleteSession(sessionId: string): Promise<void> {
    await this.call("session.delete", { session_id: sessionId });
    // Local drop is idempotent with the session.deleted event the daemon fans
    // to every subscriber; whichever lands first wins.
    this.attached.delete(sessionId);
    this.emit("deleted", sessionId);
  }

  async renameSession(sessionId: string, title: string): Promise<string> {
    const r = (await this.call("session.title", { session_id: sessionId, title })) as { title: string };
    return r.title;
  }

  /** session.archive — set/clear a session's daemon-backed archived flag.
   *  Metadata only; the main session rejects (it's the pinned home surface). */
  async archiveSession(sessionId: string, archived: boolean): Promise<boolean> {
    const r = (await this.call("session.archive", { session_id: sessionId, archived })) as { archived: boolean };
    return r.archived;
  }

  /** session.fork (T2 #3) — branch a session into a NEW session that carries
   *  the full harness context (conversation incl. tool calls/reasoning),
   *  optionally cut at an ASSISTANT message (at_message_id). Absent cut = fork
   *  the whole session from its end. Returns the daemon's SessionForkResult;
   *  the caller opens `session_id` and surfaces `warning` non-blockingly.
   *
   *  A harness that cannot fork rejects with a "cannot fork … seeded" message
   *  (isNoForkError below) — the client's job is then its own seed-create
   *  branch with the heavier context-loss warning. The webui has no
   *  seed-create branch, so it surfaces that warning instead (the marmalade
   *  daemon's Claude harness always forks; this path is OpenCode-only). */
  async forkSession(
    sessionId: string,
    opts: { atMessageId?: string; title?: string } = {},
  ): Promise<SessionForkResult> {
    return (await this.call("session.fork", {
      session_id: sessionId,
      ...(opts.atMessageId ? { at_message_id: opts.atMessageId } : {}),
      ...(opts.title ? { title: opts.title } : {}),
    })) as SessionForkResult;
  }

  /** session.undo (T2 #6 second half) — pop the last completed turn in place
   *  (same session id). The popped bubbles drop LIVE via the session.undone
   *  event (single source of truth — the daemon fans it to every subscriber,
   *  this device included); this result is only for the caller's notice.
   *  files_rewound is always false in v1: the CONVERSATION is rewound, but file
   *  edits made during the popped turn are NOT reverted. The daemon rejects
   *  when a turn is in flight, nothing is undoable, or the harness can't rewind.
   *  Gate the affordance on hasFeature("undo"). */
  async undoSession(sessionId: string): Promise<SessionUndoResult> {
    return (await this.call("session.undo", { session_id: sessionId })) as SessionUndoResult;
  }

  /** Stamp the render cursor for a session (session.seen). Called when the view
   *  renders up to the current watermark. Monotonic on the server; we mirror
   *  the returned cursor locally for unread math. */
  async markSeen(sessionId: string): Promise<void> {
    const a = this.attached.get(sessionId);
    if (!a || a.state.lastSeq <= a.seenSeq) return;
    const seq = a.state.lastSeq;
    const r = (await this.call("session.seen", { session_id: sessionId, seq })) as { seq: number };
    a.seenSeq = Math.max(a.seenSeq, r.seq);
  }

  // ── Socket lifecycle ────────────────────────────────────────────────────────

  private openSocket(): void {
    this.setStatus(this.reconnectAttempt > 0 ? "reconnecting" : "connecting");
    const socket = this.makeSocket(this.buildUrl());
    this.socket = socket;
    socket.onOpen = () => this.onOpen();
    socket.onMessage = (data) => this.onMessage(data);
    socket.onClose = () => this.onClose();
    socket.onError = () => this.emit("error", "gateway socket error");
  }

  private buildUrl(): string {
    // The token rides hello's auth.token ONLY — never the URL. The daemon
    // authenticates hello auth.token on its own (gateway.ts handleHello binds
    // the principal for unauthenticated remotes), and we hello immediately
    // from onOpen, so a URL copy buys nothing and leaks the credential into
    // anything that logs request lines (a future reverse proxy, ts serve
    // debugging, browser devtools export).
    return this.opts.url;
  }

  private onOpen(): void {
    // hello on EVERY connect (spec). Reset per-connection subscription flags so
    // the hello ack path re-subscribes every attached session (reconnect must
    // not kill a run).
    for (const a of this.attached.values()) a.subscribed = false;
    this.sendHello();
  }

  private sendHello(): void {
    const tzOffset = -new Date().getTimezoneOffset();
    void this.call("hello", {
      protocolVersion: PROTOCOL_VERSION,
      client: {
        name: this.opts.deviceName || "marmalade-webui",
        version: "0.0.1",
        capabilities: ["streaming", "stable-ids"],
        deviceId: this.opts.deviceId,
        platform: "web",
        tzOffset,
      },
      ...(this.opts.token ? { auth: { token: this.opts.token } } : {}),
    })
      .then((result) => {
        const hello = result as HelloResult;
        this.features = hello.features;
        this.reconnectAttempt = 0;
        this.setStatus("connected");
        this.emit("hello", hello);
        // Re-attach every session from its watermark — gapless resume across a
        // reconnect (rule 5). New connect with no attached sessions is a no-op.
        for (const [id, a] of this.attached) {
          if (!a.subscribed) void this.resubscribe(id, a);
        }
      })
      .catch((e: Error) => this.emit("error", `hello failed: ${e.message}`));
  }

  private async resubscribe(sessionId: string, a: Attached): Promise<void> {
    try {
      await this.call("session.resume", { session_id: sessionId });
      await this.subscribe(sessionId);
      void a;
    } catch (e) {
      this.emit("error", `resubscribe ${sessionId} failed: ${(e as Error).message}`);
    }
  }

  private async subscribe(sessionId: string): Promise<void> {
    const a = this.ensureAttached(sessionId);
    // since_seq = the watermark: the daemon replays only what we're missing,
    // then streams live — dedup on our side catches any boundary overlap.
    const r = (await this.call("session.subscribe", {
      session_id: sessionId,
      since_seq: a.state.lastSeq,
    })) as SubscribeResult;
    a.subscribed = true;
    a.state = { ...a.state, lifecycle: r.lifecycle, runState: r.run_state };
    this.emit("session", sessionId, a.state);
  }

  private onClose(): void {
    this.socket = null;
    // Reject in-flight calls so awaiters don't hang forever.
    for (const [, p] of this.pending) p.reject(new Error("connection closed"));
    this.pending.clear();
    if (this.closedByUser) {
      this.setStatus("disconnected");
      return;
    }
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    this.setStatus("reconnecting");
    const delay = Math.min(this.backoffBaseMs * 2 ** this.reconnectAttempt, this.backoffMaxMs);
    this.reconnectAttempt++;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.openSocket();
    }, delay);
  }

  // ── Inbound dispatch ────────────────────────────────────────────────────────

  private onMessage(raw: string): void {
    let frame: unknown;
    try {
      frame = JSON.parse(raw);
    } catch {
      this.emit("error", "received invalid JSON frame");
      return;
    }
    this.emit("frame", frame);

    // Responses first (they carry an id we're awaiting).
    const resp = JsonRpcResponse.safeParse(frame);
    if (resp.success) {
      this.resolvePending(resp.data);
      return;
    }
    // Events — the streaming surface. Replayed and live events land here
    // identically (rule 5): one dispatch, no separate history path.
    const ev = JsonRpcEvent.safeParse(frame);
    if (ev.success) {
      this.dispatchEvent(ev.data);
      return;
    }
    // Unknown frame shape — surface it rather than silently swallow.
    this.emit("error", "unrecognized frame shape");
  }

  private resolvePending(resp: import("@marmalade/protocol").JsonRpcResponse): void {
    const id = String(resp.id);
    const p = this.pending.get(id);
    if (!p) return;
    this.pending.delete(id);
    if ("error" in resp) p.reject(new RpcError(resp.error.message, resp.error.code, resp.error.data));
    else p.resolve(resp.result);
  }

  private dispatchEvent(ev: import("@marmalade/protocol").JsonRpcEvent): void {
    const { type, payload, session_id } = ev.params;

    if (type === "session.deleted" && session_id) {
      this.attached.delete(session_id);
      this.emit("deleted", session_id);
      return;
    }
    // gateway.ready is the pre-hello handshake frame; hello is sent from onOpen.
    if (type === "gateway.ready") return;

    // Terminal stream — not session events (no session_id, no watermark):
    // route straight to the terminal listener before the session gate.
    if (type === "terminal.data" || type === "terminal.exit") {
      this.emit("terminal", type, (payload ?? {}) as Record<string, unknown>);
      return;
    }

    if (!session_id) return; // non-session events aren't rendered in v0
    const a = this.ensureAttached(session_id);
    const next = applyEvent(a.state, type, payload);
    if (next !== a.state) {
      a.state = next;
      this.emit("session", session_id, next);
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private ensureAttached(sessionId: string): Attached {
    let a = this.attached.get(sessionId);
    if (!a) {
      a = { state: emptySessionState(), seenSeq: 0, subscribed: false };
      this.attached.set(sessionId, a);
    }
    return a;
  }

  /** Seed a session's seen cursor from the session.list row (unread math on the
   *  rail before the session is opened). */
  seedSeen(sessionId: string, lastSeq: number, seenSeq: number): void {
    const a = this.ensureAttached(sessionId);
    a.seenSeq = Math.max(a.seenSeq, seenSeq);
    if (lastSeq > a.state.lastSeq) a.state = { ...a.state, lastSeq };
  }

  private call(method: string, params: Record<string, unknown>): Promise<unknown> {
    return new Promise((resolve, reject) => {
      if (!this.socket) {
        reject(new Error("not connected"));
        return;
      }
      const id = String(this.nextId++);
      this.pending.set(id, { resolve, reject });
      try {
        this.socket.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
      } catch (e) {
        this.pending.delete(id);
        reject(e as Error);
      }
    });
  }

  private setStatus(status: ConnectionStatus): void {
    if (status === this.status) return;
    this.status = status;
    this.emit("status", status);
  }

  private emit<K extends keyof GatewayEvents>(event: K, ...args: Parameters<GatewayEvents[K]>): void {
    const set = this.listeners.get(event);
    if (!set) return;
    for (const fn of set) (fn as (...a: unknown[]) => void)(...args);
  }
}
