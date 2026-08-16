// ApprovalsSheet.tsx — the tool-use approval sheet (spec view 5).
//
// DORMANT IN v0 (spec view 5, phase M2): the sheet
// is built against the M2 payload shape but stays hidden until the daemon emits
// approval.request. The daemon auto-approves everything today
// (claude-code-adapter.ts autoApproveWithLog); M2 adds an opt-in "prompt" mode.
// So this component listens for approval.request / approval.resolved and
// renders the sheet ONLY when a request is live — with none, it renders nothing.
//
// Payload shape (from the internal M2 approvals design note, "Where things
// stand", not in this repo): the
// request carries command/description/allow_permanent (+ a daemon-minted
// request_id, carried but not required — correlation is session-keyed FIFO).
// approval.respond takes {choice, session_id?, all?}. approval.respond is
// "ready-not-live" in the client (spec "Methods used in v0") — wired here,
// exercised when the daemon starts prompting.

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import { useGateway } from "../app/gateway-context.js";

interface ApprovalRequest {
  session_id: string;
  request_id?: string;
  command?: string;
  description?: string;
  allow_permanent?: boolean;
}

export function ApprovalsSheet(): ReactNode {
  const { client } = useGateway();
  const [pending, setPending] = useState<ApprovalRequest | null>(null);

  useEffect(() => {
    // The gateway client surfaces every raw frame; we watch for the M2 events.
    // Kept minimal (dormant): full request queueing lands with M2 when there's
    // a daemon actually emitting these.
    const off = client.on("frame", (frame) => {
      const f = frame as { method?: string; params?: { type?: string; payload?: ApprovalRequest; session_id?: string } };
      if (f.method !== "event") return;
      const { type, payload, session_id } = f.params ?? {};
      if (type === "approval.request") setPending({ ...(payload ?? {}), session_id: session_id ?? payload?.session_id ?? "" });
      else if (type === "approval.resolved") setPending(null);
    });
    return off;
  }, [client]);

  if (!pending) return null;

  const respond = (choice: "once" | "session" | "deny") => {
    // approval.respond: {choice, session_id?, all?} — the M2 contract. `all`
    // rides "session" (allow for the rest of this session).
    void client.submitApprovalResponse(pending.session_id, choice, choice === "session");
    setPending(null);
  };

  return (
    <div className="mm-gated" role="dialog" aria-label="approval required" style={{ position: "fixed", inset: "auto 24px 24px auto", background: "var(--surface-raised)", borderRadius: "var(--radius-box)", boxShadow: "0 8px 32px rgba(0,0,0,0.25)" }}>
      <h2>Approval required</h2>
      {pending.command && <pre className="mm-code">{pending.command}</pre>}
      {pending.description && <p>{pending.description}</p>}
      <div className="mm-row">
        <button className="mm-btn accent" onClick={() => respond("once")}>Allow once</button>
        {pending.allow_permanent && (
          <button className="mm-btn" onClick={() => respond("session")}>Allow this session</button>
        )}
        <button className="mm-btn outline" onClick={() => respond("deny")}>Deny</button>
      </div>
    </div>
  );
}
