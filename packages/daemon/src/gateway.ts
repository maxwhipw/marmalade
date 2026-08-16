// gateway.ts — the WS gateway speaking protocol v1 (frozen JSON-RPC).
//
// Negotiation (Decision 1.1, coh-H1): the EXISTING client puts auth in the WS
// URL (`?token=`/`?ticket=`) and waits for a `gateway.ready` event as the first
// frame — it sends no hello. So:
//   - On connect we bind the principal from the URL token (legacy = owner) and
//     send `gateway.ready` immediately (legacy client works unchanged).
//   - A v1-capable client MAY send a `hello` request (usually first); we
//     validate the protocol version, re-bind the principal from the hello
//     bearer token, and reply with negotiated features (upgrade).
// The legacy-acceptance path is removed at cutover (Decision 6, M5).
//
// M0 scope: framing, validation, negotiation, principal binding, and a
// pluggable request handler. Real method routing arrives with the adapters (M1).

import { WebSocketServer, type WebSocket } from "ws";
import {
  JsonRpcRequest,
  HelloRequest,
  HelloResult,
  makeResult,
  makeError,
  makeEvent,
  isSupportedProtocolVersion,
  ErrorCode,
  PROTOCOL_VERSION,
  type ServerFeature,
} from "@marmalade/protocol";
import type { DaemonConfig } from "./config.js";
import { sanitizeIdentityField } from "./identity.js";
import { isTailnetIPv4, type TokenIdentity } from "./pairing.js";

/** Loopback hosts: connections from these are trusted as the local user
 *  (same-user processes on the same box — the pre-M2 auth boundary, kept). */
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);

/** Where the gateway may bind. Loopback always; a tailnet interface
 *  (100.64.0.0/10) is allowed NOW THAT token auth is real (M2): non-loopback
 *  connections must present a paired device token before any method routes
 *  (see onMessage's auth gate). This is the M2 exit-criterion widening of the
 *  former STUB_AUTH_ALLOWED_HOSTS — still structural: 0.0.0.0 / LAN binds
 *  stay refused (the tailnet, not the open network, is the reach boundary). */
export function isAllowedBindHost(host: string): boolean {
  return LOOPBACK_HOSTS.has(host) || isTailnetIPv4(host);
}

export interface Connection {
  ws: WebSocket;
  /** Bound principal. v0.1: "owner" (legacy or v1) — guest never executes. */
  principal: string;
  /** true until a hello upgrades the connection. */
  legacy: boolean;
  capabilities: string[];
  /** True once this connection may route methods: loopback remotes are
   *  trusted immediately (same-user processes); non-loopback remotes flip
   *  this only via a valid device token (?token= / hello auth.token) or a
   *  successful pairing.claim. Unauthenticated connections can call ONLY
   *  pairing.claim (see onMessage). */
  authenticated: boolean;
  /** True when the token that authenticated this connection bound a VERIFIED
   *  deviceId — a hello's declared deviceId can then never override it. */
  deviceIdVerified: boolean;
  /** Origin identity bound at hello (P1) or by token auth (M2, verified).
   *  The daemon stamps message origins from THESE — the authenticated
   *  connection — never from a message body (sec-H3). */
  deviceId?: string;
  platform?: string;
  tzOffset?: number;
}

export type RequestHandler = (
  method: string,
  params: Record<string, unknown> | undefined,
  conn: Connection,
) => Promise<unknown>;

/** Default handler until adapters land (M1): nothing is routable yet. */
const notImplemented: RequestHandler = async (method) => {
  throw new RpcMethodError(ErrorCode.MethodNotFound, `method not routable in M0: ${method}`);
};

export class RpcMethodError extends Error {
  constructor(public code: number, message: string, public data?: unknown) {
    super(message);
  }
}

export class Gateway {
  /** One listener per configured bind host (dual-bind: loopback + tailnet).
   *  All share onConnection — per-connection trust is decided by the REMOTE
   *  address (isLoopbackAddress), never by which listener accepted it. */
  private servers: WebSocketServer[] = [];
  constructor(
    private cfg: DaemonConfig,
    private handler: RequestHandler = notImplemented,
    private version = "0.0.1",
    private log: (line: string) => void = () => {},
  ) {}

  /** All live connections, so events can fan out and shutdown can close them. */
  readonly connections = new Set<Connection>();

  /** Called when a connection closes — the router prunes it from every
   *  session subscriber set (P4). Wired in index.ts. */
  onDisconnect?: (conn: Connection) => void;

  /** Called after a hello binds device identity onto the connection — feeds
   *  the device roster (P3). Wired in index.ts. */
  onHello?: (conn: Connection) => void;

  /** token → verified identity (M2). Wired in index.ts from the PairingStore.
   *  Absent (tests without pairing) = no token ever validates. */
  authenticateToken?: (token: string) => TokenIdentity | null;

