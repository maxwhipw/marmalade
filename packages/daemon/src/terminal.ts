// terminal.ts — daemon-hosted PTY terminals (terminal.*), alongside agent
// sessions. Design note kept internally (parity-map T3 "embedded PTY" —
// built once explicitly asked for, 2026-07-19).
//
// A terminal is NOT a session: no identity stamping, no transcript, no replay
// cache, no supervisor. Output is transient and attach-scoped — terminal.data
// events go ONLY to connections currently attached; scrollback recovery is the
// ring-buffer snapshot returned by terminal.attach (atomic with joining the
// live set on the single-threaded event loop → gapless, no seq needed).
// Terminals die with the daemon by design (a PTY can't survive its parent);
// the client renders terminal.exit / an empty roster honestly.
//
// SECURITY:
//   * A terminal is an arbitrary shell as the daemon's user. Not a NEW trust
//     boundary — any paired device can already drive the agent, which runs
//     commands — but it BYPASSES the harness approval layer, so the whole
//     surface has a config kill-switch (terminal_enabled: false) and the
//     gateway's auth gate applies as usual (paired devices + loopback only).
//   * The PTY env is the daemon's own env: the systemd unit deliberately
//     carries no provider credentials (Decision 5.5), so the shell inherits
//     nothing the agent's own children wouldn't.
//   * node-pty is loaded DYNAMICALLY (loadPtyModule): a broken native build
//     degrades to "feature not advertised", never a daemon crash — the
//     transcription.ts host-conditional pattern.
//
// FLOW CONTROL: a runaway command (`cat bigfile`, `yes`) produces PTY output
// far faster than a phone drains its WS. ws.send() buffers unboundedly, so on
// every data chunk we check attached sockets' bufferedAmount and pause() the
// PTY past the high-water mark, polling for drain. The ring buffer itself is
// byte-capped, so an UNATTACHED runaway costs at most the scrollback cap.

import { randomUUID } from "node:crypto";
import { basename } from "node:path";
import { makeEvent, type TerminalInfoWire } from "@marmalade/protocol";

/** The manager's info shape: everything but workspace_id, which the router
 *  stamps from the cwd (workspaces.matcher — same derivation as sessions). */
export type TerminalInfoBare = Omit<TerminalInfoWire, "workspace_id">;
import type { Connection } from "./gateway.js";
import type * as NodePty from "node-pty"; // type-only: erased at runtime

export type PtyModule = Pick<typeof NodePty, "spawn">;

/** Dynamic node-pty load. null = native module unavailable (build failed /
 *  platform unsupported) → the daemon runs without terminals and does not
 *  advertise the "terminal" hello feature. */
export async function loadPtyModule(log?: (line: string) => void): Promise<PtyModule | null> {
  try {
    return await import("node-pty");
  } catch (e) {
    log?.(`[terminal] node-pty failed to load: ${(e as Error).message}`);
    return null;
  }
}

/** Scrollback ring cap per terminal. Enough for a full-screen app's history
 *  plus real scrollback; small enough that 8 idle terminals are noise. */
const SCROLLBACK_BYTES = 256 * 1024;
/** Live-terminal cap — a visible error beats a silent process herd. */
const MAX_TERMINALS = 8;
/** Input cap per terminal.input call (a paste, not a file transfer). */
const MAX_INPUT_BYTES = 1024 * 1024;
/** Pause the PTY when any attached socket has this much unsent; resume when
 *  every socket drains below the low mark. */
const HIGH_WATER_BYTES = 4 * 1024 * 1024;
const LOW_WATER_BYTES = 512 * 1024;
const DRAIN_POLL_MS = 100;
/** terminal.close sends SIGHUP; a shell that ignores it gets SIGKILL after. */
const KILL_ESCALATION_MS = 3_000;

export interface TerminalManagerOpts {
  now: () => number;
  log?: (line: string) => void;
  /** Shell binary. Default: $SHELL, else /bin/bash. */
  shell?: string;
  /** Shell args. Default ["-l"] (login shell — the user's profile applies). */
  shellArgs?: string[];
  /** Default cwd for terminal.create without one: the daemon process's cwd
   *  ("as whatever user the daemon runs as, in its cwd" — the ask). */
  defaultCwd?: string;
  maxTerminals?: number;
  scrollbackBytes?: number;
}

interface Term {
  id: string;
  pty: NodePty.IPty;
  shell: string;
  cwd: string;
  cols: number;
  rows: number;
  createdAt: number;
  lastActive: number;
  attached: Set<Connection>;
  /** Scrollback ring: chunks + running total, evicted from the front. */
  chunks: Buffer[];
  bufBytes: number;
  paused: boolean;
  drainTimer: ReturnType<typeof setInterval> | null;
  killTimer: ReturnType<typeof setTimeout> | null;
}

