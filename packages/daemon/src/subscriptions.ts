// subscriptions.ts — the per-session subscriber registry (P4), extracted from
// the router (M2 approvals step 0). Owns WHO is attached to a session and the
// raw fan-out; stamping/caching stay with SessionIdentity + the router's emit
// closures. Five router sites touch subscriber state (spawnAndWire,
// session.subscribe, resumeSession, disconnect, session.delete) — they all go
// through here now, so M2's transient emit + subscribe-time re-emit don't
// finish turning the router into a god object.

import type { Connection } from "./gateway.js";

export class Subscriptions {
  private subs = new Map<string, Set<Connection>>();

  add(sessionId: string, conn: Connection): void {
    let set = this.subs.get(sessionId);
    if (!set) this.subs.set(sessionId, (set = new Set()));
    set.add(conn);
  }

  remove(sessionId: string, conn: Connection): void {
    this.subs.get(sessionId)?.delete(conn);
  }

  /** Forget a whole session (session.delete). */
  dropSession(sessionId: string): void {
    this.subs.delete(sessionId);
  }

  /** A closed connection vanishes from every subscriber set. Returns the
   *  session ids that lost their LAST subscriber (the M2 unattended-fallback
   *  trigger for parked approvals). */
  disconnect(conn: Connection): string[] {
    const drained: string[] = [];
    for (const [sessionId, set] of this.subs) {
      if (set.delete(conn) && set.size === 0) drained.push(sessionId);
    }
    return drained;
  }

  count(sessionId: string): number {
    return this.subs.get(sessionId)?.size ?? 0;
  }

  /** Subscribers that declared a hello capability. The secret-entry flow needs
   *  this and plain `count` will not do: parking a "type your password" prompt
   *  for a client that cannot RENDER a secure input is a hang with no answer
   *  coming, and there is no safe auto-answer to fall back to. */
  countCapable(sessionId: string, capability: string): number {
    let n = 0;
    for (const conn of this.subs.get(sessionId) ?? []) {
      if (conn.capabilities.includes(capability)) n++;
    }
    return n;
  }

  /** Every session this connection is subscribed to. Called BEFORE disconnect
   *  (which forgets it) when a caller needs to re-evaluate per-session state
   *  that losing ONE subscriber can invalidate — losing the only
   *  secrets-capable device, say, even though others remain attached. */
  sessionsWith(conn: Connection): string[] {
    const out: string[] = [];
    for (const [sessionId, set] of this.subs) if (set.has(conn)) out.push(sessionId);
    return out;
  }

  /** Fan a pre-serialized frame to every subscriber. One dead socket never
   *  starves the others. */
  sendRaw(sessionId: string, frame: string): void {
    for (const conn of this.subs.get(sessionId) ?? []) {
      try { conn.ws.send(frame); } catch { /* connection gone */ }
    }
  }
}
