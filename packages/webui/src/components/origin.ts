// origin.ts — the display marker for a user turn the daemon minted on another
// actor's behalf (pure, socket-free — mirrors cron-format.ts / fork.ts).
//
// Some user messages are not typed by the person at the keyboard: a scheduled
// prompt (source "cron") or a cross-session send/steer from another agent
// (source "agent", device_id "session:<sender>" — minted only daemon-side,
// never spoofable from a client body). Both render with a small marker so the
// reader knows where the turn came from. Human turns (text/voice) get none.

import type { WireOrigin } from "../gateway/types.js";

export interface OriginMarker {
  /** Short chip label ("scheduled", "from session s_abc"). */
  label: string;
  /** Hover title with the fuller explanation. */
  title: string;
}

const SESSION_PREFIX = "session:";

/** Map a stamped origin to its marker, or null for a human-typed turn. */
export function originMarker(origin?: WireOrigin): OriginMarker | null {
  if (!origin) return null;
  switch (origin.source) {
    case "agent": {
      // device_id is "session:<sender>" (identity.ts agentOrigin) — strip the
      // prefix to show the sending session's id.
      const id = origin.device_id?.startsWith(SESSION_PREFIX)
        ? origin.device_id.slice(SESSION_PREFIX.length)
        : origin.device_id ?? "another session";
      return { label: `from session ${id}`, title: `Sent by another session (${id})` };
    }
    case "cron":
      return { label: "scheduled", title: "Sent by a scheduled prompt (cron)" };
    default:
      // text | voice | any future human/self source — no marker.
      return null;
  }
}
