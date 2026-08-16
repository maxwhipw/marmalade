// identity.ts — the message-identity substrate (identity plan P1).
//
// The whole plan in one sentence: IDs are names, not state. Mint each id ONCE,
// never change it, and represent run-progress as a field — never by creating
// or swapping an id.
//
// SessionIdentity is the single stamping seam: the router passes EVERY
// outbound session event through stampEvent(), so identity is structural (the
// policy.ts philosophy) — no adapter or normalizer can forget it, including
// events an adapter constructs itself (e.g. OpenCode's turn-complete). Two id
// spaces stay cleanly separated: clients see only domain ids
// (sessionId/messageId); harness ids are captured privately and never cross
// the gateway.
//
// Ordering (locked decision): `seq` orders — a plain per-session integer, the
// daemon is the single writer. `ts` (UTC ms) is metadata for display and
// durations. NEVER order by wall-clock.

import { randomBytes } from "node:crypto";
import type { JsonRpcEvent } from "@marmalade/protocol";
import type { MessageStore, MessageStatus } from "./message-store.js";

/** Who-and-where a message came from. Derived by the daemon from the
 *  AUTHENTICATED connection — never trusted from the message body (sec-H3). */
export interface Origin {
  userId: string;
  deviceId: string;
  platform: string; // desktop | android | cli | web | daemon | unknown
  /** "cron" is minted ONLY by the daemon's scheduler; "agent" ONLY by the
   *  session-tools seam (send_to_session — one session prompting another).
   *  Neither is ever accepted from a client body — prompt.submit's schema
   *  still allows text|voice only. */
  source: "text" | "voice" | "cron" | "agent";
  /** Sender's UTC offset in minutes at send time (display metadata). */
  tzOffset?: number;
}

/** Compact random message id: 12 base64url chars = 72 random bits. Random,
 *  never positional/derived — derived ids re-tie identity to position, the
 *  very bug this layer removes. */
export function mintMessageId(): string {
  return randomBytes(9).toString("base64url");
}

/** Sanitize a client-DECLARED identity field (deviceId/platform from hello)
 *  at the binding point, before it reaches anything model-facing. These
 *  strings land verbatim in the origin preamble and the list_devices tool
 *  output — i.e. inside the model's context — so a hostile client could
 *  otherwise inject instructions via its declared device name. Conservative
 *  charset, 64-char cap; an all-garbage value collapses to undefined so the
 *  downstream "no declared identity" defaults apply. */
export function sanitizeIdentityField(raw: string | undefined): string | undefined {
  if (raw === undefined) return undefined;
  const clean = raw.replace(/[^A-Za-z0-9 _.:-]/g, "").trim().slice(0, 64);
  return clean.length > 0 ? clean : undefined;
}

/** Origin as it appears on the wire (snake_case, matching the payload style). */
export function wireOrigin(o: Origin): Record<string, unknown> {
  return {
    user_id: o.userId,
    device_id: o.deviceId,
    platform: o.platform,
    source: o.source,
    ...(o.tzOffset === undefined ? {} : { tz_offset: o.tzOffset }),
  };
}

/** The per-turn origin injection (P3): one metadata line prepended to the
 *  prompt the HARNESS sees — never to the transcript (clients render the raw
 *  prompt). This is the dynamic half of device awareness; the static half is
 *  the disposition paragraph in the (cache-shared) system prompt. Includes
 *  the sender's local time when the connection declared a tz offset ("you
 *  sent this at 9pm your time" is context the assistant can actually use). */
export function originPreamble(o: Origin, ts: number): string {
  const parts = [`device "${o.deviceId}" (${o.platform})`, `via ${o.source}`];
  if (o.tzOffset !== undefined) {
    const local = new Date(ts + o.tzOffset * 60_000);
    const hh = String(local.getUTCHours()).padStart(2, "0");
    const mm = String(local.getUTCMinutes()).padStart(2, "0");
    const sign = o.tzOffset < 0 ? "-" : "+";
    const abs = Math.abs(o.tzOffset);
    parts.push(`sender local time ${hh}:${mm} (UTC${sign}${String(Math.trunc(abs / 60)).padStart(2, "0")}:${String(abs % 60).padStart(2, "0")})`);
  }
  return `[turn origin — ${parts.join(", ")}]`;
}

