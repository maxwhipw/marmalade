// acp-normalize.ts — map ACP session updates onto the SAME frozen gateway
// event vocabulary the Claude normalizer produces (normalize.ts).
//
// This is the proof that the HarnessAdapter seam is harness-NEUTRAL: two
// entirely different harness message formats (Agent SDK messages vs ACP
// SessionUpdate) converge on one orchestration vocabulary. The router, clients,
// and transcript cache never learn which harness produced an event.

import { makeEvent, type JsonRpcEvent } from "@marmalade/protocol";

export interface AcpNormalized {
  events: JsonRpcEvent[];
  /** Token usage from a usage_update, accumulated by the adapter. */
  usage?: { inputTokens: number; outputTokens: number };
}

interface SessionUpdate {
  sessionUpdate: string;
  content?: { type?: string; text?: string };
  toolCallId?: string;
  title?: string;
  kind?: string;
  status?: string;
  usage?: { inputTokens?: number; outputTokens?: number; promptTokens?: number; completionTokens?: number };
}

/** Normalize one ACP SessionUpdate into gateway events (+ optional usage). */
export function normalizeAcp(update: SessionUpdate, sessionId: string): AcpNormalized {
  const out: AcpNormalized = { events: [] };

  switch (update.sessionUpdate) {
    case "agent_message_chunk":
      if (update.content?.type === "text" && update.content.text) {
        out.events.push(makeEvent("message.delta", { text: update.content.text }, sessionId));
      }
      break;

    case "agent_thought_chunk":
      if (update.content?.text) {
        out.events.push(makeEvent("thinking.delta", { text: update.content.text }, sessionId));
      }
      break;

    case "tool_call":
      out.events.push(
        makeEvent("tool.start", { id: update.toolCallId, name: update.title ?? update.kind }, sessionId),
      );
      break;

    case "tool_call_update":
      if (update.status === "completed" || update.status === "failed") {
        out.events.push(makeEvent("tool.complete", { tool_use_id: update.toolCallId, status: update.status }, sessionId));
      } else {
        out.events.push(makeEvent("tool.progress", { tool_use_id: update.toolCallId }, sessionId));
      }
      break;

    case "usage_update":
      out.usage = {
        inputTokens: update.usage?.inputTokens ?? update.usage?.promptTokens ?? 0,
        outputTokens: update.usage?.outputTokens ?? update.usage?.completionTokens ?? 0,
      };
      break;

    // plan / available_commands_update / current_mode_update / etc: no gateway
    // event in v0.1 — forward-compatible, silently ignored.
  }

  return out;
}