  /** Test hook: treat every remote as non-loopback so the auth gate is
   *  exercisable over 127.0.0.1 test sockets. Never set in production. */
  trustLoopback = true;

  /** Host-conditional features appended to the static hello list — e.g.
   *  "transcription" only when the STT command resolves. Wired in index.ts. */
  extraFeatures: ServerFeature[] = [];

  /** Binds every configured host (dual-bind: loopback + tailnet). Disallowed
   *  hosts throw SYNCHRONOUSLY (structural refusal, before any socket opens);
   *  the returned promise resolves once every listener is live and rejects —
   *  after closing any listener that did come up — if a bind fails (e.g. the
   *  tailnet interface isn't up yet at boot; systemd Restart=on-failure
   *  retries until tailscale is). */
  start(): Promise<void> {
    const hosts = [...new Set(this.cfg.gatewayHosts)];
    for (const host of hosts) {
      if (!isAllowedBindHost(host)) {
        throw new Error(
          `[gateway] refusing to bind ${host}: only loopback or a ` +
            `tailnet interface (100.64.0.0/10) may be bound. Non-loopback connections ` +
            `require a paired device token (M2), but 0.0.0.0/LAN binds stay refused — ` +
            `the tailnet is the reach boundary.`,
        );
      }
    }
    return Promise.all(hosts.map((host) => this.listen(host))).then(
      () => undefined,
      async (err) => { await this.stop(); throw err; },
    );
  }

