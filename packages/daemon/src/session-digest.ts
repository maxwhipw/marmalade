// session-digest.ts — render a session's recent turns as readable text for
// the cross-session toolset (get_session_turns) and the watch digests.
//
// PURE: input is the transcript cache's replayed events; output is a string
// destined for another model's context. Tool calls and thinking are OPT-IN —
// the default view is the conversation a human would read. Everything is
// capped: this feeds a context window, not a pager.

import type { JsonRpcEvent } from "@marmalade/protocol";

export interface DigestOptions {
  /** How many trailing turns to render (a turn = a non-steer user message and
   *  everything until the next one). */
  turns: number;
  includeToolCalls: boolean;
  includeThinking: boolean;
}

/** Per-block char cap — one message never dominates the digest. */
const BLOCK_CAP = 2000;
/** Whole-digest char cap — the hard ceiling on what one tool call injects. */
const TOTAL_CAP = 24_000;

interface Item {
  kind: "user" | "steer" | "assistant" | "thinking" | "tool";
  text: string;
  ts?: number;
  source?: string;
}

function cap(text: string, limit: number): string {
  const t = text.trim();
  return t.length <= limit ? t : `${t.slice(0, limit)}… (+${t.length - limit} chars)`;
}

function payload(ev: JsonRpcEvent): Record<string, unknown> {
  return (ev.params.payload as Record<string, unknown>) ?? {};
}

/** Render the last `opts.turns` turns of a session from its replayed
 *  transcript events. Returns "" for a session with no conversation. */
export function renderSessionTurns(events: JsonRpcEvent[], opts: DigestOptions): string {
  // Pass 1: fold the event stream into ordered items, accumulating assistant
  // text (message.delta per message_id) and thinking alongside.
  const items: Item[] = [];
  const assistantByMessage = new Map<string, Item>();
  const thinkingByMessage = new Map<string, Item>();

  for (const ev of events) {
    const p = payload(ev);
    const messageId = typeof p.message_id === "string" ? p.message_id : undefined;
    switch (ev.params.type) {
      case "message.user": {
        const origin = p.origin as { source?: string; device_id?: string } | undefined;
        items.push({
          kind: p.steered === true ? "steer" : "user",
          text: String(p.text ?? ""),
          ts: typeof p.ts === "number" ? p.ts : undefined,
          source: origin?.source,
        });
        break;
      }
      case "message.delta": {
        if (!messageId) break;
        let item = assistantByMessage.get(messageId);
        if (!item) {
          item = { kind: "assistant", text: "" };
          assistantByMessage.set(messageId, item);
          items.push(item);
        }
        item.text += String(p.text ?? "");
        break;
      }
      case "thinking.delta":
      case "reasoning.delta": {
        if (!messageId) break;
        let item = thinkingByMessage.get(messageId);
        if (!item) {
          item = { kind: "thinking", text: "" };
          thinkingByMessage.set(messageId, item);
          items.push(item);
        }
        item.text += String(p.text ?? "");
        break;
      }
      case "message.complete": {
        // Authoritative final text — used only when no deltas accumulated for
        // this message (e.g. a transcript cached without partial streaming).
        if (messageId && typeof p.text === "string" && !assistantByMessage.get(messageId)?.text) {
          let item = assistantByMessage.get(messageId);
          if (!item) {
            item = { kind: "assistant", text: "" };
            assistantByMessage.set(messageId, item);
            items.push(item);
          }
          item.text = p.text;
        }
        break;
      }
      case "tool.start": {
        let input = "";
        try { input = JSON.stringify(p.input) ?? ""; } catch { input = String(p.input); }
        items.push({ kind: "tool", text: `${String(p.name ?? "tool")} ${cap(input, 300)}` });
        break;
      }
      default:
        break;
    }
  }

  // Pass 2: split into turns at non-steer user messages, keep the tail.
  const turns: Item[][] = [];
  let current: Item[] | null = null;
  for (const item of items) {
    if (item.kind === "user") {
      current = [item];
      turns.push(current);
    } else if (current) {
      current.push(item);
    }
    // Items before any user message (preamble events) are dropped — a turn
    // digest starts at a prompt.
  }
  const tail = turns.slice(-Math.max(1, opts.turns));
  if (tail.length === 0) return "";

  // Pass 3: render.
  const lines: string[] = [];
  for (const turn of tail) {
    const head = turn[0];
    const when = head.ts ? new Date(head.ts).toISOString() : "";
    const via = head.source && head.source !== "text" ? ` via ${head.source}` : "";
    lines.push(`--- turn${when ? ` at ${when}` : ""}${via} ---`);
    for (const item of turn) {
      if (item.kind === "thinking" && !opts.includeThinking) continue;
      if (item.kind === "tool" && !opts.includeToolCalls) continue;
      const body = item.kind === "tool" ? item.text : cap(item.text, BLOCK_CAP);
      if (!body) continue;
      lines.push(`[${item.kind}] ${body}`);
    }
    lines.push("");
  }
  const out = lines.join("\n").trim();
  return out.length <= TOTAL_CAP
    ? out
    : `${out.slice(out.length - TOTAL_CAP)}\n\n[digest truncated to the most recent ${TOTAL_CAP} chars]`;
}
