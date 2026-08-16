// NewWorkspaceDialog.tsx — "New workspace" affordance for the session rail.
//
// The webui has no fs.list folder browser today, so this uses a validated text
// path input (design-lab lab2 reuses Android's WorkspacePickerSheet; here a
// text input is the proportionate equivalent). The daemon owns validation —
// it realpath-confines to home and rejects duplicates / non-existent folders —
// so we surface its RpcError message rather than pre-validating the path
// client-side (which would drift from the daemon's rule).

import { useState } from "react";
import type { ReactNode } from "react";
import { useGateway } from "../app/gateway-context.js";
import { RpcError } from "../gateway/client.js";

interface Props {
  onClose: () => void;
  /** Called after a successful create so the caller can refetch + expand. */
  onCreated: (workspaceId: string) => void;
}

export function NewWorkspaceDialog({ onClose, onCreated }: Props): ReactNode {
  const { client } = useGateway();
  const [path, setPath] = useState("");
  const [name, setName] = useState("");
  const [emoji, setEmoji] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    const p = path.trim();
    if (!p) return;
    setBusy(true);
    setError(null);
    try {
      const ws = await client.createWorkspace({
        path: p,
        name: name.trim() || undefined,
        emoji: emoji.trim() || undefined,
      });
      onCreated(ws.workspace_id);
      onClose();
    } catch (e) {
      // Surface the daemon's own wording (duplicate / outside home / missing).
      setError(e instanceof RpcError ? e.message : (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-label="New workspace"
      style={{
        position: "fixed",
        inset: 0,
        display: "grid",
        placeItems: "center",
        background: "rgba(0,0,0,0.35)",
        zIndex: 40,
      }}
      onClick={onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 380,
          maxWidth: "calc(100vw - 32px)",
          background: "var(--surface-raised)",
          borderRadius: "var(--radius-box)",
          boxShadow: "0 8px 32px rgba(0,0,0,0.25)",
          padding: 20,
        }}
      >
        <h3 style={{ margin: "0 0 12px" }}>New workspace</h3>
        <div className="mm-field">
          <label htmlFor="ws-path">Folder path (on the daemon host)</label>
          <input
            id="ws-path"
            type="text"
            placeholder="~/coding/my-project or /home/you/…"
            value={path}
            onChange={(e) => setPath(e.target.value)}
            autoFocus
          />
        </div>
        <div className="mm-field">
          <label htmlFor="ws-name">Name (optional — defaults from the folder)</label>
          <input id="ws-name" type="text" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="mm-field">
          <label htmlFor="ws-emoji">Emoji (optional)</label>
          <input
            id="ws-emoji"
            type="text"
            style={{ width: 80 }}
            maxLength={16}
            value={emoji}
            onChange={(e) => setEmoji(e.target.value)}
          />
        </div>
        {error && (
          <div className="mm-rail-sub" style={{ color: "var(--error)", whiteSpace: "normal", marginBottom: 8 }}>
            {error}
          </div>
        )}
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <button className="mm-btn ghost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button className="mm-btn accent" onClick={() => void submit()} disabled={busy || !path.trim()}>
            {busy ? "Creating…" : "Create"}
          </button>
        </div>
      </div>
    </div>
  );
}
