// Parses a ```marmalade-ui fence body into a UiNode tree and synthesizes the
// plain-text interaction responses (Marmalade UI v1 — spec:
// docs/dynamic-ui/marmalade-ui-v1.md §Interaction contract).
//
// Pipeline (Kai's three-stage model): syntax repair (repair.ts) →
// JSON.parse → tolerant field-by-field build (nodes.ts). Supports a single
// JSON object or NDJSON (one object per line, wrapped in an implicit
// column). Returns null when nothing parses — the caller degrades to a
// plain code block.

import { fixJsonSyntax, sanitizeJson } from "./repair.js";
import { buildUiNode, type ButtonNode, type UiNode } from "./nodes.js";

export function parseUiTree(rawBlock: string): UiNode | null {
  if (rawBlock.trim().length === 0) return null;
  const repaired = fixJsonSyntax(rawBlock.trim());
  const lines = repaired
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0);

  // NDJSON: one node per line → implicit column.
  if (lines.length > 1 && lines.every((l) => l.startsWith("{"))) {
    const children = lines.flatMap((l) => {
      const node = tryParse(l);
      return node ? [node] : [];
    });
    return children.length > 0 ? { kind: "column", children } : null;
  }
  return tryParse(sanitizeJson(repaired));
}

function tryParse(json: string): UiNode | null {
  const direct = parseBuild(json);
  if (direct) return direct;
  return parseBuild(sanitizeJson(json));
}

function parseBuild(json: string): UiNode | null {
  try {
    return buildUiNode(JSON.parse(json));
  } catch {
    return null;
  }
}

// ── Interaction response grammar (spec §Interaction contract) ────────────────

/** `Pressed: <event or label>` — callback button with no collect_from. */
export function pressedMessage(button: ButtonNode): string {
  return `Pressed: ${button.event ?? button.label}`;
}

/**
 * `Responded with: <event>: <id>=<value>; …` — callback button that collects
 * local input state. `values` maps input node id → value string (checkbox:
 * "true"/"false"; multi chip_group: comma-joined ids). A collected id absent
 * from `values` contributes `<id>=`.
 */
export function respondedMessage(button: ButtonNode, values: ReadonlyMap<string, string>): string {
  const event = button.event ?? button.label;
  const fields = button.collectFrom.map((id) => `${id}=${values.get(id) ?? ""}`).join("; ");
  return `Responded with: ${event}: ${fields}`;
}

/** Route a callback press to the right grammar line. */
export function callbackMessage(button: ButtonNode, values: ReadonlyMap<string, string>): string {
  return button.collectFrom.length === 0 ? pressedMessage(button) : respondedMessage(button, values);
}
