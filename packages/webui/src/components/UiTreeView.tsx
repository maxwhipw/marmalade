// UiTreeView.tsx — React renderer for Marmalade UI v1 node trees on the
// marmalade token layer (spec: daemon repo docs/dynamic-ui/marmalade-ui-v1.md;
// step 4 of the dynamic-UI rollout). Parsing/grammar live in
// @marmalade/ui-tree; this file is pure rendering + local input state.
//
// Interaction contract: inputs hold LOCAL state only; a callback button
// synthesizes a plain user message (callbackMessage) through onCallback —
// the caller sends it via the normal prompt.submit path. open_url and
// copy_to_clipboard are the only escape hatches; open_url is scheme-guarded
// the same way markdown links are.

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import {
  callbackMessage,
  type ButtonNode,
  type UiNode,
  type UiOption,
} from "@marmalade/ui-tree";
import { highlight } from "./markdown.js";

interface Props {
  node: UiNode;
  /** Sends the synthesized plain-text response through the chat send path. */
  onCallback: (text: string) => void;
}

export function UiTreeView({ node, onCallback }: Props): ReactNode {
  // One flat values map for the whole tree — input node id → string value
  // (checkbox: "true"/"false"; multi chip_group: comma-joined ids), exactly
  // what the response grammar consumes.
  const [values, setValues] = useState<ReadonlyMap<string, string>>(() => seedValues(node));
  const setValue = (id: string, value: string) =>
    setValues((prev) => new Map(prev).set(id, value));

  return (
    <div className="mm-ui-tree">
      <Node node={node} values={values} setValue={setValue} onCallback={onCallback} />
    </div>
  );
}

/** Initial values: text_input's `value`, checkbox's `checked` — so a callback
 *  pressed with nothing touched still collects the rendered defaults. */
function seedValues(root: UiNode): Map<string, string> {
  const map = new Map<string, string>();
  const walk = (n: UiNode) => {
    if (n.kind === "text_input") map.set(n.id, n.value ?? "");
    if (n.kind === "checkbox") map.set(n.id, n.checked ? "true" : "false");
    if (n.kind === "column" || n.kind === "row" || n.kind === "card") n.children.forEach(walk);
  };
  walk(root);
  return map;
}

interface NodeProps {
  node: UiNode;
  values: ReadonlyMap<string, string>;
  setValue: (id: string, value: string) => void;
  onCallback: (text: string) => void;
}