export interface IdentityDeps {
  store: MessageStore;
  now: () => number;
  /** Continue after the highest seq ever issued for this session (resume /
   *  daemon restart) — seq must never go backward. 0 for a fresh session. */
  startSeq?: number;
}

export class SessionIdentity {
  private seq: number;
  /** Origin of the current turn's user message. Assistant messages inherit it
   *  (device targeting: "act on the device this turn came from"). */
  private turnOrigin: Origin;
  private lastUserMessageId: string | null = null;
  /** The currently-streaming assistant message id, if any. */
  private open: string | null = null;
  /** tool_use id → the assistant message that issued it + start ts (durations). */
  private tools = new Map<string, { messageId: string; startedTs: number }>();

  constructor(
    private sessionId: string,
    /** Origin of the connection that created/resumed the session — used for
     *  assistant events that precede any user message this run. */
    defaultOrigin: Origin,
    private deps: IdentityDeps,
  ) {
    this.seq = deps.startSeq ?? 0;
    this.turnOrigin = defaultOrigin;
  }

  private nextSeq(): number {
    return ++this.seq;
  }

  /** Origin of the turn currently in progress (the last accepted user
   *  message, or the connection default before any). The session-tools loop
   *  guard reads this: a turn whose source is "agent" may not itself send
   *  agent prompts — chains stop at one hop. */
  currentTurnOrigin(): Origin {
    return this.turnOrigin;
  }

  /** Mint + persist the identity of an accepted user prompt. Origin comes from
   *  the authenticated connection (the router derives it) — by the time it
   *  reaches here it is already trustworthy. */
  beginUserMessage(origin: Origin, opts?: { steered?: boolean }): { messageId: string; seq: number; ts: number } {
    const messageId = mintMessageId();
    const seq = this.nextSeq();
    const ts = this.deps.now();
    this.turnOrigin = origin;
    this.lastUserMessageId = messageId;
    this.deps.store.insert({
      messageId, sessionId: this.sessionId, role: "user", parentMessageId: null,
      origin, seq, startedAt: ts, endedAt: ts, status: "complete",
      // Steer rows are marked so undo can tell turn STARTS (steered=false)
      // from mid-turn injections when popping the last turn.
      steered: opts?.steered ?? false,
    });
    return { messageId, seq, ts };
  }

  /** Capture the harness's own uuid for the streaming assistant message.
   *  PRIVATE mapping only — it never appears on a gateway event. */
  captureHarnessUuid(uuid: string): void {
    if (this.open) this.deps.store.bindHarnessUuid(this.open, uuid);
  }

  /** Close the open assistant message (turn complete / interrupt / error).
   *  The id persists; only status + ended_at record what happened. */
  closeOpen(status: MessageStatus): string | null {
    if (!this.open) return null;
    const id = this.open;
    this.open = null;
    this.deps.store.setStatus(id, status, this.deps.now());
    return id;
  }

  private beginAssistantMessage(): { messageId: string; seq: number; ts: number } {
    // A new harness response while one is still streaming (the agent loop:
    // text → tool → more text arrives as multiple message_starts) — the prior
    // message finished streaming; close it complete before opening the next.
    this.closeOpen("complete");
    const messageId = mintMessageId();
    const seq = this.nextSeq();
    const ts = this.deps.now();
    this.open = messageId;
    this.deps.store.insert({
      messageId, sessionId: this.sessionId, role: "assistant",
      parentMessageId: this.lastUserMessageId, origin: this.turnOrigin,
      seq, startedAt: ts, endedAt: null, status: "streaming",
    });
    return { messageId, seq, ts };
  }

