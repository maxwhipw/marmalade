// @marmalade/icons — the icon map: one named glyph vocabulary shared by every
// Marmalade client surface (webui, desktop, Android). Signed off in the design
// lab `icon-map` on 2026-08-01.
//
// This package is deliberately NOT part of @marmalade/protocol: icons are not a
// wire contract and must never be able to break a handshake. It has no runtime
// dependencies and knows nothing about React — it hands out inline SVG markup
// and lets each surface wrap it in its own shell.
//
// Glyphs are Lucide (dual-licensed ISC / MIT-© Cole Bemis for the Feather-derived
// set); per-icon license is recorded in src/map.json and re-exported below.
// The map is the source of truth; src/generated.ts is built from it.

export { ICONS, WIRE_NAMES, MCP_PREFIX, MCP_TOKEN, SVG_ATTRS } from "./generated.js";
export type { IconToken, IconEntry, IconLicense } from "./generated.js";

import { ICONS, WIRE_NAMES, MCP_PREFIX, MCP_TOKEN } from "./generated.js";
import type { IconToken } from "./generated.js";

/** What an unrecognised tool gets. Kept deliberately visible (a wrench): if it
 *  starts firing often, that's a bug report about a tool name we don't know. */
export const UNKNOWN_TOOL_TOKEN = "icon.tool.unknown" satisfies IconToken;

/**
 * Resolve a tool's wire name to an icon token.
 *
 * Rules, in order:
 *  1. trim + lowercase (the harnesses are inconsistent: `Bash` vs `bash`);
 *  2. `mcp__<server>__<tool>` → `icon.tool.mcp` — the prefix wins over any
 *     name match, because an MCP tool named `search` is still an MCP call;
 *  3. exact match in the map's wire-name index (this is where task/agent →
 *     `icon.tool.subagent` lives, along with bash/read/write/…);
 *  4. anything else → `icon.tool.unknown`.
 *
 * No fuzzy or substring matching: a substring rule would make `websearch` match
 * `search` and quietly mislabel it.
 */
export function iconForTool(wireName: string | null | undefined): IconToken {
  if (!wireName) return UNKNOWN_TOOL_TOKEN;
  const name = wireName.trim().toLowerCase();
  if (name.startsWith(MCP_PREFIX)) return MCP_TOKEN;
  return WIRE_NAMES[name] ?? UNKNOWN_TOOL_TOKEN;
}

/** Inner SVG markup for a token (paths only — no <svg> wrapper). */
export function iconSvg(token: IconToken): string {
  return ICONS[token].svg;
}