function Node({ node, values, setValue, onCallback }: NodeProps): ReactNode {
  const kids = (children: UiNode[]) =>
    children.map((c, i) => (
      <Node key={i} node={c} values={values} setValue={setValue} onCallback={onCallback} />
    ));

  switch (node.kind) {
    case "column":
      return <div className="mm-ui-column">{kids(node.children)}</div>;
    case "row":
      return <div className="mm-ui-row">{kids(node.children)}</div>;
    case "card":
      return (
        <div className="mm-ui-card">
          {node.title && <div className="mm-ui-card-title">{node.title}</div>}
          {kids(node.children)}
        </div>
      );
    case "divider":
      return <hr className="mm-ui-divider" />;
    case "text":
      return (
        <div
          className={`mm-ui-text style-${node.style} color-${node.color}`}
          style={node.bold ? { fontWeight: 700 } : undefined}
        >
          {node.text}
        </div>
      );
    case "list":
      return node.ordered ? (
        <ol className="mm-ui-list">{node.items.map((it, i) => <li key={i}>{it}</li>)}</ol>
      ) : (
        <ul className="mm-ui-list">{node.items.map((it, i) => <li key={i}>{it}</li>)}</ul>
      );
    case "table":
      return (
        <table className="mm-ui-table">
          {node.columns.length > 0 && (
            <thead>
              <tr>{node.columns.map((c, i) => <th key={i}>{c}</th>)}</tr>
            </thead>
          )}
          <tbody>
            {node.rows.map((row, i) => (
              <tr key={i}>{row.map((cell, j) => <td key={j}>{cell}</td>)}</tr>
            ))}
          </tbody>
        </table>
      );
    case "code":
      return (
        <pre className="mm-code">
          <code
            className="hljs"
            dangerouslySetInnerHTML={{ __html: highlight(node.code, node.language) }}
          />
        </pre>
      );
    case "alert":
      return (
        <div className={`mm-ui-alert level-${node.level}`}>
          {node.title && <div className="mm-ui-alert-title">{node.title}</div>}
          {node.text}
        </div>
      );
    case "button":
      return <UiButton node={node} values={values} onCallback={onCallback} />;
    case "text_input":
      return (
        <label className="mm-ui-field">
          {node.label && <span className="mm-ui-label">{node.label}</span>}
          <input
            type="text"
            value={values.get(node.id) ?? ""}
            placeholder={node.placeholder}
            onChange={(e) => setValue(node.id, e.target.value)}
          />
        </label>
      );
    case "select":
      return (
        <label className="mm-ui-field">
          {node.label && <span className="mm-ui-label">{node.label}</span>}
          <select
            value={values.get(node.id) ?? ""}
            onChange={(e) => setValue(node.id, e.target.value)}
          >
            <option value="" disabled>
              choose…
            </option>
            {node.options.map((o) => (
              <option key={o.id} value={o.id}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
      );
    case "checkbox":
      return (
        <label className="mm-ui-check">
          <input
            type="checkbox"
            checked={values.get(node.id) === "true"}
            onChange={(e) => setValue(node.id, e.target.checked ? "true" : "false")}
          />
          {node.label}
        </label>
      );
    case "chip_group":
      return (
        <ChipGroup
          id={node.id}
          options={node.options}
          multi={node.multi}
          value={values.get(node.id) ?? ""}
          setValue={setValue}
        />
      );
    case "progress":
      return (
        <div className="mm-ui-progress">
          {node.label && <span className="mm-ui-label">{node.label}</span>}
          <progress value={node.value} max={1} />
        </div>
      );
    case "status":
      return (
        <div className={`mm-ui-status state-${node.state}`}>
          <span className="mm-ui-status-dot" />
          {node.text}
        </div>
      );
    case "countdown":
      return <Countdown untilMs={node.untilMs} seconds={node.seconds} label={node.label} />;
    case "unknown":
      // Degrade to readable text — never an error card (spec §Transport).
      return node.text ? <div className="mm-ui-text style-body">{node.text}</div> : null;
  }
}

function UiButton({
  node,
  values,
  onCallback,
}: {
  node: ButtonNode;
  values: ReadonlyMap<string, string>;
  onCallback: (text: string) => void;
}): ReactNode {
  const cls =
    node.variant === "danger"
      ? "mm-btn outline mm-ui-danger"
      : node.variant === "secondary"
        ? "mm-btn ghost"
        : "mm-btn accent";
  const onClick = () => {
    switch (node.action) {
      case "open_url": {
        const url = (node.url ?? "").trim();
        if (/^https?:/i.test(url)) window.open(url, "_blank", "noopener,noreferrer");
        break;
      }
      case "copy_to_clipboard":
        void navigator.clipboard.writeText(node.text ?? "");
        break;
      default:
        onCallback(callbackMessage(node, values));
    }
  };
  return (
    <button className={cls} onClick={onClick}>
      {node.label}
    </button>
  );
}

function ChipGroup({
  id,
  options,
  multi,
  value,
  setValue,
}: {
  id: string;
  options: UiOption[];
  multi: boolean;
  value: string;
  setValue: (id: string, value: string) => void;
}): ReactNode {
  const selected = new Set(value ? value.split(",") : []);
  const toggle = (optionId: string) => {
    if (multi) {
      const next = new Set(selected);
      if (next.has(optionId)) next.delete(optionId);
      else next.add(optionId);
      // Preserve option order in the comma-joined value (response grammar).
      setValue(id, options.filter((o) => next.has(o.id)).map((o) => o.id).join(","));
    } else {
      setValue(id, optionId);
    }
  };
  return (
    <div className="mm-chips">
      {options.map((o) => (
        <button
          key={o.id}
          className="mm-chip"
          aria-pressed={selected.has(o.id)}
          onClick={() => toggle(o.id)}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

function Countdown({
  untilMs,
  seconds,
  label,
}: {
  untilMs?: number;
  seconds?: number;
  label?: string;
}): ReactNode {
  // `until` is an absolute deadline; bare `seconds` counts from first render.
  const [deadline] = useState(() => untilMs ?? Date.now() + (seconds ?? 0) * 1000);
  const [remaining, setRemaining] = useState(() => Math.max(0, deadline - Date.now()));
  useEffect(() => {
    if (remaining <= 0) return;
    const t = setInterval(() => setRemaining(Math.max(0, deadline - Date.now())), 1000);
    return () => clearInterval(t);
  }, [deadline, remaining <= 0]);
  const total = Math.round(remaining / 1000);
  const mins = Math.floor(total / 60);
  const secs = total % 60;
  return (
    <div className="mm-ui-countdown">
      {label && <span className="mm-ui-label">{label}</span>}
      <span className="mm-ui-countdown-time">
        {mins}:{String(secs).padStart(2, "0")}
      </span>
    </div>
  );
}
