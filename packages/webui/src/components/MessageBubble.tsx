// MessageBubble.tsx — one chat message (spec view 1: streaming markdown bubbles
// + collapsible tool cards + an "open in artifacts" affordance on fenced code).
// Assistant text is segmented so ```marmalade-ui fences render as native node
// trees (docs/dynamic-ui/marmalade-ui-v1.md) instead of code blocks.

import { useMemo } from "react";
import type { ReactNode } from "react";
import { UI_FENCE_LANG } from "@marmalade/ui-tree";
import type { ChatMessage } from "../gateway/types.js";
import {
  extractFencedBlocks,
  renderMarkdown,
  splitUiSegments,
  type FencedBlock,
} from "./markdown.js";
import { ToolCardView } from "./ToolCard.js";
import { UiTreeView } from "./UiTreeView.js";
import { originMarker } from "./origin.js";

interface Props {
  message: ChatMessage;
  renderMd: boolean;
  toolsExpanded: boolean;
  onOpenArtifact: (block: FencedBlock) => void;
  /** Sends a UI-tree callback response through the chat send path. */
  onUiCallback: (text: string) => void;
  /** Branch a new session cut at this message (session.fork at_message_id).
   *  Rendered only on finished ASSISTANT messages — the daemon only holds
   *  harness cut-points for assistant replies (T2 #3). Absent = no branch
   *  affordance (e.g. no session open yet). */
  onBranchFrom?: (messageId: string) => void;
  /** The match navigator's current position (components/jump.ts) — drawn with
   *  a focus outline so a deep link lands somewhere visible. */
  focused?: boolean;
}

export function MessageBubble({
  message,
  renderMd,
  toolsExpanded,
  onOpenArtifact,
  onUiCallback,
  onBranchFrom,
  focused,
}: Props): ReactNode {
  const isAssistant = message.role === "assistant";
  // A user turn the daemon minted for another actor (a scheduled prompt or a
  // cross-session agent send) carries an origin marker; a human turn gets none.
  const marker = isAssistant ? null : originMarker(message.origin);
  const segments = useMemo(
    () => (isAssistant ? splitUiSegments(message.text) : []),
    [isAssistant, message.text],
  );
  const blocks = useMemo(
    () =>
      isAssistant
        ? extractFencedBlocks(message.text).filter((b) => b.lang.trim() !== UI_FENCE_LANG)
        : [],
    [isAssistant, message.text],
  );

  return (
    <div
      className={`mm-bubble ${message.role}`}
      // The deep link's scroll anchor: open-at-seq resolves a message_id and
      // finds the bubble by this attribute (ChatView).
      data-message-id={message.id}
      data-seq={message.seq}
      data-focused={focused ? "true" : undefined}
    >
      {!isAssistant ? (
        <>
          {message.steered && (
            <span
              className="mm-steer-badge"
              title="Sent mid-turn to steer the running reply (session.steer)"
            >
              steered
            </span>
          )}
          {marker && (
            <span className="mm-origin-badge" title={marker.title}>
              {marker.label}
            </span>
          )}
          <span>{message.text}</span>
        </>
      ) : (
        segments.map((seg, i) =>
          seg.kind === "ui" ? (
            <UiTreeView key={i} node={seg.node} onCallback={onUiCallback} />
          ) : renderMd ? (
            // Sanitized at render (markdown.ts drops raw HTML; only highlighted
            // code survives), so this content is app-generated markup, not
            // model markup.
            <div key={i} dangerouslySetInnerHTML={{ __html: renderMarkdown(seg.md) }} />
          ) : (
            <span key={i}>{seg.md}</span>
          ),
        )
      )}
      {message.tools.map((t) => (
        <ToolCardView key={`${t.seq}-${t.toolUseId ?? t.name}`} tool={t} expanded={toolsExpanded} />
      ))}
      {blocks.map((b, i) => (
        <button
          key={i}
          className="mm-btn ghost small mm-open-artifact"
          onClick={() => onOpenArtifact(b)}
        >
          open in artifacts{b.lang ? ` (${b.lang})` : ""}
        </button>
      ))}
      {/* hasCutPoint === false: the daemon marked this bubble cut-less
          (fork-copied / no-uuid harness) — session.fork would reject the cut,
          so the affordance is hidden. undefined keeps the legacy offer. */}
      {isAssistant && !message.streaming && onBranchFrom && message.hasCutPoint !== false && (
        <div className="mm-msg-actions">
          <button
            className="mm-btn ghost small"
            title="Branch a new chat from this reply (keeps full context)"
            onClick={() => onBranchFrom(message.id)}
          >
            ⑂ Branch from here
          </button>
        </div>
      )}
    </div>
  );
}