  private listen(host: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      // maxPayload caps frame size (a buggy client shouldn't be able to stall
      // the loop with a giant frame). 8 MiB is ample.
      const wss = new WebSocketServer({
        host,
        port: this.cfg.gatewayPort,
        maxPayload: 8 * 1024 * 1024,
      });
      this.servers.push(wss);
      wss.on("connection", (ws, req) =>
        this.onConnection(ws, req.url ?? "", req.socket.remoteAddress ?? ""));
      wss.once("listening", () => {
        // Post-listen server errors must not be an uncaught crash.
        wss.on("error", (err) => this.log(`[gateway] server error (${host}): ${err.message}`));
        resolve();
      });
      // A bind failure (EADDRINUSE, EADDRNOTAVAIL) rejects start() — a daemon
      // silently missing one of its listeners is worse than a visible exit.
      wss.once("error", (err) => reject(new Error(
        `[gateway] failed to bind ${host}:${this.cfg.gatewayPort}: ${err.message}`)));
    });
  }

  async stop(): Promise<void> {
    // Terminate open sockets first, else wss.close() waits for clients forever
    // (R5): a connected phone/CLI would block SIGTERM until systemd SIGKILLs us.
    for (const conn of this.connections) {
      try { conn.ws.terminate(); } catch { /* already gone */ }
    }
    this.connections.clear();
    const servers = this.servers;
    this.servers = [];
    await Promise.all(servers.map(
      (wss) => new Promise<void>((resolve) => wss.close(() => resolve()))));
  }

  private onConnection(ws: WebSocket, url: string, remoteAddress: string): void {
    // The auth boundary (M2): loopback remotes are trusted as the local user
    // (unchanged pre-M2 behavior — same-user processes). Non-loopback remotes
    // authenticate via a paired device token in the URL now, in a hello's
    // auth.token later, or via pairing.claim; until then they route NOTHING
    // (see onMessage).
    const loopback = this.trustLoopback && isLoopbackAddress(remoteAddress);
    const conn: Connection = {
      ws, principal: "owner", legacy: true, capabilities: [],
      authenticated: loopback, deviceIdVerified: false,
    };
    if (!loopback) {
      const token = extractToken(url);
      const identity = token ? this.authenticateToken?.(token) ?? null : null;
      if (identity) {
        conn.authenticated = true;
        conn.principal = identity.principal;
        conn.deviceId = identity.deviceId;
        conn.deviceIdVerified = true;
      }
    }
    this.connections.add(conn);

    ws.on("message", (data) => void this.onMessage(conn, data.toString()));
    // A per-socket protocol error (malformed/oversized frame, bad UTF-8) MUST
    // NOT take down the daemon (R1). Log, drop the connection, move on.
    ws.on("error", (err) => this.log(`[gateway] connection error: ${err.message}`));
    ws.on("close", () => {
      this.connections.delete(conn);
      this.onDisconnect?.(conn);
    });

    // Legacy-compatible: send gateway.ready as the first frame (the existing
    // client waits for this and sends no hello). Only once authenticated — an
    // unauthenticated remote gets silence until it claims or hellos a token.
    if (conn.authenticated) send(ws, makeEvent("gateway.ready"));
  }

  private async onMessage(conn: Connection, raw: string): Promise<void> {
    let frame: unknown;
    try {
      frame = JSON.parse(raw);
    } catch {
      send(conn.ws, makeError(null, ErrorCode.ParseError, "invalid JSON"));
      return;
    }

    // v1 hello upgrade.
    const hello = HelloRequest.safeParse(frame);
    if (hello.success) {
      this.handleHello(conn, hello.data);
      return;
    }

    const parsed = JsonRpcRequest.safeParse(frame);
    if (!parsed.success) {
      send(conn.ws, makeError(null, ErrorCode.InvalidRequest, "malformed request frame"));
      return;
    }

    const { id, method, params } = parsed.data;
    // Auth gate (M2): an unauthenticated connection may call ONLY
    // pairing.claim — everything else is refused before it can route.
    if (!conn.authenticated && method !== "pairing.claim") {
      send(conn.ws, makeError(id, ErrorCode.Unauthenticated, "authentication required: present a device token or pair via pairing.claim"));
      return;
    }
    try {
      const result = await this.handler(method, params, conn);
      send(conn.ws, makeResult(id, result));
    } catch (err) {
      if (err instanceof RpcMethodError) {
        send(conn.ws, makeError(id, err.code, err.message, err.data));
      } else {
        send(conn.ws, makeError(id, ErrorCode.InternalError, (err as Error).message));
      }
    }
  }

  private handleHello(conn: Connection, hello: import("@marmalade/protocol").HelloRequest): void {
    const { id, params } = hello;
    if (!isSupportedProtocolVersion(params.protocolVersion)) {
      send(
        conn.ws,
        makeError(id, ErrorCode.ProtocolVersionUnsupported, `unsupported protocolVersion ${params.protocolVersion}; this daemon serves ${PROTOCOL_VERSION}`),
      );
      return;
    }
    // Principal binding (Decision 1.2 → real in M2): an unauthenticated
    // (non-loopback) connection must present a valid device token here —
    // otherwise the hello is refused (it can still pairing.claim after).
    if (!conn.authenticated) {
      const identity = params.auth?.token ? this.authenticateToken?.(params.auth.token) ?? null : null;
      if (!identity) {
        send(conn.ws, makeError(id, ErrorCode.Unauthenticated, "invalid or missing device token; pair via pairing.claim"));
        return;
      }
      conn.authenticated = true;
      conn.principal = identity.principal;
      conn.deviceId = identity.deviceId;
      conn.deviceIdVerified = true;
    }
    conn.legacy = false;
    conn.capabilities = params.client.capabilities;
    // Origin binding (P1/M2): a token-VERIFIED deviceId always wins — the
    // declared one is only accepted on trusted loopback connections (CLI and
    // friends, where the local user is the authority anyway). Sanitized at
    // the binding point: declared deviceId/platform reach the model's context
    // (origin preamble, list_devices output), so hostile values are an
    // injection vector.
    if (!conn.deviceIdVerified) conn.deviceId = sanitizeIdentityField(params.client.deviceId);
    conn.platform = sanitizeIdentityField(params.client.platform);
    conn.tzOffset = params.client.tzOffset;
    this.onHello?.(conn);

    const result: HelloResult = {
      protocolVersion: PROTOCOL_VERSION,
      server: { name: "marmaladed", version: this.version },
      principal: conn.principal,
      // stable-ids: events carry server-minted message_id/seq/ts/origin (P1).
      // subscribe: session.subscribe/unsubscribe/seen — multi-client replay (P4).
      // pairing: pairing.start/claim + device.list/revoke — token auth (M2).
      // undo: session.undo + session.undone — pop the last turn (T2 #6).
      // clarify: clarify.request/respond — agent questions round-trip.
      // workspaces: workspace.* CRUD + workspace_id on session.list rows.
      // extraFeatures: host-conditional (e.g. "transcription"), see index.ts.
      features: ["stable-ids", "subscribe", "pairing", "attachments", "undo", "clarify", "workspaces", "search", "search_archive", "settings", ...this.extraFeatures],
    };
    send(conn.ws, makeResult(id, result));
  }
}

function send(ws: WebSocket, frame: unknown): void {
  ws.send(JSON.stringify(frame));
}

/** True when a socket's remote address is loopback (IPv4, IPv6, or the
 *  IPv4-mapped-IPv6 form Node reports on dual-stack sockets). */
function isLoopbackAddress(addr: string): boolean {
  if (addr === "::1") return true;
  const ipv4 = addr.startsWith("::ffff:") ? addr.slice(7) : addr;
  return ipv4.startsWith("127.");
}

/** Legacy auth is a URL query param: ?token=<loopback> or ?ticket=<oauth>. */
function extractToken(url: string): string | null {
  const qIdx = url.indexOf("?");
  if (qIdx < 0) return null;
  const params = new URLSearchParams(url.slice(qIdx + 1));
  return params.get("token") ?? params.get("ticket");
}
