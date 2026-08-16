// ui-render.ts — the CLI's Marmalade UI v1 subset renderer + streaming fence
// filter (dynamic-ui plan step 5; spec: docs/dynamic-ui/marmalade-ui-v1.md).
//
// The CLI renders the subset it can express as prompt-style text: text /
// list / table / code / alert / select / checkbox / chip_group / button
// (+ card/column/row/divider structure). Interactive nodes render their
// options plus the EXACT plain-text reply line the user can type — the
// interaction contract is plain user messages, so typing IS the TUI.
// Unknown/unsupported nodes degrade to readable lines, never errors.

import { callbackMessage, parseUiTree, type ButtonNode, type UiNode, type UiOption } from "@marmalade/ui-tree";

const C = {
  dim: (s: string) => `\x1b[2m${s}\x1b[0m`,
  bold: (s: string) => `\x1b[1m${s}\x1b[0m`,
  orange: (s: string) => `\x1b[38;5;208m${s}\x1b[0m`,
  green: (s: string) => `\x1b[32m${s}\x1b[0m`,
  yellow: (s: string) => `\x1b[33m${s}\x1b[0m`,
  red: (s: string) => `\x1b[31m${s}\x1b[0m`,
};

/** Render a fence body. Returns readable lines even for garbage (the raw
 *  body, dimmed) — the CLI never shows an error for model output. */
export function renderUiFence(body: string): string {
  const node = parseUiTree(body);
  if (!node) return C.dim(body.trim()) + "\n";
  return renderNode(node, "") + "\n";
}

function renderNode(node: UiNode, indent: string): string {
  const kids = (children: UiNode[], childIndent: string) =>
    children.map((c) => renderNode(c, childIndent)).filter((s) => s.length > 0).join("");

  switch (node.kind) {
    case "column":
      return kids(node.children, indent);
    case "row":
      // A terminal has no horizontal layout worth fighting for — stack.
      return kids(node.children, indent);
    case "card": {
      const head = node.title ? indent + C.bold(`┌─ ${node.title}\n`) : indent + C.bold("┌─\n");
      return head + kids(node.children, indent + "│ ") + indent + C.bold("└─\n");
    }
    case "divider":
      return indent + C.dim("────────────\n");
    case "text": {
      let t = node.text;
      if (node.style === "headline" || node.style === "title" || node.bold) t = C.bold(t);
      if (node.style === "caption") t = C.dim(t);
      if (node.color === "primary") t = C.orange(t);
      if (node.color === "success") t = C.green(t);
      if (node.color === "warning") t = C.yellow(t);
      if (node.color === "error") t = C.red(t);
      return indent + t + "\n";
    }
    case "list":
      return node.items.map((it, i) => indent + (node.ordered ? `${i + 1}. ` : "• ") + it + "\n").join("");
    case "table": {
      const rows = [node.columns, ...node.rows].filter((r) => r.length > 0);
      if (rows.length === 0) return "";
      const widths: number[] = [];
      for (const row of rows) row.forEach((cell, i) => (widths[i] = Math.max(widths[i] ?? 0, cell.length)));
      const line = (row: string[], style: (s: string) => string) =>
        indent + style(row.map((cell, i) => cell.padEnd(widths[i] ?? 0)).join("  ")) + "\n";
      let out = node.columns.length > 0 ? line(node.columns, C.bold) : "";
      for (const row of node.rows) out += line(row, (s) => s);
      return out;
    }
    case "code":
      return (
        node.code
          .split("\n")
          .map((l) => indent + C.dim("  " + l) + "\n")
          .join("")
      );
    case "alert": {
      const style = node.level === "error" ? C.red : node.level === "warning" ? C.yellow : node.level === "success" ? C.green : C.orange;
      const head = node.title ? `${node.title}: ` : "";
      return indent + style(`[${node.level}] `) + C.bold(head) + node.text + "\n";
    }
    case "button":
      return renderButton(node, indent);
    case "text_input":
      return indent + C.dim(`✎ ${node.label ?? node.id}${node.placeholder ? ` (e.g. ${node.placeholder})` : ""}`) + "\n";
    case "select":
      return renderOptions(node.label ?? node.id, node.options, indent, false);
    case "checkbox":
      return indent + (node.checked ? "[x] " : "[ ] ") + node.label + "\n";
    case "chip_group":
      return renderOptions(node.id, node.options, indent, node.multi);
    case "progress": {
      const bar =
        node.value === undefined
          ? "░░░░░░░░░░"
          : "█".repeat(Math.round(node.value * 10)).padEnd(10, "░");
      return indent + (node.label ? `${node.label} ` : "") + C.orange(bar) + "\n";
    }
    case "status": {
      const dot = node.state === "success" ? C.green("●") : node.state === "error" ? C.red("●") : node.state === "active" ? C.orange("●") : C.dim("●");
      return indent + dot + " " + node.text + "\n";
    }
    case "countdown": {
      const secs = node.seconds ?? (node.untilMs !== undefined ? Math.max(0, Math.round((node.untilMs - Date.now()) / 1000)) : undefined);
      return indent + C.dim(`⏱ ${node.label ? `${node.label}: ` : ""}${secs !== undefined ? `${secs}s` : "—"}`) + "\n";
    }
    case "unknown":
      return node.text ? indent + node.text + "\n" : "";
  }
}

