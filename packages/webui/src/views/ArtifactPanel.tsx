// ArtifactPanel.tsx — the right-hand artifact window (spec view 3).
//
// Artifacts are CLIENT-SIDE views of message content, NOT wire objects (spec:
// no protocol change). A fenced code block from an assistant bubble is opened
// here and rendered by kind:
//   - code            → highlighted <pre>
//   - markdown        → rendered (same sanitized path as bubbles)
//   - svg             → inline (SVG is markup, but same-origin inline SVG can
//                       carry scripts, so it goes in the sandboxed iframe too)
//   - html            → STRICTLY inside a sandboxed iframe (sandbox=
//                       "allow-scripts", NO allow-same-origin: model-authored
//                       HTML is untrusted and must not reach this origin).
//
// The kind is inferred from the fence language, with a light content sniff for
// bare fences.

import { useMemo } from "react";
import type { ReactNode } from "react";
import { highlight, renderMarkdown } from "../components/markdown.js";
import type { FencedBlock } from "../components/markdown.js";

type ArtifactKind = "code" | "markdown" | "svg" | "html";

function inferKind(block: FencedBlock): ArtifactKind {
  const lang = block.lang.toLowerCase();
  if (lang === "html" || lang === "svg") return lang;
  if (lang === "markdown" || lang === "md") return "markdown";
  if (lang) return "code";
  // Bare fence: sniff. A leading <svg/<html/<!doctype means markup.
  const head = block.code.trimStart().slice(0, 40).toLowerCase();
  if (head.startsWith("<svg")) return "svg";
  if (head.startsWith("<!doctype") || head.startsWith("<html")) return "html";
  return "code";
}

interface Props {
  block: FencedBlock | null;
  onClose: () => void;
}

export function ArtifactPanel({ block, onClose }: Props): ReactNode {
  const kind = block ? inferKind(block) : null;
  // For untrusted markup (html/svg) we render inside a sandboxed iframe with NO
  // allow-same-origin, so scripts run in a null origin that can't touch us.
  const sandboxDoc = useMemo(() => {
    if (!block || (kind !== "html" && kind !== "svg")) return null;
    return kind === "svg"
      ? `<!doctype html><meta charset="utf-8"><body style="margin:0">${block.code}</body>`
      : block.code;
  }, [block, kind]);

  if (!block) {
    return (
      <aside className="mm-artifacts">
        <div className="mm-artifacts-head">artifacts</div>
        <div className="mm-empty">Open a code block from a message to view it here.</div>
      </aside>
    );
  }

  return (
    <aside className="mm-artifacts">
      <div className="mm-artifacts-head">
        <span>artifacts</span>
        <span className="mm-rail-sub">{kind}{block.lang ? ` · ${block.lang}` : ""}</span>
        <button className="mm-btn ghost small" style={{ marginLeft: "auto" }} onClick={onClose}>
          close
        </button>
      </div>
      <div className="mm-artifacts-body">
        {kind === "code" && (
          <pre className="mm-code">
            <code
              className="hljs"
              // App-generated: highlight() escapes then re-marks tokens; no raw
              // model markup survives.
              dangerouslySetInnerHTML={{ __html: highlight(block.code, block.lang) }}
            />
          </pre>
        )}
        {kind === "markdown" && (
          <div dangerouslySetInnerHTML={{ __html: renderMarkdown(block.code) }} />
        )}
        {(kind === "html" || kind === "svg") && sandboxDoc !== null && (
          // sandbox="allow-scripts" WITHOUT allow-same-origin: model HTML runs
          // in a null origin — it cannot read cookies, localStorage, or the
          // parent DOM of this app (spec view 3, hard requirement).
          <iframe title="artifact" sandbox="allow-scripts" srcDoc={sandboxDoc} />
        )}
      </div>
    </aside>
  );
}
