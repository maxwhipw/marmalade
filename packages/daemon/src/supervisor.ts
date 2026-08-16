// supervisor.ts — M1.5, the OpenClaw antidote (feas-H1).
//
// The daemon babysits long-running harness children; OpenClaw's defining
// failure was *silent* background-agent death. The supervisor periodically
// scans for sessions whose heartbeat has gone stale while still marked live,
// marks them hung, and fires ONE loud alert. Heartbeats arrive from the
// adapter's onActivity callback (wired in router.ts).
//
// v0.1 alert = a log line + an alert callback (push lands with the client in
// M2). The detection is the load-bearing part and it exists from M1.5.

import type { SessionManager, SessionRecord } from "./session-manager.js";

export interface SupervisorConfig {
  now: () => number;
  /** How stale a heartbeat may be before a live session is "silently dead". */
  timeoutMs?: number;
  /** How often to scan. */
  intervalMs?: number;
  /** Fired once per newly-detected silent failure. */
  onSilentFailure?: (rec: SessionRecord) => void;
  log?: (line: string) => void;
}

export class Supervisor {
  private timer: ReturnType<typeof setInterval> | null = null;
  private readonly timeoutMs: number;
  private readonly intervalMs: number;
  private alerted = new Set<string>();

  constructor(private sessions: SessionManager, private cfg: SupervisorConfig) {
    this.timeoutMs = cfg.timeoutMs ?? 120_000; // 2 min without a heartbeat
    this.intervalMs = cfg.intervalMs ?? 15_000;
  }

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), this.intervalMs);
    // Don't keep the event loop alive just for the supervisor.
    this.timer.unref?.();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  /** One scan pass — exposed for tests (call directly instead of waiting). */
  tick(): SessionRecord[] {
    const now = this.cfg.now();
    // Latch self-heal: a previously-hung session whose activity resumed (the
    // heartbeat flips hung→running in session-manager) may legitimately hang
    // AGAIN later — clear its latch so a re-hang re-alerts.
    for (const id of this.alerted) {
      if (this.sessions.get(id)?.runState !== "hung") this.alerted.delete(id);
    }
    const dead = this.sessions.findSilentlyDead(now, this.timeoutMs);
    const newly: SessionRecord[] = [];
    for (const rec of dead) {
      // hung is a runState the supervisor sets (and activity clears) — a field
      // flip on the same session id, never a new id (P2).
      this.sessions.setRunState(rec.id, "hung", now);
      if (!this.alerted.has(rec.id)) {
        this.alerted.add(rec.id);
        newly.push(rec);
        this.cfg.log?.(
          `[supervisor] SILENT FAILURE: session ${rec.id} (${rec.purpose}) — no heartbeat for >${this.timeoutMs}ms, marked hung`,
        );
        this.cfg.onSilentFailure?.(rec);
      }
    }
    return newly;
  }

  /** Clear the alert latch when a session legitimately ends or resumes. */
  clear(sessionId: string): void {
    this.alerted.delete(sessionId);
  }
}
