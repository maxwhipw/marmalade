// Marmalade UI v1 — the node hierarchy + tolerant builder (dynamic-UI v2).
// Vocabulary + interaction contract: docs/dynamic-ui/marmalade-ui-v1.md
// (+ the JSON Schema next to it) — the language-neutral truth all renderers
// cite. This is the TS twin of the Android client's UiNode.kt.
//
// Builders are TOLERANT by design (Kai's model): every field falls back to a
// default when missing/mis-typed, so a truncated-and-repaired node still
// renders. An unknown `type` becomes UnknownNode and degrades to its best
// text content — never an error card.

export interface ColumnNode {
  kind: "column";
  children: UiNode[];
}
export interface RowNode {
  kind: "row";
  children: UiNode[];
}
export interface CardNode {
  kind: "card";
  title?: string;
  children: UiNode[];
}
export interface DividerNode {
  kind: "divider";
}

export interface TextNode {
  kind: "text";
  text: string;
  style: string; // headline | title | body | caption
  bold: boolean;
  color: string; // default | primary | success | warning | error
}
export interface ListNode {
  kind: "list";
  items: string[];
  ordered: boolean;
}
export interface TableNode {
  kind: "table";
  columns: string[];
  rows: string[][];
}
export interface CodeNode {
  kind: "code";
  code: string;
  language?: string;
}
export interface AlertNode {
  kind: "alert";
  text: string;
  level: string; // info | success | warning | error
  title?: string;
}

export interface ButtonNode {
  kind: "button";
  label: string;
  action: string; // callback | open_url | copy_to_clipboard
  event?: string;
  collectFrom: string[];
  url?: string;
  text?: string;
  variant: string; // primary | secondary | danger
}
export interface TextInputNode {
  kind: "text_input";
  id: string;
  label?: string;
  placeholder?: string;
  value?: string;
}
export interface UiOption {
  id: string;
  label: string;
}
export interface SelectNode {
  kind: "select";
  id: string;
  label?: string;
  options: UiOption[];
}
export interface CheckboxNode {
  kind: "checkbox";
  id: string;
  label: string;
  checked: boolean;
}
export interface ChipGroupNode {
  kind: "chip_group";
  id: string;
  options: UiOption[];
  multi: boolean;
}

export interface ProgressNode {
  kind: "progress";
  value?: number; // 0..1, absent = indeterminate
  label?: string;
}
export interface StatusNode {
  kind: "status";
  text: string;
  state: string; // pending | active | success | error
}
export interface CountdownNode {
  kind: "countdown";
  untilMs?: number;
  seconds?: number;
  label?: string;
}

/** Unrecognized type — degrades to readable text, never an error. */
export interface UnknownNode {
  kind: "unknown";
  type: string;
  text?: string;
}

export type UiNode =
  | ColumnNode
  | RowNode
  | CardNode
  | DividerNode
  | TextNode
  | ListNode
  | TableNode
  | CodeNode
  | AlertNode
  | ButtonNode
  | TextInputNode
  | SelectNode
  | CheckboxNode
  | ChipGroupNode
  | ProgressNode
  | StatusNode
  | CountdownNode
  | UnknownNode;

// ── Tolerant field readers ───────────────────────────────────────────────────

type JsonObj = Record<string, unknown>;