export class TerminalManager {
  private readonly terms = new Map<string, Term>();
  // No parameter properties: node --experimental-strip-types (the test runner)
  // rejects them; explicit fields keep src importable from tests.
  private readonly pty: PtyModule;
  private readonly now: () => number;
  private readonly log: (line: string) => void;
  private readonly shell: string;
  private readonly shellArgs: string[];
  private readonly defaultCwd: string;
  private readonly maxTerminals: number;
  private readonly scrollbackBytes: number;

  constructor(pty: PtyModule, opts: TerminalManagerOpts) {
    this.pty = pty;
    this.now = opts.now;
    this.log = opts.log ?? (() => {});
    this.shell = opts.shell ?? process.env.SHELL ?? "/bin/bash";
    this.shellArgs = opts.shellArgs ?? ["-l"];
    this.defaultCwd = opts.defaultCwd ?? process.cwd();
    this.maxTerminals = opts.maxTerminals ?? MAX_TERMINALS;
    this.scrollbackBytes = opts.scrollbackBytes ?? SCROLLBACK_BYTES;
  }

  /** Spawn a shell; the creating connection is auto-attached (mirror of
   *  session.create's auto-subscribe). Throws on the cap or a bad cwd. */
  create(p: { cols: number; rows: number; cwd?: string }, conn: Connection | null): TerminalInfoBare {
    if (this.terms.size >= this.maxTerminals) {
      throw new Error(`terminal cap reached (${this.terms.size}/${this.maxTerminals}) — close one first`);
    }
    const cwd = p.cwd ?? this.defaultCwd;
    const id = `t_${randomUUID()}`;
    // TERM/COLORTERM: xterm.js-compatible full-color. Env is the daemon's own
    // (credential-free by the systemd unit's design).
    const proc = this.pty.spawn(this.shell, this.shellArgs, {
      name: "xterm-256color",
      cols: p.cols,
      rows: p.rows,
      cwd,
      env: { ...process.env, TERM: "xterm-256color", COLORTERM: "truecolor" } as Record<string, string>,
    });
    const term: Term = {
      id, pty: proc, shell: this.shell, cwd,
      cols: p.cols, rows: p.rows,
      createdAt: this.now(), lastActive: this.now(),
      attached: new Set(), chunks: [], bufBytes: 0,
      paused: false, drainTimer: null, killTimer: null,
    };
    this.terms.set(id, term);
    if (conn) term.attached.add(conn);

    proc.onData((data) => this.onData(term, data));
    proc.onExit(({ exitCode, signal }) => this.onExit(term, exitCode, signal));
    this.log(`[terminal ${id}] spawned ${this.shell} pid=${proc.pid} cwd=${cwd}`);
    return this.info(term);
  }

  /** Join the live stream + get the scrollback snapshot. Attach-then-snapshot
   *  is synchronous, so no output can fall between the snapshot and the first
   *  live terminal.data event, and none is duplicated. */
  attach(terminalId: string, conn: Connection): { terminal: TerminalInfoBare; snapshot_b64: string } {
    const term = this.require(terminalId);
    term.attached.add(conn);
    return {
      terminal: this.info(term),
      snapshot_b64: Buffer.concat(term.chunks).toString("base64"),
    };
  }

  /** Stop delivery to THIS connection; the terminal keeps running. */
  detach(terminalId: string, conn: Connection): void {
    this.terms.get(terminalId)?.attached.delete(conn);
  }

  /** Write keystrokes/paste to the PTY (base64 → UTF-8). */
  input(terminalId: string, dataB64: string): void {
    const term = this.require(terminalId);
    const bytes = Buffer.from(dataB64, "base64");
    if (bytes.length === 0) return;
    if (bytes.length > MAX_INPUT_BYTES) {
      throw new Error(`input too large (${bytes.length} bytes; cap ${MAX_INPUT_BYTES})`);
    }
    term.lastActive = this.now();
    term.pty.write(bytes.toString("utf8"));
  }

  resize(terminalId: string, cols: number, rows: number): { cols: number; rows: number } {
    const term = this.require(terminalId);
    term.pty.resize(cols, rows);
    term.cols = cols;
    term.rows = rows;
    return { cols, rows };
  }