  /**
   * THE stamping seam. Every session-scoped event passes through here exactly
   * once (the router applies it to the adapter's onEvent stream and to events
   * it emits itself). Returns 1–2 events: a synthesized message.start is
   * prepended when a delta/tool event arrives with no open assistant message
   * (ACP streams have no explicit message_start).
   *
   * Stamps are ADDITIVE payload fields: message_id, parent_message_id, seq,
   * ts, origin, duration_ms — legacy clients ignore unknown keys.
   */
  stampEvent(ev: JsonRpcEvent): JsonRpcEvent[] {
    const out: JsonRpcEvent[] = [];
    const stamp = (extra: Record<string, unknown>): JsonRpcEvent => ({
      ...ev,
      params: {
        ...ev.params,
        payload: { ...((ev.params.payload as Record<string, unknown>) ?? {}), ...extra },
      },
    });
    const ensureOpen = (): string => {
      if (this.open) return this.open;
      const started = this.beginAssistantMessage();
      out.push({
        jsonrpc: "2.0",
        method: "event",
        params: {
          type: "message.start",
          payload: this.startPayload(started),
          ...(ev.params.session_id !== undefined ? { session_id: ev.params.session_id } : {}),
        },
      });
      return started.messageId;
    };

    switch (ev.params.type) {
      case "message.start": {
        out.push(stamp(this.startPayload(this.beginAssistantMessage())));
        break;
      }

      case "message.delta":
      case "thinking.delta":
      case "reasoning.delta": {
        const messageId = ensureOpen();
        out.push(stamp({ message_id: messageId, seq: this.nextSeq(), ts: this.deps.now() }));
        break;
      }

      case "message.complete": {
        const payload = (ev.params.payload as { is_error?: boolean } | undefined) ?? {};
        const messageId = this.closeOpen(payload.is_error ? "error" : "complete");
        out.push(stamp({
          ...(messageId ? {
            message_id: messageId,
            // has_cut_point: whether this assistant message holds a PRIVATE
            // harness cut-point, i.e. session.fork can cut here. False for
            // harnesses with no per-message uuids (ACP) and overridden to
            // false on fork-copied events (copyForkHistory) — so clients can
            // hide the branch affordance instead of offering a dead end.
            // The uuid itself stays private (two-id-spaces rule).
            has_cut_point: this.deps.store.get(messageId)?.harnessMessageUuid != null,
          } : {}),
          seq: this.nextSeq(), ts: this.deps.now(), origin: wireOrigin(this.turnOrigin),
        }));
        break;
      }

      case "tool.start": {
        const messageId = ensureOpen();
        const toolUseId = (ev.params.payload as { id?: string } | undefined)?.id;
        const ts = this.deps.now();
        if (toolUseId) this.tools.set(toolUseId, { messageId, startedTs: ts });
        out.push(stamp({ message_id: messageId, seq: this.nextSeq(), ts, origin: wireOrigin(this.turnOrigin) }));
        break;
      }

      case "tool.progress":
      case "tool.complete": {
        const toolUseId = (ev.params.payload as { tool_use_id?: string } | undefined)?.tool_use_id;
        const entry = toolUseId ? this.tools.get(toolUseId) : undefined;
        const ts = this.deps.now();
        if (ev.params.type === "tool.complete" && toolUseId) this.tools.delete(toolUseId);
        out.push(stamp({
          ...(entry ? { message_id: entry.messageId } : {}),
          seq: this.nextSeq(), ts,
          ...(ev.params.type === "tool.complete" && entry ? { duration_ms: ts - entry.startedTs } : {}),
        }));
        break;
      }

      default:
        // Session-scoped but not message-scoped (session.info, status.update,
        // error, …): still ordered + timed.
        out.push(stamp({ seq: this.nextSeq(), ts: this.deps.now() }));
    }
    return out;
  }

  private startPayload(started: { messageId: string; seq: number; ts: number }): Record<string, unknown> {
    return {
      message_id: started.messageId,
      ...(this.lastUserMessageId ? { parent_message_id: this.lastUserMessageId } : {}),
      seq: started.seq,
      ts: started.ts,
      origin: wireOrigin(this.turnOrigin),
    };
  }
}
