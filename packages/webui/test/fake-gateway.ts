// fake-gateway.ts — a scriptable in-process twin of marmaladed for the client's
// digital-twin tests. Mirrors the daemon test's fakeAdapter/harness style
// (packages/daemon/test/subscribe.test.ts): no real socket, no timers we can't
// control — just a frame pipe the test scripts by hand.
//
// The fake speaks the real wire dialect (@marmalade/protocol frames): it
// answers hello/session.* calls, and it can push stamped events (message.start
// /delta/complete, tool.*, status.update, session.deleted) so the client's
// session-state watermark + dispatch are exercised end to end.

import { makeError, makeEvent, makeResult, type JsonRpcEvent } from "@marmalade/protocol";
import type { GatewaySocket } from "../src/gateway/socket.js";

export interface FakeGatewayScript {
  /** Features returned in the hello result. */
  features?: string[];
  /** Handlers keyed by method — return the RPC result value. */
  handlers?: Record<string, (params: Record<string, unknown>) => unknown>;
}

export class FakeGateway {
  readonly socket: GatewaySocket;
  /** Every request the client sent, in order — assertions read this. */
  readonly requests: Array<{ id: string; method: string; params: Record<string, unknown> }> = [];
  private open = false;

  constructor(private script: FakeGatewayScript = {}) {
    this.socket = {
      send: (data) => this.receive(data),
      close: () => this.closeFromClient(),
      onOpen: null,
      onClose: null,
      onError: null,
      onMessage: null,
    };
  }

  /** Simulate the socket opening (the client's connect() drives makeSocket then
   *  waits for onOpen — the test calls this to release it, and the real
   *  gateway.ready frame is delivered first per protocol). */
  fireOpen(): void {
    this.open = true;
    this.socket.onOpen?.();
    this.deliver(makeEvent("gateway.ready"));
  }

  /** Simulate the socket dropping (server side) — triggers client reconnect. */
  drop(): void {
    this.open = false;
    this.socket.onClose?.();
  }

  private closeFromClient(): void {
    this.open = false;
    this.socket.onClose?.();
  }

  /** Deliver a raw server frame to the client. */
  deliver(frame: unknown): void {
    this.socket.onMessage?.(JSON.stringify(frame));
  }

  /** Push a stamped session event (the daemon's identity.ts does the stamping;
   *  the test supplies the already-stamped payload). */
  pushEvent(type: string, payload: Record<string, unknown>, sessionId: string): void {
    const ev: JsonRpcEvent = makeEvent(type, payload, sessionId);
    this.deliver(ev);
  }

  private receive(data: string): void {
    const req = JSON.parse(data) as { id: string; method: string; params?: Record<string, unknown> };
    const params = req.params ?? {};
    this.requests.push({ id: req.id, method: req.method, params });

    if (req.method === "hello") {
      this.deliver(makeResult(req.id, {
        protocolVersion: 1,
        server: { name: "marmaladed", version: "test" },
        principal: "owner",
        features: this.script.features ?? ["stable-ids", "subscribe"],
      }));
      return;
    }
    // A throwing handler becomes a real error FRAME (code + data carried),
    // matching the daemon's RpcMethodError → makeError path — so tests
    // exercise the client's RpcError data plumbing, not a sync throw.
    const handler = this.script.handlers?.[req.method];
    let result: unknown;
    try {
      result = handler ? handler(params) : this.defaultResult(req.method, params);
    } catch (e) {
      const err = e as { message?: string; code?: number; data?: unknown };
      this.deliver(makeError(req.id, err.code ?? -32602, err.message ?? "error", err.data));
      return;
    }
    this.deliver(makeResult(req.id, result));
  }

  private defaultResult(method: string, params: Record<string, unknown>): unknown {
    switch (method) {
      case "session.create":
        return { session_id: "s_srv_1" };
      case "session.resume":
        return { session_id: params.session_id };
      case "session.subscribe":
        return { session_id: params.session_id, replayed: 0, last_seq: 0, lifecycle: "active", run_state: "idle" };
      case "session.unsubscribe":
        return {};
      case "session.seen":
        return { seq: params.seq };
      case "session.title":
        return { title: params.title };
      case "session.delete":
        return {};
      case "session.list":
        return { sessions: [] };
      case "model.list":
        return { models: [] };
      case "prompt.submit":
        return { message_id: "m_user", seq: 1, ts: 1000 };
      case "session.interrupt":
        return {};
      default:
        return {};
    }
  }

  isOpen(): boolean {
    return this.open;
  }
}