  /** SIGHUP the shell; SIGKILL if it lingers. The roster row drops when the
   *  process actually exits (onExit → terminal.exit to attached). */
  close(terminalId: string): void {
    const term = this.require(terminalId);
    term.pty.kill();
    if (!term.killTimer) {
      term.killTimer = setTimeout(() => {
        // Still in the roster = exit never fired; escalate.
        if (this.terms.has(terminalId)) {
          this.log(`[terminal ${terminalId}] SIGHUP ignored — escalating to SIGKILL`);
          try { term.pty.kill("SIGKILL"); } catch { /* already gone */ }
        }
      }, KILL_ESCALATION_MS);
      term.killTimer.unref?.();
    }
  }

  list(): TerminalInfoBare[] {
    return [...this.terms.values()].map((t) => this.info(t));
  }

  /** A closed gateway connection vanishes from every attach set (the router's
   *  disconnect hook — same pattern as Subscriptions.disconnect). */
  disconnect(conn: Connection): void {
    for (const term of this.terms.values()) term.attached.delete(conn);
  }

  /** Daemon shutdown: kill every shell. No events — the sockets are closing
   *  with us; clients discover the empty roster on reconnect. */
  stopAll(): void {
    for (const term of this.terms.values()) {
      if (term.drainTimer) clearInterval(term.drainTimer);
      if (term.killTimer) clearTimeout(term.killTimer);
      try { term.pty.kill("SIGKILL"); } catch { /* already gone */ }
    }
    this.terms.clear();
  }

  size(): number {
    return this.terms.size;
  }

  // ── internals ──────────────────────────────────────────────────────────────

  private require(terminalId: string): Term {
    const term = this.terms.get(terminalId);
    if (!term) throw new Error(`unknown terminal ${terminalId}`);
    return term;
  }

  private info(term: Term): TerminalInfoBare {
    return {
      terminal_id: term.id,
      shell: basename(term.shell),
      cwd: term.cwd,
      cols: term.cols,
      rows: term.rows,
      pid: term.pty.pid,
      created_at: term.createdAt,
      last_active: term.lastActive,
    };
  }

  private onData(term: Term, data: string): void {
    term.lastActive = this.now();
    const bytes = Buffer.from(data, "utf8");
    // Ring buffer: append, evict whole chunks from the front past the cap.
    // Eviction can leave the snapshot starting mid-escape-sequence; terminal
    // emulators render a few junk cells and recover — the standard tradeoff.
    term.chunks.push(bytes);
    term.bufBytes += bytes.length;
    while (term.bufBytes > this.scrollbackBytes && term.chunks.length > 1) {
      term.bufBytes -= term.chunks.shift()!.length;
    }
    // Fan out to attached connections — one dead socket never starves the rest.
    if (term.attached.size > 0) {
      const frame = JSON.stringify(
        makeEvent("terminal.data", { terminal_id: term.id, data_b64: bytes.toString("base64") }),
      );
      for (const conn of term.attached) {
        try { conn.ws.send(frame); } catch { /* connection gone */ }
      }
      this.checkBackpressure(term);
    }
  }

  private checkBackpressure(term: Term): void {
    if (term.paused) return;
    let worst = 0;
    for (const conn of term.attached) {
      worst = Math.max(worst, (conn.ws as { bufferedAmount?: number }).bufferedAmount ?? 0);
    }
    if (worst <= HIGH_WATER_BYTES) return;
    // pause() exists on IPty (flow control); guard anyway — a missing method
    // just means we fall back to unbounded ws buffering, not a crash.
    if (typeof term.pty.pause !== "function") return;
    term.pty.pause();
    term.paused = true;
    term.drainTimer = setInterval(() => {
      let max = 0;
      for (const conn of term.attached) {
        max = Math.max(max, (conn.ws as { bufferedAmount?: number }).bufferedAmount ?? 0);
      }
      // Everyone drained (or everyone left): resume.
      if (max < LOW_WATER_BYTES) {
        if (term.drainTimer) clearInterval(term.drainTimer);
        term.drainTimer = null;
        term.paused = false;
        try { term.pty.resume(); } catch { /* exited while paused */ }
      }
    }, DRAIN_POLL_MS);
    term.drainTimer.unref?.();
  }

  private onExit(term: Term, exitCode: number, signal?: number): void {
    if (term.drainTimer) clearInterval(term.drainTimer);
    if (term.killTimer) clearTimeout(term.killTimer);
    this.terms.delete(term.id);
    const frame = JSON.stringify(
      makeEvent("terminal.exit", {
        terminal_id: term.id,
        // Signal deaths report null — the code would be shell-convention noise.
        exit_code: signal ? null : exitCode,
      }),
    );
    for (const conn of term.attached) {
      try { conn.ws.send(frame); } catch { /* connection gone */ }
    }
    term.attached.clear();
    this.log(`[terminal ${term.id}] exited (code=${exitCode}${signal ? ` signal=${signal}` : ""})`);
  }
}
