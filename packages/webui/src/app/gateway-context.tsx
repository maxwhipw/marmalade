// gateway-context.tsx — bridges the (React-free) GatewayClient to React state.
//
// The client owns the socket, dispatch, and its own event emitter (spec
// "simplicity": no state-management library — React context + the client's
// emitter is enough). This context subscribes to those events and mirrors the
// slices React renders (status, features, session list, per-session state,
// models, the debug event log) into hooks. The client is recreated whenever the
// connection settings change, so a URL/token edit reconnects live.

import { createContext, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { GatewayClient, type ConnectionStatus } from "../gateway/client.js";
import type { SessionState } from "../gateway/session-state.js";
import type { ModelInfo, SessionSummary } from "../gateway/types.js";
import type { ServerFeature, WorkspaceWire } from "@marmalade/protocol";
import { getDeviceId } from "./device-id.js";
import { useSettings } from "../settings/provider.js";

/** A bounded ring of raw frames for the settings debug pane. */
const EVENT_LOG_CAP = 500;

interface GatewayContextValue {
  client: GatewayClient;
  status: ConnectionStatus;
  features: ServerFeature[];
  sessions: SessionSummary[];
  /** Daemon workspaces (empty when the "workspaces" feature is absent — the
   *  rail then renders a flat list). */
  workspaces: WorkspaceWire[];
  models: ModelInfo[];
  /** The daemon's new-session default model id (model.list default_model), or
   *  null when the daemon doesn't advertise one. The picker resolves it to a
   *  label for its "Default (…)" row; null keeps a bare "Default". */
  defaultModel: string | null;
  /** The reasoning-effort levels the daemon accepts (model.list `efforts`,
   *  2026-07-25). Empty on a daemon that predates it — the Models card then
   *  hides the effort control rather than guessing a vocabulary. */
  efforts: string[];
  /** THE daemon-managed main session (assistant/Home surface), resolved via
   *  session.main on every connect. null until the first resolution (or on a
   *  daemon without the singleton main). */
  mainSessionId: string | null;
  /** Per-session derived state, keyed by session_id. */
  sessionStates: Map<string, SessionState>;
  /** The device's seen cursor per session (unread math on the rail). */
  seenSeqs: Map<string, number>;
  eventLog: unknown[];
  refreshSessions: () => void;
  refreshWorkspaces: () => void;
}

const GatewayContext = createContext<GatewayContextValue | null>(null);

export function GatewayProvider({ children }: { children: ReactNode }): ReactNode {
  const { settings } = useSettings();
  const conn = settings.connection;

  // One client per connection config. Recreated on url/token/deviceName change
  // (a live settings edit reconnects). deviceId is stable across all of them.
  const client = useMemo(
    () =>
      new GatewayClient({
        url: conn.url,
        token: conn.token || undefined,
        deviceId: getDeviceId(),
        deviceName: conn.deviceName,
      }),
    [conn.url, conn.token, conn.deviceName],
  );

  const [status, setStatus] = useState<ConnectionStatus>("disconnected");
  const [features, setFeatures] = useState<ServerFeature[]>([]);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceWire[]>([]);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [defaultModel, setDefaultModel] = useState<string | null>(null);
  const [efforts, setEfforts] = useState<string[]>([]);
  const [mainSessionId, setMainSessionId] = useState<string | null>(null);
  const [sessionStates, setSessionStates] = useState<Map<string, SessionState>>(new Map());
  const [seenSeqs, setSeenSeqs] = useState<Map<string, number>>(new Map());
  const [eventLog, setEventLog] = useState<unknown[]>([]);

  useEffect(() => {
    const offs = [
      client.on("status", (s) => setStatus(s)),
      client.on("hello", (h) => {
        setFeatures(h.features);
        // On (re)connect, hydrate the rail + model picker. listWorkspaces is a
        // no-op (emits []) when the daemon lacks the "workspaces" feature.
        void client.listSessions();
        void client.listWorkspaces();
        void client.listModels();
        // Resolve THE singleton main session (assistant/Home surface). A daemon
        // without the feature rejects session.main — the assistant home just
        // stays unset (mainSessionId null) and the rail renders no pinned row.
        void client.mainSession().then(setMainSessionId).catch(() => setMainSessionId(null));
      }),
      client.on("workspaces", (ws) => setWorkspaces(ws)),
      client.on("sessions", (rows) => {
        setSessions(rows);
        // Seed unread math for rows we haven't opened yet.
        const seen = new Map<string, number>();
        for (const r of rows) {
          client.seedSeen(r.session_id, r.last_seq, r.seen_seq);
          seen.set(r.session_id, r.seen_seq);
        }
        setSeenSeqs(seen);
      }),
      client.on("models", (m, def, levels) => {
        setModels(m);
        setDefaultModel(def);
        setEfforts(levels);
      }),
      client.on("session", (id, state) => {
        setSessionStates((prev) => new Map(prev).set(id, state));
        setSeenSeqs((prev) => new Map(prev).set(id, client.getSeenSeq(id)));
      }),
      client.on("deleted", (id) => {
        setSessionStates((prev) => {
          const next = new Map(prev);
          next.delete(id);
          return next;
        });
        setSessions((prev) => prev.filter((r) => r.session_id !== id));
      }),
      client.on("frame", (f) =>
        setEventLog((prev) => (prev.length >= EVENT_LOG_CAP ? [...prev.slice(1), f] : [...prev, f])),
      ),
      client.on("error", (msg) => console.warn("[gateway]", msg)),
    ];
    client.connect();
    return () => {
      for (const off of offs) off();
      client.disconnect();
    };
  }, [client]);

  const value = useMemo<GatewayContextValue>(
    () => ({
      client,
      status,
      features,
      sessions,
      workspaces,
      models,
      defaultModel,
      efforts,
      mainSessionId,
      sessionStates,
      seenSeqs,
      eventLog,
      refreshSessions: () => void client.listSessions(),
      refreshWorkspaces: () => void client.listWorkspaces(),
    }),
    [client, status, features, sessions, workspaces, models, defaultModel, efforts, mainSessionId, sessionStates, seenSeqs, eventLog],
  );

  return <GatewayContext.Provider value={value}>{children}</GatewayContext.Provider>;
}

export function useGateway(): GatewayContextValue {
  const ctx = useContext(GatewayContext);
  if (!ctx) throw new Error("useGateway must be used within a GatewayProvider");
  return ctx;
}
