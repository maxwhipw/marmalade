// TerminalView.tsx — daemon-hosted PTY terminals (terminal.*).
// xterm.js does ALL the emulation; this view is
// roster + transport glue:
//   open   → terminal.attach: reset + write the snapshot, then stream
//            terminal.data (the attach is atomic server-side — no gap)
//   type   → xterm onData/onBinary → terminal.input (base64)
//   resize → FitAddon on a ResizeObserver → terminal.resize (SIGWINCH)
//   drop   → on reconnect, re-attach + repaint (shells survive disconnects;
//            they die only with terminal.close, `exit`, or the daemon)
// The Android client mirrors exactly this glue inside a WebView (same wire,
// same codec) — keep the two in sync when the contract moves.

import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { useGateway } from "../app/gateway-context.js";
import { b64ToBytes, binaryToB64, textToB64 } from "./terminal-codec.js";
import type { TerminalInfoWire } from "@marmalade/protocol";

/** Marmalade-flavored dark theme: neutral terminal colors, orange cursor. */
const THEME = {
  background: "#1c1917",
  foreground: "#e7e5e4",
  cursor: "#f97316",
  cursorAccent: "#1c1917",
  selectionBackground: "#f9731640",
};

export function TerminalView(): ReactNode {
  const { client } = useGateway();
  const [terminals, setTerminals] = useState<TerminalInfoWire[]>([]);
  const [openId, setOpenId] = useState<string | null>(null);
  const [exitNotice, setExitNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  const refresh = useCallback(async () => {
    try {
      setTerminals(await client.terminalList());
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [client]);

  useEffect(() => { void refresh(); }, [refresh]);

  // Roster-level exit watch: a terminal dying while we look at the LIST
  // (closed from another device, shell exited) refreshes the roster.
  useEffect(() => {
    return client.on("terminal", (type) => {
      if (type === "terminal.exit") void refresh();
    });
  }, [client, refresh]);

  const createTerminal = async () => {
    try {
      // Real geometry is set by the first fit-driven terminal.resize after
      // attach; 80x24 is just the spawn default.
      const t = await client.terminalCreate(80, 24);
      await refresh();
      setExitNotice(null);
      setOpenId(t.terminal_id);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const closeTerminal = async (id: string) => {
    try {
      await client.terminalClose(id);
      if (openId === id) setOpenId(null);
      // terminal.exit will also refresh; this covers the roster-only case
      // where the exit event races the list call.
      setTimeout(() => void refresh(), 250);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  // The open terminal's lifecycle: one xterm per open, disposed on close/
  // switch. Everything transport-y lives inside so cleanup is airtight.
  useEffect(() => {
    const el = containerRef.current;
    if (!openId || !el) return;
    setExitNotice(null);

    const term = new Terminal({
      scrollback: 4000,
      fontSize: 14,
      fontFamily: "'JetBrains Mono', 'Fira Code', ui-monospace, monospace",
      theme: THEME,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(el);
    fit.fit();

    let disposed = false;
    const attach = async () => {
      try {
        const r = await client.terminalAttach(openId);
        if (disposed) return;
        term.reset();
        term.write(b64ToBytes(r.snapshot_b64));
        // Sync the PTY to OUR fitted geometry (snapshot may predate it).
        void client.terminalResize(openId, term.cols, term.rows);
      } catch (e) {
        if (!disposed) setExitNotice(`attach failed: ${(e as Error).message}`);
      }
    };
    void attach();

    const offTerminal = client.on("terminal", (type, payload) => {
      if (payload.terminal_id !== openId) return;
      if (type === "terminal.data") {
        term.write(b64ToBytes(String(payload.data_b64 ?? "")));
      } else {
        const code = payload.exit_code;
        setExitNotice(code === null || code === undefined ? "terminal closed" : `shell exited (code ${String(code)})`);
        void refresh();
      }
    });
    // Reconnect repaint: shells survive a dropped socket; we re-attach and
    // let the snapshot repaint (rule: reconnect must not kill anything).
    const offStatus = client.on("status", (s) => {
      if (s === "connected") void attach();
    });
    const dataSub = term.onData((d) => { void client.terminalInput(openId, textToB64(d)); });
    const binarySub = term.onBinary((d) => { void client.terminalInput(openId, binaryToB64(d)); });
    const ro = new ResizeObserver(() => {
      fit.fit();
      void client.terminalResize(openId, term.cols, term.rows);
    });
    ro.observe(el);
    term.focus();

    return () => {
      disposed = true;
      ro.disconnect();
      offTerminal();
      offStatus();
      dataSub.dispose();
      binarySub.dispose();
      term.dispose();
      void client.terminalDetach(openId).catch(() => { /* daemon gone; fine */ });
    };
  }, [client, openId, refresh]);

  if (openId) {
    const info = terminals.find((t) => t.terminal_id === openId);
    return (
      <div style={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: "0.75rem", padding: "0.4rem 0.75rem" }}>
          <button className="mm-tab" onClick={() => setOpenId(null)}>← Terminals</button>
          <span style={{ fontFamily: "monospace", opacity: 0.8 }}>
            {info ? `${info.shell} · ${info.cwd}` : openId}
          </span>
          {exitNotice && <span style={{ color: "#f97316" }}>{exitNotice}</span>}
          <span style={{ flex: 1 }} />
          <button className="mm-tab" onClick={() => void closeTerminal(openId)}>Kill</button>
        </div>
        <div ref={containerRef} style={{ flex: 1, minHeight: 0, padding: "0 0.5rem 0.5rem" }} />
      </div>
    );
  }

  return (
    <div style={{ padding: "1rem", maxWidth: 720 }}>
      <div style={{ display: "flex", alignItems: "center", gap: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ margin: 0 }}>Terminals</h2>
        <button className="mm-tab" onClick={() => void createTerminal()}>New terminal</button>
      </div>
      {error && <p style={{ color: "#f97316" }}>{error}</p>}
      {terminals.length === 0 && !error && (
        <p style={{ opacity: 0.7 }}>
          No terminals. “New terminal” opens a shell on the daemon host, as the
          daemon’s user, in its working directory. Shells survive disconnects
          and die with the daemon.
        </p>
      )}
      <ul style={{ listStyle: "none", padding: 0, margin: 0, display: "flex", flexDirection: "column", gap: "0.5rem" }}>
        {terminals.map((t) => (
          <li key={t.terminal_id} style={{ display: "flex", alignItems: "center", gap: "0.75rem" }}>
            <button className="mm-tab" onClick={() => { setExitNotice(null); setOpenId(t.terminal_id); }}>Open</button>
            <span style={{ fontFamily: "monospace" }}>
              {t.shell} · {t.cwd} · pid {t.pid}
            </span>
            <span style={{ flex: 1 }} />
            <button className="mm-tab" onClick={() => void closeTerminal(t.terminal_id)}>Kill</button>
          </li>
        ))}
      </ul>
    </div>
  );
}