function isObj(v: unknown): v is JsonObj {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/** String coercion matching the Android reader: strings pass through, numbers
 *  and booleans stringify, everything else is absent. */
function str(obj: JsonObj, key: string): string | undefined {
  const v = obj[key];
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return undefined;
}

function bool(obj: JsonObj, key: string): boolean | undefined {
  const v = obj[key];
  return typeof v === "boolean" ? v : undefined;
}

function num(obj: JsonObj, key: string): number | undefined {
  const v = obj[key];
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
}

function strings(obj: JsonObj, key: string): string[] {
  const v = obj[key];
  if (!Array.isArray(v)) return [];
  return v.flatMap((el) =>
    typeof el === "string" ? [el] : typeof el === "number" || typeof el === "boolean" ? [String(el)] : [],
  );
}

function childNodes(obj: JsonObj): UiNode[] {
  const v = obj["children"];
  if (!Array.isArray(v)) return [];
  return v.flatMap((el) => {
    const node = buildUiNode(el);
    return node ? [node] : [];
  });
}

function options(obj: JsonObj, key = "options"): UiOption[] {
  const v = obj[key];
  if (!Array.isArray(v)) return [];
  return v.flatMap((el): UiOption[] => {
    if (typeof el === "string") return [{ id: el, label: el }];
    if (isObj(el)) {
      const id = str(el, "id") ?? str(el, "label");
      if (!id) return [];
      return [{ id, label: str(el, "label") ?? id }];
    }
    return [];
  });
}

/**
 * Build one UiNode from parsed JSON. Returns null only when the element is
 * not an object, has no usable `type`, or is an input without an id (an
 * uncollectable input is dropped) — every recognized type builds SOMETHING
 * (per-field defaults for whatever the repair pass lost).
 */
export function buildUiNode(element: unknown): UiNode | null {
  if (!isObj(element)) return null;
  const obj = element;
  const type = str(obj, "type");
  switch (type) {
    case "column":
      return { kind: "column", children: childNodes(obj) };
    case "row":
      return { kind: "row", children: childNodes(obj) };
    case "card":
      return { kind: "card", title: str(obj, "title"), children: childNodes(obj) };
    case "divider":
      return { kind: "divider" };
    case "text":
      return {
        kind: "text",
        text: str(obj, "text") ?? "",
        style: str(obj, "style") ?? "body",
        bold: bool(obj, "bold") ?? false,
        color: str(obj, "color") ?? "default",
      };
    case "list":
      return { kind: "list", items: strings(obj, "items"), ordered: bool(obj, "ordered") ?? false };
    case "table": {
      const rowsRaw = obj["rows"];
      const rows = Array.isArray(rowsRaw)
        ? rowsRaw.flatMap((row) => (Array.isArray(row) ? [row.flatMap((c) => (typeof c === "string" ? [c] : typeof c === "number" || typeof c === "boolean" ? [String(c)] : []))] : []))
        : [];
      return { kind: "table", columns: strings(obj, "columns"), rows };
    }
    case "code":
      return { kind: "code", code: str(obj, "code") ?? "", language: str(obj, "language") };
    case "alert":
      return {
        kind: "alert",
        text: str(obj, "text") ?? "",
        level: str(obj, "level") ?? "info",
        title: str(obj, "title"),
      };
    case "button":
      return {
        kind: "button",
        label: str(obj, "label") ?? "OK",
        action: str(obj, "action") ?? "callback",
        event: str(obj, "event"),
        collectFrom: strings(obj, "collect_from"),
        url: str(obj, "url"),
        text: str(obj, "text"),
        variant: str(obj, "variant") ?? "primary",
      };
    case "text_input": {
      const id = str(obj, "id");
      if (!id) return null; // an input without an id is uncollectable
      return { kind: "text_input", id, label: str(obj, "label"), placeholder: str(obj, "placeholder"), value: str(obj, "value") };
    }
    case "select": {
      const id = str(obj, "id");
      if (!id) return null;
      return { kind: "select", id, label: str(obj, "label"), options: options(obj) };
    }
    case "checkbox": {
      const id = str(obj, "id");
      if (!id) return null;
      return { kind: "checkbox", id, label: str(obj, "label") ?? "", checked: bool(obj, "checked") ?? false };
    }
    case "chip_group": {
      const id = str(obj, "id");
      if (!id) return null;
      return { kind: "chip_group", id, options: options(obj), multi: bool(obj, "multi") ?? false };
    }
    case "progress": {
      const raw = num(obj, "value");
      return { kind: "progress", value: raw === undefined ? undefined : Math.min(1, Math.max(0, raw)), label: str(obj, "label") };
    }
    case "status":
      return { kind: "status", text: str(obj, "text") ?? "", state: str(obj, "state") ?? "pending" };
    case "countdown":
      return { kind: "countdown", untilMs: num(obj, "until"), seconds: num(obj, "seconds"), label: str(obj, "label") };
    case undefined:
      return null;
    default:
      return { kind: "unknown", type, text: str(obj, "text") ?? str(obj, "label") ?? str(obj, "title") };
  }
}
