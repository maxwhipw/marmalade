// SettingsView.tsx — the settings screen (spec "Settings": the load-bearing
// requirement). One screen, grouped cards, applied live, NO save button —
// every control writes through useSettings() immediately.
//
// Groups: Connection · Appearance · Chat · Advanced (spec). Negotiated server
// features are read-only chips (they come from the daemon's hello, not a
// setting). Export/import round-trips the whole settings blob as JSON through
// the zod schema (import validates + fills defaults, never throws).

import { useRef, useState } from "react";
import type { ReactNode } from "react";
import { useSettings } from "../settings/provider.js";
import { useGateway } from "../app/gateway-context.js";
import { getDeviceId } from "../app/device-id.js";
import { decodeSetupCode, claimPairing } from "../gateway/pairing.js";
import { ModelsCard } from "./ModelsCard.js";

export function SettingsView(): ReactNode {
  const { settings, update, replace, exportJson } = useSettings();
  const { status, features, client, eventLog } = useGateway();
  const importRef = useRef<HTMLInputElement>(null);

  // Device pairing (M2): paste a `marmalade pair` setup code → claim this
  // install's durable device token → persist url+token (the gateway context
  // reconnects on the settings change). Lets a remote/tailnet browser
  // authenticate instead of relying on loopback trust.
  const [setupCode, setSetupCode] = useState("");
  const [pairing, setPairing] = useState(false);
  const [pairMsg, setPairMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const doPair = async () => {
    const raw = setupCode.trim();
    if (!raw || pairing) return;
    setPairing(true);
    setPairMsg(null);
    try {
      const setup = decodeSetupCode(raw);
      const result = await claimPairing(setup, getDeviceId());
      update({ connection: { url: setup.url, token: result.device_token } });
      setSetupCode("");
      setPairMsg({ ok: true, text: `Paired as ${result.device_id}. Connecting…` });
    } catch (e) {
      setPairMsg({ ok: false, text: (e as Error).message });
    } finally {
      setPairing(false);
    }
  };

  const doExport = () => {
    const blob = new Blob([exportJson()], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "marmalade-settings.json";
    a.click();
    URL.revokeObjectURL(url);
  };

  const doImport = (file: File) => {
    void file.text().then((txt) => {
      try {
        replace(JSON.parse(txt));
      } catch {
        alert("Could not parse that settings file.");
      }
    });
  };

  return (
    <div className="mm-settings">
      {/* ── Connection ── */}
      <section className="mm-card">
        <h2>Connection</h2>
        <div className="mm-field">
          <label htmlFor="url">Daemon WS URL</label>
          <input
            id="url"
            type="text"
            value={settings.connection.url}
            onChange={(e) => update({ connection: { url: e.target.value } })}
          />
        </div>
        <div className="mm-field">
          <label htmlFor="token">Auth token (kept for when gateway auth lands)</label>
          <input
            id="token"
            type="password"
            value={settings.connection.token}
            placeholder="none"
            onChange={(e) => update({ connection: { token: e.target.value } })}
          />
        </div>
        <div className="mm-field">
          <label htmlFor="devname">Device name</label>
          <input
            id="devname"
            type="text"
            value={settings.connection.deviceName}
            onChange={(e) => update({ connection: { deviceName: e.target.value } })}
          />
        </div>
        <div className="mm-row">
          <span className={`mm-status-dot ${status}`}>
            <span className="dot" /> {status}
          </span>
          {status === "connected" ? (
            <button className="mm-btn outline" onClick={() => client.disconnect()}>Disconnect</button>
          ) : (
            <button className="mm-btn" onClick={() => client.connect()}>Connect</button>
          )}
        </div>

        {/* Pair a device — redeem a `marmalade pair` setup code for a durable
            device token (needed to connect over the tailnet, not just loopback). */}
        <div className="mm-field" style={{ marginTop: 4 }}>
          <label htmlFor="setupcode">Pair a device</label>
          <div className="mm-row">
            <input
              id="setupcode"
              type="text"
              value={setupCode}
              placeholder="paste `marmalade pair` setup code"
              style={{ flex: 1, minWidth: 0 }}
              onChange={(e) => setSetupCode(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") void doPair(); }}
            />
            <button className="mm-btn accent" disabled={pairing || !setupCode.trim()} onClick={() => void doPair()}>
              {pairing ? "Pairing…" : "Pair"}
            </button>
          </div>
          {pairMsg && (
            <span className="mm-rail-sub" style={{ color: pairMsg.ok ? "var(--success)" : "var(--error)" }}>
              {pairMsg.text}
            </span>
          )}
        </div>
      </section>

      {/* ── Models (daemon-owned: settings.get/update, not localStorage) ── */}
      <ModelsCard />

      {/* ── Appearance ── */}
      <section className="mm-card">
        <h2>Appearance</h2>
        <div className="mm-field">
          <label>Theme</label>
          <div className="mm-chips">
            {(["system", "light", "dark"] as const).map((t) => (
              <button
                key={t}
                className="mm-chip"
                aria-pressed={settings.appearance.theme === t}
                onClick={() => update({ appearance: { theme: t } })}
              >
                {t}
              </button>
            ))}
          </div>
        </div>
        <div className="mm-field">
          <label htmlFor="fontscale">Font scale ({settings.appearance.fontScale.toFixed(2)}×)</label>
          <input
            id="fontscale"
            type="range"
            min={0.85}
            max={1.4}
            step={0.05}
            value={settings.appearance.fontScale}
            onChange={(e) => update({ appearance: { fontScale: Number(e.target.value) } })}
          />
        </div>
      </section>

      {/* ── Chat ── */}
      <section className="mm-card">
        <h2>Chat</h2>
        <div className="mm-field">
          <label>Send on</label>
          <div className="mm-chips">
            <button className="mm-chip" aria-pressed={settings.chat.sendMode === "enter"} onClick={() => update({ chat: { sendMode: "enter" } })}>
              Enter
            </button>
            <button className="mm-chip" aria-pressed={settings.chat.sendMode === "ctrl-enter"} onClick={() => update({ chat: { sendMode: "ctrl-enter" } })}>
              Ctrl/⌘+Enter
            </button>
          </div>
        </div>
        <label className="mm-row">
          <input
            type="checkbox"
            checked={settings.chat.toolCardsExpanded}
            onChange={(e) => update({ chat: { toolCardsExpanded: e.target.checked } })}
          />
          Show tool cards expanded
        </label>
        <label className="mm-row" style={{ marginTop: 8 }}>
          <input
            type="checkbox"
            checked={settings.chat.renderMarkdown}
            onChange={(e) => update({ chat: { renderMarkdown: e.target.checked } })}
          />
          Render markdown
        </label>
      </section>

      {/* ── Advanced ── */}
      <section className="mm-card">
        <h2>Advanced</h2>
        <div className="mm-field">
          <label>Negotiated server features</label>
          <div className="mm-chips">
            {features.length === 0 ? (
              <span className="mm-rail-sub">none (not connected)</span>
            ) : (
              features.map((f) => (
                <span key={f} className="mm-chip" aria-pressed="true">{f}</span>
              ))
            )}
          </div>
        </div>
        <label className="mm-row">
          <input
            type="checkbox"
            checked={settings.advanced.showEventLog}
            onChange={(e) => update({ advanced: { showEventLog: e.target.checked } })}
          />
          Show raw event log
        </label>
        {settings.advanced.showEventLog && (
          <div className="mm-eventlog">
            {eventLog.slice(-100).map((f, i) => (
              <div key={i}>{JSON.stringify(f)}</div>
            ))}
          </div>
        )}
        <div className="mm-row" style={{ marginTop: 12 }}>
          <button className="mm-btn ghost" onClick={doExport}>Export settings</button>
          <button className="mm-btn ghost" onClick={() => importRef.current?.click()}>Import settings</button>
          {/* The export includes gateway.token — a live device credential. */}
          <span className="mm-hint">Export includes this device’s access token — treat the file like a password.</span>
          <input
            ref={importRef}
            type="file"
            accept="application/json"
            style={{ display: "none" }}
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) doImport(f);
              e.target.value = "";
            }}
          />
        </div>
      </section>
    </div>
  );
}
