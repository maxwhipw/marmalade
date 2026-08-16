// ToolCard.tsx — a tool.start/complete pair as a collapsible card (spec view 1:
// collapsed by default, shows duration). `expanded` is the chat setting default.

import type { ReactNode } from "react";
import { iconForTool } from "@marmalade/icons";
import type { ToolCard } from "../gateway/types.js";
import { Icon } from "./Icon.js";

export function ToolCardView({ tool, expanded }: { tool: ToolCard; expanded: boolean }): ReactNode {
  return (
    <details className="mm-tool" open={expanded}>
      <summary>
        {/* The glyph carries the category (the icon map resolves the wire name);
            the name stays, because it is the only thing that says WHICH tool. */}
        <Icon token={iconForTool(tool.name)} size={16} />
        <span className="tname">{tool.name}</span>
        {tool.running ? (
          <span className="tdur">running…</span>
        ) : tool.durationMs !== undefined ? (
          <span className="tdur">{formatDuration(tool.durationMs)}</span>
        ) : null}
      </summary>
      {tool.detail ? <div style={{ padding: "6px 10px" }}>{tool.detail}</div> : null}
    </details>
  );
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}
