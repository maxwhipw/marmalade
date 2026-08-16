// WorkspaceContextPeek.tsx — read-only peek at what a session spawned in a
// workspace inherits (workspace.context): CLAUDE.md / AGENTS.md content (capped;
// `truncated` flagged), the .memory note names, and the current git branch.
// Purely informational — no editing, no generic fs.read (the surface is
// workspace-scoped by id on the daemon side).

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import { useGateway } from "../app/gateway-context.js";
import type { WorkspaceContextResult, WorkspaceWire } from "@marmalade/protocol";

interface Props {
  workspace: WorkspaceWire;
  onClose: () => void;
}

export function WorkspaceContextPeek({ workspace, onClose }: Props): ReactNode {
  const { client } = useGateway();
  const [ctx, setCtx] = useState<WorkspaceContextResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    client
      .workspaceContext(workspace.workspace_id)
      .then((r) => { if (live) setCtx(r); })
      .catch((e: Error) => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [client, workspace.workspace_id]);

  const file = (label: string, f: WorkspaceContextResult["claude_md"]): ReactNode => {
    if (!f) return <div className="mm-rail-sub">{label}: absent</div>;
    return (
      <details style={{ marginBottom: 8 }}>
        <summary style={{ cursor: "pointer", fontWeight: 500 }}>
          {label}{f.truncated ? " (truncated)" : ""}
        </summary>
        <pre
          style={{
            margin: "4px 0 0",
            padding: 8,
            background: "var(--surface)",
            borderRadius: "var(--radius-field)",
            fontSize: 12,
            whiteSpace: "pre-wrap",
            maxHeight: 240,
            overflow: "auto",
          }}
        >
          {f.content}
        </pre>
      </details>
    );
  };

  return (
    <div
      role="dialog"
      aria-label={`Workspace context — ${workspace.name}`}
      style={{ position: "fixed", inset: 0, display: "grid", placeItems: "center", background: "rgba(0,0,0,0.35)", zIndex: 40 }}
      onClick={onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 520,
          maxWidth: "calc(100vw - 32px)",
          maxHeight: "calc(100vh - 48px)",
          overflow: "auto",
          background: "var(--surface-raised)",
          borderRadius: "var(--radius-box)",
          boxShadow: "0 8px 32px rgba(0,0,0,0.25)",
          padding: 20,
        }}
      >
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
          <h3 style={{ margin: 0 }}>
            {workspace.emoji ? `${workspace.emoji} ` : ""}{workspace.name}
          </h3>
          <button className="mm-btn ghost small" onClick={onClose}>close</button>
        </div>
        <div className="mm-rail-sub" style={{ whiteSpace: "normal", marginBottom: 12 }}>{workspace.path}</div>

        {error && <div className="mm-rail-sub" style={{ color: "var(--error)", whiteSpace: "normal" }}>{error}</div>}
        {!ctx && !error && <div className="mm-rail-sub">Loading…</div>}
        {ctx && (
          <>
            <div className="mm-rail-sub" style={{ marginBottom: 8 }}>
              git: {ctx.git_branch ?? "not a repo"}
            </div>
            {file("CLAUDE.md", ctx.claude_md)}
            {file("AGENTS.md", ctx.agents_md)}
            <div className="mm-rail-sub" style={{ whiteSpace: "normal" }}>
              memory notes: {ctx.memory.length ? ctx.memory.join(", ") : "none"}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