function renderOptions(label: string, options: UiOption[], indent: string, multi: boolean): string {
  let out = indent + C.dim(`${label}${multi ? " (pick any)" : ""}:`) + "\n";
  for (const o of options) out += indent + `  ◦ ${o.label}` + (o.label === o.id ? "" : C.dim(` (${o.id})`)) + "\n";
  return out;
}

function renderButton(node: ButtonNode, indent: string): string {
  if (node.action === "open_url") {
    return indent + `▸ ${C.bold(node.label)} ${C.dim(`→ ${node.url ?? ""}`)}` + "\n";
  }
  if (node.action === "copy_to_clipboard") {
    return indent + `▸ ${C.bold(node.label)} ${C.dim(`(copy: ${node.text ?? ""})`)}` + "\n";
  }
  // callback: typing the grammar line IS the button press. Show the exact
  // reply; collected fields render as fill-in slots.
  const template =
    node.collectFrom.length === 0
      ? callbackMessage(node, new Map())
      : `Responded with: ${node.event ?? node.label}: ` + node.collectFrom.map((id) => `${id}=…`).join("; ");
  return indent + `▸ ${C.bold(node.label)} ${C.dim(`— reply: ${template}`)}` + "\n";
}

/**
 * Streaming fence filter: feed message.delta text in, get printable text
 * out; ```marmalade-ui fence bodies are held back and rendered natively
 * when the fence closes (or at flush() for a truncated stream — the repair
 * layer renders the surviving prefix). All other text passes through
 * untouched, including other code fences.
 */
export class UiFenceFilter {
  private carry = ""; // incomplete trailing line
  private inUiFence = false;
  private inOtherFence = false;
  private fenceBody: string[] = [];

  feed(chunk: string): string {
    this.carry += chunk;
    let out = "";
    let nl: number;
    while ((nl = this.carry.indexOf("\n")) >= 0) {
      const line = this.carry.slice(0, nl + 1);
      this.carry = this.carry.slice(nl + 1);
      out += this.line(line);
    }
    return out;
  }

  /** End of message: emit any held content (truncated fence renders repaired). */
  flush(): string {
    let out = "";
    if (this.carry.length > 0) out += this.line(this.carry + "\n");
    if (this.inUiFence) out += renderUiFence(this.fenceBody.join(""));
    this.carry = "";
    this.inUiFence = false;
    this.inOtherFence = false;
    this.fenceBody = [];
    return out;
  }

  private line(line: string): string {
    const trimmed = line.trim();
    if (this.inUiFence) {
      if (trimmed === "```") {
        this.inUiFence = false;
        const body = this.fenceBody.join("");
        this.fenceBody = [];
        return renderUiFence(body);
      }
      this.fenceBody.push(line);
      return "";
    }
    if (this.inOtherFence) {
      if (trimmed === "```") this.inOtherFence = false;
      return line;
    }
    if (trimmed.startsWith("```")) {
      if (trimmed.slice(3).trim() === "marmalade-ui") {
        this.inUiFence = true;
        return "";
      }
      this.inOtherFence = true;
    }
    return line;
  }
}
