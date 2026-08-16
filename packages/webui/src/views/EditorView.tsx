// EditorView.tsx — the live file editor (spec view 4).
//
// DELIBERATELY A STUB IN v0 (spec view 4, phase P2): the editor is feature-
// gated OFF until the daemon ships fs.read/fs.write AND advertises "fs" in its
// hello features. The daemon currently negotiates only ["stable-ids",
// "subscribe"] (gateway.ts:195), so this panel renders the gated notice, never
// an editor. No CodeMirror dependency is pulled in for a panel that can't yet
// render one — CM6 lands with the P2 fs.* methods (spec "later phases"),
// keeping the v0 bundle lean. The menu entry that reaches here is likewise
// gated in App.tsx off the same feature, so this notice is only reachable if
// something advertises "fs" without wiring it — a visible, honest failure.

import type { ReactNode } from "react";

export function EditorView({ hasFs }: { hasFs: boolean }): ReactNode {
  return (
    <div className="mm-gated">
      <h2>Live file editor</h2>
      {hasFs ? (
        <p>
          The daemon advertises the <code>fs</code> feature, but the CodeMirror
          editor is not wired yet — it lands with phase P2. This panel is the
          deliberate v0 stub.
        </p>
      ) : (
        <p>
          Disabled. The editor turns on when the daemon ships{" "}
          <code>fs.read</code>/<code>fs.write</code> and advertises the{" "}
          <code>fs</code> feature in its hello handshake (phase P2). Until then
          there is nothing to edit.
        </p>
      )}
    </div>
  );
}
